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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The statement definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface StatementDef permits ExpressionDef.InvokeInstanceMethod, ExpressionDef.InvokeStaticMethod, StatementDef.Assign, StatementDef.DefineAndAssign, StatementDef.If, StatementDef.IfElse, StatementDef.Multi, StatementDef.PutField, StatementDef.PutStaticField, StatementDef.Return, StatementDef.Switch, StatementDef.Synchronized, StatementDef.Throw, StatementDef.Try, StatementDef.While {

    /**
     * The helper method to turn this statement into a multi statement.
     *
     * @param statement statement
     * @return statement
     * @since 1.2
     */
    default StatementDef after(StatementDef statement) {
        return multi(this, statement);
    }

    /**
     * Flatten the collection.
     *
     * @return all the statements
     * @since 1.2
     */
    default List<StatementDef> flatten() {
        return List.of(this);
    }

    /**
     * Stream of nested expressions included in this statement.
     *
     * @return all the expressions
     * @since 1.7
     */
    Stream<? extends ExpressionDef> nestedExpressionsStream();

    /**
     * Try statement.
     *
     * @return The try statement
     * @since 1.5
     */
    default Try doTry() {
        return new Try(this);
    }

    /**
     * Try statement.
     *
     * @param statement The statement to try
     * @return The try statement
     * @since 1.5
     */
    static Try doTry(StatementDef statement) {
        return new Try(statement);
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

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return statements.stream().flatMap(StatementDef::nestedExpressionsStream);
        }

        public List<StatementDef> statements() {
            return flatten();
        }

        @Override
        public List<StatementDef> flatten() {
            List<StatementDef> result = new ArrayList<>(statements.size());
            for (StatementDef statement : statements) {
                result.addAll(statement.flatten());
            }
            return result;
        }
    }

    /**
     * The throw statement.
     *
     * @param expression The exception expression
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record Throw(ExpressionDef expression) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.of(expression);
        }
    }

    /**
     * The return statement.
     *
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Return(@Nullable ExpressionDef expression) implements StatementDef {

        /**
         * Validate the return of the method.
         * @param method The method
         */
        public void validate(MethodDef method) {
            if ((expression == null || expression.type().equals(TypeDef.VOID)) && !method.getReturnType().equals(TypeDef.VOID)) {
                throw new IllegalStateException("The return expression returns VOID but method: " + method.getName() + " doesn't return VOID (" + method.getReturnType() + ")!");
            }
            if (expression != null && !expression.type().equals(TypeDef.VOID) && method.getReturnType().equals(TypeDef.VOID)) {
                throw new IllegalStateException("The return expression (" + expression.type() + ") doesn't returns VOID but method: " + method.getName() + " returns VOID!");
            }
        }

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return expression == null ? Stream.empty() : Stream.of(expression);
        }
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
    record Assign(VariableDef.Local variable,
                  ExpressionDef expression) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.of(variable, expression);
        }
    }

    /**
     * The put field expression.
     *
     * @param field   The Field
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.5
     */
    @Experimental
    record PutField(VariableDef.Field field,
                    ExpressionDef expression) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.of(field, expression);
        }
    }

    /**
     * The set a static field expression.
     *
     * @param field      The field
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.5
     */
    @Experimental
    record PutStaticField(VariableDef.StaticField field,
                          ExpressionDef expression) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.of(field, expression);
        }
    }

    /**
     * The local variable definition and assignment statement.
     *
     * @param variable   The local variable
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record DefineAndAssign(VariableDef.Local variable,
                           ExpressionDef expression) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.of(variable, expression);
        }
    }

    /**
     * The if statement.
     *
     * @param condition The condition
     * @param statement The statement if the condition is true
     */
    @Experimental
    record If(ExpressionDef condition, StatementDef statement) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                Stream.of(condition),
                statement.nestedExpressionsStream()
            );
        }

    }

    /**
     * The if-else statement.
     *
     * @param condition     The condition
     * @param statement     The statement if the condition is true
     * @param elseStatement The statement if the condition is false
     */
    @Experimental
    record IfElse(ExpressionDef condition,
                  StatementDef statement,
                  StatementDef elseStatement) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                Stream.of(condition),
                Stream.concat(
                    statement.nestedExpressionsStream(),
                    elseStatement.nestedExpressionsStream()
                )
            );
        }

    }

    /**
     * The switch statement.
     * Note: null constant or null value represents a default case.
     *
     * @param expression  The switch expression
     * @param type        The switch type
     * @param cases       The cases
     * @param defaultCase The default case
     * @since 1.2
     */
    @Experimental
    record Switch(ExpressionDef expression,
                  TypeDef type,
                  Map<ExpressionDef.Constant, StatementDef> cases,
                  @Nullable StatementDef defaultCase) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                Stream.of(expression),
                Stream.concat(
                    cases.values().stream().flatMap(StatementDef::nestedExpressionsStream),
                    defaultCase == null ? Stream.empty() : defaultCase.nestedExpressionsStream()
                )
            );
        }

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

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                Stream.of(expression),
                statement.nestedExpressionsStream()
            );
        }

    }

    /**
     * The try statement.
     *
     * @param statement        The try statement
     * @param catches          The catches
     * @param finallyStatement The final statement
     * @since 1.5
     */
    @Experimental
    record Try(StatementDef statement,
               List<Catch> catches,
               @Nullable StatementDef finallyStatement) implements StatementDef {

        public Try(StatementDef statement) {
            this(statement, List.of(), null);
        }

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                statement.nestedExpressionsStream(),
                Stream.concat(
                    catches.stream().flatMap(c -> c.statement.nestedExpressionsStream()),
                    finallyStatement == null ? Stream.empty() : finallyStatement.nestedExpressionsStream()
                )
            );
        }

        public Try doCatch(Class<?> exception, Function<VariableDef.ExceptionVar, StatementDef> catchBlock) {
            return doCatch(ClassTypeDef.of(exception), catchBlock);
        }

        public Try doCatch(ClassTypeDef exception, Function<VariableDef.ExceptionVar, StatementDef> catchBlock) {
            return new Try(statement,
                CollectionUtils.concat(
                    catches,
                    new Catch(exception, catchBlock.apply(new VariableDef.ExceptionVar(exception)))
                ),
                finallyStatement);
        }

        public Try doFinally(StatementDef finallyStatement) {
            if (this.finallyStatement != null) {
                throw new IllegalStateException("Finally statement already exists!");
            }
            return new Try(statement, catches, finallyStatement);
        }

        /**
         * The catch.
         *
         * @param exception The exception
         * @param statement The catch statement
         * @since 1.2
         */
        @Experimental
        public record Catch(ClassTypeDef exception, StatementDef statement) {
        }

    }

    /**
     * The synchronized statement.
     *
     * @param monitor   The monitor
     * @param statement The statement to be synchronized
     * @since 1.5
     */
    @Experimental
    record Synchronized(ExpressionDef monitor, StatementDef statement) implements StatementDef {

        @Override
        public Stream<? extends ExpressionDef> nestedExpressionsStream() {
            return Stream.concat(
                Stream.of(monitor),
                statement.nestedExpressionsStream()
            );
        }

    }

}
