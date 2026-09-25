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

import static io.micronaut.sourcegen.model.Inference.infer;
import static io.micronaut.sourcegen.model.OverloadCandidates.rawReceiver;
import static io.micronaut.sourcegen.model.OverloadCandidates.receiverArguments;
import static io.micronaut.sourcegen.model.ResolutionContext.isCapture;
import static io.micronaut.sourcegen.model.ResolutionTypes.ARRAY_SUPERTYPES;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.ResolutionTypes.erasedDeep;
import static io.micronaut.sourcegen.model.ResolutionTypes.expanded;
import static io.micronaut.sourcegen.model.ResolutionTypes.isObject;
import static io.micronaut.sourcegen.model.ResolutionTypes.isSubtype;
import static io.micronaut.sourcegen.model.ResolutionTypes.mentions;
import static io.micronaut.sourcegen.model.ResolutionTypes.sameType;
import static io.micronaut.sourcegen.model.ResolutionTypes.unboxed;
import static io.micronaut.sourcegen.model.ResolutionTypes.widens;

/**
 * Whether a method applies to the arguments of a call, by strict invocation, loose invocation or variable arity
 * (JLS 15.12.2.2-4): each argument converts to its parameter, with the method's variables inferred and within their
 * bounds, and a type argument contained by the one declared (JLS 4.5.1).
 *
 * @since 2.3
 */
final class Applicability {

    private Applicability() {
    }

    static Tri applicable(ResolutionContext context, Candidate candidate, List<@Nullable TypeDef> arguments, int phase) {
        List<TypeDef> generic = candidate.parameters();
        if (phase < 3 ? generic.size() != arguments.size() : !candidate.varargs() || arguments.size() < generic.size() - 1) {
            return Tri.NO;
        }
        boolean loose = phase > 1;
        boolean raw = rawReceiver(candidate);
        Map<String, TypeDef> receiver = receiverArguments(context, candidate);
        List<TypeDef> parameters = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            TypeDef parameter = expanded(generic, i, phase == 3);
            // A member of a raw receiver takes the erasure of its types
            parameters.add(raw ? erasedDeep(parameter) : TypeHierarchy.substituted(parameter, receiver));
        }
        // The bounds of the method's variables name the class's too, which the receiver binds
        List<TypeDef.TypeVariable> variables = raw ? List.of() : candidate.variables().stream()
            .map(variable -> TypeDef.variable(variable.name(), variable.bounds().stream()
                .map(bound -> TypeHierarchy.substituted(bound, receiver)).toList()))
            .toList();
        Inference inference = infer(context, parameters, arguments, variables, loose);
        if (inference.conflict()) {
            return Tri.NO;
        }
        Tri result = Tri.YES;
        // An inferred variable satisfies each of its bounds - with their type arguments: `Comparable<T>` is a
        // `Comparable` of the variable itself
        Map<String, TypeDef> solution = inference.solution();
        for (TypeDef.TypeVariable variable : variables) {
            TypeDef equal = inference.equalities().get(variable.name());
            List<TypeDef> lowers = inference.lowerBounds().getOrDefault(variable.name(), List.of());
            // The value the variable takes: its equality, else each lower bound - a least upper bound satisfies a
            // bound where each of the values it is the bound of does
            List<TypeDef> values = equal != null ? List.of(equal) : lowers;
            for (TypeDef value : values) {
                Map<String, TypeDef> self = new HashMap<>(solution);
                self.put(variable.name(), value);
                for (TypeDef bound : variable.bounds()) {
                    // A bound naming the method's variables holds for their one solution - `T extends Comparable<T>`
                    // of a String and an Integer does not - a plain one for each value
                    TypeDef checked = mentions(bound, variables) ? solution.getOrDefault(variable.name(), value) : value;
                    Map<String, TypeDef> scope = new HashMap<>(solution);
                    scope.put(variable.name(), checked);
                    result = result.and(referenceConvertible(context, checked, TypeHierarchy.substituted(bound, mentions(bound, variables) ? scope : self)));
                }
                // `? super T` bounds it from above
                for (TypeDef upper : inference.upperBounds().getOrDefault(variable.name(), List.of())) {
                    result = result.and(referenceConvertible(context, value, upper));
                }
            }
            if (equal != null) {
                for (TypeDef lower : lowers) {
                    result = result.and(referenceConvertible(context, lower, equal));
                }
            }
            List<TypeDef> uppers = inference.upperBounds().getOrDefault(variable.name(), List.of());
            if (values.isEmpty() && !uppers.isEmpty()) {
                // Bounded from above only: some type is below each upper bound and the declared ones
                List<TypeDef> above = new ArrayList<>(uppers);
                variable.bounds().stream().filter(bound -> !mentions(bound, variables)).forEach(above::add);
                result = result.and(satisfiable(above));
            }
            if (result == Tri.NO) {
                return Tri.NO;
            }
        }
        for (int i = 0; i < arguments.size(); i++) {
            result = result.and(convertible(context, arguments.get(i), TypeHierarchy.substituted(parameters.get(i), inference.equalities()),
                loose, variables));
            if (result == Tri.NO) {
                return Tri.NO;
            }
        }
        return result;
    }

    /**
     * Whether a value converts to a parameter by a strict invocation - identity, primitive and reference widening -
     * or, loosely, by boxing or unboxing too. A variable of the method takes a value that satisfies every bound.
     */
    private static Tri convertible(ResolutionContext context, @Nullable TypeDef argument, TypeDef parameter, boolean loose, List<TypeDef.TypeVariable> variables) {
        TypeDef param = TypeHierarchy.unwrap(parameter);
        if (param instanceof TypeDef.TypeVariable capture && isCapture(capture)) {
            // Loosely a primitive is boxed first: `1` is an Integer of a `? super Integer`
            return loose && argument != null && TypeHierarchy.unwrap(argument) instanceof TypeDef.Primitive primitive
                ? convertibleToCapture(context, primitive.wrapperType(), capture) : convertibleToCapture(context, argument, capture);
        }
        if (param instanceof TypeDef.TypeVariable variable) {
            List<TypeDef> bounds = variables.stream().filter(declared -> declared.name().equals(variable.name()))
                .findFirst().map(TypeDef.TypeVariable::bounds).orElse(variable.bounds());
            if (argument == null) {
                return Tri.YES;
            }
            TypeDef value = TypeHierarchy.unwrap(argument);
            if (value instanceof TypeDef.Primitive primitive) {
                if (!loose) {
                    return Tri.NO;
                }
                value = primitive.wrapperType();
            }
            Tri result = Tri.YES;
            Map<String, TypeDef> self = Map.of(variable.name(), value);
            for (TypeDef bound : bounds) {
                result = result.and(referenceConvertible(context, value, TypeHierarchy.substituted(bound, self)));
            }
            return result;
        }
        if (param instanceof TypeDef.Wildcard wildcard) {
            param = wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : wildcard.upperBounds().getFirst();
        }
        if (argument == null) {
            return param.isPrimitive() ? Tri.NO : Tri.YES;
        }
        TypeDef value = TypeHierarchy.unwrap(argument);
        if (value instanceof TypeDef.Primitive from && param instanceof TypeDef.Primitive to) {
            return Tri.of(widens(from.clazz(), to.clazz()));
        }
        if (value instanceof TypeDef.Primitive primitive) {
            return loose ? referenceConvertible(context, primitive.wrapperType(), param) : Tri.NO;
        }
        if (param instanceof TypeDef.Primitive primitive) {
            if (!loose) {
                return Tri.NO;
            }
            Class<?> unboxed = unboxed(value);
            return Tri.of(unboxed != null && widens(unboxed, primitive.clazz()));
        }
        return referenceConvertible(context, value, param);
    }

    /**
     * Whether a value of a type converts to another in an assignment context (JLS 5.2): by widening, boxing or
     * unboxing, compared by erasure.
     */
    static boolean assignable(ResolutionContext context, TypeDef type, TypeDef target) {
        TypeDef from = TypeHierarchy.unwrap(type);
        TypeDef to = TypeHierarchy.unwrap(target);
        if (from instanceof TypeDef.Primitive fromPrimitive) {
            if (TypeDef.VOID.equals(fromPrimitive) || TypeDef.VOID.equals(to)) {
                return false;
            }
            return to instanceof TypeDef.Primitive toPrimitive ? widens(fromPrimitive.clazz(), toPrimitive.clazz())
                : referenceConvertible(context, fromPrimitive.wrapperType(), erasedDeep(to)) == Tri.YES;
        }
        if (to instanceof TypeDef.Primitive toPrimitive) {
            Class<?> unboxed = unboxed(from);
            return unboxed != null && widens(unboxed, toPrimitive.clazz());
        }
        return referenceConvertible(context, erasedDeep(from), erasedDeep(to)) == Tri.YES;
    }

    /**
     * Whether a reference is a subtype of a type, with the type arguments it declares: invariant, or contained by
     * a wildcard - a {@code List<String>} is no {@code List<? extends Number>}.
     */
    static Tri referenceConvertible(ResolutionContext context, TypeDef argument, TypeDef parameter) {
        TypeDef value = TypeHierarchy.unwrap(argument);
        TypeDef param = TypeHierarchy.unwrap(parameter);
        if (value instanceof TypeDef.TypeVariable variable) {
            if (variable.bounds().size() > 1) {
                // A value of `T extends Object & Shared` is a `Shared` too: any of its bounds converts it
                Tri result = Tri.NO;
                for (TypeDef bound : variable.bounds()) {
                    Tri converted = referenceConvertible(context, bound, parameter);
                    if (converted == Tri.YES) {
                        return Tri.YES;
                    }
                    if (converted == Tri.UNKNOWN) {
                        result = Tri.UNKNOWN;
                    }
                }
                return result;
            }
            value = variable.bounds().isEmpty() ? TypeDef.OBJECT : TypeHierarchy.unwrap(variable.bounds().getFirst());
        }
        if (param instanceof TypeDef.TypeVariable capture && isCapture(capture)) {
            return convertibleToCapture(context, argument, capture);
        }
        if (param instanceof TypeDef.TypeVariable variable) {
            // A variable the inference leaves open takes what satisfies its bounds, which are checked apart
            return Tri.of(!value.isPrimitive() || variable.bounds().isEmpty());
        }
        if (value instanceof TypeDef.Array valueArray) {
            if (param instanceof TypeDef.Array paramArray) {
                if (valueArray.dimensions() != paramArray.dimensions()) {
                    // A deeper array is an array of arrays, which are Objects
                    return Tri.of(valueArray.dimensions() > paramArray.dimensions()
                        && TypeHierarchy.unwrap(paramArray.componentType()) instanceof ClassTypeDef component
                        && ARRAY_SUPERTYPES.contains(declaredClass(component).getName()));
                }
                TypeDef valueComponent = TypeHierarchy.unwrap(valueArray.componentType());
                TypeDef paramComponent = TypeHierarchy.unwrap(paramArray.componentType());
                if (valueComponent.isPrimitive() || paramComponent.isPrimitive()) {
                    return Tri.of(valueComponent.equals(paramComponent));
                }
                return referenceConvertible(context, valueComponent, paramComponent);
            }
            return Tri.of(param instanceof ClassTypeDef classTypeDef && ARRAY_SUPERTYPES.contains(declaredClass(classTypeDef).getName()));
        }
        if (param instanceof TypeDef.Array) {
            return Tri.NO;
        }
        if (!(param instanceof ClassTypeDef paramClass) || !(value instanceof ClassTypeDef valueClass)) {
            return Tri.UNKNOWN;
        }
        ClassTypeDef paramRaw = declaredClass(paramClass);
        Tri subtype = Object.class.getName().equals(paramRaw.getName()) ? Tri.YES : isSubtype(declaredClass(valueClass), paramRaw.getName());
        Map<String, TypeDef> declaredEnclosing = TypeHierarchy.enclosingArguments(paramClass, null);
        if (subtype != Tri.YES || !(paramClass instanceof ClassTypeDef.Parameterized) && declaredEnclosing.isEmpty()) {
            return subtype;
        }
        ClassTypeDef asDeclared = TypeHierarchy.asSupertype(valueClass, paramRaw.getName(), null);
        if (asDeclared == null) {
            return Tri.UNKNOWN;
        }
        // The arguments of the enclosing types are invariant as well: an `Outer<Long>.Inner` is no `Outer<Integer>.Inner`
        Map<String, TypeDef> inheritedEnclosing = TypeHierarchy.enclosingArguments(asDeclared, null);
        Tri result = Tri.YES;
        for (Map.Entry<String, TypeDef> entry : declaredEnclosing.entrySet()) {
            TypeDef actual = inheritedEnclosing.get(entry.getKey());
            if (actual != null) {
                result = result.and(contains(context, entry.getValue(), actual));
            }
        }
        if (!(paramClass instanceof ClassTypeDef.Parameterized declared)) {
            return result;
        }
        if (!(asDeclared instanceof ClassTypeDef.Parameterized inherited)) {
            // A raw value is an unchecked conversion
            return result;
        }
        if (inherited.typeArguments().size() != declared.typeArguments().size()) {
            return Tri.UNKNOWN;
        }
        for (int i = 0; i < declared.typeArguments().size(); i++) {
            result = result.and(contains(context, declared.typeArguments().get(i), inherited.typeArguments().get(i)));
        }
        return result;
    }

    private static Tri convertibleToCapture(ResolutionContext context, @Nullable TypeDef value, TypeDef.TypeVariable capture) {
        if (value == null) {
            return Tri.YES;
        }
        TypeDef unwrapped = TypeHierarchy.unwrap(value);
        if (unwrapped instanceof TypeDef.TypeVariable variable && variable.name().equals(capture.name())) {
            return Tri.YES;
        }
        TypeDef lower = context.lowerBound(capture);
        return lower == null || unwrapped.isPrimitive() ? Tri.NO : referenceConvertible(context, unwrapped, lower);
    }

    /**
     * Whether a declared type argument contains the one a value has (JLS 4.5.1).
     */
    private static Tri contains(ResolutionContext context, TypeDef declared, TypeDef actual) {
        TypeDef argument = TypeHierarchy.unwrap(declared);
        TypeDef value = TypeHierarchy.unwrap(actual);
        if (argument instanceof TypeDef.TypeVariable) {
            // Left for the inference, which binds it
            return Tri.YES;
        }
        if (argument instanceof TypeDef.Wildcard wildcard) {
            if (wildcard.upperBounds().stream().allMatch(ResolutionTypes::isObject) && wildcard.lowerBounds().isEmpty()) {
                return Tri.YES;
            }
            if (value instanceof TypeDef.Wildcard valueWildcard) {
                // `? extends A` is within `? extends B` where A is a B, `? super A` within `? super B` where B is an A
                if (!wildcard.lowerBounds().isEmpty()) {
                    if (valueWildcard.lowerBounds().isEmpty()) {
                        return Tri.NO;
                    }
                    Tri result = Tri.YES;
                    for (TypeDef lower : wildcard.lowerBounds()) {
                        result = result.and(isOpenBound(lower) ? Tri.YES : referenceConvertible(context, lower, valueWildcard.lowerBounds().getFirst()));
                    }
                    return result;
                }
                TypeDef valueUpper = valueWildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : valueWildcard.upperBounds().getFirst();
                Tri result = Tri.YES;
                for (TypeDef upper : wildcard.upperBounds()) {
                    result = result.and(isOpenBound(upper) ? Tri.YES : referenceConvertible(context, valueUpper, upper));
                }
                return result;
            }
            if (value instanceof TypeDef.TypeVariable) {
                return Tri.UNKNOWN;
            }
            Tri result = Tri.YES;
            for (TypeDef upper : wildcard.upperBounds()) {
                result = result.and(isOpenBound(upper) ? Tri.YES : referenceConvertible(context, value, upper));
            }
            for (TypeDef lower : wildcard.lowerBounds()) {
                result = result.and(isOpenBound(lower) ? Tri.YES : referenceConvertible(context, lower, value));
            }
            return result;
        }
        if (value instanceof TypeDef.Wildcard || value instanceof TypeDef.TypeVariable) {
            return Tri.UNKNOWN;
        }
        return Tri.of(sameType(argument, value));
    }

    /**
     * Whether some type is a subtype of each of the types: two classes where one extends the other, and a final class
     * only of the interfaces it implements.
     */
    private static Tri satisfiable(List<TypeDef> types) {
        List<ClassTypeDef> classes = new ArrayList<>();
        List<ClassTypeDef> declared = new ArrayList<>();
        for (TypeDef type : types) {
            TypeDef unwrapped = TypeHierarchy.unwrap(type);
            if (unwrapped instanceof ClassTypeDef classTypeDef && !isObject(classTypeDef)) {
                classes.add(declaredClass(classTypeDef));
                declared.add(classTypeDef);
            } else if (!(unwrapped instanceof ClassTypeDef)) {
                return Tri.UNKNOWN;
            }
        }
        Tri result = Tri.YES;
        for (int i = 0; i < classes.size(); i++) {
            for (int j = i + 1; j < classes.size(); j++) {
                ClassTypeDef a = classes.get(i);
                ClassTypeDef b = classes.get(j);
                Tri related = isSubtype(a, b.getName()) == Tri.YES || isSubtype(b, a.getName()) == Tri.YES ? Tri.YES : Tri.NO;
                if (related == Tri.YES && (conflicting(declared.get(i), declared.get(j)) || conflicting(declared.get(j), declared.get(i)))) {
                    // No type is a subtype of two parameterizations of one class: `List<String>` and `List<Integer>`
                    return Tri.NO;
                }
                if (related == Tri.NO) {
                    boolean aClass = !a.isInterface();
                    boolean bClass = !b.isInterface();
                    // Two unrelated classes have no common subtype, nor a final class and an interface it lacks
                    related = aClass && bClass || aClass && isFinal(a) || bClass && isFinal(b) ? Tri.NO : Tri.YES;
                }
                result = result.and(related);
            }
        }
        return result;
    }

    /**
     * Whether a type, as the other's class, has other type arguments than it - each of them a type, not a wildcard or
     * a variable, which could still take the other's.
     */
    private static boolean conflicting(ClassTypeDef type, ClassTypeDef other) {
        if (!(other instanceof ClassTypeDef.Parameterized otherParameterized)) {
            return false;
        }
        ClassTypeDef inherited = TypeHierarchy.asSupertype(type, declaredClass(other).getName(), null);
        if (!(inherited instanceof ClassTypeDef.Parameterized parameterized)
            || parameterized.typeArguments().size() != otherParameterized.typeArguments().size()) {
            return false;
        }
        for (int i = 0; i < parameterized.typeArguments().size(); i++) {
            TypeDef argument = parameterized.typeArguments().get(i);
            TypeDef otherArgument = otherParameterized.typeArguments().get(i);
            if (!mentionsOpen(argument) && !mentionsOpen(otherArgument) && !sameType(argument, otherArgument)) {
                return true;
            }
        }
        return false;
    }

    static boolean mentionsOpen(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        return switch (unwrapped) {
            case TypeDef.TypeVariable ignored -> true;
            case TypeDef.Wildcard ignored -> true;
            case ClassTypeDef.Parameterized parameterized -> parameterized.typeArguments().stream().anyMatch(Applicability::mentionsOpen);
            case TypeDef.Array array -> mentionsOpen(array.componentType());
            default -> false;
        };
    }

    private static boolean isFinal(ClassTypeDef type) {
        return type instanceof ClassTypeDef.JavaClass javaClass && java.lang.reflect.Modifier.isFinal(javaClass.type().getModifiers());
    }

    private static boolean isOpenBound(TypeDef bound) {
        TypeDef unwrapped = TypeHierarchy.unwrap(bound);
        return unwrapped instanceof TypeDef.TypeVariable || unwrapped instanceof TypeDef.Wildcard;
    }
}
