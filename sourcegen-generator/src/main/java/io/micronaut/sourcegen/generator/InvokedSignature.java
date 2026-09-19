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
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The signature an invoked method declares, which the erased model of the call does not carry: its generic
 * parameter types, and whether it takes varargs.
 *
 * @param parameterTypes The generic parameter types
 * @param varargs        Whether the last parameter takes varargs
 * @since 2.2
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
     * @param context        The context of the file being written, or {@code null}
     * @return The signature, or {@code null} where the method cannot be resolved
     */
    @Nullable
    public static InvokedSignature resolve(@Nullable ClassTypeDef owner,
                                           String methodName,
                                           List<TypeDef> parameterTypes,
                                           @Nullable VisitorContext context) {
        if (owner == null) {
            return null;
        }
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        Class<?> loaded = loaded(owner);
        if (loaded != null) {
            Executable executable = findExecutable(loaded, methodName, erasures);
            return executable == null ? null : new InvokedSignature(
                Arrays.stream(executable.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList(),
                executable.isVarArgs());
        }
        MethodElement method = findMethodElement(owner, methodName, erasures, context);
        return method == null ? null : new InvokedSignature(Arrays.stream(method.getParameters())
            .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false))
            .toList(), method.isVarArgs());
    }

    /**
     * The exceptions the invoked method or constructor declares. Resolved by loading the type, and failing that
     * through the compiler.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param parameterTypes The parameter types of the method in the model, which name the overload
     * @param context        The context of the file being written, or {@code null}
     * @return The exception types, empty where it declares none, or {@code null} where the method cannot be resolved
     * @since 2.2.2
     */
    @Nullable
    public static List<TypeDef> thrownTypes(@Nullable ClassTypeDef owner,
                                            String methodName,
                                            List<TypeDef> parameterTypes,
                                            @Nullable VisitorContext context) {
        if (owner == null) {
            return null;
        }
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        Class<?> loaded = loaded(owner);
        if (loaded != null) {
            Executable executable = findExecutable(loaded, methodName, erasures);
            return executable == null ? null
                : Arrays.stream(executable.getGenericExceptionTypes()).map(TypeHierarchy::typeDefOf).toList();
        }
        MethodElement method = findMethodElement(owner, methodName, erasures, context);
        return method == null ? null : Arrays.stream(method.getThrownTypes()).map(TypeDef::of).toList();
    }

    /**
     * The type variables the invoked method declares that its result is typed by, which javac infers from the
     * arguments and the target type at once: the {@code T} of {@code Optional.ofNullable(T)}, returned as an
     * {@code Optional<String>}.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model, which name the overload
     * @param context        The context of the file being written, or {@code null}
     * @return The names of the variables, empty where the result names none, or where the method cannot be resolved
     * @since 2.2.2
     */
    public static Set<String> inferredResultVariables(@Nullable ClassTypeDef owner,
                                                      String methodName,
                                                      List<TypeDef> parameterTypes,
                                                      @Nullable VisitorContext context) {
        if (owner == null || MethodDef.CONSTRUCTOR.equals(methodName)) {
            return Set.of();
        }
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        Class<?> loaded = loaded(owner);
        Set<String> named = new HashSet<>();
        if (loaded != null) {
            if (findExecutable(loaded, methodName, erasures) instanceof Method method && method.getTypeParameters().length > 0) {
                collectVariables(method.getGenericReturnType(), method, named);
            }
            return named;
        }
        MethodElement method = findMethodElement(owner, methodName, erasures, context);
        if (method != null) {
            Set<String> declared = new HashSet<>();
            method.getDeclaredTypeVariables().forEach(variable -> declared.add(variable.getVariableName()));
            if (!declared.isEmpty()) {
                collectVariables(method.getGenericReturnType(), declared, named, new HashSet<>());
            }
        }
        return named;
    }

    private static void collectVariables(Type type, Method declaring, Set<String> named) {
        switch (type) {
            case TypeVariable<?> variable -> {
                if (variable.getGenericDeclaration().equals(declaring)) {
                    named.add(variable.getName());
                }
            }
            case ParameterizedType parameterized -> {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    collectVariables(argument, declaring, named);
                }
            }
            case GenericArrayType array -> collectVariables(array.getGenericComponentType(), declaring, named);
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    collectVariables(bound, declaring, named);
                }
                for (Type bound : wildcard.getLowerBounds()) {
                    collectVariables(bound, declaring, named);
                }
            }
            default -> {
            }
        }
    }

    private static void collectVariables(ClassElement type, Set<String> declared, Set<String> named, Set<ClassElement> visited) {
        if (!visited.add(type)) {
            return;
        }
        if (type.isArray()) {
            collectVariables(type.fromArray(), declared, named, visited);
            return;
        }
        if (type instanceof GenericPlaceholderElement placeholder && declared.contains(placeholder.getVariableName())) {
            named.add(placeholder.getVariableName());
            return;
        }
        type.getTypeArguments().values().forEach(argument -> collectVariables(argument, declared, named, visited));
    }

    @Nullable
    private static Class<?> loaded(ClassTypeDef owner) {
        return owner instanceof ClassTypeDef.JavaClass javaClass ? javaClass.type()
            : ClassUtils.forName(owner.getName(), InvokedSignature.class.getClassLoader()).orElse(null);
    }

    @Nullable
    private static MethodElement findMethodElement(ClassTypeDef owner,
                                                   String methodName,
                                                   List<String> erasures,
                                                   @Nullable VisitorContext context) {
        if (context == null) {
            return null;
        }
        return context.getClassElement(owner.getName())
            .flatMap(element -> element.getEnclosedElements(ElementQuery.ALL_METHODS.named(methodName)).stream()
                .filter(method -> erasures.equals(Arrays.stream(method.getParameters())
                    .map(parameter -> erasedNameOf(parameter.getType())).toList()))
                .findFirst())
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
