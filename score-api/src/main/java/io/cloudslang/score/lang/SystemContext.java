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

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

/**
 * User:
 * Date: 10/06/2014
 */
public class SystemContext extends ExecutionRuntimeServices implements Map<String, Serializable> {

    private static final long serialVersionUID = -2882205533540314198L;

    public SystemContext() {
    }

    public SystemContext(Map<? extends String, ? extends Serializable> map) {
        this.contextMap = new UnifiedMap<>(map);
    }

    @Override
    public int size() {
        return contextMap.size();
    }

    @Override
    public boolean isEmpty() {
        return contextMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return contextMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return contextMap.containsValue(value);
    }

    @Override
    public Serializable get(Object key) {
        return contextMap.get(key);
    }

    @Override
    public Serializable put(String key, Serializable value) {
        return contextMap.put(key, value);
    }

    @Override
    public Serializable remove(Object key) {
        return contextMap.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Serializable> m) {
        contextMap.putAll(m);
    }

    @Override
    public void clear() {
        contextMap.clear();
    }

    @Override
    public Set<String> keySet() {
        return contextMap.keySet();
    }

    @Override
    public Collection<Serializable> values() {
        return contextMap.values();
    }

    @Override
    public Set<Entry<String, Serializable>> entrySet() {
        return contextMap.entrySet();
    }

    public void resume() {
        contextMap.remove(EXECUTION_PAUSED);
    }

    public void addBranch(Long startPosition, Long executionPlanId, Map<String, Serializable> context, SystemContext systemContext) {
        super.addBranch(startPosition, executionPlanId, context, systemContext);
    }
}
