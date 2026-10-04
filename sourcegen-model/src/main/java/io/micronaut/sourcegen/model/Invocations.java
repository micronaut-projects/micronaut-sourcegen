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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.model.ResolutionTypes.ARRAY_SUPERTYPES;
import static io.micronaut.sourcegen.model.ResolutionTypes.WRAPPERS;
import static io.micronaut.sourcegen.model.ResolutionTypes.descriptorName;
import static io.micronaut.sourcegen.model.ResolutionTypes.sameErasure;
import static io.micronaut.sourcegen.model.TypeOperations.erase;

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
        return owner == null ? null : resolve(List.of(owner), name, returningType, values);
    }

    /**
     * Resolves the method a call resolves to among the members of the owners - the bounds of a variable receiver, all
     * of them together - and adapts the arguments to it. A call no method applies to, or an ambiguous one, is left
     * unresolved: the bytecode writers reject an ambiguous one where it is written. Where the model cannot tell, the
     * one method of the name and arity the arguments can be passed to is taken.
     *
     * @param owners        The types the method is invoked on
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param returningType The return type the caller expects, or {@code null} for a constructor
     * @param values        The argument expressions
     * @return The resolved call, or {@code null} when the declaration cannot be resolved
     */
    @Nullable
    static Resolved resolve(List<ClassTypeDef> owners,
                            String name,
                            @Nullable TypeDef returningType,
                            List<? extends ExpressionDef> values) {
        if (owners.isEmpty()) {
            return null;
        }
        OverloadResolution.Resolution resolution = OverloadResolution.resolve(owners, name, returningType, values, null, null);
        if (resolution.outcome() == OverloadResolution.Outcome.RESOLVED) {
            return resolution.resolved();
        }
        if (resolution.outcome() != OverloadResolution.Outcome.UNKNOWN) {
            return null;
        }
        // The model cannot tell - an argument of a type that carries no hierarchy, a class only named, which a
        // generated class often is, or methods it tells apart no further, `choose(T)` and `choose(Object)` of a raw
        // `T extends Number` receiver: the one method of the arity the arguments are not provably not passed to.
        // A method of one descriptor two bounds declare is one candidate
        Map<String, MethodDef> byDescriptor = new java.util.LinkedHashMap<>();
        owners.stream().flatMap(owner -> owner.findDeclaredMethods(name, values.size()).stream())
            .forEach(method -> byDescriptor.putIfAbsent(method.getParameters().stream()
                .map(parameter -> descriptorName(parameter.getType())).toList() + descriptorName(method.getReturnType()), method));
        List<MethodDef> candidates = List.copyOf(byDescriptor.values());
        if (candidates.isEmpty()) {
            return null;
        }
        if (returningType == null) {
            List<MethodDef> possible = narrowByArguments(candidates, values);
            return possible.size() == 1 ? resolved(possible.get(0), null, values) : null;
        }
        List<MethodDef> matching = narrowByArguments(candidates.stream()
            .filter(m -> sameErasure(m.getReturnType(), returningType))
            .toList(), values);
        // A requested return type that matches none of the declarations is left alone: it is legal for the
        // Java writer, which lets javac resolve the call, and the bytecode writer reports it instead
        return matching.size() == 1 ? resolved(matching.get(0), returningType, values) : null;
    }

    /**
     * Drops the candidates an argument provably cannot be passed to, so that overloads of the same arity -
     * {@code ArrayList(int)} against {@code ArrayList(Collection)} - can still be told apart, and a lone one the
     * arguments do not fit is not taken. A candidate is kept whenever compatibility cannot be decided from the model
     * alone.
     *
     * @param candidates The candidates
     * @param values     The argument expressions
     * @return The candidates that are not provably incompatible
     */
    private static List<MethodDef> narrowByArguments(List<MethodDef> candidates,
                                                     List<? extends ExpressionDef> values) {
        return candidates.stream()
            .filter(m -> canAccept(m.getParameters(), values))
            .toList();
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
            return parameter instanceof ClassTypeDef classTypeDef && ARRAY_SUPERTYPES.contains(classTypeDef.getName());
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

    private static Resolved resolved(MethodDef method,
                                     @Nullable TypeDef returningType,
                                     List<? extends ExpressionDef> values) {
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        return new Resolved(parameterTypes, adaptToVarArgs(parameterTypes, returningType, values), false);
    }

    /**
     * Packs the trailing arguments of a variable arity call into an array, so that the number of arguments
     * matches the declared parameters.
     *
     * @param parameterTypes The declared parameter types
     * @param returningType  The return type the caller expects, or {@code null} when it is not known
     * @param values         The argument expressions
     * @return The adapted arguments
     */
    private static List<? extends ExpressionDef> adaptToVarArgs(List<TypeDef> parameterTypes,
                                                                @Nullable TypeDef returningType,
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
        List<ExpressionDef> packed = List.copyOf(values.subList(fixed, values.size()));
        List<ExpressionDef> adapted = new ArrayList<>(parameterTypes.size());
        adapted.addAll(values.subList(0, fixed));
        adapted.add(new ExpressionDef.NewArrayInitialized(packedArrayType(array, returningType, packed), packed));
        return adapted;
    }

    /**
     * Whether an argument names a variable alone, whose bounds the class and the method the call is written in
     * declare - which the model does not know while the call is built.
     *
     * @param values The argument expressions
     * @return true where it does
     */
    static boolean needsScope(List<? extends ExpressionDef> values) {
        return values.stream().anyMatch(value -> namesUnboundedVariable(value.type()));
    }

    /**
     * Whether a receiver is a variable named alone, whose members are those of the bounds where it is written.
     *
     * @param receiver The type of the receiver
     * @return true where it is
     */
    static boolean receiverNeedsScope(TypeDef receiver) {
        return TypeHierarchy.unwrap(receiver) instanceof TypeDef.TypeVariable variable && variable.bounds().isEmpty();
    }

    /**
     * The classes the members of a receiver are looked up in: its class, or the bounds of a variable, following a
     * bound that is another variable.
     *
     * @param receiver The type of the receiver
     * @return The classes, in the order of the bounds
     */
    static List<ClassTypeDef> receiverClasses(TypeDef receiver) {
        return receiverClasses(receiver, Map.of());
    }

    /**
     * The classes the members of a receiver are looked up in: its class, or the bounds of a variable - of one named
     * alone, those the scope gives it - following a bound that is another variable.
     *
     * @param receiver The type of the receiver
     * @param scope    The variables in scope, with their bounds, by name
     * @return The classes, in the order of the bounds
     */
    static List<ClassTypeDef> receiverClasses(TypeDef receiver, Map<String, TypeDef> scope) {
        List<ClassTypeDef> result = new ArrayList<>();
        receiverClasses(receiver, scope, result, new HashSet<>());
        return result;
    }

    private static void receiverClasses(TypeDef receiver, Map<String, TypeDef> scope, List<ClassTypeDef> result, Set<String> visited) {
        TypeDef unwrapped = TypeHierarchy.unwrap(receiver);
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            result.add(classTypeDef);
        } else if (unwrapped instanceof TypeDef.Array) {
            // An array has the members of Object (JLS 10.7)
            result.add(TypeDef.OBJECT);
        } else if (unwrapped instanceof TypeDef.TypeVariable variable && visited.add(variable.name())) {
            List<TypeDef> bounds = variable.bounds();
            if (bounds.isEmpty() && TypeHierarchy.unwrap(scope.getOrDefault(variable.name(), variable)) instanceof TypeDef.TypeVariable declared) {
                bounds = declared.bounds();
            }
            bounds.forEach(bound -> receiverClasses(bound, scope, result, visited));
        }
    }

    private static boolean namesUnboundedVariable(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        return switch (unwrapped) {
            case TypeDef.TypeVariable variable -> variable.bounds().isEmpty();
            case TypeDef.Array array -> namesUnboundedVariable(array.componentType());
            case ClassTypeDef.Parameterized parameterized -> parameterized.typeArguments().stream().anyMatch(Invocations::namesUnboundedVariable);
            default -> false;
        };
    }

    /**
     * The type of the array a variable arity tail is packed into.
     *
     * <p>A variable arity parameter declared with a type variable - {@code List.of(E...)} - erases to
     * {@code Object[]}, and an {@code Object[]} argument pins the variable to {@link Object}. The Java
     * writer then renders {@code List.of(new Object[]{...})}, which javac rejects against a
     * {@code List<Entry>} target with "inference variable E has incompatible bounds"; the fixed arity
     * overloads hide this until there are more arguments than any of them takes. Typing the array with
     * the element type the call site asks for - the array javac itself builds for a variable arity call -
     * keeps the rendered source compiling and remains a valid argument for the erased descriptor the
     * bytecode writer emits.
     *
     * @param declared      The declared array parameter type
     * @param returningType The return type the caller expects, or {@code null} when it is not known
     * @param packed        The arguments being packed into the array
     * @return The array type to pack them into
     */
    private static TypeDef.Array packedArrayType(TypeDef.Array declared,
                                                 @Nullable TypeDef returningType,
                                                 List<? extends ExpressionDef> packed) {
        if (packed.isEmpty() || declared.dimensions() != 1 || !erasesToObject(declared.componentType())) {
            return declared;
        }
        TypeDef elementType = requestedElementType(returningType);
        if (elementType == null || erasesToObject(elementType)) {
            return declared;
        }
        for (ExpressionDef value : packed) {
            if (!maybeAssignable(elementType, value.type())) {
                return declared;
            }
        }
        return new TypeDef.Array(elementType, 1, declared.nullable());
    }

    /**
     * The element type a return type pins, for a declaration that returns the container of its variable
     * arity elements - {@code List.of}, {@code Set.of}, {@code Arrays.asList}, {@code Stream.of}. A type
     * that cannot be written as an array component - a parameterized type, a wildcard or a variable -
     * pins nothing, because a generic array cannot be created.
     *
     * @param returningType The return type the caller expects, or {@code null} when it is not known
     * @return The element type, or {@code null} when none is pinned
     */
    @Nullable
    private static TypeDef requestedElementType(@Nullable TypeDef returningType) {
        if (!(returningType instanceof ClassTypeDef.Parameterized parameterized)
            || parameterized.typeArguments().size() != 1) {
            return null;
        }
        TypeDef typeArgument = parameterized.typeArguments().get(0);
        return typeArgument instanceof ClassTypeDef
            && !(typeArgument instanceof ClassTypeDef.Parameterized)
            && !(typeArgument instanceof ClassTypeDef.AnnotatedClassTypeDef)
            ? typeArgument : null;
    }

    private static boolean erasesToObject(TypeDef typeDef) {
        return erase(typeDef) instanceof ClassTypeDef classTypeDef
            && Object.class.getName().equals(classTypeDef.getName());
    }

    /**
     * A call resolved against the declaration of its target.
     *
     * @param parameterTypes The declared parameter types
     * @param values         The arguments, with a variable arity tail packed into an array
     * @param isStatic       Whether the method is static, which a call through a value invokes statically
     */
    record Resolved(List<TypeDef> parameterTypes, List<? extends ExpressionDef> values, boolean isStatic) {
    }
}
