/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode;

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
 * Resolves the bridge methods a declared method requires.
 *
 * <p>A bridge is required when a declared method overrides an inherited method whose resolved JVM
 * descriptor differs — a covariant return type, or a parameter or return type whose erasure at the
 * declaration site is wider than at the override site. The resolver walks the declared supertypes,
 * carrying a type-variable substitution per edge, finds the inherited methods the declared method
 * overrides at the source level, and reports one bridge per distinct inherited erasure.
 *
 * <p>The hierarchy is read from the supertype references themselves: an {@link ObjectDef} for another
 * generated type, a {@link Class} through reflection, and a {@link ClassElement} through the
 * annotation-processing AST. A supertype known only by name carries no method or generic metadata, so
 * no bridge can be derived from it and it is deliberately skipped.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Internal
public final class BridgeResolver {

    private static final Set<String> TERMINAL_TYPES = Set.of("java.lang.Object", "java.lang.Record", "java.lang.Enum");

    private BridgeResolver() {
    }

    /**
     * Resolve the bridges required by a declared method.
     *
     * @param objectDef The type declaring the method
     * @param methodDef The declared method
     * @return The bridges, without one for the method's own descriptor or a duplicate
     */
    public static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (objectDef == null
            || methodDef.isConstructor()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)) {
            return List.of();
        }
        List<TypeDef> superTypes = superTypesOf(objectDef);
        if (superTypes.isEmpty()) {
            return List.of();
        }

        List<String> declaredParameterDescriptors = methodDef.getParameters().stream()
            .map(p -> TypeUtils.getType(p.getType(), objectDef).getDescriptor())
            .toList();
        Set<String> takenDescriptors = new HashSet<>();
        // The method's own descriptor and the descriptors of its overloads are already implemented
        for (MethodDef declared : objectDef.getMethods()) {
            if (declared.getName().equals(methodDef.getName())) {
                takenDescriptors.add(TypeUtils.getMethodDescriptor(objectDef, declared));
            }
        }
        takenDescriptors.add(TypeUtils.getMethodDescriptor(objectDef, methodDef));
        String declaredReturnDescriptor = TypeUtils.getType(methodDef.getReturnType(), objectDef).getDescriptor();

        List<BridgeMethod> bridges = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (TypeDef superType : superTypes) {
            enqueue(queue, visited, superType, Map.of());
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (MethodSignature inherited : node.info.methods()) {
                if (!inherited.name().equals(methodDef.getName())
                    || inherited.overrideParameterTypes().size() != declaredParameterDescriptors.size()) {
                    continue;
                }
                // Override check happens before erasure: substitute the supertype's type arguments into
                // the inherited parameters and compare with the declared parameters
                List<String> substituted = inherited.overrideParameterTypes().stream()
                    .map(p -> descriptor(erase(substitute(p, node.substitution), node.info)))
                    .toList();
                if (!substituted.equals(declaredParameterDescriptors)) {
                    continue;
                }
                // The bridge carries the declaration-site erasure of the overridden method
                List<TypeDef> bridgeParameters = inherited.bridgeParameterTypes().stream()
                    .map(p -> erase(p, node.info))
                    .toList();
                TypeDef bridgeReturn = erase(inherited.bridgeReturnType(), node.info);
                String bridgeReturnDescriptor = descriptor(bridgeReturn);
                // A primitive return has no covariance a bridge could reconcile: unless the return
                // types are identical, the methods do not override each other
                if (!bridgeReturnDescriptor.equals(declaredReturnDescriptor)
                    && (isPrimitive(bridgeReturnDescriptor) || isPrimitive(declaredReturnDescriptor))) {
                    continue;
                }
                String bridgeDescriptor = bridgeParameters.stream().map(BridgeResolver::descriptor)
                    .collect(Collectors.joining("", "(", ")")) + descriptor(bridgeReturn);
                if (takenDescriptors.add(bridgeDescriptor)) {
                    bridges.add(new BridgeMethod(bridgeParameters, bridgeReturn));
                }
            }
            for (TypeDef superType : node.info.superTypes()) {
                enqueue(queue, visited, substitute(superType, node.substitution), node.substitution.isEmpty() ? Map.of() : node.substitution);
            }
        }
        return bridges;
    }

    private static void enqueue(Deque<Node> queue, Set<String> visited, TypeDef edge, Map<String, TypeDef> outerSubstitution) {
        TypeDef unwrapped = unwrap(edge);
        ClassTypeDef rawType;
        List<TypeDef> typeArguments = List.of();
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            rawType = parameterized.rawType();
            // A generic ClassElement converts to a Parameterized over its own type variables; the raw
            // declaration is inside and the outer arguments are the ones that matter
            while (rawType instanceof ClassTypeDef.Parameterized nested) {
                rawType = nested.rawType();
            }
            typeArguments = parameterized.typeArguments().stream().map(t -> substitute(t, outerSubstitution)).toList();
        } else if (unwrapped instanceof ClassTypeDef classTypeDef) {
            rawType = classTypeDef;
        } else {
            return;
        }
        TypeInfo info = typeInfoOf(rawType);
        if (info == null) {
            // A supertype known only by name carries no metadata to derive bridges from
            return;
        }
        Map<String, TypeDef> substitution = new HashMap<>();
        List<String> typeParameters = info.typeParameters();
        for (int i = 0; i < typeParameters.size() && i < typeArguments.size(); i++) {
            substitution.put(typeParameters.get(i), typeArguments.get(i));
        }
        String key = info.typeName() + typeArguments.stream()
            .map(t -> descriptor(erase(t, info)))
            .collect(Collectors.joining(",", "<", ">"));
        if (visited.add(key)) {
            queue.add(new Node(info, substitution));
        }
    }

    @Nullable
    private static TypeInfo typeInfoOf(ClassTypeDef rawType) {
        if (TERMINAL_TYPES.contains(rawType.getName())) {
            return null;
        }
        if (rawType instanceof ClassTypeDef.ClassDefType classDefType) {
            return new ObjectDefInfo(classDefType.objectDef());
        }
        if (rawType instanceof ClassTypeDef.JavaClass javaClass) {
            return new ReflectionInfo(javaClass.type());
        }
        if (rawType instanceof ClassTypeDef.ClassElementType classElementType) {
            return new ClassElementInfo(classElementType.classElement());
        }
        return null;
    }

    private static List<TypeDef> superTypesOf(ObjectDef objectDef) {
        List<TypeDef> superTypes = new ArrayList<>();
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            superTypes.add(classDef.getSuperclass());
        }
        superTypes.addAll(objectDef.getSuperinterfaces());
        return superTypes;
    }

    private static TypeDef substitute(TypeDef typeDef, Map<String, TypeDef> substitution) {
        if (substitution.isEmpty()) {
            return typeDef;
        }
        TypeDef unwrapped = unwrap(typeDef);
        if (unwrapped instanceof TypeDef.TypeVariable typeVariable) {
            TypeDef resolved = substitution.get(typeVariable.name());
            return resolved == null ? typeVariable : resolved;
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return new ClassTypeDef.Parameterized(
                parameterized.rawType(),
                parameterized.typeArguments().stream().map(t -> substitute(t, substitution)).toList()
            );
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return TypeDef.array(substitute(array.componentType(), substitution), array.dimensions());
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return new TypeDef.Wildcard(
                wildcard.upperBounds().stream().map(t -> substitute(t, substitution)).toList(),
                wildcard.lowerBounds().stream().map(t -> substitute(t, substitution)).toList()
            );
        }
        return unwrapped;
    }

    private static TypeDef erase(TypeDef typeDef, TypeInfo owner) {
        TypeDef unwrapped = unwrap(typeDef);
        if (TypeDef.THIS.equals(unwrapped)) {
            return ClassTypeDef.of(owner.typeName());
        }
        if (unwrapped instanceof TypeDef.TypeVariable typeVariable) {
            TypeDef bound = typeVariable.bounds().isEmpty() ? owner.variableBound(typeVariable.name()) : typeVariable.bounds().get(0);
            return bound == null ? TypeDef.OBJECT : erase(bound, owner);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.rawType();
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : erase(wildcard.upperBounds().get(0), owner);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return TypeDef.array(erase(array.componentType(), owner), array.dimensions());
        }
        return unwrapped;
    }

    private static TypeDef unwrap(TypeDef typeDef) {
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        return typeDef;
    }

    private static String descriptor(TypeDef erased) {
        return TypeUtils.getType(erased, null).getDescriptor();
    }

    private static boolean isPrimitive(String descriptor) {
        char first = descriptor.charAt(0);
        return first != 'L' && first != '[';
    }

    /**
     * The erased signature of an overridden method that the declaring class must implement with a
     * bridge.
     *
     * @param parameterTypes The erased parameter types of the overridden method
     * @param returnType     The erased return type of the overridden method
     */
    @Internal
    public record BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
    }

    private record Node(TypeInfo info, Map<String, TypeDef> substitution) {
    }

    /**
     * The inherited method as two views: the override check compares the substituted declaration
     * against the declared method, the bridge carries the declaration-site erasure.
     *
     * @param name                   The method name
     * @param overrideParameterTypes The parameter types to substitute for the override check
     * @param bridgeParameterTypes   The parameter types to erase for the bridge
     * @param bridgeReturnType       The return type to erase for the bridge
     */
    private record MethodSignature(String name,
                                   List<TypeDef> overrideParameterTypes,
                                   List<TypeDef> bridgeParameterTypes,
                                   TypeDef bridgeReturnType) {
    }

    /**
     * The hierarchy metadata of one supertype.
     */
    private interface TypeInfo {

        String typeName();

        List<String> typeParameters();

        @Nullable
        TypeDef variableBound(String name);

        List<MethodSignature> methods();

        /**
         * @return The declared supertypes to continue the walk with; empty when {@link #methods()}
         * already includes the inherited methods
         */
        List<TypeDef> superTypes();
    }

    private record ObjectDefInfo(ObjectDef objectDef) implements TypeInfo {

        @Override
        public String typeName() {
            return objectDef.asTypeDef().getName();
        }

        @Override
        public List<String> typeParameters() {
            return typeVariables().stream().map(TypeDef.TypeVariable::name).toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            return typeVariables().stream()
                .filter(v -> v.name().equals(name) && !v.bounds().isEmpty())
                .map(v -> v.bounds().get(0))
                .findFirst()
                .orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            return objectDef.getMethods().stream()
                .filter(m -> !m.isConstructor()
                    && !m.getModifiers().contains(Modifier.STATIC)
                    && !m.getModifiers().contains(Modifier.PRIVATE))
                .map(m -> {
                    List<TypeDef> parameters = m.getParameters().stream().map(ParameterDef::getType).toList();
                    return new MethodSignature(m.getName(), parameters, parameters, m.getReturnType());
                })
                .toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            return superTypesOf(objectDef);
        }

        private List<TypeDef.TypeVariable> typeVariables() {
            if (objectDef instanceof ClassDef classDef) {
                return classDef.getTypeVariables();
            }
            if (objectDef instanceof InterfaceDef interfaceDef) {
                return interfaceDef.getTypeVariables();
            }
            if (objectDef instanceof RecordDef recordDef) {
                return recordDef.getTypeVariables();
            }
            return List.of();
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
            return Arrays.stream(type.getTypeParameters())
                .filter(v -> v.getName().equals(name))
                .findFirst()
                .map(v -> convert(v.getBounds()[0]))
                .orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && !java.lang.reflect.Modifier.isPrivate(m.getModifiers())
                    && !m.isSynthetic())
                .map(m -> {
                    List<TypeDef> parameters = Arrays.stream(m.getGenericParameterTypes()).map(ReflectionInfo::convert).toList();
                    return new MethodSignature(m.getName(), parameters, parameters, convert(m.getGenericReturnType()));
                })
                .toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            List<TypeDef> superTypes = new ArrayList<>();
            Type superclass = type.getGenericSuperclass();
            if (superclass != null) {
                superTypes.add(convert(superclass));
            }
            for (Type superinterface : type.getGenericInterfaces()) {
                superTypes.add(convert(superinterface));
            }
            return superTypes;
        }

        private static TypeDef convert(Type type) {
            if (type instanceof Class<?> aClass) {
                return TypeDef.of(aClass);
            }
            if (type instanceof ParameterizedType parameterizedType) {
                return new ClassTypeDef.Parameterized(
                    ClassTypeDef.of((Class<?>) parameterizedType.getRawType()),
                    Arrays.stream(parameterizedType.getActualTypeArguments()).map(ReflectionInfo::convert).toList()
                );
            }
            if (type instanceof java.lang.reflect.TypeVariable<?> typeVariable) {
                // The bounds are looked up on the declaring type to avoid recursive bounds
                return TypeDef.variable(typeVariable.getName());
            }
            if (type instanceof GenericArrayType arrayType) {
                return convert(arrayType.getGenericComponentType()).array();
            }
            if (type instanceof java.lang.reflect.WildcardType wildcardType) {
                return new TypeDef.Wildcard(
                    Arrays.stream(wildcardType.getUpperBounds()).map(ReflectionInfo::convert).toList(),
                    Arrays.stream(wildcardType.getLowerBounds()).map(ReflectionInfo::convert).toList()
                );
            }
            return TypeDef.OBJECT;
        }
    }

    /**
     * The annotation-processing view, walked level by level like the other sources: the declared
     * methods carry the declaration's own placeholders, and the supertype edges carry the type
     * arguments to substitute them with.
     *
     * @param classElement The class element
     */
    private record ClassElementInfo(ClassElement classElement) implements TypeInfo {

        @Override
        public String typeName() {
            return classElement.getName();
        }

        @Override
        public List<String> typeParameters() {
            return classElement.getDeclaredGenericPlaceholders().stream()
                .map(io.micronaut.inject.ast.GenericPlaceholderElement::getVariableName)
                .toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            return classElement.getDeclaredGenericPlaceholders().stream()
                .filter(p -> p.getVariableName().equals(name) && !p.getBounds().isEmpty())
                .map(p -> TypeDef.erasure(p.getBounds().get(0)))
                .findFirst()
                .orElse(null);
        }

        @Override
        public List<MethodSignature> methods() {
            return classElement.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance().onlyDeclared()).stream()
                .filter(m -> !m.isPrivate())
                .map(ClassElementInfo::signatureOf)
                .toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            List<TypeDef> superTypes = new ArrayList<>();
            classElement.getSuperType().ifPresent(s -> superTypes.add(TypeDef.of(s, Map.of(), false)));
            for (ClassElement superinterface : classElement.getInterfaces()) {
                superTypes.add(TypeDef.of(superinterface, Map.of(), false));
            }
            return superTypes;
        }

        private static MethodSignature signatureOf(MethodElement method) {
            // The AST contextualizes placeholder bounds along the way, so the override check uses the
            // generic view while the bridge takes the declaration's raw view, which erases correctly
            List<TypeDef> overrideParameters = Arrays.stream(method.getParameters())
                .map(p -> TypeDef.of(p.getGenericType(), Map.of(), false))
                .toList();
            List<TypeDef> bridgeParameters = Arrays.stream(method.getParameters())
                .map(p -> TypeDef.erasure(p.getType()))
                .toList();
            return new MethodSignature(method.getName(), overrideParameters, bridgeParameters, TypeDef.erasure(method.getReturnType()));
        }
    }
}
