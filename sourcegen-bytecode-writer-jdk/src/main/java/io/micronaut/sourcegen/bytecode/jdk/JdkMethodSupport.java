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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

/**
 * Checks whether a model tree can currently be lowered by {@link JdkMethodWriter}.
 *
 * @since 2.2
 */
final class JdkMethodSupport {

    private JdkMethodSupport() {
    }

    static boolean supported(MethodDef method) {
        if (method.getStatements().isEmpty()) {
            return method.getReturnType().equals(io.micronaut.sourcegen.model.TypeDef.VOID);
        }
        if (method.isConstructor() && method.getTypeVariables().size() > 0) {
            return false;
        }
        for (StatementDef statement : method.getStatements()) {
            if (!supported(statement)) {
                return false;
            }
        }
        return true;
    }

    static boolean supported(StatementDef statement) {
        return switch (statement) {
            case StatementDef.Multi multi -> multi.statements().stream().allMatch(JdkMethodSupport::supported);
            case StatementDef.Return returnStatement -> returnStatement.expression() == null
                || supported(returnStatement.expression());
            case StatementDef.Throw throwing -> supported(throwing.expression());
            case StatementDef.DefineAndAssign define -> supported(define.expression());
            case StatementDef.Assign assign -> supported(assign.expression());
            case StatementDef.PutField putField -> supported(putField.field().instance()) && supported(putField.expression());
            case StatementDef.PutStaticField putStaticField -> supported(putStaticField.expression());
            case StatementDef.InvokeSuperConstructor invoke -> invoke.values().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.InvokeInstanceMethod invoke -> supported(invoke.instance())
                && invoke.values().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.InvokeStaticMethod invoke -> invoke.values().stream().allMatch(JdkMethodSupport::supported);
            case StatementDef.If anIf -> supported(anIf.condition()) && supported(anIf.statement());
            case StatementDef.IfElse ifElse -> supported(ifElse.condition()) && supported(ifElse.statement())
                && supported(ifElse.elseStatement());
            case StatementDef.While loop -> supported(loop.expression()) && supported(loop.statement());
            case StatementDef.Switch aSwitch -> supportedSwitch(aSwitch);
            case StatementDef.Try aTry -> supported(aTry.statement())
                && aTry.catches().stream().allMatch(aCatch -> supported(aCatch.statement()))
                && (aTry.finallyStatement() == null || supported(aTry.finallyStatement()));
            case StatementDef.Synchronized synchronizedStatement -> supported(synchronizedStatement.monitor())
                && supported(synchronizedStatement.statement());
            default -> false;
        };
    }

    private static boolean supportedSwitch(StatementDef.Switch aSwitch) {
        if (!supported(aSwitch.expression())
            || (!aSwitch.expression().type().equals(TypeDef.Primitive.INT)
            && !aSwitch.expression().type().equals(TypeDef.STRING))) {
            return false;
        }
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (var entry : aSwitch.cases().entrySet()) {
            Object value = entry.getKey().value();
            int key;
            if (value instanceof Integer integer) {
                key = integer;
            } else if (value instanceof String string) {
                key = string.hashCode();
            } else {
                return false;
            }
            if (!keys.add(key) || !supported(entry.getValue())) {
                return false;
            }
        }
        return aSwitch.defaultCase() == null || supported(aSwitch.defaultCase());
    }

    static boolean supported(ExpressionDef expression) {
        return switch (expression) {
            case ExpressionDef.Constant ignored -> true;
            case VariableDef.This ignored -> true;
            case VariableDef.MethodParameter ignored -> true;
            case VariableDef.Local ignored -> true;
            case VariableDef.StaticField ignored -> true;
            case VariableDef.ExceptionVar ignored -> true;
            case VariableDef.Field field -> supported(field.instance());
            case VariableDef.Super ignored -> false;
            case ExpressionDef.Cast cast -> supported(cast.expressionDef());
            case ExpressionDef.NewInstance newInstance -> newInstance.values().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.InvokeInstanceMethod invoke -> supported(invoke.instance())
                && invoke.values().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.InvokeStaticMethod invoke -> invoke.values().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.ComparisonOperation comparison -> supported(comparison.left()) && supported(comparison.right());
            case ExpressionDef.MathBinaryOperation math -> supported(math.left()) && supported(math.right());
            case ExpressionDef.StringConcatenation concat -> supported(concat.left()) && supported(concat.right());
            case ExpressionDef.MathUnaryOperation math -> supported(math.expression());
            case ExpressionDef.IsNull isNull -> supported(isNull.expression());
            case ExpressionDef.IsNotNull isNotNull -> supported(isNotNull.expression());
            case ExpressionDef.IsTrue isTrue -> supported(isTrue.expression());
            case ExpressionDef.IsFalse isFalse -> supported(isFalse.expression());
            case ExpressionDef.And and -> supported(and.left()) && supported(and.right());
            case ExpressionDef.Or or -> supported(or.left()) && supported(or.right());
            case ExpressionDef.IfElse ifElse -> supported(ifElse.condition()) && supported(ifElse.ifExpression())
                && supported(ifElse.elseExpression());
            case ExpressionDef.NewArrayOfSize ignored -> true;
            case ExpressionDef.NewArrayInitialized array -> array.expressions().stream().allMatch(JdkMethodSupport::supported);
            case ExpressionDef.ArrayElement array -> supported(array.expression()) && supported(array.indexExpression());
            case ExpressionDef.GetPropertyValue property -> supported(property.instance());
            case ExpressionDef.InvokeGetClassMethod getClass -> supported(getClass.instance());
            case ExpressionDef.InvokeHashCodeMethod hashCode -> supported(hashCode.instance());
            case ExpressionDef.EqualsStructurally equals -> supported(equals.instance()) && supported(equals.other());
            case ExpressionDef.NotEqualsStructurally notEquals -> supported(notEquals.instance()) && supported(notEquals.other());
            case ExpressionDef.EqualsReferentially equals -> supported(equals.instance()) && supported(equals.other());
            case ExpressionDef.NotEqualsReferentially notEquals -> supported(notEquals.instance()) && supported(notEquals.other());
            case ExpressionDef.InstanceOf instanceOf -> supported(instanceOf.expression());
            case ExpressionDef.Lambda lambda -> supported(lambda.implementation());
            case MethodReferenceExpression methodReference -> methodReference.instance() == null
                || supported(methodReference.instance());
            case ExpressionDef.Switch aSwitch -> supportedSwitch(aSwitch);
            case ExpressionDef.SwitchYieldCase ignored -> false;
            default -> false;
        };
    }

    private static boolean supportedSwitch(ExpressionDef.Switch aSwitch) {
        if (!supported(aSwitch.expression())
            || (!aSwitch.expression().type().equals(TypeDef.Primitive.INT)
            && !aSwitch.expression().type().equals(TypeDef.STRING))) {
            return false;
        }
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        for (var entry : aSwitch.cases().entrySet()) {
            Object value = entry.getKey().value();
            int key;
            if (value instanceof Integer integer) {
                key = integer;
            } else if (value instanceof String string) {
                key = string.hashCode();
            } else {
                return false;
            }
            if (!keys.add(key) || !supported(entry.getValue())) {
                return false;
            }
        }
        return aSwitch.defaultCase() == null || supported(aSwitch.defaultCase());
    }
}
