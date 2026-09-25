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
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The operations on the types of the model that the hierarchy walk, override resolution and the generators share:
 * unwrapping annotations, raw types, erasure, substitution, walking the variables a type names, expanding chains of
 * bounds, and a type as one of its supertypes.
 *
 * <p>Each operation is defined once here, so that the places reading a type agree on what it erases to or which
 * variables it names.</p>
 *
 * @since 2.3
 */
@Internal
public final class TypeOperations {

    private TypeOperations() {
    }

    /**
     * Unwraps an annotated type, at any depth.
     *
     * @param type The type
     * @return The type without its annotations
     */
    public static TypeDef unwrap(TypeDef type) {
        TypeDef unwrapped = type;
        while (unwrapped instanceof TypeDef.Annotated) {
            unwrapped = unwrapped instanceof TypeDef.AnnotatedTypeDef annotated ? annotated.typeDef()
                : ((ClassTypeDef.AnnotatedClassTypeDef) unwrapped).typeDef();
        }
        return unwrapped;
    }

    /**
     * The class of a parameterized type: {@code List} of {@code List<String>}. A member of a parameterized type keeps
     * the enclosing type it carries.
     *
     * @param type The type
     * @return The type without its type arguments
     */
    public static ClassTypeDef rawClass(ClassTypeDef type) {
        ClassTypeDef raw = type;
        while (raw instanceof ClassTypeDef.Parameterized parameterized) {
            raw = parameterized.rawType();
        }
        return raw;
    }

    /**
     * A type without its annotations, and a class type without its type arguments, as {@link #rawClass(ClassTypeDef)}
     * takes them: {@code List} of {@code @A List<String>}. Any other type is only unwrapped.
     *
     * @param type The type
     * @return The unwrapped type, raw where it is a class type
     */
    public static TypeDef rawClassOf(TypeDef type) {
        TypeDef unwrapped = unwrap(type);
        return unwrapped instanceof ClassTypeDef classTypeDef ? rawClass(classTypeDef) : unwrapped;
    }

    /**
     * The class a type is of, as it is declared: without its type arguments, and without the enclosing type a
     * member of a parameterized type carries - {@code Member} of {@code Outer<String>.Member<Integer>}.
     *
     * @param type The type
     * @return The declared class
     */
    public static ClassTypeDef declaredClass(ClassTypeDef type) {
        ClassTypeDef raw = type;
        while (raw instanceof ClassTypeDef.Parameterized || raw instanceof EnclosedClassType) {
            raw = raw instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : ((EnclosedClassType) raw).member();
        }
        return raw;
    }

    /**
     * The raw type of a parameterized type, or of the component of an array of one: {@code List[]} of
     * {@code List<String>[]}.
     *
     * @param type The type
     * @return The raw type, or the type itself where it has no type arguments
     */
    public static TypeDef raw(TypeDef type) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.rawType();
        }
        if (unwrapped instanceof TypeDef.Array array && unwrap(array.componentType()) instanceof ClassTypeDef.Parameterized component) {
            return TypeDef.array(component.rawType(), array.dimensions());
        }
        return type;
    }

    /**
     * Erases a type whose variables carry their bounds: a variable is the erasure of its leftmost bound (JLS 4.6), and
     * {@code Object} without one.
     *
     * @param type The type
     * @return The erased type
     * @see #erase(TypeDef, Function, ClassTypeDef)
     */
    public static TypeDef erase(TypeDef type) {
        return erase(type, name -> null, null);
    }

    /**
     * Erases a type in the scope of the variables a method and its type declare: a variable named without its bounds
     * erases to the bound its nearest declaration gives it - the method's before the type's.
     *
     * @param type            The type
     * @param methodVariables The variables the method declares
     * @param typeVariables   The variables the type declares
     * @return The erased type
     */
    public static TypeDef erase(TypeDef type, List<TypeDef.TypeVariable> methodVariables, List<TypeDef.TypeVariable> typeVariables) {
        return erase(type, name -> {
            List<TypeDef> bounds = declaredBounds(name, methodVariables, typeVariables);
            return bounds.isEmpty() ? null : bounds.get(0);
        }, null);
    }

    /**
     * Erases a type (JLS 4.6). A variable is the erasure of its leftmost bound - {@code Object} for
     * {@code T extends Object & Comparable<T>}, as the bytecode writers describe it - taken from the reference where it
     * carries its bounds, else from its declaration in scope. A variable bounded by itself through others -
     * {@code A extends B} and {@code B extends A} - erases to {@code Object}. A parameterized type is its class, without the
     * enclosing type a member carries; a wildcard is the erasure of its upper bound; and an array keeps its rank with
     * its component erased.
     *
     * @param type     The type
     * @param declared The leftmost bound of a variable in scope by its name, or {@code null} where it declares none
     * @param thisType The type {@link TypeDef#THIS} stands for, or {@code null} to keep it
     * @return The erased type
     */
    public static TypeDef erase(TypeDef type,
                                Function<String, @Nullable TypeDef> declared,
                                @Nullable ClassTypeDef thisType) {
        return erase(type, declared, thisType, new HashSet<>());
    }

    private static TypeDef erase(TypeDef type,
                                 Function<String, @Nullable TypeDef> declared,
                                 @Nullable ClassTypeDef thisType,
                                 Set<String> visiting) {
        TypeDef unwrapped = unwrap(type);
        if (thisType != null && TypeDef.THIS.equals(unwrapped)) {
            return thisType;
        }
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            if (!visiting.add(variable.name())) {
                return TypeDef.OBJECT;
            }
            TypeDef bound = variable.bounds().isEmpty() ? declared.apply(variable.name()) : variable.bounds().get(0);
            TypeDef erased = bound == null ? TypeDef.OBJECT : erase(bound, declared, thisType, visiting);
            visiting.remove(variable.name());
            return erased;
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return erase(parameterized.rawType(), declared, thisType, visiting);
        }
        if (unwrapped instanceof EnclosedClassType enclosed) {
            return enclosed.member();
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : erase(wildcard.upperBounds().get(0), declared, thisType, visiting);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            TypeDef erased = TypeDef.array(erase(array.componentType(), declared, thisType, visiting), array.dimensions());
            return array.isNullable() ? erased.makeNullable() : erased;
        }
        return unwrapped;
    }

    /**
     * Erases a reflective type: a variable is the erasure of its leftmost bound, a parameterized type its raw class.
     *
     * @param type The type
     * @return The erased class, {@code Object} for a type that has no class of its own
     */
    public static Class<?> erase(Type type) {
        if (type instanceof Class<?> aClass) {
            return aClass;
        }
        if (type instanceof ParameterizedType parameterized) {
            return erase(parameterized.getRawType());
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            return variable.getBounds().length == 0 ? Object.class : erase(variable.getBounds()[0]);
        }
        return Object.class;
    }

    /**
     * The bounds the nearest declaration of a variable in scope gives it: the method's variable of the name shadows
     * the type's.
     *
     * @param name            The name of the variable
     * @param methodVariables The variables the method declares
     * @param typeVariables   The variables the type declares
     * @return The bounds, empty where no variable of the name is declared or it has none
     */
    public static List<TypeDef> declaredBounds(String name,
                                               List<TypeDef.TypeVariable> methodVariables,
                                               List<TypeDef.TypeVariable> typeVariables) {
        for (TypeDef.TypeVariable variable : methodVariables) {
            if (variable.name().equals(name)) {
                return variable.bounds();
            }
        }
        for (TypeDef.TypeVariable variable : typeVariables) {
            if (variable.name().equals(name)) {
                return variable.bounds();
            }
        }
        return List.of();
    }

    /**
     * The type variables a definition declares: those of a class, an interface or a record.
     *
     * @param objectDef The definition, or {@code null}
     * @return The variables, empty for any other definition
     */
    public static List<TypeDef.TypeVariable> typeVariablesOf(@Nullable ObjectDef objectDef) {
        return switch (objectDef) {
            case ClassDef classDef -> classDef.getTypeVariables();
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables();
            case RecordDef recordDef -> recordDef.getTypeVariables();
            case null, default -> List.of();
        };
    }

    /**
     * Substitutes type variables by name, at any depth of the type: in the type arguments, the component of an
     * array, the bounds of a wildcard, and the enclosing type of a member. A nullable variable is substituted by the
     * nullable replacement.
     *
     * @param type         The type
     * @param substitution The types to substitute for the variables, by their names
     * @return The substituted type, without its annotations
     */
    public static TypeDef substitute(TypeDef type, Map<String, TypeDef> substitution) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            TypeDef replacement = substitution.get(variable.name());
            if (replacement == null) {
                return variable;
            }
            return variable.isNullable() ? replacement.makeNullable() : replacement;
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return new ClassTypeDef.Parameterized((ClassTypeDef) substitute(parameterized.rawType(), substitution),
                parameterized.typeArguments().stream().map(argument -> substitute(argument, substitution)).toList());
        }
        if (unwrapped instanceof EnclosedClassType enclosed) {
            // The enclosing type's arguments are substituted as well: `Outer<T>.Member`
            return new EnclosedClassType((ClassTypeDef) substitute(enclosed.enclosing(), substitution), enclosed.member());
        }
        if (unwrapped instanceof ClassTypeDef.ClassElementType elementType && !substitution.isEmpty()) {
            // The element names its enclosing type: the member carries it where the substitution changes it
            ClassTypeDef enclosing = MemberTypes.enclosingOf(elementType.classElement());
            if (enclosing != null) {
                TypeDef substituted = substitute(enclosing, substitution);
                if (!substituted.equals(enclosing)) {
                    return new EnclosedClassType((ClassTypeDef) substituted, elementType);
                }
            }
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

    /**
     * Whether a type names a type variable: itself, or in its type arguments, the component of an array, the bounds
     * of a wildcard, or the enclosing type of a member - {@code Outer<X>.Member} names {@code X}.
     *
     * @param type    The type
     * @param matches Tells the names of the variables looked for
     * @return true where a variable of a matching name is named
     */
    public static boolean mentionsVariable(TypeDef type, Predicate<String> matches) {
        TypeDef unwrapped = unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            return matches.test(variable.name());
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return mentionsVariable(parameterized.rawType(), matches)
                || parameterized.typeArguments().stream().anyMatch(argument -> mentionsVariable(argument, matches));
        }
        if (unwrapped instanceof EnclosedClassType enclosed) {
            return mentionsVariable(enclosed.enclosing(), matches);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            return mentionsVariable(array.componentType(), matches);
        }
        if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().stream().anyMatch(bound -> mentionsVariable(bound, matches))
                || wildcard.lowerBounds().stream().anyMatch(bound -> mentionsVariable(bound, matches));
        }
        return false;
    }

    /**
     * Whether a type names a type variable that is not one of the given ones.
     *
     * @param type      The type
     * @param variables The allowed variable names
     * @return true if another variable is named
     */
    public static boolean containsVariableOtherThan(TypeDef type, Set<String> variables) {
        return mentionsVariable(type, name -> !variables.contains(name));
    }

    /**
     * The names of the variables a type names, in the order they are named.
     *
     * @param type The type
     * @return The names, each once
     */
    public static Set<String> variablesOf(TypeDef type) {
        Set<String> names = new java.util.LinkedHashSet<>();
        mentionsVariable(type, name -> {
            names.add(name);
            return false;
        });
        return names;
    }

    /**
     * Expands the bounds of a variable: each bound that is itself a variable in scope is replaced by that variable's
     * bounds, along a chain of any length - {@code U extends V} with {@code V extends Number} is bounded by
     * {@code Number}. A variable reached again along the chain ends it.
     *
     * @param variable    The variable
     * @param declaration The variable in scope a reference stands for, with the bounds it is declared with, or
     *                    {@code null} for one that is not expanded, and is a bound itself
     * @return The bounds that are not expanded, as they are declared, in declaration order
     */
    public static List<TypeDef> expandBounds(TypeDef.TypeVariable variable,
                                             Function<TypeDef.TypeVariable, TypeDef.@Nullable TypeVariable> declaration) {
        List<TypeDef> result = new ArrayList<>();
        TypeDef.TypeVariable declared = declaration.apply(variable);
        if (declared != null) {
            expandBounds(declared, declaration, result, new HashSet<>());
        }
        return result;
    }

    private static void expandBounds(TypeDef.TypeVariable declared,
                                     Function<TypeDef.TypeVariable, TypeDef.@Nullable TypeVariable> declaration,
                                     List<TypeDef> result,
                                     Set<String> visited) {
        if (!visited.add(declared.name())) {
            return;
        }
        for (TypeDef bound : declared.bounds()) {
            TypeDef.TypeVariable boundDeclaration = unwrap(bound) instanceof TypeDef.TypeVariable boundVariable
                ? declaration.apply(boundVariable) : null;
            if (boundDeclaration != null) {
                expandBounds(boundDeclaration, declaration, result, visited);
            } else {
                result.add(bound);
            }
        }
    }

    /**
     * A type as one of its supertypes, with the type arguments it inherits that supertype with - from the model,
     * reflection or annotation-processing type of each on the way. A raw type inherits its supertypes raw (JLS 4.8).
     *
     * @param type          The type
     * @param supertypeName The binary name of the supertype
     * @param lookup        Looks up the definition or the element of a type only known by name, or {@code null}
     * @return The supertype, parameterized where it is inherited so, or {@code null} where it is not inherited
     */
    public static @Nullable ClassTypeDef asSupertype(ClassTypeDef type, String supertypeName, TypeHierarchy.@Nullable Lookup lookup) {
        Deque<ClassTypeDef> queue = new ArrayDeque<>();
        Set<String> rawNames = new HashSet<>();
        Set<String> visited = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            ClassTypeDef current = queue.removeFirst();
            ClassTypeDef raw = declaredClass(current);
            TypeHierarchy.TypeInfo info = TypeHierarchy.typeInfoOf(raw, lookup);
            // A member type named by its simple name is known by the name the lookup qualifies it with
            String name = info == null ? raw.getName() : info.typeName();
            if (raw.getName().equals(supertypeName) || name.equals(supertypeName)) {
                return rawNames.contains(raw.getName()) ? raw : current;
            }
            if (!visited.add(name) || info == null) {
                continue;
            }
            Map<String, TypeDef> substitution = new HashMap<>(TypeHierarchy.enclosingArguments(current, lookup));
            List<String> variables = info.typeParameters();
            if (current instanceof ClassTypeDef.Parameterized parameterized) {
                for (int i = 0; i < variables.size() && i < parameterized.typeArguments().size(); i++) {
                    substitution.put(variables.get(i), parameterized.typeArguments().get(i));
                }
            }
            // The supertypes of a raw type are raw (JLS 4.8): `RawChild` extends a raw `ListParent`, not a `ListParent<String>`
            boolean currentRaw = rawNames.contains(raw.getName())
                || !variables.isEmpty() && !(current instanceof ClassTypeDef.Parameterized);
            for (TypeDef superType : info.superTypes()) {
                if (substitute(superType, substitution) instanceof ClassTypeDef superClassType) {
                    if (currentRaw) {
                        rawNames.add(declaredClass(superClassType).getName());
                    }
                    queue.addLast(superClassType);
                }
            }
        }
        return TypeDef.OBJECT.getName().equals(supertypeName) ? TypeDef.OBJECT : null;
    }
}
