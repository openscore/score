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

package io.cloudslang.runtime.api.python;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * Created by Genadi Rabinovich, genadi@hpe.com on 05/05/2016.
 */
public interface PythonRuntimeService {
    /**
     * exec used for python script executions
     *
     * @param dependencies - list of resources with maven GAV notation ‘groupId:artifactId:version’ which can be used to resolve resources with Maven Repository Support
     */
    PythonExecutionResult exec(Set<String> dependencies, String script, Map<String, Serializable> vars);

    PythonEvaluationResult eval(String prepareEnvironmentScript, String script, Map<String, Serializable> vars);

    PythonEvaluationResult test(String prepareEnvironmentScript, String script, Map<String, Serializable> vars, long timeout);
}
