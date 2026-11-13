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
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class LambdaExpressionWriter extends AbstractStatementAwareExpressionWriter {

    public static final String EXCEPTION_VAR_NAME = "exception";
    public static final String THIS_VAR_NAME = "this";
    public static final String SUPER_VAR_NAME = "super";

    private final Lambda lambda;

    public LambdaExpressionWriter(Lambda lambda) {
        this.lambda = lambda;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        List<VariableDef> capturedVariables = captureVariables(lambda.implementation());
        MethodDef implementationMethodDef = createLambdaMethodDef(context, lambda, capturedVariables);
        context.lambdaMethods().add(implementationMethodDef);
        var objectDef = Objects.requireNonNull(context.objectDef(), "Object definition is required for lambda generation");

        // The captured variables are the parameters to the called bootstrap method
        for (VariableDef variable : capturedVariables) {
            new VariableExpressionWriter(variable).write(generatorAdapter, context);
        }

        String descriptor = TypeUtils.getType(objectDef.getName()).getDescriptor();
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
            TypeUtils.getMethodDescriptor(objectDef, implementationMethodDef, context.enclosingScope()),
            false
        );
        generatorAdapter.visitInvokeDynamicInsn(
            lambda.implementation().getName(),
            createDynamicInvocationDescriptor(capturedVariables, context),
            MetafactoryHandle.BOOTSTRAP,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, lambda.target(), context.enclosingScope())),
            lambdaMethodHandle,
            // The instantiated signature names the enclosing method's variables
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, io.micronaut.sourcegen.bytecode.core.TypeUtils.withEnclosingVariables(lambda.implementation(), context.methodDef()), context.enclosingScope()))
        );
        popValueIfNeeded(generatorAdapter, lambda.type());
    }

    private String createDynamicInvocationDescriptor(List<VariableDef> capturedVariables, MethodContext context) {
        var objectDef = Objects.requireNonNull(context.objectDef(), "Object definition is required for lambda generation");
        StringBuilder dynamicDescriptor = new StringBuilder("(");
        for (VariableDef variable : capturedVariables) {
            // `super` is the receiver: the special call made on it is only verified for a value of the class making it
            dynamicDescriptor.append(TypeUtils.getScopedType(variable instanceof VariableDef.Super ? objectDef.asTypeDef() : variable.type(), context));
        }
        dynamicDescriptor.append(")");
        dynamicDescriptor.append(TypeUtils.getType(lambda.type(), objectDef, context.enclosingScope()).getDescriptor());
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
            } else if (variable instanceof VariableDef.Super) {
                parameters.add(ParameterDef.builder(SUPER_VAR_NAME, Objects.requireNonNull(context.objectDef()).asTypeDef()).build());
            } else if (variable instanceof VariableDef.ExceptionVar exception) {
                parameters.add(ParameterDef.builder(EXCEPTION_VAR_NAME, exception.type()).build());
            }
        }

        parameters.addAll(original.getParameters());
        MethodDef.MethodDefBuilder builder = MethodDef.builder("lambda$" + context.methodDef().getName() + "$" +
                context.lambdaMethods().size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(parameters)
            .returns(original.getReturnType())
            .addStatements(original.getStatements());
        // The body is in the scope of the enclosing method: a captured value of its variable erases to the bound
        original.getTypeVariables().forEach(builder::addTypeVariable);
        context.methodDef().getTypeVariables().stream()
            .filter(variable -> original.getTypeVariables().stream().noneMatch(own -> own.name().equals(variable.name())))
            .forEach(builder::addTypeVariable);
        return builder.build();
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

    private void captureVariables(StatementDef statement, Set<String> variables, List<VariableDef> capturedVariables) {
        statement.nestedExpressionsStream()
            .forEach(expressionDef -> captureVariables(expressionDef, variables, capturedVariables));
    }

    private void captureVariables(ExpressionDef expression, Set<String> variables, List<VariableDef> capturedVariables) {
        if (expression instanceof VariableDef variable) {
            if (variable instanceof VariableDef.Local local) {
                captureVariable(local, local.name(), variables, capturedVariables);
            } else if (variable instanceof VariableDef.MethodParameter parameter) {
                captureVariable(parameter, parameter.name(), variables, capturedVariables);
            } else if (variable instanceof VariableDef.Field field) {
                captureVariables(field.instance(), variables, capturedVariables);
            } else if (variable instanceof VariableDef.This) {
                captureVariable(variable, THIS_VAR_NAME, variables, capturedVariables);
            } else if (variable instanceof VariableDef.Super) {
                captureVariable(variable, SUPER_VAR_NAME, variables, capturedVariables);
            } else if (variable instanceof VariableDef.ExceptionVar) {
                captureVariable(variable, EXCEPTION_VAR_NAME, variables, capturedVariables);
            }
        } else {
            expression.nestedExpressionsStream()
                .forEach(expressionDef -> captureVariables(expressionDef, variables, capturedVariables));
        }
    }

    private static void captureVariable(VariableDef variable, String name, Set<String> variables, List<VariableDef> capturedVariables) {
        if (variables.add(name)) {
            capturedVariables.add(variable);
        }
    }

}
