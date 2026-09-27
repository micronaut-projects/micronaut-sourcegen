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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.bytecode.core.SwitchKeys;
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * The abstract switch writer.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public class AbstractSwitchWriter {

    protected static void pushSwitchExpression(GeneratorAdapter generatorAdapter,
                                               MethodContext context,
                                               ExpressionDef expression) {
        TypeDef switchExpressionType = expression.type();
        if (!SwitchKeys.switchesOnInt(switchExpressionType)) {
            throw new UnsupportedOperationException("Not allowed switch expression type: " + switchExpressionType);
        }
        // A char, a short or a byte is widened, and a wrapper unboxed, as javac switches on them
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expression, TypeDef.Primitive.INT);
    }

    protected static int toSwitchKey(ExpressionDef.Constant constant) {
        if (constant.value() instanceof String s) {
            return s.hashCode();
        }
        Integer key = SwitchKeys.key(constant);
        if (key != null) {
            return key;
        }
        throw new UnsupportedOperationException("Unrecognized constant for a switch key: " + constant);
    }

}
