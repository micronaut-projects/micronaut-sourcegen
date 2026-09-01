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
package io.micronaut.sourcegen.bytecode.statement;

import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Iterator;
import java.util.Objects;

final class InvokeSuperConstructorStatementWriter implements StatementWriter {
    private final StatementDef.InvokeSuperConstructor invokeSuperConstructor;

    public InvokeSuperConstructorStatementWriter(StatementDef.InvokeSuperConstructor invokeSuperConstructor) {
        this.invokeSuperConstructor = invokeSuperConstructor;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        ExpressionDef instance = invokeSuperConstructor.superInstance();
        ExpressionWriter.writeExpression(generatorAdapter, context, instance);
        Iterator<ParameterDef> iterator = invokeSuperConstructor.method().getParameters().iterator();
        for (ExpressionDef parameter : invokeSuperConstructor.values()) {
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, parameter, iterator.next().getType());
        }
        MethodDef methodDef = invokeSuperConstructor.method();
        Method method = new Method(methodDef.getName(), TypeUtils.getMethodDescriptor(context.objectDef(), methodDef));
        ClassTypeDef superType = getSuperType(context, invokeSuperConstructor.superInstance());
        Type methodOwnerType = TypeUtils.getType(superType, context.objectDef());
        generatorAdapter.invokeConstructor(methodOwnerType, method);
    }

    private ClassTypeDef getSuperType(MethodContext context, VariableDef.Super aSuper) {
        ClassTypeDef superClass;
        if (aSuper.type() == TypeDef.SUPER) {
            if (context.objectDef() instanceof EnumDef) {
                superClass = ClassTypeDef.of(Enum.class);
            } else if (context.objectDef() instanceof RecordDef) {
                superClass = ClassTypeDef.of(Record.class);
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
