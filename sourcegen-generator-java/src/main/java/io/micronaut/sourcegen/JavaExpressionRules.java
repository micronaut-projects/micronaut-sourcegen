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
import io.micronaut.sourcegen.generator.CalleeBounds;
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.javapoet.Util;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.RecordDef;
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
import java.util.HashSet;
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

    // The methods being written, the innermost first: a lambda body is one written in another
    private static final ThreadLocal<java.util.Deque<MethodDef>> ENCLOSING_METHODS = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private JavaExpressionRules() {
    }

    static void enter(MethodDef method) {
        ENCLOSING_METHODS.get().addFirst(method);
    }

    static void exit() {
        ENCLOSING_METHODS.get().removeFirst();
        if (ENCLOSING_METHODS.get().isEmpty()) {
            ENCLOSING_METHODS.remove();
        }
    }

    /**
     * Whether a value is written through `Object` before it is cast, tested or compared: one an override narrowed to
     * a type the other does not convert to - a `String value`, where the model casts its `Object value` to `Integer`.
     */
    static boolean widensFirst(ExpressionDef value, TypeDef other, @Nullable MethodDef enclosingMethod, @Nullable ObjectDef objectDef) {
        TypeDef sourceType = sourceTypeOf(value, enclosingMethod, objectDef);
        if (sourceType.equals(value.type()) || isNullLiteral(value)) {
            return false;
        }
        Class<?> source = loaded(sourceType);
        Class<?> target = loaded(other);
        return source != null && target != null && !source.isPrimitive() && !target.isPrimitive()
            && !source.isAssignableFrom(target) && !target.isAssignableFrom(source)
            && (!source.isInterface() && !target.isInterface() || java.lang.reflect.Modifier.isFinal(source.getModifiers())
            || java.lang.reflect.Modifier.isFinal(target.getModifiers()));
    }

    /**
     * The type declaring an invoked method: the type being written for `this`, and the superclass of a class for
     * `super`, which the model names by placeholders.
     */
    @Nullable
    static ClassTypeDef ownerOf(@Nullable ObjectDef objectDef, TypeDef type) {
        return ownerOf(objectDef, null, type, null, List.of());
    }

    /**
     * The type declaring the method invoked on a receiver: the receiver's class, or, for a receiver of a type
     * variable, the bound declaring the method - the members of a variable are those of its bounds, which bind the
     * type arguments the parameters are converted to.
     *
     * @param objectDef      The definition being written, or {@code null}
     * @param methodDef      The method being written, or {@code null}
     * @param type           The type of the receiver
     * @param methodName     The invoked method, or {@code null}
     * @param parameterTypes Its parameter types in the model
     * @return The owner, or {@code null} where the receiver has no class
     */
    @Nullable
    static ClassTypeDef ownerOf(@Nullable ObjectDef objectDef,
                                @Nullable MethodDef methodDef,
                                TypeDef type,
                                @Nullable String methodName,
                                List<TypeDef> parameterTypes) {
        TypeDef resolved = type;
        if (objectDef != null && (TypeDef.THIS.equals(type)
            || TypeDef.SUPER.equals(type) && !(objectDef instanceof InterfaceDef))) {
            resolved = objectDef.getContextualType(type);
        }
        if (TypeHierarchy.unwrap(resolved) instanceof TypeDef.TypeVariable) {
            ClassTypeDef first = null;
            for (TypeDef bound : OverrideResolver.upperBounds(resolved, objectDef, methodDef)) {
                if (bound instanceof ClassTypeDef classBound) {
                    if (methodName != null && declaredSignature(classBound, methodName, parameterTypes) != null) {
                        return classBound;
                    }
                    first = first == null ? classBound : first;
                }
            }
            return first;
        }
        return resolved instanceof ClassTypeDef classTypeDef && !TypeDef.SUPER.equals(classTypeDef)
            && !TypeDef.THIS.equals(classTypeDef) ? classTypeDef : null;
    }

    /**
     * The signature a generated definition inherits from a compiled supertype, where it declares no method of the
     * name itself: the type arguments the definition extends the supertype with bind its parameters.
     *
     * @param definition     The generated definition
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method in the model
     * @return The signature, or {@code null} where no compiled supertype declares the method
     */
    @Nullable
    static InvokedSignature inheritedSignature(ObjectDef definition, String methodName, List<TypeDef> parameterTypes) {
        return inheritedSignature(definition, methodName, parameterTypes, new HashSet<>());
    }

    @Nullable
    private static InvokedSignature inheritedSignature(ObjectDef definition,
                                                       String methodName,
                                                       List<TypeDef> parameterTypes,
                                                       Set<String> visited) {
        if (!visited.add(definition.getName())) {
            return null;
        }
        for (TypeDef supertype : TypeHierarchy.superTypesOf(definition)) {
            if (!(TypeHierarchy.unwrap(supertype) instanceof ClassTypeDef classType)) {
                continue;
            }
            ObjectDef generated = OverrideResolver.definitionOf(classType, null);
            InvokedSignature signature = generated != null
                ? inheritedSignature(generated, methodName, parameterTypes, visited)
                : declaredSignature(classType, methodName, parameterTypes);
            if (signature != null) {
                return signature;
            }
        }
        return null;
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
            // A lambda captures the parameter of a method it is written in, as that method is written
            for (MethodDef outer : ENCLOSING_METHODS.get()) {
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
        if (castsNumericBranches(results, modelType)) {
            // Each result is cast to the type of the model, which the expression then has
            return modelType;
        }
        return types.stream().filter(type -> !type.equals(modelType)).findFirst().orElse(modelType);
    }

    /**
     * Whether the results of a conditional or a switch expression of a reference type are numeric to Java, which
     * would type the expression numerically - an `int` and an `Integer` unbox, an `Integer` and a `Long` widen to a
     * `long` - where the bytecode boxes a primitive and keeps each result as it is: each of them is cast to the type
     * of the model.
     *
     * @param results   The results
     * @param modelType The type of the model
     * @return true if the results are cast
     */
    /**
     * @param conditional A conditional or a switch expression
     * @return Its results: the branches, or the cases and the default
     */
    static List<ExpressionDef> resultsOf(ExpressionDef conditional) {
        if (conditional instanceof ExpressionDef.IfElse ifElse) {
            return List.of(ifElse.ifExpression(), ifElse.elseExpression());
        }
        if (conditional instanceof ExpressionDef.Switch switchExpression) {
            List<ExpressionDef> results = new ArrayList<>(switchExpression.cases().values());
            if (switchExpression.defaultCase() != null) {
                results.add(switchExpression.defaultCase());
            }
            return results;
        }
        return List.of();
    }

    /**
     * The type a value is cast to so that it selects a generic overload of the model over a more specific one -
     * `Stream.of((Object) values)` for `of(T)`, which `of(T...)` would take an array for: the erasure of the
     * variable's bounds, or an array of it.
     *
     * @param paramType The parameter type, a variable of the invoked method or an array of one
     * @param inferred  The variables the invoked method declares
     * @param objectDef The definition being written
     * @param methodDef The method being written
     * @return The erased type, or {@code null} where the parameter is no variable
     */
    @Nullable
    static TypeDef erasedPinningType(TypeDef paramType,
                                     List<TypeDef.TypeVariable> inferred,
                                     @Nullable ObjectDef objectDef,
                                     @Nullable MethodDef methodDef) {
        TypeDef param = TypeHierarchy.unwrap(paramType);
        if (param instanceof TypeDef.Array array && TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable) {
            TypeDef component = erasedPinningType(array.componentType(), inferred, objectDef, methodDef);
            return component == null ? null : TypeDef.array(component, array.dimensions());
        }
        if (!(param instanceof TypeDef.TypeVariable variable)) {
            return null;
        }
        TypeDef.TypeVariable declared = inferred.stream().filter(own -> own.name().equals(variable.name())).findFirst().orElse(variable);
        List<TypeDef> bounds = OverrideResolver.upperBounds(declared, objectDef, methodDef);
        return bounds.isEmpty() ? TypeDef.OBJECT : asRaw(bounds.get(0));
    }

    static boolean castsNumericBranches(List<? extends ExpressionDef> results, TypeDef modelType) {
        if (!(TypeHierarchy.unwrap(modelType) instanceof ClassTypeDef)) {
            return false;
        }
        List<TypeDef> types = results.stream()
            .filter(result -> !isNullLiteral(result) && !(result instanceof ExpressionDef.SwitchYieldCase))
            .map(result -> TypeHierarchy.unwrap(result.type()))
            .distinct()
            .toList();
        return types.size() > 1 && types.stream().allMatch(type -> type.isPrimitive() || unboxedOf(type) != null);
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

    /**
     * The operand of a cast without the casts it collapses: only the last of a chain is kept, except for a cast to a
     * primitive of a value that is no `Object`, and for one boxing a primitive under a cast to a reference -
     * `(String) (Object) 1` casts the box, `(boolean) (Boolean) true` is a constant.
     *
     * @param expressionDef The operand
     * @param castType      The type of the cast
     * @return The operand to cast
     */
    static ExpressionDef collapseNestedCasts(ExpressionDef expressionDef, TypeDef castType) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            if (cast.type().isPrimitive()) {
                TypeDef previousCastType = cast.expressionDef().type();
                if (!previousCastType.equals(TypeDef.OBJECT)) {
                    break;
                }
            } else if (cast.expressionDef().type().isPrimitive() && !castType.isPrimitive()) {
                break;
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
            Class<?> loadedParam = loaded(param);
            Class<?> loadedValue = loaded(value);
            if (loadedParam != null && loadedValue != null) {
                return !loadedParam.isAssignableFrom(loadedValue);
            }
            return !TypeHierarchy.inherits(value, param.getName(), elementLookup());
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

    static TypeDef.@Nullable Primitive unboxedOf(TypeDef type) {
        if (!(type instanceof ClassTypeDef classType)) {
            return null;
        }
        return switch (classType.getName()) {
            case "java.lang.Boolean" -> TypeDef.Primitive.BOOLEAN;
            case "java.lang.Byte" -> TypeDef.Primitive.BYTE;
            case "java.lang.Short" -> TypeDef.Primitive.SHORT;
            case "java.lang.Character" -> TypeDef.Primitive.CHAR;
            case "java.lang.Integer" -> TypeDef.Primitive.INT;
            case "java.lang.Long" -> TypeDef.Primitive.LONG;
            case "java.lang.Float" -> TypeDef.Primitive.FLOAT;
            case "java.lang.Double" -> TypeDef.Primitive.DOUBLE;
            default -> null;
        };
    }

    private static boolean widens(TypeDef.Primitive from, TypeDef.Primitive to) {
        List<String> order = List.of("byte", "short", "int", "long", "float", "double");
        if (from.equals(to)) {
            return true;
        }
        int target = order.indexOf(to.name());
        if (target == -1) {
            return false;
        }
        return "char".equals(from.name()) ? target >= 2 : order.indexOf(from.name()) != -1 && order.indexOf(from.name()) < target;
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
    static Function<String, @Nullable ClassElement> elementLookup() {
        VisitorContext context = JavaPoetNames.context();
        return context == null ? null : name -> context.getClassElement(name).orElse(null);
    }

    /**
     * Whether a value satisfies a bound: a class it is assignable to, and a parameterization it converts to - a value
     * of a variable through one of its own bounds.
     */
    private static boolean satisfies(TypeDef bound, TypeDef valueType, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
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
                TypeDef inherited = OverrideResolver.inheritedAs(value, parameterized, elementLookup());
                return inherited != null && !requiresRawConversion(parameterized, inherited, Set.of());
            }
            if (loaded(value) == null && value instanceof ClassTypeDef classType && bound instanceof ClassTypeDef boundClass) {
                // A generated class is of the supertypes its model declares
                return TypeHierarchy.inherits(classType, boundClass.getName(), elementLookup());
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
    static List<List<TypeDef>> argumentCasts(TypeDef paramType,
                                             TypeDef valueType,
                                             TypeDef sourceType,
                                             @Nullable TypeDef declaredType,
                                             boolean generated,
                                             List<TypeDef.TypeVariable> inferred,
                                             Map<String, TypeDef> receiverArguments,
                                             @Nullable ObjectDef objectDef,
                                             @Nullable MethodDef methodDef) {
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
                return List.of(List.of(TypeDef.array(asRaw(bounds.get(0)), paramArray.dimensions())));
            }
            return List.of();
        }
        if (callee && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable && !TypeDef.OBJECT.equals(sourceType)) {
            // A value inferred as a variable of the invoked method has to satisfy every bound, as the source types it
            // - a primitive boxed
            TypeDef boxed = sourceType instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : sourceType;
            List<TypeDef> bounds = calleeBounds.of(paramType);
            if (bounds.stream().anyMatch(bound -> !satisfies(bound, boxed, objectDef, methodDef))) {
                List<TypeDef> raw = bounds.stream().map(JavaExpressionRules::asRaw).toList();
                return boxed == sourceType ? List.of(raw) : List.of(raw, List.of(boxed));
            }
            // A value an override narrowed keeps the type the model infers the variable from
            return sourceType.equals(valueType) ? List.of() : List.of(bounds);
        }
        if (requiresImplicitInvocationCast(paramType, valueType)) {
            TypeDef inherited = TypeHierarchy.unwrap(paramType) instanceof ClassTypeDef.Parameterized parameterized
                ? OverrideResolver.inheritedAs(sourceType, parameterized, elementLookup()) : null;
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
        if (declaredType != null && (generated ? requiresRawConversion(declaredType, valueType, inferredNames)
            : requiresRawCast(declaredType, valueType))) {
            // Only an unchecked conversion accepts the value, which a cast to the declared raw type is - also where
            // the parameter type is annotated
            return List.of(List.of(asRaw(paramType)));
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
    static List<TypeDef> intersectionArrayBounds(TypeDef paramType,
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
        // The helper declares a variable of its own, which a bound naming the variable itself - `Comparable<N>` of an
        // `N extends Number & Comparable<N>` - is written with, where the raw bound infers no `N`
        return calleeBounds.ofNamingItself(component);
    }

    /**
     * Whether a value that is not an array, passed for a varargs parameter, is the array itself rather than one
     * element of it: the bytecode casts the value to the array type, which takes an {@code Object} - and any other
     * value that is not of the element type, a {@code Cloneable} say. A value of the element type, or converting to
     * it, is one element, as javac takes it.
     *
     * @param elementType The element type of the varargs parameter
     * @param sourceType  The type of the value in the source
     * @return true if the value is cast to the array type
     */
    static boolean isVarargsArray(TypeDef elementType, TypeDef sourceType) {
        TypeDef value = TypeHierarchy.unwrap(sourceType);
        if (value instanceof TypeDef.Primitive) {
            return false;
        }
        return TypeDef.OBJECT.equals(value) || requiresImplicitInvocationCast(elementType, sourceType);
    }

    /**
     * The lower bound a parameter of a variable takes, where the receiver binds the variable with a
     * {@code ? super X} wildcard: the {@code add} of a {@code List<? super Integer>} takes an {@code Integer}, which
     * the value is cast to. A parameter of any other binding is what
     * {@link io.micronaut.sourcegen.generator.OverloadRules#receiverBound} makes it.
     *
     * @param paramType         The parameter type as the receiver sees it
     * @param declaredType      The type the invoked method declares, or {@code null} where it is not known
     * @param inferred          The variables the invoked method declares
     * @param receiverArguments The type arguments the receiver binds the variables of its class with
     * @return The lower bound, or the parameter type
     */
    static TypeDef receiverLowerBound(TypeDef paramType,
                                      @Nullable TypeDef declaredType,
                                      List<TypeDef.TypeVariable> inferred,
                                      Map<String, TypeDef> receiverArguments) {
        TypeDef named = TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable ? paramType : declaredType;
        if (named == null || !(TypeHierarchy.unwrap(named) instanceof TypeDef.TypeVariable variable)
            || inferred.stream().anyMatch(own -> own.name().equals(variable.name()))) {
            return paramType;
        }
        TypeDef bound = receiverArguments.get(variable.name());
        return bound != null && TypeHierarchy.unwrap(bound) instanceof TypeDef.Wildcard wildcard
            && !wildcard.lowerBounds().isEmpty() ? wildcard.lowerBounds().get(0) : paramType;
    }

    /**
     * The cast a value stored or returned where a parameterized type is declared is written with, where the value
     * is the result of a generic method the model erases: javac infers the method's variable from the arguments
     * and the target type at once, which an {@code Object} argument fails - {@code Optional.ofNullable(p0)} returned
     * as an {@code Optional<String>}. The result is cast through the raw type, an unchecked conversion, as the
     * bytecode returns it.
     *
     * @param targetType The declared type
     * @param value      The value
     * @param objectDef  The definition being written
     * @param methodDef  The method being written
     * @return The cast, or none
     */
    static List<List<TypeDef>> inferredResultCasts(TypeDef targetType,
                                                  ExpressionDef value,
                                                  @Nullable ObjectDef objectDef,
                                                  @Nullable MethodDef methodDef) {
        if (!(TypeHierarchy.unwrap(targetType) instanceof ClassTypeDef.Parameterized parameterized)) {
            return List.of();
        }
        ClassTypeDef owner;
        MethodDef method;
        List<? extends ExpressionDef> values;
        switch (value) {
            case ExpressionDef.InvokeStaticMethod invocation -> {
                owner = invocation.classDef();
                method = invocation.method();
                values = invocation.values();
            }
            case ExpressionDef.InvokeInstanceMethod invocation when !invocation.method().isConstructor() -> {
                method = invocation.method();
                values = invocation.values();
                owner = ownerOf(objectDef, methodDef, invocation.instance().type(), method.getName(),
                    method.getParameters().stream().map(ParameterDef::getType).toList());
            }
            default -> {
                return List.of();
            }
        }
        TypeDef resultType = TypeHierarchy.unwrap(value.type());
        if (resultType instanceof ClassTypeDef.Parameterized || !(resultType instanceof ClassTypeDef result)
            || !result.getName().equals(parameterized.rawType().getName())) {
            return List.of();
        }
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        Set<String> resultVariables = InvokedSignature.inferredResultVariables(owner, method.getName(), parameterTypes, JavaPoetNames.context());
        InvokedSignature signature = resultVariables.isEmpty() ? null : declaredSignature(owner, method.getName(), parameterTypes);
        if (signature == null || signature.parameterTypes().size() != values.size()) {
            return List.of();
        }
        for (int i = 0; i < values.size(); i++) {
            Map<String, TypeDef.TypeVariable> named = new HashMap<>();
            collectVariables(signature.parameterTypes().get(i), named);
            if (named.keySet().stream().anyMatch(resultVariables::contains)
                && TypeDef.OBJECT.equals(sourceTypeOf(values.get(i), methodDef, objectDef))) {
                return List.of(List.of(parameterized.rawType()));
            }
        }
        return List.of();
    }

    private static TypeDef asRaw(TypeDef type) {
        return TypeHierarchy.unwrap(type) instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : type;
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
        return acceptsArgument(declaredArgument, valueArgument, false);
    }

    /**
     * @param nested Whether the argument is one of a type argument, which is invariant: a wildcard within it has to
     *               be the same wildcard - `List<? extends CharSequence>` as an argument takes no `List<String>`
     */
    private static boolean acceptsArgument(TypeDef declaredArgument, TypeDef valueArgument, boolean nested) {
        if (declaredArgument.equals(valueArgument) || declaredArgument instanceof TypeDef.TypeVariable) {
            return true;
        }
        if (declaredArgument instanceof TypeDef.Wildcard wildcard) {
            if (nested) {
                return false;
            }
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
                if (!acceptsArgument(declared.typeArguments().get(i), value.typeArguments().get(i), true)) {
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
            return collapseNestedCasts(cast.expressionDef(), cast.type()).type();
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

    static boolean isFunctional(ExpressionDef value) {
        return value instanceof ExpressionDef.Lambda || value instanceof io.micronaut.sourcegen.model.MethodReferenceExpression;
    }

    static boolean sameErasure(TypeDef type, TypeDef other) {
        return TypeHierarchy.erasedName(type).equals(TypeHierarchy.erasedName(other));
    }

    /**
     * The literal of a primitive value: a character with its escapes, the constants of the values no literal
     * writes - `NaN` and the infinities - and a `byte` or `short` cast, which Java only narrows a literal to where it
     * is assigned, not where it is passed.
     */
    static CodeBlock renderPrimitiveConstant(String primitiveName, Object value) {
        return switch (primitiveName) {
            case "long" -> CodeBlock.of(value + "l");
            case "float" -> renderFloatingPointConstant(Float.class, ((Number) value).doubleValue(), value + "f");
            case "double" -> renderFloatingPointConstant(Double.class, ((Number) value).doubleValue(), value + "d");
            case "char" -> CodeBlock.of("'$L'", Util.characterLiteralWithoutSingleQuotes(
                value instanceof Character c ? c : (char) ((Number) value).intValue()));
            case "byte", "short" -> CodeBlock.of("($L) $L", primitiveName, value);
            default -> CodeBlock.of("$L", value);
        };
    }

    static CodeBlock renderFloatingPointConstant(Class<?> wrapper, double value, String literal) {
        if (Double.isNaN(value)) {
            return CodeBlock.of("$T.NaN", wrapper);
        }
        if (Double.isInfinite(value)) {
            return CodeBlock.of("$T.$L", wrapper, value > 0 ? "POSITIVE_INFINITY" : "NEGATIVE_INFINITY");
        }
        return CodeBlock.of(literal);
    }

    static boolean isRawGeneric(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.Array array) {
            return isRawGeneric(array.componentType());
        }
        if (unwrapped instanceof ClassTypeDef classType && !(unwrapped instanceof ClassTypeDef.Parameterized)
            && OverrideResolver.definitionOf(classType, null) instanceof ObjectDef definition) {
            // A generated generic class named without its type arguments
            return definition instanceof ClassDef classDef && !classDef.getTypeVariables().isEmpty()
                || definition instanceof InterfaceDef interfaceDef && !interfaceDef.getTypeVariables().isEmpty()
                || definition instanceof RecordDef recordDef && !recordDef.getTypeVariables().isEmpty();
        }
        return unwrapped instanceof ClassTypeDef.JavaClass javaClass && javaClass.type().getTypeParameters().length > 0;
    }

    /**
     * The component an array is created with: Java creates no array of a parameterized type, `new List<String>[2]`.
     */
    static TypeDef creationComponent(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, TypeDef.Array array) {
        TypeDef component = TypeHierarchy.unwrap(array.componentType());
        if (component instanceof ClassTypeDef.Parameterized parameterized) {
            return parameterized.rawType();
        }
        if (component instanceof TypeDef.TypeVariable) {
            // Nor of a type variable: the array is created of the erasure of the variable and cast
            List<TypeDef> bounds = OverrideResolver.upperBounds(component, objectDef, methodDef);
            return bounds.isEmpty() ? TypeDef.OBJECT : creationComponent(objectDef, methodDef, TypeDef.array(bounds.get(0), 1));
        }
        return array.componentType();
    }

    /**
     * A reference operand of a structural comparison with a primitive, converted to the primitive as the bytecode
     * converts it: unboxed through {@link Number} where it is no wrapper.
     */
    static ExpressionDef asPrimitiveOperand(ExpressionDef operand, TypeDef otherType) {
        return !operand.type().isPrimitive() && otherType instanceof TypeDef.Primitive primitive && !isNullLiteral(operand)
            ? operand.cast(primitive) : operand;
    }
}
