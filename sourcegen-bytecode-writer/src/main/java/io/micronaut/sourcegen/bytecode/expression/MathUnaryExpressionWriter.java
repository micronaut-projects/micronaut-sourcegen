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

final class MathUnaryExpressionWriter implements ExpressionWriter {

    private final ExpressionDef.MathUnaryOperation math;

    public MathUnaryExpressionWriter(ExpressionDef.MathUnaryOperation math) {
        this.math = math;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        // A wrapper is unboxed, negated as the primitive it holds and boxed again: the model types the negation as its
        // operand
        TypeDef type = math.type();
        TypeDef operand = TypeDef.Primitive.unboxIfPossible(type);
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, math.expression(), operand);
        org.objectweb.asm.Type operandType = TypeUtils.getScopedType(operand, context);
        generatorAdapter.math(getMathOp(math.opType()), operandType);
        MathBinaryExpressionWriter.narrow(generatorAdapter, operandType);
        if (!operand.equals(type)) {
            CastExpressionWriter.cast(generatorAdapter, context, operand, type);
        }
    }

    private static int getMathOp(ExpressionDef.MathUnaryOperation.OpType opType) {
        return switch (opType) {
            case NEGATE -> GeneratorAdapter.NEG;
        };
    }
}
