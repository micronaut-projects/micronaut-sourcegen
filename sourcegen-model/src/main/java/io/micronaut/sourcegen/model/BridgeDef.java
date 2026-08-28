/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.core.annotation.Experimental;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An erased signature that a method must additionally be callable under.
 *
 * <p>A bridge is required when a method overrides a generic method whose erasure differs, for example
 * {@code B self()} declared in {@code AbstractCatBuilder<C, B extends AbstractCatBuilder<C, B>>} overriding
 * {@code B self()} declared in {@code AbstractAnimalBuilder<C, B extends AbstractAnimalBuilder<C, B>>}: both
 * declarations erase to a different descriptor, so the JVM does not consider the second one implemented
 * without a bridge.
 *
 * <p>Bridges are declared on the method they delegate to, via
 * {@link MethodDef.MethodDefBuilder#addBridge(TypeDef)} and friends, and are only materialized by writers
 * that emit bytecode. Source generators ignore them because the Java, Kotlin and Groovy compilers
 * synthesize bridges themselves.
 *
 * @param returnType     The erased return type of the overridden method
 * @param parameterTypes The erased parameter types of the overridden method
 * @author Denis Stepanov
 * @since 2.2
 */
@Experimental
public record BridgeDef(TypeDef returnType, List<TypeDef> parameterTypes) {

    public BridgeDef {
        Objects.requireNonNull(returnType, "The bridge return type cannot be null");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "The bridge parameter types cannot be null"));
    }

    /**
     * Resolves type variables.
     *
     * @param resolveVariableFn The resolve variable function
     * @return the resolved bridge
     */
    public BridgeDef resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
        return new BridgeDef(
            returnType.resolveTypeVariables(resolveVariableFn),
            parameterTypes.stream().map(t -> t.resolveTypeVariables(resolveVariableFn)).toList()
        );
    }
}
