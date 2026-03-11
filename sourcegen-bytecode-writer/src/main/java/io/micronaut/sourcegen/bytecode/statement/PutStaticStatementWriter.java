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
import org.objectweb.asm.commons.GeneratorAdapter;

final class PutStaticStatementWriter implements StatementWriter {
    private final StatementDef.PutStaticField putStaticField;

    public PutStaticStatementWriter(StatementDef.PutStaticField putStaticField) {
        this.putStaticField = putStaticField;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        VariableDef.StaticField field = putStaticField.field();
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, putStaticField.expression(), field.type());
        generatorAdapter.putStatic(
            TypeUtils.getType(field.ownerType(), context.objectDef()),
            field.name(),
            TypeUtils.getType(field.type(), context.objectDef())
        );
    }
}
