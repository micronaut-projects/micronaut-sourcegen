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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;

import java.util.List;
import java.util.function.Predicate;

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

    static boolean declaresField(@Nullable ObjectDef objectDef, String name) {
        List<FieldDef> fields = switch (objectDef) {
            case ClassDef classDef -> classDef.getFields();
            case EnumDef enumDef -> enumDef.getFields();
            case null, default -> List.of();
        };
        return fields.stream().anyMatch(field -> field.getName().equals(name));
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
        Assignment assignment = assignmentOf(classDef.getStaticInitializer(), classDef.asTypeDef().getName(), fieldName);
        return assignment.definite() && !assignment.repeatable();
    }

    /**
     * Whether a final instance field of the definition is assigned as Java requires of one: by its initializer and no
     * constructor, or, blank, exactly once by every constructor that does not delegate to another - which is what lets
     * the field keep its {@code final} modifier. A model written for bytecode can assign it in one constructor of
     * several, or conditionally, which the verifier accepts.
     *
     * @param objectDef The definition
     * @param field     The field
     * @return true if the field keeps its {@code final} modifier
     */
    static boolean keepsFinal(ObjectDef objectDef, FieldDef field) {
        List<MethodDef> constructors = objectDef.getMethods().stream().filter(MethodDef::isConstructor).toList();
        Predicate<StatementDef> assigns = statement -> statement instanceof StatementDef.PutField put
            && put.field().name().equals(field.getName()) && put.field().instance() instanceof VariableDef.This;
        if (field.getInitializer().isPresent()) {
            return constructors.stream().noneMatch(constructor -> assignmentOf(bodyOf(constructor), assigns).possible());
        }
        if (constructors.isEmpty()) {
            return false;
        }
        for (MethodDef constructor : constructors) {
            Assignment assignment = assignmentOf(bodyOf(constructor), assigns);
            if (delegates(constructor) ? assignment.possible() : !assignment.definite() || assignment.repeatable()) {
                return false;
            }
        }
        return true;
    }

    private static StatementDef bodyOf(MethodDef method) {
        return StatementDef.multi(method.getStatements());
    }

    /**
     * Whether a constructor delegates to another of the class, which assigns the final fields for it.
     */
    private static boolean delegates(MethodDef constructor) {
        return constructor.getStatements().stream().anyMatch(statement -> statement instanceof ExpressionDef.InvokeInstanceMethod invocation
            && invocation.method().isConstructor() && invocation.instance() instanceof VariableDef.This);
    }

    static boolean declaresLocal(@Nullable StatementDef statement, String name) {
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

    private static Assignment assignmentOf(@Nullable StatementDef statement, String ownerName, String fieldName) {
        // A field of another type shares nothing with this one but its name
        return assignmentOf(statement, child -> child instanceof StatementDef.PutStaticField put
            && put.field().name().equals(fieldName) && put.field().ownerType().getName().equals(ownerName));
    }

    private static Assignment assignmentOf(@Nullable StatementDef statement, Predicate<StatementDef> assigns) {
        return switch (statement) {
            case null -> Assignment.NONE;
            // No path completes normally past it, so the field is as assigned as it needs to be there
            case StatementDef.Throw aThrow -> Assignment.VACUOUS;
            case StatementDef.PutStaticField put -> assigns.test(put) ? Assignment.ONCE : Assignment.NONE;
            case StatementDef.PutField put -> assigns.test(put) ? Assignment.ONCE : Assignment.NONE;
            case StatementDef.Multi multi -> {
                boolean definite = false;
                boolean possible = false;
                boolean repeatable = false;
                for (StatementDef child : multi.statements()) {
                    Assignment assignment = assignmentOf(child, assigns);
                    repeatable |= assignment.repeatable() || (possible && assignment.possible());
                    definite |= assignment.definite();
                    possible |= assignment.possible();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.If anIf -> {
                Assignment assignment = assignmentOf(anIf.statement(), assigns);
                yield new Assignment(false, assignment.possible(), assignment.repeatable());
            }
            case StatementDef.IfElse ifElse -> {
                // The branches are mutually exclusive: assigning in each of them assigns the field exactly once
                Assignment then = assignmentOf(ifElse.statement(), assigns);
                Assignment otherwise = assignmentOf(ifElse.elseStatement(), assigns);
                yield new Assignment(then.definite() && otherwise.definite(), then.possible() || otherwise.possible(),
                    then.repeatable() || otherwise.repeatable());
            }
            case StatementDef.Switch aSwitch -> {
                boolean definite = aSwitch.defaultCase() != null
                    && assignmentOf(aSwitch.defaultCase(), assigns).definite();
                boolean possible = assignmentOf(aSwitch.defaultCase(), assigns).possible();
                boolean repeatable = assignmentOf(aSwitch.defaultCase(), assigns).repeatable();
                for (StatementDef aCase : aSwitch.cases().values()) {
                    Assignment assignment = assignmentOf(aCase, assigns);
                    definite &= assignment.definite();
                    possible |= assignment.possible();
                    repeatable |= assignment.repeatable();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.While aWhile -> {
                // An iteration could assign what the one before it did
                Assignment assignment = assignmentOf(aWhile.statement(), assigns);
                yield new Assignment(false, assignment.possible(), assignment.possible() || assignment.repeatable());
            }
            case StatementDef.Synchronized aSynchronized -> assignmentOf(aSynchronized.statement(), assigns);
            case StatementDef.Try aTry -> {
                Assignment body = assignmentOf(aTry.statement(), assigns);
                Assignment aFinally = assignmentOf(aTry.finallyStatement(), assigns);
                boolean catchesPossible = false;
                boolean catchesDefinite = true;
                boolean repeatable = body.repeatable() || aFinally.repeatable();
                for (StatementDef.Try.Catch aCatch : aTry.catches()) {
                    Assignment assignment = assignmentOf(aCatch.statement(), assigns);
                    catchesPossible |= assignment.possible();
                    catchesDefinite &= assignment.definite();
                    repeatable |= assignment.repeatable();
                }
                // A path through the body, or through a catch, may have assigned the field before reaching the
                // next one, which is what Java reports as possibly already assigned
                repeatable |= (body.possible() || catchesPossible) && aFinally.possible()
                    || body.possible() && catchesPossible;
                boolean definite = aFinally.definite() || (body.definite() && catchesDefinite);
                yield new Assignment(definite, body.possible() || catchesPossible || aFinally.possible(), repeatable);
            }
            default -> Assignment.NONE;
        };
    }

    static boolean cannotCompleteNormally(StatementDef statementDef) {
        return switch (statementDef) {
            case StatementDef.Return _, StatementDef.Throw _ -> true;
            // A block ends with a statement that cannot complete, or already stops at one
            case StatementDef.Multi multi -> multi.statements().stream().anyMatch(JavaSourceRules::cannotCompleteNormally);
            case StatementDef.IfElse ifElse ->
                cannotCompleteNormally(ifElse.statement()) && cannotCompleteNormally(ifElse.elseStatement());
            case StatementDef.Switch aSwitch -> aSwitch.defaultCase() != null
                && cannotCompleteNormally(aSwitch.defaultCase())
                && aSwitch.cases().values().stream().allMatch(JavaSourceRules::cannotCompleteNormally);
            // A finally that cannot complete ends the try, whatever the try does; one that can, completes as the try
            // and its catches do
            case StatementDef.Try aTry -> aTry.finallyStatement() != null && cannotCompleteNormally(aTry.finallyStatement())
                || cannotCompleteNormally(aTry.statement())
                && aTry.catches().stream().allMatch(aCatch -> cannotCompleteNormally(aCatch.statement()));
            case StatementDef.Synchronized aSynchronized -> cannotCompleteNormally(aSynchronized.statement());
            // The model has no break: `while (true)` only ends by a return or a throw
            case StatementDef.While aWhile -> isConstantTrue(aWhile.expression());
            default -> false;
        };
    }

    private static boolean isConstantTrue(ExpressionDef expression) {
        return Boolean.TRUE.equals(constantValue(expression));
    }

    static boolean isConstantFalse(ExpressionDef expression) {
        return Boolean.FALSE.equals(constantValue(expression));
    }

    /**
     * The value of a constant expression as Java evaluates one, which is what decides whether a loop over it can
     * complete: a literal, and the negations, conjunctions and comparisons of constant expressions.
     */
    @Nullable
    private static Object constantValue(ExpressionDef expression) {
        return switch (expression) {
            case ExpressionDef.Constant constant -> constant.value() instanceof Boolean || constant.value() instanceof Number
                || constant.value() instanceof Character ? constant.value() : null;
            // A cast to a primitive, or one that is not written, keeps a constant expression one
            case ExpressionDef.Cast cast -> constantValue(cast.expressionDef());
            case ExpressionDef.IsTrue isTrue -> constantValue(isTrue.expression());
            case ExpressionDef.IsFalse isFalse -> constantValue(isFalse.expression()) instanceof Boolean value ? !value : null;
            case ExpressionDef.And and -> constantValue(and.left()) instanceof Boolean left
                && constantValue(and.right()) instanceof Boolean right ? left && right : null;
            case ExpressionDef.Or or -> constantValue(or.left()) instanceof Boolean left
                && constantValue(or.right()) instanceof Boolean right ? left || right : null;
            case ExpressionDef.ComparisonOperation comparison -> compared(comparison.opType(),
                constantValue(comparison.left()), constantValue(comparison.right()));
            case ExpressionDef.EqualsReferentially equals -> compared(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                constantValue(equals.instance()), constantValue(equals.other()));
            case ExpressionDef.NotEqualsReferentially notEquals -> compared(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO,
                constantValue(notEquals.instance()), constantValue(notEquals.other()));
            default -> null;
        };
    }

    @Nullable
    private static Boolean compared(ExpressionDef.ComparisonOperation.OpType op, @Nullable Object left, @Nullable Object right) {
        if (left instanceof Boolean && right instanceof Boolean) {
            return switch (op) {
                case EQUAL_TO -> left.equals(right);
                case NOT_EQUAL_TO -> !left.equals(right);
                default -> null;
            };
        }
        if (!(left instanceof Number || left instanceof Character) || !(right instanceof Number || right instanceof Character)) {
            return null;
        }
        int order = Double.compare(left instanceof Character c ? c : ((Number) left).doubleValue(),
            right instanceof Character c ? c : ((Number) right).doubleValue());
        return switch (op) {
            case EQUAL_TO -> order == 0;
            case NOT_EQUAL_TO -> order != 0;
            case GREATER_THAN -> order > 0;
            case LESS_THAN -> order < 0;
            case GREATER_THAN_OR_EQUAL -> order >= 0;
            case LESS_THAN_OR_EQUAL -> order <= 0;
        };
    }

    static void validateFieldAccess(@Nullable ObjectDef objectDef, VariableDef.Field field) {
        if (objectDef == null) {
            throw new IllegalStateException("Accessing 'this' is not available");
        }
        if (!(field.declaringType() instanceof ClassTypeDef declaringType)
            || !declaringType.getName().equals(objectDef.asTypeDef().getName())) {
            // The field is declared by a different type than the one currently being rendered - e.g. a
            // property accessed on an instance of some other (possibly external, already-compiled) type
            // - so there is nothing in `objectDef` to validate the access against.
            return;
        }
        switch (objectDef) {
            case ClassDef classDef when classDef.hasField(field.name()) -> {
                return;
            }
            case ClassDef classDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + classDef + "]:" + classDef.getFields());
            case EnumDef enumDef when enumDef.hasField(field.name()) -> {
                return;
            }
            case EnumDef enumDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + enumDef.getName() + "]:" + enumDef.getProperties());
            default ->
                throw new IllegalStateException("Field access not supported on the object definition: " + objectDef);
        }
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
        private static final Assignment VACUOUS = new Assignment(true, false, false);
    }
}
