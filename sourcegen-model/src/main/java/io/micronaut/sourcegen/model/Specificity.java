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

import io.micronaut.sourcegen.model.OverloadCandidates.Candidate;
import io.micronaut.sourcegen.model.OverloadResolution.Tri;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.model.Applicability.mentionsOpen;
import static io.micronaut.sourcegen.model.Applicability.referenceConvertible;
import static io.micronaut.sourcegen.model.OverloadCandidates.receiverArguments;
import static io.micronaut.sourcegen.model.ResolutionContext.isCapture;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.ResolutionTypes.erasedDeep;
import static io.micronaut.sourcegen.model.ResolutionTypes.expanded;
import static io.micronaut.sourcegen.model.ResolutionTypes.mentions;
import static io.micronaut.sourcegen.model.ResolutionTypes.widens;

/**
 * Whether one applicable method is more specific than another for a call (JLS 15.12.2.5).
 *
 * @since 2.3
 */
final class Specificity {

    private Specificity() {
    }

    /**
     * Whether one method is more specific than another for a call (JLS 15.12.2.5): each parameter it takes the
     * arguments with is a subtype of the other's, a variable of a method being its bound. Against a method that
     * declares no variables the type arguments count too: a variable of the method is no type the other names.
     */
    static Tri moreSpecific(ResolutionContext context, Candidate method, Candidate other, int argumentCount, boolean variableArity) {
        int count = variableArity ? Math.max(argumentCount, Math.max(method.parameters().size(), other.parameters().size()))
            : method.parameters().size();
        Map<String, TypeDef> receiver = receiverArguments(context, method);
        Map<String, TypeDef> otherReceiver = receiverArguments(context, other);
        Tri result = Tri.YES;
        for (int i = 0; i < count; i++) {
            TypeDef declared = expanded(method.parameters(), i, variableArity);
            TypeDef otherDeclared = expanded(other.parameters(), i, variableArity);
            TypeDef parameter = specificType(declared, method, receiver);
            TypeDef otherParameter = specificType(otherDeclared, other, otherReceiver);
            if (parameter instanceof TypeDef.Primitive primitive && otherParameter instanceof TypeDef.Primitive otherPrimitive) {
                result = result.and(Tri.of(widens(primitive.clazz(), otherPrimitive.clazz())));
            } else if (parameter.isPrimitive() || otherParameter.isPrimitive()) {
                result = Tri.NO;
            } else if (otherParameter instanceof TypeDef.TypeVariable variable && !isCapture(variable)) {
                // A variable of several bounds, `T extends First & Second`, takes a type that is each of them
                for (TypeDef bound : variable.bounds()) {
                    result = result.and(referenceConvertible(context, specificForm(parameter), erasedDeep(bound)));
                }
            } else {
                result = result.and(referenceConvertible(context, specificForm(parameter), specificForm(otherParameter)));
                if (result != Tri.NO && other.variables().isEmpty() && !method.variables().isEmpty()) {
                    result = result.and(argumentsFit(TypeHierarchy.substituted(declared, receiver),
                        TypeHierarchy.substituted(otherDeclared, otherReceiver), method.variables()));
                }
            }
            if (result == Tri.NO) {
                return Tri.NO;
            }
        }
        return result;
    }

    /**
     * Whether the type arguments of a parameter fit those of the other method's, which declares no variables: where
     * the other's is a type, a variable of the method is not that type - `<T> m(List<T>)` is not more specific than
     * `m(List<String>)`.
     */
    private static Tri argumentsFit(TypeDef parameter, TypeDef otherParameter, List<TypeDef.TypeVariable> variables) {
        TypeDef param = TypeHierarchy.unwrap(parameter);
        TypeDef other = TypeHierarchy.unwrap(otherParameter);
        if (param instanceof TypeDef.Array array && other instanceof TypeDef.Array otherArray) {
            return array.dimensions() == otherArray.dimensions() ? argumentsFit(array.componentType(), otherArray.componentType(), variables)
                : Tri.YES;
        }
        if (!(other instanceof ClassTypeDef.Parameterized otherParameterized) || !(param instanceof ClassTypeDef paramClass)) {
            return Tri.YES;
        }
        ClassTypeDef inherited = TypeHierarchy.asSupertype(paramClass, declaredClass(otherParameterized).getName(), null);
        if (!(inherited instanceof ClassTypeDef.Parameterized parameterized)
            || parameterized.typeArguments().size() != otherParameterized.typeArguments().size()) {
            return Tri.YES;
        }
        for (int i = 0; i < parameterized.typeArguments().size(); i++) {
            if (!mentionsOpen(otherParameterized.typeArguments().get(i)) && mentions(parameterized.typeArguments().get(i), variables)) {
                return Tri.NO;
            }
        }
        return Tri.YES;
    }

    /**
     * A type compared for specificity: erased, but for a variable - a capture, or one of several bounds - which is
     * more specific than each of its bounds.
     */
    private static TypeDef specificForm(TypeDef type) {
        return type instanceof TypeDef.TypeVariable ? type : erasedDeep(type);
    }

    private static TypeDef specificType(TypeDef parameter, Candidate candidate, Map<String, TypeDef> receiver) {
        TypeDef substituted = TypeHierarchy.unwrap(TypeHierarchy.substituted(parameter, receiver));
        Set<String> visited = new HashSet<>();
        // A variable is its bound, following a bound that is another variable of the method: `A extends B, B extends Number`
        // A captured wildcard is a type of its own, below its bound: `CAP of ? super Integer` is more specific than Object
        while (substituted instanceof TypeDef.TypeVariable variable && !isCapture(variable) && visited.add(variable.name())) {
            List<TypeDef> bounds = candidate.variables().stream().filter(declared -> declared.name().equals(variable.name()))
                .findFirst().map(TypeDef.TypeVariable::bounds).orElse(variable.bounds());
            if (bounds.size() > 1) {
                // Each of several bounds counts: `T extends First & Second` is a Second as well
                return TypeDef.variable(variable.name(), bounds.stream().map(bound -> TypeHierarchy.substituted(bound, receiver)).toList());
            }
            substituted = bounds.isEmpty() ? TypeDef.OBJECT : TypeHierarchy.unwrap(TypeHierarchy.substituted(bounds.getFirst(), receiver));
        }
        return substituted instanceof TypeDef.TypeVariable && !isCapture(substituted) ? TypeDef.OBJECT : substituted;
    }
}
