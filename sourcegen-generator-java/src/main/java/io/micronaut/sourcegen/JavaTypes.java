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
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The types of the model as Java names them: the raw type and the erasure of a type, the primitive a box holds, and
 * what a primitive widens to. None of it depends on the file being written.
 *
 * @since 2.3
 */
@Internal
final class JavaTypes {

    private static final List<String> WIDENING_ORDER = List.of("byte", "short", "int", "long", "float", "double");

    private JavaTypes() {
    }

    /**
     * The type declaring an invoked method: the type being written for `this`, and the superclass of a class for
     * `super`, which the model names by placeholders.
     *
     * @param objectDef The definition being written
     * @param type      The type of the receiver
     * @return The declaring type, or {@code null}
     */
    @Nullable
    static ClassTypeDef ownerOf(@Nullable ObjectDef objectDef, TypeDef type) {
        TypeDef resolved = type;
        if (objectDef != null && (TypeDef.THIS.equals(type)
            || TypeDef.SUPER.equals(type) && !(objectDef instanceof InterfaceDef))) {
            resolved = objectDef.getContextualType(type);
        }
        if (TypeHierarchy.unwrap(resolved) instanceof TypeDef.TypeVariable variable) {
            // A receiver of a variable has the members of its bound, of the type arguments the bound gives them
            List<TypeDef> bounds = variable.bounds().isEmpty() ? OverrideResolver.upperBounds(variable, objectDef, null) : variable.bounds();
            resolved = bounds.isEmpty() ? variable : bounds.getFirst();
        }
        return resolved instanceof ClassTypeDef classTypeDef && !TypeDef.SUPER.equals(classTypeDef)
            && !TypeDef.THIS.equals(classTypeDef) ? classTypeDef : null;
    }

    /**
     * @param type A type
     * @return Whether it is the placeholder of `this` or `super`, of the definition being written, which no class
     * loader has
     */
    static boolean isPlaceholder(TypeDef type) {
        return TypeDef.THIS.equals(TypeHierarchy.unwrap(type)) || TypeDef.SUPER.equals(TypeHierarchy.unwrap(type));
    }

    /**
     * Whether an arithmetic operation is of a type Java promotes to {@code int}: the model types an operation of
     * bytes or shorts, and a negated char, by its operand - {@code short - short} is a {@code short} - which the
     * bytecode writers narrow the result to. The source writes the cast to that type around the operation, so that
     * it has the type of the model wherever the value goes.
     *
     * @param expression The expression
     * @return true if it is an operation the source narrows
     */
    static boolean isNarrowedOperation(ExpressionDef expression) {
        return narrowedPrimitive(expression) != null;
    }

    /**
     * The primitive an arithmetic operation is narrowed to (see {@link #isNarrowedOperation}): that of its type, or
     * the one its box holds - the negation of a {@code Character} is a char.
     *
     * @param expression The expression
     * @return The byte, short or char, or null where the operation is not narrowed
     */
    static TypeDef.@Nullable Primitive narrowedPrimitive(ExpressionDef expression) {
        if (!(expression instanceof ExpressionDef.MathBinaryOperation || expression instanceof ExpressionDef.MathUnaryOperation)) {
            return null;
        }
        TypeDef type = TypeHierarchy.unwrap(expression.type());
        TypeDef.Primitive primitive = type instanceof TypeDef.Primitive p ? p : unboxedOf(type);
        return TypeDef.Primitive.BYTE.equals(primitive) || TypeDef.Primitive.SHORT.equals(primitive)
            || TypeDef.Primitive.CHAR.equals(primitive) ? primitive : null;
    }

    static TypeDef.@Nullable Primitive unboxedOf(TypeDef type) {
        if (!(type instanceof ClassTypeDef classType)) {
            return null;
        }
        return switch (classType.getName()) {
            case "java.lang.Boolean" -> TypeDef.Primitive.BOOLEAN;
            case "java.lang.Byte" -> TypeDef.Primitive.BYTE;
            case "java.lang.Short" -> TypeDef.Primitive.SHORT;
            case "java.lang.Character" -> TypeDef.Primitive.CHAR;
            case "java.lang.Integer" -> TypeDef.Primitive.INT;
            case "java.lang.Long" -> TypeDef.Primitive.LONG;
            case "java.lang.Float" -> TypeDef.Primitive.FLOAT;
            case "java.lang.Double" -> TypeDef.Primitive.DOUBLE;
            default -> null;
        };
    }

    /**
     * @param from A primitive
     * @param to   Another primitive
     * @return Whether Java converts the one to the other without a cast
     */
    static boolean widens(TypeDef.Primitive from, TypeDef.Primitive to) {
        if (from.equals(to)) {
            return true;
        }
        int target = WIDENING_ORDER.indexOf(to.name());
        if (target == -1) {
            return false;
        }
        return "char".equals(from.name()) ? target >= 2
            : WIDENING_ORDER.indexOf(from.name()) != -1 && WIDENING_ORDER.indexOf(from.name()) < target;
    }

    static boolean sameErasure(TypeDef type, TypeDef other) {
        return TypeHierarchy.erasedName(type).equals(TypeHierarchy.erasedName(other));
    }

    /**
     * The erasure of a type: a parameterization and a member of one raw, a variable its first bound, as the class
     * literal of a type, and the component of an array created, name it.
     *
     * @param type      The type
     * @param objectDef The definition being written, which declares a variable of its class
     * @param methodDef The method being written, which declares a variable of its own
     * @return The erasure
     */
    static TypeDef erasure(TypeDef type, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        return erasure(type, objectDef, methodDef, new HashSet<>());
    }

    private static TypeDef erasure(TypeDef type, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Set<String> visited) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        return switch (unwrapped) {
            case ClassTypeDef.Parameterized parameterized -> erasure(parameterized.rawType(), objectDef, methodDef, visited);
            case TypeDef.Array array -> TypeDef.array(erasure(array.componentType(), objectDef, methodDef, visited), array.dimensions());
            case TypeDef.TypeVariable variable -> {
                if (!visited.add(variable.name())) {
                    yield TypeDef.OBJECT;
                }
                // The variable can be named without the bounds it is declared with
                List<TypeDef> bounds = variable.bounds().isEmpty()
                    ? OverrideResolver.upperBounds(variable, objectDef, methodDef) : variable.bounds();
                yield bounds.isEmpty() ? TypeDef.OBJECT : erasure(bounds.getFirst(), objectDef, methodDef, visited);
            }
            case TypeDef.Wildcard wildcard -> wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT
                : erasure(wildcard.upperBounds().getFirst(), objectDef, methodDef, visited);
            // A member of a parameterized type, `Outer<String>.Inner`, is the member class
            case ClassTypeDef member when TypeHierarchy.enclosingOf(member) instanceof ClassTypeDef.Parameterized -> TypeHierarchy.memberClass(member);
            default -> unwrapped;
        };
    }

    /**
     * The component an array is created with: Java creates no array of a variable or a parameterized type - `new T[2]`
     * is a generic array creation - where the bytecode creates the array of the erasure.
     *
     * @param array     The type of the array
     * @param objectDef The definition being written
     * @param methodDef The method being written
     * @return The component
     */
    static TypeDef creationComponent(TypeDef.Array array, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        return createsErased(array) ? erasure(array.componentType(), objectDef, methodDef) : array.componentType();
    }

    private static boolean createsErased(TypeDef.Array array) {
        TypeDef component = TypeHierarchy.unwrap(array.componentType());
        return component instanceof ClassTypeDef.Parameterized || component instanceof TypeDef.TypeVariable
            || component instanceof TypeDef.Wildcard
            || component instanceof ClassTypeDef member && TypeHierarchy.enclosingOf(member) instanceof ClassTypeDef.Parameterized;
    }

    /**
     * Whether a value is cast to a variable it is not known to be: one of another variable, or of its bound.
     *
     * @param targetType The type converted to
     * @param valueType  The type of the value
     * @return true if the value is cast
     */
    static boolean requiresVariableCast(TypeDef targetType, TypeDef valueType) {
        return TypeHierarchy.unwrap(targetType) instanceof TypeDef.TypeVariable
            && !TypeHierarchy.unwrap(targetType).equals(TypeHierarchy.unwrap(valueType))
            && (valueType instanceof ClassTypeDef || valueType instanceof TypeDef.Array
            || valueType instanceof TypeDef.TypeVariable);
    }

    static void collectVariables(TypeDef type, Map<String, TypeDef.TypeVariable> variables) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            variables.putIfAbsent(variable.name(), variable);
        } else if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            parameterized.typeArguments().forEach(argument -> collectVariables(argument, variables));
        } else if (unwrapped instanceof TypeDef.Array array) {
            collectVariables(array.componentType(), variables);
        } else if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            wildcard.upperBounds().forEach(bound -> collectVariables(bound, variables));
            wildcard.lowerBounds().forEach(bound -> collectVariables(bound, variables));
        }
    }

    /**
     * The type a value converted to a primitive has: the primitive the source types it as, an {@code int} for an
     * operation of bytes the model types as a byte.
     *
     * @param targetType The type converted to
     * @param valueType  The type of the value in the model
     * @param sourceType The type of the value in the source
     * @return The type
     */
    static TypeDef primitiveOfSource(TypeDef targetType, TypeDef valueType, TypeDef sourceType) {
        return TypeHierarchy.unwrap(targetType) instanceof TypeDef.Primitive && sourceType instanceof TypeDef.Primitive
            && valueType instanceof TypeDef.Primitive ? sourceType : valueType;
    }

    static Map<String, TypeDef> substitutionOf(Class<?> type, ClassTypeDef.Parameterized parameterized) {
        Map<String, TypeDef> substitution = new HashMap<>();
        java.lang.reflect.TypeVariable<?>[] variables = type.getTypeParameters();
        for (int i = 0; i < variables.length && i < parameterized.typeArguments().size(); i++) {
            substitution.put(variables[i].getName(), parameterized.typeArguments().get(i));
        }
        return substitution;
    }

    static boolean isAssignable(TypeDef declaredType, TypeDef valueType) {
        if (declaredType.equals(valueType) || TypeDef.OBJECT.equals(declaredType)) {
            return true;
        }
        Class<?> declared = loaded(declaredType);
        Class<?> value = loaded(valueType);
        // Unresolvable types are taken as compatible: the value is written as it is, rather than erased
        return declared == null || value == null || declared.isAssignableFrom(value);
    }

    /**
     * @param typeDef A type, or {@code null}
     * @return The class of it the generator's class loader has, or {@code null}
     */
    @Nullable
    static Class<?> loaded(@Nullable TypeDef typeDef) {
        return loaded(typeDef, TypeLookup.reflective());
    }

    /**
     * @param typeDef A type, or {@code null}
     * @param lookup  The lookup of the file being written
     * @return The class of a class type the lookup loads, or {@code null} for any other type
     */
    @Nullable
    static Class<?> loaded(@Nullable TypeDef typeDef, TypeLookup lookup) {
        if (typeDef == null) {
            return null;
        }
        TypeDef unwrapped = TypeOperations.unwrap(typeDef);
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return loaded(parameterized.rawType(), lookup);
        }
        if (unwrapped instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            return lookup.loadClass(classTypeDef.getName());
        }
        return null;
    }
}
