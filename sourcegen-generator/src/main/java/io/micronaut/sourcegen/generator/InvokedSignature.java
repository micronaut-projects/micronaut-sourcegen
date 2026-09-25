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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;

/**
 * The signature an invoked method declares, which the erased model of the call does not carry: its generic
 * parameter types, and whether it takes varargs.
 *
 * @param parameterTypes The generic parameter types
 * @param varargs        Whether the last parameter takes varargs
 * @since 2.3
 */
@Internal
public record InvokedSignature(List<TypeDef> parameterTypes, boolean varargs) {

    /**
     * The generic parameter types the invoked method declares, which the erased signature of the model does not
     * carry. Resolved by loading the type, and failing that through the compiler.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model, which name the overload
     * @param scope          The scope of the file being written, which looks up a type the class loader lacks
     * @return The signature, or {@code null} where the method cannot be resolved
     */
    @Nullable
    public static InvokedSignature resolve(@Nullable ClassTypeDef owner,
                                           String methodName,
                                           List<TypeDef> parameterTypes,
                                           GenerationScope scope) {
        if (owner == null) {
            return null;
        }
        // A variable of the method is the erasure of its first bound in the descriptor: `T[]` of a
        // `<T extends Number>` is a `Number[]`
        List<String> erasures = parameterTypes.stream().map(type -> TypeHierarchy.erasedName(TypeOperations.erase(type))).toList();
        TypeLookup lookup = scope.typeLookup();
        // The class is read first: its reflective signature is the one the other calls of the file are resolved with
        Class<?> loaded = lookup.loadClass(owner);
        if (loaded != null) {
            Executable executable = findExecutable(loaded, methodName, erasures);
            return executable == null ? null : new InvokedSignature(
                Arrays.stream(executable.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList(),
                executable.isVarArgs());
        }
        ClassElement element = lookup.classElement(owner.getName());
        if (element == null) {
            return null;
        }
        return element.getEnclosedElements(ElementQuery.ALL_METHODS.named(methodName)).stream()
            .filter(method -> erasures.equals(Arrays.stream(method.getParameters())
                .map(parameter -> erasedNameOf(parameter.getType())).toList()))
            .findFirst()
            .map(method -> new InvokedSignature(Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false))
                .toList(), method.isVarArgs()))
            .orElse(null);
    }

    @Nullable
    private static Executable findExecutable(Class<?> type, String methodName, List<String> erasures) {
        if (MethodDef.CONSTRUCTOR.equals(methodName)) {
            return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> matches(constructor, erasures)).findFirst().orElse(null);
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Executable found = Arrays.stream(current.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName) && matches(method, erasures))
                .findFirst().orElse(null);
            if (found != null) {
                return found;
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                found = findExecutable(interfaceType, methodName, erasures);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Whether the erased parameter types name the same overload, so that a method with several of them is not
     * read from the wrong one.
     */
    private static boolean matches(Executable executable, List<String> erasures) {
        // `getTypeName` writes an array as `java.lang.String[]`, the form the erasures are in, where `getName` does not
        return Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).toList().equals(erasures);
    }

    /**
     * The erased name of a compiler type in the form {@link TypeHierarchy#erasedName(TypeDef)} writes it: the
     * element of an array names its component, and counts its dimensions apart.
     */
    private static String erasedNameOf(ClassElement type) {
        String name = type.getName();
        return type.isArray() && !name.endsWith("[]") ? name + "[]".repeat(type.getArrayDimensions()) : name;
    }
}
