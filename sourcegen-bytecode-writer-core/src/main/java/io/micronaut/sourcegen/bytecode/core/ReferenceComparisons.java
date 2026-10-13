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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

/**
 * The referential comparisons ({@code ==} and {@code !=}) of two primitives, which both bytecode writers compare as
 * javac compares them: the values, promoted to a common type (JLS 5.6, binary numeric promotion) - a {@code long}
 * compared with a {@code float} is compared as a {@code float}, and a {@code char} with a {@code byte} as an
 * {@code int}. The model boxes the operands of an {@link ExpressionDef.EqualsReferentially} to {@code Object}; a
 * primitive it boxed is compared as the primitive, as the Java source writes it.
 *
 * @since 2.3
 */
@Internal
public final class ReferenceComparisons {

    private ReferenceComparisons() {
    }

    /**
     * The comparison of the values of two primitive operands of a referential comparison.
     *
     * @param left  The left operand
     * @param right The right operand
     * @return The operands and their common type, or {@code null} where an operand is a reference, or where the two are
     * a {@code boolean} and a number, which javac does not compare
     */
    public static @Nullable PrimitiveComparison primitiveComparison(ExpressionDef left, ExpressionDef right) {
        ExpressionDef leftValue = unboxed(left);
        ExpressionDef rightValue = unboxed(right);
        if (!(leftValue.type() instanceof TypeDef.Primitive leftType) || !(rightValue.type() instanceof TypeDef.Primitive rightType)) {
            return null;
        }
        TypeDef.Primitive common = promoted(leftType, rightType);
        return common == null ? null : new PrimitiveComparison(leftValue, rightValue, common);
    }

    /**
     * An operand of a referential comparison as javac compares it: a primitive the model cast to {@code Object} is the
     * primitive.
     */
    private static ExpressionDef unboxed(ExpressionDef operand) {
        if (operand instanceof ExpressionDef.Cast cast && TypeDef.OBJECT.equals(cast.type())
            && cast.expressionDef().type() instanceof TypeDef.Primitive primitive && !primitive.equals(TypeDef.VOID)) {
            return cast.expressionDef();
        }
        return operand;
    }

    /**
     * The type two primitives are compared as: {@code boolean} for two booleans, else binary numeric promotion.
     */
    private static TypeDef.@Nullable Primitive promoted(TypeDef.Primitive left, TypeDef.Primitive right) {
        boolean leftBoolean = left.equals(TypeDef.Primitive.BOOLEAN);
        boolean rightBoolean = right.equals(TypeDef.Primitive.BOOLEAN);
        if (leftBoolean || rightBoolean) {
            return leftBoolean && rightBoolean ? TypeDef.Primitive.BOOLEAN : null;
        }
        if (left.equals(TypeDef.VOID) || right.equals(TypeDef.VOID)) {
            return null;
        }
        if (left.equals(TypeDef.Primitive.DOUBLE) || right.equals(TypeDef.Primitive.DOUBLE)) {
            return TypeDef.Primitive.DOUBLE;
        }
        if (left.equals(TypeDef.Primitive.FLOAT) || right.equals(TypeDef.Primitive.FLOAT)) {
            return TypeDef.Primitive.FLOAT;
        }
        if (left.equals(TypeDef.Primitive.LONG) || right.equals(TypeDef.Primitive.LONG)) {
            return TypeDef.Primitive.LONG;
        }
        return TypeDef.Primitive.INT;
    }

    /**
     * Two primitive operands compared as their values: each one is written, then converted to the common type.
     *
     * @param left  The left operand, a primitive
     * @param right The right operand, a primitive
     * @param type  The type both are converted to and compared as
     */
    public record PrimitiveComparison(ExpressionDef left, ExpressionDef right, TypeDef.Primitive type) {
    }
}
