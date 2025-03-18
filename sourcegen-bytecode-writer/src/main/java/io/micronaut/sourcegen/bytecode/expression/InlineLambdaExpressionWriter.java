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
import io.micronaut.sourcegen.model.ExpressionDef.InlineLambda;
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
import java.util.List;
import java.util.Set;

final class InlineLambdaExpressionWriter extends AbstractStatementAwareExpressionWriter {

    public static final String EXCEPTION_VAR_NAME = "exception";
    public static final String THIS_VAR_NAME = "this";
    public static final String SUPER_VAR_NAME = "super";

    private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private static final String METAFACTORY_METHOD = "metafactory";
    private static final String METAFACTORY_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

    private final ExpressionDef.InlineLambda lambda;

    public InlineLambdaExpressionWriter(ExpressionDef.InlineLambda lambda) {
        this.lambda = lambda;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        List<VariableDef> capturedVariables = captureVariables(lambda.method());
        MethodDef implementationMethodDef = createLambdaMethodDef(context, lambda, capturedVariables);
        context.lambdaMethods().add(implementationMethodDef);

        // The captured variables are the parameters to the called bootstrap method
        for (VariableDef variable: capturedVariables) {
            System.out.println("Writing variable: " + variable);
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
            lambda.method().getName(),
            createDynamicInvocationDescriptor(capturedVariables, context),
            bootstrapMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), lambda.overriddenMethod())),
            lambdaMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), lambda.method()))
        );
        popValueIfNeeded(generatorAdapter, lambda.type());
    }

    private String createDynamicInvocationDescriptor(List<VariableDef> capturedVariables, MethodContext context) {
        StringBuilder dynamicDescriptor = new StringBuilder("(");
        for (VariableDef variable: capturedVariables) {
            dynamicDescriptor.append(TypeUtils.getType(variable.type(), context.objectDef()));
        }
        dynamicDescriptor.append(")");
        dynamicDescriptor.append(TypeUtils.getType(lambda.type()).getDescriptor());
        return dynamicDescriptor.toString();
    }

    private MethodDef createLambdaMethodDef(MethodContext context, InlineLambda lambda, List<VariableDef> capturedVariables) {
        MethodDef original = lambda.method();
        List<ParameterDef> parameters = new ArrayList<>();

        // The captured variables are parameters
        for (VariableDef variable: capturedVariables) {
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
        Set<String> variables = new HashSet<>(
            method.getParameters().stream().map(v -> v.getName()).toList()
        );
        List<VariableDef> capturedVariables = new ArrayList<>();
        for (StatementDef statement: method.getStatements()) {
            captureVariables(statement, variables, capturedVariables);
        }
        return capturedVariables;
    }

    private void captureVariables(StatementDef statement, Set<String> variables, List<VariableDef> capturedVariables) {
        if (statement instanceof StatementDef.Multi multi) {
            for (StatementDef s: multi.statements()) {
                captureVariables(s, variables, capturedVariables);
            }
        } else if (statement instanceof StatementDef.Return returnStatement) {
            captureVariables(returnStatement.expression(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.Synchronized sync) {
            captureVariables(sync.monitor(), variables, capturedVariables);
            captureVariables(sync.statement(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.Throw throwStatement) {
            captureVariables(throwStatement.expression(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.Assign assign) {
            captureVariables(assign.variable(), variables, capturedVariables);
            captureVariables(assign.expression(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.DefineAndAssign assign) {
            variables.add(assign.variable().name());
            captureVariables(assign.expression(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.While w) {
            captureVariables(w.expression(), variables, capturedVariables);
            captureVariables(w.statement(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.If ifStatement) {
            captureVariables(ifStatement.condition(), variables, capturedVariables);
            captureVariables(ifStatement.statement(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.Try tryStatement) {
            captureVariables(tryStatement.statement(), variables, capturedVariables);
            captureVariables(tryStatement.finallyStatement(), variables, capturedVariables);
            for (StatementDef.Try.Catch cat: tryStatement.catches()) {
                captureVariables(cat.statement(), variables, capturedVariables);
            }
        } else if (statement instanceof StatementDef.IfElse ifElse) {
            captureVariables(ifElse.condition(), variables, capturedVariables);
            captureVariables(ifElse.statement(), variables, capturedVariables);
            captureVariables(ifElse.elseStatement(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.PutField putField) {
            capturedVariables.add(putField.field());
            variables.add(putField.field().name());
            captureVariables(putField.expression(), variables, capturedVariables);
        } else if (statement instanceof StatementDef.PutStaticField putStaticField) {
            captureVariables(putStaticField.expression(), variables, capturedVariables);
        } else {
            throw new IllegalStateException("Unsupported statement type in lambda: " + statement.getClass().getName());
        }
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
            for (ExpressionDef operand: expression.operands()) {
                captureVariables(operand, variables, capturedVariables);
            }
        }
    }

}
