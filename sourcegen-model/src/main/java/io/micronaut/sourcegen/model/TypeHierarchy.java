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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Walks the supertypes of a definition and the methods they declare, with the type arguments of each supertype
 * substituted - from Sourcegen model, reflection, and annotation-processing types.
 *
 * <p>Both the bridges a bytecode writer adds and the signatures a source generator overrides with are resolved
 * from this walk. A supertype represented only by a name has no method metadata, so it is skipped unless a lookup
 * of its element is given.</p>
 *
 * @since 2.2
 */
@Internal
public final class TypeHierarchy {

    private static final Set<String> TERMINAL_TYPES = Set.of("java.lang.Object", "java.lang.Record", "java.lang.Enum");

    private TypeHierarchy() {
    }

    /**
     * Visits the methods of every supertype of a definition, breadth first, each supertype once.
     *
     * @param objectDef          The definition
     * @param classElementLookup Looks up the element of a supertype only known by name, or {@code null}
     * @param visitor            The visitor
     */
    public static void visitInheritedMethods(ObjectDef objectDef,
                                             @Nullable Function<String, @Nullable ClassElement> classElementLookup,
                                             InheritedMethodVisitor visitor) {
        Deque<InheritedType> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (TypeDef superType : superTypesOf(objectDef)) {
            enqueue(queue, visited, superType, Map.of(), false, classElementLookup);
        }
        while (!queue.isEmpty()) {
            InheritedType type = queue.removeFirst();
            for (InheritedMethod method : type.info.methods()) {
                if (!visitor.visit(type, method)) {
                    return;
                }
            }
            for (TypeDef superType : type.info.superTypes()) {
                enqueue(queue, visited, type.substitute(superType), type.substitution, type.raw, classElementLookup);
            }
        }
    }

    /**
     * The definition itself as a type of the hierarchy, to erase its own type variables.
     *
     * @param objectDef The definition
     * @return The type
     */
    public static InheritedType declaring(ObjectDef objectDef) {
        return new InheritedType(new ModelInfo(objectDef), Map.of(), false);
    }

    /**
     * The direct supertypes of a definition.
     *
     * @param objectDef The definition
     * @return The superclass, if any, and the superinterfaces
     */
    public static List<TypeDef> superTypesOf(ObjectDef objectDef) {
        List<TypeDef> result = new ArrayList<>();
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            result.add(classDef.getSuperclass());
        }
        result.addAll(objectDef.getSuperinterfaces());
        return result;
    }

    /**
     * Unwraps an annotated type.
     *
     * @param type The type
     * @return The type without its annotations
     */
    public static TypeDef unwrap(TypeDef type) {
        if (type instanceof TypeDef.AnnotatedTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        if (type instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return unwrap(annotated.typeDef());
        }
        return type;
    }

    /**
     * Whether a type refers to a type variable that is not one of the given ones.
     *
     * @param type      The type
     * @param variables The allowed variable names
     * @return true if another variable is referenced
     */
    public static boolean containsVariableOtherThan(TypeDef type, Set<String> variables) {
        return containsVariable(type, name -> !variables.contains(name));
    }

    /**
     * Whether a type refers to a variable a generic method declares, as renamed by
     * {@link InheritedType#substitute(TypeDef, List)}.
     *
     * @param type The type
     * @return true if a variable of the method is referenced
     */
    public static boolean containsMethodVariable(TypeDef type) {
        return containsVariable(type, name -> name.endsWith(InheritedType.methodVariable("")));
    }

    private static boolean containsVariable(TypeDef type, Predicate<String> matches) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            return matches.test(variable.name());
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.typeArguments().stream().anyMatch(argument -> containsVariable(argument, matches));
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return containsVariable(array.componentType(), matches);
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().stream().anyMatch(bound -> containsVariable(bound, matches))
                || wildcard.lowerBounds().stream().anyMatch(bound -> containsVariable(bound, matches));
        }
        return false;
    }

    /**
     * The name of an erased type, to compare erasures: the binary name of a class, the name of a primitive, and
     * the component name followed by {@code []} per dimension of an array.
     *
     * @param erased A type as returned by {@link InheritedType#erase(TypeDef)}
     * @return The name
     */
    public static String erasedName(TypeDef erased) {
        return erasedName(erased, null);
    }

    /**
     * The name of an erased type in the scope of a definition, which resolves a simple name of the definition or of
     * one of its member types, as {@link #binaryName(ClassTypeDef, ObjectDef)} does.
     *
     * @param erased    A type as returned by {@link InheritedType#erase(TypeDef)}
     * @param objectDef The definition, or {@code null}
     * @return The name
     */
    public static String erasedName(TypeDef erased, @Nullable ObjectDef objectDef) {
        TypeDef unwrapped = unwrap(erased);
        if (unwrapped instanceof TypeDef.Array array) {
            return erasedName(array.componentType(), objectDef) + "[]".repeat(array.dimensions());
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return erasedName(parameterized.rawType(), objectDef);
        }
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            return binaryName(classTypeDef, objectDef);
        }
        if (unwrapped instanceof TypeDef.Primitive primitive) {
            return primitive.clazz().getName();
        }
        return TypeDef.OBJECT.getName();
    }

    /**
     * Returns the binary name a reference to a type resolves to in the scope of the type being written.
     *
     * <p>The model is immutable, so {@link ObjectDefBuilder#addInnerType} can only qualify the copy of a member type
     * it stores; the definition the caller holds on to keeps the simple name it was built with, and a reference taken
     * from it reads as {@code Inner} rather than {@code com.example.Outer$Inner}. Java source has scoping that makes
     * such a reference resolve anyway - javac reads {@code Inner} inside {@code Outer} as its member type - while a
     * class file has none: every name in it is a binary name. This applies the same scoping, so that the generators
     * accept the same definition.</p>
     *
     * <p>An unqualified name is resolved against the type being written and the member types it declares,
     * which is the scope a definition knows about. A name that is already qualified, and one that matches
     * nothing in scope, is left as it is.</p>
     *
     * @param classTypeDef The referenced type
     * @param objectDef    The contextual object, if any
     * @return The binary name
     */
    public static String binaryName(ClassTypeDef classTypeDef, @Nullable ObjectDef objectDef) {
        String name = classTypeDef.getName();
        if (objectDef == null || name.indexOf('.') != -1 || name.indexOf('$') != -1) {
            return name;
        }
        if (name.equals(objectDef.getSimpleName())) {
            return objectDef.asTypeDef().getName();
        }
        for (ObjectDef innerType : objectDef.getInnerTypes()) {
            if (name.equals(innerType.getSimpleName())) {
                return innerType.asTypeDef().getName();
            }
        }
        return name;
    }

    /**
     * The bound a type variable erases to: the first one that is not {@code Object}, as a JVM descriptor takes it -
     * a variable can list {@code Object} ahead of its real bound.
     *
     * @param bounds The bounds
     * @return The bound, or {@code null} without bounds
     */
    @Nullable
    private static TypeDef erasureBound(List<TypeDef> bounds) {
        for (TypeDef bound : bounds) {
            if (!(unwrap(bound) instanceof ClassTypeDef classTypeDef) || !classTypeDef.getName().equals(TypeDef.OBJECT.getName())) {
                return bound;
            }
        }
        return bounds.isEmpty() ? null : bounds.get(0);
    }

    /**
     * Whether a type has the other among its supertypes, from the model, reflection or annotation-processing type
     * of each - so that a type generated in the same round, which cannot be loaded, is known as well.
     *
     * @param type               The type
     * @param supertypeName      The binary name of the supertype
     * @param classElementLookup Looks up the element of a type only known by name, or {@code null}
     * @return true if the type is the supertype or inherits it
     */
    public static boolean inherits(ClassTypeDef type,
                                   String supertypeName,
                                   @Nullable Function<String, @Nullable ClassElement> classElementLookup) {
        if (TypeDef.OBJECT.getName().equals(supertypeName)) {
            return true;
        }
        Deque<ClassTypeDef> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(rawTypeOf(type));
        while (!queue.isEmpty()) {
            ClassTypeDef current = queue.removeFirst();
            if (current.getName().equals(supertypeName)) {
                return true;
            }
            if (!visited.add(current.getName())) {
                continue;
            }
            TypeInfo info = typeInfoOf(current, classElementLookup);
            if (info == null) {
                continue;
            }
            for (TypeDef superType : info.superTypes()) {
                if (unwrap(superType) instanceof ClassTypeDef superClassType) {
                    queue.addLast(rawTypeOf(superClassType));
                }
            }
        }
        return false;
    }

    /**
     * A type as one of its supertypes, with the type arguments it inherits that supertype with - from the model,
     * reflection or annotation-processing type of each on the way.
     *
     * @param type               The type
     * @param supertypeName      The binary name of the supertype
     * @param classElementLookup Looks up the element of a type only known by name, or {@code null}
     * @return The supertype, parameterized where it is inherited so, or {@code null} where it is not inherited
     */
    @Nullable
    public static ClassTypeDef asSupertype(ClassTypeDef type,
                                           String supertypeName,
                                           @Nullable Function<String, @Nullable ClassElement> classElementLookup) {
        Deque<ClassTypeDef> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ClassTypeDef current = queue.removeFirst();
            ClassTypeDef raw = rawTypeOf(current);
            if (raw.getName().equals(supertypeName)) {
                return current;
            }
            if (!visited.add(raw.getName())) {
                continue;
            }
            TypeInfo info = typeInfoOf(raw, classElementLookup);
            if (info == null) {
                continue;
            }
            Map<String, TypeDef> substitution = new HashMap<>();
            if (current instanceof ClassTypeDef.Parameterized parameterized) {
                List<String> variables = info.typeParameters();
                for (int i = 0; i < variables.size() && i < parameterized.typeArguments().size(); i++) {
                    substitution.put(variables.get(i), parameterized.typeArguments().get(i));
                }
            }
            for (TypeDef superType : info.superTypes()) {
                if (substitute(superType, substitution) instanceof ClassTypeDef superClassType) {
                    queue.addLast(superClassType);
                }
            }
        }
        return TypeDef.OBJECT.getName().equals(supertypeName) ? TypeDef.OBJECT : null;
    }

    private static ClassTypeDef rawTypeOf(ClassTypeDef type) {
        ClassTypeDef raw = type;
        while (raw instanceof ClassTypeDef.Parameterized parameterized) {
            raw = parameterized.rawType();
        }
        return raw;
    }

    /**
     * Converts a reflective type, keeping the type arguments of a parameterized one.
     *
     * @param type The type
     * @return The type
     */
    public static TypeDef typeDefOf(Type type) {
        return ReflectionInfo.convert(type);
    }

    private static void enqueue(Deque<InheritedType> queue,
                                Set<String> visited,
                                TypeDef edge,
                                Map<String, TypeDef> outerSubstitution,
                                boolean outerRaw,
                                @Nullable Function<String, @Nullable ClassElement> classElementLookup) {
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
        TypeInfo info = typeInfoOf(rawType, classElementLookup);
        if (info == null) {
            return;
        }
        Map<String, TypeDef> substitution = new HashMap<>();
        List<String> variables = info.typeParameters();
        for (int i = 0; i < variables.size() && i < arguments.size(); i++) {
            substitution.put(variables.get(i), arguments.get(i));
        }
        // A generic type named without its arguments is raw, and so are the supertypes it is inherited with
        boolean raw = outerRaw || !variables.isEmpty() && arguments.isEmpty();
        String key = info.typeName() + arguments.stream()
            .map(argument -> erasedName(erase(argument, null, info)))
            .collect(Collectors.joining(",", "<", ">")) + (raw ? "raw" : "");
        if (visited.add(key)) {
            queue.addLast(new InheritedType(info, substitution, raw));
        }
    }

    @Nullable
    private static TypeInfo typeInfoOf(ClassTypeDef type, @Nullable Function<String, @Nullable ClassElement> classElementLookup) {
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
        if (classElementLookup != null) {
            ClassElement classElement = classElementLookup.apply(type.getName());
            if (classElement != null) {
                return new AstInfo(classElement);
            }
        }
        return null;
    }

    /**
     * Substitutes type variables by name, at any depth of the type.
     *
     * @param type         The type
     * @param substitution The types to substitute for the variables
     * @return The substituted type
     */
    public static TypeDef substituted(TypeDef type, Map<String, TypeDef> substitution) {
        return substitute(type, substitution);
    }

    private static TypeDef substitute(TypeDef type, Map<String, TypeDef> substitution) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            TypeDef replacement = substitution.get(variable.name());
            if (replacement == null) {
                return variable;
            }
            return variable.isNullable() ? replacement.makeNullable() : replacement;
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return new ClassTypeDef.Parameterized(parameterized.rawType(), parameterized.typeArguments().stream()
                .map(argument -> substitute(argument, substitution)).toList());
        }
        if (unwrapped instanceof TypeDef.Array array) {
            TypeDef substituted = TypeDef.array(substitute(array.componentType(), substitution), array.dimensions());
            return array.isNullable() ? substituted.makeNullable() : substituted;
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return new TypeDef.Wildcard(wildcard.upperBounds().stream()
                .map(bound -> substitute(bound, substitution)).toList(), wildcard.lowerBounds().stream()
                .map(bound -> substitute(bound, substitution)).toList());
        }
        return unwrapped;
    }

    private static TypeDef erase(TypeDef type, @Nullable TypeInfo boundOwner, TypeInfo owner) {
        TypeDef unwrapped = unwrap(type);
        if (TypeDef.THIS.equals(unwrapped)) {
            return ClassTypeDef.of(owner.typeName());
        }
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            TypeDef bound = !variable.bounds().isEmpty() ? erasureBound(variable.bounds())
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
            TypeDef erased = TypeDef.array(erase(array.componentType(), boundOwner, owner), array.dimensions());
            return array.isNullable() ? erased.makeNullable() : erased;
        }
        return unwrapped;
    }

    /**
     * Visits an inherited method.
     */
    @FunctionalInterface
    public interface InheritedMethodVisitor {
        /**
         * @param type   The supertype declaring the method
         * @param method The method
         * @return true to continue with the next method, false to stop the walk
         */
        boolean visit(InheritedType type, InheritedMethod method);
    }

    /**
     * A method declared by a supertype, as declared: the type variables of the supertype are not substituted.
     *
     * @param name                The name
     * @param overrideParameters  The generic parameter types, as an override sees them
     * @param bridgeParameters    The parameter types of the declaration, as a bridge erases them
     * @param returnType          The erased return type
     * @param genericReturnType   The generic return type
     * @param finalMethod         Whether the method is final
     * @param packagePrivate      Whether the method is package-private
     * @param typeVariables       The names of the type variables the method declares of its own, which shadow those
     *                            of the supertype and are bound by nothing it is inherited with
     */
    public record InheritedMethod(String name,
                                  List<TypeDef> overrideParameters,
                                  List<TypeDef> bridgeParameters,
                                  TypeDef returnType,
                                  TypeDef genericReturnType,
                                  boolean finalMethod,
                                  boolean packagePrivate,
                                  List<String> typeVariables) {
    }

    /**
     * A type of the hierarchy with the type arguments it is inherited with.
     */
    public static final class InheritedType {
        private final TypeInfo info;
        private final Map<String, TypeDef> substitution;
        private final boolean raw;

        private InheritedType(TypeInfo info, Map<String, TypeDef> substitution, boolean raw) {
            this.info = info;
            this.substitution = substitution;
            this.raw = raw;
        }

        /**
         * @return Whether the type is inherited raw, directly or through a raw supertype: its members are erased,
         * and its type variables are bound to nothing
         */
        public boolean isRaw() {
            return raw;
        }

        /**
         * @return The binary name
         */
        public String getName() {
            return info.typeName();
        }

        /**
         * @return The package name
         */
        public String getPackageName() {
            return info.packageName();
        }

        /**
         * @return The names of the type variables the type declares
         */
        public List<String> getTypeParameters() {
            return info.typeParameters();
        }

        /**
         * @param variableName The name of a type variable the type declares
         * @return All the bounds it is declared with - `CharSequence` and `Serializable` for
         * `V extends CharSequence & Serializable` - or none
         */
        public List<TypeDef> getBounds(String variableName) {
            return info.variableBounds(variableName);
        }

        /**
         * Substitutes the type arguments this type is inherited with.
         *
         * @param type A type in the scope of this type
         * @return The substituted type
         */
        public TypeDef substitute(TypeDef type) {
            return TypeHierarchy.substitute(type, substitution);
        }

        /**
         * Substitutes the type arguments this type is inherited with, except for variables a generic method declares
         * of its own, which shadow the type's variables of the same name. Those are renamed to
         * {@link #methodVariable(String)}, so that they cannot be taken for a variable of the same name a type
         * argument names.
         *
         * @param type     A type in the scope of a method of this type
         * @param shadowed The names of the variables the method declares
         * @return The substituted type
         */
        public TypeDef substitute(TypeDef type, List<String> shadowed) {
            if (shadowed.isEmpty()) {
                return substitute(type);
            }
            Map<String, TypeDef> visible = new HashMap<>(substitution);
            shadowed.forEach(name -> visible.put(name, TypeDef.variable(methodVariable(name))));
            return TypeHierarchy.substitute(type, visible);
        }

        /**
         * The name a variable declared by a generic method has after {@link #substitute(TypeDef, List)}, which no
         * variable of a type can have.
         *
         * @param name The declared name
         * @return The name
         */
        public static String methodVariable(String name) {
            return name + " (method)";
        }

        /**
         * Erases a type in the scope of this type.
         *
         * @param type The type
         * @return The erased type
         */
        public TypeDef erase(TypeDef type) {
            return TypeHierarchy.erase(type, null, info);
        }

        /**
         * Erases a type in the scope of this type, taking the bounds of its type variables from another type -
         * the declaring definition, for a type already substituted into its scope.
         *
         * @param type       The type
         * @param boundOwner The type declaring the variables
         * @return The erased type
         */
        public TypeDef erase(TypeDef type, InheritedType boundOwner) {
            return TypeHierarchy.erase(type, boundOwner.info, info);
        }
    }

    private interface TypeInfo {
        String typeName();

        List<String> typeParameters();

        @Nullable
        TypeDef variableBound(String name);

        List<TypeDef> variableBounds(String name);

        List<InheritedMethod> methods();

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
                .map(variable -> erasureBound(variable.bounds())).filter(Objects::nonNull).findFirst().orElse(null);
        }

        @Override
        public List<TypeDef> variableBounds(String name) {
            return variables().stream().filter(variable -> variable.name().equals(name))
                .findFirst().map(TypeDef.TypeVariable::bounds).orElse(List.of());
        }

        @Override
        public List<InheritedMethod> methods() {
            boolean interfaceType = objectDef instanceof InterfaceDef;
            return objectDef.getMethods().stream()
                .filter(method -> !method.isConstructor()
                    && !method.getModifiers().contains(Modifier.STATIC)
                    && !method.getModifiers().contains(Modifier.PRIVATE))
                .map(method -> {
                    List<TypeDef> parameters = method.getParameters().stream().map(ParameterDef::getType).toList();
                    boolean packagePrivate = !interfaceType && !method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.PROTECTED);
                    // A type can name a variable of the method without its bounds, which the method declares
                    Map<String, TypeDef> declared = new HashMap<>();
                    method.getTypeVariables().forEach(variable -> declared.put(variable.name(), variable));
                    return new InheritedMethod(method.getName(), parameters,
                        parameters.stream().map(parameter -> substitute(parameter, declared)).toList(),
                        substitute(method.getReturnType(), declared), method.getReturnType(),
                        method.getModifiers().contains(Modifier.FINAL), packagePrivate,
                        method.getTypeVariables().stream().map(TypeDef.TypeVariable::name).toList());
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
        public List<TypeDef> variableBounds(String name) {
            return Arrays.stream(type.getTypeParameters()).filter(variable -> variable.getName().equals(name))
                .findFirst().map(variable -> Arrays.stream(variable.getBounds()).map(ReflectionInfo::convert).toList())
                .orElse(List.of());
        }

        @Override
        public List<InheritedMethod> methods() {
            return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && !java.lang.reflect.Modifier.isPrivate(method.getModifiers()) && !method.isSynthetic())
                .map(method -> {
                    List<TypeDef> parameters = Arrays.stream(method.getGenericParameterTypes())
                        .map(ReflectionInfo::convert).toList();
                    int modifiers = method.getModifiers();
                    List<TypeDef> erasedParameters = Arrays.stream(method.getParameterTypes()).map(TypeDef::of).toList();
                    return new InheritedMethod(method.getName(), parameters, erasedParameters,
                        TypeDef.of(method.getReturnType()), convert(method.getGenericReturnType()),
                        java.lang.reflect.Modifier.isFinal(modifiers),
                        !java.lang.reflect.Modifier.isPublic(modifiers)
                            && !java.lang.reflect.Modifier.isProtected(modifiers),
                        Arrays.stream(method.getTypeParameters()).map(java.lang.reflect.TypeVariable::getName).toList());
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
            return placeholders().stream().map(GenericPlaceholderElement::getVariableName).toList();
        }

        @Override
        @Nullable
        public TypeDef variableBound(String name) {
            for (GenericPlaceholderElement placeholder : placeholders()) {
                if (placeholder.getVariableName().equals(name) && !placeholder.getBounds().isEmpty()) {
                    return TypeDef.erasure(placeholder.getBounds().get(0));
                }
            }
            return null;
        }

        @Override
        public List<TypeDef> variableBounds(String name) {
            for (GenericPlaceholderElement placeholder : placeholders()) {
                if (placeholder.getVariableName().equals(name)) {
                    return placeholder.getBounds().stream()
                        .map(bound -> TypeDef.of(bound, ignore -> null, false)).toList();
                }
            }
            return List.of();
        }

        /**
         * The declared placeholders. The Groovy element of a parameterized type reports its type arguments in their
         * place, so each entry is checked before it is used as a placeholder.
         *
         * @return The placeholders
         */
        private List<GenericPlaceholderElement> placeholders() {
            List<GenericPlaceholderElement> result = new ArrayList<>();
            for (Object placeholder : classElement.getDeclaredGenericPlaceholders()) {
                if (placeholder instanceof GenericPlaceholderElement genericPlaceholder) {
                    result.add(genericPlaceholder);
                }
            }
            return result;
        }

        @Override
        public List<InheritedMethod> methods() {
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

        private static InheritedMethod signatureOf(MethodElement method) {
            List<TypeDef> overrideParameters = Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false)).toList();
            List<TypeDef> bridgeParameters = Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.erasure(parameter.getType())).toList();
            return new InheritedMethod(method.getName(), overrideParameters, bridgeParameters,
                TypeDef.erasure(method.getReturnType()), TypeDef.of(method.getGenericReturnType(), ignore -> null, false),
                method.isFinal(), !method.isPublic() && !method.isProtected(),
                method.getDeclaredTypeVariables().stream().map(GenericPlaceholderElement::getVariableName).toList());
        }
    }
}
