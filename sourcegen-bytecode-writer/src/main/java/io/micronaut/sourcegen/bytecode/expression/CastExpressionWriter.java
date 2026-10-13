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
import io.micronaut.sourcegen.bytecode.core.Conversions;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

final class CastExpressionWriter implements ExpressionWriter {

    private final ExpressionDef.Cast castExpressionDef;

    public CastExpressionWriter(ExpressionDef.Cast castExpressionDef) {
        this.castExpressionDef = castExpressionDef;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        // Only the last cast of a chain is written, unless an inner one converts or checks what it does not
        ExpressionDef exp = Conversions.castOperand(castExpressionDef, context.objectDef(), context.methodDef(), context.enclosingScope());
        ExpressionWriter.writeExpression(generatorAdapter, context, exp);
        if (exp instanceof ExpressionDef.Constant constant && constant.value() == null) {
            // null needs no cast to a reference; to a primitive it is unboxed, which throws, as javac unboxes it
            if (castExpressionDef.type() instanceof TypeDef.Primitive primitive && !primitive.equals(TypeDef.VOID)) {
                cast(generatorAdapter, context, primitive.wrapperType(), primitive);
            }
            return;
        }
        cast(generatorAdapter, context, exp.type(), castExpressionDef.type());
    }

    /**
     * Emits the conversion {@link Conversions#plan} plans, leaving out the checkcasts the model knows redundant.
     *
     * @param generatorAdapter The adapter
     * @param context          The method being written
     * @param from             The type of the value on the stack
     * @param to               The type to convert it to
     */
    static void cast(GeneratorAdapter generatorAdapter, MethodContext context, TypeDef from, TypeDef to) {
        for (Conversions.Step step : Conversions.plan(from, to, context.objectDef(), context.methodDef(), Conversions.Checkcasts.MODEL, context.enclosingScope())) {
            switch (step) {
                case Conversions.CheckCast checkCast -> generatorAdapter.checkCast(Type.getType(checkCast.descriptor()));
                case Conversions.Unboxing unboxing -> {
                    Type owner = Type.getObjectType(unboxing.owner());
                    generatorAdapter.checkCast(owner);
                    generatorAdapter.invokeVirtual(owner, new Method(unboxing.method(), unboxing.methodDescriptor()));
                }
                case Conversions.PrimitiveConversion conversion ->
                    generatorAdapter.cast(TypeUtils.getType(conversion.from()), TypeUtils.getType(conversion.to()));
                case Conversions.Box box -> generatorAdapter.valueOf(TypeUtils.getType(box.primitive()));
            }
        }
    }
}
