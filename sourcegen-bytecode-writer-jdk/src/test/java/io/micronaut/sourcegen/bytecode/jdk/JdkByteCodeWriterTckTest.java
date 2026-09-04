/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.bytecode.tck.ByteCodeWriterTck;
import io.micronaut.sourcegen.model.ObjectDef;

import java.lang.classfile.ClassFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TCK against the direct ClassFile lowering. Writing through {@link JdkClassFileWriter} rather
 * than the public writer means a test fails instead of passing quietly on the javac fallback.
 */
class JdkByteCodeWriterTckTest extends ByteCodeWriterTck {

    @Override
    protected byte[] write(ObjectDef definition) {
        var result = new JdkClassFileWriter(true).write(definition, null);
        assertTrue(result.isPresent(), () -> "Expected direct ClassFile lowering for " + definition.getName());
        byte[] bytes = result.orElseThrow();
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
        return bytes;
    }
}
