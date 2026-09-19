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
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;
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
        // The implementation of a lambda written into an interface is a static method of that interface, which
        // is linked as an interface method
        Handle lambdaMethodHandle = new Handle(
            Opcodes.H_INVOKESTATIC,
            descriptor,
            implementationMethodDef.getName(),
            TypeUtils.getMethodDescriptor(objectDef, implementationMethodDef),
            objectDef instanceof InterfaceDef
        );
        generatorAdapter.visitInvokeDynamicInsn(
            lambda.implementation().getName(),
            createDynamicInvocationDescriptor(capturedVariables, context),
            MetafactoryHandle.BOOTSTRAP,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, lambda.target())),
            lambdaMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, lambda.implementation()))
        );
        popValueIfNeeded(generatorAdapter, lambda.type());
    }

    private String createDynamicInvocationDescriptor(List<VariableDef> capturedVariables, MethodContext context) {
        var objectDef = Objects.requireNonNull(context.objectDef(), "Object definition is required for lambda generation");
        StringBuilder dynamicDescriptor = new StringBuilder("(");
        for (VariableDef variable : capturedVariables) {
            // `super` is the receiver: the special call made on it is only verified for a value of the class making it
            dynamicDescriptor.append(TypeUtils.getType(variable instanceof VariableDef.Super ? objectDef.asTypeDef() : variable.type(), objectDef));
        }
        dynamicDescriptor.append(")");
        dynamicDescriptor.append(TypeUtils.getType(lambda.type(), objectDef).getDescriptor());
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
        // `<init>` and `<clinit>` are no valid member names: the placeholders javac uses stand in for them
        String owner = switch (context.methodDef().getName()) {
            case MethodDef.CONSTRUCTOR -> "new";
            case "<clinit>" -> "static";
            default -> context.methodDef().getName();
        };
        return MethodDef.builder("lambda$" + owner + "$" + context.lambdaMethods().size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(parameters)
            .returns(original.getReturnType())
            .addStatements(original.getStatements())
            .build();
    }

    /**
     * The variables a lambda body uses that it does not declare itself - through its parameters or its locals -
     * which the enclosing method has to pass to it. A nested lambda captures from this one whatever its own body
     * uses and does not declare, so its captures are this lambda's captures as well.
     */
    private static List<VariableDef> captureVariables(MethodDef method) {
        Set<String> variables = new LinkedHashSet<>(
            method.getParameters().stream().map(ParameterDef::getName).toList()
        );
        List<VariableDef> capturedVariables = new ArrayList<>();
        for (StatementDef statement : method.getStatements()) {
            captureVariables(statement, variables, capturedVariables);
        }
        return capturedVariables;
    }

    private static void captureVariables(StatementDef statement, Set<String> variables, List<VariableDef> capturedVariables) {
        switch (statement) {
            case StatementDef.Multi multi -> multi.statements().forEach(nested -> captureVariables(nested, variables, capturedVariables));
            case StatementDef.DefineAndAssign define -> {
                // A local the body defines is its own, not one to capture
                captureVariables(define.expression(), variables, capturedVariables);
                variables.add(define.variable().name());
            }
            case StatementDef.If anIf -> {
                captureVariables(anIf.condition(), variables, capturedVariables);
                captureVariables(anIf.statement(), variables, capturedVariables);
            }
            case StatementDef.IfElse ifElse -> {
                captureVariables(ifElse.condition(), variables, capturedVariables);
                captureVariables(ifElse.statement(), variables, capturedVariables);
                captureVariables(ifElse.elseStatement(), variables, capturedVariables);
            }
            case StatementDef.Switch aSwitch -> {
                captureVariables(aSwitch.expression(), variables, capturedVariables);
                aSwitch.cases().values().forEach(nested -> captureVariables(nested, variables, capturedVariables));
                if (aSwitch.defaultCase() != null) {
                    captureVariables(aSwitch.defaultCase(), variables, capturedVariables);
                }
            }
            case StatementDef.While aWhile -> {
                captureVariables(aWhile.expression(), variables, capturedVariables);
                captureVariables(aWhile.statement(), variables, capturedVariables);
            }
            case StatementDef.Try aTry -> {
                captureVariables(aTry.statement(), variables, capturedVariables);
                aTry.catches().forEach(aCatch -> captureVariables(aCatch.statement(), variables, capturedVariables));
                if (aTry.finallyStatement() != null) {
                    captureVariables(aTry.finallyStatement(), variables, capturedVariables);
                }
            }
            case StatementDef.Synchronized aSynchronized -> {
                captureVariables(aSynchronized.monitor(), variables, capturedVariables);
                captureVariables(aSynchronized.statement(), variables, capturedVariables);
            }
            default -> statement.nestedExpressionsStream()
                .forEach(expressionDef -> captureVariables(expressionDef, variables, capturedVariables));
        }
    }

    private static void captureVariables(ExpressionDef expression, Set<String> variables, List<VariableDef> capturedVariables) {
        if (expression instanceof VariableDef variable) {
            if (variable instanceof VariableDef.Field field) {
                captureVariables(field.instance(), variables, capturedVariables);
            } else {
                String name = captureName(variable);
                if (name != null) {
                    captureVariable(variable, name, variables, capturedVariables);
                }
            }
        } else if (expression instanceof Lambda nested) {
            // The nested lambda's own parameters and locals shadow this scope; what remains it captures from here
            Set<String> nestedVariables = new LinkedHashSet<>(variables);
            nested.implementation().getParameters().forEach(parameter -> nestedVariables.add(parameter.getName()));
            List<VariableDef> nestedCaptures = new ArrayList<>();
            for (StatementDef statement : nested.implementation().getStatements()) {
                captureVariables(statement, nestedVariables, nestedCaptures);
            }
            for (VariableDef captured : nestedCaptures) {
                captureVariable(captured, Objects.requireNonNull(captureName(captured)), variables, capturedVariables);
            }
        } else {
            expression.nestedExpressionsStream()
                .forEach(expressionDef -> captureVariables(expressionDef, variables, capturedVariables));
        }
    }

    /**
     * The name a variable is captured under, or {@code null} for one that is not captured by itself.
     */
    @Nullable
    private static String captureName(VariableDef variable) {
        return switch (variable) {
            case VariableDef.Local local -> local.name();
            case VariableDef.MethodParameter parameter -> parameter.name();
            case VariableDef.This _ -> THIS_VAR_NAME;
            case VariableDef.Super _ -> SUPER_VAR_NAME;
            case VariableDef.ExceptionVar _ -> EXCEPTION_VAR_NAME;
            default -> null;
        };
    }

    private static void captureVariable(VariableDef variable, String name, Set<String> variables, List<VariableDef> capturedVariables) {
        if (variables.add(name)) {
            capturedVariables.add(variable);
        }
    }

}
