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

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The least upper bound of the types of values, as javac infers and orders it: the class they share, then their
 * interfaces of the greater rank, and of one rank by qualified name.
 *
 * @since 2.3
 */
@Internal
final class LeastUpperBounds {

    private LeastUpperBounds() {
    }

    /**
     * The least common superclass of values: `Base` of two siblings, the value where all share it.
     */
    static TypeDef leastUpperBound(List<TypeDef> values) {
        TypeDef first = TypeHierarchy.unwrap(values.getFirst());
        if (values.stream().allMatch(value -> ResolutionTypes.sameType(value, first))) {
            return first;
        }
        if (values.stream().allMatch(value -> TypeHierarchy.unwrap(value) instanceof TypeDef.Array array
            && (array.dimensions() > 1 || !TypeHierarchy.unwrap(array.componentType()).isPrimitive()))) {
            // Arrays of references are covariant: `String[][]` and `Integer[]` are arrays of what `String[]` and
            // `Integer` both are, and `int[][]` and `long[][]` arrays of what `int[]` and `long[]` are
            TypeDef component = leastUpperBound(values.stream().map(value -> {
                TypeDef.Array array = (TypeDef.Array) TypeHierarchy.unwrap(value);
                return array.dimensions() == 1 ? array.componentType() : TypeDef.array(array.componentType(), array.dimensions() - 1);
            }).toList());
            return TypeDef.array(component, 1);
        }
        // The intersection of the types in common, which a variable erases to the first of
        List<ClassTypeDef> minimal = commonSupertypes(values);
        return minimal.isEmpty() ? TypeDef.OBJECT : minimal.getFirst();
    }

    /**
     * The types the values have in common that are not a supertype of another of them, in the order javac lists the
     * intersection of them it infers, and erases it to the first of: the class, then the interfaces of the greater
     * rank - the longer path to Object - and of those of one rank the first by qualified name. An array is a
     * `Serializable` and a `Cloneable`.
     */
    static List<ClassTypeDef> commonSupertypes(List<TypeDef> values) {
        TypeDef first = TypeHierarchy.unwrap(values.getFirst());
        List<ClassTypeDef> supertypes = first instanceof TypeDef.Array ? List.of(ClassTypeDef.of(java.io.Serializable.class), ClassTypeDef.of(Cloneable.class))
            : first instanceof ClassTypeDef firstClass ? supertypesOf(TypeOperations.declaredClass(firstClass)) : List.of();
        List<ClassTypeDef> common = new ArrayList<>();
        for (ClassTypeDef candidate : supertypes) {
            String name = candidate.getName();
            if (!Object.class.getName().equals(name) && values.stream().allMatch(value -> subtypeOf(value, name))) {
                common.add(candidate);
            }
        }
        Map<String, Integer> ranks = new HashMap<>();
        return common.stream().filter(candidate -> common.stream().noneMatch(other -> other != candidate
                && !other.getName().equals(candidate.getName()) && ResolutionTypes.isSubtype(other, candidate.getName()) == OverloadResolution.Tri.YES))
            .sorted(Comparator.comparing(ClassTypeDef::isInterface)
                .thenComparing(candidate -> -rank(candidate, ranks))
                .thenComparing(LeastUpperBounds::qualifiedName))
            .toList();
    }

    /**
     * The length of the longest path from a type to Object through its supertypes: an interface extending none is of
     * rank one.
     */
    private static int rank(ClassTypeDef type, Map<String, Integer> ranks) {
        String name = type.getName();
        if (Object.class.getName().equals(name)) {
            return 0;
        }
        Integer known = ranks.get(name);
        if (known != null) {
            return known;
        }
        ranks.put(name, 1);
        ClassTypeDef superclass = superclassOf(type);
        int rank = superclass == null ? 0 : rank(TypeOperations.declaredClass(superclass), ranks);
        for (ClassTypeDef superinterface : interfacesOf(type)) {
            rank = Math.max(rank, rank(TypeOperations.declaredClass(superinterface), ranks));
        }
        ranks.put(name, rank + 1);
        return rank + 1;
    }

    private static String qualifiedName(ClassTypeDef type) {
        String canonical = type instanceof ClassTypeDef.JavaClass javaClass ? javaClass.type().getCanonicalName() : null;
        return canonical != null ? canonical : type.getName().replace('$', '.');
    }

    private static boolean subtypeOf(TypeDef value, String name) {
        TypeDef unwrapped = TypeHierarchy.unwrap(value);
        if (unwrapped instanceof TypeDef.Array) {
            return ResolutionTypes.ARRAY_SUPERTYPES.contains(name);
        }
        return unwrapped instanceof ClassTypeDef valueClass && ResolutionTypes.isSubtype(TypeOperations.declaredClass(valueClass), name) == OverloadResolution.Tri.YES;
    }

    private static List<ClassTypeDef> supertypesOf(ClassTypeDef type) {
        List<ClassTypeDef> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<ClassTypeDef> queue = new java.util.ArrayDeque<>();
        for (ClassTypeDef current = type; current != null; current = superclassOf(current)) {
            queue.add(current);
        }
        while (!queue.isEmpty()) {
            ClassTypeDef current = queue.removeFirst();
            if (!visited.add(current.getName())) {
                continue;
            }
            result.add(current);
            queue.addAll(interfacesOf(current));
        }
        return result;
    }

    private static List<ClassTypeDef> interfacesOf(ClassTypeDef type) {
        return switch (type) {
            case ClassTypeDef.JavaClass javaClass -> Arrays.stream(javaClass.type().getInterfaces()).<ClassTypeDef>map(ClassTypeDef::of).toList();
            case ClassTypeDef.ClassDefType classDefType -> classDefType.objectDef().getSuperinterfaces().stream()
                .map(TypeHierarchy::unwrap).filter(ClassTypeDef.class::isInstance).map(ClassTypeDef.class::cast).map(TypeOperations::declaredClass).toList();
            case ClassTypeDef.ClassElementType elementType -> elementType.classElement().getInterfaces().stream()
                .<ClassTypeDef>map(ClassTypeDef::of).toList();
            case EnclosedClassType memberOf -> interfacesOf(memberOf.member());
            default -> List.of();
        };
    }

    @Nullable
    private static ClassTypeDef superclassOf(ClassTypeDef type) {
        return switch (type) {
            case ClassTypeDef.JavaClass javaClass -> javaClass.type().getSuperclass() == null ? null : ClassTypeDef.of(javaClass.type().getSuperclass());
            case ClassTypeDef.ClassDefType classDefType -> classDefType.objectDef() instanceof ClassDef classDef && classDef.getSuperclass() != null
                ? TypeOperations.declaredClass(classDef.getSuperclass()) : null;
            case ClassTypeDef.ClassElementType elementType -> elementType.classElement().getSuperType().map(ClassTypeDef::of).orElse(null);
            case EnclosedClassType memberOf -> superclassOf(memberOf.member());
            default -> null;
        };
    }
}
