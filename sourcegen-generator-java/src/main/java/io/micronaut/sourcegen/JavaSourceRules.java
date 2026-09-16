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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What Java makes of a statement: whether it can complete normally, whether it has to be written as a statement of
 * its own, and where a blank final static field is definitely assigned.
 *
 * @since 2.2
 */
@Internal
final class JavaSourceRules {

    private JavaSourceRules() {
    }

    /**
     * @param lambda The lambda
     * @return The expression of a single expression body, or {@code null} for a block body
     */
    @Nullable
    static ExpressionDef singleExpressionBody(Lambda lambda) {
        List<StatementDef> statements = lambda.implementation().getStatements();
        if (statements.size() == 1 && statements.get(0) instanceof StatementDef.Return(ExpressionDef expression)) {
            return expression;
        }
        return null;
    }

    static boolean containsBlockBodyLambda(StatementDef statementDef) {
        return statementDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
    }

    static boolean containsBlockBodyLambda(ExpressionDef expressionDef) {
        if (expressionDef instanceof Lambda lambda) {
            // A lambda does not expose its body as nested expressions, so descend into it explicitly
            return singleExpressionBody(lambda) == null
                || lambda.implementation().getStatements().stream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
        }
        return expressionDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
    }

    static boolean containsSwitchExpression(StatementDef statementDef) {
        return statementDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsSwitchExpression);
    }

    static boolean containsSwitchExpression(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Switch
            || expressionDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsSwitchExpression);
    }

    /**
     * Whether the static initializer of the definition assigns a field exactly once and outside any control flow,
     * which is what Java's definite-assignment checks accept for a blank final.
     *
     * @param objectDef The definition
     * @param fieldName The field name
     * @return true if the field keeps its `final` modifier
     */
    static boolean isAssignedOnceInStaticInitializer(@Nullable ObjectDef objectDef, String fieldName) {
        if (!(objectDef instanceof ClassDef classDef)) {
            return false;
        }
        return countAssignments(classDef.getStaticInitializer(), fieldName, false) == 1;
    }

    static int countAssignments(@Nullable StatementDef statement, String fieldName, boolean conditional) {
        // A conditional assignment counts twice, so that it never reads as the single unconditional one
        return switch (statement) {
            case null -> 0;
            case StatementDef.PutStaticField put ->
                put.field().name().equals(fieldName) ? (conditional ? 2 : 1) : 0;
            case StatementDef.Multi multi -> multi.statements().stream()
                .mapToInt(child -> countAssignments(child, fieldName, conditional)).sum();
            case StatementDef.If anIf -> countAssignments(anIf.statement(), fieldName, true);
            case StatementDef.IfElse ifElse -> countAssignments(ifElse.statement(), fieldName, true)
                + countAssignments(ifElse.elseStatement(), fieldName, true);
            case StatementDef.Switch aSwitch -> aSwitch.cases().values().stream()
                .mapToInt(aCase -> countAssignments(aCase, fieldName, true)).sum()
                + countAssignments(aSwitch.defaultCase(), fieldName, true);
            case StatementDef.While aWhile -> countAssignments(aWhile.statement(), fieldName, true);
            case StatementDef.Synchronized aSynchronized ->
                countAssignments(aSynchronized.statement(), fieldName, conditional);
            case StatementDef.Try aTry -> countAssignments(aTry.statement(), fieldName, true)
                + aTry.catches().stream().mapToInt(aCatch -> countAssignments(aCatch.statement(), fieldName, true)).sum()
                + countAssignments(aTry.finallyStatement(), fieldName, true);
            default -> 0;
        };
    }

    static boolean cannotCompleteNormally(StatementDef statementDef) {
        return switch (statementDef) {
            case StatementDef.Return _, StatementDef.Throw _ -> true;
            case StatementDef.Multi multi ->
                !multi.statements().isEmpty() && cannotCompleteNormally(multi.statements().getLast());
            case StatementDef.IfElse ifElse ->
                cannotCompleteNormally(ifElse.statement()) && cannotCompleteNormally(ifElse.elseStatement());
            case StatementDef.Switch aSwitch -> aSwitch.defaultCase() != null
                && cannotCompleteNormally(aSwitch.defaultCase())
                && aSwitch.cases().values().stream().allMatch(JavaSourceRules::cannotCompleteNormally);
            case StatementDef.Try aTry -> aTry.finallyStatement() == null
                && cannotCompleteNormally(aTry.statement())
                && aTry.catches().stream().allMatch(aCatch -> cannotCompleteNormally(aCatch.statement()));
            default -> false;
        };
    }

    static boolean hasSwitchYieldReturn(StatementDef statementDef) {
        List<StatementDef> statements = statementDef.flatten();
        if (statements.isEmpty()) {
            return false;
        }
        StatementDef last = statements.getLast();
        return switch (last) {
            case StatementDef.Return(_) -> true;
            case StatementDef.IfElse(_, StatementDef statement, StatementDef elseStatement) ->
                hasSwitchYieldReturn(statement) && hasSwitchYieldReturn(elseStatement);
            default -> false;
        };
    }
}
