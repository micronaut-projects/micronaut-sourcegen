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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The calls of generic methods whose variables javac infers otherwise than the erased model assumes: from the
 * bounds alone as a receiver, and from the type the result is returned or stored as. The bytecode writers call the
 * erasure; the source takes the result raw, which is what it is.
 *
 * @since 2.3
 */
@Internal
final class JavaGenerics {

    private final JavaConversionRules conversions;
    private final GenerationScope scope;

    JavaGenerics(JavaConversionRules conversions, GenerationScope scope) {
        this.conversions = conversions;
        this.scope = scope;
    }

    /**
     * The raw type a generic result is cast to as a receiver, where a variable of the method the arguments do not
     * name is inferred from its bounds alone - {@code Comparator.naturalOrder()} is a {@code Comparator<T>} of a
     * {@code T} no argument of {@code compare} converts to.
     *
     * @param receiver  The receiver, as written
     * @param objectDef The definition being written
     * @return The raw type, or {@code null} where the receiver is taken as it is
     */
    @Nullable
    TypeDef uninferredReceiver(ExpressionDef receiver, @Nullable ObjectDef objectDef) {
        Signature signature = signatureOf(receiver, objectDef);
        if (signature == null) {
            return null;
        }
        for (Map.Entry<String, List<TypeDef>> variable : signature.variables().entrySet()) {
            if (mentions(signature.returnType(), variable.getKey())
                && signature.parameterTypes().stream().noneMatch(parameter -> mentions(parameter, variable.getKey()))
                && variable.getValue().stream().anyMatch(bound -> !TypeDef.OBJECT.equals(bound))) {
                return TypeOperations.raw(signature.returnType());
            }
        }
        return null;
    }

    private static boolean mentions(TypeDef type, String name) {
        return switch (TypeHierarchy.unwrap(type)) {
            case TypeDef.TypeVariable variable -> variable.name().equals(name);
            case ClassTypeDef.Parameterized parameterized -> parameterized.typeArguments().stream().anyMatch(argument -> mentions(argument, name));
            case TypeDef.Array array -> mentions(array.componentType(), name);
            case TypeDef.Wildcard wildcard -> wildcard.upperBounds().stream().anyMatch(bound -> mentions(bound, name))
                || wildcard.lowerBounds().stream().anyMatch(bound -> mentions(bound, name));
            default -> false;
        };
    }

    /**
     * The raw type a generic result is cast to where it is returned or stored as a parameterization that fixes a
     * variable of the method an argument does not convert to - {@code Optional.ofNullable(value)} of an {@code Object}
     * returned as an {@code Optional<String>}.
     *
     * @param value     The value
     * @param target    The type it is returned or stored as
     * @param objectDef The definition being written
     * @param methodDef The method being written
     * @return The raw type, or {@code null} where the value is converted as it is
     */
    @Nullable
    TypeDef conflictingResult(ExpressionDef value, TypeDef target, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        ExpressionDef written = JavaCasts.writtenNode(value, JavaCasts.CastContext.DEFAULT);
        Signature signature = signatureOf(written, objectDef);
        if (signature == null
            || !(TypeHierarchy.unwrap(signature.returnType()) instanceof ClassTypeDef.Parameterized returned)
            || !(TypeHierarchy.unwrap(target) instanceof ClassTypeDef.Parameterized fixed)
            || !returned.rawType().getName().equals(fixed.rawType().getName())
            || returned.typeArguments().size() != fixed.typeArguments().size()) {
            return null;
        }
        Map<String, TypeDef> substitution = new HashMap<>();
        for (int i = 0; i < returned.typeArguments().size(); i++) {
            if (TypeHierarchy.unwrap(returned.typeArguments().get(i)) instanceof TypeDef.TypeVariable variable
                && signature.variables().containsKey(variable.name())
                && TypeHierarchy.unwrap(fixed.typeArguments().get(i)) instanceof ClassTypeDef argument) {
                substitution.put(variable.name(), argument);
            }
        }
        if (substitution.isEmpty()) {
            return null;
        }
        List<? extends ExpressionDef> arguments = argumentsOf(written);
        for (int i = 0; i < arguments.size() && i < signature.parameterTypes().size(); i++) {
            TypeDef declared = signature.parameterTypes().get(i);
            if (!TypeHierarchy.containsVariableOtherThan(declared, Set.of())) {
                continue;
            }
            TypeDef expected = TypeHierarchy.substituted(declared, substitution);
            ExpressionDef argument = arguments.get(i);
            if (JavaCasts.isNullLiteral(argument) || JavaCasts.isFunctional(argument)
                || TypeHierarchy.containsVariableOtherThan(expected, Set.of())) {
                continue;
            }
            TypeDef source = conversions.sourceTypeOf(argument, methodDef, objectDef);
            TypeDef boxed = source instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : source;
            if (conversions.requiresImplicitInvocationCast(expected, boxed) || conversions.requiresRawCast(expected, boxed)) {
                return fixed.rawType();
            }
        }
        return null;
    }

    private static List<? extends ExpressionDef> argumentsOf(ExpressionDef invocation) {
        return switch (invocation) {
            case ExpressionDef.InvokeStaticMethod call -> call.values();
            case ExpressionDef.InvokeInstanceMethod call -> call.values();
            default -> List.of();
        };
    }

    /**
     * The generic signature of the method an expression invokes, where it declares variables.
     */
    @Nullable
    private Signature signatureOf(ExpressionDef expression, @Nullable ObjectDef objectDef) {
        ClassTypeDef owner;
        MethodDef method;
        switch (expression) {
            case ExpressionDef.InvokeStaticMethod call -> {
                owner = call.classDef();
                method = call.method();
            }
            case ExpressionDef.InvokeInstanceMethod call when !call.method().isConstructor() -> {
                owner = JavaTypes.ownerOf(objectDef, call.instance().type());
                method = call.method();
            }
            default -> {
                return null;
            }
        }
        ObjectDef definition = scope.definitionOf(owner, objectDef);
        if (definition != null) {
            List<String> erasures = erasures(method.getParameters().stream().map(ParameterDef::getType).toList());
            return definition.getMethods().stream()
                .filter(declared -> declared.getName().equals(method.getName()) && !declared.getTypeVariables().isEmpty()
                    && erasures.equals(erasures(declared.getParameters().stream().map(ParameterDef::getType).toList())))
                .findFirst()
                .map(declared -> {
                    Map<String, List<TypeDef>> variables = new HashMap<>();
                    declared.getTypeVariables().forEach(variable -> variables.put(variable.name(), variable.bounds()));
                    return new Signature(variables, declared.getReturnType(),
                        declared.getParameters().stream().map(ParameterDef::getType).toList());
                })
                .orElse(null);
        }
        if (!(JavaSignatures.executable(owner, method) instanceof Method compiled) || compiled.getTypeParameters().length == 0) {
            return null;
        }
        Map<String, List<TypeDef>> variables = new HashMap<>();
        for (java.lang.reflect.TypeVariable<?> variable : compiled.getTypeParameters()) {
            variables.put(variable.getName(), Arrays.stream(variable.getBounds()).map(TypeHierarchy::typeDefOf).toList());
        }
        return new Signature(variables, TypeHierarchy.typeDefOf(compiled.getGenericReturnType()),
            Arrays.stream(compiled.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList());
    }

    private static List<String> erasures(List<TypeDef> types) {
        return types.stream().map(TypeHierarchy::erasedName).toList();
    }

    /**
     * The generic signature of a method.
     *
     * @param variables      The bounds of the variables it declares, by their names
     * @param returnType     The return type
     * @param parameterTypes The parameter types
     */
    private record Signature(Map<String, List<TypeDef>> variables, TypeDef returnType, List<TypeDef> parameterTypes) {
    }
}
