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
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;

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
        return false;
    }

    /**
     * The generic parameter types the invoked method declares, which the erased signature of the model does not
     * carry. Resolved by loading the type, and failing that through the compiler.
     *
     * @param owner      The type declaring the method, or {@code null}
     * @param methodName The method name
     * @param arity      The number of parameters
     * @return The declared types, or {@code null} where the method cannot be resolved
     */
    @Nullable
    static List<TypeDef> declaredParameterTypes(@Nullable ClassTypeDef owner,
                                                String methodName,
                                                List<TypeDef> parameterTypes) {
        if (owner == null) {
            return null;
        }
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        Class<?> loaded = owner instanceof ClassTypeDef.JavaClass javaClass ? javaClass.type()
            : ClassUtils.forName(owner.getName(), JavaExpressionRules.class.getClassLoader()).orElse(null);
        if (loaded != null) {
            Executable executable = findExecutable(loaded, methodName, erasures);
            return executable == null ? null
                : Arrays.stream(executable.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList();
        }
        VisitorContext context = JavaPoetNames.context();
        if (context == null) {
            return null;
        }
        return context.getClassElement(owner.getName())
            .flatMap(element -> element.getEnclosedElements(ElementQuery.ALL_METHODS.named(methodName)).stream()
                .filter(method -> erasures.equals(Arrays.stream(method.getParameters())
                    .map(parameter -> parameter.getType().getName()).toList()))
                .findFirst())
            .map(method -> Arrays.stream(method.getParameters())
                .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false))
                .toList())
            .orElse(null);
    }

    @Nullable
    private static Executable findExecutable(Class<?> type, String methodName, List<String> erasures) {
        if (MethodDef.CONSTRUCTOR.equals(methodName)) {
            return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> matches(constructor, erasures)).findFirst().orElse(null);
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Executable found = Arrays.stream(current.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName) && matches(method, erasures))
                .findFirst().orElse(null);
            if (found != null) {
                return found;
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                found = findExecutable(interfaceType, methodName, erasures);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Whether the erased parameter types name the same overload, so that a method with several of them is not
     * read from the wrong one.
     */
    private static boolean matches(Executable executable, List<String> erasures) {
        return Arrays.stream(executable.getParameterTypes()).map(Class::getName).toList().equals(erasures);
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
     * Whether a value of one type can be passed where the other is declared, without an unchecked conversion.
     */
    private static boolean accepts(TypeDef declaredType, TypeDef valueType) {
        if (declaredType.equals(valueType)) {
            return true;
        }
        if (declaredType instanceof TypeDef.TypeVariable) {
            // Inferred from the value, or bound by the caller - either way the value is what it is
            return true;
        }
        if (declaredType instanceof TypeDef.Wildcard wildcard) {
            return wildcard.upperBounds().stream().allMatch(bound -> isAssignable(bound, valueType))
                && wildcard.lowerBounds().stream().allMatch(bound -> isAssignable(valueType, bound));
        }
        if (declaredType instanceof ClassTypeDef.Parameterized declared) {
            if (!(valueType instanceof ClassTypeDef.Parameterized value)
                || !declared.rawType().getName().equals(value.rawType().getName())
                || declared.typeArguments().size() != value.typeArguments().size()) {
                return false;
            }
            for (int i = 0; i < declared.typeArguments().size(); i++) {
                if (!accepts(declared.typeArguments().get(i), value.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return isAssignable(declaredType, valueType);
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
