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
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class ReturnStatementWriter implements StatementWriter {
    private final StatementDef.Return aReturn;

    public ReturnStatementWriter(StatementDef.Return aReturn) {
        this.aReturn = aReturn;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        MethodContext.YieldTarget yieldTarget = context.currentYieldTarget();
        if (yieldTarget != null) {
            writeYield(generatorAdapter, context, finallyBlock, yieldTarget);
            return;
        }
        aReturn.validate(context.methodDef());
        if (aReturn.expression() != null) {
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, aReturn.expression(), context.methodDef().getReturnType());
            pushFinallyStatement(generatorAdapter, context, finallyBlock, context.methodDef().getReturnType());
        } else {
            if (finallyBlock != null) {
                finallyBlock.run();
            }
        }
        generatorAdapter.returnValue();
    }

    /**
     * Writes a return that is the value of the switch yield case being written: the value is held in
     * the local of the case and control jumps to its end, where the case loads it.
     */
    private void writeYield(GeneratorAdapter generatorAdapter,
                            MethodContext context,
                            @Nullable Runnable finallyBlock,
                            MethodContext.YieldTarget yieldTarget) {
        ExpressionDef expression = aReturn.expression();
        if (expression == null) {
            throw new IllegalStateException("Switch yield return has no value");
        }
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expression, yieldTarget.type());
        generatorAdapter.storeLocal(yieldTarget.slot(), TypeUtils.getType(yieldTarget.type(), context.objectDef()));
        if (finallyBlock != null) {
            finallyBlock.run();
        }
        generatorAdapter.goTo(yieldTarget.end());
    }

    private void pushFinallyStatement(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock, TypeDef expTypeDef) {
        if (finallyBlock != null) {
            if (expTypeDef.equals(TypeDef.VOID)) {
                finallyBlock.run();
            } else {
                Type expType = TypeUtils.getType(expTypeDef, context.objectDef());
                int returnLocal = generatorAdapter.newLocal(expType);
                generatorAdapter.storeLocal(returnLocal);
                finallyBlock.run();
                generatorAdapter.loadLocal(returnLocal);
            }
        }
    }
}
