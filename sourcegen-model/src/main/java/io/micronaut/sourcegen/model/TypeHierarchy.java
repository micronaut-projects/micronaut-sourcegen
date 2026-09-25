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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Walks the supertypes of a definition and the methods they declare, with the type arguments of each supertype
 * substituted - from Sourcegen model, reflection, and annotation-processing types.
 *
 * <p>Both the bridges a bytecode writer adds and the signatures a source generator overrides with are resolved
 * from this walk. A supertype represented only by a name has no method metadata, so it is skipped unless a lookup
 * of its element is given.</p>
 *
 * @since 2.3
 */
@Internal
public final class TypeHierarchy {

    private static final Set<String> TERMINAL_TYPES = Set.of("java.lang.Object");

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
        visitInheritedMethods(objectDef, Lookup.of(classElementLookup), visitor);
    }

    /**
     * Visits the methods of every supertype of a definition, breadth first, each supertype once.
     *
     * @param objectDef The definition
     * @param lookup    Looks up the definition or the element of a supertype only known by name
     * @param visitor   The visitor
     * @since 2.3
     */
    public static void visitInheritedMethods(ObjectDef objectDef, @Nullable Lookup lookup, InheritedMethodVisitor visitor) {
        Deque<InheritedType> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        // A type named without a package, from a definition in one, is of that package: the default package cannot
        // be named from another. It is a member type named by its simple name
        String scopePackage = packageOf(objectDef);
        for (TypeDef superType : superTypesOf(objectDef)) {
            enqueue(queue, visited, superType, Map.of(), false, lookup, scopePackage);
        }
        while (!queue.isEmpty()) {
            InheritedType type = queue.removeFirst();
            for (InheritedMethod method : type.info.methods()) {
                if (!visitor.visit(type, method)) {
                    return;
                }
            }
            for (TypeDef superType : type.info.superTypes()) {
                // `enqueue` substitutes the arguments of the edge: substituting here as well would substitute an
                // argument that names a variable of the same name twice
                enqueue(queue, visited, superType, type.substitution, type.raw, lookup, scopePackage);
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
        return declaring(objectDef, List.of());
    }

    /**
     * The definition itself as a type of the hierarchy, to erase its own type variables and those of the types
     * enclosing it that are in its scope - the variables of the enclosing class of an inner class.
     *
     * @param objectDef          The definition
     * @param enclosingVariables The variables of the enclosing types in scope, the nearest first
     * @return The type
     * @since 2.3
     */
    public static InheritedType declaring(ObjectDef objectDef, List<TypeDef.TypeVariable> enclosingVariables) {
        return new InheritedType(new ModelInfo(objectDef, enclosingVariables), Map.of(), false, packageOf(objectDef));
    }

    /**
     * The variables a method declares as they read in its scope: each with its bounds, {@code Object} for one
     * declared without - so that a reference to one by its name alone erases to what the method declares it with,
     * and not to the bound of a variable of the class of the same name - and a bound naming another variable of the
     * method with that variable's bounds.
     *
     * @param variables The variables the method declares
     * @return The variables by their names
     * @since 2.3
     */
    public static Map<String, TypeDef> methodScope(List<TypeDef.TypeVariable> variables) {
        return boundVariables(variables, Map.of(), UnaryOperator.identity());
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
        // The superclass an enum and a record have without declaring it
        if (objectDef instanceof EnumDef) {
            result.add(TypeDef.parameterized(Enum.class, objectDef.asTypeDef()));
        } else if (objectDef instanceof RecordDef) {
            result.add(ClassTypeDef.of(Record.class));
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
        return TypeOperations.unwrap(type);
    }

    /**
     * Whether a type refers to a type variable that is not one of the given ones.
     *
     * @param type      The type
     * @param variables The allowed variable names
     * @return true if another variable is referenced
     */
    public static boolean containsVariableOtherThan(TypeDef type, Set<String> variables) {
        return TypeOperations.containsVariableOtherThan(type, variables);
    }

    /**
     * Whether a type names a type variable: itself, or in its type arguments, the component of an array, the bounds
     * of a wildcard, or the enclosing type of a member - {@code Outer<X>.Member} names {@code X}.
     *
     * @param type    The type
     * @param matches Tells the names of the variables looked for
     * @return true where a variable of a matching name is named
     * @since 2.3
     */
    public static boolean mentionsVariable(TypeDef type, Predicate<String> matches) {
        return TypeOperations.mentionsVariable(type, matches);
    }

    /**
     * The package of a definition. A member type is of the package of its outermost type, which the binary name
     * tells: the canonical name of {@code pkg.Outer$Member} ends its package at {@code Outer}.
     *
     * @param objectDef The definition
     * @return The package name
     */
    public static String packageOf(ObjectDef objectDef) {
        String binaryName = objectDef.asTypeDef().getName();
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? "" : binaryName.substring(0, i);
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
     * The bound a type variable erases to: its leftmost one (JLS 4.6), as the bytecode writers describe it -
     * {@code Object} for {@code T extends Object & Comparable<T>}.
     *
     * @param bounds The bounds
     * @return The bound, or {@code null} without bounds
     */
    @Nullable
    private static TypeDef erasureBound(List<TypeDef> bounds) {
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
        return inherits(type, supertypeName, Lookup.of(classElementLookup));
    }

    /**
     * Whether a type has the other among its supertypes, from the model, reflection or annotation-processing type
     * of each - so that a type generated in the same round, which cannot be loaded, is known as well.
     *
     * @param type          The type
     * @param supertypeName The binary name of the supertype
     * @param lookup        Looks up the definition or the element of a type only known by name
     * @return true if the type is the supertype or inherits it
     * @since 2.3
     */
    public static boolean inherits(ClassTypeDef type, String supertypeName, @Nullable Lookup lookup) {
        if (TypeDef.OBJECT.getName().equals(supertypeName)) {
            return true;
        }
        Deque<ClassTypeDef> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(TypeOperations.declaredClass(type));
        while (!queue.isEmpty()) {
            ClassTypeDef current = queue.removeFirst();
            if (current.getName().equals(supertypeName)) {
                return true;
            }
            if (!visited.add(current.getName())) {
                continue;
            }
            TypeInfo info = typeInfoOf(current, lookup);
            if (info == null) {
                continue;
            }
            if (!info.typeName().equals(current.getName())) {
                // A member type named by its simple name, which the lookup qualifies
                if (info.typeName().equals(supertypeName)) {
                    return true;
                }
                visited.add(info.typeName());
            }
            for (TypeDef superType : info.superTypes()) {
                if (unwrap(superType) instanceof ClassTypeDef superClassType) {
                    queue.addLast(TypeOperations.declaredClass(superClassType));
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
        return asSupertype(type, supertypeName, Lookup.of(classElementLookup));
    }

    /**
     * A type as one of its supertypes, with the type arguments it inherits that supertype with - from the model,
     * reflection or annotation-processing type of each on the way. A raw type inherits its supertypes raw (JLS 4.8).
     *
     * @param type          The type
     * @param supertypeName The binary name of the supertype
     * @param lookup        Looks up the definition or the element of a type only known by name
     * @return The supertype, parameterized where it is inherited so, or {@code null} where it is not inherited
     * @since 2.3
     */
    @Nullable
    public static ClassTypeDef asSupertype(ClassTypeDef type, String supertypeName, @Nullable Lookup lookup) {
        return TypeOperations.asSupertype(type, supertypeName, lookup);
    }

    /**
     * The enclosing type of a member class, with its type arguments: {@code Outer<String>} of
     * {@code Outer<String>.Member}. A compiler element names it in its type mirror, a substituted or reflected
     * member carries it.
     *
     * @param type The class type, not parameterized: the raw type of a parameterized one
     * @return The enclosing type, or {@code null} where the type is no member of a parameterized type
     * @since 2.3
     */
    public static @Nullable ClassTypeDef enclosingOf(ClassTypeDef type) {
        if (type instanceof EnclosedClassType enclosed) {
            return enclosed.enclosing();
        }
        if (type instanceof ClassTypeDef.ClassElementType elementType) {
            return MemberTypes.enclosingOf(elementType.classElement());
        }
        return null;
    }

    /**
     * The enclosing type a substituted or reflected member carries, without reading that of an element.
     *
     * @param type The class type
     * @return The enclosing type it carries, or {@code null}
     * @since 2.3
     */
    public static @Nullable ClassTypeDef carriedEnclosingOf(ClassTypeDef type) {
        return type instanceof EnclosedClassType enclosed ? enclosed.enclosing() : null;
    }

    /**
     * The member class of a type carrying its enclosing type, or the type itself.
     *
     * @param type The class type
     * @return The member class
     * @since 2.3
     */
    public static ClassTypeDef memberClass(ClassTypeDef type) {
        return type instanceof EnclosedClassType enclosed ? enclosed.member() : type;
    }

    /**
     * A member class named with a parameterized enclosing type, {@code Outer<String>.Member}; its own type arguments
     * are those of a {@link ClassTypeDef.Parameterized} of it.
     *
     * @param enclosing The enclosing type
     * @param member    The member class
     * @return The member type
     * @since 2.3
     */
    public static ClassTypeDef memberType(ClassTypeDef enclosing, ClassTypeDef member) {
        return new EnclosedClassType(enclosing, member);
    }

    /**
     * The type arguments the enclosing types of a member bind their variables with: {@code T} of an
     * {@code Outer<Integer>.Inner<String>} is {@code Integer}.
     *
     * @param type   The type
     * @param lookup Looks up the definition or the element of a type only known by name
     * @return The arguments by the variables of the enclosing types, empty for a type that is no such member
     * @since 2.3
     */
    static Map<String, TypeDef> enclosingArguments(ClassTypeDef type, @Nullable Lookup lookup) {
        ClassTypeDef raw = type instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : type;
        ClassTypeDef enclosingType = enclosingOf(raw);
        if (enclosingType == null) {
            return Map.of();
        }
        // The outermost first, so that a nearer type's variable shadows one of the same name
        Map<String, TypeDef> result = new HashMap<>(enclosingArguments(enclosingType, lookup));
        if (enclosingType instanceof ClassTypeDef.Parameterized enclosing) {
            TypeInfo info = typeInfoOf(TypeOperations.declaredClass(enclosing), lookup);
            if (info != null) {
                List<String> variables = info.typeParameters();
                for (int i = 0; i < variables.size() && i < enclosing.typeArguments().size(); i++) {
                    result.put(variables.get(i), enclosing.typeArguments().get(i));
                }
            }
        }
        return result;
    }

    /**
     * The type arguments a type binds the variables of its class with, and those its enclosing types bind:
     * {@code T}, {@code U} of an {@code Outer<Integer>.Inner<String>} are {@code Integer} and {@code String}. A
     * raw type binds none of its own.
     *
     * @param type   The type
     * @param lookup Looks up the definition or the element of a type only known by name, or {@code null}
     * @return The arguments by the variables they bind
     * @since 2.3
     */
    public static Map<String, TypeDef> typeArguments(ClassTypeDef type, @Nullable Lookup lookup) {
        Map<String, TypeDef> result = new HashMap<>(enclosingArguments(type, lookup));
        if (type instanceof ClassTypeDef.Parameterized parameterized) {
            TypeInfo info = typeInfoOf(TypeOperations.declaredClass(parameterized), lookup);
            List<String> variables = info == null ? List.of() : info.typeParameters();
            if (variables.size() == parameterized.typeArguments().size()) {
                for (int i = 0; i < variables.size(); i++) {
                    result.put(variables.get(i), parameterized.typeArguments().get(i));
                }
            }
        }
        return result;
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
                                @Nullable Lookup lookup,
                                String scopePackage) {
        TypeDef unwrapped = unwrap(edge);
        ClassTypeDef rawType;
        List<TypeDef> arguments;
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            rawType = TypeOperations.rawClass(parameterized);
            arguments = parameterized.typeArguments().stream()
                .map(argument -> TypeOperations.substitute(argument, outerSubstitution)).toList();
        } else if (unwrapped instanceof ClassTypeDef classTypeDef) {
            rawType = classTypeDef;
            arguments = List.of();
        } else {
            return;
        }
        TypeInfo info = typeInfoOf(rawType, lookup);
        if (info == null) {
            return;
        }
        // A member of a parameterized type binds the variables of its enclosing types too: `Outer<Integer>.Base`
        Map<String, TypeDef> substitution = new HashMap<>();
        enclosingArguments((ClassTypeDef) unwrapped, lookup)
            .forEach((name, argument) -> substitution.put(name, TypeOperations.substitute(argument, outerSubstitution)));
        List<String> variables = info.typeParameters();
        for (int i = 0; i < variables.size() && i < arguments.size(); i++) {
            substitution.put(variables.get(i), arguments.get(i));
        }
        // A generic type named without its arguments is raw, and so are the supertypes it is inherited with
        boolean raw = outerRaw || !variables.isEmpty() && arguments.isEmpty();
        String key = info.typeName() + arguments.stream()
            .map(argument -> erasedName(erase(argument, null, info)))
            .collect(Collectors.joining(",", "<", ">")) + (raw ? "raw" : "") + substitution.entrySet().stream()
            .filter(entry -> !variables.contains(entry.getKey())).sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + erasedName(erase(entry.getValue(), null, info)))
            .collect(Collectors.joining(","));
        if (visited.add(key)) {
            queue.addLast(new InheritedType(info, substitution, raw, scopePackage));
        }
    }

    /**
     * The bounds a class declares its type parameters with, in order, that name none of its variables: those a
     * wildcard argument is captured with besides its own (JLS 5.1.10) - a {@code NumberBox<?>} of a
     * {@code NumberBox<E extends Number>} holds Numbers.
     *
     * @param type The class
     * @return The bounds of each type parameter, empty where unknown
     */
    static List<List<TypeDef>> declaredBounds(ClassTypeDef type) {
        TypeInfo info = typeInfoOf(TypeOperations.declaredClass(type), Lookup.NONE);
        if (info == null) {
            return List.of();
        }
        return info.typeParameters().stream()
            .map(name -> info.variableBounds(name).stream()
                .filter(bound -> !mentionsVariable(bound, ignore -> true)).toList())
            .toList();
    }

    @Nullable
    static TypeInfo typeInfoOf(ClassTypeDef type, @Nullable Lookup scope) {
        if (TERMINAL_TYPES.contains(type.getName())) {
            return null;
        }
        Lookup lookup = scope == null ? Lookup.NONE : scope;
        if (type instanceof EnclosedClassType enclosed) {
            return typeInfoOf(enclosed.member(), lookup);
        }
        if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            ObjectDef objectDef = classDefType.objectDef();
            // A member type the caller holds on to keeps the simple name it was built with, where the lookup has
            // the copy its enclosing definition stores, named after it
            ObjectDef qualified = isSimpleName(objectDef.getName()) ? lookup.definition(objectDef.getName()) : null;
            return new ModelInfo(qualified == null ? objectDef : qualified);
        }
        if (type instanceof ClassTypeDef.JavaClass javaClass) {
            return new ReflectionInfo(javaClass.type());
        }
        if (type instanceof ClassTypeDef.ClassElementType classElementType) {
            return new AstInfo(classElementType.classElement());
        }
        ObjectDef definition = lookup.definition(type.getName());
        if (definition != null) {
            return new ModelInfo(definition);
        }
        ClassElement classElement = lookup.apply(type.getName());
        return classElement == null ? null : new AstInfo(classElement);
    }

    private static boolean isSimpleName(String name) {
        return name.indexOf('.') == -1 && name.indexOf('$') == -1;
    }

    /**
     * Substitutes type variables by name, at any depth of the type.
     *
     * @param type         The type
     * @param substitution The types to substitute for the variables
     * @return The substituted type
     */
    public static TypeDef substituted(TypeDef type, Map<String, TypeDef> substitution) {
        return TypeOperations.substitute(type, substitution);
    }

    /**
     * The variables a method declares, keyed by their names, with their bounds substituted - including the
     * variables of the method a bound names, {@code <V extends Number, U extends V>}, each with its own bound. A
     * variable declared without bounds is bounded by {@code Object}: it is the method's own, which a variable of the
     * type of the same name does not bound.
     */
    private static Map<String, TypeDef> boundVariables(List<TypeDef.TypeVariable> variables,
                                                       Map<String, TypeDef> substitution,
                                                       UnaryOperator<String> naming) {
        Map<String, TypeDef> bound = new HashMap<>(substitution);
        variables.forEach(variable -> bound.put(variable.name(), TypeDef.variable(naming.apply(variable.name()))));
        // Each round resolves the bounds one level further
        for (int round = 0; round < variables.size(); round++) {
            Map<String, TypeDef> inBounds = new HashMap<>(bound);
            variables.forEach(variable -> bound.put(variable.name(), TypeDef.variable(naming.apply(variable.name()),
                variable.bounds().isEmpty() ? List.of(TypeDef.OBJECT)
                    : variable.bounds().stream().map(type -> TypeOperations.substitute(type, inBounds)).toList())));
        }
        return bound;
    }

    private static TypeDef erase(TypeDef type, @Nullable TypeInfo boundOwner, TypeInfo owner) {
        TypeInfo bounds = boundOwner == null ? owner : boundOwner;
        return TypeOperations.erase(type, bounds::variableBound, ClassTypeDef.of(owner.typeName()));
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
                                  List<TypeDef.TypeVariable> typeVariables) {
    }

    /**
     * A type of the hierarchy with the type arguments it is inherited with.
     */
    public static final class InheritedType {
        private final TypeInfo info;
        private final Map<String, TypeDef> substitution;
        private final boolean raw;
        private final String scopePackage;

        private InheritedType(TypeInfo info, Map<String, TypeDef> substitution, boolean raw, String scopePackage) {
            this.info = info;
            this.substitution = substitution;
            this.raw = raw;
            this.scopePackage = scopePackage;
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
         * @return The package name. A type named without one - a member type named by its simple name, as the
         * definition the caller holds on to names it - is of the package of the definition whose hierarchy it is
         */
        public String getPackageName() {
            return info instanceof ModelInfo && isSimpleName(info.typeName()) ? scopePackage : info.packageName();
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
            return TypeOperations.substitute(type, substitution);
        }

        /**
         * Substitutes the type arguments this type is inherited with, except for variables a generic method declares
         * of its own, which shadow the type's variables of the same name.
         *
         * @param type     A type in the scope of a method of this type
         * @param shadowed The variables the method declares
         * @return The substituted type
         * @see #shadowedBy(List)
         */
        public TypeDef substitute(TypeDef type, List<TypeDef.TypeVariable> shadowed) {
            if (shadowed.isEmpty()) {
                return substitute(type);
            }
            return shadowedBy(shadowed).substitute(type);
        }

        /**
         * The scope of a generic method of this type, whose variables shadow the type's variables of the same name.
         *
         * @param shadowed The variables the method declares
         * @return The scope
         */
        public MethodVariables shadowedBy(List<TypeDef.TypeVariable> shadowed) {
            return new MethodVariables(shadowed, substitution);
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

    /**
     * The variables a generic method of an inherited type declares of its own, in the scope of the type: they shadow
     * the type's variables of the same name, and are bound by nothing the type is inherited with.
     *
     * <p>A substituted type names each by a name of its own, which no variable of a type has - so that it is never
     * taken for a variable of the same name a type argument names - and with its bounds, the type arguments
     * substituted, so that it erases as the override's declaration does. One declared without bounds is bounded by
     * {@code Object}.</p>
     *
     * @since 2.3
     */
    public static final class MethodVariables {

        private final Map<String, String> names = new HashMap<>();
        private final Set<String> renamed = new HashSet<>();
        private final Map<String, TypeDef> substitution;

        private MethodVariables(List<TypeDef.TypeVariable> variables, Map<String, TypeDef> typeArguments) {
            for (TypeDef.TypeVariable variable : variables) {
                // Not an identifier: no variable of a type can be named so
                String name = variable.name() + "'";
                names.put(variable.name(), name);
                renamed.add(name);
            }
            substitution = boundVariables(variables, typeArguments, names::get);
        }

        /**
         * Substitutes the type arguments the type is inherited with, and the variables of the method by their own
         * names.
         *
         * @param type A type in the scope of the method
         * @return The substituted type
         */
        public TypeDef substitute(TypeDef type) {
            return TypeOperations.substitute(type, substitution);
        }

        /**
         * @param declared The name a variable of the method is declared with
         * @return The name a substituted type names it by
         */
        public String nameOf(String declared) {
            String name = names.get(declared);
            if (name == null) {
                throw new IllegalArgumentException("The method declares no variable " + declared);
            }
            return name;
        }

        /**
         * @param name The name of a variable a substituted type names
         * @return Whether it is a variable of the method
         */
        public boolean isMethodVariable(String name) {
            return renamed.contains(name);
        }
    }

    /**
     * Looks up what is known of a type the hierarchy names only by its name: the compiler's element, as a
     * function of the binary name, and the definition of the model written with it - a member type naming its
     * enclosing one, or a sibling by its simple name.
     *
     * @since 2.3
     */
    @FunctionalInterface
    public interface Lookup extends Function<String, @Nullable ClassElement> {

        /**
         * Looks up nothing.
         */
        Lookup NONE = name -> null;

        /**
         * @param name The binary name of a definition, or the simple name of a member type
         * @return The definition, or {@code null}
         */
        @Nullable
        default ObjectDef definition(String name) {
            return null;
        }

        /**
         * @param classElements Looks up the element of a type by its binary name, or {@code null}
         * @return The lookup of elements alone
         */
        static Lookup of(@Nullable Function<String, @Nullable ClassElement> classElements) {
            if (classElements instanceof Lookup lookup) {
                return lookup;
            }
            return classElements == null ? NONE : classElements::apply;
        }

        /**
         * @param definitions   Looks up a definition by its binary name, or a member type by its simple name
         * @param classElements Looks up the element of a type by its binary name, or {@code null}
         * @return The lookup of both
         */
        static Lookup of(Function<String, @Nullable ObjectDef> definitions,
                         @Nullable Function<String, @Nullable ClassElement> classElements) {
            return new Lookup() {
                @Override
                public @Nullable ClassElement apply(String name) {
                    return classElements == null ? null : classElements.apply(name);
                }

                @Override
                public @Nullable ObjectDef definition(String name) {
                    return definitions.apply(name);
                }
            };
        }
    }

    interface TypeInfo {
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

    /**
     * A definition of the model.
     *
     * @param objectDef          The definition
     * @param enclosingVariables The variables of the types enclosing it in its scope, which its own shadow
     */
    private record ModelInfo(ObjectDef objectDef, List<TypeDef.TypeVariable> enclosingVariables) implements TypeInfo {

        ModelInfo(ObjectDef objectDef) {
            this(objectDef, List.of());
        }

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
            return erasureBound(variableBounds(name));
        }

        @Override
        public List<TypeDef> variableBounds(String name) {
            return Stream.concat(variables().stream(), enclosingVariables.stream())
                .filter(variable -> variable.name().equals(name))
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
                    Map<String, TypeDef> declared = methodScope(method.getTypeVariables());
                    return new InheritedMethod(method.getName(), parameters,
                        parameters.stream().map(parameter -> TypeOperations.substitute(parameter, declared)).toList(),
                        TypeOperations.substitute(method.getReturnType(), declared), method.getReturnType(),
                        method.getModifiers().contains(Modifier.FINAL), packagePrivate,
                        method.getTypeVariables());
                }).toList();
        }

        @Override
        public List<TypeDef> superTypes() {
            return superTypesOf(objectDef);
        }

        private List<TypeDef.TypeVariable> variables() {
            return TypeOperations.typeVariablesOf(objectDef);
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
                        Arrays.stream(method.getTypeParameters()).map(variable -> TypeDef.variable(variable.getName(),
                            Arrays.stream(variable.getBounds()).map(ReflectionInfo::convert).toList())).toList());
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
                ClassTypeDef rawType = ClassTypeDef.of((Class<?>) parameterized.getRawType());
                // A member of a parameterized enclosing type keeps the enclosing type's arguments:
                // `Outer<String>.Member<Integer>`, and `Outer<String>.Member` of a member declaring no variables
                if (parameterized.getOwnerType() != null
                    && convert(parameterized.getOwnerType()) instanceof ClassTypeDef enclosing
                    && (enclosing instanceof ClassTypeDef.Parameterized || enclosing instanceof EnclosedClassType)) {
                    rawType = memberType(enclosing, rawType);
                    if (parameterized.getActualTypeArguments().length == 0) {
                        return rawType;
                    }
                }
                return new ClassTypeDef.Parameterized(rawType,
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

    /**
     * A type the compiler describes, read from its declaration: the element of a parameterized type - the supertype
     * of another element - reports its members and supertypes with its type arguments already bound, variables of
     * the subtype among them, where the walk substitutes the arguments of each edge itself.
     *
     * @param classElement The element of the declaration
     */
    private record AstInfo(ClassElement classElement) implements TypeInfo {

        AstInfo {
            classElement = declarationOf(classElement);
        }

        private static ClassElement declarationOf(ClassElement classElement) {
            try {
                ClassElement declaration = classElement.getRawClassElement();
                return declaration != null && declaration.getName().equals(classElement.getName()) ? declaration : classElement;
            } catch (RuntimeException e) {
                // An element that cannot give its declaration is read as it is
                return classElement;
            }
        }

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
                method.getDeclaredTypeVariables().stream().map(variable -> TypeDef.variable(variable.getVariableName(),
                    variable.getBounds().stream().map(bound -> TypeDef.of(bound, ignore -> null, false)).toList())).toList());
        }
    }
}
