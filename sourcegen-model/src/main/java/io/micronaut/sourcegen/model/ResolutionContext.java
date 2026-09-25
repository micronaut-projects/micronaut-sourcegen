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
package io.micronaut.sourcegen.model;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The state of one resolution of a call: the wildcards it captures as fresh variables (JLS 5.1.10). Each resolution
 * creates its own and passes it along, so one started within another numbers its captures apart.
 *
 * @since 2.3
 */
final class ResolutionContext {

    /**
     * The name a captured wildcard is a fresh variable of: two captures are distinct variables.
     */
    private static final String CAPTURE = "capture#";

    /**
     * The lower bound of a captured `? super X`, by the capture's name.
     */
    private final Map<String, TypeDef> captureLowerBounds = new HashMap<>();
    /**
     * The number of wildcards captured.
     */
    private int captures;

    /**
     * Captures a wildcard.
     *
     * @param wildcard The wildcard
     * @return The fresh variable
     */
    TypeDef.TypeVariable capture(TypeDef.Wildcard wildcard) {
        return capture(wildcard, List.of());
    }

    /**
     * A wildcard captured as a fresh variable (JLS 5.1.10): of its upper bound and the bound its class declares the
     * parameter with - a `NumberBox<?>` of a `NumberBox<E extends Number>` holds Numbers. A value converts to it only
     * where it is `null`, the capture itself, or - for `? super X` - an X.
     *
     * @param wildcard      The wildcard
     * @param declaredBound The bounds the class declares the parameter with
     * @return The fresh variable
     */
    TypeDef.TypeVariable capture(TypeDef.Wildcard wildcard, List<TypeDef> declaredBound) {
        captures++;
        String name = CAPTURE + captures;
        List<TypeDef> upper = Stream.concat(wildcard.upperBounds().stream(), declaredBound.stream())
            .filter(bound -> !ResolutionTypes.isObject(bound)).distinct().toList();
        if (!wildcard.lowerBounds().isEmpty()) {
            captureLowerBounds.put(name, wildcard.lowerBounds().getFirst());
        }
        return TypeDef.variable(name, upper.isEmpty() ? List.of(TypeDef.OBJECT) : upper);
    }

    /**
     * The lower bound of a capture.
     *
     * @param capture The capture
     * @return The `X` of the `? super X` it captures, or {@code null}
     */
    @Nullable TypeDef lowerBound(TypeDef.TypeVariable capture) {
        return captureLowerBounds.get(capture.name());
    }

    /**
     * Whether a type is a captured wildcard.
     *
     * @param type The type
     * @return true where it is
     */
    static boolean isCapture(TypeDef type) {
        return TypeHierarchy.unwrap(type) instanceof TypeDef.TypeVariable variable && variable.name().startsWith(CAPTURE);
    }
}
