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
import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The signatures of the compiled methods a generated source invokes, as javac sees them: the generic types they
 * declare, which the erased model does not carry, read from the method itself - never from a bridge, which declares
 * the erasure of another method's signature.
 *
 * @since 2.3
 */
@Internal
final class JavaSignatures {

    private JavaSignatures() {
    }

    /**
     * The signature the invoked method declares, looked up with the context of the file being written.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model
     * @param scope          The scope of the file being written
     * @return The signature, or {@code null} where the method cannot be resolved
     */
    @Nullable
    static InvokedSignature resolve(@Nullable ClassTypeDef owner, String methodName, List<TypeDef> parameterTypes,
                                    GenerationScope scope) {
        Executable executable = executable(owner, methodName, parameterTypes);
        if (executable != null) {
            return new InvokedSignature(Arrays.stream(executable.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList(),
                executable.isVarArgs());
        }
        return JavaTypes.loaded(owner, scope.typeLookup()) != null ? null : InvokedSignature.resolve(owner, methodName, parameterTypes, scope);
    }

    /**
     * The compiled method or constructor of the model.
     *
     * @param owner          The type declaring it, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model
     * @return The method, or {@code null} where the owner is not loaded or declares no such method
     */
    @Nullable
    static Executable executable(@Nullable ClassTypeDef owner, String methodName, List<TypeDef> parameterTypes) {
        Class<?> type = JavaTypes.loaded(owner);
        if (type == null) {
            return null;
        }
        List<String> erasures = parameterTypes.stream().map(parameter -> TypeHierarchy.erasedName(TypeOperations.erase(parameter))).toList();
        if (MethodDef.CONSTRUCTOR.equals(methodName)) {
            return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> matches(constructor, erasures)).findFirst().orElse(null);
        }
        return find(type, methodName, erasures, new HashSet<>());
    }

    /**
     * @param owner  The type declaring the method, or {@code null}
     * @param method The method of the model
     * @return The compiled method, or {@code null}
     */
    @Nullable
    static Executable executable(@Nullable ClassTypeDef owner, MethodDef method) {
        return executable(owner, method.getName(), method.getParameters().stream().map(ParameterDef::getType).toList());
    }

    @Nullable
    private static Executable find(Class<?> type, String methodName, List<String> erasures, Set<Class<?>> visited) {
        for (Class<?> current = type; current != null && visited.add(current); current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                // A bridge declares the erasure of the signature of the method it calls
                if (!method.isBridge() && !method.isSynthetic() && method.getName().equals(methodName) && matches(method, erasures)) {
                    return method;
                }
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                Executable found = find(interfaceType, methodName, erasures, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The bounds of the variables an invoked method declares: those of the model, and those the compiled method
     * declares, which a model built by reflection does not carry.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model
     * @param declared       The variables of the method in the model
     * @return The bounds by the names of the variables
     */
    static Map<String, List<TypeDef>> methodVariables(@Nullable ClassTypeDef owner,
                                                      @Nullable String methodName,
                                                      @Nullable List<TypeDef> parameterTypes,
                                                      List<TypeDef.TypeVariable> declared) {
        Map<String, List<TypeDef>> variables = new HashMap<>();
        declared.forEach(variable -> variables.put(variable.name(), variable.bounds()));
        Executable executable = methodName == null || parameterTypes == null ? null : executable(owner, methodName, parameterTypes);
        if (executable != null) {
            for (java.lang.reflect.TypeVariable<?> variable : executable.getTypeParameters()) {
                variables.putIfAbsent(variable.getName(), Arrays.stream(variable.getBounds()).map(TypeHierarchy::typeDefOf).toList());
            }
        }
        return variables;
    }

    /**
     * Whether the owner declares an instance method a static method reference of the model could also name: {@code
     * Integer::toString} of a {@code Function<Integer, String>} names {@code toString()} and {@code toString(int)}.
     *
     * @param owner  The type the reference names
     * @param method The static method of the model
     * @return true if the reference is ambiguous
     */
    static boolean hasInstanceMethodForReference(ClassTypeDef owner, MethodDef method) {
        Class<?> type = JavaTypes.loaded(owner);
        if (type == null || method.getParameters().isEmpty()) {
            return false;
        }
        int arity = method.getParameters().size() - 1;
        return Arrays.stream(type.getMethods()).anyMatch(candidate -> candidate.getName().equals(method.getName())
            && !Modifier.isStatic(candidate.getModifiers()) && candidate.getParameterCount() == arity);
    }

    private static boolean matches(Executable executable, List<String> erasures) {
        // `getTypeName` writes an array as `java.lang.String[]`, the form the erasures are in
        return Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).toList().equals(erasures);
    }
}
