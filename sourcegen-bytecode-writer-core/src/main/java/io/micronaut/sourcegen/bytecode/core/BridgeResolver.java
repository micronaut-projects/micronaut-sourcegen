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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves generic bridges from Sourcegen model, reflection, and annotation-processing types.
 *
 * <p>Bridge resolution is part of lowering rather than an ASM concern: the same erased inherited
 * method must be represented by every bytecode backend. The resolver intentionally ignores a
 * supertype represented only by a name because that form does not contain enough method metadata.</p>
 *
 * @since 2.2
 */
@Internal
public final class BridgeResolver {

    private BridgeResolver() {
    }

    /**
     * Resolves the bridges required by a declared method.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @return Erased inherited method shapes requiring a bridge
     */
    public static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (objectDef == null || methodDef.isConstructor()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)
            || TypeHierarchy.superTypesOf(objectDef).isEmpty()) {
            return List.of();
        }
        List<String> parameters = methodDef.getParameters().stream()
            .map(parameter -> TypeUtils.getDescriptor(parameter.getType(), objectDef)).toList();
        Declared declared = new Declared(objectDef, TypeHierarchy.declaring(objectDef), methodDef, parameters,
            TypeUtils.getDescriptor(methodDef.getReturnType(), objectDef));
        List<BridgeMethod> result = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        objectDef.getMethods().stream()
            .filter(method -> method.getName().equals(methodDef.getName()))
            .map(method -> TypeUtils.getMethodDescriptor(objectDef, method))
            .forEach(taken::add);
        TypeHierarchy.visitInheritedMethods(objectDef, null, (type, inherited) -> {
            BridgeMethod bridge = bridgeFor(declared, type, inherited);
            if (bridge != null && taken.add(descriptorOf(bridge))) {
                result.add(bridge);
            }
            return true;
        });
        return result;
    }

    @Nullable
    private static BridgeMethod bridgeFor(Declared declared,
                                          TypeHierarchy.InheritedType type,
                                          TypeHierarchy.InheritedMethod inherited) {
        if (!inherited.name().equals(declared.methodDef().getName())
            || inherited.overrideParameters().size() != declared.parameterDescriptors().size()
            || inherited.finalMethod()
            || (inherited.packagePrivate() && !type.getPackageName().equals(TypeHierarchy.packageOf(declared.objectDef())))) {
            return null;
        }
        // A variable the method declares of its own is not the type's of the same name: it is erased to its bound,
        // with the type arguments substituted
        List<String> substituted = inherited.overrideParameters().stream()
            .map(parameter -> type.substitute(parameter, inherited.typeVariables()))
            .map(parameter -> TypeUtils.getDescriptor(type.erase(parameter, declared.declaringType()), null))
            .toList();
        if (!substituted.equals(declared.parameterDescriptors())) {
            return null;
        }
        TypeDef returnType = type.erase(inherited.returnType());
        String returnDescriptor = TypeUtils.getDescriptor(returnType, null);
        if (!returnDescriptor.equals(declared.returnDescriptor())
            && (isPrimitive(returnDescriptor) || isPrimitive(declared.returnDescriptor()))) {
            return null;
        }
        return new BridgeMethod(inherited.bridgeParameters().stream().map(type::erase).toList(), returnType);
    }

    private static String descriptorOf(BridgeMethod bridge) {
        return bridge.parameterTypes().stream().map(type -> TypeUtils.getDescriptor(type, null))
            .collect(Collectors.joining("", "(", ")")) + TypeUtils.getDescriptor(bridge.returnType(), null);
    }

    private static boolean isPrimitive(String descriptor) {
        return descriptor.charAt(0) != 'L' && descriptor.charAt(0) != '[';
    }

    /**
     * The erased method shape used by a bridge.
     *
     * @param parameterTypes Erased parameter types
     * @param returnType Erased return type
     */
    public record BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
    }

    private record Declared(ObjectDef objectDef,
                            TypeHierarchy.InheritedType declaringType,
                            MethodDef methodDef,
                            List<String> parameterDescriptors,
                            String returnDescriptor) {
    }
}
