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
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Which casts of the model the source writes, and what an expression is once the casts it drops are seen through: a
 * {@code null}, a lambda or a method reference, a boxed constant.
 *
 * @since 2.3
 */
@Internal
final class JavaCasts {

    private JavaCasts() {
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

    /**
     * Whether the source drops a cast of the model: one of {@code null}, one to the type the value has, and one to
     * {@code Object} where a reference is all the context needs.
     *
     * @param cast        The cast
     * @param operand     Its operand, of nested casts the last one
     * @param castContext Where the cast is written
     * @return true if only the operand is written
     */
    static boolean dropsCast(ExpressionDef.Cast cast, ExpressionDef operand, CastContext castContext) {
        if (writesCastToOwnType(cast, operand)) {
            return false;
        }
        return isNullLiteral(operand) || cast.type().equals(operand.type())
            || canEliminateCastToObject(cast, operand, castContext);
    }

    /**
     * Whether a cast to the type the model gives the value is written: Java types the value otherwise - an array of a
     * variable, created as one of its erasure, as that. An operation of bytes, which Java types as an int, is written
     * narrowed to the byte the model types it as (see {@link JavaTypes#isNarrowedOperation}).
     *
     * @param cast    The cast
     * @param operand Its operand, of nested casts the last one
     * @return true if the cast is written
     */
    static boolean writesCastToOwnType(ExpressionDef.Cast cast, ExpressionDef operand) {
        return (operand instanceof ExpressionDef.NewArrayOfSize || operand instanceof ExpressionDef.NewArrayInitialized)
            && operand.type() instanceof TypeDef.Array array && TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable;
    }

    /**
     * The node of an expression the source writes: the operand of a cast it drops, see {@link #dropsCast}.
     *
     * @param expression  The expression
     * @param castContext Where it is written
     * @return The node written
     */
    static ExpressionDef writtenNode(ExpressionDef expression, CastContext castContext) {
        ExpressionDef written = expression;
        while (written instanceof ExpressionDef.Cast cast) {
            ExpressionDef operand = collapseNestedCasts(cast.expressionDef());
            if (!dropsCast(cast, operand, castContext)) {
                return cast;
            }
            written = operand;
        }
        return written;
    }

    private static boolean canEliminateCastToObject(ExpressionDef.Cast castExpressionDef,
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

    private static TypeDef objectCastOperandType(ExpressionDef expressionDef) {
        // Of the casts to Object alone: a primitive the model casts to its box, `(Integer) i`, is compared as the box
        ExpressionDef operand = expressionDef;
        while (operand instanceof ExpressionDef.Cast cast && cast.type().equals(TypeDef.OBJECT)) {
            operand = cast.expressionDef();
        }
        return operand.type();
    }

    static boolean isNullLiteral(ExpressionDef expressionDef) {
        return unwrapCasts(expressionDef) instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    /**
     * Whether a value is a lambda or a method reference as the source writes it: a cast to the type it already has is
     * not written, and gives it no type where the context does not.
     *
     * @param value The value
     * @return true if it is written as a lambda or a method reference
     */
    static boolean isFunctional(ExpressionDef value) {
        ExpressionDef written = value;
        while (written instanceof ExpressionDef.Cast cast && cast.type().equals(cast.expressionDef().type())) {
            written = cast.expressionDef();
        }
        return written instanceof ExpressionDef.Lambda || written instanceof MethodReferenceExpression;
    }

    /**
     * Whether a conditional or a switch yields a lambda or a reference, which takes no type from a context that is
     * not of its functional type.
     *
     * @param value The value
     * @return true if a result is functional
     */
    static boolean hasFunctionalBranch(ExpressionDef value) {
        return switch (value) {
            case ExpressionDef.IfElse condition -> isFunctional(condition.ifExpression()) || isFunctional(condition.elseExpression())
                || hasFunctionalBranch(condition.ifExpression()) || hasFunctionalBranch(condition.elseExpression());
            case ExpressionDef.Switch aSwitch -> aSwitch.cases().values().stream().anyMatch(v -> isFunctional(v) || hasFunctionalBranch(v))
                || aSwitch.defaultCase() != null && (isFunctional(aSwitch.defaultCase()) || hasFunctionalBranch(aSwitch.defaultCase()));
            default -> false;
        };
    }

    /**
     * Whether an operand of `==` or `!=` is a constant of a box compared with a reference, which the bytecode compares
     * as one: {@code Integer.valueOf(1000) == Integer.valueOf(1000)} is false, {@code 1000 == 1000} a constant true.
     *
     * @param operand The operand
     * @param other   The other operand
     * @return true if the operand is written boxed
     */
    static boolean comparesBoxed(ExpressionDef operand, ExpressionDef other) {
        return JavaLiterals.isBoxedValue(writtenNode(operand, CastContext.OBJECT_REFERENCE)) && !other.type().isPrimitive();
    }

    /**
     * The operands a structural equality of a primitive compares, as the bytecode converts them: the other operand to
     * the type of the primitive, the left one first - {@code 2 == (int) 2.5}.
     *
     * @param left  The left operand
     * @param right The right operand
     * @return The operands, or {@code null} where neither is a primitive and {@code equals} compares them
     */
    @Nullable
    static List<ExpressionDef> structuralOperands(ExpressionDef left, ExpressionDef right) {
        if (left.type().isPrimitive()) {
            return List.of(left, right.cast(left.type()));
        }
        if (right.type().isPrimitive()) {
            return List.of(left.cast(right.type()), right);
        }
        return null;
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
