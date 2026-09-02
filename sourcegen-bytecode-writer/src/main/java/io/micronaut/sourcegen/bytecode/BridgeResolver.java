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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Compatibility facade for the backend-neutral bridge resolver.
 *
 * @since 2.2
 */
@Internal
final class BridgeResolver {

    private BridgeResolver() {
    }

    static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return io.micronaut.sourcegen.bytecode.core.BridgeResolver.resolve(objectDef, methodDef).stream()
            .map(bridge -> new BridgeMethod(bridge.parameterTypes(), bridge.returnType()))
            .toList();
    }

    record BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
    }
}
