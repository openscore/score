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

package io.cloudslang.engine.node.services;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.cloudslang.engine.node.entities.QueueDetails;
import io.cloudslang.engine.node.entities.WorkerKeepAliveInfo;
import io.cloudslang.engine.node.entities.WorkerNode;
import io.cloudslang.engine.node.repositories.WorkerNodeRepository;
import io.cloudslang.engine.versioning.services.VersionService;
import io.cloudslang.score.api.nodes.WorkerStatus;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Maps.newHashMapWithExpectedSize;


public class WorkerNodeServiceImpl implements WorkerNodeService {

    private static final Logger logger = LogManager.getLogger(WorkerNodeServiceImpl.class);

    private static final long MAX_VERSION_GAP_ALLOWED = Long.getLong("max.allowed.version.gap.worker.recovery", 2);
    private static final boolean disableMonitoring = Boolean.getBoolean("global.worker.monitoring.disable");
    private static final String MSG_RECOVERY_VERSION_NAME = "MSG_RECOVERY_VERSION";

    @Autowired
    private WorkerNodeRepository workerNodeRepository;

    @Autowired
    private WorkerLockService workerLockService;

    @Autowired
    private VersionService versionService;

    @Autowired(required = false)
    private List<LoginListener> loginListeners;

    @Autowired
    private QueueConfigurationDataService queueConfigurationDataService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PostConstruct
    void setWorkerMonitoring(){
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        if(disableMonitoring) {
            logger.info("Monitoring is disabled,setting busyness status as not available for all workers");
            transactionTemplate.executeWithoutResult(transactionStatus -> readAllWorkersUuids().stream().forEach(uuid -> updateWorkerBusynessValue(uuid,"NA") ));
        }
    }
//readAllWorkersUuids().stream().forEach(uuid -> updateWorkerBusynessValue(uuid,"NA") );

    @Override
    @Transactional
    public String keepAlive(String uuid) {
        WorkerNode worker = readByUUID(uuid);
        worker.setAckTime(new Date());
        String wrv = worker.getWorkerRecoveryVersion();
        long version = versionService.getCurrentVersion(MSG_RECOVERY_VERSION_NAME);
        worker.setAckVersion(version);
        if (!worker.getStatus().equals(WorkerStatus.IN_RECOVERY)) {
            worker.setStatus(WorkerStatus.RUNNING);
        }
        logger.debug("Got keepAlive for Worker with uuid=" + uuid + " and update its ackVersion to " + version);
        return wrv;
    }

    @Override
    @Transactional
    public WorkerKeepAliveInfo newKeepAlive(String uuid) {
        WorkerNode worker = readByUUID(uuid);
        worker.setAckTime(new Date());
        long version = versionService.getCurrentVersion(MSG_RECOVERY_VERSION_NAME);
        worker.setAckVersion(version);
        if (!worker.getStatus().equals(WorkerStatus.IN_RECOVERY)) {
            worker.setStatus(WorkerStatus.RUNNING);
        }
        boolean active = worker.isActive();
        logger.debug(
                "Got keepAlive for Worker with uuid=" + uuid + " and update its ackVersion to " + version + " isActive"
                        + active);
        QueueDetails queueDetails = queueConfigurationDataService.getQueueConfigurations();
		return new WorkerKeepAliveInfo(worker.getWorkerRecoveryVersion(), active, queueDetails,disableMonitoring);
    }

    @Override
    @Transactional
    public void create(String uuid, String password, String hostName, String installDir) {
        WorkerNode worker = new WorkerNode();
        worker.setUuid(uuid);
        worker.setDescription(uuid);
        worker.setHostName(hostName);
        worker.setActive(false);
        worker.setInstallPath(installDir);
        worker.setStatus(WorkerStatus.FAILED);
        worker.setPassword(password);
        worker.setGroups(Arrays.asList(WorkerNode.DEFAULT_WORKER_GROUPS));
        worker.setWorkerBusynessValue("NA");
        workerNodeRepository.save(worker);
        workerLockService.create(uuid);
    }

    @Override
    @Transactional
    public void updateWorkerToDeleted(String uuid) {
        WorkerNode worker = readByUUID(uuid);
        if (worker != null) {
            worker.setActive(false);
            worker.setDeleted(true);
            worker.setStatus(WorkerStatus.IN_RECOVERY);
        }
    }

    @Override
    @Transactional
    public void updateWorkerToNotDeleted(String uuid) {
        WorkerNode worker = workerNodeRepository.findByUuidAndDeleted(uuid, true);
        if (worker != null) {
            worker.setActive(false);
            worker.setDeleted(false);
            worker.setStatus(WorkerStatus.IN_RECOVERY);
        }
    }

    @Override
    @Transactional
    public List<WorkerNode> readAllNotDeletedWorkers() {
        return workerNodeRepository.findByDeletedOrderByIdAsc(false);
    }

    @Override
    @Transactional
    public String up(String uuid, String version, String versionId) {

        if (loginListeners != null) {
            for (LoginListener listener : loginListeners) {
                listener.preLogin(uuid);
            }
        }
        WorkerKeepAliveInfo workerKeepAliveInfo = newKeepAlive(uuid);
        if (loginListeners != null) {
            for (LoginListener listener : loginListeners) {
                listener.postLogin(uuid);
            }
        }

        updateVersion(uuid, version, versionId);

        return workerKeepAliveInfo.getWorkerRecoveryVersion();
    }

    @Override
    @Transactional
    @Deprecated //is left here for backward compatibility
    public String up(String uuid) {

        if (loginListeners != null) {
            for (LoginListener listener : loginListeners) {
                listener.preLogin(uuid);
            }
        }
        WorkerKeepAliveInfo workerKeepAliveInfo = newKeepAlive(uuid);
        if (loginListeners != null) {
            for (LoginListener listener : loginListeners) {
                listener.postLogin(uuid);
            }
        }
        return workerKeepAliveInfo.getWorkerRecoveryVersion();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkerNode readByUUID(String uuid) {
        WorkerNode worker = workerNodeRepository.findByUuidAndDeleted(uuid, false);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        return worker;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(String uuid) {
        WorkerNode worker = workerNodeRepository.findByUuidAndDeleted(uuid, false);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        return worker.isActive();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkerNode findByUuid(String uuid) {
        WorkerNode worker = workerNodeRepository.findByUuid(uuid);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        return worker;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkerNode> readAllWorkers() {
        return workerNodeRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Set<String>> readWorkerGroupsMap() {
        List<WorkerNode> all = workerNodeRepository.findAll();
        Map<String, Set<String>> workerGroupsMap = newHashMapWithExpectedSize(all.size());
        for (WorkerNode workerNode : all) {
            workerGroupsMap.put(workerNode.getUuid(), new HashSet<>(workerNode.getGroups()));
        }
        return workerGroupsMap;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readAllWorkersUuids() {
        List<WorkerNode> workers = workerNodeRepository.findAll();
        List<String> result = new ArrayList<>();
        for (WorkerNode w : workers) {
            result.add(w.getUuid());
        }
        return result;
    }

    @Override
    @Transactional
    public void updateVersion(String workerUuid, String version, String versionId) {
        WorkerNode worker = workerNodeRepository.findByUuid(workerUuid);
        if (worker == null) {
            throw new IllegalStateException("No worker was found by the specified UUID:" + workerUuid);
        }
        worker.setVersion(version);
        worker.setVersionId(versionId);
    }

    @Override
    @Transactional
    public void updateMigratedPassword(String workerUuid, String encodedPassword) {
        WorkerNode worker = workerNodeRepository.findByUuid(workerUuid);
        if (worker == null) {
            throw new IllegalStateException("No worker was found by the specified UUID:" + workerUuid);
        }
        if (StringUtils.isEmpty(encodedPassword)) {
            throw new IllegalStateException("Invalid encoded password provided for UUID:" + workerUuid);
        }
        if (!StringUtils.equals(worker.getMigratedPassword(), encodedPassword)) {
            worker.setMigratedPassword(encodedPassword);
        }
    }

    @Override
    @Transactional
    public void updateQueueSyncByUuid(String workerUuid, boolean isQueueSync) {
        WorkerNode worker = workerNodeRepository.findByUuid(workerUuid);
        if (worker == null) {
            throw new IllegalStateException("No worker was found by the specified UUID:" + workerUuid);
        }
        worker.setQueueSync(isQueueSync);
    }

    @Override
    @Transactional
    public void updateQueueSync(boolean isQueueSync) {
        List<WorkerNode> workers = workerNodeRepository.findAll();
        for (WorkerNode w : workers) {
            w.setQueueSync(isQueueSync);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readNonRespondingWorkers() {
        long systemVersion = versionService.getCurrentVersion(MSG_RECOVERY_VERSION_NAME);
        long minVersionAllowed = Math.max(systemVersion - MAX_VERSION_GAP_ALLOWED, 0);
        return workerNodeRepository.findNonRespondingWorkers(minVersionAllowed, WorkerStatus.RECOVERED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkerNode> readWorkersByActivation(boolean isActive) {
        return workerNodeRepository.findByActiveAndDeleted(isActive, false);
    }

    @Override
    @Transactional
    public void activate(String uuid) {
        WorkerNode worker = readByUUID(uuid);
        worker.setActive(true);
    }

    @Override
    @Transactional
    public void deactivate(String uuid) {
        WorkerNode worker = readByUUID(uuid);
        worker.setActive(false);
    }

    @Override
    @Transactional
    public void updateEnvironmentParams(String uuid, String os, String jvm, String dotNetVersion) {
        WorkerNode worker = readByUUID(uuid);
        worker.setOs(os);
        worker.setJvm(jvm);
        worker.setDotNetVersion(dotNetVersion);
    }

    @Override
    @Transactional
    public void updateStatus(String uuid, WorkerStatus status) {
        WorkerNode worker = workerNodeRepository.findByUuid(uuid);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        worker.setStatus(status);
    }

    @Override
    @Transactional
    public void updateWorkerBusynessValue(String uuid, String workerBusynessValue) {
        WorkerNode worker = workerNodeRepository.findByUuid(uuid);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        worker.setWorkerBusynessValue(workerBusynessValue);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusInSeparateTransaction(String uuid, WorkerStatus status) {
        WorkerNode worker = workerNodeRepository.findByUuid(uuid);
        if (worker == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        worker.setStatus(status);
    }

    @Override
    @Transactional
    public void migratePassword(String uuid, String password) {
        WorkerNode workerNode = workerNodeRepository.findByUuid(uuid);
        if (workerNode == null) {
            throw new IllegalStateException("no worker was found by the specified UUID:" + uuid);
        }
        if (StringUtils.isNotEmpty(workerNode.getMigratedPassword())) {
            throw new IllegalStateException(
                    "the migration password has already been changed for the specified UUID:" + uuid);
        }
        workerNode.setMigratedPassword(password);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readAllWorkerGroups() {
        return workerNodeRepository.findGroups();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readWorkerGroups(String uuid) {
        WorkerNode node = readByUUID(uuid);
        ArrayList<String> res = new ArrayList<>();
        res.addAll(node.getGroups());
        return res;
    }

    @Override
    @Transactional
    public void updateWorkerGroups(String uuid, String... groupNames) {
        WorkerNode worker = readByUUID(uuid);

        Set<String> groupSet = groupNames != null ? new HashSet<>(Arrays.asList(groupNames)) : new HashSet<String>();
        List<String> groups = new ArrayList<>();
        groupSet.remove(null);
        groups.addAll(groupSet);

        worker.setGroups(groups);
    }

    @Override
    @Transactional(readOnly = true)
    public Multimap<String, String> readGroupWorkersMapActiveAndRunningAndVersion(String versionId) {
        Multimap<String, String> result = ArrayListMultimap.create();
        List<WorkerNode> workers;
        workers = workerNodeRepository
                .findByActiveAndStatusAndDeletedAndVersionId(true, WorkerStatus.RUNNING, false, versionId);
        for (WorkerNode worker : workers) {
            for (String groupName : worker.getGroups()) {
                result.put(groupName, worker.getUuid());
            }
        }
        return result;
    }

    @Override
    @Transactional
    public void addGroupToWorker(String workerUuid, String group) {

        if (group == null) {
            return;
        }

        WorkerNode worker = readByUUID(workerUuid);

        if (!worker.getGroups().contains(group)) {
            List<String> groups = new ArrayList<>(worker.getGroups());
            groups.add(group);
            worker.setGroups(groups);
        }
    }

    @Override
    @Transactional
    public void removeGroupFromWorker(String workerUuid, String group) {
        WorkerNode worker = readByUUID(workerUuid);
        List<String> groups = new ArrayList<>(worker.getGroups());
        groups.remove(group);
        if (groups.size() == 0) {
            throw new IllegalStateException("Can't leave worker without any group !");
        }
        worker.setGroups(groups);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> readWorkerGroups(List<String> groups) {
        return workerNodeRepository.findGroups(groups);
    }

    @Override
    @Transactional
    public void updateBulkNumber(String workerUuid, String bulkNumber) {
        WorkerNode worker = readByUUID(workerUuid);
        worker.setBulkNumber(bulkNumber);
    }

    @Override
    @Transactional
    public void updateWRV(String workerUuid, String wrv) {
        WorkerNode worker = workerNodeRepository.findByUuid(workerUuid);
        worker.setWorkerRecoveryVersion(wrv);
    }

}
