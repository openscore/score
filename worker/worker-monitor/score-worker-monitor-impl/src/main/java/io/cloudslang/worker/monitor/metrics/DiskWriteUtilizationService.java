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
import oshi.software.os.OSProcess;

import javax.annotation.PostConstruct;
import java.io.Serializable;

public class DiskWriteUtilizationService extends WorkerPerformanceMetricBase {
	private OSProcess process;

	@PostConstruct
	public void init() {
		this.process = getProcess();
	}

	@Override
	public Pair<WorkerPerformanceMetric, Serializable> measure() {
		Pair<WorkerPerformanceMetric, Serializable> diskWriteUsage = Pair.of(WorkerPerformanceMetric.DISK_WRITE_USAGE, getCurrentValue());
		return diskWriteUsage;
	}

	public long getCurrentValue() {
		long writeBytes = 0;
		if (process != null) {
			writeBytes = process.getBytesWritten();
		}
		return writeBytes;
	}
}
