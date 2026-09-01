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
final class BridgeResolver {

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
    static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (objectDef == null || !canBeOverridden(methodDef)) {
            return List.of();
        }
        List<TypeDef> superTypes = superTypesOf(objectDef);
        if (superTypes.isEmpty()) {
            return List.of();
        }
        return collectBridges(new Declared(
            objectDef,
            new ObjectDefInfo(objectDef),
            methodDef,
            methodDef.getParameters().stream()
                .map(p -> TypeUtils.getType(p.getType(), objectDef).getDescriptor())
                .toList(),
            TypeUtils.getType(methodDef.getReturnType(), objectDef).getDescriptor()
        ), superTypes);
    }

    /**
     * Whether a method can override an inherited one at all. A constructor and a static method are
     * dispatched without a receiver, and a private method is not inherited.
     *
     * @param methodDef The declared method
     * @return Whether the method can override
     */
    private static boolean canBeOverridden(MethodDef methodDef) {
        return !methodDef.isConstructor()
            && !methodDef.getModifiers().contains(Modifier.STATIC)
            && !methodDef.getModifiers().contains(Modifier.PRIVATE);
    }

    /**
     * Walk the supertypes breadth-first, carrying a type-variable substitution per edge, and collect
     * one bridge per distinct erasure the declared method has to implement.
     *
     * @param declared   The declaring type and method
     * @param superTypes The declared supertypes to start from
     * @return The bridges
     */
    private static List<BridgeMethod> collectBridges(Declared declared, List<TypeDef> superTypes) {
        List<BridgeMethod> bridges = new ArrayList<>();
        Set<String> takenDescriptors = takenDescriptorsOf(declared.objectDef(), declared.methodDef());
        Deque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (TypeDef superType : superTypes) {
            enqueue(queue, visited, superType, Map.of());
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (MethodSignature inherited : node.info.methods()) {
                BridgeMethod bridge = bridgeFor(declared, node, inherited);
                if (bridge != null && takenDescriptors.add(descriptorOf(bridge))) {
                    bridges.add(bridge);
                }
            }
            Map<String, TypeDef> substitution = node.substitution.isEmpty() ? Map.of() : node.substitution;
            for (TypeDef superType : node.info.superTypes()) {
                enqueue(queue, visited, substitute(superType, node.substitution), substitution);
            }
        }
        return bridges;
    }

    /**
     * The descriptors the declaring type already implements: the method's own and those of its
     * same-name overloads.
     *
     * @param objectDef The declaring type
     * @param methodDef The declared method
     * @return The descriptors no bridge may be written for
     */
    private static Set<String> takenDescriptorsOf(ObjectDef objectDef, MethodDef methodDef) {
        Set<String> taken = new HashSet<>();
        for (MethodDef declared : objectDef.getMethods()) {
            if (declared.getName().equals(methodDef.getName())) {
                taken.add(TypeUtils.getMethodDescriptor(objectDef, declared));
            }
        }
        taken.add(TypeUtils.getMethodDescriptor(objectDef, methodDef));
        return taken;
    }

    /**
     * The bridge the declared method needs for one inherited method, or {@code null} when the declared
     * method does not override it or their erasures already agree.
     *
     * @param declared  The declaring type and method
     * @param node      The supertype the inherited method was found in
     * @param inherited The inherited method
     * @return The bridge or {@code null}
     */
    @Nullable
    private static BridgeMethod bridgeFor(Declared declared, Node node, MethodSignature inherited) {
        if (!inherited.name().equals(declared.methodDef().getName())
            || inherited.overrideParameterTypes().size() != declared.parameterDescriptors().size()) {
            return null;
        }
        // A final method cannot be overridden, and a package-private one only from its own package
        if (inherited.finalMethod()
            || (inherited.packagePrivate() && !node.info.packageName().equals(declared.objectDef().getPackageName()))) {
            return null;
        }
        // Override check happens before erasure: substitute the supertype's type arguments into the
        // inherited parameters and compare with the declared parameters. A variable left after
        // substitution belongs to the declaring type, whose bounds the ancestor cannot know, so its
        // bounds are looked up there first
        List<String> substituted = inherited.overrideParameterTypes().stream()
            .map(p -> descriptor(erase(substitute(p, node.substitution), declared.declaringInfo(), node.info)))
            .toList();
        if (!substituted.equals(declared.parameterDescriptors())) {
            return null;
        }
        // The bridge carries the declaration-site erasure of the overridden method
        TypeDef bridgeReturn = erase(inherited.bridgeReturnType(), node.info);
        String bridgeReturnDescriptor = descriptor(bridgeReturn);
        // A primitive return has no covariance a bridge could reconcile: unless the return types are
        // identical, the methods do not override each other
        if (!bridgeReturnDescriptor.equals(declared.returnDescriptor())
            && (isPrimitive(bridgeReturnDescriptor) || isPrimitive(declared.returnDescriptor()))) {
            return null;
        }
        return new BridgeMethod(
            inherited.bridgeParameterTypes().stream().map(p -> erase(p, node.info)).toList(),
            bridgeReturn
        );
    }

    private static String descriptorOf(BridgeMethod bridge) {
        return bridge.parameterTypes().stream()
            .map(BridgeResolver::descriptor)
            .collect(Collectors.joining("", "(", ")")) + descriptor(bridge.returnType());
    }

    private static void enqueue(Deque<Node> queue, Set<String> visited, TypeDef edge, Map<String, TypeDef> outerSubstitution) {
        TypeDef unwrapped = unwrap(edge);
        ClassTypeDef rawType;
        List<TypeDef> typeArguments;
        switch (unwrapped) {
            case ClassTypeDef.Parameterized(ClassTypeDef raw, List<TypeDef> arguments) -> {
                // A generic ClassElement converts to a Parameterized over its own type variables; the
                // raw declaration is inside and the outer arguments are the ones that matter
                ClassTypeDef unnested = raw;
                while (unnested instanceof ClassTypeDef.Parameterized(ClassTypeDef nested, List<TypeDef> _)) {
                    unnested = nested;
                }
                rawType = unnested;
                typeArguments = arguments.stream().map(t -> substitute(t, outerSubstitution)).toList();
            }
            case ClassTypeDef classTypeDef -> {
                rawType = classTypeDef;
                typeArguments = List.of();
            }
            default -> {
                return;
            }
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
        if (unwrapped instanceof ClassTypeDef.Parameterized(ClassTypeDef rawType, List<TypeDef> typeArguments)) {
            return new ClassTypeDef.Parameterized(
                rawType,
                typeArguments.stream().map(t -> substitute(t, substitution)).toList()
            );
        }
        if (unwrapped instanceof TypeDef.Array(TypeDef componentType, int dimensions, boolean _)) {
            return TypeDef.array(substitute(componentType, substitution), dimensions);
        }
        if (unwrapped instanceof TypeDef.Wildcard(List<TypeDef> upperBounds, List<TypeDef> lowerBounds)) {
            return new TypeDef.Wildcard(
                upperBounds.stream().map(t -> substitute(t, substitution)).toList(),
                lowerBounds.stream().map(t -> substitute(t, substitution)).toList()
            );
        }
        return unwrapped;
    }

    private static TypeDef erase(TypeDef typeDef, TypeInfo owner) {
        return erase(typeDef, null, owner);
    }

    private static TypeDef erase(TypeDef typeDef, @Nullable TypeInfo boundOwner, TypeInfo owner) {
        TypeDef unwrapped = unwrap(typeDef);
        if (TypeDef.THIS.equals(unwrapped)) {
            return ClassTypeDef.of(owner.typeName());
        }
        if (unwrapped instanceof TypeDef.TypeVariable typeVariable) {
            TypeDef bound = boundOf(typeVariable, boundOwner, owner);
            return bound == null ? TypeDef.OBJECT : erase(bound, boundOwner, owner);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized(ClassTypeDef rawType, List<TypeDef> _)) {
            return rawType;
        }
        if (unwrapped instanceof TypeDef.Wildcard(List<TypeDef> upperBounds, List<TypeDef> _)) {
            return upperBounds.isEmpty() ? TypeDef.OBJECT : erase(upperBounds.get(0), boundOwner, owner);
        }
        if (unwrapped instanceof TypeDef.Array(TypeDef componentType, int dimensions, boolean _)) {
            return TypeDef.array(erase(componentType, boundOwner, owner), dimensions);
        }
        return unwrapped;
    }

    /**
     * The first bound of a type variable: its own, then the declaring type's, then the ancestor's. A
     * variable left after substitution belongs to the declaring type, which the ancestor knows nothing
     * about, so that lookup comes first.
     *
     * @param typeVariable The variable
     * @param boundOwner   The declaring type, when the variable may be bound there
     * @param owner        The type the variable was read from
     * @return The bound or {@code null} when the variable is unbounded
     */
    @Nullable
    private static TypeDef boundOf(TypeDef.TypeVariable typeVariable, @Nullable TypeInfo boundOwner, TypeInfo owner) {
        if (!typeVariable.bounds().isEmpty()) {
            return typeVariable.bounds().get(0);
        }
        if (boundOwner != null) {
            TypeDef bound = boundOwner.variableBound(typeVariable.name());
            if (bound != null) {
                return bound;
            }
        }
        return owner.variableBound(typeVariable.name());
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
    record BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
    }

    private record Node(TypeInfo info, Map<String, TypeDef> substitution) {
    }

    /**
     * The declaring side of the comparison, resolved once per {@link #resolve}.
     *
     * @param objectDef            The declaring type
     * @param declaringInfo        The declaring type as a {@link TypeInfo}, for its variable bounds
     * @param methodDef            The declared method
     * @param parameterDescriptors The erased parameter descriptors of the declared method
     * @param returnDescriptor     The erased return descriptor of the declared method
     */
    private record Declared(ObjectDef objectDef,
                            TypeInfo declaringInfo,
                            MethodDef methodDef,
                            List<String> parameterDescriptors,
                            String returnDescriptor) {
    }

    /**
     * The inherited method as two views: the override check compares the substituted declaration
     * against the declared method, the bridge carries the declaration-site erasure.
     *
     * @param name                   The method name
     * @param overrideParameterTypes The parameter types to substitute for the override check
     * @param bridgeParameterTypes   The parameter types to erase for the bridge
     * @param bridgeReturnType       The return type to erase for the bridge
     * @param finalMethod            Whether the method is final and so cannot be overridden
     * @param packagePrivate         Whether the method is only overridable from its own package
     */
    private record MethodSignature(String name,
                                   List<TypeDef> overrideParameterTypes,
                                   List<TypeDef> bridgeParameterTypes,
                                   TypeDef bridgeReturnType,
                                   boolean finalMethod,
                                   boolean packagePrivate) {
    }

    /**
     * The hierarchy metadata of one supertype.
     */
    private interface TypeInfo {

        String typeName();

        /**
         * @return The package, deciding whether a package-private method can be overridden
         */
        default String packageName() {
            String name = typeName();
            int index = name.lastIndexOf('.');
            return index == -1 ? "" : name.substring(0, index);
        }

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
            boolean isInterface = objectDef instanceof InterfaceDef;
            return objectDef.getMethods().stream()
                .filter(m -> !m.isConstructor()
                    && !m.getModifiers().contains(Modifier.STATIC)
                    && !m.getModifiers().contains(Modifier.PRIVATE))
                .map(m -> {
                    List<TypeDef> parameters = m.getParameters().stream().map(ParameterDef::getType).toList();
                    boolean packagePrivate = !isInterface
                        && !m.getModifiers().contains(Modifier.PUBLIC)
                        && !m.getModifiers().contains(Modifier.PROTECTED);
                    return new MethodSignature(m.getName(), parameters, parameters, m.getReturnType(),
                        m.getModifiers().contains(Modifier.FINAL), packagePrivate);
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
                    int modifiers = m.getModifiers();
                    boolean packagePrivate = !java.lang.reflect.Modifier.isPublic(modifiers)
                        && !java.lang.reflect.Modifier.isProtected(modifiers);
                    return new MethodSignature(m.getName(), parameters, parameters, convert(m.getGenericReturnType()),
                        java.lang.reflect.Modifier.isFinal(modifiers), packagePrivate);
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
            classElement.getSuperType().ifPresent(s -> superTypes.add(TypeDef.of(s, ignore -> null, false)));
            for (ClassElement superinterface : classElement.getInterfaces()) {
                superTypes.add(TypeDef.of(superinterface, ignore -> null, false));
            }
            return superTypes;
        }

        private static MethodSignature signatureOf(MethodElement method) {
            // The AST contextualizes placeholder bounds along the way, so the override check uses the
            // generic view while the bridge takes the declaration's raw view, which erases correctly
            List<TypeDef> overrideParameters = Arrays.stream(method.getParameters())
                .map(p -> TypeDef.of(p.getGenericType(), ignore -> null, false))
                .toList();
            List<TypeDef> bridgeParameters = Arrays.stream(method.getParameters())
                .map(p -> TypeDef.erasure(p.getType()))
                .toList();
            return new MethodSignature(method.getName(), overrideParameters, bridgeParameters, TypeDef.erasure(method.getReturnType()),
                method.isFinal(), !method.isPublic() && !method.isProtected());
        }
    }
}
