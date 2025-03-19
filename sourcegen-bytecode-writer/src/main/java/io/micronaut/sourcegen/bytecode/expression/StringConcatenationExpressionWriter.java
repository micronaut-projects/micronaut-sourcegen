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

import io.micronaut.sourcegen.bytecode.AbstractConditionalWriter;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.StringConcatenation;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.List;

final class StringConcatenationExpressionWriter extends AbstractConditionalWriter implements ExpressionWriter {

    private static final String VALUE_OF_METHOD = "valueOf";
    private static final String CONCAT_METHOD = "concat";
    private final InvokeInstanceMethodExpressionWriter methodWriter;

    public StringConcatenationExpressionWriter(StringConcatenation concat) {
        ExpressionDef left = concat.left();
        if (!left.type().equals(TypeDef.STRING)) {
            left = TypeDef.STRING.invokeStatic(VALUE_OF_METHOD, List.of(TypeDef.OBJECT), TypeDef.STRING, List.of(left));
        }
        methodWriter = new InvokeInstanceMethodExpressionWriter(
            left.invoke(CONCAT_METHOD, List.of(TypeDef.STRING), TypeDef.STRING, List.of(concat.right()))
        );
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
       methodWriter.write(generatorAdapter, context);
    }
}
