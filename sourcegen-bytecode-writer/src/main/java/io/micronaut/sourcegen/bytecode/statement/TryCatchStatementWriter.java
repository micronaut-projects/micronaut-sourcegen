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
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The try-catch statement.
 * @since 1.5
 */
public final class TryCatchStatementWriter implements StatementWriter {
    public static final String EXCEPTION_NAME = "$exception";
    private final StatementDef.Try aTry;

    public TryCatchStatementWriter(StatementDef.Try aTry) {
        this.aTry = aTry;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        Label end = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();

        List<CatchBlock> exceptionHandlers = new ArrayList<>();

        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            Label exceptionHandler = new Label();

            exceptionHandlers.add(new CatchBlock(aCatch, exceptionHandler));

            generatorAdapter.visitTryCatchBlock(
                tryStart,
                tryEnd,
                exceptionHandler,
                TypeUtils.getType(aCatch.exception(), context.objectDef()).getInternalName()
            );
        }

        Label finallyExceptionHandler = null;
        StatementDef finallyStatement = aTry.finallyStatement();

        if (finallyStatement != null) {
            finallyExceptionHandler = new Label();
            generatorAdapter.visitTryCatchBlock(
                tryStart,
                tryEnd,
                finallyExceptionHandler,
                null
            );
            for (CatchBlock catchBlock : exceptionHandlers) {
                catchBlock.to = new Label();
                generatorAdapter.visitTryCatchBlock(
                    catchBlock.from,
                    catchBlock.to,
                    finallyExceptionHandler,
                    null
                );
            }
        }

        generatorAdapter.visitLabel(tryStart);

        Runnable thisFinallyBlock = finallyStatement == null ? null : () -> StatementWriter.of(finallyStatement).writeScoped(generatorAdapter, context, finallyBlock);
        StatementWriter.of(aTry.statement()).writeScoped(generatorAdapter, context, thisFinallyBlock);

        generatorAdapter.visitLabel(tryEnd);

        if (finallyStatement != null && canCompleteNormally(aTry.statement())) {
            // The body fell through, the finally is outside of the protected range
            StatementWriter.of(finallyStatement).writeScoped(generatorAdapter, context, finallyBlock);
        }

        generatorAdapter.goTo(end);

        for (CatchBlock catchBlock : exceptionHandlers) {
            StatementDef.Try.Catch aCatch = catchBlock.aCatch;
            generatorAdapter.visitLabel(catchBlock.from);

            Type exceptionType = TypeUtils.getType(aCatch.exception(), context.objectDef());
            int local = generatorAdapter.newLocal(exceptionType);
            generatorAdapter.storeLocal(local);
            String varName = EXCEPTION_NAME;
            context.locals().put(varName, new MethodContext.LocalData(varName, exceptionType, catchBlock.from, local));

            StatementWriter.of(aCatch.statement()).writeScoped(generatorAdapter, context, thisFinallyBlock);

            context.locals().remove(varName);

            if (catchBlock.to != null) {
                generatorAdapter.visitLabel(catchBlock.to);
            }

            if (finallyStatement != null) {
                StatementWriter.of(finallyStatement).writeScoped(generatorAdapter, context, thisFinallyBlock);
            }

            generatorAdapter.goTo(end);
        }

        if (finallyExceptionHandler != null) {
            generatorAdapter.visitLabel(finallyExceptionHandler);

            Type exceptionType = TypeUtils.getType(TypeDef.of(Throwable.class), context.objectDef());
            int local = generatorAdapter.newLocal(exceptionType);
            generatorAdapter.storeLocal(local);

            StatementWriter.of(Objects.requireNonNull(finallyStatement)).writeScoped(generatorAdapter, context, finallyBlock);

            generatorAdapter.loadLocal(local);
            generatorAdapter.throwException();

            generatorAdapter.goTo(end);
        }

        generatorAdapter.visitLabel(end);
    }

    /**
     * Checks if the statement can complete normally, a statement that cannot would make the following
     * bytecode unreachable.
     *
     * @param statement The statement
     * @return true if the execution can continue after the statement
     */
    private static boolean canCompleteNormally(StatementDef statement) {
        List<StatementDef> statements = statement.flatten();
        if (statements.isEmpty()) {
            return true;
        }
        StatementDef last = statements.get(statements.size() - 1);
        if (last instanceof StatementDef.IfElse ifElse) {
            return canCompleteNormally(ifElse.statement()) || canCompleteNormally(ifElse.elseStatement());
        }
        if (last instanceof StatementDef.Synchronized aSynchronized) {
            return canCompleteNormally(aSynchronized.statement());
        }
        return !(last instanceof StatementDef.Return || last instanceof StatementDef.Throw);
    }

    private static final class CatchBlock {

        private final StatementDef.Try.Catch aCatch;
        private final Label from;
        private @Nullable Label to;

        private CatchBlock(StatementDef.Try.Catch aCatch, Label from) {
            this.aCatch = aCatch;
            this.from = from;
            this.to = null;
        }
    }
}
