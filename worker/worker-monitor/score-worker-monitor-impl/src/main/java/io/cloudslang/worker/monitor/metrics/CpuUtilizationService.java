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

import io.cloudslang.worker.monitor.service.WorkerPerformanceMetric;
import org.apache.commons.lang3.tuple.Pair;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OSProcess;

import javax.annotation.PostConstruct;
import java.io.Serializable;

public class CpuUtilizationService extends WorkerPerformanceMetricBase {
    private static OSProcess oldProcess;
    private int cpuNumber;

    @PostConstruct
    public void init() {
        SystemInfo systemInfo = new SystemInfo();
        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        this.cpuNumber = processor.getLogicalProcessorCount();
    }

    @Override
    public Pair<WorkerPerformanceMetric, Serializable> measure() {
        Pair<WorkerPerformanceMetric, Serializable> cpuUsage = Pair.of(WorkerPerformanceMetric.CPU_USAGE, getCurrentValue());
        return cpuUsage;
    }

    public double getCurrentValue() {
        OSProcess osProcess = getProcess();
        double cpuUsed = (osProcess.getProcessCpuLoadBetweenTicks(oldProcess) * 100) / cpuNumber;
        oldProcess = osProcess;
        return formatTo2Decimal(cpuUsed);
    }
}
