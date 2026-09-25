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

/**
 * How tightly the Java source of an expression binds, from a lambda, the loosest, to a primary. It is decided on the
 * node that is actually written: a cast the source drops leaves its operand.
 *
 * @since 2.3
 */
@Internal
final class JavaPrecedence {

    static final int LAMBDA = 0;
    static final int CONDITIONAL = 1;
    static final int OR = 2;
    static final int AND = 3;
    static final int BITWISE_OR = 4;
    static final int BITWISE_XOR = 5;
    static final int BITWISE_AND = 6;
    static final int EQUALITY = 7;
    static final int RELATIONAL = 8;
    static final int SHIFT = 9;
    static final int ADDITIVE = 10;
    static final int MULTIPLICATIVE = 11;
    // A cast, a prefix operator and a switch expression
    static final int UNARY = 12;
    // A literal, a name, a call, a field or an array access, an instance creation
    static final int POSTFIX = 13;

    private JavaPrecedence() {
    }

    /**
     * @param written The expression as it is written, see {@link JavaCasts#writtenNode}
     * @return The precedence of its source
     */
    static int of(ExpressionDef written) {
        return switch (written) {
            case ExpressionDef.Constant constant -> JavaLiterals.precedence(constant);
            // An operation the source narrows is written as a cast: `(short) (a - b)`
            case ExpressionDef.Cast _, ExpressionDef.MathUnaryOperation _, ExpressionDef.Switch _,
                 ExpressionDef.IsFalse _ -> UNARY;
            case ExpressionDef.MathBinaryOperation math when JavaTypes.isNarrowedOperation(math) -> UNARY;
            case ExpressionDef.MathBinaryOperation math -> switch (math.opType()) {
                case MULTIPLICATION, DIVISION, MODULUS -> MULTIPLICATIVE;
                case ADDITION, SUBTRACTION -> ADDITIVE;
                case BITWISE_LEFT_SHIFT, BITWISE_RIGHT_SHIFT, BITWISE_UNSIGNED_RIGHT_SHIFT -> SHIFT;
                case BITWISE_AND -> BITWISE_AND;
                case BITWISE_XOR -> BITWISE_XOR;
                case BITWISE_OR -> BITWISE_OR;
            };
            case ExpressionDef.StringConcatenation _ -> ADDITIVE;
            case ExpressionDef.IfElse _ -> CONDITIONAL;
            case ExpressionDef.Lambda _ -> LAMBDA;
            case ExpressionDef.And _ -> AND;
            case ExpressionDef.Or _ -> OR;
            case ExpressionDef.ComparisonOperation comparison ->
                comparison.opType() == ExpressionDef.ComparisonOperation.OpType.EQUAL_TO
                    || comparison.opType() == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO ? EQUALITY : RELATIONAL;
            case ExpressionDef.InstanceOf _ -> RELATIONAL;
            case ExpressionDef.IsNull _, ExpressionDef.IsNotNull _, ExpressionDef.EqualsReferentially _,
                 ExpressionDef.NotEqualsReferentially _ -> EQUALITY;
            // Of primitives a comparison, else a call in parentheses
            case ExpressionDef.EqualsStructurally equals ->
                equals.instance().type().isPrimitive() || equals.other().type().isPrimitive() ? EQUALITY : POSTFIX;
            case ExpressionDef.NotEqualsStructurally notEquals ->
                notEquals.instance().type().isPrimitive() || notEquals.other().type().isPrimitive() ? EQUALITY : POSTFIX;
            case ExpressionDef.IsTrue isTrue -> JavaCasts.unwrapCasts(isTrue.expression()) instanceof ExpressionDef.ConditionExpressionDef condition
                ? of(condition) : POSTFIX;
            // Of a reference, a conditional that checks it for null
            case ExpressionDef.InvokeHashCodeMethod hashCode -> {
                TypeDef type = hashCode.instance().type();
                yield type.isPrimitive() || type.isArray() ? POSTFIX : CONDITIONAL;
            }
            default -> POSTFIX;
        };
    }

    /**
     * Whether the source of an expression starts with a minus, which a prefix minus cannot be written before: {@code --1}
     * is a decrement.
     *
     * @param written The expression as it is written
     * @return true if it starts with a minus
     */
    static boolean startsWithMinus(ExpressionDef written) {
        return written instanceof ExpressionDef.MathUnaryOperation && !JavaTypes.isNarrowedOperation(written)
            || written instanceof ExpressionDef.Constant constant && JavaLiterals.isNegativeLiteral(constant);
    }

    /**
     * Whether the source of an expression is an array creation, which an array access cannot follow: {@code new
     * Object[2][1]} creates an array of two dimensions.
     *
     * @param written The expression as it is written
     * @return true if it is an array creation
     */
    static boolean isArrayCreation(ExpressionDef written) {
        return written instanceof ExpressionDef.NewArrayOfSize || written instanceof ExpressionDef.NewArrayInitialized
            || written instanceof ExpressionDef.Constant constant && constant.value() != null && constant.value().getClass().isArray();
    }

    /**
     * Whether a binary operation needs parentheses as the operand of another: one that binds less tightly, or as
     * tightly on the right - `a - (b - c)`.
     *
     * @param parent       The operation
     * @param child        Its operand
     * @param rightOperand Whether the operand is the right one
     * @return true if the operand is put in parentheses
     */
    static boolean requiresMathParentheses(ExpressionDef.MathBinaryOperation parent,
                                           ExpressionDef.MathBinaryOperation child,
                                           boolean rightOperand) {
        int parentPrecedence = of(parent);
        int childPrecedence = of(child);
        return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence);
    }

    /**
     * Whether an operand of an operator is put in parentheses whatever the operator: anything but a primary, a name,
     * a literal, an {@code &&} and a switch.
     *
     * @param expressionDef The operand, whose casts are seen through
     * @return true if it is put in parentheses
     */
    static boolean requiresParentheses(ExpressionDef expressionDef) {
        ExpressionDef operand = JavaCasts.unwrapCasts(expressionDef);
        if (operand instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
            TypeDef type = invokeHashCodeMethod.instance().type();
            return !type.isPrimitive() && !type.isArray();
        }
        return !(operand instanceof StatementDef
            || operand instanceof VariableDef
            || operand instanceof ExpressionDef.And
            || operand instanceof ExpressionDef.Constant
            || operand instanceof ExpressionDef.GetPropertyValue
            || operand instanceof ExpressionDef.InvokeGetClassMethod
            || operand instanceof ExpressionDef.ArrayElement
            || operand instanceof ExpressionDef.NewArrayOfSize
            || operand instanceof ExpressionDef.NewArrayInitialized
            || operand instanceof ExpressionDef.NewInstance
            || operand instanceof ExpressionDef.Switch);
    }

    /**
     * @param expressionDef The operand of a cast, of its casts the innermost
     * @return Whether it is put in parentheses: an operator, and a negative number, which would read as a subtraction
     * from the type - `(Object) -1`
     */
    static boolean requiresCastOperandParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch
            || expressionDef instanceof ExpressionDef.Constant constant
            && constant.value() instanceof Number number
            && number.toString().startsWith("-");
    }

    /**
     * @param expressionDef The receiver of a call, as the model has it
     * @return Whether it is put in parentheses: a cast and an operator
     */
    static boolean requiresReceiverParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Cast
            || expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch;
    }

    /**
     * Whether an operand of a concatenation is put in parentheses: where it binds no tighter than `+` does - or, on
     * the right, as tight: `a + (b + c)` is not `a + b + c` where `a` and `b` are numbers.
     *
     * @param written      The operand as it is written
     * @param rightOperand Whether it is the right operand
     * @return true if it is put in parentheses
     */
    static boolean requiresConcatOperandParentheses(ExpressionDef written, boolean rightOperand) {
        return written instanceof ExpressionDef.IfElse
            || written instanceof ExpressionDef.ConditionExpressionDef
            || written instanceof ExpressionDef.Switch
            || written instanceof ExpressionDef.Lambda
            || written instanceof ExpressionDef.MathBinaryOperation && !JavaTypes.isNarrowedOperation(written)
            || rightOperand && written instanceof ExpressionDef.StringConcatenation;
    }

    static String operator(ExpressionDef.MathBinaryOperation mathOperation) {
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

    static String operator(ExpressionDef.MathUnaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case NEGATE -> "-";
        };
    }

    static String operator(ExpressionDef.ComparisonOperation comparisonOperation) {
        return switch (comparisonOperation.opType()) {
            case EQUAL_TO -> " == ";
            case NOT_EQUAL_TO -> " != ";
            case GREATER_THAN -> " > ";
            case LESS_THAN -> " < ";
            case GREATER_THAN_OR_EQUAL -> " >= ";
            case LESS_THAN_OR_EQUAL -> " <= ";
        };
    }
}
