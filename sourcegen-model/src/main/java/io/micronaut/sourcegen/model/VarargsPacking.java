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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.micronaut.sourcegen.model.Applicability.assignable;
import static io.micronaut.sourcegen.model.Applicability.referenceConvertible;
import static io.micronaut.sourcegen.model.Inference.expandChains;
import static io.micronaut.sourcegen.model.Inference.infer;
import static io.micronaut.sourcegen.model.OverloadCandidates.receiverArguments;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.ResolutionTypes.erasedDeep;
import static io.micronaut.sourcegen.model.ResolutionTypes.expanded;
import static io.micronaut.sourcegen.model.ResolutionTypes.mentions;

/**
 * The call to the method chosen: its parameters keep the bounds of the method's variables, and a variable arity tail
 * is packed into an array, typed as javac infers it or as the requested return type pins it.
 *
 * @since 2.3
 */
final class VarargsPacking {

    private VarargsPacking() {
    }

    /**
     * The call resolved to a method: its parameters keep the bounds of the method's variables, which the erasure
     * of a variable named alone needs, and a variable arity tail is packed into an array of the inferred component.
     */
    static Invocations.Resolved resolved(ResolutionContext context, Candidate chosen,
                                                 boolean variableArity,
                                                 List<@Nullable TypeDef> arguments,
                                                 List<? extends ExpressionDef> values,
                                                 @Nullable TypeDef returningType) {
        Map<String, TypeDef> bounded = new HashMap<>();
        for (TypeDef.TypeVariable variable : chosen.method().getTypeVariables()) {
            if (!variable.bounds().isEmpty()) {
                bounded.put(variable.name(), variable);
            }
        }
        for (TypeDef.TypeVariable variable : chosen.variables()) {
            bounded.putIfAbsent(variable.name(), variable.bounds().isEmpty()
                ? TypeDef.variable(variable.name(), List.of(TypeDef.OBJECT)) : variable);
        }
        // `A extends B, B extends Number`: the chain is carried to where A erases to Number
        expandChains(bounded);
        List<TypeDef> parameterTypes = chosen.method().getParameters().stream()
            .map(parameter -> TypeHierarchy.substituted(parameter.getType(), bounded)).toList();
        if (!variableArity) {
            // By strict or loose invocation the last argument is the array itself, `null` included
            return new Invocations.Resolved(parameterTypes, values, chosen.isStatic());
        }
        int fixed = parameterTypes.size() - 1;
        TypeDef.Array declared = (TypeDef.Array) TypeHierarchy.unwrap(parameterTypes.getLast());
        TypeDef.Array array = pinnedArray(context, chosen, arguments, returningType);
        if (array == null) {
            array = inferredArray(context, chosen, arguments);
        }
        List<ExpressionDef> adapted = new ArrayList<>(values.subList(0, fixed));
        adapted.add(new ExpressionDef.NewArrayInitialized(array != null ? array : declared,
            List.copyOf(values.subList(fixed, values.size()))));
        return new Invocations.Resolved(parameterTypes, adapted, chosen.isStatic());
    }

    /**
     * The array a variable arity tail of the method's variable is packed into where the requested return type pins
     * the variable, as the target of the call does for javac: {@code List<Object> values = List.of(..)} packs an
     * {@code Object[]} whatever the elements are. A pinned type no array can be created of - a parameterized one -
     * or one an element does not convert to keeps the array the descriptor takes.
     *
     * @return The array, or {@code null} where the requested type pins nothing and the arguments infer it
     */
    private static TypeDef.@Nullable Array pinnedArray(ResolutionContext context, Candidate chosen, List<@Nullable TypeDef> arguments, @Nullable TypeDef requested) {
        List<TypeDef> generic = chosen.parameters();
        if (requested == null || generic.isEmpty()
            || !(TypeHierarchy.unwrap(generic.getLast()) instanceof TypeDef.Array last) || last.dimensions() != 1
            || !(TypeHierarchy.unwrap(last.componentType()) instanceof TypeDef.TypeVariable variable)) {
            return null;
        }
        TypeDef.TypeVariable declared = chosen.variables().stream().filter(candidate -> candidate.name().equals(variable.name()))
            .findFirst().orElse(null);
        if (declared == null) {
            return null;
        }
        TypeDef target = targetArgument(chosen.returnType(), requested, declared.name());
        // A wildcard of the target is no equality: the arguments infer the variable
        if (target == null || TypeHierarchy.unwrap(target) instanceof TypeDef.Wildcard) {
            return null;
        }
        TypeDef erasedComponent = declared.bounds().isEmpty() ? TypeDef.OBJECT : erasedDeep(declared.bounds().getFirst());
        TypeDef.Array erased = new TypeDef.Array(erasedComponent, 1, false);
        if (!(TypeHierarchy.unwrap(target) instanceof ClassTypeDef component) || component instanceof ClassTypeDef.Parameterized) {
            return erased;
        }
        for (int i = generic.size() - 1; i < arguments.size(); i++) {
            TypeDef argument = arguments.get(i);
            if (argument != null && !assignable(context, argument, component)) {
                return erased;
            }
        }
        return new TypeDef.Array(component, 1, false);
    }

    /**
     * The type argument a requested return type gives a variable the declared return type has as an argument:
     * {@code String} of {@code List<String>} for the {@code E} of {@code List<E> of(E...)}.
     */
    private static @Nullable TypeDef targetArgument(TypeDef declaredReturn, TypeDef requested, String variable) {
        if (!(TypeHierarchy.unwrap(declaredReturn) instanceof ClassTypeDef.Parameterized declared)
            || !(TypeHierarchy.unwrap(requested) instanceof ClassTypeDef.Parameterized target)
            || !declaredClass(declared).getName().equals(declaredClass(target).getName())
            || declared.typeArguments().size() != target.typeArguments().size()) {
            return null;
        }
        for (int i = 0; i < declared.typeArguments().size(); i++) {
            if (TypeHierarchy.unwrap(declared.typeArguments().get(i)) instanceof TypeDef.TypeVariable argument
                && argument.name().equals(variable)) {
                return target.typeArguments().get(i);
            }
        }
        return null;
    }

    /**
     * The array a variable arity tail is packed into, typed as javac infers it: a variable of the method is inferred
     * from every argument - `String[]` for `component("abc")`, `Base[]` for siblings, `Object[]` where a fixed
     * argument is an `Object` - and an array component keeps its rank.
     */
    private static TypeDef.@Nullable Array inferredArray(ResolutionContext context, Candidate chosen, List<@Nullable TypeDef> arguments) {
        List<TypeDef> generic = chosen.parameters();
        if (generic.isEmpty() || !(TypeHierarchy.unwrap(generic.getLast()) instanceof TypeDef.Array last)) {
            return null;
        }
        Map<String, TypeDef> receiver = receiverArguments(context, chosen);
        if (!mentions(last, chosen.variables())) {
            // A variable of the class is the argument the receiver binds it with: `ClassVarargs<String>` packs `String[]`
            TypeDef bound = TypeHierarchy.substituted(last, receiver);
            return bound.equals(last) || !(erasedDeep(bound) instanceof TypeDef.Array array) ? null : array;
        }
        List<TypeDef> parameters = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            parameters.add(TypeHierarchy.substituted(expanded(generic, i, true), receiver));
        }
        // The method's bounds as the receiver specializes them: `<U extends T>` of a `Receiver<Integer>`
        Map<String, TypeDef> bounds = new HashMap<>();
        List<TypeDef.TypeVariable> variables = chosen.variables().stream()
            .map(variable -> TypeDef.variable(variable.name(), variable.bounds().stream()
                .map(bound -> TypeHierarchy.substituted(bound, receiver)).toList()))
            .toList();
        variables.forEach(variable -> bounds.put(variable.name(), variable));
        expandChains(bounds);
        Inference inference = infer(context, parameters, arguments, variables, true);
        if (inference.conflict()) {
            return null;
        }
        Map<String, TypeDef> solution = new HashMap<>(inference.solution());
        // A variable no value binds is its bound: an empty call of a `Receiver<Integer>` packs `Integer[]`
        for (TypeDef.TypeVariable variable : variables) {
            if (!solution.containsKey(variable.name())) {
                List<TypeDef> declaredBounds = bounds.get(variable.name()) instanceof TypeDef.TypeVariable bounded ? bounded.bounds() : List.of();
                solution.put(variable.name(), declaredBounds.isEmpty() ? TypeDef.OBJECT : erasedDeep(declaredBounds.getFirst()));
            }
        }
        TypeDef receiverLast = TypeHierarchy.substituted(last, receiver);
        TypeDef declared = erasedDeep(TypeHierarchy.substituted(receiverLast, bounds));
        TypeDef component = erasedDeep(TypeHierarchy.substituted(receiverLast, solution));
        if (!(component instanceof TypeDef.Array array) || !(declared instanceof TypeDef.Array declaredArray)) {
            return null;
        }
        if (fits(context, array, declaredArray) || interfaceComponents(array, declaredArray)) {
            // An array of an interface passes for an array of another, which the verifier takes as Objects: javac packs
            // `Alpha[]` for the `Zeta[]` of `T extends Zeta & Alpha`
            return array;
        }
        // The descriptor takes an array of the variable's erasure: of the types the values have in common, the one
        // that is one - `Zeta` of `T extends Zeta & Alpha` - else the erasure
        TypeDef.TypeVariable variable = componentVariable(last, variables);
        if (variable != null) {
            List<TypeDef> lowers = inference.lowerBounds().getOrDefault(variable.name(), List.of());
            for (ClassTypeDef candidate : lowers.isEmpty() ? List.<ClassTypeDef>of() : LeastUpperBounds.commonSupertypes(lowers)) {
                TypeDef candidateArray = erasedDeep(TypeHierarchy.substituted(receiverLast, Map.of(variable.name(), candidate)));
                if (candidateArray instanceof TypeDef.Array candidateAsArray && fits(context, candidateAsArray, declaredArray)) {
                    return candidateAsArray;
                }
            }
        }
        return declaredArray;
    }

    private static boolean interfaceComponents(TypeDef.Array array, TypeDef.Array declared) {
        return array.dimensions() == declared.dimensions()
            && TypeHierarchy.unwrap(array.componentType()) instanceof ClassTypeDef component && component.isInterface()
            && TypeHierarchy.unwrap(declared.componentType()) instanceof ClassTypeDef declaredComponent && declaredComponent.isInterface();
    }

    private static boolean fits(ResolutionContext context, TypeDef.Array array, TypeDef.Array declared) {
        return referenceConvertible(context, array, declared) == Tri.YES;
    }

    private static TypeDef.@Nullable TypeVariable componentVariable(TypeDef.Array array, List<TypeDef.TypeVariable> variables) {
        TypeDef component = TypeHierarchy.unwrap(array.componentType());
        return component instanceof TypeDef.TypeVariable variable && variables.stream().anyMatch(own -> own.name().equals(variable.name()))
            ? variable : null;
    }
}
