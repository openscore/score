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

package io.cloudslang.engine.queue.services.cleaner;

import java.util.Set;
/**
 * Created by IntelliJ IDEA.
 * User:
 * Date: 14/10/13
 *
 * A service that is responsible for cleaning the queue tables
 */
public interface QueueCleanerService {

    /**
     *
     * get a set of ids of finished executions
     *
     * @return Set of ids of finished executions
     */
    Set<Long> getFinishedExecStateIds();

    /**
     *
     * clean queues data for the given ids
     *
     * @param ids the ids to clean data for
     */
    void cleanFinishedSteps(Set<Long> ids);

    /**
     * get a set of ids of finished executions
     * but are still present in queues and states
     *
     * @return Set of ids of finished executions
     */
    Set<Long> getFlowCompletedExecStateIds();
    /**
     * delete orphan ids still present in queues
     * but not in states
     *
     * @return Set of ids of finished executions
     */
    int deleteOrphanSteps();
}
