/*
 * Copyright 2017-2023 original authors
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

import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * Support for building an invocation from the signature the target actually declares, instead of from
 * the static types of the arguments.
 *
 * <p>A descriptor built from the argument types names a method that does not exist whenever an argument
 * is statically narrower than the declared parameter. The Java writer hides this, because javac resolves
 * the overload from the rendered call; the bytecode writer emits the descriptor verbatim and the mismatch
 * surfaces at run time as a {@link NoSuchMethodError}.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
final class Invocations {

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
        boolean.class, Boolean.class,
        byte.class, Byte.class,
        char.class, Character.class,
        short.class, Short.class,
        int.class, Integer.class,
        long.class, Long.class,
        float.class, Float.class,
        double.class, Double.class
    );

    private Invocations() {
    }

    /**
     * Resolves the method a call resolves to and adapts the arguments to it.
     *
     * @param owner         The type declaring the method, if it is known
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param returningType The return type the caller expects, or {@code null} for a constructor
     * @param values        The argument expressions
     * @return The resolved call, or {@code null} when the declaration cannot be resolved
     */
    @Nullable
    static Resolved resolve(@Nullable ClassTypeDef owner,
                            String name,
                            @Nullable TypeDef returningType,
                            List<? extends ExpressionDef> values) {
        if (owner == null) {
            return null;
        }
        List<MethodDef> candidates = owner.findDeclaredMethods(name, values.size());
        if (candidates.isEmpty()) {
            return null;
        }
        if (returningType == null) {
            List<MethodDef> possible = narrowByArguments(candidates, values);
            return possible.size() == 1 ? resolved(possible.get(0), values) : null;
        }
        List<MethodDef> matching = narrowByArguments(candidates.stream()
            .filter(m -> sameErasure(m.getReturnType(), returningType))
            .toList(), values);
        // A requested return type that matches none of the declarations is left alone: it is legal for the
        // Java writer, which lets javac resolve the call, and the bytecode writer reports it instead
        return matching.size() == 1 ? resolved(matching.get(0), values) : null;
    }

    /**
     * Drops the candidates an argument provably cannot be passed to, so that overloads of the same arity -
     * {@code ArrayList(int)} against {@code ArrayList(Collection)} - can still be told apart. A candidate is
     * kept whenever compatibility cannot be decided from the model alone.
     *
     * @param candidates The candidates
     * @param values     The argument expressions
     * @return The candidates that are not provably incompatible
     */
    private static List<MethodDef> narrowByArguments(List<MethodDef> candidates,
                                                     List<? extends ExpressionDef> values) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<MethodDef> possible = candidates.stream()
            .filter(m -> canAccept(m.getParameters(), values))
            .toList();
        return possible.isEmpty() ? candidates : possible;
    }

    private static boolean canAccept(List<ParameterDef> parameters, List<? extends ExpressionDef> values) {
        // With variable arity only the fixed prefix can be checked; the tail is packed into the array later
        int fixed = parameters.size() == values.size() ? parameters.size() : parameters.size() - 1;
        for (int i = 0; i < fixed && i < values.size(); i++) {
            // The model does not record variable arity, so the last argument of an exact-count call to an
            // array parameter may also be a single element of it
            boolean lastOfExactCount = i == parameters.size() - 1 && parameters.size() == values.size();
            if (!canAccept(parameters.get(i).getType(), values.get(i).type(), lastOfExactCount)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canAccept(TypeDef parameter, TypeDef argument, boolean orElement) {
        return maybeAssignable(parameter, argument)
            || orElement && erase(parameter) instanceof TypeDef.Array array && maybeAssignable(array.componentType(), argument);
    }

    private static boolean maybeAssignable(TypeDef parameterType, TypeDef argumentType) {
        if (sameErasure(parameterType, argumentType)) {
            return true;
        }
        TypeDef parameter = erase(parameterType);
        TypeDef argument = erase(argumentType);
        if (parameter instanceof TypeDef.Primitive primitive) {
            // Only the exact wrapper unboxes to a primitive; a wider reference type does not
            return argument instanceof ClassTypeDef classTypeDef
                && isWrapperOf(primitive, classTypeDef.getName());
        }
        if (argument instanceof TypeDef.Primitive primitive) {
            // A primitive boxes, and the box widens to any supertype of the wrapper
            Class<?> wrapper = WRAPPERS.get(primitive.clazz());
            return wrapper != null && parameter instanceof ClassTypeDef classTypeDef
                && isSuperTypeName(wrapper, classTypeDef.getName());
        }
        if (argument instanceof TypeDef.Array argumentArray) {
            if (parameter instanceof TypeDef.Array parameterArray) {
                return parameterArray.dimensions() == argumentArray.dimensions()
                    && maybeAssignable(parameterArray.componentType(), argumentArray.componentType());
            }
            return parameter instanceof ClassTypeDef classTypeDef && isArraySuperTypeName(classTypeDef.getName());
        }
        if (parameter instanceof TypeDef.Array) {
            return false;
        }
        if (parameter instanceof ClassTypeDef parameterClass && argument instanceof ClassTypeDef argumentClass) {
            return maybeAssignable(parameterClass, argumentClass);
        }
        // Not resolvable from the model - assume it could match
        return true;
    }

    private static boolean maybeAssignable(ClassTypeDef parameter, ClassTypeDef argument) {
        if (argument instanceof ClassTypeDef.ClassElementType argumentElement) {
            Class<?> parameterClass = rawClass(parameter);
            return parameterClass != null
                ? argumentElement.classElement().isAssignable(parameterClass)
                : argumentElement.classElement().isAssignable(parameter.getName());
        }
        Class<?> argumentClass = rawClass(argument);
        if (argumentClass == null) {
            return true;
        }
        Class<?> parameterClass = rawClass(parameter);
        if (parameterClass != null) {
            return parameterClass.isAssignableFrom(argumentClass);
        }
        return isSuperTypeName(argumentClass, parameter.getName());
    }

    private static boolean isWrapperOf(TypeDef.Primitive primitive, String name) {
        Class<?> wrapper = WRAPPERS.get(primitive.clazz());
        return wrapper != null && wrapper.getName().equals(name);
    }

    private static boolean isArraySuperTypeName(String name) {
        return name.equals(Object.class.getName())
            || name.equals(Cloneable.class.getName())
            || name.equals(java.io.Serializable.class.getName());
    }

    /**
     * @param type The type
     * @param name The binary name of a candidate supertype
     * @return True if the type is, extends or implements the named type
     */
    private static boolean isSuperTypeName(Class<?> type, String name) {
        if (type.getName().equals(name)) {
            return true;
        }
        for (Class<?> i : type.getInterfaces()) {
            if (isSuperTypeName(i, name)) {
                return true;
            }
        }
        return type.getSuperclass() != null && isSuperTypeName(type.getSuperclass(), name);
    }

    @Nullable
    private static Class<?> rawClass(TypeDef typeDef) {
        return switch (erase(typeDef)) {
            case ClassTypeDef.JavaClass javaClass -> javaClass.type();
            default -> null;
        };
    }

    /**
     * Erases a type the way a descriptor does: a parameterized type to its raw type, a type variable to its
     * first bound, and an annotated type to the type it annotates.
     *
     * @param typeDef The type
     * @return The erasure
     */
    private static TypeDef erase(TypeDef typeDef) {
        return switch (typeDef) {
            case ClassTypeDef.Parameterized parameterized -> erase(parameterized.rawType());
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> erase(annotated.typeDef());
            case TypeDef.AnnotatedTypeDef annotated -> erase(annotated.typeDef());
            case TypeDef.TypeVariable variable -> variable.bounds().isEmpty()
                ? ClassTypeDef.OBJECT
                : erase(variable.bounds().get(0));
            default -> typeDef;
        };
    }

    /**
     * Compares two types the way a descriptor does: by erasure, ignoring nullability.
     *
     * @param left  The left type
     * @param right The right type
     * @return True if the two erase to the same descriptor
     */
    private static boolean sameErasure(TypeDef left, TypeDef right) {
        return descriptorName(left).equals(descriptorName(right));
    }

    private static String descriptorName(TypeDef typeDef) {
        return switch (erase(typeDef)) {
            case TypeDef.Array array -> descriptorName(array.componentType()) + "[]".repeat(array.dimensions());
            case TypeDef.Primitive primitive -> primitive.name();
            case ClassTypeDef classTypeDef -> classTypeDef.getName();
            case TypeDef other -> other.toString();
        };
    }

    private static Resolved resolved(MethodDef method, List<? extends ExpressionDef> values) {
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        return new Resolved(parameterTypes, adaptToVarArgs(parameterTypes, values));
    }

    /**
     * Packs the trailing arguments of a variable arity call into an array, so that the number of arguments
     * matches the declared parameters.
     *
     * @param parameterTypes The declared parameter types
     * @param values         The argument expressions
     * @return The adapted arguments
     */
    private static List<? extends ExpressionDef> adaptToVarArgs(List<TypeDef> parameterTypes,
                                                                List<? extends ExpressionDef> values) {
        if (parameterTypes.isEmpty()) {
            return values;
        }
        if (!(parameterTypes.getLast() instanceof TypeDef.Array array)) {
            return values;
        }
        int fixed = parameterTypes.size() - 1;
        if (values.size() == parameterTypes.size() && values.getLast().type() instanceof TypeDef.Array) {
            // The caller already passed the array
            return values;
        }
        List<ExpressionDef> adapted = new ArrayList<>(parameterTypes.size());
        adapted.addAll(values.subList(0, fixed));
        adapted.add(new ExpressionDef.NewArrayInitialized(array, List.copyOf(values.subList(fixed, values.size()))));
        return adapted;
    }

    /**
     * A call resolved against the declaration of its target.
     *
     * @param parameterTypes The declared parameter types
     * @param values         The arguments, with a variable arity tail packed into an array
     */
    record Resolved(List<TypeDef> parameterTypes, List<? extends ExpressionDef> values) {
    }

}
