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
import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
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
        if (instance instanceof VariableDef.Super superInstance) {
            // `super::name` is the method of the superclass, which a handle on it would dispatch past: javac writes a
            // lambda that makes the special call, and so does this
            ExpressionWriter.writeExpression(generatorAdapter, context, asSuperCall(methodReference, superInstance));
            return;
        }
        ExpressionDef adapter = io.micronaut.sourcegen.bytecode.core.ReferenceAdapters.varargsAdapter(methodReference);
        if (adapter != null) {
            // The values of a variable arity call are packed into its array, which no handle does
            ExpressionWriter.writeExpression(generatorAdapter, context, adapter);
            return;
        }
        if (instance != null) {
            ExpressionWriter.writeExpression(generatorAdapter, context, instance);
            if (!(instance instanceof VariableDef.This)) {
                // A bound reference checks its receiver where it is created, as `target::apply` does in source
                generatorAdapter.dup();
                generatorAdapter.invokeStatic(Type.getType(java.util.Objects.class),
                    new org.objectweb.asm.commons.Method("requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                generatorAdapter.pop();
            }
        }

        MethodDef referenced = methodReference.method();
        // Resolved first, so that a reference to a method of the object being generated is asked about
        // the object itself rather than about the this-type marker
        TypeDef owner = ObjectDef.getContextualType(objectDef, methodReference.owner());
        boolean ownerIsInterface = owner instanceof ClassTypeDef classTypeDef && classTypeDef.isInterface();
        var implMethodHandle = new Handle(
            handleTag(ownerIsInterface),
            TypeUtils.getType(owner, objectDef, context.enclosingScope()).getInternalName(),
            referenced.getName(),
            // Erased in the scope of the class and the method declaring it
            referencedDescriptor(owner, objectDef, referenced, context.enclosingScope()),
            ownerIsInterface
        );

        generatorAdapter.visitInvokeDynamicInsn(
            methodReference.instantiated().getName(),
            callSiteDescriptor(instance, objectDef, context.enclosingScope()),
            MetafactoryHandle.BOOTSTRAP,
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, methodReference.target(), context.enclosingScope())),
            implMethodHandle,
            // The instantiated signature names the enclosing method's variables
            Type.getType(TypeUtils.getMethodDescriptor(objectDef, io.micronaut.sourcegen.bytecode.core.TypeUtils.withEnclosingVariables(methodReference.instantiated(), context.methodDef()), context.enclosingScope()))
        );
        popValueIfNeeded(generatorAdapter, methodReference.type());
    }

    /**
     * @param reference     A reference through `super`
     * @param superInstance Its receiver
     * @return The lambda that calls the method on `super`
     */
    static ExpressionDef asSuperCall(MethodReferenceExpression reference, VariableDef.Super superInstance) {
        return reference.type().getLambda().implement((aThis, parameters) -> {
            ExpressionDef.InvokeInstanceMethod call = superInstance.invoke(reference.method(), parameters);
            // The functional method decides: `Runnable r = super::name` discards the result `name()` returns
            return TypeDef.VOID.equals(reference.method().getReturnType())
                || TypeDef.VOID.equals(reference.instantiated().getReturnType()) ? call : call.returning();
        });
    }

    private static String referencedDescriptor(TypeDef owner, @Nullable ObjectDef objectDef, MethodDef referenced, EnclosingScope enclosingScope) {
        ObjectDef scope = io.micronaut.sourcegen.bytecode.core.TypeUtils.declaringScope(owner, objectDef, referenced, enclosingScope);
        return TypeUtils.getMethodDescriptor(scope,
            io.micronaut.sourcegen.bytecode.core.TypeUtils.inDeclaringScope(referenced, scope, objectDef), enclosingScope);
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

    private String callSiteDescriptor(@Nullable ExpressionDef instance, @Nullable ObjectDef objectDef, EnclosingScope enclosingScope) {
        StringBuilder descriptor = new StringBuilder("(");
        if (instance != null) {
            descriptor.append(TypeUtils.getType(instance.type(), objectDef, enclosingScope).getDescriptor());
        }
        descriptor.append(")");
        descriptor.append(TypeUtils.getType(methodReference.type(), objectDef, enclosingScope).getDescriptor());
        return descriptor.toString();
    }

}
