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
            exceptionHandlers.add(new CatchBlock(aCatch, new Label()));
        }

        Label finallyExceptionHandler = null;
        StatementDef finallyStatement = aTry.finallyStatement();

        if (finallyStatement != null) {
            finallyExceptionHandler = new Label();
            for (CatchBlock catchBlock : exceptionHandlers) {
                catchBlock.to = new Label();
            }
        }

        generatorAdapter.visitLabel(tryStart);

        List<MethodContext.Gap> tryGaps = new ArrayList<>();
        StatementWriter.of(aTry.statement()).writeScoped(generatorAdapter, context, exit(generatorAdapter, context, finallyBlock, tryGaps));

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

            StatementWriter.of(aCatch.statement()).writeScoped(generatorAdapter, context, exit(generatorAdapter, context, finallyBlock, catchBlock.gaps));

            context.locals().remove(varName);

            if (catchBlock.to != null) {
                generatorAdapter.visitLabel(catchBlock.to);
            }

            if (finallyStatement != null) {
                // Outside of the try, so a return in it runs only the finally blocks around the try
                StatementWriter.of(finallyStatement).writeScoped(generatorAdapter, context, finallyBlock);
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

        visitTryCatchBlocks(generatorAdapter, context, tryStart, tryEnd, exceptionHandlers, finallyExceptionHandler);
    }

    /**
     * Visits the exception handlers of the try once it is written. The JVM takes the first entry of the
     * exception table that matches, so the handlers of the try follow those of the statements nested in
     * it, which were visited as they were written.
     *
     * @param generatorAdapter        The generator adapter
     * @param context                 The method context
     * @param tryStart                The start of the try block
     * @param tryEnd                  The end of the try block
     * @param exceptionHandlers       The catch blocks
     * @param finallyExceptionHandler The handler running the finally block, if there is one
     */
    private static void visitTryCatchBlocks(GeneratorAdapter generatorAdapter,
                                            MethodContext context,
                                            Label tryStart,
                                            Label tryEnd,
                                            List<CatchBlock> exceptionHandlers,
                                            @Nullable Label finallyExceptionHandler) {
        for (CatchBlock catchBlock : exceptionHandlers) {
            visitTryCatchBlocks(
                generatorAdapter,
                tryStart,
                tryEnd,
                tryGaps,
                catchBlock.from,
                TypeUtils.getType(catchBlock.aCatch.exception(), context.objectDef()).getInternalName()
            );
        }
        if (finallyExceptionHandler != null) {
            visitTryCatchBlocks(generatorAdapter, tryStart, tryEnd, tryGaps, finallyExceptionHandler, null);
            for (CatchBlock catchBlock : exceptionHandlers) {
                if (catchBlock.to != null) {
                    visitTryCatchBlocks(generatorAdapter, catchBlock.from, catchBlock.to, catchBlock.gaps, finallyExceptionHandler, null);
                }
            }
        }
    }

    /**
     * The way out of the try body or of a catch body for a return or a yield: the finally block, then the
     * finally blocks and the monitor releases of the statements around the try.
     *
     * @param generatorAdapter The adapter
     * @param context          The method context
     * @param finallyBlock     The way out of the statements around the try
     * @param gaps             The gaps of the body, which the copies of the finally block are written in
     * @return The way out of the body
     */
    private @Nullable Runnable exit(GeneratorAdapter generatorAdapter,
                                    MethodContext context,
                                    @Nullable Runnable finallyBlock,
                                    List<MethodContext.Gap> gaps) {
        StatementDef finallyStatement = aTry.finallyStatement();
        if (finallyStatement == null) {
            return finallyBlock;
        }
        return () -> {
            gaps.add(context.openGap(generatorAdapter));
            StatementWriter.of(finallyStatement).writeScoped(generatorAdapter, context, finallyBlock);
            if (finallyBlock != null && canCompleteNormally(finallyStatement)) {
                finallyBlock.run();
            }
        };
    }

    /**
     * Visits the try/catch blocks of a range of code, leaving out the gaps in it. A block that protects no
     * instruction is removed once the method is written.
     *
     * @param generatorAdapter The adapter
     * @param start            The start of the range
     * @param end              The end of the range
     * @param gaps             The gaps in the range, in the order of the code
     * @param handler          The handler
     * @param type             The internal name of the exception type handled, or null for any
     */
    static void visitTryCatchBlocks(GeneratorAdapter generatorAdapter,
                                    Label start,
                                    Label end,
                                    List<MethodContext.Gap> gaps,
                                    Label handler,
                                    @Nullable String type) {
        Label from = start;
        for (MethodContext.Gap gap : gaps) {
            generatorAdapter.visitTryCatchBlock(from, gap.start(), handler, type);
            from = gap.end();
        }
        generatorAdapter.visitTryCatchBlock(from, end, handler, type);
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
        private final List<MethodContext.Gap> gaps = new ArrayList<>();
        private @Nullable Label to;

        private CatchBlock(StatementDef.Try.Catch aCatch, Label from) {
            this.aCatch = aCatch;
            this.from = from;
            this.to = null;
        }
    }
}
