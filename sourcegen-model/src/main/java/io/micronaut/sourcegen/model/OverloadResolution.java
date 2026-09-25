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

import io.micronaut.sourcegen.model.OverloadCandidates.Access;
import io.micronaut.sourcegen.model.OverloadCandidates.Candidate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.micronaut.sourcegen.model.Applicability.applicable;
import static io.micronaut.sourcegen.model.OverloadCandidates.accessible;
import static io.micronaut.sourcegen.model.OverloadCandidates.candidates;
import static io.micronaut.sourcegen.model.OverloadCandidates.returns;
import static io.micronaut.sourcegen.model.OverloadCandidates.tiers;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.Specificity.moreSpecific;
import static io.micronaut.sourcegen.model.VarargsPacking.resolved;

/**
 * The method a call names, chosen as javac chooses it (JLS 15.12.2): of the members a call can access, the methods
 * applicable by strict invocation, then by loose invocation, boxing, and only then by variable arity, and of those the
 * most specific one. A method's variables are inferred across all its arguments, from the type arguments a value binds
 * them with and the values themselves, and have to satisfy their bounds. The candidates are those of a compiled, a
 * generated or a source-only type alike - of each bound of an intersection together - and a parameterized receiver
 * binds the variables of the class declaring each of them.
 *
 * <p>Which members a call accesses depends on the class it is written in, which the model does not know while the
 * call is built: there each member competes but a private one, which a caller in another package than a package-private
 * one, or not a subclass of a protected one, does not reach - the bytecode writers, which know it, resolve such a call
 * again where it is written ({@link InvocationResolver}). A member of the platform, which no generated class is in the
 * package of, is a candidate as javac takes it from any such class: a public one, before a protected one.</p>
 *
 * @since 2.3
 */
final class OverloadResolution {

    private OverloadResolution() {
    }

    /**
     * Resolves a call.
     *
     * @param owners        The types the method is invoked on: the receiver's class, or each bound of a variable
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param returningType The return type the caller expects, or {@code null} for a constructor
     * @param values        The argument expressions
     * @param argumentTypes The types of the arguments as they read where the call is written, or {@code null} for the
     *                      types they have
     * @param caller        Where the call is written, or {@code null} where it is not known
     * @return How the call resolves
     */
    static Resolution resolve(List<ClassTypeDef> owners,
                              String name,
                              @Nullable TypeDef returningType,
                              List<? extends ExpressionDef> values,
                              @Nullable List<TypeDef> argumentTypes,
                              @Nullable Caller caller) {
        ResolutionContext context = new ResolutionContext();
        // An annotation on the receiver's type leaves its members as they are
        List<ClassTypeDef> unwrapped = new ArrayList<>();
        for (ClassTypeDef annotatedOwner : owners) {
            if (!(TypeHierarchy.unwrap(annotatedOwner) instanceof ClassTypeDef owner)) {
                return Resolution.UNKNOWN;
            }
            unwrapped.add(owner);
        }
        Map<String, Candidate> members = candidates(context, unwrapped, name);
        if (members == null) {
            return Resolution.UNKNOWN;
        }
        boolean constructor = MethodDef.CONSTRUCTOR.equals(name);
        List<Candidate> all = List.copyOf(members.values());
        if (returningType != null && !constructor) {
            all = all.stream().filter(candidate -> returns(context, candidate, returningType)).toList();
        }
        List<@Nullable TypeDef> arguments = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            ExpressionDef value = values.get(i);
            arguments.add(isNull(value) ? null : argumentTypes != null ? argumentTypes.get(i) : value.type());
        }
        for (List<Candidate> tier : tiers(all, caller, constructor)) {
            Choice choice = choose(context, tier, arguments);
            switch (choice.outcome()) {
                case RESOLVED -> {
                    Candidate chosen = Objects.requireNonNull(choice.chosen());
                    return new Resolution(Outcome.RESOLVED, resolved(context, chosen, choice.variableArity(), arguments, values, returningType), List.of());
                }
                case NOT_APPLICABLE -> {
                    // The next tier, if any
                }
                case AMBIGUOUS -> {
                    return new Resolution(Outcome.AMBIGUOUS, null, choice.ambiguous().stream().map(OverloadResolution::describe).toList());
                }
                default -> {
                    return Resolution.UNKNOWN;
                }
            }
        }
        return Resolution.NOT_APPLICABLE;
    }

    /**
     * The method a call names, where one of the owners declares or inherits a method of the name whose parameters
     * erase to those given.
     *
     * @param owners         The types the method is invoked on
     * @param name           The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param parameterTypes The parameter types, as they read where the call is written
     * @param caller         Where the call is written, or {@code null} where it is not known
     * @return Whether an owner declares it, and whether the caller accesses it
     */
    static Declaration declaration(List<ClassTypeDef> owners, String name, List<TypeDef> parameterTypes, @Nullable Caller caller) {
        Map<String, Candidate> all = candidates(new ResolutionContext(), owners, name);
        if (all == null) {
            return Declaration.UNKNOWN;
        }
        Candidate declared = all.get(signature(parameterTypes));
        if (declared == null) {
            return Declaration.ABSENT;
        }
        return caller == null || accessible(declared, caller, MethodDef.CONSTRUCTOR.equals(name))
            ? Declaration.ACCESSIBLE : Declaration.INACCESSIBLE;
    }

    /**
     * The methods of a name a type declares or inherits that a call of so many arguments can target - not a bridge, a
     * method of {@link Object} for an interface (JLS 9.2), and a private one where the type declares it.
     *
     * @param owner         The type
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param argumentCount The number of arguments
     * @return The methods, or {@code null} where the type carries no member information
     */
    static @Nullable List<MethodDef> members(ClassTypeDef owner, String name, int argumentCount) {
        Map<String, Candidate> all = candidates(new ResolutionContext(), List.of(owner), name);
        if (all == null) {
            return null;
        }
        String ownerName = declaredClass(owner).getName();
        return all.values().stream()
            .filter(candidate -> candidate.access() != Access.PRIVATE || candidate.declaring().equals(ownerName))
            .filter(candidate -> candidate.varargs() ? argumentCount >= candidate.parameters().size() - 1
                : argumentCount == candidate.parameters().size())
            .map(Candidate::method)
            .toList();
    }

    /**
     * Chooses among candidates: of those applicable in the first phase any are, the one more specific than each of the
     * others. Where each is decidedly not, the call is ambiguous; where the model cannot tell, it says so.
     */
    private static Choice choose(ResolutionContext context, List<Candidate> candidates, List<@Nullable TypeDef> arguments) {
        for (int phase = 1; phase <= 3; phase++) {
            List<Candidate> applicable = new ArrayList<>();
            for (Candidate candidate : candidates) {
                Tri result = applicable(context, candidate, arguments, phase);
                if (result == Tri.UNKNOWN) {
                    return Choice.UNKNOWN;
                }
                if (result == Tri.YES) {
                    applicable.add(candidate);
                }
            }
            if (applicable.isEmpty()) {
                continue;
            }
            boolean variableArity = phase == 3;
            int count = applicable.size();
            Tri[][] specific = new Tri[count][count];
            boolean decided = true;
            List<Candidate> maximal = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Tri most = Tri.YES;
                for (int j = 0; j < count; j++) {
                    if (i != j) {
                        specific[i][j] = moreSpecific(context, applicable.get(i), applicable.get(j), arguments.size(), variableArity);
                        decided &= specific[i][j] != Tri.UNKNOWN;
                        most = most.and(specific[i][j]);
                    }
                }
                if (most == Tri.UNKNOWN) {
                    return Choice.UNKNOWN;
                }
                if (most == Tri.YES) {
                    maximal.add(applicable.get(i));
                }
            }
            if (maximal.size() == 1) {
                return new Choice(Outcome.RESOLVED, maximal.getFirst(), variableArity, List.of());
            }
            if (!maximal.isEmpty() || !decided) {
                // Methods more specific than each other are ones the model tells apart no further
                return Choice.UNKNOWN;
            }
            // No method is more specific than all the others: those no other is strictly more specific than are
            // maximally specific, and javac rejects the call
            List<Candidate> ambiguous = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                boolean dominated = false;
                for (int j = 0; j < count; j++) {
                    dominated |= i != j && specific[j][i] == Tri.YES && specific[i][j] == Tri.NO;
                }
                if (!dominated) {
                    ambiguous.add(applicable.get(i));
                }
            }
            return new Choice(Outcome.AMBIGUOUS, null, variableArity, ambiguous);
        }
        return Choice.NOT_APPLICABLE;
    }

    /**
     * The signature of parameters as a class file erases them.
     *
     * @param parameterTypes The parameter types
     * @return The signature
     */
    static String signature(List<TypeDef> parameterTypes) {
        return parameterTypes.stream().map(ResolutionTypes::descriptorName).toList().toString();
    }

    private static String describe(Candidate candidate) {
        String name = candidate.method().getName();
        return candidate.declaring() + "#" + (MethodDef.CONSTRUCTOR.equals(name) ? "<init>" : name)
            + candidate.method().getParameters().stream().map(parameter -> TypeHierarchy.erasedName(parameter.getType()))
            .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    private static boolean isNull(ExpressionDef value) {
        return value instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    /**
     * An answer the model can give, or not.
     */
    enum Tri {
        YES, NO, UNKNOWN;

        static Tri of(boolean value) {
            return value ? YES : NO;
        }

        Tri and(Tri other) {
            if (this == NO || other == NO) {
                return NO;
            }
            return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : YES;
        }
    }

    /**
     * How a call resolves.
     */
    enum Outcome {
        /**
         * One method is more specific than each other applicable one.
         */
        RESOLVED,
        /**
         * No method applies to the arguments.
         */
        NOT_APPLICABLE,
        /**
         * Several methods apply and none is more specific than each of the others, which javac rejects.
         */
        AMBIGUOUS,
        /**
         * The model cannot tell.
         */
        UNKNOWN
    }

    /**
     * Whether a type declares a method a call names.
     */
    enum Declaration {
        /**
         * The type carries no member information.
         */
        UNKNOWN,
        /**
         * The type has no method of the name and parameters.
         */
        ABSENT,
        /**
         * The type has the method, which the caller accesses.
         */
        ACCESSIBLE,
        /**
         * The type has the method, which the caller does not access.
         */
        INACCESSIBLE
    }

    /**
     * A call resolved.
     *
     * @param outcome   How it resolves
     * @param resolved  The call to the method it resolves to, where it does
     * @param ambiguous The methods an ambiguous call names, described
     */
    record Resolution(Outcome outcome, Invocations.@Nullable Resolved resolved, List<String> ambiguous) {
        static final Resolution NOT_APPLICABLE = new Resolution(Outcome.NOT_APPLICABLE, null, List.of());
        static final Resolution UNKNOWN = new Resolution(Outcome.UNKNOWN, null, List.of());
    }

    /**
     * Where a call is written: the class its class file is, and whether its receiver is that class's `this` or
     * `super` - of a constructor, whether it is invoked by `this(...)` or `super(...)` rather than `new`.
     *
     * @param definition The definition the call is written in
     * @param self       Whether the receiver is `this` or `super`
     */
    record Caller(ObjectDef definition, boolean self) {

        String name() {
            return definition.asTypeDef().getName();
        }

        String packageName() {
            return TypeHierarchy.packageOf(definition);
        }
    }

    /**
     * The method chosen among candidates.
     *
     * @param outcome       How the call resolves among them
     * @param chosen        The method, where the call resolves
     * @param variableArity Whether it applies by variable arity
     * @param ambiguous     The maximally specific methods of an ambiguous call
     */
    private record Choice(Outcome outcome, @Nullable Candidate chosen, boolean variableArity, List<Candidate> ambiguous) {
        private static final Choice NOT_APPLICABLE = new Choice(Outcome.NOT_APPLICABLE, null, false, List.of());
        private static final Choice UNKNOWN = new Choice(Outcome.UNKNOWN, null, false, List.of());
    }
}
