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

import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Collection;
import java.util.Iterator;

final class NewInstanceExpressionWriter extends AbstractStatementAwareExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.NewInstance newInstance;

    public NewInstanceExpressionWriter(ExpressionDef.NewInstance newInstance) {
        this.newInstance = newInstance;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        Type type = TypeUtils.getType(newInstance.type(), context.objectDef());
        generatorAdapter.newInstance(type);
        generatorAdapter.dup();
        Iterator<TypeDef> iterator = newInstance.parameterTypes().iterator();
        for (ExpressionDef expression : newInstance.values()) {
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expression, iterator.next());
        }
        generatorAdapter.invokeConstructor(
            type,
            new Method("<init>", getConstructorDescriptor(context.objectDef(), newInstance.parameterTypes()))
        );
        popValueIfNeeded(generatorAdapter, newInstance.type());
    }

    private static String getConstructorDescriptor(@Nullable ObjectDef objectDef, Collection<TypeDef> types) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');

        for (TypeDef argumentType : types) {
            builder.append(TypeUtils.getType(argumentType, objectDef).getDescriptor());
        }

        return builder.append(")V").toString();
    }
}
