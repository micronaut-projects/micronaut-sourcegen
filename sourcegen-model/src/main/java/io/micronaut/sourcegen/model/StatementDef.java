/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The statement definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface StatementDef permits ExpressionDef.CallInstanceMethod, ExpressionDef.CallStaticMethod, StatementDef.Assign, StatementDef.DefineAndAssign, StatementDef.If, StatementDef.IfElse, StatementDef.Multi, StatementDef.Return, StatementDef.Switch, StatementDef.Throw, StatementDef.While {

    /**
     * The statement supplier for a better code flow.
     *
     * @param statementSupplier The statement supplier
     * @return statement
     * @since 1.2
     */
    static StatementDef of(@NonNull Supplier<StatementDef> statementSupplier) {
        return statementSupplier.get();
    }

    /**
     * The multi line statement.
     *
     * @param statements statements
     * @return statement
     * @since 1.2
     */
    static StatementDef multi(@NonNull List<StatementDef> statements) {
        return new Multi(statements);
    }

    /**
     * The multi line statement.
     *
     * @param statements statements
     * @return statement
     * @since 1.2
     */
    static StatementDef multi(@NonNull StatementDef... statements) {
        return multi(List.of(statements));
    }

    /**
     * The multi statement.
     *
     * @param statements The statements
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record Multi(@NonNull List<StatementDef> statements) implements StatementDef {
    }

    /**
     * The throw statement.
     *
     * @param variableDef The exception
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record Throw(ExpressionDef variableDef) implements StatementDef {
    }

    /**
     * The return statement.
     *
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Return(ExpressionDef expression) implements StatementDef {
    }

    /**
     * The assign statement.
     *
     * @param variable   The variable to assign
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Assign(VariableDef variable,
                  ExpressionDef expression) implements StatementDef {
    }

    /**
     * The local variable definition and assigment statement.
     *
     * @param variable   The local variable
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record DefineAndAssign(VariableDef.Local variable,
                           ExpressionDef expression) implements StatementDef {
    }

    /**
     * The if statement.
     *
     * @param condition The condition
     * @param statement The statement if the condition is true
     */
    @Experimental
    record If(ExpressionDef condition, StatementDef statement) implements StatementDef {
    }

    /**
     * The if-else statement.
     *
     * @param condition     The condition
     * @param statement     The statement if the condition is true
     * @param elseStatement The statement if the condition is false
     */
    @Experimental
    record IfElse(ExpressionDef condition, StatementDef statement,
                  StatementDef elseStatement) implements StatementDef {
    }

    /**
     * The switch statement.
     * Note: null constant or null value represents a default case.
     *
     * @param expression The switch expression
     * @param type       The switch type
     * @param cases      The cases
     * @since 1.2
     */
    @Experimental
    record Switch(ExpressionDef expression,
                  TypeDef type,
                  Map<ExpressionDef.Constant, StatementDef> cases) implements StatementDef {
    }

    /**
     * The while statement.
     *
     * @param expression The while expression
     * @param statement  The while statement
     * @since 1.2
     */
    @Experimental
    record While(ExpressionDef expression, StatementDef statement) implements StatementDef {
    }

}
