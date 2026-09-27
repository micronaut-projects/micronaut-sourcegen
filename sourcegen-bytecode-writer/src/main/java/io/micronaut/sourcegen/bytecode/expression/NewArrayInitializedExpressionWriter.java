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
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.List;

final class NewArrayInitializedExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.NewArrayInitialized newArray;

    public NewArrayInitializedExpressionWriter(ExpressionDef.NewArrayInitialized newArray) {
        this.newArray = newArray;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        List<? extends ExpressionDef> expressions = newArray.expressions();
        generatorAdapter.push(expressions.size());
        TypeDef.Array arrayType = newArray.type();
        TypeDef componentType = arrayType.componentType();
        if (arrayType.dimensions() > 1) {
            componentType = componentType.array(arrayType.dimensions() - 1);
        }

        Type type = TypeUtils.getScopedType(componentType, context);
        generatorAdapter.newArray(type);

        if (!expressions.isEmpty()) {
            int index = 0;
            for (ExpressionDef expression : expressions) {
                generatorAdapter.dup();
                generatorAdapter.push(index++);
                ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expression, componentType);
                generatorAdapter.arrayStore(type);
            }
        }
    }
}
