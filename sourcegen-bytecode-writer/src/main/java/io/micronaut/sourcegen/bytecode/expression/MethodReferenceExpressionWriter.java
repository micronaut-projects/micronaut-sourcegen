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

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * Writes a method reference as an {@code invokedynamic} whose implementation method handle points
 * straight at the referenced method, so no synthetic method is generated for it.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Internal
final class MethodReferenceExpressionWriter extends AbstractStatementAwareExpressionWriter {

    private final MethodReferenceExpression methodReference;

    MethodReferenceExpressionWriter(MethodReferenceExpression methodReference) {
        this.methodReference = methodReference;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        ObjectDef objectDef = context.objectDef();
        // A bound reference captures its receiver, which is the sole argument of the call site
        ExpressionDef instance = methodReference.instance();
        if (instance != null) {
            ExpressionWriter.writeExpression(generatorAdapter, context, instance);
        }

        MethodDef referenced = methodReference.method();
        // Resolved first, so that a reference to a method of the object being generated is asked about
        // the object itself rather than about the this-type marker
        TypeDef owner = ObjectDef.getContextualType(objectDef, methodReference.owner());
        boolean ownerIsInterface = owner instanceof ClassTypeDef classTypeDef && classTypeDef.isInterface();
        var implMethodHandle = new Handle(
            handleTag(ownerIsInterface),
            TypeUtils.getType(owner, objectDef).getInternalName(),
            referenced.getName(),
            TypeUtils.getMethodDescriptor(objectDef, referenced),
            ownerIsInterface
        );

        generatorAdapter.visitInvokeDynamicInsn(
            methodReference.instantiated().getName(),
            callSiteDescriptor(instance, objectDef),
            MetafactoryHandle.BOOTSTRAP,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, methodReference.target())),
            implMethodHandle,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, methodReference.instantiated()))
        );
        popValueIfNeeded(generatorAdapter, methodReference.type());
    }

    private int handleTag(boolean ownerIsInterface) {
        if (methodReference.isStatic()) {
            return Opcodes.H_INVOKESTATIC;
        }
        if (methodReference.isConstructor()) {
            return Opcodes.H_NEWINVOKESPECIAL;
        }
        return ownerIsInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
    }

    private String callSiteDescriptor(@Nullable ExpressionDef instance, @Nullable ObjectDef objectDef) {
        StringBuilder descriptor = new StringBuilder("(");
        if (instance != null) {
            descriptor.append(TypeUtils.getType(instance.type(), objectDef).getDescriptor());
        }
        descriptor.append(")");
        descriptor.append(TypeUtils.getType(methodReference.type(), objectDef).getDescriptor());
        return descriptor.toString();
    }

}
