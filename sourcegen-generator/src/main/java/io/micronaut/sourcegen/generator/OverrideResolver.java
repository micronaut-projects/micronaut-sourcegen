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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * @since 2.3
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
     * @param scope     The scope of the file being written, which looks up the supertypes only known by name
     * @return The substituted signature, or {@code null} when Java source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           GenerationScope scope) {
        return resolve(objectDef, methodDef, scope, false);
    }

    /**
     * Resolves the substituted signature of an erased override.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @param scope     The scope of the file being written, which looks up the supertypes only known by name
     * @param exact     Whether the source language overrides with the substituted types only, as Kotlin does,
     *                  rather than accepting their erasures and raw types as Java does
     * @return The substituted signature, or {@code null} when the source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           GenerationScope scope,
                                           boolean exact) {
        if (objectDef == null || !methodDef.isOverride() || methodDef.isConstructor()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)
            || TypeHierarchy.superTypesOf(objectDef).isEmpty()) {
            return null;
        }
        // The variables of an inner class's enclosing classes are in its scope
        List<TypeDef.TypeVariable> enclosingVariables = scope.enclosingVariables(objectDef);
        TypeHierarchy.InheritedType declaringType = TypeHierarchy.declaring(objectDef, enclosingVariables);
        // A reference to a variable of the method by its name alone erases to the bound the method declares it with -
        // Object for one declared without, whatever a variable of the class of the same name is bounded by
        Map<String, TypeDef> methodVariables = TypeHierarchy.methodScope(methodDef.getTypeVariables());
        List<String> parameterErasures = methodDef.getParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(declaringType.erase(
                TypeOperations.substitute(parameter.getType(), methodVariables)), objectDef)).toList();
        String returnErasure = TypeHierarchy.erasedName(declaringType.erase(
            TypeOperations.substitute(methodDef.getReturnType(), methodVariables)), objectDef);
        TypeHierarchy.Lookup lookup = scope.hierarchyLookup();
        Set<String> variables = new HashSet<>(declaringType.getTypeParameters());
        enclosingVariables.forEach(variable -> variables.add(variable.name()));
        Declared declared = new Declared(objectDef, methodDef, parameterErasures, returnErasure, declaringType,
            variables, exact, lookup);
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
     * The type a record component is written with, where the accessor it implies overrides a generic inherited
     * method with the erasure of that method's signature: an {@code Object value} of a record implementing
     * {@code T value()} of a {@code Source<String>} is a {@code String value}. Java requires the accessor of a
     * component to return the type of the component, so the accessor is narrowed by narrowing the component.
     *
     * @param recordDef The record
     * @param property  The component
     * @param scope     The scope of the file being written
     * @param exact     Whether the source language overrides with the substituted types only
     * @return The type, or {@code null} where the component is written as the model declares it
     */
    @Nullable
    public static TypeDef recordComponentType(RecordDef recordDef,
                                              PropertyDef property,
                                              GenerationScope scope,
                                              boolean exact) {
        if (recordDef.getMethods().stream().anyMatch(method -> method.getName().equals(property.getName())
            && method.getParameters().isEmpty())) {
            // An accessor the model declares is resolved as any other method
            return null;
        }
        MethodDef accessor = MethodDef.builder(property.getName()).addModifiers(Modifier.PUBLIC).overrides()
            .returns(property.getType()).build();
        OverriddenMethod overridden = resolve(recordDef, accessor, scope, exact);
        return overridden == null || overridden.returnType().equals(property.getType()) ? null : overridden.returnType();
    }

    /**
     * The signature a method of the definition is written with, where override resolution changes it.
     */
    @Nullable
    private static OverriddenMethod emittedSignature(ObjectDef objectDef,
                                                    MethodDef callMethod,
                                                    GenerationScope scope,
                                                    boolean exact) {
        MethodDef method = declaredMethod(objectDef, callMethod);
        return method == null ? null : resolve(objectDef, method, scope, exact);
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
                                                       GenerationScope scope,
                                                       boolean exact,
                                                       Map<ObjectDef, Optional<OverriddenMethod>> inherited) {
        // A definition reached again along another path of the hierarchy - an interface two supertypes extend -
        // inherits the method as it did the first time: each is walked once, which also ends a cycle
        Optional<OverriddenMethod> known = inherited.get(objectDef);
        if (known != null) {
            return known.orElse(null);
        }
        inherited.put(objectDef, Optional.empty());
        List<TypeDef> supertypes = new ArrayList<>();
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            supertypes.add(classDef.getSuperclass());
        }
        supertypes.addAll(objectDef.getSuperinterfaces());
        OverriddenMethod result = null;
        for (TypeDef supertype : supertypes) {
            if (supertype instanceof ClassTypeDef classType && scope.definitionOf(classType) != null) {
                result = emittedSignature(classType, objectDef, null, callMethod, scope, exact, inherited);
                if (result != null) {
                    break;
                }
            }
        }
        inherited.put(objectDef, Optional.ofNullable(result));
        return result;
    }

    /**
     * The signature a generated method is written with, as the receiver of the call sees it: with the type arguments
     * the receiver binds the definition's variables to - `apply(String)` on a `GenericTarget<String>`.
     *
     * @param owner      The type of the receiver, or {@code null}
     * @param current    The definition being written, or {@code null}
     * @param caller     The method the call is written in, or {@code null}
     * @param callMethod The invoked method, as the model calls it
     * @param scope      The scope of the file being written
     * @param exact      Whether the source language overrides with the substituted types only
     * @return The signature, or {@code null} where the method is written as the model declares it, or its types
     * cannot be expressed where it is called
     */
    @Nullable
    public static OverriddenMethod emittedSignature(@Nullable ClassTypeDef owner,
                                                    @Nullable ObjectDef current,
                                                    @Nullable MethodDef caller,
                                                    MethodDef callMethod,
                                                    GenerationScope scope,
                                                    boolean exact) {
        return emittedSignature(owner, current, caller, callMethod, scope, exact, new IdentityHashMap<>());
    }

    @Nullable
    private static OverriddenMethod emittedSignature(@Nullable ClassTypeDef owner,
                                                     @Nullable ObjectDef current,
                                                     @Nullable MethodDef caller,
                                                     MethodDef callMethod,
                                                     GenerationScope scope,
                                                     boolean exact,
                                                     Map<ObjectDef, Optional<OverriddenMethod>> inherited) {
        ObjectDef target = scope.definitionOf(owner, current);
        if (target == null) {
            return null;
        }
        OverriddenMethod emitted = declaredMethod(target, callMethod) != null
            ? emittedSignature(target, callMethod, scope, exact)
            : inheritedSignature(target, callMethod, scope, exact, inherited);
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
            scope.enclosingVariables(current).forEach(variable -> inScope.add(variable.name()));
        }
        List<String> variables = TypeHierarchy.declaring(target).getTypeParameters();
        Function<TypeDef, TypeDef> asSeen;
        // A member of a parameterized type binds the variables of its enclosing types as well: `Outer<String>.Inner`
        Map<String, TypeDef> substitution = new HashMap<>();
        if (owner != null) {
            // The arguments of its own class are the parameterization's, which are bound below
            ClassTypeDef ownerClass = owner instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : owner;
            TypeHierarchy.typeArguments(ownerClass, scope.hierarchyLookup())
                .forEach((name, argument) -> substitution.put(name, asWritten(argument, inScope)));
        }
        if (owner instanceof ClassTypeDef.Parameterized parameterized) {
            if (parameterized.typeArguments().size() != variables.size()) {
                return null;
            }
            for (int i = 0; i < variables.size(); i++) {
                substitution.put(variables.get(i), asWritten(parameterized.typeArguments().get(i), inScope));
            }
            asSeen = type -> TypeOperations.substitute(type, substitution);
        } else if (!variables.isEmpty()) {
            // The members of a raw type are erased
            asSeen = TypeHierarchy.declaring(target)::erase;
        } else {
            asSeen = substitution.isEmpty() ? Function.identity() : type -> TypeOperations.substitute(type, substitution);
        }
        List<TypeDef> parameterTypes = emitted.parameterTypes().stream().map(asSeen).toList();
        TypeDef returnType = asSeen.apply(emitted.returnType());
        // A wildcard the receiver binds a variable to is captured: a parameter of that type takes no value it can be
        // cast to, and a result of it is of the wildcard's upper bound, or else of the variable's
        if (parameterTypes.stream().anyMatch(type -> TypeOperations.unwrap(type) instanceof TypeDef.Wildcard)) {
            return null;
        }
        if (TypeOperations.unwrap(returnType) instanceof TypeDef.Wildcard wildcard) {
            returnType = wildcard.lowerBounds().isEmpty() && !wildcard.upperBounds().isEmpty()
                && !TypeDef.OBJECT.equals(wildcard.upperBounds().get(0))
                ? wildcard.upperBounds().get(0) : capturedBound(target, emitted.returnType(), substitution, inScope);
        }
        if (parameterTypes.stream().anyMatch(type -> TypeOperations.containsVariableOtherThan(type, inScope))
            || TypeOperations.containsVariableOtherThan(returnType, inScope)) {
            return null;
        }
        return new OverriddenMethod(parameterTypes, returnType);
    }

    /**
     * The bound of a variable a wildcard is captured for, with the type arguments of the receiver substituted:
     * {@code T extends A} of a {@code Target<String, ?>} is a String.
     */
    private static TypeDef capturedBound(ObjectDef target,
                                         TypeDef variable,
                                         Map<String, TypeDef> substitution,
                                         Set<String> inScope) {
        TypeHierarchy.InheritedType declaring = TypeHierarchy.declaring(target);
        if (!(TypeOperations.unwrap(variable) instanceof TypeDef.TypeVariable typeVariable)) {
            return declaring.erase(variable);
        }
        List<TypeDef> bounds = declaring.getBounds(typeVariable.name());
        if (bounds.isEmpty()) {
            return TypeDef.OBJECT;
        }
        TypeDef bound = TypeOperations.unwrap(TypeOperations.substitute(bounds.get(0), substitution));
        if (bound instanceof TypeDef.Wildcard wildcard) {
            if (wildcard.lowerBounds().isEmpty() && !wildcard.upperBounds().isEmpty()
                && !TypeDef.OBJECT.equals(wildcard.upperBounds().get(0))) {
                bound = wildcard.upperBounds().get(0);
            } else if (TypeOperations.unwrap(bounds.get(0)) instanceof TypeDef.TypeVariable boundVariable
                && !boundVariable.name().equals(typeVariable.name())) {
                // A bound naming another variable captured itself - `T extends A` of a `Target<?, ?>` - is that
                // variable's bound
                return capturedBound(target, boundVariable, substitution, inScope);
            } else {
                bound = TypeDef.OBJECT;
            }
        }
        // A bound the receiver binds keeps its type arguments - `T extends List<A>` of a `Target<String, ?>`, and
        // the variables the caller declares
        return TypeOperations.containsVariableOtherThan(bound, inScope) ? declaring.erase(bound) : bound;
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
     * @param scope     The scope of the file being written
     * @param exact     Whether the source language overrides with the substituted types only
     * @return The conversions, or {@code null} where the reference is written as is
     */
    @Nullable
    public static ReferenceAdaptation adaptReference(@Nullable ClassTypeDef owner,
                                                     @Nullable ObjectDef current,
                                                     @Nullable MethodDef caller,
                                                     MethodReferenceExpression reference,
                                                     GenerationScope scope,
                                                     boolean exact) {
        MethodDef method = reference.method();
        OverriddenMethod emitted = emittedSignature(owner, current, caller, method, scope, exact);
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
        MethodDef functional = functionalMethod(reference.type());
        TypeDef functionalReturn = functional == null ? null : TypeOperations.unwrap(functional.getReturnType());
        if (functionalReturn instanceof TypeDef.Wildcard wildcard) {
            // `Supplier<? extends String>` returns a String
            functionalReturn = wildcard.lowerBounds().isEmpty() && !wildcard.upperBounds().isEmpty()
                ? TypeOperations.unwrap(wildcard.upperBounds().get(0)) : null;
        }
        if (!(functionalReturn instanceof ClassTypeDef || functionalReturn instanceof TypeDef.Array
            || functionalReturn instanceof TypeDef.Primitive || functionalReturn instanceof TypeDef.TypeVariable)) {
            functionalReturn = null;
        }
        TypeDef returned = TypeOperations.unwrap(emitted.returnType());
        if (functionalReturn != null && !TypeDef.OBJECT.equals(functionalReturn) && !TypeDef.VOID.equals(functionalReturn)
            && !(returned instanceof TypeDef.Primitive) && !functionalReturn.equals(returned)) {
            // A primitive is unboxed from its wrapper, which the reference type is cast to
            resultType = functionalReturn instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : functionalReturn;
        }
        // For Java, a value that is not an `Object` is cast to a raw type: a parameterization does not convert to
        // another. Kotlin casts to the parameterization
        List<List<TypeDef>> argumentConversions = new ArrayList<>(argumentTypes.size());
        argumentTypes.forEach(type -> argumentConversions.add(List.of()));
        if (!exact && functional != null && functional.getParameters().size() == argumentTypes.size()) {
            for (int i = 0; i < argumentTypes.size(); i++) {
                TypeDef type = argumentTypes.get(i);
                TypeDef passed = TypeOperations.unwrap(functional.getParameters().get(i).getType());
                if (type != null && !TypeDef.OBJECT.equals(passed)) {
                    argumentTypes.set(i, TypeOperations.raw(type));
                    // A primitive is boxed before it is cast to a variable, through a bound the box does not convert to
                    TypeDef boxed = passed instanceof TypeDef.Primitive primitive
                        && TypeOperations.unwrap(type) instanceof TypeDef.TypeVariable ? primitive.wrapperType() : null;
                    TypeDef bound = boundConversion(type, boxed == null ? passed : boxed, current, caller, scope);
                    List<TypeDef> conversions = new ArrayList<>();
                    if (bound != null) {
                        conversions.add(bound);
                    }
                    if (boxed != null) {
                        conversions.add(boxed);
                    }
                    argumentConversions.set(i, conversions);
                }
            }
        }
        if (converted || resultType != null) {
            return new ReferenceAdaptation(argumentTypes, argumentConversions,
                resultType == null || exact ? resultType : TypeOperations.raw(resultType),
                exact || resultType == null ? null : boundConversion(resultType, returned, current, caller, scope));
        }
        return null;
    }

    /**
     * The raw bound a Java value is converted to before it is cast to a variable: a parameterized bound does not
     * relate to another parameterization - `List<String>` to `U extends List<Object>`.
     */
    @Nullable
    private static TypeDef boundConversion(TypeDef variable,
                                           TypeDef value,
                                           @Nullable ObjectDef current,
                                           @Nullable MethodDef method,
                                           GenerationScope scope) {
        // A value of a variable is of its own bounds
        List<TypeDef> values = TypeOperations.unwrap(value) instanceof TypeDef.TypeVariable
            ? upperBounds(value, current, method) : List.of(TypeOperations.unwrap(value));
        // Every bound of an intersection has to accept the value
        for (TypeDef bound : upperBounds(variable, current, method)) {
            if (!(bound instanceof ClassTypeDef.Parameterized parameterized)) {
                continue;
            }
            for (TypeDef candidate : values) {
                // A class inheriting the bound is of the type arguments it inherits it with
                TypeDef inherited = inheritedAs(candidate, parameterized, scope);
                if (inherited instanceof ClassTypeDef.Parameterized && !parameterized.equals(inherited)) {
                    return parameterized.rawType();
                }
            }
        }
        return null;
    }

    /**
     * A value type as the class a parameterized bound is of, with the type arguments it inherits it with -
     * `GenericList<String> extends ArrayList<String>` as a `List<String>`.
     *
     * @param value  The value type
     * @param bound  The bound
     * @param scope  The scope of the file being written, which looks up the types only known by name
     * @return The type, or {@code null} where the value does not inherit the bound's class
     */
    @Nullable
    public static TypeDef inheritedAs(TypeDef value,
                                      ClassTypeDef.Parameterized bound,
                                      GenerationScope scope) {
        return value instanceof ClassTypeDef classType
            ? TypeOperations.asSupertype(classType, bound.rawType().getName(), scope.hierarchyLookup()) : null;
    }

    /**
     * The type arguments a receiver binds the variables of the class declaring the invoked method with: the receiver's
     * type as that class, with the type arguments it inherits it with - through generated and compiled supertypes
     * alike, and none through a raw one (JLS 4.8) - and those its enclosing types bind, for a member of a
     * parameterized type.
     *
     * @param owner      The type of the receiver, or {@code null}
     * @param current    The definition being written, or {@code null}
     * @param callMethod The invoked method
     * @param scope      The scope of the file being written
     * @return The type arguments by the variables they bind, empty where there are none
     */
    public static Map<String, TypeDef> receiverArguments(@Nullable ClassTypeDef owner,
                                                         @Nullable ObjectDef current,
                                                         MethodDef callMethod,
                                                         GenerationScope scope) {
        if (owner == null) {
            return Map.of();
        }
        // The definitions of the file alone: a compiled supertype is read reflectively
        TypeHierarchy.Lookup lookup = scope.definitionsLookup();
        ObjectDef target = scope.definitionOf(owner, current);
        ClassTypeDef receiver = owner;
        String declaring = callMethod.isConstructor() ? null
            : declaringTypeName(owner, current, callMethod, new HashSet<>(), scope);
        if (target != null && target == current && !(owner instanceof ClassTypeDef.Parameterized)) {
            if (declaring == null || declaring.equals(target.getName())) {
                // Its own variables are bound to themselves
                return Map.of();
            }
            // Within the definition itself, the receiver is of its own variables, which are in scope
            List<String> variables = TypeHierarchy.declaring(target).getTypeParameters();
            if (!variables.isEmpty()) {
                receiver = TypeDef.parameterized(owner, variables.stream().<TypeDef>map(TypeDef::variable).toList());
            }
        }
        ClassTypeDef asDeclaring = declaring == null ? null : TypeOperations.asSupertype(receiver, declaring, lookup);
        return TypeHierarchy.typeArguments(asDeclaring == null ? receiver : asDeclaring, lookup);
    }

    /**
     * The binary name of the class declaring the invoked method: the type itself, or a generated or compiled
     * supertype of it, through the superclass first.
     */
    @Nullable
    private static String declaringTypeName(ClassTypeDef type,
                                            @Nullable ObjectDef current,
                                            MethodDef callMethod,
                                            Set<String> visited,
                                            GenerationScope scope) {
        ObjectDef definition = scope.definitionOf(TypeHierarchy.memberClass(TypeOperations.rawClass(type)), current);
        if (definition == null) {
            definition = scope.definitionsLookup().definition(TypeOperations.rawClass(type).getName());
        }
        if (definition != null) {
            if (declaredMethod(definition, callMethod) != null) {
                return definition.getName();
            }
            // A hierarchy of any depth is followed: the visited types end a cycle
            if (!visited.add(definition.getName())) {
                return null;
            }
            for (TypeDef supertype : TypeHierarchy.superTypesOf(definition)) {
                if (TypeOperations.unwrap(supertype) instanceof ClassTypeDef classType) {
                    String found = declaringTypeName(classType, null, callMethod, visited, scope);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
        Class<?> loaded = loaded(type);
        return loaded == null ? null : declaringClassName(loaded, callMethod, visited);
    }

    @Nullable
    private static String declaringClassName(Class<?> type, MethodDef callMethod, Set<String> visited) {
        if (!visited.add(type.getName())) {
            return null;
        }
        if (Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> declaresInvoked(method, callMethod))) {
            return type.getName();
        }
        List<Class<?>> supertypes = new ArrayList<>();
        if (type.getSuperclass() != null) {
            supertypes.add(type.getSuperclass());
        }
        supertypes.addAll(Arrays.asList(type.getInterfaces()));
        for (Class<?> supertype : supertypes) {
            String found = declaringClassName(supertype, callMethod, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Whether a compiled method is the one invoked: of its name, of the invoked method's type variables where it
     * declares any - the model calls a generic method by its erasure too - with a variable where the invoked
     * method has one, and the erasure of its other parameters - not an unrelated overload of the same arity.
     */
    private static boolean declaresInvoked(Method method, MethodDef callMethod) {
        if (!method.getName().equals(callMethod.getName())
            || method.getParameterCount() != callMethod.getParameters().size()
            || method.isSynthetic()) {
            // A bridge only has the erasure of the method it stands for
            return false;
        }
        if (callMethod.getTypeVariables().isEmpty()) {
            // An erased call: the erased parameters name the method
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (!TypeHierarchy.erasedName(TypeOperations.erase(callMethod.getParameters().get(i).getType()))
                    .equals(TypeHierarchy.erasedName(TypeDef.of(method.getParameterTypes()[i])))) {
                    return false;
                }
            }
            return true;
        }
        if (method.getTypeParameters().length != callMethod.getTypeVariables().size()) {
            return false;
        }
        for (int i = 0; i < method.getTypeParameters().length; i++) {
            // The variables are bounded alike: `<U extends Number>` is another method than `<U extends X>`
            TypeDef.TypeVariable invoked = callMethod.getTypeVariables().get(i);
            Type[] bounds = method.getTypeParameters()[i].getBounds();
            String declared = bounds.length == 0 ? Object.class.getName() : TypeOperations.erase(bounds[0]).getName();
            Class<?> invokedBound = TypeLookup.reflective().loadClass(TypeHierarchy.erasedName(TypeOperations.erase(invoked)));
            if (invokedBound != null && !invokedBound.getName().equals(declared)) {
                return false;
            }
        }
        for (int i = 0; i < method.getParameterCount(); i++) {
            TypeDef invoked = TypeOperations.unwrap(callMethod.getParameters().get(i).getType());
            Type declared = method.getGenericParameterTypes()[i];
            if (invoked instanceof TypeDef.Array array && TypeOperations.unwrap(array.componentType()) instanceof TypeDef.TypeVariable) {
                // An array of a variable, whatever the variable erases to
                if (!(declared instanceof java.lang.reflect.GenericArrayType)) {
                    return false;
                }
                continue;
            }
            boolean matches = invoked instanceof TypeDef.TypeVariable
                ? declared instanceof java.lang.reflect.TypeVariable<?>
                : TypeHierarchy.erasedName(invoked instanceof ClassTypeDef.Parameterized parameterized
                ? parameterized.rawType() : invoked).equals(TypeHierarchy.erasedName(TypeDef.of(method.getParameterTypes()[i])));
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bounds of a type variable that are no variables themselves, in declaration order, with the bounds of the
     * variables a bound names in its place - `U extends V` with `V extends Number` is bounded by `Number`.
     *
     * @param type    The type
     * @param current The definition being written, or {@code null}
     * @param method  The method being written, or {@code null}
     * @return The bounds, empty where the type is no variable or an unbounded one
     */
    public static List<TypeDef> upperBounds(TypeDef type, @Nullable ObjectDef current, @Nullable MethodDef method) {
        if (!(TypeOperations.unwrap(type) instanceof TypeDef.TypeVariable variable)) {
            return List.of();
        }
        // A reference carries the bounds of the variable, else they are those the method declares it with, or else
        // those of the definition
        List<TypeDef.TypeVariable> methodVariables = method == null ? List.of() : method.getTypeVariables();
        List<TypeDef.TypeVariable> typeVariables = TypeOperations.typeVariablesOf(current);
        List<TypeDef> result = new ArrayList<>();
        for (TypeDef bound : TypeOperations.expandBounds(variable, reference -> {
            if (!reference.bounds().isEmpty()) {
                return reference;
            }
            List<TypeDef> bounds = TypeOperations.declaredBounds(reference.name(), methodVariables, List.of());
            return TypeDef.variable(reference.name(), bounds.isEmpty()
                ? TypeOperations.declaredBounds(reference.name(), List.of(), typeVariables) : bounds);
        })) {
            TypeDef unwrapped = TypeOperations.unwrap(bound);
            if (!TypeDef.OBJECT.equals(unwrapped)) {
                result.add(unwrapped);
            }
        }
        return result;
    }

    @Nullable
    private static MethodDef functionalMethod(ClassTypeDef functionalInterface) {
        try {
            return functionalInterface.getLambda().getImplementation();
        } catch (RuntimeException e) {
            // A functional interface known only by name has no members to read
            return null;
        }
    }

    /**
     * A type argument as it is written where the call is: a variable out of scope is written as its bound.
     */
    private static TypeDef asWritten(TypeDef type, Set<String> inScope) {
        TypeDef unwrapped = TypeOperations.unwrap(type);
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

    private static boolean isNarrower(TypeDef narrowerType,
                                      String narrower,
                                      String wider,
                                      TypeHierarchy.Lookup lookup) {
        if (TypeDef.OBJECT.getName().equals(wider)) {
            return true;
        }
        Class<?> narrowerClass = TypeLookup.reflective().loadClass(narrower);
        Class<?> widerClass = TypeLookup.reflective().loadClass(wider);
        if (narrowerClass != null && widerClass != null) {
            return widerClass.isAssignableFrom(narrowerClass);
        }
        // A type generated in this round cannot be loaded: its model or its element says what it inherits
        return TypeOperations.unwrap(narrowerType) instanceof ClassTypeDef narrowerClassType
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
        TypeDef sub = TypeOperations.unwrap(subtype);
        TypeDef sup = TypeOperations.unwrap(supertype);
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
            // `List<String>` is a `Collection<String>` and a `List<? extends CharSequence>`, and a member of a
            // parameterized type those its enclosing types bind - `Box<String>.Getter` is a `Supplier<String>`
            TypeDef asSupertype = TypeOperations.asSupertype(subClassType, supClass.getName(), hierarchy.lookup());
            return asSupertype instanceof ClassTypeDef.Parameterized parameterized
                && containsArguments(supParameterized.typeArguments(), parameterized.typeArguments(), hierarchy, depth);
        }
        // A type generated in this round cannot be loaded: its model or its element says what it inherits, and with
        // which type arguments - `Child extends Parent<String>` is a `Parent<String>`
        String supName = TypeHierarchy.erasedName(sup);
        ClassTypeDef asSupertype = TypeOperations.asSupertype(subClassType, supName, hierarchy.lookup());
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

    @Nullable
    private static Class<?> loaded(TypeDef type) {
        return TypeLookup.reflective().loadClass(type);
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
            || (inherited.packagePrivate() && !type.getPackageName().equals(TypeHierarchy.packageOf(declared.objectDef())))) {
            return null;
        }
        List<TypeDef.TypeVariable> inheritedVariables = inherited.typeVariables();
        // The inherited parameters with the type arguments substituted, and the variables the inherited method
        // declares of its own renamed, so that they are never taken for one of the declaring type
        TypeHierarchy.MethodVariables inheritedScope = type.shadowedBy(inheritedVariables);
        List<TypeDef> parameters = inherited.overrideParameters().stream().map(inheritedScope::substitute).toList();
        // The declared method is the erasure of the inherited declaration. The erased parameters are compared: an
        // element of a parameterized supertype can report its parameters with the type arguments already bound
        List<String> declarationErasure = inherited.bridgeParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(type.erase(parameter))).toList();
        // Or it declares the substituted parameters already, next to an erased return - `Object apply(String)` of a
        // `Function<String, String>` - which the bytecode writer bridges as well
        List<String> substitutedErasure = parameters.stream()
            .map(parameter -> TypeHierarchy.erasedName(type.erase(parameter, declared.declaringType()))).toList();
        if (!declarationErasure.equals(declared.parameterErasures()) && !substitutedErasure.equals(declared.parameterErasures())) {
            return null;
        }
        if (!methodDef.getTypeVariables().isEmpty() && methodDef.getTypeVariables().size() != inheritedVariables.size()) {
            return null;
        }
        TypeDef genericReturn = inheritedScope.substitute(inherited.genericReturnType());
        OwnVariables own = OwnVariables.of(declared, inheritedScope, inherited, parameters, genericReturn);
        // A variable the inherited method declares of its own is renamed by the substitution, so it is never taken
        // for one of the declaring type
        Set<String> visibleVariables = new HashSet<>(declared.variables());
        own.resolved().forEach(variable -> visibleVariables.add(variable.name()));
        Set<String> withMethodVariables = new HashSet<>(visibleVariables);
        inheritedVariables.forEach(variable -> withMethodVariables.add(inheritedScope.nameOf(variable.name())));
        // A variable of the method erases to its bound, with the type arguments substituted
        Map<String, TypeDef> methodScope = TypeHierarchy.methodScope(own.resolved());
        Function<TypeDef, TypeDef> erasure = substituted -> type.erase(TypeOperations.substitute(substituted, methodScope),
            declared.declaringType());
        List<TypeDef> substitutedParameters = new ArrayList<>(declarationErasure.size());
        // A Java method overrides a generic one with the erasure of its signature, as a member of the supertype:
        // `String echo(String, Object)` for `<U> T echo(T, U)` of a `Parent<String>`. Kotlin cannot
        boolean erasedSignature = false;
        for (TypeDef parameter : parameters) {
            TypeDef substituted = TypeOperations.substitute(parameter, own.references());
            if (TypeOperations.containsVariableOtherThan(substituted, visibleVariables)) {
                if (declared.exact() || TypeOperations.containsVariableOtherThan(substituted, withMethodVariables)) {
                    return null;
                }
                erasedSignature = true;
            }
            substitutedParameters.add(substituted);
        }
        // For Java, only a substitution that changes an erasure - where the bytecode writer adds a bridge - needs the
        // resolved parameters. A difference in type arguments alone (a raw `Set` for `Set<Class<?>>`) is a valid
        // override as is, and keeping it leaves the body assigning to the declared, raw types. The declared method
        // is taken with its variables renamed where they are
        boolean changed = own.changes();
        List<TypeDef> parameterTypes = new ArrayList<>(declarationErasure.size());
        for (int i = 0; i < declarationErasure.size(); i++) {
            TypeDef substituted = substitutedParameters.get(i);
            TypeDef declaredType = own.rename(methodDef.getParameters().get(i).getType());
            TypeDef erased = erasure.apply(substituted);
            boolean parameterChanged = declared.exact()
                ? !sameType(substituted, declaredType)
                : !TypeHierarchy.erasedName(erased).equals(declared.parameterErasures().get(i));
            changed |= parameterChanged;
            // A Java parameter of the same erasure is a valid override as declared - a raw `List` for `List<T>` -
            // and the body is written against it; only a changed erasure takes the substituted type
            parameterTypes.add(!parameterChanged ? declaredType : erasedSignature ? erased : substituted);
        }
        TypeDef returnType = own.rename(methodDef.getReturnType());
        String declarationReturnErasure = TypeHierarchy.erasedName(type.erase(inherited.returnType()));
        if (!declarationReturnErasure.equals(declared.returnErasure())
            && isNarrower(type.erase(inherited.returnType()), declarationReturnErasure, declared.returnErasure(),
                declared.lookup())
            && !(TypeOperations.unwrap(inherited.returnType()) instanceof TypeDef.Primitive)) {
            // A wider return cannot implement a narrower one, which another supertype may need erased: the narrower
            // one is a constraint on the return type as well - `Integer get()` next to `A<Number>.get()`, or next to
            // `A<T extends Number>.get()` erased to `Number get()`
            TypeDef substituted = TypeOperations.substitute(genericReturn, own.references());
            if (TypeOperations.containsVariableOtherThan(substituted, visibleVariables)) {
                if (declared.exact() || TypeOperations.containsVariableOtherThan(substituted, withMethodVariables)) {
                    return null;
                }
                // A variable of the inherited method, which the erased override does not declare, is written as its
                // erasure: `Outer.Member make()` for `<X> Outer<X>.Member make()`
                return own.method(parameterTypes, erasure.apply(substituted));
            }
            return own.method(parameterTypes, erasedSignature ? erasure.apply(substituted) : substituted);
        }
        if (declarationReturnErasure.equals(declared.returnErasure())
            && !(TypeOperations.unwrap(returnType) instanceof TypeDef.Primitive)) {
            TypeDef substituted = TypeOperations.substitute(genericReturn, own.references());
            if (TypeOperations.containsVariableOtherThan(substituted, visibleVariables)) {
                // A variable of the method is written as its erasure
                TypeDef erased = erasure.apply(substituted);
                if (!TypeHierarchy.erasedName(erased).equals(declarationReturnErasure)) {
                    return own.method(parameterTypes, erased);
                }
                return changed ? own.method(parameterTypes, returnType) : null;
            }
            // A return type has to be a subtype of the substituted one: the erasure of a type variable is not,
            // even where it is the same class
            boolean returnChanged = declared.exact()
                ? !sameType(substituted, returnType)
                : isVariableOrArrayOfVariable(substituted)
                || !TypeHierarchy.erasedName(erasure.apply(substituted)).equals(declarationReturnErasure);
            if (returnChanged) {
                changed = true;
                returnType = substituted;
            }
        }
        if (!declarationReturnErasure.equals(declared.returnErasure())
            && !(TypeOperations.unwrap(returnType) instanceof TypeDef.Primitive)) {
            // A return between the erasure and the type argument - `CharSequence get()` of a `Supplier<String>` -
            // implements the inherited method only as the type argument
            TypeDef substituted = TypeOperations.substitute(genericReturn, own.references());
            Hierarchy hierarchy = new Hierarchy(declared.lookup(), declared.declaringType());
            if (!TypeOperations.containsVariableOtherThan(substituted, visibleVariables)
                && !isSubtype(returnType, substituted, hierarchy, 0) && isSubtype(substituted, returnType, hierarchy, 0)) {
                return own.method(parameterTypes, substituted);
            }
        }
        return changed ? own.method(parameterTypes, returnType) : null;
    }

    /**
     * Whether two types are the same, a variable known by its name whatever bounds a reference to it carries.
     */
    private static boolean sameType(TypeDef substituted, TypeDef declared) {
        TypeDef left = TypeOperations.unwrap(substituted);
        TypeDef right = TypeOperations.unwrap(declared);
        if (left instanceof TypeDef.TypeVariable leftVariable && right instanceof TypeDef.TypeVariable rightVariable) {
            return leftVariable.name().equals(rightVariable.name()) && leftVariable.isNullable() == rightVariable.isNullable();
        }
        if (left instanceof ClassTypeDef.Parameterized leftType && right instanceof ClassTypeDef.Parameterized rightType) {
            return sameType(leftType.rawType(), rightType.rawType()) && sameTypes(leftType.typeArguments(), rightType.typeArguments());
        }
        if (left instanceof TypeDef.Array leftArray && right instanceof TypeDef.Array rightArray) {
            return leftArray.dimensions() == rightArray.dimensions() && leftArray.isNullable() == rightArray.isNullable()
                && sameType(leftArray.componentType(), rightArray.componentType());
        }
        if (left instanceof TypeDef.Wildcard leftWildcard && right instanceof TypeDef.Wildcard rightWildcard) {
            return sameTypes(leftWildcard.upperBounds(), rightWildcard.upperBounds())
                && sameTypes(leftWildcard.lowerBounds(), rightWildcard.lowerBounds());
        }
        return left.equals(right);
    }

    private static boolean sameTypes(List<TypeDef> left, List<TypeDef> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameType(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVariableOrArrayOfVariable(TypeDef type) {
        TypeDef unwrapped = TypeOperations.unwrap(type);
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
                            TypeHierarchy.Lookup lookup) {
    }

    /**
     * The variables a declared generic method overrides those of the inherited method with, in their order: with the
     * bounds the inherited method declares them with, the type arguments substituted - {@code U extends T} of a
     * {@code Conv<Number>} is {@code U extends Number} - and renamed where a type argument names a variable of the
     * declaring type of the same name, which the method's own would capture: the {@code U} a method {@code m(T, U)}
     * of an {@code Api<List<U>>} declares is renamed to {@code V}, as the class's {@code U} is named by {@code T}.
     *
     * @param declared   The declared method
     * @param resolved   The variables, as the resolved method declares them
     * @param references The renamed variables of the inherited method by the reference to the declared one each is
     * @param renamed    The new names of the declared variables that are renamed
     * @param changes    Whether the declared variables are renamed, or bounded otherwise
     */
    private record OwnVariables(MethodDef declared,
                                List<TypeDef.TypeVariable> resolved,
                                Map<String, TypeDef> references,
                                Map<String, String> renamed,
                                boolean changes) {

        static OwnVariables of(Declared declared,
                               TypeHierarchy.MethodVariables inheritedScope,
                               TypeHierarchy.InheritedMethod inherited,
                               List<TypeDef> parameters,
                               TypeDef genericReturn) {
            MethodDef methodDef = declared.methodDef();
            List<TypeDef.TypeVariable> own = methodDef.getTypeVariables();
            if (own.isEmpty()) {
                return new OwnVariables(methodDef, List.of(), Map.of(), Map.of(), false);
            }
            List<TypeDef.TypeVariable> inheritedVariables = inherited.typeVariables();
            List<List<TypeDef>> bounds = inheritedVariables.stream()
                .map(variable -> variable.bounds().stream().map(inheritedScope::substitute).toList())
                .toList();
            // The variables of the declaring type the type arguments name
            Set<String> named = new HashSet<>();
            parameters.forEach(parameter -> named.addAll(TypeOperations.variablesOf(parameter)));
            named.addAll(TypeOperations.variablesOf(genericReturn));
            bounds.forEach(variableBounds -> variableBounds.forEach(bound -> named.addAll(TypeOperations.variablesOf(bound))));
            named.removeIf(inheritedScope::isMethodVariable);
            Set<String> taken = new HashSet<>(named);
            taken.addAll(declared.variables());
            own.forEach(variable -> taken.add(variable.name()));
            Map<String, String> renamed = new HashMap<>();
            for (TypeDef.TypeVariable variable : own) {
                if (named.contains(variable.name())) {
                    String name = freshName(variable.name(), taken);
                    taken.add(name);
                    renamed.put(variable.name(), name);
                }
            }
            Map<String, TypeDef> references = new HashMap<>();
            for (int i = 0; i < own.size(); i++) {
                references.put(inheritedScope.nameOf(inheritedVariables.get(i).name()),
                    TypeDef.variable(renamed.getOrDefault(own.get(i).name(), own.get(i).name())));
            }
            List<TypeDef.TypeVariable> resolved = new ArrayList<>(own.size());
            boolean changes = !renamed.isEmpty();
            for (int i = 0; i < own.size(); i++) {
                List<TypeDef> resolvedBounds = bounds.get(i).stream()
                    .map(bound -> TypeOperations.substitute(bound, references)).toList();
                if (resolvedBounds.size() == 1 && TypeDef.OBJECT.equals(TypeOperations.unwrap(resolvedBounds.get(0)))) {
                    resolvedBounds = List.of();
                }
                TypeDef.TypeVariable variable = own.get(i);
                List<TypeDef> declaredBounds = variable.bounds().stream().map(bound -> rename(bound, renamed)).toList();
                changes |= !sameTypes(resolvedBounds, declaredBounds);
                resolved.add(new TypeDef.TypeVariable(renamed.getOrDefault(variable.name(), variable.name()), resolvedBounds,
                    variable.isNullable()));
            }
            return new OwnVariables(methodDef, resolved, references, renamed, changes);
        }

        /**
         * A name for a renamed variable that no variable in scope has: the next letter after a single letter, else
         * the name with a number.
         */
        private static String freshName(String name, Set<String> taken) {
            if (name.length() == 1 && Character.isUpperCase(name.charAt(0))) {
                for (char letter = (char) (name.charAt(0) + 1); letter <= 'Z'; letter++) {
                    if (!taken.contains(String.valueOf(letter))) {
                        return String.valueOf(letter);
                    }
                }
            }
            int index = 1;
            while (taken.contains(name + index)) {
                index++;
            }
            return name + index;
        }

        /**
         * A type the declared method names, with its renamed variables renamed.
         */
        TypeDef rename(TypeDef type) {
            return rename(type, renamed);
        }

        static TypeDef rename(TypeDef type, Map<String, String> renamed) {
            return renamed.isEmpty() ? type : TypeVariableRenaming.rename(type, renamed);
        }

        OverriddenMethod method(List<TypeDef> parameterTypes, TypeDef returnType) {
            return new OverriddenMethod(parameterTypes, returnType, own().isEmpty() ? null : resolved, renamed);
        }

        private List<TypeDef.TypeVariable> own() {
            return declared.getTypeVariables();
        }
    }

    /**
     * The signature of an overridden generic method with the type arguments of the supertype substituted.
     *
     * @param parameterTypes   The parameter types
     * @param returnType       The return type
     * @param typeVariables    The variables the method declares, with the bounds the overridden method declares them
     *                         with, or {@code null} to keep the declared ones
     * @param renamedVariables The new names of the variables of the declared method that are renamed, so that a type
     *                         argument naming a variable of the declaring type of the same name is not captured
     */
    public record OverriddenMethod(List<TypeDef> parameterTypes,
                                   TypeDef returnType,
                                   @Nullable List<TypeDef.TypeVariable> typeVariables,
                                   Map<String, String> renamedVariables) {

        /**
         * @param parameterTypes The parameter types
         * @param returnType     The return type
         */
        public OverriddenMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
            this(parameterTypes, returnType, null, Map.of());
        }

        /**
         * The declared method with this signature, keeping everything else it declares, so that the body is rendered
         * against the resolved types - and with the variables it declares renamed in the body as well.
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
                .addThrows(methodDef.getThrowTypes().stream().map(this::renamed).toList())
                .returns(returnType)
                .addStatements(renamedVariables.isEmpty() ? methodDef.getStatements()
                    : TypeVariableRenaming.rename(methodDef.getStatements(), renamedVariables))
                .overrides();
            (typeVariables == null ? methodDef.getTypeVariables() : typeVariables).forEach(builder::addTypeVariable);
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

        private TypeDef renamed(TypeDef type) {
            return renamedVariables.isEmpty() ? type : TypeVariableRenaming.rename(type, renamedVariables);
        }
    }

    /**
     * The conversions a method reference to a narrowed method is written with.
     *
     * @param argumentTypes The type each value the functional interface passes is cast to, or {@code null} where it
     *                      is passed as is
     * @param resultType    The type the result is cast to, or {@code null} where it is returned as is
     * @param argumentConversions The types each value is converted to before its argument type, the outer first:
     *                      the raw bound of a variable, and the box of a primitive
     * @param resultBound   The raw bound of a variable result type the result is converted to first, or
     *                      {@code null}
     */
    public record ReferenceAdaptation(List<@Nullable TypeDef> argumentTypes,
                                      List<List<TypeDef>> argumentConversions,
                                      @Nullable TypeDef resultType,
                                      @Nullable TypeDef resultBound) {
    }

    /**
     * What return types relate through: the definitions and elements of types only known by name, and the declaring
     * type, whose type variables a resolved return type can name.
     *
     * @param lookup        Looks up the definition or the element of a type only known by name
     * @param declaringType The declaring type
     */
    private record Hierarchy(TypeHierarchy.Lookup lookup, TypeHierarchy.InheritedType declaringType) {
    }
}
