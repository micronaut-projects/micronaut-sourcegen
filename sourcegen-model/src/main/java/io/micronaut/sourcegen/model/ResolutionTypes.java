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

import io.micronaut.sourcegen.model.OverloadResolution.Tri;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The type operations the resolution of a call shares: erasure as a descriptor takes it, the identity of types, the
 * subtypes of a class and the primitive conversions.
 *
 * @since 2.3
 */
final class ResolutionTypes {

    /**
     * The supertypes of every array (JLS 10.8).
     */
    static final Set<String> ARRAY_SUPERTYPES = Set.of(Object.class.getName(), Cloneable.class.getName(),
        java.io.Serializable.class.getName());

    /**
     * The wrapper of each primitive but {@code void}.
     */
    static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
        boolean.class, Boolean.class,
        byte.class, Byte.class,
        char.class, Character.class,
        short.class, Short.class,
        int.class, Integer.class,
        long.class, Long.class,
        float.class, Float.class,
        double.class, Double.class
    );

    private static final List<Class<?>> NUMERIC_ORDER = List.of(byte.class, short.class, int.class, long.class, float.class, double.class);

    private ResolutionTypes() {
    }

    /**
     * A type as its erasure, an array keeping its rank but not whether it is nullable, which the arrays the resolution
     * compares and packs do not carry.
     *
     * @param type The type
     * @return The erasure
     */
    static TypeDef erasedDeep(TypeDef type) {
        TypeDef erased = TypeOperations.erase(type);
        return erased instanceof TypeDef.Array array && array.isNullable() ? TypeDef.array(array.componentType(), array.dimensions()) : erased;
    }

    /**
     * The name of a type in a descriptor, as a class file erases it.
     *
     * @param typeDef The type
     * @return The name
     */
    static String descriptorName(TypeDef typeDef) {
        return switch (TypeOperations.erase(typeDef)) {
            case TypeDef.Array array -> descriptorName(array.componentType()) + "[]".repeat(array.dimensions());
            case TypeDef.Primitive primitive -> primitive.name();
            case ClassTypeDef classTypeDef -> classTypeDef.getName();
            case TypeDef other -> other.toString();
        };
    }

    /**
     * Whether two types erase to the same descriptor, ignoring nullability.
     *
     * @param left  The left type
     * @param right The right type
     * @return true where they do
     */
    static boolean sameErasure(TypeDef left, TypeDef right) {
        return descriptorName(left).equals(descriptorName(right));
    }

    /**
     * Whether two types are the same, with their type arguments: a variable is itself alone.
     *
     * @param left  The left type
     * @param right The right type
     * @return true where they are
     */
    static boolean sameType(TypeDef left, TypeDef right) {
        TypeDef l = TypeOperations.unwrap(left);
        TypeDef r = TypeOperations.unwrap(right);
        if (l instanceof TypeDef.TypeVariable || r instanceof TypeDef.TypeVariable) {
            // A variable is itself: two captures are distinct
            return l instanceof TypeDef.TypeVariable left2 && r instanceof TypeDef.TypeVariable right2 && left2.name().equals(right2.name());
        }
        if (l instanceof TypeDef.Array || r instanceof TypeDef.Array) {
            // Compared by their components: a `List<Integer>[]` is not a `List<String>[]`
            return l instanceof TypeDef.Array leftArray && r instanceof TypeDef.Array rightArray
                && leftArray.dimensions() == rightArray.dimensions() && sameType(leftArray.componentType(), rightArray.componentType());
        }
        if (l instanceof ClassTypeDef.Parameterized lp && r instanceof ClassTypeDef.Parameterized rp) {
            if (!sameErasure(lp, rp) || lp.typeArguments().size() != rp.typeArguments().size()) {
                return false;
            }
            for (int i = 0; i < lp.typeArguments().size(); i++) {
                if (!sameType(lp.typeArguments().get(i), rp.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return !(l instanceof ClassTypeDef.Parameterized) && !(r instanceof ClassTypeDef.Parameterized) && sameErasure(l, r);
    }

    /**
     * Whether a type is {@link Object}, with no bound a variable could stand for.
     *
     * @param type The type
     * @return true where it is
     */
    static boolean isObject(TypeDef type) {
        return TypeOperations.unwrap(type) instanceof ClassTypeDef classTypeDef && Object.class.getName().equals(TypeOperations.declaredClass(classTypeDef).getName());
    }

    /**
     * Whether a type extends or implements the named one, from its reflection, compiler element or model.
     *
     * @param type The type
     * @param name The binary name of the supertype
     * @return Whether it does, or {@link Tri#UNKNOWN} where the type carries no hierarchy
     */
    static Tri isSubtype(ClassTypeDef type, String name) {
        if (type.getName().equals(name)) {
            return Tri.YES;
        }
        if (type instanceof ClassTypeDef.ClassElementType element) {
            if (element.classElement().isAssignable(name)) {
                return Tri.YES;
            }
            // An element of a compiled class tells its supertypes by class rather than by name
            Class<?> named = load(name);
            return Tri.of(named != null && element.classElement().isAssignable(named));
        }
        if (type instanceof ClassTypeDef.ClassDefType) {
            return Tri.of(TypeHierarchy.inherits(type, name, null));
        }
        Class<?> valueClass = type instanceof ClassTypeDef.JavaClass javaClass ? javaClass.type() : load(type.getName());
        if (valueClass == null) {
            return Tri.UNKNOWN;
        }
        Class<?> parameterClass = load(name);
        // A compiled class does not extend a type no class loader has
        return Tri.of(parameterClass != null && parameterClass.isAssignableFrom(valueClass));
    }

    @Nullable
    private static Class<?> load(String name) {
        return TypeLookup.reflective().loadClass(name);
    }

    /**
     * The primitive a type unboxes to: a wrapper's, or that of a variable bounded by one - a wrapper is final, so a
     * `T extends Integer` is an Integer.
     *
     * @param type The type
     * @return The primitive, or {@code null} where the type unboxes to none
     */
    @Nullable
    static Class<?> unboxed(TypeDef type) {
        return unboxed(type, new HashSet<>());
    }

    @Nullable
    private static Class<?> unboxed(TypeDef type, Set<String> visited) {
        TypeDef unwrapped = TypeOperations.unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable && visited.add(variable.name())) {
            for (TypeDef bound : variable.bounds()) {
                Class<?> unboxed = unboxed(bound, visited);
                if (unboxed != null) {
                    return unboxed;
                }
            }
            return null;
        }
        if (!(unwrapped instanceof ClassTypeDef classTypeDef)) {
            return null;
        }
        String name = TypeOperations.declaredClass(classTypeDef).getName();
        return WRAPPERS.entrySet().stream().filter(entry -> entry.getValue().getName().equals(name))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /**
     * Whether a primitive widens to another (JLS 5.1.2), or is it.
     *
     * @param from The primitive
     * @param to   The other
     * @return true where it does
     */
    static boolean widens(Class<?> from, Class<?> to) {
        if (from == to) {
            return true;
        }
        if (from == char.class) {
            return NUMERIC_ORDER.indexOf(to) >= NUMERIC_ORDER.indexOf(int.class);
        }
        int source = NUMERIC_ORDER.indexOf(from);
        int target = NUMERIC_ORDER.indexOf(to);
        // A short does not widen to a char, nor a byte
        return source >= 0 && target > source;
    }

    /**
     * Whether a type names one of the variables.
     *
     * @param type      The type
     * @param variables The variables
     * @return true where it does
     */
    static boolean mentions(TypeDef type, List<TypeDef.TypeVariable> variables) {
        // Also through a wildcard, and the enclosing type of a member: `Outer<T>.Member`
        return TypeOperations.mentionsVariable(type, name -> variables.stream().anyMatch(own -> own.name().equals(name)));
    }

    /**
     * The parameter an argument is passed to: by variable arity, one of the tail is the component of the array.
     *
     * @param parameters    The parameters
     * @param index         The index of the argument
     * @param variableArity Whether the method applies by variable arity
     * @return The parameter
     */
    static TypeDef expanded(List<TypeDef> parameters, int index, boolean variableArity) {
        if (!variableArity || index < parameters.size() - 1) {
            return parameters.get(Math.min(index, parameters.size() - 1));
        }
        TypeDef.Array array = (TypeDef.Array) TypeOperations.unwrap(parameters.getLast());
        return array.dimensions() == 1 ? array.componentType() : TypeDef.array(array.componentType(), array.dimensions() - 1);
    }

    /**
     * The bounds a class declares a parameter with.
     *
     * @param bounds The bounds of each parameter
     * @param index  The index of the parameter
     * @return Its bounds, none where the class declares fewer parameters
     */
    static List<TypeDef> boundAt(List<List<TypeDef>> bounds, int index) {
        return index < bounds.size() ? bounds.get(index) : List.of();
    }
}
