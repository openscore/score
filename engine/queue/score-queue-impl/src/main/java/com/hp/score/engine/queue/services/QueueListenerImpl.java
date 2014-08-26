package com.hp.score.engine.queue.services;

import com.hp.score.engine.queue.entities.ExecutionMessage;
import com.hp.score.engine.queue.entities.ExecutionMessageConverter;
import com.hp.oo.enginefacade.execution.ExecutionSummary;
import com.hp.oo.enginefacade.execution.PauseReason;
import com.hp.oo.internal.sdk.execution.Execution;
import com.hp.score.orchestrator.services.PauseResumeService;
import com.hp.score.orchestrator.services.SplitJoinService;
import com.hp.score.events.EventBus;
import com.hp.score.events.ScoreEvent;
import com.hp.score.orchestrator.services.ExecutionStateService;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: Amit Levin
 * Date: 19/09/12
 * Time: 15:14
 */
public class QueueListenerImpl implements QueueListener {

	private static Logger logger = Logger.getLogger(QueueListenerImpl.class);

	@Autowired
	private ExecutionStateService executionStateService;

	@Autowired
	private ExecutionMessageConverter executionMessageConverter;

	@Autowired
	private EventBus eventBus;

	@Autowired
	private SplitJoinService splitJoinService;

	@Autowired
	private ScoreEventFactory scoreEventFactory;

	@Autowired
	private PauseResumeService pauseResumeService;

	@Override
	public void onEnqueue(List<ExecutionMessage> messages, int queueSize) {
		if (logger.isDebugEnabled()) {
			logger.debug("Enqueue " + messages.size() + " messages:");
			logger.debug("queue size: " + queueSize);
			if (logger.isTraceEnabled()) {
				for (ExecutionMessage msg : messages) {
					logger.trace("Enqueue msgId= " + msg.getMsgUniqueId() + ":" + msg.getMsgSeqId() + ",workerId=" + msg.getWorkerId() + ",status=" + msg.getStatus());
				}
			}
		}
	}

	@Override
	public void onPoll(List<ExecutionMessage> messages, int queueSize) {
		if (logger.isDebugEnabled()) {
			logger.debug("poll " + messages.size() + " messages:");
			logger.debug("queue size: " + queueSize);
			if (logger.isTraceEnabled()) {
				for (ExecutionMessage msg : messages) {
					logger.trace("Poll msgId= " + msg.getMsgUniqueId() + ":" + msg.getMsgSeqId() + ",workerId=" + msg.getWorkerId() + ",status=" + msg.getStatus());
				}
			}
		}
	}

	@Override
	public void onTerminated(List<ExecutionMessage> messages) {
		ScoreEvent[] scoreEvents = handleTerminatedMessages(messages);
		if (scoreEvents.length > 0) {
			eventBus.dispatch(scoreEvents);
		}
	}

	private ScoreEvent[] handleTerminatedMessages(List<ExecutionMessage> messages) {
		List<ScoreEvent> scoreEvents = new ArrayList<>(messages.size());

		for (ExecutionMessage executionMessage : messages) {
			handleTerminatedMessage(executionMessage);
			Execution execution = extractExecution(executionMessage);
			scoreEvents.add(scoreEventFactory.createFinishedEvent(execution));//TODO:??? also if it is branch??? need to check this
		}
		return scoreEvents.toArray(new ScoreEvent[scoreEvents.size()]);
	}

	private void handleTerminatedMessage(ExecutionMessage executionMessage) {
		//Only delete parent runs and not branches because the Terminated event of branches should not cause the
		//deletion of the entire run
		if (!isBranchExecution(executionMessage)) {
			executionStateService.deleteExecutionState(Long.valueOf(executionMessage.getMsgId()), ExecutionSummary.EMPTY_BRANCH);
		} else {
			Execution execution = extractExecution(executionMessage);
			finishBranchExecution(execution);
		}
	}

	//The logic for this method was copied from oo's FinishedFlowEventsListener
	//Does the endBranch only for branches that are not non-blocking (parallel, multi-instance and sub-flows)
	private void finishBranchExecution(Execution execution) {
		if (execution.isNewBranchMechanism()) {
			splitJoinService.endBranch(Arrays.asList(execution));
		}
	}

	/**
	 * Returns true when the execution is a branch with the new branch mechanism
	 * It will return true for executions of parallel, multi-instance and sub-flows but not for non-blocking
	 * (which is the old mechanism)
	 */
	private boolean isBranch(Execution execution) {
		return execution.isBranch() && execution.isNewBranchMechanism();
	}

	/*
	Parses the payload of the execution message and returns true if the execution is marked as a branch
	 */
	private boolean isBranchExecution(ExecutionMessage executionMessage) {
		Execution execution = extractExecution(executionMessage);
		return execution != null && execution.isBranch();
	}

	private Execution extractExecution(ExecutionMessage executionMessage) {
		try {
			return executionMessageConverter.extractExecution(executionMessage.getPayload());
		} catch (IOException e) {
			logger.error("Unable to parse payload from execution message");
			return null;
		}
	}

	@Override
	public void onFailed(List<ExecutionMessage> messages) {
		deleteExecutionStateObjects(messages);
		ScoreEvent[] events = createFailureEvents(messages);
		if (events.length > 0) {
			eventBus.dispatch(events);
		}
	}

	private Long pauseExecution(Execution execution) {
		String branchId = execution.getSystemContext().getBrunchId();

		ExecutionSummary pe = pauseResumeService.readPausedExecution(execution.getExecutionId(), branchId);

		//Check if this execution is not paused already (by user)
		Long pauseId;
		if (pe == null) {
			pauseId = pauseResumeService.pauseExecution(execution.getExecutionId(), branchId, PauseReason.NO_WORKERS_IN_GROUP);
			pauseResumeService.writeExecutionObject(execution.getExecutionId(), branchId, execution);
		} else {
			pauseId = null;
			//If yes - just write the object
			pauseResumeService.writeExecutionObject(execution.getExecutionId(), branchId, execution);
		}
		return pauseId;
	}

	private ScoreEvent[] createFailureEvents(List<ExecutionMessage> messages) {
		Execution execution;
		List<ScoreEvent> events = new ArrayList<>(messages.size());
		for (ExecutionMessage executionMessage : messages) {
			execution = extractExecution(executionMessage);
			if (failedBecauseNoWorker(executionMessage)) {
				Long pauseID = pauseExecution(execution);
				events.add(scoreEventFactory.createNoWorkerEvent(execution, pauseID));
			} else if (isBranch(execution)) {
				finishBranchExecution(execution);
				events.add(scoreEventFactory.createFailedBranchEvent(execution));
			} else {
				events.add(scoreEventFactory.createFailureEvent(execution));
			}
		}
		return events.toArray(new ScoreEvent[events.size()]);
	}

	private void deleteExecutionStateObjects(List<ExecutionMessage> messages) {
		for (ExecutionMessage executionMessage : messages) {
			if (!failedBecauseNoWorker(executionMessage)) {
				executionStateService.deleteExecutionState(Long.valueOf(executionMessage.getMsgId()), ExecutionSummary.EMPTY_BRANCH);
			}
		}
	}

	private boolean failedBecauseNoWorker(ExecutionMessage executionMessage) {
		Execution execution = extractExecution(executionMessage);
		return execution != null && !StringUtils.isEmpty(execution.getSystemContext().getNoWorkerInGroupName());
	}

}
