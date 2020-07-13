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

import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.io.Serializable;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.Collections;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * A POJO which serves as an holder for the contexts and exception (if exists) of a finished branch
 */
public class EndBranchDataContainer implements Serializable {
    private final Map<String, Serializable> contexts;
    private final Map<String, Serializable> systemContext;
    private final String exception;

    public EndBranchDataContainer(Map<String, Serializable> contexts, Map<String, Serializable> systemContext, String exception) {
        Validate.notNull(contexts);
        Validate.notNull(systemContext);

        this.contexts = new UnifiedMap<>(contexts);
        this.systemContext = new UnifiedMap<>(systemContext);
        this.exception = exception;
    }

    public Map<String, Serializable> getContexts() {
        return Collections.unmodifiableMap(contexts);
    }

    public Map<String, Serializable> getSystemContext() {
        return Collections.unmodifiableMap(systemContext);
    }

    public String getException() {
        return exception;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof EndBranchDataContainer))
            return false;

        EndBranchDataContainer that = (EndBranchDataContainer) o;

        return new EqualsBuilder()
                .append(this.contexts, that.contexts)
                .append(this.systemContext, that.systemContext)
                .append(this.exception, that.exception)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(this.contexts)
                .append(this.systemContext)
                .append(this.exception)
                .toHashCode();
    }
}
