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

import javax.lang.model.element.Modifier;

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
     * Whether a blank final static field of the definition is definitely assigned exactly once by its static
     * initializer, which is what Java requires of one - and what lets the field keep its {@code final} modifier and
     * be assigned by its unqualified name.
     *
     * @param objectDef The definition
     * @param fieldName The field name
     * @return true if the field keeps its {@code final} modifier
     */
    static boolean keepsFinal(@Nullable ObjectDef objectDef, String fieldName) {
        if (!(objectDef instanceof ClassDef classDef) || classDef.getStaticInitializer() == null) {
            return false;
        }
        boolean blankFinal = classDef.getFields().stream()
            .filter(field -> field.getName().equals(fieldName))
            .anyMatch(field -> field.getModifiers().contains(Modifier.STATIC)
                && field.getModifiers().contains(Modifier.FINAL)
                && field.getInitializer().isEmpty());
        if (!blankFinal) {
            return false;
        }
        // A local of the same name takes over the unqualified assignment the field would need
        if (declaresLocal(classDef.getStaticInitializer(), fieldName)) {
            return false;
        }
        Assignment assignment = assignmentOf(classDef.getStaticInitializer(), fieldName);
        return assignment.definite() && !assignment.repeatable();
    }

    private static boolean declaresLocal(@Nullable StatementDef statement, String name) {
        return switch (statement) {
            case null -> false;
            case StatementDef.DefineAndAssign define -> define.variable().name().equals(name);
            case StatementDef.Multi multi -> multi.statements().stream().anyMatch(child -> declaresLocal(child, name));
            case StatementDef.If anIf -> declaresLocal(anIf.statement(), name);
            case StatementDef.IfElse ifElse -> declaresLocal(ifElse.statement(), name)
                || declaresLocal(ifElse.elseStatement(), name);
            case StatementDef.Switch aSwitch -> declaresLocal(aSwitch.defaultCase(), name)
                || aSwitch.cases().values().stream().anyMatch(aCase -> declaresLocal(aCase, name));
            case StatementDef.While aWhile -> declaresLocal(aWhile.statement(), name);
            case StatementDef.Synchronized aSynchronized -> declaresLocal(aSynchronized.statement(), name);
            case StatementDef.Try aTry -> declaresLocal(aTry.statement(), name)
                || declaresLocal(aTry.finallyStatement(), name)
                || aTry.catches().stream().anyMatch(aCatch -> declaresLocal(aCatch.statement(), name));
            default -> false;
        };
    }

    private static Assignment assignmentOf(@Nullable StatementDef statement, String fieldName) {
        return switch (statement) {
            case null -> Assignment.NONE;
            case StatementDef.PutStaticField put ->
                put.field().name().equals(fieldName) ? Assignment.ONCE : Assignment.NONE;
            case StatementDef.Multi multi -> {
                boolean definite = false;
                boolean possible = false;
                boolean repeatable = false;
                for (StatementDef child : multi.statements()) {
                    Assignment assignment = assignmentOf(child, fieldName);
                    repeatable |= assignment.repeatable() || (possible && assignment.possible());
                    definite |= assignment.definite();
                    possible |= assignment.possible();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.If anIf -> {
                Assignment assignment = assignmentOf(anIf.statement(), fieldName);
                yield new Assignment(false, assignment.possible(), assignment.repeatable());
            }
            case StatementDef.IfElse ifElse -> {
                // The branches are mutually exclusive: assigning in each of them assigns the field exactly once
                Assignment then = assignmentOf(ifElse.statement(), fieldName);
                Assignment otherwise = assignmentOf(ifElse.elseStatement(), fieldName);
                yield new Assignment(then.definite() && otherwise.definite(), then.possible() || otherwise.possible(),
                    then.repeatable() || otherwise.repeatable());
            }
            case StatementDef.Switch aSwitch -> {
                boolean definite = aSwitch.defaultCase() != null
                    && assignmentOf(aSwitch.defaultCase(), fieldName).definite();
                boolean possible = assignmentOf(aSwitch.defaultCase(), fieldName).possible();
                boolean repeatable = assignmentOf(aSwitch.defaultCase(), fieldName).repeatable();
                for (StatementDef aCase : aSwitch.cases().values()) {
                    Assignment assignment = assignmentOf(aCase, fieldName);
                    definite &= assignment.definite();
                    possible |= assignment.possible();
                    repeatable |= assignment.repeatable();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.While aWhile -> {
                // An iteration could assign what the one before it did
                Assignment assignment = assignmentOf(aWhile.statement(), fieldName);
                yield new Assignment(false, assignment.possible(), assignment.possible() || assignment.repeatable());
            }
            case StatementDef.Synchronized aSynchronized -> assignmentOf(aSynchronized.statement(), fieldName);
            case StatementDef.Try aTry -> {
                Assignment body = assignmentOf(aTry.statement(), fieldName);
                Assignment aFinally = assignmentOf(aTry.finallyStatement(), fieldName);
                boolean catchesPossible = false;
                boolean catchesDefinite = true;
                boolean repeatable = body.repeatable() || aFinally.repeatable();
                for (StatementDef.Try.Catch aCatch : aTry.catches()) {
                    Assignment assignment = assignmentOf(aCatch.statement(), fieldName);
                    catchesPossible |= assignment.possible();
                    catchesDefinite &= assignment.definite();
                    repeatable |= assignment.repeatable();
                }
                // The body may have assigned the field before it threw, so a catch or a finally assigning it again
                // is what Java reports as possibly already assigned
                repeatable |= body.possible() && (catchesPossible || aFinally.possible());
                boolean definite = aFinally.definite() || (body.definite() && catchesDefinite);
                yield new Assignment(definite, body.possible() || catchesPossible || aFinally.possible(), repeatable);
            }
            default -> Assignment.NONE;
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

    /**
     * How a statement assigns one field.
     *
     * @param definite   Whether every path that completes it normally has assigned the field
     * @param possible   Whether some path has
     * @param repeatable Whether a path could assign the field a second time, which Java rejects for a blank final
     */
    private record Assignment(boolean definite, boolean possible, boolean repeatable) {
        private static final Assignment NONE = new Assignment(false, false, false);
        private static final Assignment ONCE = new Assignment(true, true, false);
    }
}
