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

import io.micronaut.sourcegen.bytecode.tck.BridgeTck;
import io.micronaut.sourcegen.bytecode.tck.ByteCodeWriterTck;
import io.micronaut.sourcegen.bytecode.tck.ControlFlowTck;
import io.micronaut.sourcegen.bytecode.tck.ConversionTck;
import io.micronaut.sourcegen.bytecode.tck.ExpressionTck;
import io.micronaut.sourcegen.bytecode.tck.InferenceTck;
import io.micronaut.sourcegen.bytecode.tck.LambdaAndReferenceTck;
import io.micronaut.sourcegen.bytecode.tck.MemberTypeTck;
import io.micronaut.sourcegen.bytecode.tck.OverloadSelectionTck;
import io.micronaut.sourcegen.bytecode.tck.ReceiverTck;
import io.micronaut.sourcegen.bytecode.tck.SignatureTck;
import io.micronaut.sourcegen.model.ObjectDef;
import org.junit.jupiter.api.Nested;

import java.lang.classfile.ClassFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every topic of the bytecode writer TCK against the direct ClassFile lowering. Writing through
 * {@link JdkClassFileWriter} rather than the public writer means a test fails instead of passing
 * quietly on the javac fallback, and every class it writes must verify and target Java 17.
 */
class JdkByteCodeWriterTckTest {

    static byte[] writeDirectly(ObjectDef definition) {
        var result = new JdkClassFileWriter(true).write(definition, null);
        assertTrue(result.isPresent(), () -> "Expected direct ClassFile lowering for " + definition.getName());
        byte[] bytes = result.orElseThrow();
        assertTrue(ClassFile.of().verify(bytes).isEmpty(), () -> "Expected verifiable bytecode for " + definition.getName());
        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
        return bytes;
    }

    @Nested
    class Statements extends ByteCodeWriterTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class ControlFlow extends ControlFlowTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Expressions extends ExpressionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Conversions extends ConversionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class OverloadSelection extends OverloadSelectionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Inference extends InferenceTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Receivers extends ReceiverTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class LambdasAndReferences extends LambdaAndReferenceTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Bridges extends BridgeTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class Signatures extends SignatureTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }

    @Nested
    class MemberTypes extends MemberTypeTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeDirectly(definition);
        }
    }
}
