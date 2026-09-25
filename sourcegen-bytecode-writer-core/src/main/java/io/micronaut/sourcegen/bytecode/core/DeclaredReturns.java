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
import io.micronaut.core.naming.NameUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The return type a class file declares for an invoked method, where the call names another: a
 * {@code T get()} of a {@code Getter<String>} invoked for a {@code String} is written, as javac writes it, with the
 * descriptor of its declaration, {@code ()Ljava/lang/Object;}, and a checkcast of the result.
 *
 * <p>The owner's methods are looked up with {@link ClassTypeDef#findDeclaredMethods(String, int)}, which covers
 * reflection (bridges included), compiler elements and generated definitions (their inherited methods included),
 * and cached per definition being written.</p>
 *
 * @since 2.3
 */
@Internal
public final class DeclaredReturns {

    private static final Map<ObjectDef, Map<String, List<Declared>>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private DeclaredReturns() {
    }

    /**
     * The erased return type to invoke a method with, where the owner declares no method of the call's descriptor
     * but exactly one of its name and erased parameter types, returning a type related to the requested one.
     *
     * @param owner      The type the method is invoked on: the receiver, the supertype of a super call or the owner of a
     *                   static method
     * @param method     The invoked method
     * @param descriptor The descriptor the call is written with
     * @param requested  The type the call requests, in the scope it is written in
     * @param current    The definition being written, if any
     * @return The erased return type the owner declares, or {@code null} to invoke the descriptor as it is
     */
    @Nullable
    public static TypeDef of(@Nullable TypeDef owner, MethodDef method, String descriptor, TypeDef requested, @Nullable ObjectDef current) {
        return of(owner, method, descriptor, requested, current, EnclosingScope.NONE);
    }

    /**
     * The erased return type to invoke a method with, as {@link #of(TypeDef, MethodDef, String, TypeDef, ObjectDef)}
     * finds it, in the enclosing scope of the class being written.
     *
     * @param owner          The type the method is invoked on
     * @param method         The invoked method
     * @param descriptor     The descriptor the call is written with
     * @param requested      The type the call requests, in the scope it is written in
     * @param current        The definition being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The erased return type the owner declares, or {@code null} to invoke the descriptor as it is
     * @since 2.3
     */
    @Nullable
    public static TypeDef of(@Nullable TypeDef owner,
                             MethodDef method,
                             String descriptor,
                             TypeDef requested,
                             @Nullable ObjectDef current,
                             EnclosingScope enclosingScope) {
        if (owner == null || method.isConstructor()) {
            return null;
        }
        TypeDef raw = TypeOperations.rawClassOf(owner);
        if (raw instanceof TypeDef.Array) {
            // An array has the members of Object: its `clone()` is typed as the array, and declared as an Object
            raw = TypeDef.OBJECT;
        }
        if (!(raw instanceof ClassTypeDef ownerClass)) {
            return null;
        }
        int split = descriptor.indexOf(')') + 1;
        String parameters = descriptor.substring(0, split);
        String returns = descriptor.substring(split);
        String declared = null;
        for (Declared candidate : declared(ownerClass, method.getName(), method.getParameters().size(), current, enclosingScope)) {
            if (candidate.parameters().equals(parameters)) {
                if (candidate.returns().equals(returns)) {
                    // The call names a declared method: a bridge of a covariant override is one
                    return null;
                }
                if (declared != null) {
                    // Several declare other return types: which of them javac invokes is not known here
                    return null;
                }
                declared = candidate.returns();
            }
        }
        if (declared == null) {
            return null;
        }
        TypeDef declaredType = typeOf(declared);
        // Only the erasure of a generic return, which the requested type specializes, or a covariant override the
        // compiler knows no bridge of: a declaration returning an unrelated type is not the one the call means
        return related(requested, declaredType, current, enclosingScope) ? declaredType : null;
    }

    private static boolean related(TypeDef requested, TypeDef declared, @Nullable ObjectDef current, EnclosingScope enclosingScope) {
        TypeDef type = TypeHierarchy.unwrap(ObjectDef.getContextualType(current, requested));
        if (type instanceof TypeDef.Primitive primitive) {
            if (primitive.equals(TypeDef.VOID)) {
                return false;
            }
            if (declared instanceof TypeDef.Primitive declaredPrimitive) {
                // A primitive return widens to the primitive requested, as javac widens Math.max(int, int) to a long
                return widens(declaredPrimitive, primitive);
            }
            // A primitive requested of a generic return unboxes its wrapper
            type = primitive.wrapperType();
        }
        if (declared instanceof TypeDef.Primitive declaredPrimitive) {
            // A primitive return boxes to a reference its wrapper is
            return !declaredPrimitive.equals(TypeDef.VOID) && type instanceof ClassTypeDef requestedClass
                && TypeHierarchy.inherits(declaredPrimitive.wrapperType(), requestedClass.getName(), null);
        }
        if (declared instanceof ClassTypeDef declaredClass && declaredClass.getName().equals(TypeDef.OBJECT.getName())) {
            return true;
        }
        if (type instanceof TypeDef.Array requestedArray) {
            return declared instanceof TypeDef.Array declaredArray && requestedArray.dimensions() == declaredArray.dimensions()
                && related(requestedArray.componentType(), declaredArray.componentType(), current, enclosingScope);
        }
        if (type instanceof ClassTypeDef requestedType) {
            type = TypeOperations.rawClass(requestedType);
        } else {
            // A variable requested erases to its bound
            type = TypeUtils.erased(type, current, null, enclosingScope);
        }
        return type instanceof ClassTypeDef requestedClass && declared instanceof ClassTypeDef declaredClass
            && (TypeHierarchy.inherits(requestedClass, declaredClass.getName(), null)
            || TypeHierarchy.inherits(loaded(declaredClass), requestedClass.getName(), null));
    }

    /**
     * Whether a primitive widens to another, a widening primitive conversion (JLS 5.1.2).
     */
    private static boolean widens(TypeDef.Primitive from, TypeDef.Primitive to) {
        List<String> order = List.of("byte", "short", "int", "long", "float", "double");
        if (from.name().equals("char")) {
            return order.indexOf(to.name()) >= order.indexOf("int");
        }
        int fromIndex = order.indexOf(from.name());
        int toIndex = order.indexOf(to.name());
        return fromIndex >= 0 && toIndex > fromIndex;
    }

    /**
     * A type a descriptor names, with the supertypes a class loader gives it where one has it: a supertype of the
     * declared return - the `CharSequence` of a `String` - is requested too.
     */
    private static ClassTypeDef loaded(ClassTypeDef named) {
        Class<?> type = TypeLookup.reflective().loadClass(named.getName());
        return type == null ? named : ClassTypeDef.of(type);
    }

    private static List<Declared> declared(ClassTypeDef owner,
                                           String name,
                                           int arity,
                                           @Nullable ObjectDef current,
                                           EnclosingScope enclosingScope) {
        if (current == null) {
            return lookup(owner, name, arity, null, enclosingScope);
        }
        Map<String, List<Declared>> cache = CACHE.computeIfAbsent(current, ignore -> new ConcurrentHashMap<>());
        return cache.computeIfAbsent(owner.getName() + '#' + name + '/' + arity, ignore -> lookup(owner, name, arity, current, enclosingScope));
    }

    private static List<Declared> lookup(ClassTypeDef owner,
                                         String name,
                                         int arity,
                                         @Nullable ObjectDef current,
                                         EnclosingScope enclosingScope) {
        List<MethodDef> methods = owner.findDeclaredMethods(name, arity);
        List<Declared> result = new ArrayList<>(methods.size());
        for (MethodDef method : methods) {
            if (method.getParameters().size() != arity) {
                // A variable arity method matched for fewer or more arguments
                continue;
            }
            // A generated method is erased in the scope of the definition declaring it, reflection and compiler
            // elements are erased already
            ObjectDef scope = owner instanceof ClassTypeDef.ClassDefType ? TypeUtils.declaringScope(owner, current, method, enclosingScope) : null;
            String descriptor = TypeUtils.getMethodDescriptor(scope, method, enclosingScope);
            int split = descriptor.indexOf(')') + 1;
            result.add(new Declared(descriptor.substring(0, split), descriptor.substring(split)));
        }
        if (owner instanceof ClassTypeDef.ClassDefType generated) {
            // The accessors a writer adds for the properties of a generated definition are no methods of it
            synthesized(generated.objectDef(), name, arity, result, enclosingScope, new HashSet<>());
        }
        return List.copyOf(result);
    }

    private static void synthesized(ObjectDef definition,
                                    String name,
                                    int arity,
                                    List<Declared> result,
                                    EnclosingScope enclosingScope,
                                    Set<String> visited) {
        if (!visited.add(definition.getName())) {
            return;
        }
        for (PropertyDef property : definition.getProperties()) {
            MethodDef accessor = null;
            if (definition instanceof RecordDef) {
                if (arity == 0 && property.getName().equals(name)) {
                    accessor = MethodDef.builder(name).returns(property.getType()).build();
                }
            } else {
                String capitalized = NameUtils.capitalize(property.getName());
                if (arity == 0 && name.equals("get" + capitalized)) {
                    accessor = MethodDef.builder(name).returns(property.getType()).build();
                } else if (arity == 1 && name.equals("set" + capitalized)) {
                    accessor = MethodDef.builder(name).addParameter(property.getName(), property.getType()).returns(TypeDef.VOID).build();
                }
            }
            if (accessor != null) {
                String descriptor = TypeUtils.getMethodDescriptor(definition, accessor, enclosingScope);
                int split = descriptor.indexOf(')') + 1;
                result.add(new Declared(descriptor.substring(0, split), descriptor.substring(split)));
            }
        }
        for (TypeDef superType : TypeHierarchy.superTypesOf(definition)) {
            TypeDef raw = TypeOperations.rawClassOf(superType);
            if (raw instanceof ClassTypeDef.ClassDefType superDefinition) {
                synthesized(superDefinition.objectDef(), name, arity, result, enclosingScope, visited);
            }
        }
    }

    private static TypeDef typeOf(String descriptor) {
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        TypeDef component = switch (descriptor.charAt(dimensions)) {
            case 'V' -> TypeDef.VOID;
            case 'Z' -> TypeDef.primitive(boolean.class);
            case 'B' -> TypeDef.primitive(byte.class);
            case 'C' -> TypeDef.primitive(char.class);
            case 'S' -> TypeDef.primitive(short.class);
            case 'I' -> TypeDef.primitive(int.class);
            case 'J' -> TypeDef.primitive(long.class);
            case 'F' -> TypeDef.primitive(float.class);
            case 'D' -> TypeDef.primitive(double.class);
            default -> ClassTypeDef.of(descriptor.substring(dimensions + 1, descriptor.length() - 1).replace('/', '.'));
        };
        return dimensions == 0 ? component : TypeDef.array(component, dimensions);
    }

    /**
     * A declared method of the owner, erased.
     *
     * @param parameters The descriptor of its parameters, parenthesized
     * @param returns    The descriptor of its return type
     */
    private record Declared(String parameters, String returns) {
    }
}
