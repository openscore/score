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

package io.cloudslang.score.lang;

import io.cloudslang.score.api.EndBranchDataContainer;
import io.cloudslang.score.api.StartBranchDataContainer;
import io.cloudslang.score.api.execution.ExecutionParametersConsts;
import io.cloudslang.score.events.ScoreEvent;
import io.cloudslang.score.facade.execution.ExecutionStatus;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

import static java.lang.Boolean.TRUE;

/**
 * User: Date: 11/06/2014
 */
public class ExecutionRuntimeServices implements Serializable {

    private static final long serialVersionUID = 2557429503280678353L;

    protected static final String EXECUTION_PAUSED = "EXECUTION_PAUSED";

    private static final String BRANCH_DATA = "BRANCH_DATA";

    protected static final String SCORE_EVENTS_QUEUE = "SCORE_EVENTS_QUEUE";

    protected static final String NO_WORKERS_IN_GROUP = "NO_WORKERS_IN_GROUP";

    private static final String NEW_SPLIT_ID = "NEW_SPLIT_ID";

    private static final String BRANCH_ID = "BRANCH_ID";

    public static final String EXECUTION_ID_CONTEXT = "executionIdContext";

    private static final String EXECUTION_STEP_ERROR_KEY = "EXECUTION_STEP_ERROR_KEY";

    private static final String RUNNING_PLANS_MAP = "RUNNING_PLANS_MAP";

    private static final String BEGIN_STEPS_MAP = "BEGIN_STEPS_MAP";

    private static final String FLOW_TERMINATION_TYPE = "FLOW_TERMINATION_TYPE";

    private static final String REQUESTED_EXECUTION_PLAN_ID = "REQUESTED_EXECUTION_PLAN_ID";

    public static final String LANGUAGE_TYPE = "LANGUAGE_TYPE";

    private static final String METADATA = "METADATA";

    private static final String STEP_PERSIST = "STEP_PERSIST";

    private static final String STEP_PERSIST_ID = "STEP_PERSIST_ID";

    private static final String NODE_NAME = "NODE_NAME";

    private static final String NODE_NAME_WITH_DEPTH = "NODE_NAME_WITH_DEPTH";

    private static final String PARENT_RUNNING_ID = "PARENT_RUNNING_ID";

    private static final String WORKER_GROUP_NAME = "WORKER_GROUP_NAME";

    private static final String SHOULD_CHECK_GROUP = "SHOULD_CHECK_GROUP";

    private static final String CONSUMER_WORKER_ID = "CONSUMER_WORKER_ID";

    private static final String PRODUCER_WORKER_ID = "PRODUCER_WORKER_ID";

    private static final String ROBOT_ID = "ROBOT_ID";

    private static final String ROBOT_GROUP_NAME = "ROBOT_GROUP_NAME";

    private static final String PRECONDITION_NOT_FULFILLED = "PRECONDITION_NOT_FULFILLED";

    private static final String MERGE_USER_INPUTS = "MERGE_USER_INPUTS";

    public static final String ENTERPRISE_MODE = "ENTERPRISE_MODE";

    protected Map<String, Serializable> contextMap = new UnifiedMap<>();

    public ExecutionRuntimeServices() {
    }

    /**
     * copy constructor that clean the NEW_SPLIT_ID & BRANCH_ID keys
     */
    public ExecutionRuntimeServices(ExecutionRuntimeServices executionRuntimeServices) {
        contextMap.putAll(executionRuntimeServices.contextMap);
        contextMap.remove(NEW_SPLIT_ID);
        contextMap.remove(BRANCH_ID);
    }

    /**
     * setter for the finished child brunches data
     *
     * @param data - list of EndBranchDataContainer
     */
    public void setFinishedChildBranchesData(ArrayList<EndBranchDataContainer> data) {
        Validate.isTrue(!contextMap.containsKey(ExecutionParametersConsts.FINISHED_CHILD_BRANCHES_DATA),
                "not allowed to overwrite finished branches data");
        contextMap.put(ExecutionParametersConsts.FINISHED_CHILD_BRANCHES_DATA, data);
    }

    /**
     * put all the data relevant for sub flows: map of runningPlanIds and list of BeginStepIds
     *
     * @param runningPlansIds - map of flowUUID to runningPlanId
     * @param beginStepsIds -  map of flowUUID to beginStepId
     */
    public void setSubFlowsData(Map<String, Long> runningPlansIds, Map<String, Long> beginStepsIds) {
        contextMap.put(RUNNING_PLANS_MAP, (Serializable) runningPlansIds);
        contextMap.put(BEGIN_STEPS_MAP, (Serializable) beginStepsIds);
    }

    /**
     * @param subFlowUuid - the required sub flow UUID
     * @return the id of the runningPlan of the given flow
     */
    public Long getSubFlowRunningExecutionPlan(String subFlowUuid) {
        return ((Map<String, Long>) contextMap.get(RUNNING_PLANS_MAP)).get(subFlowUuid);
    }

    /**
     * @param subFlowUuid - the required sub flow UUID
     * @return the begin step of the given flow
     */
    public Long getSubFlowBeginStep(String subFlowUuid) {
        return ((Map<String, Long>) contextMap.get(BEGIN_STEPS_MAP)).get(subFlowUuid);
    }

    public String getLanguageName() {
        return ((String) contextMap.get(LANGUAGE_TYPE));
    }

    public void setLanguageName(String languageName) {
        contextMap.put(LANGUAGE_TYPE, languageName);
    }

    /**
     * @return the brunchId of the current execution
     */
    public String getBranchId() {
        return getFromMap(BRANCH_ID);
    }

    /**
     * setter for the brunch id of the current Execution
     */
    public void setBranchId(String brunchId) {
        Validate.isTrue(StringUtils.isEmpty(getBranchId()), "not allowed to overwrite branch id");
        contextMap.put(BRANCH_ID, brunchId);
    }

    /**
     * @return the flow termination type : one of ExecutionStatus values
     */
    public ExecutionStatus getFlowTerminationType() {
        return getFromMap(FLOW_TERMINATION_TYPE);
    }

    /**
     * set the flow termination type
     *
     * @param flowTerminationType - from ExecutionStatus
     */
    public void setFlowTerminationType(ExecutionStatus flowTerminationType) {
        contextMap.put(FLOW_TERMINATION_TYPE, flowTerminationType);
    }

    /**
     * Request the engine to change the running execution plan to a new one The engine will deal with the request after
     * finishing to execute the curretn step
     *
     * @param runningExecutionPlanId the new running execution plan id
     */
    public void requestToChangeExecutionPlan(Long runningExecutionPlanId) {
        contextMap.put(REQUESTED_EXECUTION_PLAN_ID, runningExecutionPlanId);
    }

    /**
     * This method should be used by score engine once it finishes executing a step, and checks if the running execution
     * plan should be changed
     *
     * @return the id of the requested running execution plan
     */
    public Long pullRequestForChangingExecutionPlan() {
        return removeFromMap(REQUESTED_EXECUTION_PLAN_ID);
    }

    /**
     * @return the error key of the step
     */
    public String getStepErrorKey() {
        return getFromMap(EXECUTION_STEP_ERROR_KEY);
    }

    /**
     * set the step error key
     */
    public void setStepErrorKey(String stepErrorKey) {
        contextMap.put(EXECUTION_STEP_ERROR_KEY, stepErrorKey);
    }

    /**
     * @return true if there is step error
     */
    public boolean hasStepErrorKey() {
        return contextMap.containsKey(EXECUTION_STEP_ERROR_KEY);
    }

    /**
     * clean step error key
     *
     * @return the values cleaned
     */
    public String removeStepErrorKey() {
        return (String) removeFromMap(EXECUTION_STEP_ERROR_KEY);
    }

    public void setStepPersist(boolean stepPersist) {
        contextMap.put(STEP_PERSIST, stepPersist);
    }

    public boolean isStepPersist() {
        if (getFromMap(STEP_PERSIST) == null) {
            return false;
        } else {
            return getFromMap(STEP_PERSIST);
        }
    }

    public void removeStepPersist() {
        removeFromMap(STEP_PERSIST);
    }

    public void setStepPersistId(String stepPersistId) {
        contextMap.put(STEP_PERSIST_ID, stepPersistId);
    }

    public String getStepPersistId() {
        return getFromMap(STEP_PERSIST_ID);
    }

    public void removeStepPersistID() {
        removeFromMap(STEP_PERSIST_ID);
    }

    /**
     * @return the execution id
     */
    public Long getExecutionId() {
        return getFromMap(EXECUTION_ID_CONTEXT);
    }

    /**
     * set the execution id - should be called only once in score triggering!
     */
    public void setExecutionId(Long executionId) {
        contextMap.put(EXECUTION_ID_CONTEXT, executionId);
    }

    /**
     * @return the split id
     */
    public String getSplitId() {
        return getFromMap(NEW_SPLIT_ID);
    }

    /**
     * set teh split id
     */
    public void setSplitId(String splitId) {
        Validate.isTrue(StringUtils.isEmpty(getSplitId()), "not allowed to overwrite split id");
        contextMap.put(NEW_SPLIT_ID, splitId);
    }

    public String getWorkerGroupName() {
        return getFromMap(WORKER_GROUP_NAME);
    }

    public void setWorkerGroupName(String workerGroupName) {
        contextMap.put(WORKER_GROUP_NAME, workerGroupName);
    }

    /** This flag is set if the current execution step needs to go through group resolving */
    public void setShouldCheckGroup() {
        contextMap.put(SHOULD_CHECK_GROUP, true);
    }

    public void removeShouldCheckGroup() {
        contextMap.remove(SHOULD_CHECK_GROUP);
    }

    public boolean shouldCheckGroup() {
        return contextMap.containsKey(SHOULD_CHECK_GROUP);
    }

    public String getNodeName() {
        return getFromMap(NODE_NAME);
    }

    public String getNodeNameWithDepth() {
        return getFromMap(NODE_NAME_WITH_DEPTH);
    }

    public void setNodeName(String nodeName) {
        contextMap.put(NODE_NAME, nodeName);
    }

    public void setNodeNameWithDepth(String nodeNameWithDepth) {
        contextMap.put(NODE_NAME_WITH_DEPTH, nodeNameWithDepth);
    }

    public Long getParentRunningId() {
        return getFromMap(PARENT_RUNNING_ID);
    }

    public void setParentRunningId(Long parentRunningId) {
        contextMap.put(PARENT_RUNNING_ID, parentRunningId);
    }

    /**
     * used for asking score to pause your run
     */
    public void pause() {
        contextMap.put(EXECUTION_PAUSED, TRUE);
    }

    /**
     * @return true if the execution should be paused
     */
    public boolean isPaused() {
        // This is called lots of times, the flipped order is for performance considerations
        return TRUE.equals(contextMap.get(EXECUTION_PAUSED));
    }

    /**
     * add event - for score to fire
     *
     * @param eventType - string which is the key you can listen to
     * @param eventData - the event data
     */
    public void addEvent(String eventType, Serializable eventData) {
        @SuppressWarnings("unchecked")
        Queue<ScoreEvent> eventsQueue = getFromMap(SCORE_EVENTS_QUEUE);
        if (eventsQueue == null) {
            eventsQueue = new ArrayDeque<>();
            contextMap.put(SCORE_EVENTS_QUEUE, (ArrayDeque) eventsQueue);
        }
        eventsQueue.add(new ScoreEvent(eventType, getLanguageName(), eventData, getMetaData()));
    }

    /**
     * @return all the added events
     */
    public ArrayDeque<ScoreEvent> getEvents() {
        return getFromMap(SCORE_EVENTS_QUEUE);
    }

    /**
     * means we dont have worker with the required group
     *
     * @param groupName - the name of the missing group
     */
    public void setNoWorkerInGroup(String groupName) {
        contextMap.put(NO_WORKERS_IN_GROUP, groupName);
    }

    /**
     * @return the missing group name
     */
    public String getNoWorkerInGroupName() {
        return getFromMap(NO_WORKERS_IN_GROUP);
    }

    protected <T> T getFromMap(String key) {
        //noinspection unchecked
        return (T) contextMap.get(key);
    }

    /**
     * add brunch - means you want to split your execution
     *
     * @param startPosition - the position in the execution plan the new brunch will point to
     * @param flowUuid - the flow uuid
     * @param context - the context of the created brunch
     */
    public void addBranch(Long startPosition, String flowUuid, Map<String, Serializable> context) {
        Map<String, Long> runningPlansIds = getFromMap(RUNNING_PLANS_MAP);
        Long runningPlanId = runningPlansIds.get(flowUuid);
        addBranch(startPosition, runningPlanId, context, new ExecutionRuntimeServices(this));
    }

    protected void addBranch(Long startPosition, Long executionPlanId, Map<String, Serializable> context,
            ExecutionRuntimeServices executionRuntimeServices) {
        if (!contextMap.containsKey(BRANCH_DATA)) {
            contextMap.put(BRANCH_DATA, new ArrayList<StartBranchDataContainer>());
        }
        List<StartBranchDataContainer> branchesData = getFromMap(BRANCH_DATA);

        Map<String, Serializable> contextMapForBranch = new HashMap<>(executionRuntimeServices.contextMap);
        contextMapForBranch.remove(BRANCH_DATA);
        contextMapForBranch.put(SCORE_EVENTS_QUEUE, (ArrayDeque) new ArrayDeque<>());

        branchesData.add(new StartBranchDataContainer(startPosition, executionPlanId, context,
                new SystemContext(contextMapForBranch)));
    }

    /**
     * Removes the branches data and returns it
     */
    public List<StartBranchDataContainer> removeBranchesData() {
        return removeFromMap(BRANCH_DATA);
    }

    /**
     * @return a list of all branches ended.
     */
    public List<EndBranchDataContainer> getFinishedChildBranchesData() {
        return (List<EndBranchDataContainer>) removeFromMap(ExecutionParametersConsts.FINISHED_CHILD_BRANCHES_DATA);
    }

    public void putMetaData(Map<String, ? extends Serializable> metadata) {
        contextMap.put(METADATA, (Serializable) metadata);
    }

    public Map<String, ? extends Serializable> getMetaData() {
        return (Map<String, Serializable>) contextMap.get(METADATA);
    }

    public void setConsumerWorkerId(String consumerWorkerId) {
        contextMap.put(CONSUMER_WORKER_ID, consumerWorkerId);
    }

    public String removeConsumerWorkerId() {
        return removeFromMap(CONSUMER_WORKER_ID);
    }

    public void setProducerWorkerId(String producerWorkerId) {
        contextMap.put(PRODUCER_WORKER_ID, producerWorkerId);
    }

    public String removeProducerWorkerId() {
        return removeFromMap(PRODUCER_WORKER_ID);
    }

    public void setRobotId(String robotId) {
        contextMap.put(ROBOT_ID, robotId);
    }

    public String removeRobotId() {
        return removeFromMap(ROBOT_ID);
    }

    public void setRobotGroupName(String robotGroupName) {
        contextMap.put(ROBOT_GROUP_NAME, robotGroupName);
    }

    public String removeRobotGroupName() {
        return removeFromMap(ROBOT_GROUP_NAME);
    }

    public void setPreconditionNotFulfilled() {
        contextMap.put(PRECONDITION_NOT_FULFILLED, true);
    }

    public void removePreconditionNotFulfilled() {
        removeFromMap(PRECONDITION_NOT_FULFILLED);
    }

    public boolean getPreconditionNotFulfilled() {
        return getFromMap(PRECONDITION_NOT_FULFILLED) != null;
    }

    public void setMergeUserInputs(boolean mergeUserInputs) {
        contextMap.put(MERGE_USER_INPUTS, mergeUserInputs);
    }

    public boolean getMergeUserInputs() {
        Boolean mergeUserInputs = getFromMap(MERGE_USER_INPUTS);
        return mergeUserInputs != null && mergeUserInputs;
    }

    public boolean isEnterpriseMode() {
        Boolean enterprise = getFromMap(ENTERPRISE_MODE);
        return enterprise != null && enterprise;
    }

    public Double removeTotalRoiValue() { return removeFromMap(ExecutionParametersConsts.EXECUTION_TOTAL_ROI); }

    public void addRoiValue(Double roiValue) {
        Double currentRoiValue = (Double) contextMap.get(ExecutionParametersConsts.EXECUTION_TOTAL_ROI);
        if (currentRoiValue == null) {
            currentRoiValue = ExecutionParametersConsts.DEFAULT_ROI_VALUE;
        }
        contextMap.put(ExecutionParametersConsts.EXECUTION_TOTAL_ROI, currentRoiValue + roiValue);
    }

    private <T> T removeFromMap(String key) {
        //noinspection unchecked
        return (T) contextMap.remove(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ExecutionRuntimeServices that = (ExecutionRuntimeServices) o;

        return new EqualsBuilder()
                .append(this.contextMap, that.contextMap)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(this.contextMap)
                .toHashCode();
    }
}
