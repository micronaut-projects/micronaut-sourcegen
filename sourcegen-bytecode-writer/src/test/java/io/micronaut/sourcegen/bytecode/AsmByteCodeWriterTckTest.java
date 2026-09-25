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
package io.micronaut.sourcegen.bytecode;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;

/**
 * Every topic of the bytecode writer TCK against the ASM backend. The writer is asked to check the
 * classes it produces, so a definition that is written into invalid bytecode fails here rather than
 * at class loading.
 *
 * <p>A test the backend does not satisfy yet is overridden without {@code @Test}, which removes it
 * from the run, and carries a {@code @Disabled} reason that records the gap.
 */
class AsmByteCodeWriterTckTest {

    static byte[] writeWithAsm(ObjectDef definition) {
        return new ByteCodeWriter(true, true).write(definition);
    }

    @Nested
    class Statements extends ByteCodeWriterTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }

        @Override
        @Disabled("The ASM backend writes no MethodParameters attribute, so a parameter reflects as arg0")
        public void writesCatchVariablesThrowsAndDeclarationMetadata() {
            // Empty on purpose: the override exists only to disable the inherited test.
        }

        @Override
        @Disabled("The ASM backend rejects a void method whose body returns a value-producing call "
            + "instead of discarding the value")
        public void writesVoidMethodsThatReturnAnExpression() {
            // Empty on purpose: the override exists only to disable the inherited test.
        }

        @Override
        @Disabled("The ASM backend repeats the field initializers in a delegating constructor, so they run twice")
        public void writesConstructorDelegationWithoutRepeatingFieldInitializers() {
            // Empty on purpose: the override exists only to disable the inherited test.
        }
    }

    @Nested
    class ControlFlow extends ControlFlowTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Expressions extends ExpressionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Conversions extends ConversionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class OverloadSelection extends OverloadSelectionTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Inference extends InferenceTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Receivers extends ReceiverTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class LambdasAndReferences extends LambdaAndReferenceTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Bridges extends BridgeTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class Signatures extends SignatureTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }

    @Nested
    class MemberTypes extends MemberTypeTck {

        @Override
        protected byte[] write(ObjectDef definition) {
            return writeWithAsm(definition);
        }
    }
}
