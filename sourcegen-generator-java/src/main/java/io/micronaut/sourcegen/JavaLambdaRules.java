/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What the locals of a body are to the lambdas in it. The bytecode writers pass a captured local by the value it has
 * where the lambda is created, and the lambda assigns a copy of its own; Java requires a captured local to be
 * effectively final.
 *
 * @since 2.3
 */
@Internal
final class JavaLambdaRules {

    private JavaLambdaRules() {
    }

    /**
     * The names of the locals a body assigns after declaring them - in blocks of switch expressions too, not in the
     * lambdas of the body, which assign copies of their own: those locals are not effectively final.
     *
     * @param statements The statements of the body
     * @return The names
     */
    static Set<String> assignedLocals(List<StatementDef> statements) {
        Set<String> names = new HashSet<>();
        statements.forEach(statement -> visit(statement, false, child -> {
            if (child instanceof StatementDef.Assign assign && assign.variable() instanceof VariableDef.Local local) {
                names.add(local.name());
            }
        }, expression -> { }));
        return names;
    }

    /**
     * The locals a lambda captures: those its body - nested lambdas included - names and does not declare.
     *
     * @param lambda The lambda
     * @return The types of the captured locals by their names
     */
    static Map<String, TypeDef> capturedLocals(ExpressionDef.Lambda lambda) {
        Set<String> declared = new HashSet<>();
        Map<String, TypeDef> named = new LinkedHashMap<>();
        lambda.implementation().getStatements().forEach(statement -> visit(statement, true, child -> {
            if (child instanceof StatementDef.DefineAndAssign define) {
                declared.add(define.variable().name());
            }
        }, expression -> {
            if (expression instanceof VariableDef.Local local) {
                named.putIfAbsent(local.name(), local.type());
            }
        }));
        declared.forEach(named::remove);
        return named;
    }

    /**
     * Visits the expressions a body evaluates, in blocks of switch expressions too, not in the lambdas of the body.
     *
     * @param statements The statements of the body
     * @param action     The action
     */
    static void forEachExpression(List<StatementDef> statements, Consumer<ExpressionDef> action) {
        statements.forEach(statement -> visit(statement, false, child -> { }, action));
    }

    /**
     * The names a body declares variables by: its locals and the parameters of its lambdas, nested lambdas included.
     *
     * @param statements The statements of the body
     * @return The names
     */
    static Set<String> declaredNames(List<StatementDef> statements) {
        Set<String> names = new HashSet<>();
        statements.forEach(statement -> visit(statement, true, child -> {
            if (child instanceof StatementDef.DefineAndAssign define) {
                names.add(define.variable().name());
            }
        }, expression -> {
            if (expression instanceof ExpressionDef.Lambda lambda) {
                lambda.implementation().getParameters().forEach(parameter -> names.add(parameter.getName()));
            }
        }));
        return names;
    }

    /**
     * Visits the statements of a body, in blocks of switch expressions too, not in the lambdas of the body.
     *
     * @param statements The statements of the body
     * @param action     The action
     */
    static void forEachStatement(List<StatementDef> statements, Consumer<StatementDef> action) {
        statements.forEach(statement -> visit(statement, false, action, expression -> { }));
    }

    /**
     * The lambdas a statement creates where it is executed: in the expressions of a simple statement, and in the
     * condition of an {@code if} or the selector of a {@code switch} - not those in the body of another lambda, nor in
     * a block of a switch expression, whose statements create them, nor in a loop condition, which creates them anew
     * for each iteration.
     *
     * @param statement The statement
     * @return The lambdas
     */
    static List<ExpressionDef.Lambda> createdLambdas(StatementDef statement) {
        List<ExpressionDef.Lambda> lambdas = new ArrayList<>();
        if (!(statement instanceof StatementDef.While)) {
            expressionsOf(statement).forEach(expression -> collectLambdas(expression, lambdas));
        }
        return lambdas;
    }

    private static void collectLambdas(ExpressionDef expression, List<ExpressionDef.Lambda> lambdas) {
        if (expression instanceof ExpressionDef.Lambda lambda) {
            lambdas.add(lambda);
        } else if (!(expression instanceof ExpressionDef.SwitchYieldCase)) {
            expression.nestedExpressionsStream().forEach(nested -> collectLambdas(nested, lambdas));
        }
    }

    /**
     * Visits a statement, the statements nested in it - those of the blocks of switch expressions too, and of the
     * bodies of lambdas where asked to - and the expressions each of them evaluates.
     */
    private static void visit(@Nullable StatementDef statement,
                              boolean intoLambdas,
                              Consumer<StatementDef> onStatement,
                              Consumer<ExpressionDef> onExpression) {
        if (statement == null) {
            return;
        }
        onStatement.accept(statement);
        expressionsOf(statement).forEach(expression -> visitExpression(expression, intoLambdas, onStatement, onExpression));
        childrenOf(statement).forEach(child -> visit(child, intoLambdas, onStatement, onExpression));
    }

    private static void visitExpression(ExpressionDef expression,
                                        boolean intoLambdas,
                                        Consumer<StatementDef> onStatement,
                                        Consumer<ExpressionDef> onExpression) {
        onExpression.accept(expression);
        if (expression instanceof ExpressionDef.Lambda lambda) {
            if (intoLambdas) {
                lambda.implementation().getStatements().forEach(statement -> visit(statement, true, onStatement, onExpression));
            }
        } else if (expression instanceof ExpressionDef.SwitchYieldCase block) {
            visit(block.statement(), intoLambdas, onStatement, onExpression);
        } else {
            expression.nestedExpressionsStream().forEach(nested -> visitExpression(nested, intoLambdas, onStatement, onExpression));
        }
    }

    /**
     * The expressions a statement evaluates itself, not those of the statements nested in it.
     */
    private static List<ExpressionDef> expressionsOf(StatementDef statement) {
        return switch (statement) {
            case StatementDef.If anIf -> List.of(anIf.condition());
            case StatementDef.IfElse ifElse -> List.of(ifElse.condition());
            case StatementDef.Switch aSwitch -> List.of(aSwitch.expression());
            case StatementDef.While aWhile -> List.of(aWhile.expression());
            case StatementDef.Synchronized aSynchronized -> List.of(aSynchronized.monitor());
            case StatementDef.Multi _, StatementDef.Try _ -> List.of();
            case ExpressionDef expression -> List.of(expression);
            default -> statement.nestedExpressionsStream().<ExpressionDef>map(expression -> expression).toList();
        };
    }

    private static List<StatementDef> childrenOf(StatementDef statement) {
        List<StatementDef> children = new ArrayList<>();
        switch (statement) {
            case StatementDef.Multi multi -> children.addAll(multi.statements());
            case StatementDef.If anIf -> children.add(anIf.statement());
            case StatementDef.IfElse ifElse -> {
                children.add(ifElse.statement());
                children.add(ifElse.elseStatement());
            }
            case StatementDef.Switch aSwitch -> {
                children.addAll(aSwitch.cases().values());
                if (aSwitch.defaultCase() != null) {
                    children.add(aSwitch.defaultCase());
                }
            }
            case StatementDef.While aWhile -> children.add(aWhile.statement());
            case StatementDef.Synchronized aSynchronized -> children.add(aSynchronized.statement());
            case StatementDef.Try aTry -> {
                children.add(aTry.statement());
                aTry.catches().forEach(aCatch -> children.add(aCatch.statement()));
                if (aTry.finallyStatement() != null) {
                    children.add(aTry.finallyStatement());
                }
            }
            default -> {
            }
        }
        return children;
    }
}
