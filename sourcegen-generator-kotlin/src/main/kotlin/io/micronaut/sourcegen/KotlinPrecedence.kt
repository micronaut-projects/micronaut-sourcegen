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
package io.micronaut.sourcegen

import com.squareup.kotlinpoet.*
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*

/*
 * Where an operand needs parentheses, by the precedence of Kotlin's operators.
 */

internal fun requiresMathParentheses(
    parent: MathBinaryOperation,
    child: MathBinaryOperation,
    rightOperand: Boolean
): Boolean {
    val parentPrecedence = mathPrecedence(parent.opType)
    val childPrecedence = mathPrecedence(child.opType)
    return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence)
}

/**
 * The Kotlin precedence of a binary operation. The bitwise operations are named infix functions,
 * which all share one precedence level below the arithmetic operators.
 */
private fun mathPrecedence(opType: MathBinaryOperation.OpType): Int {
    return when (opType) {
        MathBinaryOperation.OpType.MULTIPLICATION,
        MathBinaryOperation.OpType.DIVISION,
        MathBinaryOperation.OpType.MODULUS -> 3

        MathBinaryOperation.OpType.ADDITION,
        MathBinaryOperation.OpType.SUBTRACTION -> 2

        else -> 1
    }
}

internal fun requiresParentheses(expressionDef: ExpressionDef): Boolean {
    val expression = unwrapCasts(expressionDef)
    if (expression is InvokeHashCodeMethod) {
        val type = expression.instance().type()
        return !type.isPrimitive && !type.isArray
    }
    return !(expression is StatementDef
        || expression is VariableDef
        || expression is And
        || expression is Constant
        || expression is GetPropertyValue
        || expression is InvokeGetClassMethod
        || expression is ArrayElement
        || expression is NewArrayOfSize
        || expression is NewArrayInitialized
        || expression is NewInstance
        || expression is Switch)
}

internal fun requiresMethodCallTargetParentheses(expressionDef: ExpressionDef): Boolean {
    return expressionDef is Cast
        || expressionDef is IfElse
        || expressionDef is StringConcatenation
        || expressionDef is Switch
        || expressionDef is MathBinaryOperation
        || expressionDef is MathUnaryOperation
        || expressionDef is ConditionExpressionDef
        // `-1L.hashCode()` is the negation of the hash code
        || isNegativeNumericConstant(expressionDef)
}

/**
 * A conversion is a member call, and both `.` and a prefix minus bind tighter than the
 * binary operators, so anything looser than a postfix expression has to be wrapped.
 */
internal fun requiresConversionTargetParentheses(expressionDef: ExpressionDef): Boolean {
    return requiresMethodCallTargetParentheses(expressionDef)
        || isNegativeNumericConstant(expressionDef)
}

internal fun isNegativeNumericConstant(expressionDef: ExpressionDef): Boolean {
    return expressionDef is Constant
        && expressionDef.value is Number
        && expressionDef.value.toString().startsWith("-")
        // The smallest values are written as the constants of their type
        && expressionDef.value != Long.MIN_VALUE && expressionDef.value != Int.MIN_VALUE
}

internal fun requiresCastOperandParentheses(expressionDef: ExpressionDef): Boolean {
    return expressionDef is ConditionExpressionDef
        || expressionDef is IfElse
        || expressionDef is MathBinaryOperation
        || expressionDef is StringConcatenation
        || expressionDef is Switch
}

internal fun addParentheses(rendered: CodeBlock): CodeBlock {
    return CodeBlock.builder().add("(").add(rendered).add(")").build()
}

internal fun getMathOp(opType: MathBinaryOperation.OpType): String {
    return when (opType) {
        MathBinaryOperation.OpType.ADDITION -> " + "
        MathBinaryOperation.OpType.SUBTRACTION -> " - "
        MathBinaryOperation.OpType.MULTIPLICATION -> " * "
        MathBinaryOperation.OpType.DIVISION -> " / "
        MathBinaryOperation.OpType.MODULUS -> " % "
        // Kotlin spells the bitwise operations as infix functions
        MathBinaryOperation.OpType.BITWISE_AND -> " and "
        MathBinaryOperation.OpType.BITWISE_OR -> " or "
        MathBinaryOperation.OpType.BITWISE_XOR -> " xor "
        MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT -> " shl "
        MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT -> " shr "
        MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT -> " ushr "
    }
}

internal fun getMathOp(opType: MathUnaryOperation.OpType): String {
    return when (opType) {
        MathUnaryOperation.OpType.NEGATE -> "-"
    }
}

internal fun getOpType(opType: ComparisonOperation.OpType): String {
    return when (opType) {
        ComparisonOperation.OpType.EQUAL_TO -> " == "
        ComparisonOperation.OpType.NOT_EQUAL_TO -> " != "
        ComparisonOperation.OpType.GREATER_THAN -> " > "
        ComparisonOperation.OpType.LESS_THAN -> " < "
        ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL -> " >= "
        ComparisonOperation.OpType.LESS_THAN_OR_EQUAL -> " <= "
    }
}
