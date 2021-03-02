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

import io.cloudslang.engine.queue.entities.ExecStatus;
import io.cloudslang.engine.queue.entities.ExecutionMessage;
import io.cloudslang.engine.queue.entities.Payload;
import io.cloudslang.engine.queue.services.QueueDispatcherService;
import io.cloudslang.worker.management.ExecutionsActivityListener;
import io.cloudslang.worker.management.monitor.WorkerStateUpdateService;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

import static ch.lambdaj.Lambda.extract;
import static ch.lambdaj.Lambda.on;
import static io.cloudslang.worker.management.services.WorkerConfigurationUtils.NEW_DEFAULT_WORKER_MEMORY_RATIO;
import static java.lang.Double.compare;
import static java.lang.Integer.getInteger;
import static java.lang.Long.parseLong;
import static java.lang.Runtime.getRuntime;


public class InBuffer implements WorkerRecoveryListener, ApplicationListener, Runnable {

    private static final Logger logger = LogManager.getLogger(InBuffer.class);
    private static final int MINIMUM_GC_DELTA = 10000; // Minimum delta between garbage collections in milliseconds

    @Autowired
    private QueueDispatcherService queueDispatcher;

    @Resource
    private String workerUuid;

    @Autowired
    @Qualifier("inBufferCapacity")
    private Integer capacity;

    @Autowired(required = false)
    @Qualifier("coolDownPollingMillis")
    private Integer coolDownPollingMillis = 200;

    @Autowired
    private WorkerManager workerManager;

    @Autowired
    private SimpleExecutionRunnableFactory simpleExecutionRunnableFactory;

    @Autowired
    private OutboundBuffer outBuffer;

    @Autowired
    private SynchronizationManager syncManager;

    @Autowired
    @Qualifier("numberOfExecutionThreads")
    private Integer numberOfThreads;

    @Autowired(required = false)
    private ExecutionsActivityListener executionsActivityListener;

    @Autowired
    private WorkerConfigurationUtils workerConfigurationUtils;

    @Autowired
    private WorkerStateUpdateService workerStateUpdateService;

    private Thread fillBufferThread = new Thread(this);
    private boolean inShutdown;
    private boolean endOfInit = false;
    private long gcTimer = System.currentTimeMillis();
    // New in buffer settings
    private boolean newInBufferBehaviour;
    private int newInBufferSize;
    private int minInBufferSize;
    private double workerFreeMemoryRatio;

    @PostConstruct
    void init() {
        capacity = getInteger("worker.inbuffer.capacity", capacity);
        coolDownPollingMillis = getInteger("worker.inbuffer.coolDownPollingMillis", coolDownPollingMillis);
        logger.info("InBuffer capacity is set to :" + capacity
                + ", coolDownPollingMillis is set to :" + coolDownPollingMillis);

        newInBufferBehaviour = workerConfigurationUtils.isNewInbuffer();
        logger.info("new inbuffer behaviour enabled: " + newInBufferBehaviour);

        // Simplify out of the box worker configuration settings, to only set thread pool number of threads.
        // Buffer size and min buffer size are computed based on worker execution thread count.

        // Buffer size is the capacity that is desired (it is not enforced right now) since we can offer to queue from inside SimpleExecutionRunnable,
        // and buffer size is infinite (Integer.MAX_VALUE)

        // Min buffer size is the size at which the WorkerFillBufferThread thread starts polling if free memory conditions are met.
        if (newInBufferBehaviour) {
            Pair<Integer, Integer> minSizeAndSizeOfInBuffer = workerConfigurationUtils.getMinSizeAndSizeOfInBuffer(numberOfThreads);
            minInBufferSize = minSizeAndSizeOfInBuffer.getLeft();
            newInBufferSize = minSizeAndSizeOfInBuffer.getRight();

            logger.info("new inbuffer size: " + newInBufferSize);
            logger.info("new inbuffer minimum size: " + minInBufferSize);
        }

        workerFreeMemoryRatio = workerConfigurationUtils.getWorkerMemoryRatio();
        logger.info("Worker free memory ratio is: " + String.format("%.2f", workerFreeMemoryRatio));
    }

    private double getWorkerFreeMemoryRatio() {
        double localWorkerFreeMemoryRatio = workerFreeMemoryRatio;
        return (compare(0, localWorkerFreeMemoryRatio) == 0) ? NEW_DEFAULT_WORKER_MEMORY_RATIO : localWorkerFreeMemoryRatio;
    }

    private void fillBufferPeriodically() {
        while (!inShutdown) {
            try {
                boolean workerUp = workerManager.isUp();
                if (!workerUp) {
                    Thread.sleep(3000); //sleep if worker is not fully started yet
                } else {
                    // We must lock recovery lock before poll - otherwise we will get duplications
                    syncManager.startGetMessages();

                    // We need to check if the current thread was interrupted while waiting for the lock (InBufferThread) and RESET its interrupted flag!
                    if (Thread.interrupted()) {
                        logger.info("Thread was interrupted while waiting on the lock in fillBufferPeriodically()");
                        continue;
                    }

                    if (!workerStateUpdateService.isWorkerEnabled()) {
                        logger.debug("Worker is disabled, skipping polling.");
                        syncManager.finishGetMessages(); // Release all locks before going to sleep
                        Thread.sleep(3000);
                        continue;
                    }

                    int inBufferSize = workerManager.getInBufferSize();
                    long totalMemory = getRuntime().totalMemory();
                    long freeMemory = getRuntime().freeMemory();

                    if (needToPoll(inBufferSize, totalMemory, freeMemory)) {
                        int messagesToGet = !newInBufferBehaviour ? (capacity - inBufferSize) : (newInBufferSize - inBufferSize);

                        if (logger.isDebugEnabled()) {
                            logger.debug("Polling messages from queue (max " + messagesToGet + ")");
                        }
                        List<ExecutionMessage> newMessages = queueDispatcher.poll(workerUuid, messagesToGet, freeMemory);
                        if (executionsActivityListener != null) {
                            executionsActivityListener.onActivate(extract(newMessages, on(ExecutionMessage.class).getExecStateId()));
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug("Received " + newMessages.size() + " messages from queue");
                        }

                        if (!newMessages.isEmpty()) {
                            // We must acknowledge the messages that we took from the queue
                            ackMessages(newMessages);
                            for (ExecutionMessage msg : newMessages) {
                                addExecutionMessageInner(msg);
                            }

                            syncManager.finishGetMessages(); // Release all locks before going to sleep
                            Thread.sleep(coolDownPollingMillis / 8); // Cool down - sleep a while
                        } else {
                            syncManager.finishGetMessages(); // Release all locks before going to sleep
                            Thread.sleep(coolDownPollingMillis); // If there are no messages - sleep a while
                        }
                    } else {
                        syncManager.finishGetMessages(); // Release all locks before going to sleep
                        Thread.sleep(coolDownPollingMillis); // If the buffer is not empty enough yet or in recovery - sleep a while
                    }
                }
            } catch (InterruptedException ex) {
                logger.error("Fill InBuffer thread was interrupted... ", ex);
                syncManager.finishGetMessages(); // Release all locks before going to sleep
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {/*ignore*/}
            } catch (Exception ex) {
                logger.error("Failed to load new ExecutionMessages to the buffer!", ex);
                syncManager.finishGetMessages(); // Release all locks before going to sleep
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {/*ignore*/}
            } finally {
                syncManager.finishGetMessages();
            }
        }
    }

    private boolean needToPoll(int bufferSize, long totalMemory, long freeMemory) {
        if (logger.isDebugEnabled()) {
            logger.debug("InBuffer size: " + bufferSize);
        }
        if (!newInBufferBehaviour) {
            return bufferSize < (capacity * 0.2) && checkFreeMemorySpace(totalMemory, freeMemory);
        } else {
            return (bufferSize < minInBufferSize) && checkFreeMemorySpace(totalMemory, freeMemory);
        }
    }

    private void ackMessages(List<ExecutionMessage> newMessages) throws InterruptedException {
        ExecutionMessage cloned;
        for (ExecutionMessage message : newMessages) {
            // create a unique id for this lane in this specific worker to be used in out buffer optimization
            message.setWorkerKey(message.getMsgId() + " : " + message.getExecStateId());

            Payload payload = message.getPayload(); // store payload
            message.setPayload(null);

            cloned = (ExecutionMessage) message.clone(); // To clone without payload, payload is not needed in ack - make it null in order to minimize the data that is being sent
            cloned.setStatus(ExecStatus.IN_PROGRESS);
            cloned.incMsgSeqId();

            message.setPayload(payload); // Fix payload again in original message
            message.incMsgSeqId(); // Increment the original message seq too in order to preserve the order of all messages of entire step

            outBuffer.put(cloned);
        }
    }


    public void addExecutionMessage(ExecutionMessage msg) throws InterruptedException {
        try {
            syncManager.startGetMessages(); // This is a public method that can push new executions from outside - from execution threads
            // We need to check if the current execution thread was interrupted while waiting for the lock
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread was interrupted while waiting on the lock in fillBufferPeriodically()!");
            }
            addExecutionMessageInner(msg);
        } finally {
            syncManager.finishGetMessages();
        }
    }

    private void addExecutionMessageInner(ExecutionMessage msg) {
        SimpleExecutionRunnable simpleExecutionRunnable = simpleExecutionRunnableFactory.getObject();
        simpleExecutionRunnable.setExecutionMessage(msg);
        long executionId = parseLong(msg.getMsgId());
        workerManager.addExecution(executionId, simpleExecutionRunnable);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent applicationEvent) {
        if (applicationEvent instanceof ContextRefreshedEvent && !endOfInit) {
            endOfInit = true;
            inShutdown = false;
            fillBufferThread.setName("WorkerFillBufferThread");
            fillBufferThread.start();
        } else if (applicationEvent instanceof ContextClosedEvent) {
            inShutdown = true;
        }
    }

    @Override
    public void run() {
        fillBufferPeriodically();
    }

    private boolean checkFreeMemorySpace(long totalMemory, long freeMemory) {
        double allocatedMemory = totalMemory - freeMemory;
        long maxMemory = getRuntime().maxMemory();
        double presumableFreeMemory = maxMemory - allocatedMemory;
        double crtFreeMemoryRatio = presumableFreeMemory / maxMemory;
        double configuredWorkerFreeMemoryRatio = getWorkerFreeMemoryRatio();
        boolean result = crtFreeMemoryRatio > configuredWorkerFreeMemoryRatio;
        if (!result) {
            logger.warn("InBuffer would not poll messages, because there is not enough free memory. Free memory is "
                    + String.format("%.0f", presumableFreeMemory) + ". Worker free memory ratio is "
                    + String.format("%.2f", configuredWorkerFreeMemoryRatio));
            if (System.currentTimeMillis() > (gcTimer + MINIMUM_GC_DELTA)) {
                logger.warn("Trying to initiate garbage collection");
                System.gc();
                gcTimer = System.currentTimeMillis();
            }
        }
        return result;
    }

    @Override
    public void doRecovery() {
        //We must interrupt the inBuffer thread in case it is stuck in await() because the outBuffer is full
        fillBufferThread.interrupt();
    }

    public int getCapacity() {
        return (!newInBufferBehaviour) ? capacity : newInBufferSize;
    }
}
