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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.sourcegen.model.InterfaceDef;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an expression reads as in Java source: where it needs parentheses of its own, where a cast is implicit in
 * bytecode but required in source, and how an operator is spelled.
 *
 * @since 2.2
 */
@Internal
final class JavaExpressionRules {

    private JavaExpressionRules() {
    }

    /**
     * The type declaring an invoked method: the type being written for `this`, and the superclass of a class for
     * `super`, which the model names by placeholders.
     */
    @Nullable
    static ClassTypeDef ownerOf(@Nullable ObjectDef objectDef, TypeDef type) {
        TypeDef resolved = type;
        if (objectDef != null && (TypeDef.THIS.equals(type)
            || TypeDef.SUPER.equals(type) && !(objectDef instanceof InterfaceDef))) {
            resolved = objectDef.getContextualType(type);
        }
        return resolved instanceof ClassTypeDef classTypeDef && !TypeDef.SUPER.equals(classTypeDef)
            && !TypeDef.THIS.equals(classTypeDef) ? classTypeDef : null;
    }

    /**
     * The type a value has in the source: that of the parameter it names, which an override can have narrowed
     * from the type the model built the value with.
     */
    static TypeDef sourceTypeOf(ExpressionDef value,
                                        @Nullable MethodDef enclosingMethod,
                                        @Nullable ObjectDef objectDef) {
        if (value instanceof ExpressionDef.Cast cast) {
            // A cast to the type the value already has in the model is not written, and leaves the value its type
            return cast.type().equals(cast.expressionDef().type())
                ? sourceTypeOf(cast.expressionDef(), enclosingMethod, objectDef) : cast.type();
        }
        if (value instanceof ExpressionDef.InvokeInstanceMethod invocation && !invocation.method().isConstructor()) {
            // The result of a generated method that override resolution narrowed has the narrowed type
            OverrideResolver.OverriddenMethod emitted = OverrideResolver.emittedSignature(
                ownerOf(objectDef, invocation.instance().type()), objectDef, enclosingMethod, invocation.method(),
                JavaPoetNames.context(), false);
            if (emitted != null) {
                return emitted.returnType();
            }
        }
        if (value instanceof ExpressionDef.IfElse conditional) {
            return branchesType(List.of(conditional.ifExpression(), conditional.elseExpression()), value.type(),
                enclosingMethod, objectDef);
        }
        if (value instanceof ExpressionDef.Switch switchExpression) {
            List<ExpressionDef> results = new ArrayList<>(switchExpression.cases().values());
            if (switchExpression.defaultCase() != null) {
                results.add(switchExpression.defaultCase());
            }
            return branchesType(results, value.type(), enclosingMethod, objectDef);
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
        }
        return value.type();
    }

    /**
     * The type of a conditional or a switch expression: the type its results other than `null` have, where they
     * agree. Where they do not, Java types the expression by what they have in common, which need not be the type
     * of the model; one that differs from it is returned, which says that the source type differs.
     */
    private static TypeDef branchesType(List<ExpressionDef> results,
                                        TypeDef modelType,
                                        @Nullable MethodDef enclosingMethod,
                                        @Nullable ObjectDef objectDef) {
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

    static boolean isNullLiteral(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            expressionDef = castExpressionDef.expressionDef();
        }
        return expressionDef instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    static String getMathOp(ExpressionDef.MathBinaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case ADDITION -> " + ";
            case SUBTRACTION -> " - ";
            case MULTIPLICATION -> " * ";
            case DIVISION -> " / ";
            case MODULUS -> " % ";
            case BITWISE_AND -> " & ";
            case BITWISE_OR -> " | ";
            case BITWISE_XOR -> " ^ ";
            case BITWISE_LEFT_SHIFT -> " << ";
            case BITWISE_RIGHT_SHIFT -> " >> ";
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> " >>> ";
        };
    }

    static String getMathOp(ExpressionDef.MathUnaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case NEGATE -> "-";
        };
    }

    static boolean requiresMathParentheses(ExpressionDef.MathBinaryOperation parent,
                                                  ExpressionDef.MathBinaryOperation child,
                                                  boolean rightOperand) {
        int parentPrecedence = mathPrecedence(parent.opType());
        int childPrecedence = mathPrecedence(child.opType());
        return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence);
    }

    static int mathPrecedence(ExpressionDef.MathBinaryOperation.OpType opType) {
        return switch (opType) {
            case MULTIPLICATION, DIVISION, MODULUS -> 6;
            case ADDITION, SUBTRACTION -> 5;
            case BITWISE_LEFT_SHIFT, BITWISE_RIGHT_SHIFT, BITWISE_UNSIGNED_RIGHT_SHIFT -> 4;
            case BITWISE_AND -> 3;
            case BITWISE_XOR -> 2;
            case BITWISE_OR -> 1;
        };
    }

    static boolean requiresParentheses(ExpressionDef expressionDef) {
        expressionDef = unwrapCasts(expressionDef);
        if (expressionDef instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
            TypeDef type = invokeHashCodeMethod.instance().type();
            return !type.isPrimitive() && !type.isArray();
        }
        return !(expressionDef instanceof StatementDef
            || expressionDef instanceof VariableDef
            || expressionDef instanceof ExpressionDef.And
            || expressionDef instanceof ExpressionDef.Constant
            || expressionDef instanceof ExpressionDef.GetPropertyValue
            || expressionDef instanceof ExpressionDef.InvokeGetClassMethod
            || expressionDef instanceof ExpressionDef.ArrayElement
            || expressionDef instanceof ExpressionDef.NewArrayOfSize
            || expressionDef instanceof ExpressionDef.NewArrayInitialized
            || expressionDef instanceof ExpressionDef.NewInstance
            || expressionDef instanceof ExpressionDef.Switch);
    }

    static ExpressionDef unwrapCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    static ExpressionDef collapseNestedCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            if (cast.type().isPrimitive()) {
                TypeDef previousCastType = cast.expressionDef().type();
                if (!previousCastType.equals(TypeDef.OBJECT)) {
                    break;
                }
            }
            // Only keep the last cast
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    static boolean requiresCastOperandParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch
            || isNegativeNumericConstant(expressionDef);
    }

    static boolean isNegativeNumericConstant(ExpressionDef expressionDef) {
        // `(Object) -1` would parse as a subtraction of the variable `Object`
        return expressionDef instanceof ExpressionDef.Constant constant
            && constant.value() instanceof Number number
            && number.toString().startsWith("-");
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
    static boolean requiresImplicitReturnCast(TypeDef returnType, TypeDef valueType) {
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

    static boolean requiresImplicitInvocationCast(TypeDef paramType, TypeDef valueType) {
        if (valueType.equals(TypeDef.OBJECT)) {
            return !paramType.equals(TypeDef.OBJECT);
        }
        // A value of a supertype of the declared one, as the erasure of a bounded type variable is - `Number` for
        // `N extends Number`: the verifier accepts it, source needs the cast
        if (paramType instanceof ClassTypeDef.JavaClass paramClass
            && valueType instanceof ClassTypeDef.JavaClass valueClass) {
            return !paramClass.type().isAssignableFrom(valueClass.type());
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
    static InvokedSignature declaredSignature(@Nullable ClassTypeDef owner,
                                              String methodName,
                                              List<TypeDef> parameterTypes) {
        return InvokedSignature.resolve(owner, methodName, parameterTypes, JavaPoetNames.context());
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
    static boolean requiresRawCast(@Nullable TypeDef declaredType, TypeDef valueType) {
        return declaredType instanceof ClassTypeDef.Parameterized declared
            && valueType instanceof ClassTypeDef.Parameterized
            && !accepts(declared, valueType);
    }

    /**
     * Whether a value only converts to a type through the raw type, where the variables the type names are fixed:
     * those of a return type, or of the class of a generated method as the receiver sees it. `List<T>` does not
     * accept a `List<String>`.
     */
    static boolean requiresRawConversion(TypeDef targetType, TypeDef valueType, Set<String> inferred) {
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
    static boolean requiresRawCastTo(TypeDef castType,
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
     * Whether a value is cast to a variable it is not known to be: one of another variable, or of its bound.
     */
    static boolean requiresVariableCast(TypeDef targetType, TypeDef valueType) {
        return TypeHierarchy.unwrap(targetType) instanceof TypeDef.TypeVariable
            && !TypeHierarchy.unwrap(targetType).equals(TypeHierarchy.unwrap(valueType))
            && (valueType instanceof ClassTypeDef || valueType instanceof TypeDef.Array
            || valueType instanceof TypeDef.TypeVariable);
    }

    private static void collectVariables(TypeDef type, Map<String, TypeDef.TypeVariable> variables) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            variables.putIfAbsent(variable.name(), variable);
        } else if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            parameterized.typeArguments().forEach(argument -> collectVariables(argument, variables));
        } else if (unwrapped instanceof TypeDef.Array array) {
            collectVariables(array.componentType(), variables);
        } else if (unwrapped instanceof TypeDef.Wildcard wildcard) {
            wildcard.upperBounds().forEach(bound -> collectVariables(bound, variables));
            wildcard.lowerBounds().forEach(bound -> collectVariables(bound, variables));
        }
    }

    /**
     * The raw bound a value is converted to before it is cast to a variable: a bound of the variable that does not
     * accept the value - `List<String>`, a variable of that bound, or a class inheriting it, cast to a
     * `T extends List<Object>`.
     */
    @Nullable
    static TypeDef rawBoundConversion(TypeDef variable,
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
                TypeDef inherited = OverrideResolver.inheritedAs(value, parameterized, elementLookup());
                if (inherited != null && requiresRawConversion(parameterized, inherited, Set.of())) {
                    return parameterized.rawType();
                }
            }
        }
        return null;
    }

    @Nullable
    private static Function<String, @Nullable ClassElement> elementLookup() {
        VisitorContext context = JavaPoetNames.context();
        return context == null ? null : name -> context.getClassElement(name).orElse(null);
    }

    /**
     * The bounds of a variable the invoked method declares, in its own scope: its bounds name its variables.
     */
    private static List<TypeDef> calleeBounds(TypeDef.TypeVariable variable, List<TypeDef.TypeVariable> inferred) {
        List<TypeDef> result = new ArrayList<>();
        for (TypeDef bound : variable.bounds()) {
            TypeDef unwrapped = TypeHierarchy.unwrap(bound);
            TypeDef.TypeVariable declared = unwrapped instanceof TypeDef.TypeVariable named
                ? inferred.stream().filter(v -> v.name().equals(named.name())).findFirst().orElse(null) : null;
            if (declared != null) {
                result.addAll(calleeBounds(declared, inferred.stream().filter(v -> v != declared).toList()));
            } else if (unwrapped instanceof ClassTypeDef.Parameterized parameterized
                && namesCalleeVariable(parameterized, inferred)) {
                // `Comparable<T>` names the variable itself, which is out of scope where it is called
                result.add(parameterized.rawType());
            } else if (!TypeDef.OBJECT.equals(unwrapped)) {
                result.add(unwrapped);
            }
        }
        return result;
    }

    /**
     * A parameter type without the variables the invoked method declares, which name the variables of the class
     * where it is called: a variable is its bounds, an array one of their arrays, and a parameterization raw.
     */
    private static List<TypeDef> withoutCalleeVariables(TypeDef paramType, List<TypeDef.TypeVariable> inferred) {
        TypeDef unwrapped = TypeHierarchy.unwrap(paramType);
        if (unwrapped instanceof TypeDef.TypeVariable variable) {
            List<TypeDef> bounds = inferred.stream().filter(v -> v.name().equals(variable.name())).findFirst()
                .map(declared -> calleeBounds(declared, inferred)).orElse(List.of());
            return bounds.isEmpty() ? List.of(TypeDef.OBJECT) : bounds;
        }
        if (unwrapped instanceof TypeDef.Array array) {
            List<TypeDef> components = withoutCalleeVariables(array.componentType(), inferred);
            return List.of(TypeDef.array(components.get(0), array.dimensions()));
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return List.of(parameterized.rawType());
        }
        return List.of(unwrapped);
    }

    private static boolean namesCalleeVariable(TypeDef type, List<TypeDef.TypeVariable> inferred) {
        Map<String, TypeDef.TypeVariable> variables = new HashMap<>();
        collectVariables(type, variables);
        return inferred.stream().anyMatch(variable -> variables.containsKey(variable.name()));
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
    static List<List<TypeDef>> argumentCasts(TypeDef paramType,
                                             TypeDef valueType,
                                             TypeDef sourceType,
                                             @Nullable TypeDef declaredType,
                                             boolean generated,
                                             List<TypeDef.TypeVariable> inferred,
                                             @Nullable ObjectDef objectDef,
                                             @Nullable MethodDef methodDef) {
        boolean callee = namesCalleeVariable(paramType, inferred);
        if (generated && !callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable) {
            if (valueType instanceof TypeDef.Primitive primitive) {
                // A primitive is boxed before it is cast to a variable
                return List.of(List.of(paramType), List.of(primitive.wrapperType()));
            }
            TypeDef bound = rawBoundConversion(paramType, sourceType, objectDef, methodDef);
            if (bound != null) {
                return List.of(List.of(paramType), List.of(bound));
            }
        }
        if (callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable
            && !(valueType instanceof TypeDef.Primitive) && !TypeDef.OBJECT.equals(valueType)) {
            // A value inferred as a variable of the invoked method has to satisfy every bound
            List<TypeDef> bounds = withoutCalleeVariables(paramType, inferred);
            if (bounds.stream().anyMatch(bound -> !isAssignable(bound, valueType))) {
                return List.of(bounds);
            }
        }
        if (requiresImplicitInvocationCast(paramType, valueType)) {
            // A variable the invoked method declares names the one of the class where it is called: its bounds,
            // which the value has to satisfy together, are cast to
            return List.of(callee ? withoutCalleeVariables(paramType, inferred) : List.of(paramType));
        }
        if (generated && !callee && requiresVariableCast(paramType, valueType)) {
            return List.of(List.of(paramType));
        }
        Set<String> inferredNames = inferred.stream().map(TypeDef.TypeVariable::name).collect(Collectors.toSet());
        if (declaredType != null && (generated ? requiresRawConversion(declaredType, valueType, inferredNames)
            : requiresRawCast(declaredType, valueType))) {
            // Only an unchecked conversion accepts the value, which a cast to the declared raw type is
            return List.of(List.of(paramType instanceof ClassTypeDef.Parameterized parameterized
                ? parameterized.rawType() : paramType));
        }
        return List.of();
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
    static List<List<TypeDef>> returnCasts(TypeDef returnType,
                                          TypeDef valueType,
                                          TypeDef sourceType,
                                          @Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef) {
        if (TypeDef.VOID.equals(returnType)) {
            return List.of();
        }
        if (TypeHierarchy.unwrap(returnType) instanceof TypeDef.TypeVariable && valueType instanceof TypeDef.Primitive primitive) {
            // A primitive is boxed before it is cast to a variable
            return List.of(List.of(returnType), List.of(primitive.wrapperType()));
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
            return List.of(List.of(((ClassTypeDef.Parameterized) TypeHierarchy.unwrap(returnType)).rawType()));
        }
        return List.of();
    }

    /**
     * Whether a value of one type can be passed where the other is declared, without an unchecked conversion: a
     * subtype is, with the type arguments the declared type sees it with.
     */
    private static boolean accepts(TypeDef declaredType, TypeDef valueType) {
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
    private static boolean acceptsArgument(TypeDef declaredArgument, TypeDef valueArgument) {
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
    private static boolean containsWildcard(TypeDef.Wildcard declared, TypeDef.Wildcard value) {
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
    private static boolean withinBound(TypeDef bound, TypeDef type) {
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
            Class<?> loaded = loaded(concrete);
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

    private static boolean isGenericToTheCompiler(ClassTypeDef type) {
        VisitorContext context = JavaPoetNames.context();
        return context != null && context.getClassElement(type.getName())
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
    private static ClassTypeDef.@Nullable Parameterized asSupertype(ClassTypeDef.Parameterized value, String rawName) {
        Class<?> valueClass = loaded(value);
        return valueClass == null ? null : asSupertype(valueClass, substitutionOf(valueClass, value), rawName);
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

    private static Map<String, TypeDef> substitutionOf(Class<?> type, ClassTypeDef.Parameterized parameterized) {
        Map<String, TypeDef> substitution = new HashMap<>();
        java.lang.reflect.TypeVariable<?>[] variables = type.getTypeParameters();
        for (int i = 0; i < variables.length && i < parameterized.typeArguments().size(); i++) {
            substitution.put(variables[i].getName(), parameterized.typeArguments().get(i));
        }
        return substitution;
    }

    private static boolean isAssignable(TypeDef declaredType, TypeDef valueType) {
        if (declaredType.equals(valueType) || TypeDef.OBJECT.equals(declaredType)) {
            return true;
        }
        Class<?> declared = loaded(declaredType);
        Class<?> value = loaded(valueType);
        // Unresolvable types are taken as compatible: the value is written as it is, rather than erased
        return declared == null || value == null || declared.isAssignableFrom(value);
    }

    @Nullable
    private static Class<?> loaded(TypeDef typeDef) {
        TypeDef unwrapped = TypeHierarchy.unwrap(typeDef);
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return loaded(parameterized.rawType());
        }
        if (unwrapped instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            return ClassUtils.forName(classTypeDef.getName(), JavaExpressionRules.class.getClassLoader()).orElse(null);
        }
        return null;
    }

    static boolean requiresMethodCallTargetParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Cast
            || expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.MathUnaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch;
    }

    static boolean canEliminateCastToObject(ExpressionDef.Cast castExpressionDef,
                                                    ExpressionDef expressionDef,
                                                    CastContext castContext) {
        if (!castExpressionDef.type().equals(TypeDef.OBJECT)) {
            return false;
        }
        return switch (castContext) {
            case DEFAULT -> false;
            case OBJECT_REFERENCE -> !expressionDef.type().isPrimitive();
            case PRIMITIVE_EQUALITY -> true;
        };
    }

    static boolean arePrimitiveReferenceEqualityOperands(ExpressionDef left, ExpressionDef right) {
        return objectCastOperandType(left).isPrimitive() && objectCastOperandType(right).isPrimitive();
    }

    static TypeDef objectCastOperandType(ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.Cast cast && cast.type().equals(TypeDef.OBJECT)) {
            return collapseNestedCasts(cast.expressionDef()).type();
        }
        return expressionDef.type();
    }

    static boolean isOrCondition(ExpressionDef.ConditionExpressionDef expressionDef) {
        return switch (expressionDef) {
            case ExpressionDef.Or _ -> true;
            case ExpressionDef.IsTrue isTrue when unwrapCasts(isTrue.expression()) instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef ->
                isOrCondition(conditionExpressionDef);
            case null, default -> false;
        };
    }

    static String getOpType(ExpressionDef.ComparisonOperation comparisonOperation) {
        return switch (comparisonOperation.opType()) {
            case EQUAL_TO -> " == ";
            case NOT_EQUAL_TO -> " != ";
            case GREATER_THAN -> " > ";
            case LESS_THAN -> " < ";
            case GREATER_THAN_OR_EQUAL -> " >= ";
            case LESS_THAN_OR_EQUAL -> " <= ";
        };
    }

    /**
     * Where an expression is rendered, which decides whether a cast to {@code Object} carries meaning.
     */
    enum CastContext {
        DEFAULT,
        OBJECT_REFERENCE,
        PRIMITIVE_EQUALITY
    }
}
