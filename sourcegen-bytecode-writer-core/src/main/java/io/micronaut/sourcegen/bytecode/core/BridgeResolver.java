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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
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

    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    private static final Set<String> ARRAY_SUPERTYPES = Set.of(Cloneable.class.getName(), Serializable.class.getName());

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
        Declared declared = new Declared(objectDef, TypeHierarchy.declaring(objectDef), methodDef, parameters);
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
        // A method returning a type unrelated to the inherited one does not override it - it hides it in bytecode,
        // where javac rejects the source - so no bridge casting one to the other belongs there. A return narrower
        // than the inherited one is the covariant override javac bridges; a wider one is bridged with a cast, as
        // the writers do for a declaration the model marks as an override
        TypeDef declaredReturnType = declared.declaringType().erase(
            ObjectDef.getContextualType(declared.objectDef(), declared.methodDef().getReturnType()));
        if (!isSubtype(declaredReturnType, returnType, declared.objectDef())
            && !isSubtype(returnType, declaredReturnType, declared.objectDef())) {
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
     * Whether an erased type is a subtype of another, as the JVM sees them: a primitive only of itself, an array of
     * one of a supertype of its component or of the types every array implements, and a class of one it inherits.
     * The class is asked through the model, reflection or the annotation-processing element it is known by; a class
     * known by its name alone is loaded where it can be, and taken for a subtype where it cannot - the bridge is
     * then written as it was before the return types were compared.
     */
    private static boolean isSubtype(TypeDef declared, TypeDef inherited, ObjectDef objectDef) {
        String declaredDescriptor = TypeUtils.getDescriptor(declared, objectDef);
        String inheritedDescriptor = TypeUtils.getDescriptor(inherited, null);
        if (declaredDescriptor.equals(inheritedDescriptor)) {
            return true;
        }
        if (isPrimitive(declaredDescriptor) || isPrimitive(inheritedDescriptor)) {
            return false;
        }
        if (inheritedDescriptor.equals(OBJECT_DESCRIPTOR)) {
            return true;
        }
        TypeDef declaredType = TypeHierarchy.unwrap(declared);
        TypeDef inheritedType = TypeHierarchy.unwrap(inherited);
        if (declaredType instanceof TypeDef.Array declaredArray) {
            if (inheritedType instanceof TypeDef.Array inheritedArray) {
                return isSubtype(peel(declaredArray), peel(inheritedArray), objectDef);
            }
            return ARRAY_SUPERTYPES.contains(TypeHierarchy.erasedName(inheritedType));
        }
        if (inheritedType instanceof TypeDef.Array || !(declaredType instanceof ClassTypeDef declaredClass)) {
            return false;
        }
        ClassTypeDef resolved = resolve(declaredClass, objectDef);
        if (resolved == null) {
            return true;
        }
        return TypeHierarchy.inherits(resolved, TypeHierarchy.erasedName(inheritedType), null);
    }

    private static TypeDef peel(TypeDef.Array array) {
        return array.dimensions() > 1 ? TypeDef.array(array.componentType(), array.dimensions() - 1) : array.componentType();
    }

    /**
     * The type as something the hierarchy can ask about its supertypes: the definition being written, one it holds
     * as a model, class or element, or the loaded class of a name, or {@code null} where the name cannot be loaded.
     */
    @Nullable
    private static ClassTypeDef resolve(ClassTypeDef type, ObjectDef objectDef) {
        ClassTypeDef raw = type;
        while (raw instanceof ClassTypeDef.Parameterized parameterized) {
            raw = parameterized.rawType();
        }
        if (raw instanceof ClassTypeDef.ClassDefType || raw instanceof ClassTypeDef.JavaClass
            || raw instanceof ClassTypeDef.ClassElementType) {
            return raw;
        }
        String name = TypeUtils.getBinaryName(raw, objectDef);
        if (name.equals(objectDef.getName())) {
            return objectDef.asTypeDef();
        }
        for (ObjectDef innerType : objectDef.getInnerTypes()) {
            if (name.equals(innerType.getName())) {
                return innerType.asTypeDef();
            }
        }
        try {
            return ClassTypeDef.of(Class.forName(name, false, BridgeResolver.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
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
                            List<String> parameterDescriptors) {
    }
}
