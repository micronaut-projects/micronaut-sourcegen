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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bounds of the type variables an invoked method declares, as they read where the method is called: a
 * variable of the caller can have the same name as one of the method, which is out of scope there.
 *
 * <p>A variable of the method is its bounds - following a bound that is another variable of the method - with the
 * receiver's type arguments for the variables of its class. A bound that names a variable of the method itself,
 * `Comparable<T>`, is raw for Java, and has the variable as `Object` for Kotlin, which has no raw types.</p>
 *
 * @param variables         The type variables the invoked method declares
 * @param receiverArguments The type arguments the receiver binds the variables of the method's class with
 * @param raw               Whether a bound naming a variable of the method is written raw, rather than with
 *                          {@code Object} for the variable
 * @since 2.2
 */
@Internal
public record CalleeBounds(List<TypeDef.TypeVariable> variables,
                           Map<String, TypeDef> receiverArguments,
                           boolean raw) {

    private static final int MAX_DEPTH = 8;

    /**
     * Whether a type names a variable of the invoked method.
     *
     * @param type The type
     * @return true where it does
     */
    public boolean names(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            return variable(variable.name()) != null;
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.typeArguments().stream().anyMatch(this::names);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return names(array.componentType());
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().stream().anyMatch(this::names)
                || wildcard.lowerBounds().stream().anyMatch(this::names);
        }
        return false;
    }

    /**
     * A type without the variables of the invoked method: a variable is its bounds, an array one of the arrays of its
     * component's first bound, and a parameterization raw for Java, or with each variable its first bound for Kotlin.
     *
     * @param type The type
     * @return The types the type is, more than one for a variable of several bounds
     */
    public List<TypeDef> of(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        TypeDef.TypeVariable declared = unwrapped instanceof TypeDef.TypeVariable variable ? variable(variable.name()) : null;
        if (declared != null) {
            List<TypeDef> bounds = new ArrayList<>();
            collect(declared, bounds, new HashSet<>(), 0);
            return bounds.isEmpty() ? List.of(TypeDef.OBJECT) : bounds;
        }
        if (unwrapped instanceof TypeDef.Array array && names(array.componentType())) {
            return List.of(TypeDef.array(of(array.componentType()).get(0), array.dimensions()));
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized && names(parameterized)) {
            if (raw) {
                return List.of(parameterized.rawType());
            }
            Map<String, TypeDef> firstBounds = new HashMap<>();
            variables.forEach(own -> firstBounds.put(own.name(), of(own).get(0)));
            return List.of(TypeHierarchy.substituted(parameterized, firstBounds));
        }
        return List.of(type);
    }

    private void collect(TypeDef.TypeVariable variable, List<TypeDef> bounds, Set<String> visited, int depth) {
        if (!visited.add(variable.name()) || depth > MAX_DEPTH) {
            return;
        }
        for (TypeDef bound : variable.bounds()) {
            TypeDef unwrapped = TypeHierarchy.unwrap(bound);
            TypeDef.TypeVariable declared = unwrapped instanceof TypeDef.TypeVariable named ? variable(named.name()) : null;
            if (declared != null) {
                // A bound that is another variable of the method is that variable's bounds
                collect(declared, bounds, visited, depth + 1);
            } else if (names(unwrapped)) {
                // `Comparable<T>` names a variable of the method, out of scope where it is called
                bounds.add(withoutOwnVariables(unwrapped));
            } else if (!TypeDef.OBJECT.equals(unwrapped)) {
                // A variable of the class is the type argument the receiver binds it to
                bounds.add(TypeHierarchy.containsVariableOtherThan(bound, Set.of())
                    ? TypeHierarchy.unwrap(TypeHierarchy.substituted(bound, receiverArguments)) : bound);
            }
        }
    }

    private TypeDef withoutOwnVariables(TypeDef bound) {
        if (raw) {
            return bound instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : TypeDef.OBJECT;
        }
        Map<String, TypeDef> asObject = new HashMap<>();
        variables.forEach(declared -> asObject.put(declared.name(), TypeDef.OBJECT));
        return TypeHierarchy.substituted(bound, asObject);
    }

    private TypeDef.@Nullable TypeVariable variable(String name) {
        return variables.stream().filter(declared -> declared.name().equals(name)).findFirst().orElse(null);
    }
}
