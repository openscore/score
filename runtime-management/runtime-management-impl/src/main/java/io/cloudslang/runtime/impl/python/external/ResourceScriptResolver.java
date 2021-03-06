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
package io.cloudslang.runtime.impl.python.external;

import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.io.IOUtils.readLines;
import static org.apache.commons.io.IOUtils.toByteArray;
import static org.apache.commons.lang3.StringUtils.join;


public class ResourceScriptResolver {

    private static final byte[] execScriptAsBytes;
    private static final String evalScriptAsString;

    static {
        execScriptAsBytes = loadScriptFromResource("main.py");
        evalScriptAsString = loadScriptFromResourceAsString("eval.py");
    }

    private static byte[] loadScriptFromResource(String resourceName) {
        try (InputStream stream = requireNonNull(ResourceScriptResolver.class.getClassLoader(),
                "Could not get not-null classloader")
                .getResourceAsStream(resourceName)) {
            return toByteArray(requireNonNull(stream, "Could not locate resource '" + resourceName + "'"));
        } catch (IOException ioEx) {
            throw new RuntimeException("Could not load resource '" + resourceName + "': ", ioEx);
        }
    }

    private static String loadScriptFromResourceAsString(String resourceName) {
        try (InputStream stream = requireNonNull(ResourceScriptResolver.class.getClassLoader(),
                "Could not get not-null classloader")
                .getResourceAsStream(resourceName)) {
            InputStream safeStream = requireNonNull(stream, "Could not locate resource '" + resourceName + "'");
            // Must make sure that the eval.py does not have " character in its source.
            return join(readLines(safeStream, UTF_8), "\n");
        } catch (IOException ioEx) {
            throw new RuntimeException("Could not load resource '" + resourceName + "': ", ioEx);
        }
    }

    public static byte[] loadExecScriptAsBytes() {
        return execScriptAsBytes;
    }

    public static String loadEvalScriptAsString() {
        return evalScriptAsString;
    }

}
