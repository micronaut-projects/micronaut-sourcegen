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
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.bytecode.statement.StatementWriter;
import io.micronaut.sourcegen.model.ExpressionDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class SwitchYieldCaseExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.SwitchYieldCase switchYieldCase;

    public SwitchYieldCaseExpressionWriter(ExpressionDef.SwitchYieldCase switchYieldCase) {
        this.switchYieldCase = switchYieldCase;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        // The case is a statement block that yields its value with a return statement, so the returns
        // it contains hold the value in a local and jump here instead of returning from the method
        Type type = TypeUtils.getType(switchYieldCase.type(), context.objectDef());
        Label end = new Label();
        int slot = generatorAdapter.newLocal(type);
        context.yieldTargets().push(new MethodContext.YieldTarget(switchYieldCase.type(), slot, end));
        try {
            StatementWriter.of(switchYieldCase.statement()).write(generatorAdapter, context, null);
        } finally {
            context.yieldTargets().pop();
        }
        generatorAdapter.visitLabel(end);
        generatorAdapter.loadLocal(slot, type);
    }
}
