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
import org.jspecify.annotations.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which method an invocation names. The bytecode binds the one of the model by its descriptor; a source language selects the most
 * specific one that takes the values as the source types them, so a value is cast to the parameter of the model
 * wherever another overload would take it - and to the type the receiver binds a variable of its class with.
 *
 * @since 2.3
 */
@Internal
public final class OverloadRules {

    private OverloadRules() {
    }

    /**
     * The parameter as the receiver sees it: a variable of the receiver's class is the type argument it is bound
     * with - the {@code E} of a {@code List<String>} is {@code String}, where the model has the erased {@code Object}.
     *
     * @param paramType         The parameter type of the model
     * @param declaredType      The type the invoked method declares, or {@code null} where it is not known
     * @param inferred          The variables the invoked method declares
     * @param receiverArguments The type arguments the receiver binds the variables of its class with
     * @return The parameter type as the receiver sees it
     */
    public static TypeDef receiverBound(TypeDef paramType,
                                 @Nullable TypeDef declaredType,
                                 List<TypeDef.TypeVariable> inferred,
                                 Map<String, TypeDef> receiverArguments) {
        TypeDef named = TypeOperations.unwrap(paramType) instanceof TypeDef.TypeVariable ? paramType : declaredType;
        if (named == null || !(TypeOperations.unwrap(named) instanceof TypeDef.TypeVariable variable)
            || inferred.stream().anyMatch(own -> own.name().equals(variable.name()))) {
            return paramType;
        }
        TypeDef bound = receiverArguments.get(variable.name());
        if (bound == null) {
            return paramType;
        }
        TypeDef unwrapped = TypeOperations.unwrap(bound);
        // A wildcard is captured: no value can be cast to it
        return unwrapped instanceof ClassTypeDef || unwrapped instanceof TypeDef.TypeVariable || unwrapped instanceof TypeDef.Array
            ? bound : paramType;
    }

    /**
     * Whether a value is cast to the parameter so that the overload of the model is the one selected.
     *
     * @param paramType  The parameter type
     * @param sourceType The type of the value in the source, or {@code null} for the {@code null} literal
     * @param inferred   The variables the invoked method declares
     * @return true if the value is cast
     */
    public static boolean pinsOverload(TypeDef paramType, @Nullable TypeDef sourceType, List<TypeDef.TypeVariable> inferred) {
        TypeDef param = TypeOperations.unwrap(paramType);
        if (param instanceof TypeDef.TypeVariable || param instanceof TypeDef.Wildcard
            || param instanceof TypeDef.Array array && TypeOperations.unwrap(array.componentType()) instanceof TypeDef.TypeVariable
            || !inferred.isEmpty() && TypeOperations.containsVariableOtherThan(paramType, Set.of())) {
            return false;
        }
        return sourceType == null || !TypeHierarchy.erasedName(paramType).equals(TypeHierarchy.erasedName(sourceType));
    }

    /**
     * The erasure a compiler selects an overload by for a variable named alone: that of the bound the method or the
     * class declares it with. The model resolves a call by name without that bound, taking the variable as an
     * {@code Object}, so a value of a {@code T extends Number} is cast to the {@code Object} of the model where
     * another overload takes a {@code Number} - as the bytecode writers call it.
     *
     * @param sourceType The type of the value in the source
     * @param objectDef  The definition the call is written in, or {@code null}
     * @param method     The method the call is written in, or {@code null}
     * @return The erased bound of a variable named alone, or of an array of one, else the type as it is
     */
    public static TypeDef lexicalErasure(TypeDef sourceType, @Nullable ObjectDef objectDef, @Nullable MethodDef method) {
        TypeDef unwrapped = TypeOperations.unwrap(sourceType);
        if (unwrapped instanceof TypeDef.Array array) {
            TypeDef component = lexicalErasure(array.componentType(), objectDef, method);
            return component == array.componentType() ? sourceType : TypeDef.array(component, array.dimensions());
        }
        if (!(unwrapped instanceof TypeDef.TypeVariable variable) || !variable.bounds().isEmpty()) {
            return sourceType;
        }
        List<TypeDef.TypeVariable> methodVariables = method == null ? List.of() : method.getTypeVariables();
        List<TypeDef.TypeVariable> typeVariables = TypeOperations.typeVariablesOf(objectDef);
        TypeDef erased = sourceType;
        Set<String> visited = new HashSet<>();
        while (TypeOperations.unwrap(erased) instanceof TypeDef.TypeVariable named && visited.add(named.name())) {
            List<TypeDef> bounds = named.bounds().isEmpty()
                ? TypeOperations.declaredBounds(named.name(), methodVariables, typeVariables) : named.bounds();
            if (bounds.isEmpty()) {
                return sourceType;
            }
            erased = bounds.getFirst();
        }
        return TypeOperations.unwrap(erased) instanceof TypeDef.TypeVariable ? sourceType : erased;
    }

    /**
     * Whether the owner declares another method of the name that takes the values as the source types them.
     *
     * @param owner          The type the method is invoked on
     * @param definition     The definition of the owner, where it is generated
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method of the model
     * @param sourceTypes    The types of the values in the source, {@code null} for the {@code null} literal
     * @param scope          The scope of the file being written
     * @return true if another overload is applicable
     */
    public static boolean hasApplicableOverload(@Nullable ClassTypeDef owner,
                                         @Nullable ObjectDef definition,
                                         String methodName,
                                         List<TypeDef> parameterTypes,
                                         List<@Nullable TypeDef> sourceTypes,
                                         GenerationScope scope) {
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        List<List<@Nullable Class<?>>> candidates = new ArrayList<>();
        if (definition != null) {
            generatedCandidates(definition, methodName, erasures, candidates, new HashSet<>(), true, scope);
        } else if (owner != null) {
            Class<?> type = loaded(owner);
            if (type != null) {
                compiledCandidates(type, methodName, erasures, candidates);
            } else if (TypeOperations.rawClass(owner) instanceof ClassTypeDef.ClassElementType elementType && !MethodDef.CONSTRUCTOR.equals(methodName)) {
                // A type being compiled, which no class loader has: the compiler describes its methods
                elementCandidates(elementType.classElement(), methodName, erasures, candidates);
            } else {
                return false;
            }
        }
        List<@Nullable Class<?>> modelled = parameterTypes.stream().<@Nullable Class<?>>map(OverloadRules::loaded).toList();
        return candidates.stream().anyMatch(candidate -> {
            if (definition == null && moreSpecific(modelled, candidate)) {
                // Of the two applicable methods the one of the model is the more specific - `of(E...)` taking a
                // `Class[]` rather than `of(E)` - which the source selects without a cast. A generated method can be
                // written narrower than the model declares it, overriding `apply(Object)` as `apply(String)`
                return false;
            }
            for (int i = 0; i < candidate.size(); i++) {
                if (!takes(candidate.get(i), sourceTypes.get(i))) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * The methods of a generated definition and those it inherits, generated or compiled, with the parameters a
     * source writes them with: an override the resolution narrows takes the narrowed ones.
     */
    private static void generatedCandidates(ObjectDef definition,
                                            String methodName,
                                            List<String> erasures,
                                            List<List<@Nullable Class<?>>> candidates,
                                            Set<String> visited,
                                            boolean own,
                                            GenerationScope scope) {
        if (!visited.add(definition.asTypeDef().getName())) {
            return;
        }
        for (MethodDef method : definition.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameters().size() != erasures.size()
                || !own && (method.isConstructor() || method.getModifiers().contains(Modifier.PRIVATE))) {
                continue;
            }
            // Resolved against the definitions of the file alone, not the elements of the context
            OverrideResolver.OverriddenMethod overridden = OverrideResolver.resolve(definition, method, scope.withoutVisitorContext());
            List<TypeDef> written = overridden != null && overridden.parameterTypes().size() == erasures.size()
                ? overridden.parameterTypes() : method.getParameters().stream().map(ParameterDef::getType).toList();
            if (!written.stream().map(TypeHierarchy::erasedName).toList().equals(erasures)) {
                candidates.add(written.stream().<@Nullable Class<?>>map(OverloadRules::loaded).toList());
            }
        }
        if (MethodDef.CONSTRUCTOR.equals(methodName)) {
            return;
        }
        for (TypeDef superType : TypeHierarchy.superTypesOf(definition)) {
            if (!(TypeOperations.unwrap(superType) instanceof ClassTypeDef classType)) {
                continue;
            }
            ObjectDef inherited = scope.definitionOf(classType);
            if (inherited != null) {
                generatedCandidates(inherited, methodName, erasures, candidates, visited, false, scope);
            } else {
                Class<?> type = loaded(classType);
                if (type != null && visited.add(type.getName())) {
                    compiledCandidates(type, methodName, erasures, candidates);
                }
            }
        }
    }

    private static void elementCandidates(ClassElement element,
                                          String methodName,
                                          List<String> erasures,
                                          List<List<@Nullable Class<?>>> candidates) {
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS.named(methodName))) {
            ParameterElement[] parameters = method.getParameters();
            if (!method.getName().equals(methodName) || parameters.length != erasures.size()) {
                continue;
            }
            List<String> names = Arrays.stream(parameters).map(parameter -> {
                ClassElement type = parameter.getType();
                String name = type.getName();
                return type.isArray() && !name.endsWith("[]") ? name + "[]".repeat(type.getArrayDimensions()) : name;
            }).toList();
            if (!names.equals(erasures)) {
                candidates.add(names.stream().<@Nullable Class<?>>map(name -> loaded(TypeDef.of(name))).toList());
            }
        }
    }

    private static void compiledCandidates(Class<?> type,
                                           String methodName,
                                           List<String> erasures,
                                           List<List<@Nullable Class<?>>> candidates) {
        List<Executable> executables = new ArrayList<>();
        if (MethodDef.CONSTRUCTOR.equals(methodName)) {
            executables.addAll(Arrays.asList(type.getDeclaredConstructors()));
        } else {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                executables.addAll(Arrays.asList(current.getDeclaredMethods()));
            }
            executables.addAll(Arrays.asList(type.getMethods()));
            executables.removeIf(executable -> !executable.getName().equals(methodName));
        }
        for (Executable executable : executables) {
            if (executable.getParameterCount() == erasures.size() && !executable.isSynthetic()
                && !Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).toList().equals(erasures)) {
                candidates.add(Arrays.<Class<?>>asList(executable.getParameterTypes()));
            }
        }
    }

    /**
     * The methods of a definition without the bridges the model declares itself: an erased method that is resolved to
     * the signature of another one, which javac writes the bridge of.
     *
     * @param objectDef The definition
     * @param scope     The scope of the file being written
     * @return The methods to write
     */
    public static List<MethodDef> writtenMethods(ObjectDef objectDef, GenerationScope scope) {
        return writtenMethods(objectDef, scope, false);
    }

    /**
     * The methods of a definition without the bridges the model declares itself, as a source language that overrides
     * with the exact substituted signature, as Kotlin does, or with its erasure as Java does, resolves them.
     *
     * @param objectDef The definition
     * @param scope     The scope of the file being written
     * @param exact     Whether overrides take the substituted types only
     * @return The methods to write
     */
    public static List<MethodDef> writtenMethods(ObjectDef objectDef, GenerationScope scope, boolean exact) {
        Set<String> declared = new HashSet<>();
        objectDef.getMethods().forEach(method -> declared.add(method.getName() + method.getParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList()));
        return objectDef.getMethods().stream().filter(method -> {
            OverrideResolver.OverriddenMethod overridden = OverrideResolver.resolve(objectDef, method, scope, exact);
            if (overridden == null) {
                return true;
            }
            String own = method.getName() + method.getParameters().stream()
                .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList();
            String resolved = method.getName() + overridden.parameterTypes().stream()
                .map(type -> TypeHierarchy.erasedName(type, objectDef)).toList();
            if (!own.equals(resolved)) {
                // Another method is declared with the signature this one is resolved to
                return !declared.contains(resolved);
            }
            // Of two methods of one signature - they differ in the return type - the erased one is the bridge
            return objectDef.getMethods().stream().noneMatch(other -> other != method && other.getName().equals(method.getName())
                && own.equals(other.getName() + other.getParameters().stream()
                .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList())
                && OverrideResolver.resolve(objectDef, other, scope, exact) == null);
        }).toList();
    }

    /**
     * The variables a compiled class declares, as a raw receiver sees them: erased to their bounds.
     *
     * @param owner The type of the receiver
     * @return The erased bound of each variable, by its name
     */
    public static Map<String, TypeDef> erasedClassVariables(ClassTypeDef owner) {
        Map<String, TypeDef> erased = new java.util.HashMap<>();
        for (Class<?> type = loaded(owner); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.TypeVariable<?> variable : type.getTypeParameters()) {
                erased.putIfAbsent(variable.getName(), TypeDef.of(TypeOperations.erase(variable)));
            }
        }
        return erased;
    }

    private static boolean moreSpecific(List<@Nullable Class<?>> modelled, List<@Nullable Class<?>> candidate) {
        for (int i = 0; i < candidate.size(); i++) {
            Class<?> own = modelled.get(i);
            Class<?> other = candidate.get(i);
            if (own == null || other == null || !other.isAssignableFrom(own)) {
                return false;
            }
        }
        return true;
    }

    private static boolean takes(@Nullable Class<?> parameter, @Nullable TypeDef sourceType) {
        if (parameter == null) {
            // A type that cannot be loaded is taken to fit
            return true;
        }
        if (sourceType == null) {
            return !parameter.isPrimitive();
        }
        Class<?> source = loaded(sourceType);
        if (source == null) {
            return !parameter.isPrimitive();
        }
        if (parameter.isAssignableFrom(source)) {
            return true;
        }
        Class<?> boxedParameter = ReflectionUtils.getWrapperType(parameter);
        Class<?> boxedSource = ReflectionUtils.getWrapperType(source);
        // Boxing, unboxing and the primitive widenings, none of which is told apart here
        return boxedParameter.isAssignableFrom(boxedSource)
            || Number.class.isAssignableFrom(boxedParameter) && (Number.class.isAssignableFrom(boxedSource) || boxedSource == Character.class);
    }

    @Nullable
    private static Class<?> loaded(TypeDef type) {
        return TypeLookup.reflective().loadClass(type);
    }
}
