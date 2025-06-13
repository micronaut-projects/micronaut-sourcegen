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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Iterator;
import java.util.Objects;

import static org.objectweb.asm.Opcodes.INVOKESPECIAL;

final class InvokeInstanceMethodExpressionWriter extends AbstractStatementAwareExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.InvokeInstanceMethod invokeInstanceMethod;

    public InvokeInstanceMethodExpressionWriter(ExpressionDef.InvokeInstanceMethod invokeInstanceMethod) {
        this.invokeInstanceMethod = invokeInstanceMethod;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        ExpressionDef instance = invokeInstanceMethod.instance();
        ExpressionWriter.writeExpression(generatorAdapter, context, instance);
        Iterator<ParameterDef> iterator = invokeInstanceMethod.method().getParameters().iterator();
        for (ExpressionDef parameter : invokeInstanceMethod.values()) {
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, parameter, iterator.next().getType());
        }
        TypeDef instanceType = instance.type();
        Type methodOwnerType = TypeUtils.getType(instanceType, context.objectDef());
        MethodDef methodDef = invokeInstanceMethod.method();
        Method method = new Method(methodDef.getName(), TypeUtils.getMethodDescriptor(context.objectDef(), methodDef));
        if (instanceType instanceof TypeDef.TypeVariable typeVariable) {
            instanceType = typeVariable.bounds().get(0);
        }
        if (instanceType instanceof ClassTypeDef classTypeDef) {
            if (instance instanceof VariableDef.Super aSuper) {
                ClassTypeDef superType = getSuperType(context, aSuper);
                methodOwnerType = TypeUtils.getType(superType, context.objectDef());
                generatorAdapter.visitMethodInsn(
                    INVOKESPECIAL,
                    methodOwnerType.getSort() == Type.ARRAY ? methodOwnerType.getDescriptor() : methodOwnerType.getInternalName(),
                    method.getName(),
                    method.getDescriptor(),
                    superType.isInterface() && invokeInstanceMethod.isDefault()
                );
            } else if (invokeInstanceMethod.method().isConstructor()) {
                generatorAdapter.invokeConstructor(methodOwnerType, method);
            } else if (classTypeDef.isInterface()) {
                generatorAdapter.invokeInterface(methodOwnerType, method);
            } else {
                generatorAdapter.invokeVirtual(methodOwnerType, method);
            }
        } else if (instanceType instanceof TypeDef.Array) {
            generatorAdapter.invokeVirtual(methodOwnerType, method);
        } else {
            throw new IllegalStateException("Unsupported instance type: " + instanceType + " in invoke expression " + invokeInstanceMethod);
        }
        popValueIfNeeded(generatorAdapter, invokeInstanceMethod.method().getReturnType());
    }

    private ClassTypeDef getSuperType(MethodContext context, VariableDef.Super aSuper) {
        ClassTypeDef superClass;
        if (aSuper.type() == TypeDef.SUPER) {
            if (context.objectDef() instanceof EnumDef) {
                superClass = ClassTypeDef.of(Enum.class);
            } else if (context.objectDef() instanceof ClassDef classDef) {
                superClass = Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT);
            } else {
                superClass = TypeDef.OBJECT;
            }
        } else {
            superClass = aSuper.type();
        }
        return superClass;
    }

}
