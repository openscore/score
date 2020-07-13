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

package io.cloudslang.score.api;

import io.cloudslang.score.lang.SystemContext;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * A POJO containing all the data necessary to create a new branch
 */
public class StartBranchDataContainer implements Serializable{
    private final Long startPosition;
    private final Long executionPlanId;
    private final Map<String, Serializable> contexts;
    private final SystemContext systemContext;

    public StartBranchDataContainer(Long startPosition, Long executionPlanId, Map<String, Serializable> contexts, SystemContext systemContext) {
        Validate.notNull(startPosition);
        Validate.notNull(executionPlanId);
        Validate.notNull(contexts);
        Validate.notNull(systemContext);

        this.startPosition = startPosition;
        this.executionPlanId = executionPlanId;
        this.systemContext = new SystemContext(systemContext);
        this.contexts = new UnifiedMap<>();

        for (String name : contexts.keySet()) {
            this.contexts.put(name, contexts.get(name));
        }
    }

    public Long getStartPosition() {
        return startPosition;
    }

    public Long getExecutionPlanId() {
        return executionPlanId;
    }

    public Map<String, Serializable> getContexts() {
        return Collections.unmodifiableMap(contexts);
    }

    public SystemContext getSystemContext() {
        return systemContext;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StartBranchDataContainer)) return false;

        StartBranchDataContainer that = (StartBranchDataContainer) o;

        return new EqualsBuilder()
                .append(this.startPosition, that.startPosition)
                .append(this.executionPlanId, that.executionPlanId)
                .append(this.contexts, that.contexts)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(this.startPosition)
                .append(this.executionPlanId)
                .toHashCode();
    }
}
