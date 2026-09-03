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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final Set<String> TERMINAL_TYPES = Set.of("java.lang.Object", "java.lang.Record", "java.lang.Enum");

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
            || methodDef.getModifiers().contains(Modifier.PRIVATE)) {
            return List.of();
        }
        List<TypeDef> superTypes = superTypesOf(objectDef);
        if (superTypes.isEmpty()) {
            return List.of();
        }
        List<String> parameters = methodDef.getParameters().stream()
            .map(parameter -> TypeUtils.getDescriptor(parameter.getType(), objectDef)).toList();
        Declared declared = new Declared(objectDef, new ModelInfo(objectDef), methodDef, parameters,
            TypeUtils.getDescriptor(methodDef.getReturnType(), objectDef));
        List<BridgeMethod> result = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        objectDef.getMethods().stream()
            .filter(method -> method.getName().equals(methodDef.getName()))
            .map(method -> TypeUtils.getMethodDescriptor(objectDef, method))
            .forEach(taken::add);
        Deque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (TypeDef superType : superTypes) {
            enqueue(queue, visited, superType, Map.of());
        }
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            for (MethodSignature inherited : node.info().methods()) {
                BridgeMethod bridge = bridgeFor(declared, node, inherited);
                if (bridge != null && taken.add(descriptorOf(bridge))) {
                    result.add(bridge);
                }
            }
            for (TypeDef superType : node.info().superTypes()) {
                enqueue(queue, visited, substitute(superType, node.substitution()), node.substitution());
            }
        }
        return result;
    }

    @Nullable
    private static BridgeMethod bridgeFor(Declared declared, Node node, MethodSignature inherited) {
        if (!inherited.name().equals(declared.methodDef().getName())
            || inherited.overrideParameters().size() != declared.parameterDescriptors().size()
            || inherited.finalMethod()
            || (inherited.packagePrivate() && !node.info().packageName().equals(declared.objectDef().getPackageName()))) {
            return null;
        }
        List<String> substituted = inherited.overrideParameters().stream()
            .map(parameter -> TypeUtils.getDescriptor(erase(substitute(parameter, node.substitution()),
                declared.declaringInfo(), node.info()), null)).toList();
        if (!substituted.equals(declared.parameterDescriptors())) {
            return null;
        }
        TypeDef returnType = erase(inherited.returnType(), node.info());
        String returnDescriptor = TypeUtils.getDescriptor(returnType, null);
        if (!returnDescriptor.equals(declared.returnDescriptor())
            && (isPrimitive(returnDescriptor) || isPrimitive(declared.returnDescriptor()))) {
            return null;
        }
        return new BridgeMethod(inherited.bridgeParameters().stream()
            .map(parameter -> erase(parameter, node.info())).toList(), returnType);
    }

    private static void enqueue(Deque<Node> queue,
                                Set<String> visited,
                                TypeDef edge,
                                Map<String, TypeDef> outerSubstitution) {
        TypeDef unwrapped = unwrap(edge);
        ClassTypeDef rawType;
        List<TypeDef> arguments;
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            rawType = parameterized.rawType();
            while (rawType instanceof ClassTypeDef.Parameterized nested) {
                rawType = nested.rawType();
            }
            arguments = parameterized.typeArguments().stream()
                .map(argument -> substitute(argument, outerSubstitution)).toList();
        } else if (unwrapped instanceof ClassTypeDef classTypeDef) {
            rawType = classTypeDef;
            arguments = List.of();
        } else {
            return;
        }
        TypeInfo info = typeInfoOf(rawType);
        if (info == null) {
            return;
        }
        Map<String, TypeDef> substitution = new HashMap<>();
        List<String> variables = info.typeParameters();
        for (int i = 0; i < variables.size() && i < arguments.size(); i++) {
            substitution.put(variables.get(i), arguments.get(i));
        }
        String key = info.typeName() + arguments.stream()
            .map(argument -> TypeUtils.getDescriptor(erase(argument, info), null))
            .collect(Collectors.joining(",", "<", ">"));
        if (visited.add(key)) {
            queue.addLast(new Node(info, substitution));
        }
    }

    @Nullable
    private static TypeInfo typeInfoOf(ClassTypeDef type) {
        if (TERMINAL_TYPES.contains(type.getName())) {
            return null;
        }
        if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            return new ModelInfo(classDefType.objectDef());
        }
        if (type instanceof ClassTypeDef.JavaClass javaClass) {
            return new ReflectionInfo(javaClass.type());
        }
        if (type instanceof ClassTypeDef.ClassElementType classElementType) {
            return new AstInfo(classElementType.classElement());
        }
        return null;
    }

    private static List<TypeDef> superTypesOf(ObjectDef objectDef) {
        List<TypeDef> result = new ArrayList<>();
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            result.add(classDef.getSuperclass());
        }
        result.addAll(objectDef.getSuperinterfaces());
        return result;
    }

    private static TypeDef substitute(TypeDef type, Map<String, TypeDef> substitution) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            return substitution.getOrDefault(variable.name(), variable);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return new ClassTypeDef.Parameterized(parameterized.rawType(), parameterized.typeArguments().stream()
                .map(argument -> substitute(argument, substitution)).toList());
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return TypeDef.array(substitute(array.componentType(), substitution), array.dimensions());
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return new TypeDef.Wildcard(wildcard.upperBounds().stream()
                .map(bound -> substitute(bound, substitution)).toList(), wildcard.lowerBounds().stream()
                .map(bound -> substitute(bound, substitution)).toList());
        }
        return unwrapped;
    }

    private static TypeDef erase(TypeDef type, TypeInfo owner) {
        return erase(type, null, owner);
    }

    private static TypeDef erase(TypeDef type, @Nullable TypeInfo boundOwner, TypeInfo owner) {
        TypeDef unwrapped = unwrap(type);
        if (TypeDef.THIS.equals(unwrapped)) {
            return ClassTypeDef.of(owner.typeName());
        }
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            TypeDef bound = !variable.bounds().isEmpty() ? variable.bounds().get(0)
                : boundOwner == null ? owner.variableBound(variable.name()) : boundOwner.variableBound(variable.name());
            return bound == null ? TypeDef.OBJECT : erase(bound, boundOwner, owner);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.rawType();
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT
                : erase(wildcard.upperBounds().get(0), boundOwner, owner);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return TypeDef.array(erase(array.componentType(), boundOwner, owner), array.dimensions());
        }
        return unwrapped;
    }

    private static TypeDef unwrap(TypeDef type) {
        if (type instanceof TypeDef.AnnotatedTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        if (type instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        return type;
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
                            TypeInfo declaringInfo,
                            MethodDef methodDef,
                            List<String> parameterDescriptors,
                            String returnDescriptor) {
    }

    private record Node(TypeInfo info, Map<String, TypeDef> substitution) {
    }

    private record MethodSignature(String name,
                                   List<TypeDef> overrideParameters,
                                   List<TypeDef> bridgeParameters,
                                   TypeDef returnType,
                                   boolean finalMethod,
                                   boolean packagePrivate) {
    }

    private interface TypeInfo {
        String typeName();

        List<String> typeParameters();

        @Nullable
        TypeDef variableBound(String name);

        List<MethodSignature> methods();

        List<TypeDef> superTypes();

        default String packageName() {
            int index = typeName().lastIndexOf('.');
            return index < 0 ? "" : typeName().substring(0, index);
        }
    }

    private record ModelInfo(ObjectDef objectDef) implements TypeInfo {
        @Override
        public String typeName() {
            return objectDef.getName();
        }

        @Override
        public List<String> typeParameters() {
            return variables().stream().map(TypeDef.TypeVariable::name).toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            return variables().stream().filter(variable -> variable.name().equals(name))
                .flatMap(variable -> variable.bounds().stream()).findFirst().orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            boolean interfaceType = objectDef instanceof InterfaceDef;
            return objectDef.getMethods().stream()
                .filter(method -> !method.isConstructor()
                    && !method.getModifiers().contains(Modifier.STATIC)
                    && !method.getModifiers().contains(Modifier.PRIVATE))
                .map(method -> {
                    List<TypeDef> parameters = method.getParameters().stream().map(ParameterDef::getType).toList();
                    boolean packagePrivate = !interfaceType && !method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.PROTECTED);
                    return new MethodSignature(method.getName(), parameters, parameters, method.getReturnType(),
                        method.getModifiers().contains(Modifier.FINAL), packagePrivate);
                }).toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            return superTypesOf(objectDef);
        }

        private List<TypeDef.TypeVariable> variables() {
            return switch (objectDef) {
                case ClassDef classDef -> classDef.getTypeVariables();
                case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables();
                case RecordDef recordDef -> recordDef.getTypeVariables();
                default -> List.of();
            };
        }
    }

    private record ReflectionInfo(Class<?> type) implements TypeInfo {
        @Override
        public String typeName() {
            return type.getName();
        }

        @Override
        public List<String> typeParameters() {
            return Arrays.stream(type.getTypeParameters()).map(java.lang.reflect.TypeVariable::getName).toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            return Arrays.stream(type.getTypeParameters()).filter(variable -> variable.getName().equals(name))
                .findFirst().map(variable -> convert(variable.getBounds()[0])).orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && !java.lang.reflect.Modifier.isPrivate(method.getModifiers()) && !method.isSynthetic())
                .map(method -> {
                    List<TypeDef> parameters = Arrays.stream(method.getGenericParameterTypes())
                        .map(ReflectionInfo::convert).toList();
                    int modifiers = method.getModifiers();
                    return new MethodSignature(method.getName(), parameters, parameters,
                        convert(method.getGenericReturnType()), java.lang.reflect.Modifier.isFinal(modifiers),
                        !java.lang.reflect.Modifier.isPublic(modifiers)
                            && !java.lang.reflect.Modifier.isProtected(modifiers));
                }).toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            List<TypeDef> result = new ArrayList<>();
            if (type.getGenericSuperclass() != null) {
                result.add(convert(type.getGenericSuperclass()));
            }
            Arrays.stream(type.getGenericInterfaces()).map(ReflectionInfo::convert).forEach(result::add);
            return result;
        }

        private static TypeDef convert(Type type) {
            if (type instanceof Class<?> aClass) {
                return TypeDef.of(aClass);
            }
            if (type instanceof ParameterizedType parameterized) {
                return new ClassTypeDef.Parameterized(ClassTypeDef.of((Class<?>) parameterized.getRawType()),
                    Arrays.stream(parameterized.getActualTypeArguments()).map(ReflectionInfo::convert).toList());
            }
            if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
                return TypeDef.variable(variable.getName());
            }
            if (type instanceof GenericArrayType array) {
                return convert(array.getGenericComponentType()).array();
            }
            if (type instanceof java.lang.reflect.WildcardType wildcard) {
                return new TypeDef.Wildcard(Arrays.stream(wildcard.getUpperBounds()).map(ReflectionInfo::convert).toList(),
                    Arrays.stream(wildcard.getLowerBounds()).map(ReflectionInfo::convert).toList());
            }
            return TypeDef.OBJECT;
        }
    }

    private record AstInfo(ClassElement classElement) implements TypeInfo {
        @Override
        public String typeName() {
            return classElement.getName();
        }

        @Override
        public List<String> typeParameters() {
            return classElement.getDeclaredGenericPlaceholders().stream()
                .map(io.micronaut.inject.ast.GenericPlaceholderElement::getVariableName).toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            return classElement.getDeclaredGenericPlaceholders().stream()
                .filter(variable -> variable.getVariableName().equals(name) && !variable.getBounds().isEmpty())
                .map(variable -> TypeDef.erasure(variable.getBounds().get(0))).findFirst().orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            return classElement.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance().onlyDeclared()).stream()
                .filter(method -> !method.isPrivate()).map(AstInfo::signatureOf).toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            List<TypeDef> result = new ArrayList<>();
            classElement.getSuperType().ifPresent(type -> result.add(TypeDef.of(type, ignore -> null, false)));
            for (ClassElement interfaceType : classElement.getInterfaces()) {
                result.add(TypeDef.of(interfaceType, ignore -> null, false));
            }
            return result;
        }

        private static MethodSignature signatureOf(MethodElement method) {
            List<TypeDef> overrideParameters = Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false)).toList();
            List<TypeDef> bridgeParameters = Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.erasure(parameter.getType())).toList();
            return new MethodSignature(method.getName(), overrideParameters, bridgeParameters,
                TypeDef.erasure(method.getReturnType()), method.isFinal(), !method.isPublic() && !method.isProtected());
        }
    }
}
