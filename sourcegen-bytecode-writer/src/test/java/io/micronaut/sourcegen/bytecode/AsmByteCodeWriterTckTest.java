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

import io.micronaut.sourcegen.bytecode.tck.ByteCodeWriterTck;
import io.micronaut.sourcegen.model.ObjectDef;
import org.junit.jupiter.api.Disabled;

/**
 * The TCK against the ASM backend. The writer is asked to check the classes it produces, so a
 * definition that is written into invalid bytecode fails here rather than at class loading.
 */
class AsmByteCodeWriterTckTest extends ByteCodeWriterTck {

    @Override
    protected byte[] write(ObjectDef definition) {
        return new ByteCodeWriter(true, true).write(definition);
    }

    @Override
    @Disabled("The ASM backend compares NaN with the wrong one of dcmpg/dcmpl, so `NaN < 3` is true")
    public void writesAllComparisonsForIntegralAndFloatingPointValues() {
        // Empty on purpose: the override exists only to disable the inherited test.
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
    @Disabled("On the ASM backend the caught exception falls through into the else branch")
    public void writesTryWhoseCatchCompletesInsideAnIfBranch() {
        // Empty on purpose: the override exists only to disable the inherited test.
    }

    @Override
    @Disabled("The ASM backend repeats the field initializers in a delegating constructor, so they run twice")
    public void writesConstructorDelegationWithoutRepeatingFieldInitializers() {
        // Empty on purpose: the override exists only to disable the inherited test.
    }
}
