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

package io.cloudslang.schema;

import io.cloudslang.engine.node.services.StubQueueConfigurationDataServiceImpl;
import io.cloudslang.runtime.impl.sequential.DefaultSequentialExecutionServiceImpl;
import io.cloudslang.score.events.EventBusImpl;
import io.cloudslang.score.events.FastEventBusImpl;
import io.cloudslang.worker.execution.reflection.ReflectionAdapterImpl;
import io.cloudslang.worker.execution.services.ExecutionServiceImpl;
import io.cloudslang.worker.execution.services.ScoreRobotAvailabilityServiceImpl;
import io.cloudslang.worker.execution.services.SessionDataHandlerImpl;
import io.cloudslang.worker.execution.services.StubExecutionPostconditionService;
import io.cloudslang.worker.execution.services.StubExecutionPreconditionService;
import io.cloudslang.worker.management.WorkerConfigurationServiceImpl;
import io.cloudslang.worker.management.WorkerRegistration;
import io.cloudslang.worker.management.monitor.ScheduledWorkerLoadMonitor;
import io.cloudslang.worker.management.monitor.WorkerMonitorsImpl;
import io.cloudslang.worker.management.monitor.WorkerStateUpdateServiceImpl;
import io.cloudslang.worker.management.queue.WorkerQueueDetailsContainer;
import io.cloudslang.worker.management.services.InBuffer;
import io.cloudslang.worker.management.services.OutboundBufferImpl;
import io.cloudslang.worker.management.services.RetryTemplate;
import io.cloudslang.worker.management.services.SimpleExecutionRunnableFactory;
import io.cloudslang.worker.management.services.SynchronizationManagerImpl;
import io.cloudslang.worker.management.services.WorkerConfigurationUtils;
import io.cloudslang.worker.management.services.WorkerExecutionMonitorServiceImpl;
import io.cloudslang.worker.management.services.WorkerManager;
import io.cloudslang.worker.management.services.WorkerManagerMBean;
import io.cloudslang.worker.management.services.WorkerRecoveryManagerImpl;
import io.cloudslang.worker.management.services.WorkerVersionServiceImpl;
import io.cloudslang.worker.monitor.PerformanceMetricsCollector;
import io.cloudslang.worker.monitor.mbean.WorkerMetricsMBean;
import io.cloudslang.worker.monitor.metrics.CpuUtilizationService;
import io.cloudslang.worker.monitor.metrics.DiskReadUtilizationService;
import io.cloudslang.worker.monitor.metrics.DiskWriteUtilizationService;
import io.cloudslang.worker.monitor.metrics.HeapUtilizationService;
import io.cloudslang.worker.monitor.metrics.MemoryUtilizationService;
import io.cloudslang.worker.monitor.metrics.WorkerThreadUtilization;
import io.cloudslang.worker.monitor.service.WorkerMetricsServiceImpl;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Boolean.FALSE;

/**
 * @since 21/01/2014
 */
public class WorkerBeanDefinitionParser extends AbstractBeanDefinitionParser {

    private final Map<Class<?>, String> beans = new HashMap<Class<?>, String>() {{
        put(WorkerManager.class, "workerManager");
        put(EventBusImpl.class, null);
        put(FastEventBusImpl.class, "consumptionFastEventBus");
        put(ExecutionServiceImpl.class, "agent");
        put(InBuffer.class, null);
        put(WorkerConfigurationUtils.class, null);
        put(WorkerStateUpdateServiceImpl.class, null);
        put(OutboundBufferImpl.class, "outBuffer");
        put(RetryTemplate.class, null);
        put(SimpleExecutionRunnableFactory.class, null);
        put(WorkerManagerMBean.class, "io.cloudslang.worker.management.services.WorkerManagerMBean");
        put(WorkerRecoveryManagerImpl.class, null);
        put(ReflectionAdapterImpl.class, null);
        put(SessionDataHandlerImpl.class, "sessionDataHandler");
        put(SynchronizationManagerImpl.class, null);
        put(WorkerConfigurationServiceImpl.class, "workerConfiguration");
        put(WorkerQueueDetailsContainer.class, "workerQueueDetailsContainer");

        //Monitors
        put(WorkerExecutionMonitorServiceImpl.class, "workerExecutionMonitorService");
        put(WorkerMonitorsImpl.class, "workerMonitorsImpl");
        put(ScheduledWorkerLoadMonitor.class, "scheduledWorkerLoadMonitor");
        put(CpuUtilizationService.class, "cpuUtilizationService");
        //Metrics
        put(DiskReadUtilizationService.class, "diskReadUtilizationService");
        put(DiskWriteUtilizationService.class, "diskWriteUtilizationService");
        put(MemoryUtilizationService.class, "memoryUtilizationService");
        put(PerformanceMetricsCollector.class, "perfMetricCollector");
        put(WorkerMetricsServiceImpl.class, "workerMetricCollectorService");
        put(HeapUtilizationService.class, "heapUtilizationService");
        put(WorkerThreadUtilization.class, "workerThreadUtilization");
        put(WorkerMetricsMBean.class, "io.cloudslang.worker.monitor.mbean.WorkerMetricsMBean");
    }};

    private final List<ConfValue> configurationValues = Arrays.asList(
            new ConfValue().NAME("inBufferCapacity").DEFAULT(500),
            new ConfValue().NAME("numberOfExecutionThreads").DEFAULT(20),
            new ConfValue().NAME("maxDeltaBetweenDrains").DEFAULT(100)
    );

    private final List<ConfValue> schedulerValues = Arrays.asList(
            new ConfValue().NAME("outBufferInterval").DEFAULT(100L),
            new ConfValue().NAME("keepAliveInterval").DEFAULT(10000L),
            new ConfValue().NAME("configRefreshInterval").DEFAULT(1000L),
            new ConfValue().NAME("interruptCanceledInterval").DEFAULT(30000L),
            new ConfValue().NAME("statisticsInterval").DEFAULT(1000L),
            new ConfValue().NAME("scheduledWorkerMonitorInterval").DEFAULT(10000L),
            new ConfValue().NAME("workerMonitorRefreshInterval").DEFAULT(300000L),
            new ConfValue().NAME("scheduledPerfMetricCollectionInterval").DEFAULT(5000L),
            new ConfValue().NAME("scheduledMetricDispatchInterval").DEFAULT(30000L)
    );

    @Override
    protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
        registerWorkerUuid(element.getAttribute("uuid"), element.getAttribute("depends-on"), parserContext);
        registerBeans(parserContext);
        registerSpecialBeans(element, parserContext);
        registerConfiguration(DomUtils.getChildElementByTagName(element, "configuration"), parserContext);
        registerScheduler(DomUtils.getChildElementByTagName(element, "scheduler"), parserContext);
        return createRootBeanDefinition();
    }

    private AbstractBeanDefinition createRootBeanDefinition() {
        return BeanDefinitionBuilder.genericBeanDefinition(Object.class).getBeanDefinition();
    }

    private void registerWorkerUuid(String uuid, String dependsOn, ParserContext parserContext) {
        new BeanRegistrator(parserContext)
                .NAME("workerUuid")
                .CLASS(String.class)
                .addConstructorArgValue(uuid)
                .addDependsOn(StringUtils.hasText(dependsOn) ? dependsOn.split(",") : null)
                .register();
    }

    private void registerBeans(ParserContext parserContext) {
        BeanRegistrator beanRegistrator = new BeanRegistrator(parserContext);
        for (Map.Entry<Class<?>, String> entry : beans.entrySet()) {
            beanRegistrator
                    .NAME(entry.getValue())
                    .CLASS(entry.getKey())
                    .register();
        }
    }

    private void registerSpecialBeans(Element element, ParserContext parserContext) {
        if (!"false".equalsIgnoreCase(element.getAttribute("register"))) {
            new BeanRegistrator(parserContext).CLASS(WorkerRegistration.class).register();
        }

        registerWorkerVersionService(element, parserContext);
        registerSequentialExecution(element, parserContext);
        registerRobotAvailabilityService(element, parserContext);
        registerExecutionPreconditionService(element, parserContext);
        registerExecutionPostconditionService(element, parserContext);
        registerQueueConfigurationDataService(element, parserContext);
    }

    private void registerSequentialExecution(Element element, ParserContext parserContext) {
        String registerSequentialExecutionService = element.getAttribute("registerSequentialExecutionService");
        if (!FALSE.toString().equals(registerSequentialExecutionService)) {
            new BeanRegistrator(parserContext)
                    .NAME("sequentialExecutionService")
                    .CLASS(DefaultSequentialExecutionServiceImpl.class)
                    .register();
        }
    }

    private void registerWorkerVersionService(Element element, ParserContext parserContext) {
        String registerWorkerVersionService = element.getAttribute("registerWorkerVersionService");
        if (!FALSE.toString().equals(registerWorkerVersionService)) {
            new BeanRegistrator(parserContext).CLASS(WorkerVersionServiceImpl.class).register();
        }
    }

    private void registerRobotAvailabilityService(Element element, ParserContext parserContext) {
        String registerRobotAvailabilityService = element.getAttribute("registerRobotAvailabilityService");
        if (!FALSE.toString().equals(registerRobotAvailabilityService)) {
            new BeanRegistrator(parserContext)
                    .NAME("robotAvailabilityService")
                    .CLASS(ScoreRobotAvailabilityServiceImpl.class)
                    .register();
        }
    }

    private void registerExecutionPreconditionService(Element element, ParserContext parserContext) {
        String registerPreconditionService = element.getAttribute("registerExecutionPreconditionService");
        if (!FALSE.toString().equals(registerPreconditionService)) {
            new BeanRegistrator(parserContext)
                    .NAME("executionPreconditionService")
                    .CLASS(StubExecutionPreconditionService.class)
                    .register();
        }
    }


    private void registerExecutionPostconditionService(Element element, ParserContext parserContext) {
        String registerPostconditionService = element.getAttribute("registerExecutionPostconditionService");
        if (!FALSE.toString().equals(registerPostconditionService)) {
            new BeanRegistrator(parserContext)
                    .NAME("executionPostconditionService")
                    .CLASS(StubExecutionPostconditionService.class)
                    .register();
        }
    }

    private void registerConfiguration(Element configurationElement, ParserContext parserContext) {
        for (ConfValue configurationValue : configurationValues) {
            configurationValue.register(configurationElement, parserContext);
        }
    }

    private void registerScheduler(Element schedulerElement, ParserContext parserContext) {
        for (ConfValue value : schedulerValues) {
            value.register(schedulerElement, parserContext);
        }
        new XmlBeanDefinitionReader(parserContext.getRegistry())
                .loadBeanDefinitions("META-INF/spring/score/context/scoreWorkerSchedulerContext.xml");
    }

    private void registerQueueConfigurationDataService(Element element, ParserContext parserContext) {
        String registerQueueConfigurationDataService = element.getAttribute("registerQueueConfigurationDataService");
        if (!FALSE.toString().equals(registerQueueConfigurationDataService)) {
            new BeanRegistrator(parserContext)
                    .NAME("queueConfigurationDataService")
                    .CLASS(StubQueueConfigurationDataServiceImpl.class)
                    .register();
        }
    }

    @Override
    protected boolean shouldGenerateId() {
        return true;
    }

}
