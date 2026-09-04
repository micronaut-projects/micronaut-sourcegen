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

import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The statement writer.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public sealed interface StatementWriter permits AssignVariableStatementWriter, DefineAndAssignStatementWriter, ExpressionAsStatementWriter, IfElseStatementWriter, IfStatementWriter, InvokeSuperConstructorStatementWriter, MultiStatementWriter, PutStaticFieldStatementWriter, PutStaticStatementWriter, ReturnStatementWriter, SwitchStatementWriter, SynchronizedStatementWriter, ThrowStatementWriter, TryCatchStatementWriter, WhileLoopStatementWriter {

    /**
     * Create a writer from the statement.
     *
     * @param statementDef Statement
     * @return a writer
     */
    static StatementWriter of(StatementDef statementDef) {
        if (statementDef instanceof StatementDef.InvokeSuperConstructor invokeSuperConstructor) {
            return new InvokeSuperConstructorStatementWriter(invokeSuperConstructor);
        }
        if (statementDef instanceof StatementDef.Multi statements) {
            return new MultiStatementWriter(statements);
        }
        if (statementDef instanceof StatementDef.If ifStatement) {
            return new IfStatementWriter(ifStatement);
        }
        if (statementDef instanceof StatementDef.IfElse ifStatement) {
            return new IfElseStatementWriter(ifStatement);
        }
        if (statementDef instanceof StatementDef.Switch aSwitch) {
            return new SwitchStatementWriter(aSwitch);
        }
        if (statementDef instanceof StatementDef.While aWhile) {
            return new WhileLoopStatementWriter(aWhile);
        }
        if (statementDef instanceof StatementDef.Throw aThrow) {
            return new ThrowStatementWriter(aThrow);
        }
        if (statementDef instanceof StatementDef.Return aReturn) {
            return new ReturnStatementWriter(aReturn);
        }
        if (statementDef instanceof StatementDef.PutStaticField putStaticField) {
            return new PutStaticStatementWriter(putStaticField);
        }
        if (statementDef instanceof StatementDef.PutField putField) {
            return new PutStaticFieldStatementWriter(putField);
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            return new AssignVariableStatementWriter(assign);
        }
        if (statementDef instanceof StatementDef.DefineAndAssign assign) {
            return new DefineAndAssignStatementWriter(assign);
        }
        if (statementDef instanceof StatementDef.Try aTry) {
            return new TryCatchStatementWriter(aTry);
        }
        if (statementDef instanceof StatementDef.Synchronized aSynchronized) {
            return new SynchronizedStatementWriter(aSynchronized);
        }
        if (statementDef instanceof ExpressionDef expressionDef) {
            return new ExpressionAsStatementWriter(expressionDef);
        }
        throw new UnsupportedOperationException("Unrecognized statement: " + statementDef);
    }

    /**
     * Write the statement.
     *
     * @param generatorAdapter The adapter
     * @param context          The method context
     * @param finallyBlock     The runnable that should be invoked before any returning operation - return/throw
     */
    void write(GeneratorAdapter generatorAdapter,
               MethodContext context,
               @Nullable Runnable finallyBlock);

    /**
     * Write the statement with scoped locals.
     *
     * @param generatorAdapter The adapter
     * @param context          The method context
     * @param finallyBlock     The runnable that should be invoked before any returning operation - return/throw
     */
    default void writeScoped(GeneratorAdapter generatorAdapter,
                             MethodContext context,
                             @Nullable Runnable finallyBlock) {
        Map<String, MethodContext.LocalData> oldLocals = context.locals();
        Map<String, MethodContext.LocalData> newLocals = new LinkedHashMap<>(oldLocals);
        MethodContext newContext = new MethodContext(context.objectDef(), context.methodDef(), newLocals, new ArrayList<>(), false, context.yieldTargets());
        write(generatorAdapter, newContext, finallyBlock);
        oldLocals.keySet().forEach(newLocals::remove); // Remove locals not created in the scope
        Label endMethod = new Label();
        if (!newLocals.isEmpty()) {
            generatorAdapter.visitLabel(endMethod);
        }
        for (MethodContext.LocalData localsDatum : newLocals.values()) {
            generatorAdapter.getDelegate().visitLocalVariable(
                localsDatum.name(),
                localsDatum.type().getDescriptor(),
                null,
                localsDatum.start(),
                endMethod,
                localsDatum.index()
            );
        }
    }

}
