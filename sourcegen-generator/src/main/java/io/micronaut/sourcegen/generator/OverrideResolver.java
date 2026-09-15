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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the signature a declared method takes as source when it overrides a generic inherited method with that
 * method's erased signature: the inherited parameter and return types with the type arguments of the supertype
 * substituted.
 *
 * <p>A model written for bytecode overrides with the erased form, which the verifier accepts, such as
 * {@code Object get()} for {@code Supplier<String>}; source only overrides with the substituted one. How much of
 * it depends on the language: Java accepts erased parameters and a raw type for a parameterized one, and needs the
 * substitution where an erasure changes or a return type is a type variable; Kotlin has no raw types and matches
 * parameters exactly, so it needs the whole substituted signature.</p>
 *
 * @since 2.2
 */
@Internal
public final class OverrideResolver {

    private OverrideResolver() {
    }

    /**
     * Resolves the substituted signature of an erased override for Java source.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @param context   The context of the file being written, to look up the supertypes only known by name, or
     *                  {@code null}
     * @return The substituted signature, or {@code null} when Java source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           @Nullable VisitorContext context) {
        return resolve(objectDef, methodDef, context, false);
    }

    /**
     * Resolves the substituted signature of an erased override.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @param context   The context of the file being written, to look up the supertypes only known by name, or
     *                  {@code null}
     * @param exact     Whether the source language overrides with the substituted types only, as Kotlin does,
     *                  rather than accepting their erasures and raw types as Java does
     * @return The substituted signature, or {@code null} when the source can override with the declared one or a
     * type variable of the supertype cannot be resolved
     */
    @Nullable
    public static OverriddenMethod resolve(@Nullable ObjectDef objectDef,
                                           MethodDef methodDef,
                                           @Nullable VisitorContext context,
                                           boolean exact) {
        if (objectDef == null || !methodDef.isOverride() || methodDef.isConstructor()
            || !methodDef.getTypeVariables().isEmpty()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)
            || TypeHierarchy.superTypesOf(objectDef).isEmpty()) {
            return null;
        }
        TypeHierarchy.InheritedType declaringType = TypeHierarchy.declaring(objectDef);
        List<String> parameterErasures = methodDef.getParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(declaringType.erase(parameter.getType()), objectDef)).toList();
        String returnErasure = TypeHierarchy.erasedName(declaringType.erase(methodDef.getReturnType()), objectDef);
        Declared declared = new Declared(objectDef, methodDef, parameterErasures, returnErasure, declaringType,
            new HashSet<>(declaringType.getTypeParameters()), exact);
        OverriddenMethod[] found = new OverriddenMethod[1];
        TypeHierarchy.visitInheritedMethods(objectDef,
            context == null ? null : name -> context.getClassElement(name).orElse(null),
            (type, inherited) -> {
                found[0] = overriddenBy(declared, type, inherited);
                return found[0] == null;
            });
        return found[0];
    }

    @Nullable
    private static OverriddenMethod overriddenBy(Declared declared,
                                                 TypeHierarchy.InheritedType type,
                                                 TypeHierarchy.InheritedMethod inherited) {
        MethodDef methodDef = declared.methodDef();
        if (!inherited.name().equals(methodDef.getName())
            || inherited.overrideParameters().size() != declared.parameterErasures().size()
            || inherited.finalMethod()
            || (inherited.packagePrivate() && !type.getPackageName().equals(declared.objectDef().getPackageName()))) {
            return null;
        }
        // The declared method is the erasure of the inherited declaration. The erased parameters are compared: an
        // element of a parameterized supertype can report its parameters with the type arguments already bound
        List<String> declarationErasure = inherited.bridgeParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(type.erase(parameter))).toList();
        if (!declarationErasure.equals(declared.parameterErasures())) {
            return null;
        }
        // For Java, only a substitution that changes an erasure - where the bytecode writer adds a bridge - needs the
        // resolved parameters. A difference in type arguments alone (a raw `Set` for `Set<Class<?>>`) is a valid
        // override as is, and keeping it leaves the body assigning to the declared, raw types
        boolean changed = false;
        List<TypeDef> parameterTypes = new ArrayList<>(declarationErasure.size());
        for (int i = 0; i < declarationErasure.size(); i++) {
            TypeDef substituted = type.substitute(inherited.overrideParameters().get(i));
            if (TypeHierarchy.containsVariableOtherThan(substituted, declared.variables())) {
                return null;
            }
            TypeDef declaredType = methodDef.getParameters().get(i).getType();
            changed |= declared.exact()
                ? !sameType(substituted, declaredType)
                : !TypeHierarchy.erasedName(type.erase(substituted, declared.declaringType())).equals(declarationErasure.get(i));
            parameterTypes.add(substituted);
        }
        TypeDef returnType = methodDef.getReturnType();
        String declarationReturnErasure = TypeHierarchy.erasedName(type.erase(inherited.returnType()));
        if (declarationReturnErasure.equals(declared.returnErasure())
            && !(TypeHierarchy.unwrap(returnType) instanceof TypeDef.Primitive)) {
            TypeDef substituted = type.substitute(inherited.genericReturnType());
            if (TypeHierarchy.containsVariableOtherThan(substituted, declared.variables())) {
                return null;
            }
            // A return type has to be a subtype of the substituted one: the erasure of a type variable is not,
            // even where it is the same class
            boolean returnChanged = declared.exact()
                ? !sameType(substituted, returnType)
                : isVariableOrArrayOfVariable(substituted)
                || !TypeHierarchy.erasedName(type.erase(substituted, declared.declaringType())).equals(declarationReturnErasure);
            if (returnChanged) {
                changed = true;
                returnType = substituted;
            }
        }
        return changed ? new OverriddenMethod(parameterTypes, returnType) : null;
    }

    private static boolean sameType(TypeDef substituted, TypeDef declared) {
        return TypeHierarchy.unwrap(substituted).equals(TypeHierarchy.unwrap(declared));
    }

    private static boolean isVariableOrArrayOfVariable(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.Array array) {
            return isVariableOrArrayOfVariable(array.componentType());
        }
        return unwrapped instanceof TypeDef.TypeVariable;
    }

    private record Declared(ObjectDef objectDef,
                            MethodDef methodDef,
                            List<String> parameterErasures,
                            String returnErasure,
                            TypeHierarchy.InheritedType declaringType,
                            Set<String> variables,
                            boolean exact) {
    }

    /**
     * The signature of an overridden generic method with the type arguments of the supertype substituted.
     *
     * @param parameterTypes The parameter types
     * @param returnType     The return type
     */
    public record OverriddenMethod(List<TypeDef> parameterTypes, TypeDef returnType) {

        /**
         * The declared method with this signature, keeping everything else it declares, so that the body is rendered
         * against the resolved types.
         *
         * @param methodDef The declared method
         * @return The method
         */
        public MethodDef apply(MethodDef methodDef) {
            MethodDef.MethodDefBuilder builder = MethodDef.builder(methodDef.getName())
                .addModifiers(methodDef.getModifiers())
                .addAnnotations(methodDef.getAnnotations())
                .addJavadoc(methodDef.getJavadoc())
                .synthetic(methodDef.isSynthetic())
                .addThrows(methodDef.getThrowTypes())
                .returns(returnType)
                .addStatements(methodDef.getStatements())
                .overrides();
            for (int i = 0; i < parameterTypes.size(); i++) {
                ParameterDef parameter = methodDef.getParameters().get(i);
                builder.addParameter(ParameterDef.builder(parameter.getName(), parameterTypes.get(i))
                    .addModifiers(parameter.getModifiers())
                    .addAnnotations(parameter.getAnnotations())
                    .addJavadoc(parameter.getJavadoc())
                    .synthetic(parameter.isSynthetic())
                    .build());
            }
            return builder.build();
        }
    }
}
