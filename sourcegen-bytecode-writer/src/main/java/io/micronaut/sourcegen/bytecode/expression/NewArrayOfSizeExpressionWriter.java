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
import org.objectweb.asm.commons.GeneratorAdapter;

final class NewArrayOfSizeExpressionWriter implements ExpressionWriter {
    private final ExpressionDef.NewArrayOfSize newArray;

    public NewArrayOfSizeExpressionWriter(ExpressionDef.NewArrayOfSize newArray) {
        this.newArray = newArray;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        generatorAdapter.push(newArray.size());
        // The array counts every dimension at once: the elements of `new int[2][]` are `int[]`s
        TypeDef.Array type = newArray.type();
        TypeDef element = type.dimensions() > 1 ? TypeDef.array(type.componentType(), type.dimensions() - 1) : type.componentType();
        generatorAdapter.newArray(TypeUtils.getScopedType(element, context));
    }
}
