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
package io.cloudslang.worker.monitor.metrics;

import io.cloudslang.worker.management.services.WorkerManager;
import io.cloudslang.worker.monitor.metric.WorkerPerfMetric;
import io.cloudslang.worker.monitor.service.MetricKeyValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class WorkerThreadUtilization implements WorkerPerfMetric {

    private final WorkerManager workerManager;
    @Autowired
    @Qualifier("numberOfExecutionThreads")
    private Integer numberOfThreads;

    public WorkerThreadUtilization(WorkerManager workerManager) {
        this.workerManager = workerManager;
    }

    @Override
    public Map<MetricKeyValue, Serializable> measure() {
        Map<MetricKeyValue, Serializable> threadUtilization = new HashMap<>();
        threadUtilization.put(MetricKeyValue.THREAD_UTILIZATION, getCurrentValue());
        return threadUtilization;
    }

    public int getCurrentValue() {
        return ((workerManager.getRunningTasksCount() * 100) / numberOfThreads);
    }
}