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
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.commons.GeneratorAdapter;

final class PutStaticFieldStatementWriter implements StatementWriter {
    private final StatementDef.PutField putField;

    public PutStaticFieldStatementWriter(StatementDef.PutField putField) {
        this.putField = putField;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        VariableDef.Field field = putField.field();
        ExpressionWriter.writeExpression(generatorAdapter, context, field.instance());
        TypeDef fieldType = field.type();
        TypeDef declaringType = field.declaringType();
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, putField.expression(), fieldType);
        generatorAdapter.putField(
            TypeUtils.getType(declaringType, context.objectDef()),
            field.name(),
            TypeUtils.getType(fieldType, context.objectDef())
        );
    }
}
