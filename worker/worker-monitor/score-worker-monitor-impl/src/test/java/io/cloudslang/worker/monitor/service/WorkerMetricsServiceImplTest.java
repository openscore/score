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

import io.cloudslang.engine.node.services.WorkerNodeService;
import io.cloudslang.orchestrator.services.EngineVersionService;
import io.cloudslang.score.events.EventBus;
import io.cloudslang.worker.management.WorkerConfigurationService;
import io.cloudslang.worker.management.monitor.WorkerStateUpdateService;
import io.cloudslang.worker.management.queue.WorkerQueueDetailsContainer;
import io.cloudslang.worker.management.services.SynchronizationManager;
import io.cloudslang.worker.management.services.SynchronizationManagerImpl;
import io.cloudslang.worker.management.services.WorkerConfigurationUtils;
import io.cloudslang.worker.management.services.WorkerManager;
import io.cloudslang.worker.management.services.WorkerRecoveryManager;
import io.cloudslang.worker.management.services.WorkerVersionService;
import io.cloudslang.worker.monitor.PerfMetricCollector;
import io.cloudslang.worker.monitor.metrics.CpuUtilizationService;
import io.cloudslang.worker.monitor.metrics.DiskReadUtilizationService;
import io.cloudslang.worker.monitor.metrics.DiskWriteUtilizationService;
import io.cloudslang.worker.monitor.metrics.HeapUtilizationService;
import io.cloudslang.worker.monitor.metrics.MemoryUtilizationService;
import io.cloudslang.worker.monitor.metrics.WorkerThreadUtilization;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = WorkerMetricsServiceImplTest.MyTestConfig.class)
public class WorkerMetricsServiceImplTest {

	static final String CREDENTIAL_UUID = "uuid";
	@Autowired
	WorkerStateUpdateService workerStateUpdateService;
	@Autowired
	private WorkerMetricsService workerMetricsService;
	@Autowired
	private PerfMetricCollector perfMetricCollector;
	@Autowired
	private EventBus eventBus;

	@Test
	public void testWorkerMetricCollectorService() throws InterruptedException {
		Map<WorkerPerformanceMetric, Serializable> metricData = createWorkerPerformanceMetrics();
		when(perfMetricCollector.collectMetrics()).thenReturn(metricData);
		workerMetricsService.collectPerformanceMetrics();
		workerMetricsService.dispatchPerformanceMetrics();
		verify(eventBus, times(1)).dispatch(anyObject());
	}

	@Test
	public void testNoMetricCollectionWhenDisabledFromCentral() throws InterruptedException {
		reset(perfMetricCollector);
		when(workerStateUpdateService.isMonitoringDisabled()).thenReturn(true);
		workerMetricsService.collectPerformanceMetrics();
		verify(perfMetricCollector, times(0)).collectMetrics();
	}

	private Map<WorkerPerformanceMetric, Serializable> createWorkerPerformanceMetrics() {
		Map<WorkerPerformanceMetric, Serializable> metric1 = new HashMap<WorkerPerformanceMetric, Serializable>();
		metric1.put(WorkerPerformanceMetric.WORKER_ID, "123");
		metric1.put(WorkerPerformanceMetric.WORKER_MEASURED_TIME, 1603954121462L);
		metric1.put(WorkerPerformanceMetric.CPU_USAGE, 32.0);
		metric1.put(WorkerPerformanceMetric.MEMORY_USAGE, 8.0);
		metric1.put(WorkerPerformanceMetric.DISK_READ_USAGE, 1101268201L);
		metric1.put(WorkerPerformanceMetric.DISK_WRITE_USAGE, 11012601L);
		metric1.put(WorkerPerformanceMetric.THREAD_UTILIZATION, 10);
		metric1.put(WorkerPerformanceMetric.HEAP_SIZE, 28.0);
		return metric1;
	}

	@Configuration
	public static class MyTestConfig {

		@Bean
		public WorkerMetricsService workerMetricCollectorService() {
			return new WorkerMetricsServiceImpl();
		}

		@Bean
		public PerfMetricCollector perfMetricCollector() {
			return mock(PerfMetricCollector.class);
		}

		@Bean
		public EventBus eventBus() {
			return mock(EventBus.class);
		}

		@Bean
		public CpuUtilizationService cpuUtilizationService() {
			return mock(CpuUtilizationService.class);
		}

		@Bean
		public DiskReadUtilizationService diskReadUtilizationService() {
			return mock(DiskReadUtilizationService.class);
		}

		@Bean
		public DiskWriteUtilizationService diskWriteUtilizationService() {
			return mock(DiskWriteUtilizationService.class);
		}

		@Bean
		public MemoryUtilizationService memoryUtilizationService() {
			return mock(MemoryUtilizationService.class);
		}

		@Bean
		public HeapUtilizationService heapUtilizationService() {
			return mock(HeapUtilizationService.class);
		}

		@Bean
		public WorkerThreadUtilization workerThreadUtilization() {
			return mock(WorkerThreadUtilization.class);
		}

		@Bean
		SynchronizationManager synchronizationManager() {
			return new SynchronizationManagerImpl();
		}

		@Bean
		WorkerManager workerManager() {
			return new WorkerManager();
		}

		@Bean
		WorkerNodeService workerNodeService() {
			return mock(WorkerNodeService.class);
		}

		@Bean
		WorkerConfigurationService workerConfigurationService() {
			return mock(WorkerConfigurationService.class);
		}

		@Bean
		WorkerRecoveryManager workerRecoveryManager() {
			return mock(WorkerRecoveryManager.class);
		}

		@Bean
		Integer numberOfExecutionThreads() {
			return 2;
		}

		@Bean
		Long initStartUpSleep() {
			return 10L;
		}

		@Bean
		Long maxStartUpSleep() {
			return 100L;
		}

		@Bean
		WorkerConfigurationUtils workerConfigurationUtils() {
			WorkerConfigurationUtils workerConfigurationUtils = mock(WorkerConfigurationUtils.class);
			doReturn(mock(LinkedBlockingQueue.class)).when(workerConfigurationUtils).getBlockingQueue(anyInt(), anyInt());
			return workerConfigurationUtils;
		}

		@Bean
		WorkerStateUpdateService workerStateUpdateService() {
			return mock(WorkerStateUpdateService.class);
		}

		@Bean
		Integer inBufferCapacity() {
			return 20;
		}

		@Bean
		WorkerVersionService workerVersionService() {
			WorkerVersionService service = mock(WorkerVersionService.class);
			when(service.getWorkerVersion()).thenReturn("version");
			when(service.getWorkerVersionId()).thenReturn("123");
			return service;
		}

		@Bean
		EngineVersionService engineVersionService() {
			EngineVersionService service = mock(EngineVersionService.class);
			when(service.getEngineVersionId()).thenReturn("123");
			return service;
		}

		@Bean
		String workerUuid() {
			return CREDENTIAL_UUID;
		}

		@Bean
		public WorkerQueueDetailsContainer workerQueueDetailsContainer() {
			return mock(WorkerQueueDetailsContainer.class);
		}

	}
}
