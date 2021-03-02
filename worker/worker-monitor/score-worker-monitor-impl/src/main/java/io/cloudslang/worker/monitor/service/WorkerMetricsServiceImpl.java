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
package io.cloudslang.worker.monitor.service;

import io.cloudslang.score.events.EventBus;
import io.cloudslang.score.events.EventConstants;
import io.cloudslang.score.events.ScoreEvent;
import io.cloudslang.worker.management.monitor.WorkerStateUpdateService;
import io.cloudslang.worker.monitor.PerfMetricCollector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public class WorkerMetricsServiceImpl implements WorkerMetricsService {
    protected static final Logger logger = LogManager.getLogger(WorkerMetricsServiceImpl.class);
    final int capacity = Integer.getInteger("metrics.collection.sampleCount", Integer.MAX_VALUE);
    final boolean disabled = Boolean.getBoolean("worker.monitoring.disable");
    @Autowired
    PerfMetricCollector perfMetricCollector;

    @Autowired
    private WorkerStateUpdateService workerStateUpdateService;

    private final LinkedBlockingQueue<Map<WorkerPerformanceMetric, Serializable>> collectMetricQueue = new LinkedBlockingQueue<>(capacity);
    @Autowired
    private EventBus eventBus;

    @Override
    public void collectPerformanceMetrics() {
        try {
            if (!isMonitoringDisabled()) {
                Map<WorkerPerformanceMetric, Serializable> metricInfo = perfMetricCollector.collectMetrics();
                collectMetricQueue.put(metricInfo);
                if (logger.isDebugEnabled()) {
                    logger.debug("Collected worker metric " + metricInfo.size());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load metric into queue", e);
        }
    }

    private boolean isMonitoringDisabled() {
        return disabled || workerStateUpdateService.isMonitoringDisabled();
    }

    @Override
    public void dispatchPerformanceMetrics() {
        try {
            List<Map<WorkerPerformanceMetric, Serializable>> metricData = getCurrentBatch(collectMetricQueue);
            if (!isMonitoringDisabled()  && metricData.size() > 0) {
                ScoreEvent scoreEvent = new ScoreEvent(EventConstants.WORKER_PERFORMANCE_MONITOR, (Serializable) metricData);
                eventBus.dispatch(scoreEvent);
                if (logger.isDebugEnabled()) {
                    logger.debug("Dispatched worker metric " + metricData.size());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to dispatch metric info event", e);
        }
    }

    private List<Map<WorkerPerformanceMetric, Serializable>> getCurrentBatch(LinkedBlockingQueue<Map<WorkerPerformanceMetric, Serializable>> metricQueue) {
        List<Map<WorkerPerformanceMetric, Serializable>> metricList = new ArrayList<>();
        metricQueue.drainTo(metricList);

        return metricList;
    }
}
