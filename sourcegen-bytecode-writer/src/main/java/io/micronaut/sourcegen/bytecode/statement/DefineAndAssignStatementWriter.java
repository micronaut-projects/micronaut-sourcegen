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
package io.micronaut.sourcegen.bytecode.statement;

import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class DefineAndAssignStatementWriter implements StatementWriter {
    private final StatementDef.DefineAndAssign assign;

    public DefineAndAssignStatementWriter(StatementDef.DefineAndAssign assign) {
        this.assign = assign;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        VariableDef.Local local = assign.variable();
        Type localType = TypeUtils.getType(local.type(), context.objectDef());
        Label startVariable = new Label();
        generatorAdapter.visitLabel(startVariable);
        int localIndex = generatorAdapter.newLocal(localType);
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, assign.expression(), local.type());
        generatorAdapter.storeLocal(localIndex, localType);
        MethodContext.LocalData prevLocal = context.locals().put(local.name(), new MethodContext.LocalData(local.name(), localType, startVariable, localIndex));
        if (prevLocal != null) {
            throw new IllegalStateException("Duplicate local name: " + local.name());
        }
    }
}
