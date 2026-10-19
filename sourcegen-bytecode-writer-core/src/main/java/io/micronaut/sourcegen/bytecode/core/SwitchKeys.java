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
 * The switches both bytecode writers write as a switch on an {@code int}, as javac writes them: a selector of type
 * {@code int}, {@code char}, {@code short} or {@code byte} is widened to an {@code int}, and one of their wrappers is
 * unboxed first, which throws {@link NullPointerException} for {@code null}.
 *
 * @since 2.3
 */
@Internal
public final class SwitchKeys {

    private SwitchKeys() {
    }

    /**
     * Whether a switch on a selector of a type is a switch on the {@code int} it widens or unboxes to.
     *
     * @param selector The type of the selector
     * @return true for an {@code int}, a {@code char}, a {@code short}, a {@code byte} and their wrappers
     */
    public static boolean switchesOnInt(TypeDef selector) {
        TypeDef type = TypeDef.Primitive.unboxIfPossible(selector);
        return type.equals(TypeDef.Primitive.INT) || type.equals(TypeDef.Primitive.CHAR)
            || type.equals(TypeDef.Primitive.SHORT) || type.equals(TypeDef.Primitive.BYTE);
    }

    /**
     * The {@code int} a case constant of a switch on an {@code int} matches.
     *
     * @param constant The constant of the case
     * @return The key, or {@code null} for a constant that is no {@code int}, {@code char}, {@code short} or {@code byte}
     */
    public static @Nullable Integer key(ExpressionDef.Constant constant) {
        return switch (constant.value()) {
            case Integer value -> value;
            case Character value -> (int) value;
            case Short value -> (int) value;
            case Byte value -> (int) value;
            case null, default -> null;
        };
    }
}
