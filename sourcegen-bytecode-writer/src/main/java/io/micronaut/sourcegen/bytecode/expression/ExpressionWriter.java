/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode.expression;

import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.core.InvocationPlan;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.List;

/**
 * The expression writer.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public sealed interface ExpressionWriter
        permits AbstractStatementAwareExpressionWriter,
        ArrayElementExpressionWriter,
        CastExpressionWriter,
        ConditionExpressionWriter,
        ConstantExpressionWriter,
        GetPropertyExpressionWriter,
        IfElseExpressionWriter,
        InstanceOfExpressionWriter,
        InvokeGetClassExpressionWriter,
        InvokeHashCodeMethodExpressionWriter,
        InvokeInstanceMethodExpressionWriter,
        InvokeStaticMethodExpressionWriter,
        MathBinaryExpressionWriter,
        MathUnaryExpressionWriter,
        NewArrayInitializedExpressionWriter,
        NewArrayOfSizeExpressionWriter,
        NewInstanceExpressionWriter,
        SwitchExpressionWriter,
        SwitchYieldCaseExpressionWriter,
        VariableExpressionWriter {

    /**
     * Create a writer from an expression.
     *
     * @param expressionDef The expression
     * @return the writer
     */
    static ExpressionWriter of(ExpressionDef expressionDef) {
        return switch (expressionDef) {
            case ExpressionDef.ArrayElement arrayElement ->
                new ArrayElementExpressionWriter(arrayElement);
            case ExpressionDef.InstanceOf instanceOf -> new InstanceOfExpressionWriter(instanceOf);
            case ExpressionDef.ConditionExpressionDef _ ->
                new ConditionExpressionWriter(expressionDef);
            case ExpressionDef.MathBinaryOperation math -> new MathBinaryExpressionWriter(math);
            case ExpressionDef.MathUnaryOperation math -> new MathUnaryExpressionWriter(math);
            case ExpressionDef.InvokeInstanceMethod invokeInstanceMethod ->
                new InvokeInstanceMethodExpressionWriter(invokeInstanceMethod);
            case ExpressionDef.NewInstance newInstance ->
                new NewInstanceExpressionWriter(newInstance);
            case ExpressionDef.NewArrayOfSize newArray ->
                new NewArrayOfSizeExpressionWriter(newArray);
            case ExpressionDef.NewArrayInitialized newArray ->
                new NewArrayInitializedExpressionWriter(newArray);
            case ExpressionDef.Cast castExpressionDef ->
                new CastExpressionWriter(castExpressionDef);
            case ExpressionDef.Constant constant -> new ConstantExpressionWriter(constant);
            case ExpressionDef.InvokeStaticMethod invokeStaticMethod ->
                new InvokeStaticMethodExpressionWriter(invokeStaticMethod);
            case ExpressionDef.GetPropertyValue getPropertyValue ->
                new GetPropertyExpressionWriter(getPropertyValue);
            case ExpressionDef.IfElse conditionIfElse ->
                new IfElseExpressionWriter(conditionIfElse);
            case ExpressionDef.Switch aSwitch -> new SwitchExpressionWriter(aSwitch);
            case ExpressionDef.SwitchYieldCase switchYieldCase ->
                new SwitchYieldCaseExpressionWriter(switchYieldCase);
            case VariableDef variableDef -> new VariableExpressionWriter(variableDef);
            case ExpressionDef.InvokeGetClassMethod invokeGetClassMethod ->
                new InvokeGetClassExpressionWriter(invokeGetClassMethod);
            case ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod ->
                new InvokeHashCodeMethodExpressionWriter(invokeHashCodeMethod);
            case Lambda lambda -> new LambdaExpressionWriter(lambda);
            case MethodReferenceExpression methodReference ->
                new MethodReferenceExpressionWriter(methodReference);
            case ExpressionDef.StringConcatenation concat ->
                new StringConcatenationExpressionWriter(concat);
            case null ->
                throw new UnsupportedOperationException("Unrecognized expression: " + expressionDef);
        };
    }

    static void writeExpression(GeneratorAdapter generatorAdapter,
                                MethodContext context,
                                ExpressionDef expressionDef) {
        ExpressionWriter.of(expressionDef).write(generatorAdapter, context);
    }

    static void writeExpressionCheckCast(GeneratorAdapter generatorAdapter,
                                         MethodContext context,
                                         ExpressionDef expressionDef,
                                         TypeDef expectedType) {
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            expressionDef = adjustConstant(expressionDef, expectedType, constant);
        }
        if (io.micronaut.sourcegen.bytecode.core.TypeUtils.packedInterfaceArray(expressionDef, expectedType)) {
            // A variable arity tail packed into an array of interfaces is passed as it is, as javac passes it
            writeExpression(generatorAdapter, context, expressionDef);
            return;
        }
        ExpressionWriter.of(new ExpressionDef.Cast(expectedType, expressionDef)).write(generatorAdapter, context);
    }

    /**
     * Writes the arguments of an invocation, converted to the parameters it plans, and emits it and the conversion
     * of its result. The receiver, if any, is written first by the caller as the plan says.
     *
     * @param generatorAdapter The adapter
     * @param context          The method context
     * @param plan             The invocation
     * @since 2.3
     */
    static void writeInvocation(GeneratorAdapter generatorAdapter, MethodContext context, InvocationPlan plan) {
        List<TypeDef> parameterTypes = plan.parameterTypes();
        for (int i = 0; i < plan.values().size(); i++) {
            writeExpressionCheckCast(generatorAdapter, context, plan.values().get(i), parameterTypes.get(i));
        }
        generatorAdapter.visitMethodInsn(plan.kind().opcode(), plan.ownerInternalName(), plan.name(), plan.descriptor(),
            plan.ownerInterface());
        InvocationPlan.ResultConversion result = plan.result();
        if (result != null) {
            CastExpressionWriter.cast(generatorAdapter, context, result.from(), result.to());
        }
    }

    private static ExpressionDef adjustConstant(ExpressionDef expressionDef, TypeDef expectedType, ExpressionDef.Constant constant) {
        if (expectedType.isPrimitive()) {
            if (!constant.type().isPrimitive() && constant.value() != null && ReflectionUtils.getPrimitiveType(constant.value().getClass()).isPrimitive()) {
                expressionDef = ExpressionDef.primitiveConstant(constant.value());
            }
        }
        return expressionDef;
    }

    /**
     * Write the expression.
     *
     * @param generatorAdapter The adapter
     * @param context          The method context
     */
    void write(GeneratorAdapter generatorAdapter, MethodContext context);

}
