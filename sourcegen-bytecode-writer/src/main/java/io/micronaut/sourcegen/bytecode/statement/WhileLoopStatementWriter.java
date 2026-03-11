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
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class WhileLoopStatementWriter implements StatementWriter {
    private final StatementDef.While aWhile;

    public WhileLoopStatementWriter(StatementDef.While aWhile) {
        this.aWhile = aWhile;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        Label whileLoop = new Label();
        Label end = new Label();
        generatorAdapter.visitLabel(whileLoop);
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, aWhile.expression(), TypeDef.Primitive.BOOLEAN);
        generatorAdapter.push(true);
        generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, end);
        StatementWriter.of(aWhile.statement()).writeScoped(generatorAdapter, context, finallyBlock);
        generatorAdapter.goTo(whileLoop);
        generatorAdapter.visitLabel(end);
    }
}
