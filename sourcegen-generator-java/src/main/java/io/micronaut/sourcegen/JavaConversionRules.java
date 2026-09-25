/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.CalleeBounds;
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.micronaut.sourcegen.JavaCasts.collapseNestedCasts;
import static io.micronaut.sourcegen.JavaCasts.isNullLiteral;
import static io.micronaut.sourcegen.JavaCasts.writesCastToOwnType;
import static io.micronaut.sourcegen.JavaTypes.collectVariables;
import static io.micronaut.sourcegen.JavaTypes.creationComponent;
import static io.micronaut.sourcegen.JavaTypes.isAssignable;
import static io.micronaut.sourcegen.JavaTypes.isPlaceholder;
import static io.micronaut.sourcegen.JavaTypes.loaded;
import static io.micronaut.sourcegen.JavaTypes.ownerOf;
import static io.micronaut.sourcegen.JavaTypes.primitiveOfSource;
import static io.micronaut.sourcegen.JavaTypes.requiresVariableCast;
import static io.micronaut.sourcegen.JavaTypes.substitutionOf;
import static io.micronaut.sourcegen.JavaTypes.unboxedOf;
import static io.micronaut.sourcegen.JavaTypes.widens;

/**
 * The conversions of the Java source generator: the type a value has in the source, which an override can have
 * narrowed from the one of the model, and the casts that are implicit in bytecode but required in source.
 *
 * @since 2.3
 */
@Internal
final class JavaConversionRules {

    private final JavaWriteContext context;

    JavaConversionRules(JavaWriteContext context) {
        this.context = context;
    }

    /**
     * Whether a value is written through `Object` before it is cast, tested or compared: one of a type the other does
     * not convert to by a cast, which javac rejects and the bytecode checks at runtime - a `String` cast to `Integer`,
     * also where an override narrowed the `Object value` of the model to a `String value`.
     */
    boolean widensFirst(ExpressionDef value, TypeDef other, @Nullable MethodDef enclosingMethod, @Nullable ObjectDef objectDef) {
        TypeDef sourceType = sourceTypeOf(value, enclosingMethod, objectDef);
        if (isNullLiteral(value) || isPlaceholder(sourceType) || isPlaceholder(other)) {
            // `this` and `super` are of the definition being written, which no class loader has
            return false;
        }
        Class<?> source = loaded(sourceType, context.scope().typeLookup());
        Class<?> target = loaded(other, context.scope().typeLookup());
        return source != null && target != null && !source.isPrimitive() && !target.isPrimitive()
            && !source.isAssignableFrom(target) && !target.isAssignableFrom(source)
            && (!source.isInterface() && !target.isInterface() || java.lang.reflect.Modifier.isFinal(source.getModifiers())
            || java.lang.reflect.Modifier.isFinal(target.getModifiers()));
    }

    /**
     * Whether a cast of a value is written through `Object`: javac rejects it as inconvertible, where the bytecode
     * checks it at runtime and throws ClassCastException. Those {@link #widensFirst} names, and a primitive cast to a
     * reference its box does not convert to - `(Long) i` of an int, which the bytecode boxes as an Integer - a
     * reference cast to a `char` or a `boolean` other than their box, which the bytecode checks as the box - an
     * Integer cast to a char too - and an array cast to what no array is.
     *
     * @param value           The value cast
     * @param target          The type cast to
     * @param enclosingMethod The method the cast is written in
     * @param objectDef       The definition the cast is written in
     * @return true if the value is cast to Object first
     */
    boolean castsThroughObject(ExpressionDef value, TypeDef target, @Nullable MethodDef enclosingMethod, @Nullable ObjectDef objectDef) {
        if (widensFirst(value, target, enclosingMethod, objectDef)) {
            return true;
        }
        TypeDef sourceType = sourceTypeOf(value, enclosingMethod, objectDef);
        if (isNullLiteral(value) || isPlaceholder(sourceType) || isPlaceholder(target)) {
            return false;
        }
        Class<?> source = runtimeClass(sourceType);
        Class<?> to = runtimeClass(target);
        if (source == null || to == null || source.isPrimitive() && to.isPrimitive()) {
            return false;
        }
        if (source.isPrimitive()) {
            return !to.isAssignableFrom(ReflectionUtils.getWrapperType(source));
        }
        if (to.isPrimitive()) {
            // A box of a number converts to a number as the bytecode does, which renderUnboxed writes; to a char and a
            // boolean the bytecode checks the value as their box
            Class<?> box = ReflectionUtils.getWrapperType(to);
            boolean numericBox = ReflectionUtils.getPrimitiveType(source) != source && source != Boolean.class
                && to != boolean.class && to != char.class;
            return !source.isAssignableFrom(box) && !box.equals(source) && !numericBox;
        }
        return (source.isArray() || to.isArray()) && !arrayCastable(source, to);
    }

    /**
     * Whether a value compared by reference with another is compared as an `Object`: {@link #widensFirst}, and a
     * reference javac takes as incomparable with an array, `Integer` and `int[]`.
     *
     * @param value           The value
     * @param other           The value it is compared with
     * @param enclosingMethod The method the comparison is written in
     * @param objectDef       The definition the comparison is written in
     * @return true if the value is written as an Object
     */
    boolean comparesThroughObject(ExpressionDef value, ExpressionDef other, @Nullable MethodDef enclosingMethod, @Nullable ObjectDef objectDef) {
        TypeDef otherType = sourceTypeOf(other, enclosingMethod, objectDef);
        if (widensFirst(value, otherType, enclosingMethod, objectDef)) {
            return true;
        }
        TypeDef valueType = sourceTypeOf(value, enclosingMethod, objectDef);
        return !valueType.isPrimitive() && !otherType.isPrimitive() && (valueType.isArray() || otherType.isArray())
            && castsThroughObject(value, otherType, enclosingMethod, objectDef);
    }

    /**
     * Whether javac accepts a cast between two reference types one of which is an array.
     */
    private static boolean arrayCastable(Class<?> source, Class<?> target) {
        if (source.isArray() && target.isArray()) {
            Class<?> sourceComponent = source.getComponentType();
            Class<?> targetComponent = target.getComponentType();
            if (sourceComponent.isPrimitive() || targetComponent.isPrimitive()) {
                return sourceComponent.equals(targetComponent);
            }
            return sourceComponent.isArray() || targetComponent.isArray() ? arrayCastable(sourceComponent, targetComponent)
                : sourceComponent.isAssignableFrom(targetComponent) || targetComponent.isAssignableFrom(sourceComponent)
                || !java.lang.reflect.Modifier.isFinal(sourceComponent.getModifiers()) && !java.lang.reflect.Modifier.isFinal(targetComponent.getModifiers())
                && (sourceComponent.isInterface() || targetComponent.isInterface());
        }
        Class<?> other = source.isArray() ? target : source;
        return other == Object.class || other == Cloneable.class || other == java.io.Serializable.class;
    }

    /**
     * The class of a primitive, an array or a loaded class, or null for another type.
     */
    @Nullable
    private Class<?> runtimeClass(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.Primitive primitive) {
            return ClassUtils.getPrimitiveType(primitive.name()).orElse(null);
        }
        if (unwrapped instanceof TypeDef.Array array) {
            Class<?> component = runtimeClass(array.componentType());
            if (component == null || component == void.class) {
                return null;
            }
            for (int i = 0; i < array.dimensions(); i++) {
                component = component.arrayType();
            }
            return component;
        }
        return loaded(unwrapped, context.scope().typeLookup());
    }

    /**
     * The type a value has in the source: that of the parameter it names, which an override can have narrowed
     * from the type the model built the value with.
     */
    TypeDef sourceTypeOf(ExpressionDef value,
                                        @Nullable MethodDef enclosingMethod,
                                        @Nullable ObjectDef objectDef) {
        if (value instanceof ExpressionDef.Cast cast) {
            // A cast to the type the value already has in the model is not written, and leaves the value its type
            return cast.type().equals(cast.expressionDef().type()) && !writesCastToOwnType(cast, collapseNestedCasts(cast.expressionDef()))
                ? sourceTypeOf(cast.expressionDef(), enclosingMethod, objectDef) : cast.type();
        }
        if (value instanceof ExpressionDef.InvokeInstanceMethod invocation && !invocation.method().isConstructor()) {
            // The result of a generated method that override resolution narrowed has the narrowed type
            OverrideResolver.OverriddenMethod emitted = OverrideResolver.emittedSignature(
                ownerOf(objectDef, invocation.instance().type()), objectDef, enclosingMethod, invocation.method(),
                context.scope(), false);
            if (emitted != null) {
                return emitted.returnType();
            }
        }
        if (value instanceof ExpressionDef.IfElse conditional) {
            return branchesType(List.of(conditional.ifExpression(), conditional.elseExpression()), value,
                enclosingMethod, objectDef);
        }
        if (value instanceof ExpressionDef.Switch switchExpression) {
            List<ExpressionDef> results = new ArrayList<>(switchExpression.cases().values());
            if (switchExpression.defaultCase() != null) {
                results.add(switchExpression.defaultCase());
            }
            return branchesType(results, value, enclosingMethod, objectDef);
        }
        if ((value instanceof ExpressionDef.NewArrayOfSize || value instanceof ExpressionDef.NewArrayInitialized)
            && value.type() instanceof TypeDef.Array array && TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable) {
            // An array of a variable is created as one of its erasure, which is no array of the variable - one of a
            // parameterized type is its raw array, which converts to it unchecked
            return TypeDef.array(creationComponent(array, objectDef, enclosingMethod), array.dimensions());
        }
        if (value instanceof ExpressionDef.ArrayElement element) {
            // An element of an array an override narrowed has the narrowed component type
            TypeDef arrayType = sourceTypeOf(element.expression(), enclosingMethod, objectDef);
            if (!arrayType.equals(element.expression().type())
                && TypeHierarchy.unwrap(arrayType) instanceof TypeDef.Array array) {
                return array.dimensions() == 1 ? array.componentType()
                    : TypeDef.array(array.componentType(), array.dimensions() - 1);
            }
        }
        if (value instanceof VariableDef.MethodParameter parameter && enclosingMethod != null) {
            for (ParameterDef declared : enclosingMethod.getParameters()) {
                if (declared.getName().equals(parameter.name())) {
                    return declared.getType();
                }
            }
            // A lambda captures the parameter of a method it is written in, as that method is written
            for (MethodDef outer : context.enclosingMethods()) {
                for (ParameterDef declared : outer.getParameters()) {
                    if (declared.getName().equals(parameter.name())) {
                        return declared.getType();
                    }
                }
            }
        }
        return value.type();
    }

    /**
     * The type of a conditional or a switch expression: the type its results other than `null` have, where they
     * agree. Where they do not, Java types the expression by what they have in common, which need not be the type
     * of the model; one that differs from it is returned, which says that the source type differs.
     */
    private TypeDef branchesType(List<ExpressionDef> results,
                                        ExpressionDef expression,
                                        @Nullable MethodDef enclosingMethod,
                                        @Nullable ObjectDef objectDef) {
        TypeDef modelType = expression.type();
        if (promotesResults(expression, enclosingMethod, objectDef)) {
            // Each result is converted to the type of the model
            return modelType;
        }
        List<TypeDef> types = results.stream()
            .filter(result -> !isNullLiteral(result))
            .map(result -> sourceTypeOf(result, enclosingMethod, objectDef))
            .distinct()
            .toList();
        if (types.size() == 1) {
            return types.get(0);
        }
        return types.stream().filter(type -> !type.equals(modelType)).findFirst().orElse(modelType);
    }

    /**
     * Whether Java promotes the results of a conditional or a switch expression the model types as a reference to
     * other values than the bytecode: where each is a number, or each a boolean, primitive or boxed, Java types the
     * expression as a primitive, where the bytecode converts each result to the type of the model - {@code flag ? 1 :
     * 2L} of an {@code Object} is an {@code Integer} or a {@code Long}, not a {@code long}, and a {@code null}
     * Integer is no NPE. Results of one primitive type alone are promoted to that type, which boxes as they do.
     *
     * @param expression      The conditional or the switch expression
     * @param enclosingMethod The method being written
     * @param objectDef       The definition being written
     * @return true if each result is converted to the type of the model
     */
    boolean promotesResults(ExpressionDef expression, @Nullable MethodDef enclosingMethod, @Nullable ObjectDef objectDef) {
        if (expression.type().isPrimitive() || !(expression instanceof ExpressionDef.IfElse || expression instanceof ExpressionDef.Switch)) {
            return false;
        }
        List<ExpressionDef> results = resultsOf(expression);
        boolean numeric = true;
        boolean bool = true;
        boolean boxed = false;
        java.util.Set<TypeDef> primitives = new java.util.HashSet<>();
        for (ExpressionDef result : results) {
            if (isNullLiteral(result)) {
                return false;
            }
            TypeDef source = TypeHierarchy.unwrap(sourceTypeOf(result, enclosingMethod, objectDef));
            TypeDef.Primitive primitive = source instanceof TypeDef.Primitive p ? p : unboxedOf(source);
            if (primitive == null || primitive.equals(TypeDef.VOID)) {
                return false;
            }
            boxed |= !(source instanceof TypeDef.Primitive);
            primitives.add(primitive);
            boolean isBoolean = primitive.equals(TypeDef.Primitive.BOOLEAN);
            numeric &= !isBoolean;
            bool &= isBoolean;
        }
        return !results.isEmpty() && (numeric || bool) && (boxed || primitives.size() > 1);
    }

    /**
     * The result expressions of a conditional or a switch expression, with the values the blocks of the switch yield.
     */
    private static List<ExpressionDef> resultsOf(ExpressionDef expression) {
        List<ExpressionDef> values = new ArrayList<>();
        if (expression instanceof ExpressionDef.IfElse conditional) {
            values.add(conditional.ifExpression());
            values.add(conditional.elseExpression());
        } else if (expression instanceof ExpressionDef.Switch aSwitch) {
            values.addAll(aSwitch.cases().values());
            if (aSwitch.defaultCase() != null) {
                values.add(aSwitch.defaultCase());
            }
        }
        List<ExpressionDef> results = new ArrayList<>();
        for (ExpressionDef value : values) {
            if (value instanceof ExpressionDef.SwitchYieldCase block) {
                collectYields(block.statement(), results);
            } else {
                results.add(value);
            }
        }
        return results;
    }

    private static void collectYields(@Nullable StatementDef statement, List<ExpressionDef> yields) {
        switch (statement) {
            case StatementDef.Return aReturn when aReturn.expression() != null -> yields.add(aReturn.expression());
            case StatementDef.Multi multi -> multi.statements().forEach(child -> collectYields(child, yields));
            case StatementDef.If anIf -> collectYields(anIf.statement(), yields);
            case StatementDef.IfElse ifElse -> {
                collectYields(ifElse.statement(), yields);
                collectYields(ifElse.elseStatement(), yields);
            }
            case StatementDef.Switch aSwitch -> {
                aSwitch.cases().values().forEach(aCase -> collectYields(aCase, yields));
                collectYields(aSwitch.defaultCase(), yields);
            }
            case StatementDef.While aWhile -> collectYields(aWhile.statement(), yields);
            case StatementDef.Synchronized aSynchronized -> collectYields(aSynchronized.statement(), yields);
            case StatementDef.Try aTry -> {
                collectYields(aTry.statement(), yields);
                aTry.catches().forEach(aCatch -> collectYields(aCatch.statement(), yields));
                collectYields(aTry.finallyStatement(), yields);
            }
            case null, default -> {
            }
        }
    }

    /**
     * Whether a returned value needs a cast to the return type: as an argument does, and also where an override is
     * resolved to return a type variable the value is typed with the bound of - `(T)` for a `CharSequence` value
     * of `T extends CharSequence`. An argument is not cast to a variable, which a generic method infers instead.
     *
     * @param returnType The return type
     * @param valueType  The type of the value
     * @return true if the value is cast
     */
    boolean requiresImplicitReturnCast(TypeDef returnType, TypeDef valueType) {
        if (requiresImplicitInvocationCast(returnType, valueType)) {
            return true;
        }
        TypeDef target = TypeHierarchy.unwrap(returnType);
        if (target instanceof TypeDef.Array targetArray
            && TypeHierarchy.unwrap(valueType) instanceof TypeDef.Array valueArray
            && targetArray.dimensions() == valueArray.dimensions()
            && !targetArray.componentType().equals(valueArray.componentType())) {
            // An array of the erased bound, returned where the override narrows it: `CharSequence[]` as `String[]`
            return targetArray.componentType() instanceof TypeDef.TypeVariable
                || requiresImplicitInvocationCast(targetArray.componentType(), valueArray.componentType());
        }
        return requiresVariableCast(target, valueType);
    }

    boolean requiresImplicitInvocationCast(TypeDef paramType, TypeDef valueType) {
        if (valueType.equals(TypeDef.OBJECT)) {
            return !paramType.equals(TypeDef.OBJECT);
        }
        if (TypeHierarchy.unwrap(paramType) instanceof TypeDef.Primitive primitive) {
            // The bytecode narrows a primitive and unboxes a reference; Java only widens, and unboxes the wrapper
            TypeDef value = TypeHierarchy.unwrap(valueType);
            TypeDef unboxed = value instanceof TypeDef.Primitive ? value : unboxedOf(value);
            return unboxed == null || !widens((TypeDef.Primitive) unboxed, primitive);
        }
        // A value of a supertype of the declared one, as the erasure of a bounded type variable is - `Number` for
        // `N extends Number`: the verifier accepts it, source needs the cast
        if (paramType instanceof ClassTypeDef.JavaClass paramClass
            && valueType instanceof ClassTypeDef.JavaClass valueClass) {
            return !paramClass.type().isAssignableFrom(valueClass.type());
        }
        if (TypeHierarchy.unwrap(paramType) instanceof ClassTypeDef param && TypeHierarchy.unwrap(valueType) instanceof ClassTypeDef value
            && !TypeDef.OBJECT.equals(param) && !TypeDef.THIS.equals(value) && !TypeDef.SUPER.equals(value)
            && !TypeDef.THIS.equals(param) && !TypeDef.SUPER.equals(param)) {
            // Types that are not both loaded: a generated class, or one known by name or to the compiler
            if (param.getName().equals(value.getName())) {
                return false;
            }
            Class<?> loadedParam = loaded(param, context.scope().typeLookup());
            Class<?> loadedValue = loaded(value, context.scope().typeLookup());
            if (loadedParam != null && loadedValue != null) {
                return !loadedParam.isAssignableFrom(loadedValue);
            }
            return !TypeHierarchy.inherits(value, param.getName(), context.elementLookup());
        }
        // A value of a variable, or an array of another component, where an override narrowed the parameter
        if (valueType instanceof TypeDef.TypeVariable) {
            return paramType instanceof ClassTypeDef.JavaClass && !paramType.equals(TypeDef.OBJECT);
        }
        return paramType instanceof TypeDef.Array paramArray && valueType instanceof TypeDef.Array valueArray
            && paramArray.dimensions() == valueArray.dimensions()
            && !paramArray.componentType().equals(valueArray.componentType())
            && requiresImplicitInvocationCast(paramArray.componentType(), valueArray.componentType());
    }

    /**
     * The signature the invoked method declares, looked up with the context of the file being written.
     *
     * @param owner          The type declaring the method, or {@code null}
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model
     * @return The signature, or {@code null} where the method cannot be resolved
     */
    @Nullable
    InvokedSignature declaredSignature(@Nullable ClassTypeDef owner,
                                              String methodName,
                                              List<TypeDef> parameterTypes) {
        // Read from the method itself, not from the bridge a class declares with the erasure of its signature
        return JavaSignatures.resolve(owner, methodName, parameterTypes, context.scope());
    }

    /**
     * Whether a value has to be passed through the raw type, which only an unchecked conversion accepts: the
     * declared type is parameterized and the value does not fit it - as
     * {@code List<BeanRegistration<Interceptor>>} does not fit {@code List<BeanRegistration<Interceptor<?, ?>>>}.
     *
     * @param declaredType The type the method declares, or {@code null} where it is unknown
     * @param valueType    The type of the value
     * @return true if the value is cast to the raw type
     */
    boolean requiresRawCast(@Nullable TypeDef declaredType, TypeDef valueType) {
        if (declaredType instanceof TypeDef.Array declared && valueType instanceof TypeDef.Array value
            && declared.dimensions() == value.dimensions()) {
            // An array of one parameterization is no array of another: the verifier takes both as the raw array
            return requiresRawCast(declared.componentType(), value.componentType());
        }
        return declaredType instanceof ClassTypeDef.Parameterized declared
            && valueType instanceof ClassTypeDef.Parameterized
            && !accepts(declared, valueType);
    }

    /**
     * Whether a value of a wildcard parameterization is passed where a variable of the invoked method with a bound
     * stands: javac infers no variable of the bound from the capture - {@code Enum.valueOf} of a {@code Class<?>}.
     *
     * @param declaredType    The type the invoked method declares the parameter with
     * @param valueType       The type of the value in the source
     * @param methodVariables The bounds of the variables the invoked method declares, by their names
     * @return true if the value is passed raw
     */
    static boolean passesWildcardForBoundedVariable(TypeDef declaredType, TypeDef valueType, Map<String, List<TypeDef>> methodVariables) {
        if (!(TypeHierarchy.unwrap(declaredType) instanceof ClassTypeDef.Parameterized declared)
            || !(TypeHierarchy.unwrap(valueType) instanceof ClassTypeDef.Parameterized value)
            || !declared.rawType().getName().equals(value.rawType().getName())
            || declared.typeArguments().size() != value.typeArguments().size()) {
            return false;
        }
        for (int i = 0; i < declared.typeArguments().size(); i++) {
            if (TypeHierarchy.unwrap(declared.typeArguments().get(i)) instanceof TypeDef.TypeVariable variable
                && TypeHierarchy.unwrap(value.typeArguments().get(i)) instanceof TypeDef.Wildcard
                && methodVariables.getOrDefault(variable.name(), List.of()).stream().anyMatch(bound -> !TypeDef.OBJECT.equals(bound))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a value only converts to a type through the raw type, where the variables the type names are fixed:
     * those of a return type, or of the class of a generated method as the receiver sees it. `List<T>` does not
     * accept a `List<String>`.
     */
    boolean requiresRawConversion(TypeDef targetType, TypeDef valueType, Set<String> inferred) {
        Map<String, TypeDef.TypeVariable> variables = new HashMap<>();
        collectVariables(targetType, variables);
        collectVariables(valueType, variables);
        Map<String, TypeDef> fixed = new HashMap<>();
        variables.keySet().stream().filter(name -> !inferred.contains(name))
            .forEach(name -> fixed.put(name, ClassTypeDef.of("fixed variable " + name)));
        return requiresRawCast(TypeHierarchy.substituted(targetType, fixed), TypeHierarchy.substituted(valueType, fixed));
    }

    /**
     * Whether a cast to a parameterized type cannot convert a value, which it then does as raw: a variable the type
     * names can be any type within its bounds - `List<T>` casts a `List<String>`, unless `T extends Number`.
     */
    boolean requiresRawCastTo(TypeDef castType,
                                     TypeDef valueType,
                                     @Nullable ObjectDef objectDef,
                                     @Nullable MethodDef methodDef) {
        Map<String, TypeDef.TypeVariable> variables = new HashMap<>();
        collectVariables(castType, variables);
        Map<String, List<TypeDef>> bounds = new HashMap<>();
        variables.forEach((name, variable) -> bounds.put(name, OverrideResolver.upperBounds(variable, objectDef, methodDef)));
        Map<String, TypeDef> asWildcards = new HashMap<>();
        bounds.forEach((name, variableBounds) -> asWildcards.put(name,
            variableBounds.isEmpty() ? TypeDef.wildcard() : TypeDef.wildcardSubtypeOf(variableBounds.get(0))));
        if (requiresRawCast(TypeHierarchy.substituted(castType, asWildcards), valueType)) {
            return true;
        }
        // Every bound of an intersection bounds the variable: `T extends Serializable & CharSequence` is no Integer
        for (Map.Entry<String, List<TypeDef>> entry : bounds.entrySet()) {
            for (TypeDef bound : entry.getValue()) {
                Map<String, TypeDef> other = new HashMap<>(asWildcards);
                other.put(entry.getKey(), TypeDef.wildcardSubtypeOf(bound));
                if (requiresRawCast(TypeHierarchy.substituted(castType, other), valueType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The raw bound a value is converted to before it is cast to a variable: a bound of the variable that does not
     * accept the value - `List<String>`, a variable of that bound, or a class inheriting it, cast to a
     * `T extends List<Object>`.
     */
    @Nullable
    TypeDef rawBoundConversion(TypeDef variable,
                                      TypeDef valueType,
                                      @Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef) {
        List<TypeDef> values = TypeHierarchy.unwrap(valueType) instanceof TypeDef.TypeVariable
            ? OverrideResolver.upperBounds(valueType, objectDef, methodDef)
            : List.of(TypeHierarchy.unwrap(valueType));
        for (TypeDef bound : OverrideResolver.upperBounds(variable, objectDef, methodDef)) {
            if (!(bound instanceof ClassTypeDef.Parameterized parameterized)) {
                continue;
            }
            for (TypeDef value : values) {
                // A class inheriting the bound is of the type arguments it inherits it with
                TypeDef inherited = OverrideResolver.inheritedAs(value, parameterized, context.scope());
                if (inherited != null && requiresRawConversion(parameterized, inherited, Set.of())) {
                    return parameterized.rawType();
                }
            }
        }
        return null;
    }

    /**
     * Whether a value satisfies a bound: a class it is assignable to, and a parameterization it converts to - a value
     * of a variable through one of its own bounds.
     */
    private boolean satisfies(TypeDef bound, TypeDef valueType, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        if (bound instanceof TypeDef.TypeVariable variable) {
            // A variable the receiver fixes is satisfied by a value of it, or of a variable bounded by it
            return variable.equals(TypeHierarchy.unwrap(valueType))
                || TypeHierarchy.unwrap(valueType) instanceof TypeDef.TypeVariable value
                && value.bounds().stream().anyMatch(own -> own.equals(variable));
        }
        List<TypeDef> values = TypeHierarchy.unwrap(valueType) instanceof TypeDef.TypeVariable
            ? OverrideResolver.upperBounds(valueType, objectDef, methodDef) : List.of(TypeHierarchy.unwrap(valueType));
        if (values.isEmpty()) {
            return TypeDef.OBJECT.equals(bound);
        }
        return values.stream().anyMatch(value -> {
            if (bound instanceof ClassTypeDef.Parameterized parameterized) {
                TypeDef inherited = OverrideResolver.inheritedAs(value, parameterized, context.scope());
                return inherited != null && !requiresRawConversion(parameterized, inherited, Set.of());
            }
            if (loaded(value, context.scope().typeLookup()) == null && value instanceof ClassTypeDef classType && bound instanceof ClassTypeDef boundClass) {
                // A generated class is of the supertypes its model declares
                return TypeHierarchy.inherits(classType, boundClass.getName(), context.elementLookup());
            }
            return isAssignable(bound, value);
        });
    }

    /**
     * The casts a value passed to a parameter is written with, the outer first: to a class variable bounded by a
     * parameterization the value does not convert to, through the raw bound - `(T) (List) values`.
     *
     * @param paramType    The parameter type
     * @param valueType    The type of the value
     * @param declaredType The type the invoked method declares, or {@code null} where it is not known
     * @param generated    Whether the method is generated, whose class variables the receiver fixes
     * @param inferred     The variables of the invoked method, which are inferred from the value
     * @param objectDef    The definition being written
     * @param methodDef    The method being written
     * @return The casts, empty where the value is passed as is
     */
    List<List<TypeDef>> argumentCasts(TypeDef paramType,
                                             TypeDef valueType,
                                             TypeDef sourceType,
                                             @Nullable TypeDef declaredType,
                                             boolean generated,
                                             List<TypeDef.TypeVariable> inferred,
                                             Map<String, TypeDef> receiverArguments,
                                             @Nullable ObjectDef objectDef,
                                             @Nullable MethodDef methodDef) {
        valueType = primitiveOfSource(paramType, valueType, sourceType);
        CalleeBounds calleeBounds = new CalleeBounds(inferred, receiverArguments, true);
        boolean callee = calleeBounds.names(paramType);
        if (generated && !callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable) {
            if (valueType instanceof TypeDef.Primitive primitive) {
                // A primitive is boxed before it is cast to a variable, through a bound the box does not convert to
                TypeDef bound = rawBoundConversion(paramType, primitive.wrapperType(), objectDef, methodDef);
                return bound == null ? List.of(List.of(paramType), List.of(primitive.wrapperType()))
                    : List.of(List.of(paramType), List.of(bound), List.of(primitive.wrapperType()));
            }
            TypeDef bound = rawBoundConversion(paramType, sourceType, objectDef, methodDef);
            if (bound != null) {
                return List.of(List.of(paramType), List.of(bound));
            }
        }
        if (callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.Array paramArray
            && TypeHierarchy.unwrap(paramArray.componentType()) instanceof TypeDef.TypeVariable component
            && TypeHierarchy.unwrap(sourceType) instanceof TypeDef.Array sourceArray
            && sourceArray.dimensions() == paramArray.dimensions()) {
            // An array of a variable of the invoked method: its component has to satisfy the variable's bounds
            List<TypeDef> bounds = calleeBounds.of(component);
            if (bounds.stream().anyMatch(bound -> !satisfies(bound, sourceArray.componentType(), objectDef, methodDef))) {
                return List.of(List.of(TypeDef.array(TypeOperations.raw(bounds.get(0)), paramArray.dimensions())));
            }
            return List.of();
        }
        if (callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable && !TypeDef.OBJECT.equals(sourceType)) {
            // A value inferred as a variable of the invoked method has to satisfy every bound, as the source types it
            // - a primitive boxed
            TypeDef boxed = sourceType instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : sourceType;
            List<TypeDef> bounds = calleeBounds.of(paramType);
            if (bounds.stream().anyMatch(bound -> !satisfies(bound, boxed, objectDef, methodDef))) {
                List<TypeDef> raw = bounds.stream().map(TypeOperations::raw).toList();
                return boxed == sourceType ? List.of(raw) : List.of(raw, List.of(boxed));
            }
            // A value an override narrowed keeps the type the model infers the variable from
            return sourceType.equals(valueType) ? List.of() : List.of(bounds);
        }
        if (requiresImplicitInvocationCast(paramType, valueType)) {
            TypeDef inherited = TypeHierarchy.unwrap(paramType) instanceof ClassTypeDef.Parameterized parameterized
                ? OverrideResolver.inheritedAs(sourceType, parameterized, context.scope()) : null;
            if (TypeHierarchy.unwrap(paramType) instanceof ClassTypeDef.Parameterized parameterized && inherited != null
                && requiresRawConversion(parameterized, inherited, Set.of())) {
                // A value an override narrowed to another parameterization is cast raw
                return List.of(List.of(parameterized.rawType()));
            }
            // A variable the invoked method declares names the one of the class where it is called: its bounds,
            // which the value has to satisfy together, are cast to
            return List.of(callee ? calleeBounds.of(paramType) : List.of(paramType));
        }
        if (generated && !callee && requiresVariableCast(paramType, valueType)) {
            return List.of(List.of(paramType));
        }
        Set<String> inferredNames = inferred.stream().map(TypeDef.TypeVariable::name).collect(Collectors.toSet());
        // The receiver's type arguments bind the variables of its class the declared type names: `Collection<? extends
        // E>` of a `List<String>` takes no `List<Object>`
        if (declaredType != null && (generated ? requiresRawConversion(declaredType, valueType, inferredNames)
            : requiresRawCast(TypeHierarchy.substituted(declaredType, receiverArguments), valueType))) {
            // Only an unchecked conversion accepts the value, which a cast to the declared raw type is - also where
            // the parameter type is annotated
            return List.of(List.of(TypeOperations.raw(paramType)));
        }
        return List.of();
    }

    /**
     * The bounds of the variable an array argument's component is inferred as, where the value does not satisfy all
     * of several: no single array type expresses them, so the value is converted by a generic helper whose variable
     * the invocation infers.
     *
     * @param paramType         The parameter type
     * @param sourceType        The type of the value in the source
     * @param inferred          The variables the invoked method declares
     * @param receiverArguments The type arguments the receiver binds the variables of the method's class with
     * @param objectDef         The definition being written
     * @param methodDef         The method being written
     * @return The bounds, or {@code null} where the value is converted otherwise
     */
    @Nullable
    List<TypeDef> intersectionArrayBounds(TypeDef paramType,
                                                 TypeDef sourceType,
                                                 List<TypeDef.TypeVariable> inferred,
                                                 Map<String, TypeDef> receiverArguments,
                                                 @Nullable ObjectDef objectDef,
                                                 @Nullable MethodDef methodDef) {
        if (!(TypeHierarchy.unwrap(paramType) instanceof TypeDef.Array array)
            || !(TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable component)) {
            return null;
        }
        CalleeBounds calleeBounds = new CalleeBounds(inferred, receiverArguments, true);
        if (!calleeBounds.names(component)) {
            return null;
        }
        List<TypeDef> bounds = calleeBounds.of(component);
        TypeDef sourceComponent = TypeHierarchy.unwrap(sourceType) instanceof TypeDef.Array sourceArray
            && sourceArray.dimensions() == array.dimensions() ? sourceArray.componentType() : null;
        if (bounds.size() < 2 || sourceComponent != null
            && bounds.stream().allMatch(bound -> satisfies(bound, sourceComponent, objectDef, methodDef))) {
            return null;
        }
        return bounds;
    }

    /**
     * The casts a returned value is written with, the outer first: to a variable bounded by a parameterization the
     * value, as the source types it, does not convert to, through the raw bound - `(U) (List) this.values`.
     *
     * @param returnType The return type
     * @param valueType  The type of the value in the model
     * @param sourceType The type of the value in the source
     * @param objectDef  The definition being written
     * @param methodDef  The method being written
     * @return The casts, empty where the value is returned as is
     */
    List<List<TypeDef>> returnCasts(TypeDef returnType,
                                          TypeDef valueType,
                                          TypeDef sourceType,
                                          @Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef) {
        if (TypeDef.VOID.equals(returnType)) {
            return List.of();
        }
        valueType = primitiveOfSource(returnType, valueType, sourceType);
        if (TypeHierarchy.unwrap(valueType) instanceof TypeDef.Array array && TypeHierarchy.unwrap(sourceType) instanceof TypeDef.Array created
            && array.dimensions() == created.dimensions() && TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable
            && !created.componentType().equals(array.componentType())) {
            // An array of a variable is created as one of the erasure, which is no array of the variable
            valueType = sourceType;
        }
        if (TypeHierarchy.unwrap(returnType) instanceof TypeDef.TypeVariable && valueType instanceof TypeDef.Primitive primitive) {
            // A primitive is boxed before it is cast to a variable, through a bound the box does not convert to
            TypeDef boxBound = rawBoundConversion(returnType, primitive.wrapperType(), objectDef, methodDef);
            return boxBound == null ? List.of(List.of(returnType), List.of(primitive.wrapperType()))
                : List.of(List.of(returnType), List.of(boxBound), List.of(primitive.wrapperType()));
        }
        TypeDef bound = rawBoundConversion(returnType, sourceType, objectDef, methodDef);
        if (bound != null) {
            return List.of(List.of(returnType), List.of(bound));
        }
        if (requiresImplicitReturnCast(returnType, valueType)) {
            // e.g. an interceptor chain proceeds to Object, which the verifier accepts for a reference return
            return List.of(List.of(returnType));
        }
        if (requiresRawConversion(returnType, valueType, Set.of())) {
            // Only an unchecked conversion returns it - `List<Object>` as the `List<String>` of an override
            return List.of(List.of(TypeOperations.raw(returnType)));
        }
        return List.of();
    }

    /**
     * Whether a value of one type can be passed where the other is declared, without an unchecked conversion: a
     * subtype is, with the type arguments the declared type sees it with.
     */
    private boolean accepts(TypeDef declaredType, TypeDef valueType) {
        if (declaredType.equals(valueType) || declaredType instanceof TypeDef.TypeVariable) {
            // A variable is inferred from the value, or bound by the caller - either way the value is what it is
            return true;
        }
        if (declaredType instanceof ClassTypeDef.Parameterized declared) {
            if (!(valueType instanceof ClassTypeDef.Parameterized value)) {
                // A raw value is an unchecked conversion without a cast
                return true;
            }
            ClassTypeDef.Parameterized asDeclared = value;
            if (!declared.rawType().getName().equals(value.rawType().getName())) {
                // `ArrayList<String>` is the `List<String>` a `List<? extends T>` accepts. A supertype that cannot be
                // resolved is taken to fit, rather than erasing the value
                asDeclared = asSupertype(value, declared.rawType().getName());
                if (asDeclared == null) {
                    return true;
                }
            }
            if (declared.typeArguments().size() != asDeclared.typeArguments().size()) {
                return false;
            }
            for (int i = 0; i < declared.typeArguments().size(); i++) {
                if (!acceptsArgument(declared.typeArguments().get(i), asDeclared.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return isAssignable(declaredType, valueType);
    }

    /**
     * Whether a type argument fits the declared one. Arguments are invariant - `List<Integer>` is not a
     * `List<Number>` - unless the declared one is a wildcard, whose bounds are compared with their own arguments.
     */
    private boolean acceptsArgument(TypeDef declaredArgument, TypeDef valueArgument) {
        if (declaredArgument.equals(valueArgument) || declaredArgument instanceof TypeDef.TypeVariable) {
            return true;
        }
        if (declaredArgument instanceof TypeDef.Wildcard wildcard) {
            if (valueArgument instanceof TypeDef.Wildcard value) {
                return containsWildcard(wildcard, value);
            }
            return wildcard.upperBounds().stream().allMatch(bound -> withinBound(bound, valueArgument))
                && wildcard.lowerBounds().stream().allMatch(bound -> withinBound(valueArgument, bound));
        }
        if (declaredArgument instanceof ClassTypeDef.Parameterized declared) {
            if (!(valueArgument instanceof ClassTypeDef.Parameterized value)
                || !declared.rawType().getName().equals(value.rawType().getName())
                || declared.typeArguments().size() != value.typeArguments().size()) {
                return false;
            }
            for (int i = 0; i < declared.typeArguments().size(); i++) {
                if (!acceptsArgument(declared.typeArguments().get(i), value.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return declaredArgument instanceof ClassTypeDef declared
            && valueArgument instanceof ClassTypeDef value
            && !(value instanceof ClassTypeDef.Parameterized)
            && declared.getName().equals(value.getName());
    }

    /**
     * Whether a declared wildcard contains a wildcard argument: the range of types the value stands for lies
     * within the declared one - `? extends String` within `? extends T` or `? extends CharSequence`.
     */
    private boolean containsWildcard(TypeDef.Wildcard declared, TypeDef.Wildcard value) {
        List<TypeDef> valueUpperBounds = value.upperBounds().isEmpty() ? List.of(TypeDef.OBJECT) : value.upperBounds();
        boolean upper = declared.upperBounds().stream().allMatch(bound ->
            valueUpperBounds.stream().anyMatch(valueBound -> withinBound(bound, valueBound)));
        // A lower bound needs one on the value that is its supertype: `? super Integer` within `? super Number`
        // does not hold, `? super Number` within `? super Integer` does
        boolean lower = declared.lowerBounds().isEmpty()
            || !value.lowerBounds().isEmpty() && declared.lowerBounds().stream().allMatch(bound ->
                value.lowerBounds().stream().anyMatch(valueBound -> withinBound(valueBound, bound)));
        return upper && lower;
    }

    /**
     * Whether a type lies within the upper bound of a wildcard: a subtype of it, where a parameterized bound
     * needs a parameterized type - a raw one is only an unchecked conversion.
     */
    private boolean withinBound(TypeDef bound, TypeDef type) {
        if (bound instanceof ClassTypeDef.Parameterized parameterizedBound
            && !(type instanceof ClassTypeDef.Parameterized)) {
            if (type instanceof TypeDef.TypeVariable) {
                return true;
            }
            if (!(type instanceof ClassTypeDef concrete)) {
                return false;
            }
            // A class that declares no type variables is not raw: `StringList extends ArrayList<String>` is the
            // `List<String>` a `? extends List<T>` bounds
            Class<?> loaded = loaded(concrete, context.scope().typeLookup());
            if (loaded == null) {
                return !isGenericToTheCompiler(concrete);
            }
            if (loaded.getTypeParameters().length > 0) {
                return false;
            }
            ClassTypeDef.Parameterized asBound = asSupertype(loaded, Map.of(), parameterizedBound.rawType().getName());
            return asBound != null && accepts(parameterizedBound, asBound);
        }
        return accepts(bound, type);
    }

    private boolean isGenericToTheCompiler(ClassTypeDef type) {
        VisitorContext visitorContext = context.visitorContext();
        return visitorContext != null && visitorContext.getClassElement(type.getName())
            .map(element -> !element.getDeclaredGenericPlaceholders().isEmpty())
            .orElse(false);
    }

    /**
     * The value type as one of its supertypes, with the type arguments it inherits that supertype with.
     *
     * @param value   The type
     * @param rawName The binary name of the supertype
     * @return The supertype, or {@code null} where it cannot be resolved
     */
    private ClassTypeDef.@Nullable Parameterized asSupertype(ClassTypeDef.Parameterized value, String rawName) {
        Class<?> valueClass = loaded(value, context.scope().typeLookup());
        if (valueClass == null) {
            // A generated type, which the model describes: `GeneratedList<String>` is the `ArrayList<String>` it extends
            ClassTypeDef found = TypeHierarchy.asSupertype(value, rawName, context.elementLookup());
            return found instanceof ClassTypeDef.Parameterized parameterized ? parameterized : null;
        }
        return asSupertype(valueClass, substitutionOf(valueClass, value), rawName);
    }

    private static ClassTypeDef.@Nullable Parameterized asSupertype(Class<?> type,
                                                                    Map<String, TypeDef> substitution,
                                                                    String rawName) {
        List<Type> superTypes = new ArrayList<>();
        if (type.getGenericSuperclass() != null) {
            superTypes.add(type.getGenericSuperclass());
        }
        superTypes.addAll(Arrays.asList(type.getGenericInterfaces()));
        for (Type superType : superTypes) {
            TypeDef converted = TypeHierarchy.substituted(TypeHierarchy.typeDefOf(superType), substitution);
            Class<?> raw = loaded(converted);
            if (raw == null) {
                continue;
            }
            if (raw.getName().equals(rawName)) {
                return converted instanceof ClassTypeDef.Parameterized parameterized ? parameterized : null;
            }
            ClassTypeDef.Parameterized found = asSupertype(raw,
                converted instanceof ClassTypeDef.Parameterized parameterized
                    ? substitutionOf(raw, parameterized) : Map.of(), rawName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
