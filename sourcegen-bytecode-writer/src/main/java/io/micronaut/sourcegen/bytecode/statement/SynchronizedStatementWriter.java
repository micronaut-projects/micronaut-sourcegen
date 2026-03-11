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
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class SynchronizedStatementWriter implements StatementWriter {
    private final StatementDef.Synchronized aSynchronized;

    public SynchronizedStatementWriter(StatementDef.Synchronized aSynchronized) {
        this.aSynchronized = aSynchronized;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        Label end = new Label();
        Label synchronizedStart = new Label();
        Label synchronizedEnd = new Label();
        Label synchronizedException = new Label();
        Label synchronizedExceptionEnd = new Label();
        generatorAdapter.visitTryCatchBlock(synchronizedStart, synchronizedEnd, synchronizedException, null);
        generatorAdapter.visitTryCatchBlock(synchronizedException, synchronizedExceptionEnd, synchronizedException, null);

        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, aSynchronized.monitor(), aSynchronized.monitor().type());
        generatorAdapter.dup();
        Type monitorType = TypeUtils.getType(aSynchronized.monitor().type(), context.objectDef());
        int monitorLocal = generatorAdapter.newLocal(monitorType);
        generatorAdapter.storeLocal(monitorLocal);
        generatorAdapter.monitorEnter();

        generatorAdapter.visitLabel(synchronizedStart);

        StatementWriter.of(aSynchronized.statement()).writeScoped(generatorAdapter, context, () -> {
            generatorAdapter.loadLocal(monitorLocal);
            generatorAdapter.monitorExit();
            if (finallyBlock != null) {
                finallyBlock.run();
            }
        });

        generatorAdapter.loadLocal(monitorLocal);
        generatorAdapter.monitorExit();
        generatorAdapter.visitLabel(synchronizedEnd);
        generatorAdapter.goTo(end);

        generatorAdapter.visitLabel(synchronizedException);
        // Insert the monitor exit before the exception throw
        Type throwableType = Type.getType(Throwable.class);
        int exceptionLocal = generatorAdapter.newLocal(throwableType);
        generatorAdapter.storeLocal(exceptionLocal);

        generatorAdapter.loadLocal(monitorLocal);
        generatorAdapter.monitorExit();

        generatorAdapter.visitLabel(synchronizedExceptionEnd);

        generatorAdapter.loadLocal(exceptionLocal, throwableType);
        generatorAdapter.throwException();

        generatorAdapter.visitLabel(end);
    }

}
