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

import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class LambdaExpressionWriter extends AbstractStatementAwareExpressionWriter {

    public static final String EXCEPTION_VAR_NAME = "exception";
    public static final String THIS_VAR_NAME = "this";
    public static final String SUPER_VAR_NAME = "super";

    private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private static final String METAFACTORY_METHOD = "metafactory";
    private static final String METAFACTORY_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

    private final Lambda lambda;

    public LambdaExpressionWriter(Lambda lambda) {
        this.lambda = lambda;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        List<VariableDef> capturedVariables = captureVariables(lambda.implementation());
        MethodDef implementationMethodDef = createLambdaMethodDef(context, lambda, capturedVariables);
        context.lambdaMethods().add(implementationMethodDef);

        // The captured variables are the parameters to the called bootstrap method
        for (VariableDef variable : capturedVariables) {
            new VariableExpressionWriter(variable).write(generatorAdapter, context);
        }

        String descriptor = TypeUtils.getType(context.objectDef().getName()).getDescriptor();
        if (descriptor.endsWith(";")) {
            descriptor = descriptor.substring(0, descriptor.length() - 1);
        }
        if (descriptor.startsWith("L")) {
            descriptor = descriptor.substring(1);
        }
        Handle lambdaMethodHandle = new Handle(
            Opcodes.H_INVOKESTATIC,
            descriptor,
            implementationMethodDef.getName(),
            TypeUtils.getMethodDescriptor(context.objectDef(), implementationMethodDef),
            false
        );
        Handle bootstrapMethodHandle = new Handle(
            Opcodes.H_INVOKESTATIC,
            METAFACTORY_OWNER,
            METAFACTORY_METHOD,
            METAFACTORY_DESCRIPTOR,
            false
        );

        generatorAdapter.visitInvokeDynamicInsn(
            lambda.implementation().getName(),
            createDynamicInvocationDescriptor(capturedVariables, context),
            bootstrapMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), lambda.target())),
            lambdaMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), lambda.implementation()))
        );
        popValueIfNeeded(generatorAdapter, lambda.type());
    }

    private String createDynamicInvocationDescriptor(List<VariableDef> capturedVariables, MethodContext context) {
        StringBuilder dynamicDescriptor = new StringBuilder("(");
        for (VariableDef variable : capturedVariables) {
            dynamicDescriptor.append(TypeUtils.getType(variable.type(), context.objectDef()));
        }
        dynamicDescriptor.append(")");
        dynamicDescriptor.append(TypeUtils.getType(lambda.type()).getDescriptor());
        return dynamicDescriptor.toString();
    }

    private MethodDef createLambdaMethodDef(MethodContext context, Lambda lambda, List<VariableDef> capturedVariables) {
        MethodDef original = lambda.implementation();
        List<ParameterDef> parameters = new ArrayList<>();

        // The captured variables are parameters
        for (VariableDef variable : capturedVariables) {
            if (variable instanceof VariableDef.Local local) {
                parameters.add(ParameterDef.builder(local.name(), local.type()).build());
            } else if (variable instanceof VariableDef.MethodParameter parameter) {
                parameters.add(ParameterDef.builder(parameter.name(), parameter.type()).build());
            } else if (variable instanceof VariableDef.Field field) {
                parameters.add(ParameterDef.builder(field.name(), field.type()).build());
            } else if (variable instanceof VariableDef.This thisVar) {
                parameters.add(ParameterDef.builder(THIS_VAR_NAME, thisVar.type()).build());
            } else if (variable instanceof VariableDef.Super superVar) {
                parameters.add(ParameterDef.builder(SUPER_VAR_NAME, superVar.type()).build());
            } else if (variable instanceof VariableDef.ExceptionVar exception) {
                parameters.add(ParameterDef.builder(EXCEPTION_VAR_NAME, exception.type()).build());
            }
        }

        parameters.addAll(original.getParameters());
        return MethodDef.builder("lambda$" + context.methodDef().getName() + "$" +
                context.lambdaMethods().size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(parameters)
            .returns(original.getReturnType())
            .addStatements(original.getStatements())
            .build();
    }

    private List<VariableDef> captureVariables(MethodDef method) {
        Set<String> variables = new LinkedHashSet<>(
            method.getParameters().stream().map(v -> v.getName()).toList()
        );
        List<VariableDef> capturedVariables = new ArrayList<>();
        for (StatementDef statement : method.getStatements()) {
            captureVariables(statement, variables, capturedVariables);
        }
        return capturedVariables;
    }

    private Stream<StatementDef> flatten(StatementDef statementDef) {
        List<StatementDef> statements = statementDef.statements();
        if (statements.isEmpty() || statements.size() == 1) {
            return statements.stream();
        }
        return statements.stream()
            .flatMap(this::flatten);
    }

    private Stream<? extends ExpressionDef> flatten(ExpressionDef expressionDef) {
        List<? extends ExpressionDef> expressions = expressionDef.expressions();
        if (expressions.isEmpty() || expressions.size() == 1) {
            return expressions.stream();
        }
        return expressions.stream().flatMap(this::flatten);
    }

    private void captureVariables(StatementDef statement, Set<String> variables, List<VariableDef> capturedVariables) {
        flatten(statement)
            .flatMap(s -> statement.expressions().stream())
            .flatMap(this::flatten)
            .forEach(expressionDef -> captureVariables(expressionDef, variables, capturedVariables));
    }

    private void captureVariables(ExpressionDef expression, Set<String> variables, List<VariableDef> capturedVariables) {
        if (expression instanceof VariableDef variable) {
            if (variable instanceof VariableDef.Local local) {
                if (!variables.contains(local.name())) {
                    capturedVariables.add(local);
                    variables.add(local.name());
                }
            } else if (variable instanceof VariableDef.MethodParameter parameter) {
                if (!variables.contains(parameter.name())) {
                    capturedVariables.add(parameter);
                    variables.add(parameter.name());
                }
            } else if (variable instanceof VariableDef.Field field) {
                captureVariables(field.instance(), variables, capturedVariables);
            } else if (variable instanceof VariableDef.This) {
                if (!variables.contains(THIS_VAR_NAME)) {
                    capturedVariables.add(variable);
                    variables.add(THIS_VAR_NAME);
                }
            } else if (variable instanceof VariableDef.Super) {
                if (!variables.contains(SUPER_VAR_NAME)) {
                    capturedVariables.add(variable);
                    variables.add(SUPER_VAR_NAME);
                }
            } else if (variable instanceof VariableDef.ExceptionVar) {
                if (!variables.contains(EXCEPTION_VAR_NAME)) {
                    capturedVariables.add(variable);
                    variables.add(EXCEPTION_VAR_NAME);
                }
            }
        } else {
            for (ExpressionDef operand : expression.expressions()) {
                captureVariables(operand, variables, capturedVariables);
            }
        }
    }

}
