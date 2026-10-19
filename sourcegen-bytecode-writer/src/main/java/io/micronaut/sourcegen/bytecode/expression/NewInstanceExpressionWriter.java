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

import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.core.InvocationPlan;
import io.micronaut.sourcegen.model.ExpressionDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class NewInstanceExpressionWriter extends AbstractStatementAwareExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.NewInstance newInstance;

    public NewInstanceExpressionWriter(ExpressionDef.NewInstance newInstance) {
        this.newInstance = newInstance;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        InvocationPlan plan = InvocationPlan.ofNewInstance(newInstance, context.objectDef(), context.methodDef(), context.enclosingScope());
        generatorAdapter.newInstance(Type.getType(plan.ownerDescriptor()));
        generatorAdapter.dup();
        ExpressionWriter.writeInvocation(generatorAdapter, context, plan);
        popValueIfNeeded(generatorAdapter, newInstance.type());
    }
}
