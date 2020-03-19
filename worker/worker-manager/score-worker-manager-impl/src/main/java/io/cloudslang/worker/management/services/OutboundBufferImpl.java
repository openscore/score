/*
 * Copyright © 2014-2017 EntIT Software LLC, a Micro Focus company (L.P.)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudslang.worker.management.services;

import ch.lambdaj.group.Group;
import io.cloudslang.engine.queue.entities.ExecutionMessage;
import io.cloudslang.orchestrator.entities.Message;
import io.cloudslang.orchestrator.services.OrchestratorDispatcherService;
import io.cloudslang.worker.management.ExecutionsActivityListener;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static ch.lambdaj.Lambda.by;
import static ch.lambdaj.Lambda.extract;
import static ch.lambdaj.Lambda.group;
import static ch.lambdaj.Lambda.on;
import static org.apache.commons.lang.Validate.notEmpty;

public class OutboundBufferImpl implements OutboundBuffer, WorkerRecoveryListener {

    private static final Logger logger = Logger.getLogger(OutboundBufferImpl.class);
    private static long GB = 900000000; //there is JVM overhead, so i will take 10% buffer...

    @Autowired
    private RetryTemplate retryTemplate;

    @Autowired
    private WorkerRecoveryManager recoveryManager;

    @Autowired
    private OrchestratorDispatcherService dispatcherService;

    @Resource
    private String workerUuid;

    @Autowired
    private SynchronizationManager syncManager;

    @Autowired(required = false)
    private ExecutionsActivityListener executionsActivityListener;

    private List<Message> buffer = new ArrayList<>();

    private int currentWeight;
    private int maxBufferWeight = Integer.getInteger("out.buffer.max.buffer.weight", 30000);
    private int maxBulkWeight = Integer.getInteger("out.buffer.max.bulk.weight", 1500);
    private int retryAmount = Integer.getInteger("out.buffer.retry.number", 5);
    private long retryDelay = Long.getLong("out.buffer.retry.delay", 5000);

    @PostConstruct
    public void init() {
        maxBufferWeight = Integer.getInteger("out.buffer.max.buffer.weight", defaultBufferCapacity());
        logger.info("maxBufferWeight = " + maxBufferWeight);
    }

    @Override
    public void put(final Message... messages) throws InterruptedException {
        notEmpty(messages, "messages is null or empty");
        try {
            syncManager.startPutMessages();

            // We need to check if the current thread was interrupted while waiting for the lock (ExecutionThread or InBufferThread in ackMessages)
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread was interrupted while waiting on the lock! Exiting...");
            }

            while (currentWeight >= maxBufferWeight) {
                logger.info("Outbound buffer is full. Waiting...");
                syncManager.waitForDrain();
                logger.info("Outbound buffer drained. Finished waiting.");
            }

            // In case of multiple messages create a single compound message
            // to make sure that it will be processed in a single transaction
            Message message = messages.length == 1 ? messages[0] : new CompoundMessage(messages);

            // Put message into the buffer
            buffer.add(message);

            currentWeight += message.getWeight();
        } catch (InterruptedException ex) {
            logger.warn("Buffer put action was interrupted", ex);
            throw ex;
        } finally {
            syncManager.finishPutMessages();
        }
    }

    @Override
    public void drain() {
        List<Message> bufferToDrain;
        try {
            syncManager.startDrain();
            while (buffer.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("buffer is empty. Waiting to drain...");
                }
                syncManager.waitForMessages();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("buffer is going to be drained. " + getStatus());
            }

            bufferToDrain = buffer;
            buffer = new ArrayList<>();
            currentWeight = 0;
        } catch (InterruptedException e) {
            logger.warn("Drain outgoing buffer was interrupted while waiting for messages on the buffer");
            return;
        } finally {
            syncManager.finishDrain();
        }

        drainInternal(bufferToDrain);
    }

    private void drainInternal(List<Message> bufferToDrain) {
        List<Message> bulk = new ArrayList<>();
        int bulkWeight = 0;
        try {
            for (Message message : bufferToDrain) {
                if (message.getClass().equals(CompoundMessage.class)) {
                    bulk.addAll(((CompoundMessage) message).asList());
                } else {
                    bulk.add(message);
                }
                bulkWeight += message.getWeight();

                if (bulkWeight > maxBulkWeight) {
                    drainBulk(bulk);
                    bulk.clear();
                    bulkWeight = 0;
                }
            }

            drainBulk(bulk);
        } catch (Exception ex) {
            logger.error("Failed to drain buffer, invoking worker internal recovery... ", ex);
            recoveryManager.doRecovery();
        }
    }

    private List<Message> optimize(List<Message> messages) {
        List<Message> result = new ArrayList<>();

        Group<Message> groups = group(messages, by(on(Message.class).getId()));
        for (Group<Message> group : groups.subgroups()) {
            result.addAll(group.first().shrink(group.findAll()));
        }
        return result;
    }

    private void drainBulk(List<Message> bulkToDrain) {
        final List<Message> optimizedBulk = optimize(bulkToDrain);
        //Bulk number is the same for all retries! This is done to prevent duplications when we insert with retries
        final String bulkNumber = UUID.randomUUID().toString();

        retryTemplate.retry(retryAmount, retryDelay, new RetryTemplate.RetryCallback() {
            @Override
            public void tryOnce() {
                String wrv = recoveryManager.getWRV();
                dispatcherService.dispatch(optimizedBulk, bulkNumber, wrv, workerUuid);
                if (executionsActivityListener != null) {
                    executionsActivityListener
                            .onHalt(extract(optimizedBulk, on(ExecutionMessage.class).getExecStateId()));
                }
            }
        });

    }

    @Override
    public int getSize() {
        return buffer.size();
    }

    @Override
    public int getWeight() {
        return currentWeight;
    }

    @Override
    public int getCapacity() {
        return maxBufferWeight;
    }

    @Override
    public String getStatus() {
        return "Buffer status: [W:" + currentWeight + '/' + maxBufferWeight + ",S:" + buffer.size() + "]";
    }

    @Override
    public void doRecovery() {
        if (logger.isDebugEnabled()) {
            logger.debug("OutboundBuffer is in recovery, clearing buffer.");
        }
        buffer.clear();
        currentWeight = 0;
    }

    private class CompoundMessage implements Message {

        private Message[] messages;

        public CompoundMessage(Message[] messages) {
            this.messages = messages;
        }

        @Override
        public int getWeight() {
            int weight = 0;
            for (Message message : messages) {
                weight += message.getWeight();
            }
            return weight;
        }

        public List<Message> asList() {
            return Arrays.asList(messages);
        }

        @Override
        public String getId() {
            return null;
        }

        @Override
        public List<Message> shrink(List<Message> messages) {
            return messages; // do nothing
        }
    }


    private int defaultBufferCapacity() {
        Long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory < 0.5 * GB) {
            return 10000;
        }
        if (maxMemory < 1 * GB) {
            return 15000;
        }
        if (maxMemory < 2 * GB) {
            return 30000;
        }
        return 60000;
    }
}
