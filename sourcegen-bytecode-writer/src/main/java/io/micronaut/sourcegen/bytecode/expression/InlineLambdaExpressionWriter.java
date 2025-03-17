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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import javax.lang.model.element.Modifier;

final class InlineLambdaExpressionWriter extends AbstractStatementAwareExpressionWriter {
    private final ExpressionDef.InlineLambda lambda;

    private final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private final String METAFACTORY_METHOD = "metafactory";
    private final String METAFACTORY_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

    public InlineLambdaExpressionWriter(ExpressionDef.InlineLambda lambda) {
        this.lambda = lambda;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        MethodDef implementationMethodDef = createLambdaMethodDef(context, lambda);
        context.lambdaMethods().add(implementationMethodDef);

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
        System.out.println("Type: " + lambda.type());
        generatorAdapter.visitInvokeDynamicInsn(
            lambda.method().getName(),
            "()" + TypeUtils.getType(lambda.type()).getDescriptor(), // TODO here would be arguments for stateful lambdas
            bootstrapMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), lambda.overriddenMethod())),
            lambdaMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(context.objectDef(), implementationMethodDef))
        );
        popValueIfNeeded(generatorAdapter, lambda.type());
    }

    private MethodDef createLambdaMethodDef(MethodContext context, InlineLambda lambda) {
        MethodDef original = lambda.method();
        return MethodDef.builder("lambda$" + context.methodDef().getName() + "$" +
            context.lambdaMethods().size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(original.getParameters())
            .returns(original.getReturnType())
            .addStatements(original.getStatements())
            .build();
    }

}
