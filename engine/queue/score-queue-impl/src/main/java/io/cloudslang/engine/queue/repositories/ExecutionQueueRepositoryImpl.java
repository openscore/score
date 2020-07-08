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

package io.cloudslang.engine.queue.repositories;

import com.google.common.collect.Iterables;
import io.cloudslang.engine.data.IdentityGenerator;
import io.cloudslang.engine.queue.entities.ExecStatus;
import io.cloudslang.engine.queue.entities.ExecutionMessage;
import io.cloudslang.engine.queue.entities.Payload;
import io.cloudslang.engine.queue.entities.StartNewBranchPayload;
import io.cloudslang.engine.queue.services.StatementAwareJdbcTemplateWrapper;
import io.cloudslang.orchestrator.services.ExecutionStateService;
import java.util.ArrayList;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.CollectionUtils;

import static java.lang.Long.parseLong;

/**
 * User:
 * Date: 20/09/12
 * Time: 15:04
 */

@SuppressWarnings("FieldCanBeLocal")
public class ExecutionQueueRepositoryImpl implements ExecutionQueueRepository {

    private Logger logger = Logger.getLogger(getClass());

    private static final int PARTITION_SIZE = 250;

    private static final String MYSQL = "mysql";
    private static final String MSSQL = "Microsoft";

    final private String SELECT_FINISHED_STEPS_IDS =  " SELECT DISTINCT EXEC_STATE_ID FROM OO_EXECUTION_QUEUES " +
            " WHERE " +
            "        (STATUS = "+ExecStatus.TERMINATED.getNumber()+") OR " +
            "        (STATUS = "+ExecStatus.FAILED.getNumber()+") OR " +
            "        (STATUS = "+ExecStatus.FINISHED.getNumber()+")";

    final private String SELECT_CANCELED_STEPS_IDS = " SELECT DISTINCT EXEC_STATE_ID FROM OO_EXECUTION_QUEUES "
            + "WHERE EXEC_STATE_ID IN "
            + "(SELECT DISTINCT ESS.ID FROM OO_EXECUTION_STATES ESS JOIN OO_EXECUTION_STATE ES ON "
            + "ESS.MSG_ID = CAST(ES.EXECUTION_ID AS VARCHAR(255)) "
            + "WHERE ES.STATUS = 'PENDING_CANCEL')";

    final private String SELECT_CANCELED_STEPS_IDS_MYSQL = " SELECT DISTINCT EXEC_STATE_ID FROM OO_EXECUTION_QUEUES "
            + "WHERE EXEC_STATE_ID IN "
            + "(SELECT DISTINCT ESS.ID FROM OO_EXECUTION_STATES ESS JOIN OO_EXECUTION_STATE ES ON "
            + "ESS.MSG_ID = CAST(ES.EXECUTION_ID AS CHAR(255)) "
            + "WHERE ES.STATUS = 'PENDING_CANCEL')";

    final private String QUERY_DELETE_FINISHED_STEPS_FROM_QUEUES = "DELETE FROM OO_EXECUTION_QUEUES " +
            " WHERE EXEC_STATE_ID in (:ids)";

    final private String QUERY_DELETE_FINISHED_STEPS_FROM_STATES = "DELETE FROM OO_EXECUTION_STATES " +
            " WHERE ID in (:ids)";

    final private String QUERY_DELETE_EXECS_STATES_MAPPINGS = "DELETE FROM OO_EXECS_STATES_EXECS_MAPPINGS " +
            " WHERE EXEC_STATE_ID in (:ids)";

    final private String QUERY_MESSAGES_WITHOUT_ACK_SQL =
            "SELECT EXEC_STATE_ID,      " +
                    "       ASSIGNED_WORKER,      " +
                    "       EXEC_GROUP ,       " +
                    "       STATUS,       " +
                    "       MSG_SEQ_ID,   " +
                    "      CREATE_TIME " +
                    "  FROM  OO_EXECUTION_QUEUES q  " +
                    "  WHERE " +
                    "      (q.STATUS  = ? ) AND " +
                    "     (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "                  FROM OO_EXECUTION_QUEUES qq " +
                    "                  WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND " +
                    "                        qq.MSG_SEQ_ID > q.MSG_SEQ_ID" +
                    "                 )" +
                    "      ) AND " +
                    "      (q.MSG_VERSION < ?) ";


    final private String QUERY_COUNT_MESSAGES_WITHOUT_ACK_FOR_WORKER_SQL =
            "SELECT COUNT(*)  " +
                    "  FROM  OO_EXECUTION_QUEUES  q  " +
                    "  WHERE " +
                    "      (q.ASSIGNED_WORKER  = ? ) AND " +
                    "      (q.STATUS  = ? ) AND " +
                    "     (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "                  FROM OO_EXECUTION_QUEUES qq " +
                    "                  WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND " +
                    "                        qq.MSG_SEQ_ID > q.MSG_SEQ_ID " +
                    "                 )" +
                    "      ) AND " +
                    "      (q.MSG_VERSION < ?)  ";

    final private String QUERY_WORKER_LEGACY_MEMORY_HANDLING_SQL =
            "SELECT EXEC_STATE_ID,      " +
                    "       ASSIGNED_WORKER,      " +
                    "       EXEC_GROUP ,       " +
                    "       STATUS,       " +
                    "       PAYLOAD,       " +
                    "       MSG_SEQ_ID ,      " +
                    "       MSG_ID," +
                    "       q.CREATE_TIME " +
                    " FROM  OO_EXECUTION_QUEUES q,  " +
                    "      OO_EXECUTION_STATES s   " +
                    " WHERE  " +
                    "      (q.ASSIGNED_WORKER =  ?)  AND " +
                    "      (q.STATUS IN (:status)) AND " +
                    " 	   (s.ACTIVE = 1) AND " +
                    " (q.EXEC_STATE_ID = s.ID) AND " +
                    " (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "              FROM OO_EXECUTION_QUEUES qq " +
                    "              WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) " +
                    " ORDER BY q.CREATE_TIME  ";

    final private String QUERY_WORKER_SQL =
            "SELECT EXEC_STATE_ID, " +
                    "    ASSIGNED_WORKER, " +
                    "    EXEC_GROUP, " +
                    "    STATUS, " +
                    "    PAYLOAD, " +
                    "    MSG_SEQ_ID, " +
                    "    MSG_ID, " +
                    "    CREATE_TIME " +
                    "FROM (" +
                    "   SELECT EXEC_STATE_ID, " +
                    "       ASSIGNED_WORKER, " +
                    "       EXEC_GROUP, " +
                    "       STATUS, " +
                    "       PAYLOAD, " +
                    "       MSG_SEQ_ID, " +
                    "       MSG_ID, " +
                    "       q.CREATE_TIME, " +
                    "       SUM(PAYLOAD_SIZE) OVER (ORDER BY q.CREATE_TIME ASC) AS total " +
                    "   FROM OO_EXECUTION_QUEUES q, " +
                    "       OO_EXECUTION_STATES s " +
                    "   WHERE (q.ASSIGNED_WORKER = ?)  AND " +
                    "       (q.STATUS IN (:status)) AND " +
                    "       (s.PAYLOAD_SIZE < ?) AND " +
                    " 	    (s.ACTIVE = 1) AND " +
                    "       (q.EXEC_STATE_ID = s.ID) AND " +
                    "       (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "                 FROM  OO_EXECUTION_QUEUES qq " +
                    "                 WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) " +
                    "   ORDER BY q.CREATE_TIME" +
                    ") e " +
                    "WHERE total < ? ";

    final private String QUERY_WORKER_SQL_MSSQL =
            "SELECT EXEC_STATE_ID, " +
                    "    ASSIGNED_WORKER, " +
                    "    EXEC_GROUP, " +
                    "    STATUS, " +
                    "    PAYLOAD, " +
                    "    MSG_SEQ_ID, " +
                    "    MSG_ID, " +
                    "    CREATE_TIME " +
                    "FROM (" +
                    "   SELECT EXEC_STATE_ID, " +
                    "       ASSIGNED_WORKER, " +
                    "       EXEC_GROUP, " +
                    "       STATUS, " +
                    "       PAYLOAD, " +
                    "       MSG_SEQ_ID, " +
                    "       MSG_ID, " +
                    "       q.CREATE_TIME, " +
                    "       SUM(PAYLOAD_SIZE) OVER (ORDER BY q.CREATE_TIME ASC) AS total " +
                    "   FROM OO_EXECUTION_QUEUES q, " +
                    "       OO_EXECUTION_STATES s " +
                    "   WHERE (q.ASSIGNED_WORKER = ?)  AND " +
                    "       (q.STATUS IN (:status)) AND " +
                    "       (s.PAYLOAD_SIZE < ?) AND " +
                    " 	    (s.ACTIVE = 1) AND " +
                    "       (q.EXEC_STATE_ID = s.ID) AND " +
                    "       (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "                 FROM  OO_EXECUTION_QUEUES qq " +
                    "                 WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) " +
                    ") e " +
                    "WHERE total < ? ";

    final private String QUERY_WORKER_SQL_MYSQL =
            "SELECT EXEC_STATE_ID, " +
                    "    ASSIGNED_WORKER, " +
                    "    EXEC_GROUP, " +
                    "    STATUS, " +
                    "    PAYLOAD, " +
                    "    MSG_SEQ_ID, " +
                    "    MSG_ID, " +
                    "    CREATE_TIME " +
                    "FROM (" +
                    "   SELECT EXEC_STATE_ID, " +
                    "       ASSIGNED_WORKER, " +
                    "       EXEC_GROUP, " +
                    "       STATUS, " +
                    "       PAYLOAD, " +
                    "       MSG_SEQ_ID, " +
                    "       MSG_ID, " +
                    "       q.CREATE_TIME, " +
                    "       (@csum:=@csum + PAYLOAD_SIZE) AS total " +
                    "   FROM OO_EXECUTION_QUEUES q, " +
                    "       OO_EXECUTION_STATES s JOIN(SELECT @csum:=0) c " +
                    "   WHERE (q.ASSIGNED_WORKER = ?)  AND " +
                    "       (q.STATUS IN (:status)) AND " +
                    "       (s.PAYLOAD_SIZE < ?) AND " +
                    " 	    (s.ACTIVE = 1) AND " +
                    "       (q.EXEC_STATE_ID = s.ID) AND " +
                    "       (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "                 FROM  OO_EXECUTION_QUEUES qq " +
                    "                 WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) " +
                    "   ORDER BY q.CREATE_TIME " +
                    ") e " +
                    "WHERE total < ? ";

    final private String QUERY_WORKER_RECOVERY_SQL =
            "SELECT         EXEC_STATE_ID,      " +
                    "       ASSIGNED_WORKER,      " +
                    "       EXEC_GROUP,       " +
                    "       STATUS,       " +
                    "       PAYLOAD,       " +
                    "       MSG_SEQ_ID,      " +
                    "       MSG_ID," +
                    "       q.CREATE_TIME " +
                    " FROM  OO_EXECUTION_QUEUES q,  " +
                    "       OO_EXECUTION_STATES s1   " +
                    " WHERE  " +
                    "      (q.ASSIGNED_WORKER =  ?)  AND " +
                    "      (q.STATUS IN (:status)) AND " +
                    " q.EXEC_STATE_ID = s1.ID AND" +
                    " (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "              FROM OO_EXECUTION_QUEUES qq " +
                    "              WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) ";

    final private String QUERY_MESSAGES_BY_STATUSES =
            "SELECT EXEC_STATE_ID, " +
                    "  ASSIGNED_WORKER, " +
                    "  EXEC_GROUP , " +
                    "  STATUS, " +
                    "  MSG_SEQ_ID, " +
                    "  CREATE_TIME " +
                    "FROM  OO_EXECUTION_QUEUES q  " +
                    "WHERE STATUS IN (:status) AND " +
                    "  NOT EXISTS (" +
                    "     SELECT qq.MSG_SEQ_ID " +
                    "     FROM OO_EXECUTION_QUEUES qq " +
                    "     WHERE" +
                    "         qq.EXEC_STATE_ID = q.EXEC_STATE_ID" +
                    "         AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID" +
                    "  )";
    final private String BUSY_WORKERS_SQL =
            "SELECT ASSIGNED_WORKER      " +
                    " FROM  OO_EXECUTION_QUEUES q  " +
                    " WHERE  " +
                    "      (q.STATUS IN (:status)) AND " +
                    " (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
                    "              FROM OO_EXECUTION_QUEUES qq " +
                    "              WHERE (qq.EXEC_STATE_ID = q.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > q.MSG_SEQ_ID)) " +
                    " GROUP BY ASSIGNED_WORKER";

    final private String INSERT_EXEC_STATE = "INSERT INTO OO_EXECUTION_STATES  (ID, MSG_ID,  PAYLOAD, PAYLOAD_SIZE, CREATE_TIME, ACTIVE) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)";

    final private String INSERT_QUEUE = "INSERT INTO OO_EXECUTION_QUEUES (ID, EXEC_STATE_ID, ASSIGNED_WORKER, EXEC_GROUP, STATUS,MSG_SEQ_ID, CREATE_TIME,MSG_VERSION) VALUES (?, ?, ?, ?, ?, ?,?,?)";

	final private String INSERT_EXECUTION_STATE_MAPPING = "INSERT INTO OO_EXECS_STATES_EXECS_MAPPINGS (ID, EXEC_STATE_ID, EXEC_ID) VALUES (?, ?, ?)";

    private static final String QUERY_PAYLOAD_BY_EXECUTION_IDS = "SELECT ID, PAYLOAD FROM OO_EXECUTION_STATES WHERE ID IN (:IDS)";

    private static final String FIND_OLD_STATES =
            "SELECT q.EXEC_STATE_ID, CREATE_TIME, MSG_SEQ_ID, ASSIGNED_WORKER, EXEC_GROUP, STATUS " +
            "FROM OO_EXECUTION_QUEUES q, " +
            "  (SELECT EXEC_STATE_ID FROM OO_EXECUTION_QUEUES qt WHERE (CREATE_TIME < ?) AND " +
            "     (STATUS = " + ExecStatus.ASSIGNED.getNumber() + ") AND " +
            "          (NOT EXISTS (SELECT qq.MSG_SEQ_ID " +
            "              FROM OO_EXECUTION_QUEUES qq " +
            "              WHERE (qq.EXEC_STATE_ID = qt.EXEC_STATE_ID) AND qq.MSG_SEQ_ID > qt.MSG_SEQ_ID)) " +
            "  ) t " +
            "WHERE (STATUS IN (" + ExecStatus.ASSIGNED.getNumber() + ", " + ExecStatus.PENDING.getNumber() + ")) AND " +
            "q.EXEC_STATE_ID = t.EXEC_STATE_ID";


    private static final String FIND_EXEC_IDS = "SELECT DISTINCT MSG_ID FROM OO_EXECUTION_STATES WHERE ID IN (:IDS)";

    //We use dedicated JDBC templates for each query since JDBCTemplate is state-full object and we have different settings for each query.
    private StatementAwareJdbcTemplateWrapper pollJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper pollForRecoveryJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper pollMessagesWithoutAckJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper getFinishedExecStateIdsJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper getCanceledExecStateIdsJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper countMessagesWithoutAckForWorkerJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper findByStatusesJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper findLargeJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper findExecIDsJdbcTemplate;
    private StatementAwareJdbcTemplateWrapper getFirstPendingBranchJdbcTemplate;

	private JdbcTemplate insertExecutionJdbcTemplate;
	private JdbcTemplate deleteFinishedStepsJdbcTemplate;
	private JdbcTemplate findPayloadByExecutionIdsJdbcTemplate;
	private JdbcTemplate getBusyWorkersJdbcTemplate;
    private JdbcTemplate updateExecutionStateStatusJdbcTemplate;
    private JdbcTemplate deletePendingExecutionStateJdbcTemplate;

    @Autowired
    private IdentityGenerator idGen;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ExecutionStateService executionStateService;

    private boolean useLargeMessageQuery = true;

    private String workerQuery;

    private String cancelExecQuery;

    @PostConstruct
    public void init() {
        //We use dedicated JDBCTemplates for each query since JDBCTemplate is state-full object and we have different settings for each query.
        pollJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "pollJdbcTemplate");
        pollForRecoveryJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "pollForRecoveryJdbcTemplate");
        pollMessagesWithoutAckJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "pollMessagesWithoutAckJdbcTemplate");
        getFinishedExecStateIdsJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "getFinishedExecStateIdsJdbcTemplate");
        getCanceledExecStateIdsJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "getCanceledExecStateIdsJdbcTemplate");
        countMessagesWithoutAckForWorkerJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "countMessagesWithoutAckForWorkerJdbcTemplate");
        findByStatusesJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "findByStatusesJdbcTemplate");
        findLargeJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "findLargeJdbcTemplate");
        findExecIDsJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "findExecIDsJdbcTemplate");
        getFirstPendingBranchJdbcTemplate = new StatementAwareJdbcTemplateWrapper(dataSource, "getFirstPendingBranchJdbcTemplate");

        insertExecutionJdbcTemplate = new JdbcTemplate(dataSource);
        deleteFinishedStepsJdbcTemplate = new JdbcTemplate(dataSource);
        findPayloadByExecutionIdsJdbcTemplate = new JdbcTemplate(dataSource);
        getBusyWorkersJdbcTemplate = new JdbcTemplate(dataSource);
        updateExecutionStateStatusJdbcTemplate = new JdbcTemplate(dataSource);
        deletePendingExecutionStateJdbcTemplate = new JdbcTemplate(dataSource);

        useLargeMessageQuery = Boolean.parseBoolean(System.getProperty("score.poll.use.large.message.query", "true"));

        String dbms = getDatabaseProductName();

        cancelExecQuery = isMysql(dbms) ? SELECT_CANCELED_STEPS_IDS_MYSQL : SELECT_CANCELED_STEPS_IDS;

        if (useLargeMessageQuery) {
            if (isMssql(dbms)) {
                workerQuery = QUERY_WORKER_SQL_MSSQL;
            } else if (isMysql(dbms)) {
                workerQuery = QUERY_WORKER_SQL_MYSQL;

            } else {
                workerQuery = QUERY_WORKER_SQL;
            }

            try {
                // testing query
                poll("worker1", 1, 1, ExecStatus.ASSIGNED);
            } catch (RuntimeException ex) {
                // query failed, fallback on old mechanism
                useLargeMessageQuery = false;
                logger.info("Large message poll query failed" + ex.getMessage());
            }
        }

        if (!useLargeMessageQuery) {
            workerQuery = QUERY_WORKER_LEGACY_MEMORY_HANDLING_SQL;
        }

        logger.info("Poll using large message query: " + useLargeMessageQuery);
    }

    @Override
    public long generateExecStateId() {
        return idGen.next();
    }

    @Override
    public void insertExecutionStates(final List<ExecutionMessage> stateMessages) {
        String insertExecStateSQL = INSERT_EXEC_STATE;
        insertExecutionJdbcTemplate.batchUpdate(insertExecStateSQL, new BatchPreparedStatementSetter() {

			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				ExecutionMessage msg = stateMessages.get(i);
				ps.setLong(1, msg.getExecStateId());
				ps.setString(2, msg.getMsgId());
				ps.setBytes(3, msg.getPayload().getData());
                ps.setLong(4, msg.getPayloadSize());
                ps.setInt(5, msg.isActive() ? 1 : 0);
			}

            @Override
            public int getBatchSize() {
                return stateMessages.size();
            }
        });
    }

    @Override
    public void insertExecutionQueue(final List<ExecutionMessage> messages, final long version) {
        // insert execution queue table
        // id, exec_state_id, assigned_worker, status, create_time
        String insertQueueSQL = INSERT_QUEUE;

        long t = System.currentTimeMillis();
        insertExecutionJdbcTemplate.batchUpdate(insertQueueSQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ExecutionMessage msg = messages.get(i);
                ps.setLong(1, idGen.next());
                ps.setLong(2, msg.getExecStateId());
                ps.setString(3, msg.getWorkerId());
                ps.setString(4, msg.getWorkerGroup());
                ps.setInt(5, msg.getStatus().getNumber());
                ps.setInt(6, msg.getMsgSeqId());
                ps.setLong(7, Calendar.getInstance().getTimeInMillis());
                ps.setLong(8, version);
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        });
        t = System.currentTimeMillis() - t;
        if (logger.isDebugEnabled()) logger.debug("Insert to queue: " + messages.size() + "/" + t + " messages/ms");
    }

    @Override
    public void insertNotActiveExecutionsQueues(List<ExecutionMessage> notActiveMessages) {
        insertExecutionJdbcTemplate.batchUpdate(INSERT_EXECUTION_STATE_MAPPING, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement preparedStatement, int i) throws SQLException {
                ExecutionMessage executionMessage = notActiveMessages.get(i);
                preparedStatement.setLong(1, idGen.next());
                preparedStatement.setLong(2, executionMessage.getExecStateId());
                preparedStatement.setLong(3, parseLong(executionMessage.getMsgId()));
            }

            @Override
            public int getBatchSize() {
                return notActiveMessages.size();
            }
        });
    }

    @Override
    public StartNewBranchPayload getFirstPendingBranch(final long executionId) {
        final String sql = "SELECT ID, EXEC_STATE_ID FROM OO_EXECS_STATES_EXECS_MAPPINGS WHERE EXEC_ID = ?";
        getFirstPendingBranchJdbcTemplate.setStatementBatchSize(1);
        Object[] inputs = {executionId};
        StartNewBranchPayload startNewBranchPayload = null;
        try {
            startNewBranchPayload = getFirstPendingBranchJdbcTemplate.queryForObject(sql, inputs,
                    (resultSet, rowNumber) -> new StartNewBranchPayload(resultSet.getLong("EXEC_STATE_ID"), resultSet.getLong("ID")));
        } catch (EmptyResultDataAccessException ignored) {
        }
        return startNewBranchPayload;
    }

    @Override
    public void activatePendingExecutionStateForAnExecution(long executionId) {
        final String sql = "UPDATE OO_EXECUTION_STATES SET ACTIVE = 1 WHERE ID = ?";
        Object[] args = {executionId};
        updateExecutionStateStatusJdbcTemplate.update(sql, args);
    }

    @Override
    public void deletePendingExecutionState(long executionStatesId) {
        final String sql = "DELETE FROM OO_EXECS_STATES_EXECS_MAPPINGS WHERE ID = ?";
        Object[] args = {executionStatesId};
        deletePendingExecutionStateJdbcTemplate.update(sql, args);
    }


    @Override
    public List<ExecutionMessage> pollRecovery(String workerId, int maxSize, ExecStatus... statuses) {
        pollForRecoveryJdbcTemplate.setStatementBatchSize(maxSize);
        try {
            // prepare the sql statement
            String sqlStatPrvTable = QUERY_WORKER_RECOVERY_SQL
                    .replaceAll(":status", StringUtils.repeat("?", ",", statuses.length));

            // prepare the argument
            Object[] values = new Object[statuses.length + 1];
            values[0] = workerId;
            int i = 1;

            for (ExecStatus status : statuses) {
                values[i++] = status.getNumber();
            }

            return doSelectWithTemplate(pollForRecoveryJdbcTemplate, sqlStatPrvTable, new ExecutionMessageRowMapper(), values);
        } finally {
            pollForRecoveryJdbcTemplate.clearStatementBatchSize();
        }
    }

    @Override
    public List<ExecutionMessage> poll(
            String workerId, int maxSize, long workerPollingMemory, ExecStatus... statuses) {

        Object[] args = useLargeMessageQuery ?
                            preparePollArgs(workerId, workerPollingMemory, statuses) :
                            prepareStdPollArgs(workerId, statuses);

        String sqlStat = workerQuery.replaceAll(":status", StringUtils.repeat("?", ",", statuses.length));

        return executePoll(maxSize, sqlStat, args);
    }

    private String getDatabaseProductName() {
        String dbms = "";
        try {
            dbms = (String) JdbcUtils.extractDatabaseMetaData(dataSource, "getDatabaseProductName");

            logger.info("Database product name: " + dbms);
        } catch (MetaDataAccessException e) {
            logger.warn("Database type could not be determined!", e);
        }

        return dbms;
    }

    private boolean isMssql(String dbms) {
        return StringUtils.containsIgnoreCase(dbms, MSSQL);
    }

    private boolean isMysql(String dbms) {
        return StringUtils.containsIgnoreCase(dbms, MYSQL);
    }

    private List<ExecutionMessage> executePoll(int maxSize, String sql, Object[] args) {

        pollJdbcTemplate.setStatementBatchSize(maxSize);

        try {
            RowMapper<ExecutionMessage> rowMapper = (rs, rowNum) -> new ExecutionMessage(
                    rs.getLong("EXEC_STATE_ID"),
                    rs.getString("ASSIGNED_WORKER"),
                    rs.getString("EXEC_GROUP"),
                    rs.getString("MSG_ID"),
                    ExecStatus.find(rs.getInt("STATUS")),
                    new Payload(rs.getBytes("PAYLOAD")),
                    rs.getInt("MSG_SEQ_ID"),
                    rs.getLong("CREATE_TIME"));

            List<ExecutionMessage> executionMessages = doSelectWithTemplate(
                    pollJdbcTemplate,
                    sql,
                    rowMapper,
                    args);

            return executionMessages;
        } finally {
            pollJdbcTemplate.clearStatementBatchSize();
        }
    }

    private Object[] prepareStdPollArgs(String workerId, ExecStatus[] statuses) {

        Object[] args = new Object[statuses.length + 1];

        int i = 0;

        args[i++] = workerId;
        for (ExecStatus status : statuses) {
            args[i++] = status.getNumber();
        }

        return args;
    }

    private Object[] preparePollArgs(String workerId, long workerPollingMemory, ExecStatus[] statuses) {

        Object[] args = new Object[statuses.length + 3];

        int i = 0;

        args[i++] = workerId;
        for (ExecStatus status : statuses) {
            args[i++] = status.getNumber();
        }

        args[i++] = workerPollingMemory;
        args[i++] = workerPollingMemory;

        return args;
    }

    @Override
    public void deleteFinishedSteps(Set<Long> stepsIds) {
        if (stepsIds == null || stepsIds.size() == 0) {
            return;
        }
        Set<Long> result = getCanceledExecStateIds();
        if (!CollectionUtils.isEmpty(result)) {
            stepsIds.addAll(result);
        }
	//delete 1K at a time


        // fill the set
        Iterable<List<Long>> lists = Iterables.partition(stepsIds, 1000);
        Iterator itr = lists.iterator();

        while(itr.hasNext())
        {
            List ids = (List)itr.next();
		// Access STATES first and then QUEUES - same order as ExecutionQueueService#enqueue (prevents deadlocks on MSSQL)
		String query = QUERY_DELETE_FINISHED_STEPS_FROM_STATES.replaceAll(":ids", StringUtils.repeat("?", ",", ids.size()));

		Object[] args = ids.toArray(new Object[ids.size()]);
		logSQL(query, args);

		int deletedRows = deleteFinishedStepsJdbcTemplate.update(query, args); //MUST NOT set here maxRows!!!! It must delete all without limit!!!

		if(logger.isDebugEnabled()){
		    logger.debug("Deleted " + deletedRows + " rows of finished steps from OO_EXECUTION_STATES table.");
		}

		query = QUERY_DELETE_FINISHED_STEPS_FROM_QUEUES.replaceAll(":ids", StringUtils.repeat("?", ",", ids.size()));
		logSQL(query,args);

		deletedRows = deleteFinishedStepsJdbcTemplate.update(query, args); //MUST NOT set here maxRows!!!! It must delete all without limit!!!

		if(logger.isDebugEnabled()){
		    logger.debug("Deleted " + deletedRows + " rows of finished steps from OO_EXECUTION_QUEUES table.");
		}

		if (!CollectionUtils.isEmpty(result)) {
		    query = QUERY_DELETE_EXECS_STATES_MAPPINGS.replace(":ids", StringUtils.repeat("?", ",", ids.size()));
		    logSQL(query, args);
		    deletedRows = deleteFinishedStepsJdbcTemplate.update(query, args);
		    if (logger.isDebugEnabled()) {
			logger.debug("Deleted " + deletedRows + " rows of finished steps from OO_EXECS_STATES_EXECS_MAPPINGS table.");
		    }
		    executionStateService.deleteCanceledExecutionStates();
		}
		itr.next();
        }
    }

    @Override
    public Set<Long> getFinishedExecStateIds() {
        getFinishedExecStateIdsJdbcTemplate.setStatementBatchSize(1_000_000);
        try {
            List<Long> result = doSelectWithTemplate(getFinishedExecStateIdsJdbcTemplate, SELECT_FINISHED_STEPS_IDS, new SingleColumnRowMapper<>(Long.class));

            return new HashSet<>(result);
        } finally {
            getFinishedExecStateIdsJdbcTemplate.clearStatementBatchSize();
        }
    }

    public Set<Long> getCanceledExecStateIds() {
        getCanceledExecStateIdsJdbcTemplate.setStatementBatchSize(1_000_000);
        try {
            List<Long> result = doSelectWithTemplate(getCanceledExecStateIdsJdbcTemplate, cancelExecQuery,
                    new SingleColumnRowMapper<>(Long.class));

            return new HashSet<>(result);
        } finally {
            getCanceledExecStateIdsJdbcTemplate.clearStatementBatchSize();
        }
    }

    public List<ExecutionMessage> pollMessagesWithoutAck(int maxSize, long minVersionAllowed) {
        pollMessagesWithoutAckJdbcTemplate.setStatementBatchSize(maxSize);

        try {
            String sqlStat = QUERY_MESSAGES_WITHOUT_ACK_SQL;
            Object[] values = {ExecStatus.SENT.getNumber(), minVersionAllowed};

            long time = System.currentTimeMillis();
            List<ExecutionMessage> result = pollMessagesWithoutAckJdbcTemplate.query(sqlStat, values, new ExecutionMessageWithoutPayloadRowMapper());

            if (!result.isEmpty()) {
                logger.warn("Pool " + result.size() + " messages without ack, version = " + minVersionAllowed);
                if (logger.isDebugEnabled()) {
                    for (ExecutionMessage msg : result) {
                        logger.debug("Recovery msg [" + msg.getExecStateId() + "," + msg.getStatus() + "," + msg.getCreateDate() + "]");
                    }
                }
            }
            if (logger.isTraceEnabled())
                logger.trace("Query [" + sqlStat + "] took " + (System.currentTimeMillis() - time) + " ms");

            if (logger.isDebugEnabled()) {
                logger.debug("Got msg without ack :" + result.size() + ",for version:" + minVersionAllowed);
            }
            return result;
        } finally {
            pollMessagesWithoutAckJdbcTemplate.clearStatementBatchSize();
        }
    }

    public Integer countMessagesWithoutAckForWorker(int maxSize, long minVersionAllowed, String workerUuid) {
        countMessagesWithoutAckForWorkerJdbcTemplate.setStatementBatchSize(maxSize);
        try {
            Object[] values = {workerUuid, ExecStatus.SENT.getNumber(), minVersionAllowed};

            long time = System.currentTimeMillis();
            Integer result = countMessagesWithoutAckForWorkerJdbcTemplate.queryForObject(QUERY_COUNT_MESSAGES_WITHOUT_ACK_FOR_WORKER_SQL, values,Integer.class);

            if (logger.isTraceEnabled())
                logger.trace("Query [" + QUERY_COUNT_MESSAGES_WITHOUT_ACK_FOR_WORKER_SQL + "] took " + (System.currentTimeMillis() - time) + " ms");

            if (logger.isDebugEnabled()) {
                logger.debug("Got msg without ack :" + result + ",for version:" + minVersionAllowed + ",for worker:" + workerUuid);
            }
            return result;
        } finally {
            countMessagesWithoutAckForWorkerJdbcTemplate.clearStatementBatchSize();
        }
    }

    @Override
    public Map<Long, Payload> findPayloadByExecutionIds(Long... ids) {
        String qMarks = StringUtils.repeat("?", ",", ids.length);
        String sqlStat = QUERY_PAYLOAD_BY_EXECUTION_IDS.replace(":IDS", qMarks);

        final Map<Long, Payload> result = new HashMap<>();
        findPayloadByExecutionIdsJdbcTemplate.query(sqlStat, ids, new RowCallbackHandler() {
            @Override
            public void processRow(ResultSet resultSet) throws SQLException {
                result.put(
                        resultSet.getLong(1),
                        new Payload(resultSet.getBytes("payload"))
                );
            }
        });

        return result;
    }

    @Override
    public List<ExecutionMessage> findByStatuses(int maxSize, ExecStatus... statuses) {
        findByStatusesJdbcTemplate.setStatementBatchSize(maxSize);

        // prepare the sql statement
        String sqlStat = QUERY_MESSAGES_BY_STATUSES
                .replaceAll(":status", StringUtils.repeat("?", ",", statuses.length)); // set ? according to the number of parameters

        Object[] values = new Object[statuses.length];
        int i = 0;
        for (ExecStatus status : statuses) {
            values[i++] = status.getNumber();
        }

        try {
            return doSelectWithTemplate(findByStatusesJdbcTemplate, sqlStat, new ExecutionMessageWithoutPayloadRowMapper(), values);
        } catch (RuntimeException ex) {
            logger.error(sqlStat, ex);
            throw ex;
        } finally {
            findByStatusesJdbcTemplate.clearStatementBatchSize();
        }
    }

    @Override
    public List<String> getBusyWorkers(ExecStatus... statuses) {
        // prepare the sql statement
        String sqlStat = BUSY_WORKERS_SQL
                .replaceAll(":status", StringUtils.repeat("?", ",", statuses.length));
        // prepare the argument
        Object[] values = new Object[statuses.length];
        int i=0;
        for (ExecStatus status : statuses) {
            values[i] = status.getNumber();
        }
        return doSelectWithTemplate(getBusyWorkersJdbcTemplate, sqlStat, new BusyWorkerRowMapper(), values);
    }

    @Override
    public List<ExecutionMessage> findOldMessages(long timestamp) {

        return findLargeJdbcTemplate.query(FIND_OLD_STATES, new Object[]{timestamp},
                (rs, rowNum) -> {
                    ExecutionMessage msg = new ExecutionMessage(
                            rs.getLong("EXEC_STATE_ID"),
                            rs.getString("ASSIGNED_WORKER"),
                            rs.getString("EXEC_GROUP"),
                            null,
                            ExecStatus.find(rs.getInt("STATUS")),
                            null,
                            rs.getInt("MSG_SEQ_ID"),
                            rs.getLong("CREATE_TIME"));
                    return msg;
                }
        );
    }

    @Override
    public Set<Long> getExecutionIdsForExecutionStateIds(Set<Long> execStateIds) {

        Set<Long> result = new HashSet<>();

        for (List<Long> part: Iterables.partition(execStateIds, PARTITION_SIZE)) {

            String qMarks = StringUtils.repeat("?", ",", part.size());
            String sqlStat = FIND_EXEC_IDS.replace(":IDS", qMarks);

            List<Long> execIds = findExecIDsJdbcTemplate.query(sqlStat, part.toArray(),
                    (rs, rowNum) -> rs.getLong("MSG_ID")
            );

            result.addAll(execIds);
        }

        return result;
    }

    private class BusyWorkerRowMapper implements RowMapper<String> {
        @Override
        public String mapRow(ResultSet rs, int rowNum) throws SQLException {
            return rs.getString("ASSIGNED_WORKER");
        }
    }



    private class ExecutionMessageRowMapper implements RowMapper<ExecutionMessage> {
        @Override
        public ExecutionMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExecutionMessage(rs.getLong("EXEC_STATE_ID"),
                    rs.getString("ASSIGNED_WORKER"),
                    rs.getString("EXEC_GROUP"),
                    rs.getString("MSG_ID"),
                    ExecStatus.find(rs.getInt("STATUS")),
                    new Payload(rs.getBytes("PAYLOAD")),
                    rs.getInt("MSG_SEQ_ID"),
                    rs.getLong("CREATE_TIME"));
        }
    }

    private class ExecutionMessageWithoutPayloadRowMapper implements RowMapper<ExecutionMessage> {
        @Override
        public ExecutionMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExecutionMessage(rs.getLong("EXEC_STATE_ID"),
                    rs.getString("ASSIGNED_WORKER"),
                    rs.getString("EXEC_GROUP"),
                    "-1",
                    ExecStatus.find(rs.getInt("STATUS")),
                    null,
                    rs.getInt("MSG_SEQ_ID"),
                    rs.getLong("CREATE_TIME"));
        }
    }

    private <T> List<T> doSelectWithTemplate(JdbcTemplate jdbcTemplate, String sql, RowMapper<T> rowMapper, Object... params) {
        logSQL(sql,params);
        try {
            long t = System.currentTimeMillis();
            List<T> result = jdbcTemplate.query(sql, params, rowMapper);
            if (logger.isDebugEnabled())
                logger.debug("Fetched result: " + result.size() + '/' + (System.currentTimeMillis() - t) + " rows/ms");
            return result;
        } catch (RuntimeException ex) {
            logger.error("Failed to execute query: " + sql, ex);
            throw ex;
        }
    }

    private void logSQL(String query, Object... params) {
        if (logger.isDebugEnabled()) {
            logger.debug("Execute SQL: " + query);
            if (params != null && params.length > 1) logger.debug("Parameters : " + Arrays.toString(params));
        }
    }
}
