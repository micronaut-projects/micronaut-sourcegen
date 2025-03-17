/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode.expression;

import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * The variation of {@link ExpressionWriter} that is aware that the expression is written as a statement.
 * Unused stack values should be popped in that case.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public abstract sealed class AbstractStatementAwareExpressionWriter implements ExpressionWriter permits InvokeInstanceMethodExpressionWriter, InvokeStaticMethodExpressionWriter, NewInstanceExpressionWriter, InlineLambdaExpressionWriter {

    protected boolean statement;

    /**
     * Marks the expression as being written as a statement.
     */
    public final void markAsStatement() {
        this.statement = true;
    }

    protected final void popValueIfNeeded(GeneratorAdapter generatorAdapter, TypeDef typeDef) {
        if (!statement || typeDef.equals(TypeDef.VOID)) {
            return;
        }
        if (typeDef.equals(TypeDef.Primitive.LONG) || typeDef.equals(TypeDef.Primitive.DOUBLE)) {
            generatorAdapter.pop2();
        } else {
            generatorAdapter.pop();
        }
    }
}
