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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.model.ResolutionTypes.boundAt;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.ResolutionTypes.mentions;
import static io.micronaut.sourcegen.model.ResolutionTypes.sameType;

/**
 * What the arguments of a call tell of the method's variables (JLS 18): the type a variable is bound with, the types
 * it takes values of and the types it has to be a subtype of.
 *
 * @since 2.3
 */
final class Inference {

    /**
     * The resolution, which captures the wildcards the arguments bind variables with.
     */
    private final ResolutionContext context;
    /**
     * The names of the method's variables.
     */
    private final Set<String> names = new HashSet<>();
    /**
     * The type a variable is bound with.
     */
    private final Map<String, TypeDef> equalities = new HashMap<>();
    /**
     * The types a variable takes values of.
     */
    private final Map<String, List<TypeDef>> lowerBounds = new HashMap<>();
    /**
     * The types a variable has to be a subtype of.
     */
    private final Map<String, List<TypeDef>> upperBounds = new HashMap<>();
    /**
     * Whether a variable is bound with two types.
     */
    private boolean conflict;

    private Inference(ResolutionContext context, List<TypeDef.TypeVariable> variables) {
        this.context = context;
        variables.forEach(variable -> names.add(variable.name()));
    }

    /**
     * Infers the method's variables from the arguments: a type argument a value binds one with is an equality, and
     * a value passed where one is, or an element of an array of one, is a lower bound. An argument that cannot bind
     * one consistently is a conflict.
     *
     * @param context    The resolution, which captures the wildcards of the arguments
     * @param parameters The parameters the arguments are passed to
     * @param arguments  The types of the arguments, {@code null} for a {@code null}
     * @param variables  The method's variables, with their bounds
     * @param loose      Whether a primitive is boxed
     * @return What the arguments tell of the variables
     */
    static Inference infer(ResolutionContext context, List<TypeDef> parameters, List<@Nullable TypeDef> arguments,
                           List<TypeDef.TypeVariable> variables, boolean loose) {
        Inference inference = new Inference(context, variables);
        if (!inference.names.isEmpty()) {
            inference.inferFrom(parameters, arguments, variables, loose);
        }
        return inference;
    }

    /**
     * The type each variable is bound with.
     *
     * @return The types, by name
     */
    Map<String, TypeDef> equalities() {
        return equalities;
    }

    /**
     * The types each variable takes values of.
     *
     * @return The types, by name
     */
    Map<String, List<TypeDef>> lowerBounds() {
        return lowerBounds;
    }

    /**
     * The types each variable has to be a subtype of.
     *
     * @return The types, by name
     */
    Map<String, List<TypeDef>> upperBounds() {
        return upperBounds;
    }

    /**
     * Whether a variable is bound with two types.
     *
     * @return true where one is
     */
    boolean conflict() {
        return conflict;
    }

    /**
     * Each variable as it is inferred: its equality, else the least upper bound of its lower bounds.
     *
     * @return The types, by name
     */
    Map<String, TypeDef> solution() {
        Map<String, TypeDef> solution = new HashMap<>(equalities);
        lowerBounds.forEach((name, lowers) -> {
            if (!solution.containsKey(name) && !lowers.isEmpty()) {
                solution.put(name, LeastUpperBounds.leastUpperBound(lowers));
            }
        });
        return solution;
    }

    private void inferFrom(List<TypeDef> parameters, List<@Nullable TypeDef> arguments, List<TypeDef.TypeVariable> variables,
                           boolean loose) {
        for (int i = 0; i < parameters.size(); i++) {
            TypeDef argument = arguments.get(i);
            if (argument != null) {
                collect(parameters.get(i), argument, loose);
            }
        }
        // A value a variable takes is a subtype of each bound too, whose type arguments that binds: a `java.sql.Date`
        // is the `Comparable<java.util.Date>` a `T extends Comparable<T>` is, which makes T a `java.util.Date`, and a
        // `LocalDate` makes it a `ChronoLocalDate` (JLS 18.3.1)
        for (TypeDef.TypeVariable variable : variables) {
            List<TypeDef> values = new ArrayList<>(lowerBounds.getOrDefault(variable.name(), List.of()));
            TypeDef equal = equalities.get(variable.name());
            if (equal != null) {
                values.add(equal);
            }
            for (TypeDef bound : variable.bounds()) {
                if (TypeHierarchy.unwrap(bound) instanceof ClassTypeDef.Parameterized && mentions(bound, variables)) {
                    values.forEach(value -> collect(bound, value, false));
                }
            }
        }
        // `U extends T`: what U takes, T takes too
        for (int pass = 0; pass < variables.size(); pass++) {
            for (TypeDef.TypeVariable variable : variables) {
                List<TypeDef> values = new ArrayList<>(lowerBounds.getOrDefault(variable.name(), List.of()));
                TypeDef equal = equalities.get(variable.name());
                if (equal != null) {
                    values.add(equal);
                }
                for (TypeDef bound : variable.bounds()) {
                    if (TypeHierarchy.unwrap(bound) instanceof TypeDef.TypeVariable other && names.contains(other.name())) {
                        List<TypeDef> lowers = lowerBounds.computeIfAbsent(other.name(), ignore -> new ArrayList<>());
                        values.stream().filter(value -> lowers.stream().noneMatch(lower -> sameType(lower, value))).forEach(lowers::add);
                    }
                }
            }
        }
    }

    private void collect(TypeDef parameter, TypeDef argument, boolean loose) {
        TypeDef param = TypeHierarchy.unwrap(parameter);
        TypeDef value = TypeHierarchy.unwrap(argument);
        if (param instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
            if (value instanceof TypeDef.Primitive primitive) {
                if (!loose) {
                    return;
                }
                value = primitive.wrapperType();
            }
            lowerBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>()).add(value);
            return;
        }
        if (param instanceof TypeDef.Array paramArray && value instanceof TypeDef.Array valueArray
            && valueArray.dimensions() >= paramArray.dimensions()) {
            // Arrays are covariant: an element of the value is a lower bound of the component
            TypeDef component = valueArray.dimensions() == paramArray.dimensions() ? valueArray.componentType()
                : TypeDef.array(valueArray.componentType(), valueArray.dimensions() - paramArray.dimensions());
            if (!TypeHierarchy.unwrap(component).isPrimitive()) {
                collect(paramArray.componentType(), component, loose);
            }
            return;
        }
        if (param instanceof ClassTypeDef.Parameterized declared && value instanceof ClassTypeDef valueClass) {
            ClassTypeDef inherited = TypeHierarchy.asSupertype(valueClass, declaredClass(declared).getName(), null);
            if (!(inherited instanceof ClassTypeDef.Parameterized actual) || actual.typeArguments().size() != declared.typeArguments().size()) {
                return;
            }
            List<List<TypeDef>> declaredBounds = TypeHierarchy.declaredBounds(declared);
            for (int i = 0; i < declared.typeArguments().size(); i++) {
                equate(declared.typeArguments().get(i), actual.typeArguments().get(i), boundAt(declaredBounds, i));
            }
        }
    }

    private void equate(TypeDef declared, TypeDef actual, List<TypeDef> declaredBound) {
        TypeDef argument = TypeHierarchy.unwrap(declared);
        TypeDef value = TypeHierarchy.unwrap(actual);
        if (value instanceof TypeDef.Wildcard wildcard) {
            if (argument instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                // The variable is a fresh capture of the wildcard: a `List<? extends String>` binds no
                // `T extends Number`, and two captures are distinct
                TypeDef capture = context.capture(wildcard, declaredBound);
                TypeDef existing = equalities.putIfAbsent(variable.name(), capture);
                if (existing != null && !sameType(existing, capture)) {
                    conflict = true;
                }
            } else if (argument instanceof TypeDef.Wildcard formal) {
                // `? extends A` within `? extends F`: A is a subtype of F; `? super A` within `? super F`: F of A
                TypeDef actualUpper = wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : wildcard.upperBounds().getFirst();
                if (wildcard.lowerBounds().isEmpty()) {
                    for (TypeDef upper : formal.upperBounds()) {
                        if (TypeHierarchy.unwrap(upper) instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                            lowerBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>()).add(actualUpper);
                        } else {
                            collect(upper, actualUpper, false);
                        }
                    }
                } else {
                    // `? super A` has Object for its upper bound: within `? extends T` only where T is an Object
                    for (TypeDef upper : formal.upperBounds()) {
                        if (TypeHierarchy.unwrap(upper) instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                            lowerBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>()).add(actualUpper);
                        }
                    }
                    for (TypeDef lower : formal.lowerBounds()) {
                        if (TypeHierarchy.unwrap(lower) instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                            upperBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>())
                                .add(wildcard.lowerBounds().getFirst());
                        }
                    }
                }
            }
            return;
        }
        if (argument instanceof TypeDef.Array argumentArray && value instanceof TypeDef.Array valueArray
            && valueArray.dimensions() >= argumentArray.dimensions()) {
            // `T[]` of a `String[][]` binds T to `String[]`
            equate(argumentArray.componentType(), valueArray.dimensions() == argumentArray.dimensions() ? valueArray.componentType()
                : TypeDef.array(valueArray.componentType(), valueArray.dimensions() - argumentArray.dimensions()), List.of());
            return;
        }
        if (argument instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
            TypeDef existing = equalities.putIfAbsent(variable.name(), value);
            if (existing != null && !sameType(existing, value)) {
                conflict = true;
            }
            return;
        }
        if (argument instanceof TypeDef.Wildcard wildcard) {
            // `? extends T` takes a subtype of T - and `? extends List<T>` a subtype of a list of T - `? super T` a
            // supertype
            for (TypeDef upper : wildcard.upperBounds()) {
                if (TypeHierarchy.unwrap(upper) instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                    lowerBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>()).add(value);
                } else {
                    collect(upper, value, false);
                }
            }
            for (TypeDef lower : wildcard.lowerBounds()) {
                if (TypeHierarchy.unwrap(lower) instanceof TypeDef.TypeVariable variable && names.contains(variable.name())) {
                    upperBounds.computeIfAbsent(variable.name(), ignore -> new ArrayList<>()).add(value);
                }
            }
            return;
        }
        if (argument instanceof ClassTypeDef.Parameterized nested && value instanceof ClassTypeDef.Parameterized nestedValue
            && nested.typeArguments().size() == nestedValue.typeArguments().size()) {
            List<List<TypeDef>> declaredBounds = TypeHierarchy.declaredBounds(nested);
            for (int i = 0; i < nested.typeArguments().size(); i++) {
                equate(nested.typeArguments().get(i), nestedValue.typeArguments().get(i), boundAt(declaredBounds, i));
            }
        }
    }

    /**
     * Substitutes the variables of a scope into each other's bounds, so that a variable bounded by another carries
     * that one's bounds - a few levels suffice for the erasure, which takes the first bound of each.
     */
    static void expandChains(Map<String, TypeDef> scope) {
        for (int pass = 0; pass < 4; pass++) {
            Map<String, TypeDef> previous = new HashMap<>(scope);
            for (Map.Entry<String, TypeDef> entry : previous.entrySet()) {
                if (entry.getValue() instanceof TypeDef.TypeVariable variable) {
                    scope.put(entry.getKey(), TypeDef.variable(variable.name(), variable.bounds().stream()
                        .map(bound -> TypeHierarchy.substituted(bound, previous)).toList()));
                }
            }
        }
        // A chain longer than the passes - `A extends B, B extends C, ...` - ends at its last variable's bounds:
        // each variable whose first bound is another of the scope takes that one, expanded once, in any length
        Map<String, TypeDef> chained = new HashMap<>();
        for (String name : List.copyOf(scope.keySet())) {
            TypeDef expanded = chained(name, scope, chained, new HashSet<>());
            if (expanded != null) {
                scope.put(name, expanded);
            }
        }
    }

    private static @Nullable TypeDef chained(String name, Map<String, TypeDef> scope, Map<String, TypeDef> chained, Set<String> visiting) {
        TypeDef done = chained.get(name);
        if (done != null) {
            return done;
        }
        TypeDef value = scope.get(name);
        if (!(value instanceof TypeDef.TypeVariable variable) || variable.bounds().isEmpty() || !visiting.add(name)) {
            return value;
        }
        TypeDef first = variable.bounds().getFirst();
        TypeDef result = value;
        if (TypeHierarchy.unwrap(first) instanceof TypeDef.TypeVariable next && !next.name().equals(name)
            && scope.get(next.name()) != null && !visiting.contains(next.name())) {
            TypeDef expanded = chained(next.name(), scope, chained, visiting);
            if (expanded != null) {
                List<TypeDef> bounds = new ArrayList<>(variable.bounds());
                bounds.set(0, expanded);
                result = TypeDef.variable(variable.name(), bounds);
            }
        }
        visiting.remove(name);
        chained.put(name, result);
        return result;
    }
}
