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
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves the signature a declared method takes as source when it overrides a generic inherited method with that
 * method's erased signature: the inherited parameter and return types with the type arguments of the supertype
 * substituted.
 *
 * <p>A model written for bytecode overrides with the erased form, which the verifier accepts, such as
 * {@code Object get()} for {@code Supplier<String>}; source only overrides with the substituted one. How much of
 * it depends on the language: Java accepts erased parameters and a raw type for a parameterized one, and needs the
 * substitution where an erasure changes or a return type is a type variable; Kotlin has no raw types and matches
 * parameters exactly, so it needs the whole substituted signature.</p>
 *
 * @since 2.2
 */
@Internal
public final class OverrideResolver {

    private static final int MAX_DEPTH = 8;
    private static final Set<String> ARRAY_SUPERTYPES = Set.of(Cloneable.class.getName(), java.io.Serializable.class.getName());

    private OverrideResolver() {
    }

    /**
     * Resolves the substituted signature of an erased override for Java source.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @param context   The context of the file being written, to look up the supertypes only known by name, or
     *                  {@code null}
     * @return The substituted signature, or {@code null} when Java source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           @Nullable VisitorContext context) {
        return resolve(objectDef, methodDef, context, false);
    }

    /**
     * Resolves the substituted signature of an erased override.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @param context   The context of the file being written, to look up the supertypes only known by name, or
     *                  {@code null}
     * @param exact     Whether the source language overrides with the substituted types only, as Kotlin does,
     *                  rather than accepting their erasures and raw types as Java does
     * @return The substituted signature, or {@code null} when the source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           @Nullable VisitorContext context,
                                           boolean exact) {
        if (objectDef == null || !methodDef.isOverride() || methodDef.isConstructor()
            || !methodDef.getTypeVariables().isEmpty()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)
            || TypeHierarchy.superTypesOf(objectDef).isEmpty()) {
            return null;
        }
        TypeHierarchy.InheritedType declaringType = TypeHierarchy.declaring(objectDef);
        List<String> parameterErasures = methodDef.getParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(declaringType.erase(parameter.getType()), objectDef)).toList();
        String returnErasure = TypeHierarchy.erasedName(declaringType.erase(methodDef.getReturnType()), objectDef);
        Function<String, @Nullable ClassElement> lookup = context == null ? null
            : name -> context.getClassElement(name).orElse(null);
        Declared declared = new Declared(objectDef, methodDef, parameterErasures, returnErasure, declaringType,
            new HashSet<>(declaringType.getTypeParameters()), exact, lookup);
        List<OverriddenMethod> found = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(objectDef, lookup,
            (type, inherited) -> {
                OverriddenMethod overridden = overriddenBy(declared, type, inherited);
                if (overridden != null) {
                    found.add(overridden);
                }
                return true;
            });
        return mostSpecific(found, new Hierarchy(lookup, declaringType));
    }

    /**
     * The parameter types a method of the definition is written with, where override resolution changes them - so
     * that a call to it passes values of those types.
     *
     * @param objectDef  The definition
     * @param callMethod The invoked method, as the model calls it
     * @param context    The context of the file being written, or {@code null}
     * @param exact      Whether the source language overrides with the substituted types only
     * @return The parameter types, or {@code null} where the method is written as the model declares it
     */
    @Nullable
    public static List<TypeDef> emittedParameterTypes(ObjectDef objectDef,
                                                      MethodDef callMethod,
                                                      @Nullable VisitorContext context,
                                                      boolean exact) {
        OverriddenMethod emitted = emittedSignature(objectDef, callMethod, context, exact);
        return emitted == null ? null : emitted.parameterTypes();
    }

    /**
     * The signature a method of the definition is written with, where override resolution changes it.
     *
     * @param objectDef  The definition
     * @param callMethod The invoked method, as the model calls it
     * @param context    The context of the file being written, or {@code null}
     * @param exact      Whether the source language overrides with the substituted types only
     * @return The signature, or {@code null} where the method is written as the model declares it
     */
    @Nullable
    public static OverriddenMethod emittedSignature(ObjectDef objectDef,
                                                    MethodDef callMethod,
                                                    @Nullable VisitorContext context,
                                                    boolean exact) {
        MethodDef method = declaredMethod(objectDef, callMethod);
        return method == null ? null : resolve(objectDef, method, context, exact);
    }

    @Nullable
    private static MethodDef declaredMethod(ObjectDef objectDef, MethodDef callMethod) {
        List<TypeDef> callTypes = callMethod.getParameters().stream().map(ParameterDef::getType).toList();
        for (MethodDef method : objectDef.getMethods()) {
            if (method.getName().equals(callMethod.getName())
                && method.getParameters().stream().map(ParameterDef::getType).toList().equals(callTypes)) {
                return method;
            }
        }
        return null;
    }

    /**
     * The signature of a method a definition inherits from the generated class it extends or the generated
     * interfaces it implements, in the scope of the definition: with the type arguments it extends them with.
     */
    @Nullable
    private static OverriddenMethod inheritedSignature(ObjectDef objectDef,
                                                       MethodDef callMethod,
                                                       @Nullable VisitorContext context,
                                                       boolean exact) {
        List<TypeDef> supertypes = new ArrayList<>();
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            supertypes.add(classDef.getSuperclass());
        }
        supertypes.addAll(objectDef.getSuperinterfaces());
        for (TypeDef supertype : supertypes) {
            if (supertype instanceof ClassTypeDef classType && definitionOf(classType, null) != null) {
                OverriddenMethod inherited = emittedSignature(classType, objectDef, null, callMethod, context, exact);
                if (inherited != null) {
                    return inherited;
                }
            }
        }
        return null;
    }

    /**
     * The signature a generated method is written with, as the receiver of the call sees it: with the type arguments
     * the receiver binds the definition's variables to - `apply(String)` on a `GenericTarget<String>`.
     *
     * @param owner      The type of the receiver, or {@code null}
     * @param current    The definition being written, or {@code null}
     * @param caller     The method the call is written in, or {@code null}
     * @param callMethod The invoked method, as the model calls it
     * @param context    The context of the file being written, or {@code null}
     * @param exact      Whether the source language overrides with the substituted types only
     * @return The signature, or {@code null} where the method is written as the model declares it, or its types
     * cannot be expressed where it is called
     */
    @Nullable
    public static OverriddenMethod emittedSignature(@Nullable ClassTypeDef owner,
                                                    @Nullable ObjectDef current,
                                                    @Nullable MethodDef caller,
                                                    MethodDef callMethod,
                                                    @Nullable VisitorContext context,
                                                    boolean exact) {
        ObjectDef target = definitionOf(owner, current);
        if (target == null) {
            return null;
        }
        OverriddenMethod emitted = declaredMethod(target, callMethod) != null
            ? emittedSignature(target, callMethod, context, exact)
            : inheritedSignature(target, callMethod, context, exact);
        if (emitted == null || target == current && !(owner instanceof ClassTypeDef.Parameterized)) {
            // Within the definition itself, its variables are in scope
            return emitted;
        }
        // The variables of the calling method, and those of the calling definition outside a static method
        Set<String> inScope = new HashSet<>();
        if (caller != null) {
            caller.getTypeVariables().forEach(variable -> inScope.add(variable.name()));
        }
        if (current != null && (caller == null || !caller.getModifiers().contains(Modifier.STATIC))) {
            inScope.addAll(TypeHierarchy.declaring(current).getTypeParameters());
        }
        List<String> variables = TypeHierarchy.declaring(target).getTypeParameters();
        Function<TypeDef, TypeDef> asSeen;
        if (owner instanceof ClassTypeDef.Parameterized parameterized) {
            if (parameterized.typeArguments().size() != variables.size()) {
                return null;
            }
            Map<String, TypeDef> substitution = new HashMap<>();
            for (int i = 0; i < variables.size(); i++) {
                substitution.put(variables.get(i), asWritten(parameterized.typeArguments().get(i), inScope));
            }
            asSeen = type -> TypeHierarchy.substituted(type, substitution);
        } else {
            // The members of a raw type are erased
            TypeHierarchy.InheritedType declaring = TypeHierarchy.declaring(target);
            asSeen = variables.isEmpty() ? Function.identity() : declaring::erase;
        }
        List<TypeDef> parameterTypes = emitted.parameterTypes().stream().map(asSeen).toList();
        TypeDef returnType = asSeen.apply(emitted.returnType());
        // A wildcard the receiver binds a variable to is captured: a parameter of that type takes no value it can be
        // cast to
        if (parameterTypes.stream().anyMatch(type -> TypeHierarchy.unwrap(type) instanceof TypeDef.Wildcard)
            || TypeHierarchy.unwrap(returnType) instanceof TypeDef.Wildcard) {
            return null;
        }
        if (parameterTypes.stream().anyMatch(type -> TypeHierarchy.containsVariableOtherThan(type, inScope))
            || TypeHierarchy.containsVariableOtherThan(returnType, inScope)) {
            return null;
        }
        return new OverriddenMethod(parameterTypes, returnType);
    }

    /**
     * How a method reference is written where override resolution narrowed the referenced generated method: as a
     * lambda converting the values the functional interface passes to the narrowed parameter types, and the result
     * to the type the functional interface returns.
     *
     * @param owner     The type of the receiver, or {@code null}
     * @param current   The definition being written, or {@code null}
     * @param caller    The method the reference is written in, or {@code null}
     * @param reference The reference
     * @param context   The context of the file being written, or {@code null}
     * @param exact     Whether the source language overrides with the substituted types only
     * @return The conversions, or {@code null} where the reference is written as is
     */
    @Nullable
    public static ReferenceAdaptation adaptReference(@Nullable ClassTypeDef owner,
                                                     @Nullable ObjectDef current,
                                                     @Nullable MethodDef caller,
                                                     MethodReferenceExpression reference,
                                                     @Nullable VisitorContext context,
                                                     boolean exact) {
        MethodDef method = reference.method();
        OverriddenMethod emitted = emittedSignature(owner, current, caller, method, context, exact);
        if (emitted == null) {
            return null;
        }
        List<@Nullable TypeDef> argumentTypes = new ArrayList<>();
        boolean converted = false;
        for (int i = 0; i < emitted.parameterTypes().size(); i++) {
            TypeDef type = emitted.parameterTypes().get(i);
            boolean changed = !type.equals(method.getParameters().get(i).getType());
            argumentTypes.add(changed ? type : null);
            converted |= changed;
        }
        TypeDef resultType = null;
        TypeDef functionalReturn = functionalReturnType(reference.type());
        TypeDef returned = TypeHierarchy.unwrap(emitted.returnType());
        if (functionalReturn != null && !TypeDef.OBJECT.equals(functionalReturn) && !TypeDef.VOID.equals(functionalReturn)
            && !(returned instanceof TypeDef.Primitive) && !functionalReturn.equals(returned)) {
            // A primitive is unboxed from its wrapper, which the reference type is cast to
            resultType = functionalReturn instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : functionalReturn;
        }
        return converted || resultType != null ? new ReferenceAdaptation(argumentTypes, resultType) : null;
    }

    @Nullable
    private static TypeDef functionalReturnType(ClassTypeDef functionalInterface) {
        try {
            TypeDef type = TypeHierarchy.unwrap(functionalInterface.getLambda().getImplementation().getReturnType());
            return type instanceof ClassTypeDef || type instanceof TypeDef.Array || type instanceof TypeDef.Primitive
                || type instanceof TypeDef.TypeVariable ? type : null;
        } catch (RuntimeException e) {
            // A functional interface known only by name has no members to read
            return null;
        }
    }

    /**
     * A type argument as it is written where the call is: a variable out of scope is written as its bound.
     */
    private static TypeDef asWritten(TypeDef type, Set<String> inScope) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable && !inScope.contains(variable.name())) {
            return variable.bounds().isEmpty() ? TypeDef.OBJECT : asWritten(variable.bounds().get(0), inScope);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return TypeDef.parameterized(parameterized.rawType(), parameterized.typeArguments().stream()
                .map(argument -> asWritten(argument, inScope)).toList());
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return TypeDef.array(asWritten(array.componentType(), inScope), array.dimensions());
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            if (!wildcard.lowerBounds().isEmpty()) {
                return TypeDef.wildcardSupertypeOf(asWritten(wildcard.lowerBounds().get(0), inScope));
            }
            return wildcard.upperBounds().isEmpty() ? wildcard
                : TypeDef.wildcardSubtypeOf(asWritten(wildcard.upperBounds().get(0), inScope));
        }
        return type;
    }

    /**
     * The definition a method is invoked on, where it is generated: the one the owner names, or the one being written.
     *
     * @param owner   The type declaring the invoked method, or {@code null}
     * @param current The definition being written, or {@code null}
     * @return The definition, or {@code null} where the owner is no generated definition
     */
    @Nullable
    public static ObjectDef definitionOf(@Nullable ClassTypeDef owner, @Nullable ObjectDef current) {
        ClassTypeDef raw = owner;
        while (raw instanceof ClassTypeDef.Parameterized parameterized) {
            raw = parameterized.rawType();
        }
        if (raw instanceof ClassTypeDef.ClassDefType classDefType) {
            return classDefType.objectDef();
        }
        if (raw != null && current != null && raw.getName().equals(current.asTypeDef().getName())) {
            return current;
        }
        return null;
    }

    private static boolean isNarrower(TypeDef narrowerType,
                                      String narrower,
                                      String wider,
                                      @Nullable Function<String, @Nullable ClassElement> lookup) {
        if (TypeDef.OBJECT.getName().equals(wider)) {
            return true;
        }
        Class<?> narrowerClass = ClassUtils.forName(narrower, OverrideResolver.class.getClassLoader()).orElse(null);
        Class<?> widerClass = ClassUtils.forName(wider, OverrideResolver.class.getClassLoader()).orElse(null);
        if (narrowerClass != null && widerClass != null) {
            return widerClass.isAssignableFrom(narrowerClass);
        }
        // A type generated in this round cannot be loaded: its model or its element says what it inherits
        return TypeHierarchy.unwrap(narrowerType) instanceof ClassTypeDef narrowerClassType
            && TypeHierarchy.inherits(narrowerClassType, wider, lookup);
    }

    /**
     * The resolved signature that satisfies every inherited method the declared one overrides: one of
     * {@code A<Number>.get()} and {@code B<Integer>.get()} is implemented by {@code Integer get()}.
     *
     * @param found     The signatures resolved from each inherited method
     * @param hierarchy What the types relate through
     * @return The one whose return type is a subtype of all the others, or {@code null} where there is none
     */
    @Nullable
    private static OverriddenMethod mostSpecific(List<OverriddenMethod> found, Hierarchy hierarchy) {
        OverriddenMethod best = null;
        for (OverriddenMethod candidate : found) {
            if (best == null || isSubtype(candidate.returnType(), best.returnType(), hierarchy, 0)) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        for (OverriddenMethod other : found) {
            if (!isSubtype(best.returnType(), other.returnType(), hierarchy, 0)) {
                return null;
            }
        }
        return best;
    }

    private static boolean isSubtype(TypeDef subtype, TypeDef supertype, Hierarchy hierarchy, int depth) {
        TypeDef sub = TypeHierarchy.unwrap(subtype);
        TypeDef sup = TypeHierarchy.unwrap(supertype);
        if (sub.equals(sup) || TypeDef.OBJECT.equals(sup)) {
            return true;
        }
        if (sub instanceof TypeDef.TypeVariable subVariable && sup instanceof TypeDef.TypeVariable supVariable
            && subVariable.name().equals(supVariable.name())) {
            // One variable of the declaring type, whether a reference carries its bounds or not
            return true;
        }
        if (depth > MAX_DEPTH) {
            // A bound that refers to its own variable, `T extends Comparable<T>`, is not followed any further
            return false;
        }
        if (sub instanceof TypeDef.TypeVariable variable) {
            // A variable is a subtype of whatever its bound is: `V extends CharSequence` is a CharSequence
            // A reference names the variable without its bounds: the declaring type has all of them, where the
            // erasure keeps only the first - `V extends CharSequence & Serializable` is a Serializable too
            List<TypeDef> bounds = variable.bounds().isEmpty()
                ? hierarchy.declaringType().getBounds(variable.name()) : variable.bounds();
            return bounds.stream().anyMatch(bound -> isSubtype(bound, sup, hierarchy, depth + 1));
        }
        if (sub instanceof TypeDef.Array subArray) {
            if (sup instanceof TypeDef.Array supArray) {
                // Arrays of references are covariant: `String[]` is an `Object[]`, and `String[][]` is too
                if (subArray.dimensions() == supArray.dimensions()) {
                    TypeDef subComponent = subArray.componentType();
                    TypeDef supComponent = supArray.componentType();
                    return subComponent.isPrimitive() || supComponent.isPrimitive()
                        ? subComponent.equals(supComponent)
                        : isSubtype(subComponent, supComponent, hierarchy, depth + 1);
                }
                // The deeper elements are arrays, which are Objects, Cloneables and Serializables:
                // `int[][]` is a `Cloneable[]`
                return subArray.dimensions() > supArray.dimensions()
                    && (TypeDef.OBJECT.equals(supArray.componentType())
                    || supArray.componentType() instanceof ClassTypeDef supComponent
                    && !(supComponent instanceof ClassTypeDef.Parameterized)
                    && ARRAY_SUPERTYPES.contains(supComponent.getName()));
            }
            // Every array is Cloneable and Serializable as well
            return sup instanceof ClassTypeDef supClass && !(sup instanceof ClassTypeDef.Parameterized)
                && ARRAY_SUPERTYPES.contains(supClass.getName());
        }
        if (!(sub instanceof ClassTypeDef subClassType) || !(sup instanceof ClassTypeDef supClassType)) {
            // A variable as the supertype is only known to relate to itself
            return false;
        }
        Class<?> subClass = loaded(sub);
        Class<?> supClass = loaded(sup);
        if (subClass != null && supClass != null) {
            if (!supClass.isAssignableFrom(subClass)) {
                return false;
            }
            if (!(supClassType instanceof ClassTypeDef.Parameterized supParameterized)) {
                return true;
            }
            // A parameterized supertype contains the type arguments the subtype inherits it with:
            // `List<String>` is a `Collection<String>` and a `List<? extends CharSequence>`
            TypeDef asSupertype = asSupertype(subClassType, subClass, supClass);
            return asSupertype instanceof ClassTypeDef.Parameterized parameterized
                && containsArguments(supParameterized.typeArguments(), parameterized.typeArguments(), hierarchy, depth);
        }
        // A type generated in this round cannot be loaded: its model or its element says what it inherits, and with
        // which type arguments - `Child extends Parent<String>` is a `Parent<String>`
        String supName = TypeHierarchy.erasedName(sup);
        ClassTypeDef asSupertype = TypeHierarchy.asSupertype(subClassType, supName, hierarchy.lookup());
        if (asSupertype == null) {
            return false;
        }
        if (!(supClassType instanceof ClassTypeDef.Parameterized supParameterized)) {
            return true;
        }
        return asSupertype instanceof ClassTypeDef.Parameterized parameterized
            && containsArguments(supParameterized.typeArguments(), parameterized.typeArguments(), hierarchy, depth);
    }

    private static boolean containsArguments(List<TypeDef> declared,
                                             List<TypeDef> arguments,
                                             Hierarchy hierarchy,
                                             int depth) {
        if (declared.size() != arguments.size()) {
            return false;
        }
        for (int i = 0; i < declared.size(); i++) {
            if (!containsArgument(declared.get(i), arguments.get(i), hierarchy, depth)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a type argument lies within the declared one: the same type, or one within the bounds of a wildcard.
     */
    private static boolean containsArgument(TypeDef declared, TypeDef argument, Hierarchy hierarchy, int depth) {
        if (declared.equals(argument)) {
            return true;
        }
        if (!(declared instanceof TypeDef.Wildcard wildcard)) {
            return false;
        }
        if (argument instanceof TypeDef.Wildcard argumentWildcard) {
            List<TypeDef> upperBounds = argumentWildcard.upperBounds().isEmpty()
                ? List.of(TypeDef.OBJECT) : argumentWildcard.upperBounds();
            return wildcard.upperBounds().stream().allMatch(bound ->
                    upperBounds.stream().anyMatch(upper -> isSubtype(upper, bound, hierarchy, depth + 1)))
                && (wildcard.lowerBounds().isEmpty() || wildcard.lowerBounds().stream().allMatch(bound ->
                    argumentWildcard.lowerBounds().stream().anyMatch(lower -> isSubtype(bound, lower, hierarchy, depth + 1))));
        }
        return wildcard.upperBounds().stream().allMatch(bound -> isSubtype(argument, bound, hierarchy, depth + 1))
            && wildcard.lowerBounds().stream().allMatch(bound -> isSubtype(bound, argument, hierarchy, depth + 1));
    }

    /**
     * A type as one of its supertypes, with the type arguments it inherits that supertype with.
     */
    @Nullable
    private static TypeDef asSupertype(ClassTypeDef type, Class<?> typeClass, Class<?> supertypeClass) {
        Map<String, TypeDef> substitution = new HashMap<>();
        if (type instanceof ClassTypeDef.Parameterized parameterized) {
            java.lang.reflect.TypeVariable<?>[] variables = typeClass.getTypeParameters();
            for (int i = 0; i < variables.length && i < parameterized.typeArguments().size(); i++) {
                substitution.put(variables[i].getName(), parameterized.typeArguments().get(i));
            }
        }
        if (typeClass.equals(supertypeClass)) {
            return type;
        }
        List<Type> superTypes = new ArrayList<>();
        if (typeClass.getGenericSuperclass() != null) {
            superTypes.add(typeClass.getGenericSuperclass());
        }
        superTypes.addAll(Arrays.asList(typeClass.getGenericInterfaces()));
        for (Type superType : superTypes) {
            TypeDef converted = TypeHierarchy.substituted(TypeHierarchy.typeDefOf(superType), substitution);
            Class<?> raw = loaded(converted);
            if (converted instanceof ClassTypeDef superClassType && raw != null && supertypeClass.isAssignableFrom(raw)) {
                TypeDef found = asSupertype(superClassType, raw, supertypeClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Class<?> loaded(TypeDef type) {
        if (type instanceof ClassTypeDef.Parameterized parameterized) {
            return loaded(parameterized.rawType());
        }
        if (type instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        return ClassUtils.forName(TypeHierarchy.erasedName(type), OverrideResolver.class.getClassLoader()).orElse(null);
    }

    @Nullable
    private static OverriddenMethod overriddenBy(Declared declared,
                                                 TypeHierarchy.InheritedType type,
                                                 TypeHierarchy.InheritedMethod inherited) {
        MethodDef methodDef = declared.methodDef();
        // A raw supertype has erased members, which the declared erased method already overrides; its variables are
        // bound to nothing, however the declaring type names its own
        if (type.isRaw()) {
            return null;
        }
        if (!inherited.name().equals(methodDef.getName())
            || inherited.overrideParameters().size() != declared.parameterErasures().size()
            || inherited.finalMethod()
            || (inherited.packagePrivate() && !type.getPackageName().equals(declared.objectDef().getPackageName()))) {
            return null;
        }
        // The declared method is the erasure of the inherited declaration. The erased parameters are compared: an
        // element of a parameterized supertype can report its parameters with the type arguments already bound
        List<String> declarationErasure = inherited.bridgeParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(type.erase(parameter))).toList();
        if (!declarationErasure.equals(declared.parameterErasures())) {
            return null;
        }
        // A variable the inherited method declares of its own is renamed by the substitution, so it is never taken
        // for one of the declaring type
        Set<String> visibleVariables = declared.variables();
        Set<String> withMethodVariables = new HashSet<>(visibleVariables);
        inherited.typeVariables().forEach(variable ->
            withMethodVariables.add(TypeHierarchy.InheritedType.methodVariable(variable.name())));
        List<TypeDef> substitutedParameters = new ArrayList<>(declarationErasure.size());
        // A Java method overrides a generic one with the erasure of its signature, as a member of the supertype:
        // `String echo(String, Object)` for `<U> T echo(T, U)` of a `Parent<String>`. Kotlin cannot
        boolean erasedSignature = false;
        for (int i = 0; i < declarationErasure.size(); i++) {
            TypeDef substituted = type.substitute(inherited.overrideParameters().get(i), inherited.typeVariables());
            if (TypeHierarchy.containsVariableOtherThan(substituted, visibleVariables)) {
                if (declared.exact() || TypeHierarchy.containsVariableOtherThan(substituted, withMethodVariables)) {
                    return null;
                }
                erasedSignature = true;
            }
            substitutedParameters.add(substituted);
        }
        // For Java, only a substitution that changes an erasure - where the bytecode writer adds a bridge - needs the
        // resolved parameters. A difference in type arguments alone (a raw `Set` for `Set<Class<?>>`) is a valid
        // override as is, and keeping it leaves the body assigning to the declared, raw types
        boolean changed = false;
        List<TypeDef> parameterTypes = new ArrayList<>(declarationErasure.size());
        for (int i = 0; i < declarationErasure.size(); i++) {
            TypeDef substituted = substitutedParameters.get(i);
            TypeDef declaredType = methodDef.getParameters().get(i).getType();
            if (TypeHierarchy.containsVariableOtherThan(substituted, visibleVariables)) {
                parameterTypes.add(declaredType);
                continue;
            }
            TypeDef erased = type.erase(substituted, declared.declaringType());
            boolean parameterChanged = declared.exact()
                ? !sameType(substituted, declaredType)
                : !TypeHierarchy.erasedName(erased).equals(declarationErasure.get(i));
            changed |= parameterChanged;
            // A Java parameter of the same erasure is a valid override as declared - a raw `List` for `List<T>` -
            // and the body is written against it; only a changed erasure takes the substituted type
            parameterTypes.add(!parameterChanged ? declaredType : erasedSignature ? erased : substituted);
        }
        TypeDef returnType = methodDef.getReturnType();
        String declarationReturnErasure = TypeHierarchy.erasedName(type.erase(inherited.returnType()));
        if (!declarationReturnErasure.equals(declared.returnErasure())
            && isNarrower(type.erase(inherited.returnType()), declarationReturnErasure, declared.returnErasure(),
                declared.lookup())
            && !(TypeHierarchy.unwrap(inherited.returnType()) instanceof TypeDef.Primitive)) {
            // A wider return cannot implement a narrower one, which another supertype may need erased: the narrower
            // one is a constraint on the return type as well - `Integer get()` next to `A<Number>.get()`, or next to
            // `A<T extends Number>.get()` erased to `Number get()`
            TypeDef substituted = type.substitute(inherited.genericReturnType(), inherited.typeVariables());
            if (TypeHierarchy.containsVariableOtherThan(substituted, visibleVariables)) {
                return null;
            }
            return new OverriddenMethod(parameterTypes, erasedSignature ? type.erase(substituted, declared.declaringType()) : substituted);
        }
        if (declarationReturnErasure.equals(declared.returnErasure())
            && !(TypeHierarchy.unwrap(returnType) instanceof TypeDef.Primitive)) {
            TypeDef substituted = type.substitute(inherited.genericReturnType(), inherited.typeVariables());
            if (TypeHierarchy.containsVariableOtherThan(substituted, visibleVariables)) {
                // The erasure is what the model declares
                return changed && erasedSignature ? new OverriddenMethod(parameterTypes, returnType) : null;
            }
            // A return type has to be a subtype of the substituted one: the erasure of a type variable is not,
            // even where it is the same class
            boolean returnChanged = declared.exact()
                ? !sameType(substituted, returnType)
                : isVariableOrArrayOfVariable(substituted)
                || !TypeHierarchy.erasedName(type.erase(substituted, declared.declaringType())).equals(declarationReturnErasure);
            if (returnChanged) {
                changed = true;
                returnType = substituted;
            }
        }
        return changed ? new OverriddenMethod(parameterTypes, returnType) : null;
    }

    private static boolean sameType(TypeDef substituted, TypeDef declared) {
        return TypeHierarchy.unwrap(substituted).equals(TypeHierarchy.unwrap(declared));
    }

    private static boolean isVariableOrArrayOfVariable(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.Array array) {
            return isVariableOrArrayOfVariable(array.componentType());
        }
        return unwrapped instanceof TypeDef.TypeVariable;
    }

    private record Declared(ObjectDef objectDef,
                            MethodDef methodDef,
                            List<String> parameterErasures,
                            String returnErasure,
                            TypeHierarchy.InheritedType declaringType,
                            Set<String> variables,
                            boolean exact,
                            @Nullable Function<String, @Nullable ClassElement> lookup) {
    }

    /**
     * The signature of an overridden generic method with the type arguments of the supertype substituted.
     *
     * @param parameterTypes The parameter types
     * @param returnType     The return type
     */
    public record OverriddenMethod(List<TypeDef> parameterTypes, TypeDef returnType) {

        /**
         * The declared method with this signature, keeping everything else it declares, so that the body is rendered
         * against the resolved types.
         *
         * @param methodDef The declared method
         * @return The method
         */
        public MethodDef apply(MethodDef methodDef) {
            MethodDef.MethodDefBuilder builder = MethodDef.builder(methodDef.getName())
                .addModifiers(methodDef.getModifiers())
                .addAnnotations(methodDef.getAnnotations())
                .addJavadoc(methodDef.getJavadoc())
                .synthetic(methodDef.isSynthetic())
                .addThrows(methodDef.getThrowTypes())
                .returns(returnType)
                .addStatements(methodDef.getStatements())
                .overrides();
            for (int i = 0; i < parameterTypes.size(); i++) {
                ParameterDef parameter = methodDef.getParameters().get(i);
                builder.addParameter(ParameterDef.builder(parameter.getName(), parameterTypes.get(i))
                    .addModifiers(parameter.getModifiers())
                    .addAnnotations(parameter.getAnnotations())
                    .addJavadoc(parameter.getJavadoc())
                    .synthetic(parameter.isSynthetic())
                    .build());
            }
            return builder.build();
        }
    }

    /**
     * The conversions a method reference to a narrowed method is written with.
     *
     * @param argumentTypes The type each value the functional interface passes is cast to, or {@code null} where it
     *                      is passed as is
     * @param resultType    The type the result is cast to, or {@code null} where it is returned as is
     */
    public record ReferenceAdaptation(List<@Nullable TypeDef> argumentTypes, @Nullable TypeDef resultType) {
    }

    /**
     * What return types relate through: the elements of types only known by name, and the declaring type, whose
     * type variables a resolved return type can name.
     *
     * @param lookup        Looks up the element of a type only known by name, or {@code null}
     * @param declaringType The declaring type
     */
    private record Hierarchy(@Nullable Function<String, @Nullable ClassElement> lookup,
                             TypeHierarchy.InheritedType declaringType) {
    }
}
