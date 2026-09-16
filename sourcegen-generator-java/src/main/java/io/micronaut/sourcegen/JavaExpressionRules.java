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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

/**
 * What an expression reads as in Java source: where it needs parentheses of its own, where a cast is implicit in
 * bytecode but required in source, and how an operator is spelled.
 *
 * @since 2.2
 */
@Internal
final class JavaExpressionRules {

    private JavaExpressionRules() {
    }

    static boolean isNullLiteral(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            expressionDef = castExpressionDef.expressionDef();
        }
        return expressionDef instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    static String getMathOp(ExpressionDef.MathBinaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case ADDITION -> " + ";
            case SUBTRACTION -> " - ";
            case MULTIPLICATION -> " * ";
            case DIVISION -> " / ";
            case MODULUS -> " % ";
            case BITWISE_AND -> " & ";
            case BITWISE_OR -> " | ";
            case BITWISE_XOR -> " ^ ";
            case BITWISE_LEFT_SHIFT -> " << ";
            case BITWISE_RIGHT_SHIFT -> " >> ";
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> " >>> ";
        };
    }

    static String getMathOp(ExpressionDef.MathUnaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case NEGATE -> "-";
        };
    }

    static boolean requiresMathParentheses(ExpressionDef.MathBinaryOperation parent,
                                                  ExpressionDef.MathBinaryOperation child,
                                                  boolean rightOperand) {
        int parentPrecedence = mathPrecedence(parent.opType());
        int childPrecedence = mathPrecedence(child.opType());
        return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence);
    }

    static int mathPrecedence(ExpressionDef.MathBinaryOperation.OpType opType) {
        return switch (opType) {
            case MULTIPLICATION, DIVISION, MODULUS -> 6;
            case ADDITION, SUBTRACTION -> 5;
            case BITWISE_LEFT_SHIFT, BITWISE_RIGHT_SHIFT, BITWISE_UNSIGNED_RIGHT_SHIFT -> 4;
            case BITWISE_AND -> 3;
            case BITWISE_XOR -> 2;
            case BITWISE_OR -> 1;
        };
    }

    static boolean requiresParentheses(ExpressionDef expressionDef) {
        expressionDef = unwrapCasts(expressionDef);
        if (expressionDef instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
            TypeDef type = invokeHashCodeMethod.instance().type();
            return !type.isPrimitive() && !type.isArray();
        }
        return !(expressionDef instanceof StatementDef
            || expressionDef instanceof VariableDef
            || expressionDef instanceof ExpressionDef.And
            || expressionDef instanceof ExpressionDef.Constant
            || expressionDef instanceof ExpressionDef.GetPropertyValue
            || expressionDef instanceof ExpressionDef.InvokeGetClassMethod
            || expressionDef instanceof ExpressionDef.ArrayElement
            || expressionDef instanceof ExpressionDef.NewArrayOfSize
            || expressionDef instanceof ExpressionDef.NewArrayInitialized
            || expressionDef instanceof ExpressionDef.NewInstance
            || expressionDef instanceof ExpressionDef.Switch);
    }

    static ExpressionDef unwrapCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    static ExpressionDef collapseNestedCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            if (cast.type().isPrimitive()) {
                TypeDef previousCastType = cast.expressionDef().type();
                if (!previousCastType.equals(TypeDef.OBJECT)) {
                    break;
                }
            }
            // Only keep the last cast
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    static boolean requiresCastOperandParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch
            || isNegativeNumericConstant(expressionDef);
    }

    static boolean isNegativeNumericConstant(ExpressionDef expressionDef) {
        // `(Object) -1` would parse as a subtraction of the variable `Object`
        return expressionDef instanceof ExpressionDef.Constant constant
            && constant.value() instanceof Number number
            && number.toString().startsWith("-");
    }

    static boolean requiresImplicitInvocationCast(TypeDef paramType, TypeDef valueType) {
        if (valueType.equals(TypeDef.OBJECT)) {
            return !paramType.equals(TypeDef.OBJECT);
        }
        // A reflective signature is raw: passing a parameterized value through the raw type makes the call
        // unchecked, where javac would otherwise reject mismatched type arguments
        return paramType instanceof ClassTypeDef paramClass
            && !(paramType instanceof ClassTypeDef.Parameterized)
            && valueType instanceof ClassTypeDef.Parameterized parameterized
            && parameterized.rawType().getName().equals(paramClass.getName());
    }

    static boolean requiresMethodCallTargetParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Cast
            || expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch;
    }

    static boolean canEliminateCastToObject(ExpressionDef.Cast castExpressionDef,
                                                    ExpressionDef expressionDef,
                                                    CastContext castContext) {
        if (!castExpressionDef.type().equals(TypeDef.OBJECT)) {
            return false;
        }
        return switch (castContext) {
            case DEFAULT -> false;
            case OBJECT_REFERENCE -> !expressionDef.type().isPrimitive();
            case PRIMITIVE_EQUALITY -> true;
        };
    }

    static boolean arePrimitiveReferenceEqualityOperands(ExpressionDef left, ExpressionDef right) {
        return objectCastOperandType(left).isPrimitive() && objectCastOperandType(right).isPrimitive();
    }

    static TypeDef objectCastOperandType(ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.Cast cast && cast.type().equals(TypeDef.OBJECT)) {
            return collapseNestedCasts(cast.expressionDef()).type();
        }
        return expressionDef.type();
    }

    static boolean isOrCondition(ExpressionDef.ConditionExpressionDef expressionDef) {
        return switch (expressionDef) {
            case ExpressionDef.Or _ -> true;
            case ExpressionDef.IsTrue isTrue when unwrapCasts(isTrue.expression()) instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef ->
                isOrCondition(conditionExpressionDef);
            case null, default -> false;
        };
    }

    static String getOpType(ExpressionDef.ComparisonOperation comparisonOperation) {
        return switch (comparisonOperation.opType()) {
            case EQUAL_TO -> " == ";
            case NOT_EQUAL_TO -> " != ";
            case GREATER_THAN -> " > ";
            case LESS_THAN -> " < ";
            case GREATER_THAN_OR_EQUAL -> " >= ";
            case LESS_THAN_OR_EQUAL -> " <= ";
        };
    }

    /**
     * Where an expression is rendered, which decides whether a cast to {@code Object} carries meaning.
     */
    enum CastContext {
        DEFAULT,
        OBJECT_REFERENCE,
        PRIMITIVE_EQUALITY
    }
}
