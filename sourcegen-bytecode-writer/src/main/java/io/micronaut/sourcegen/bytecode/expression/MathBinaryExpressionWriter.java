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

final class MathBinaryExpressionWriter implements ExpressionWriter {

    private final ExpressionDef.MathBinaryOperation math;

    public MathBinaryExpressionWriter(ExpressionDef.MathBinaryOperation math) {
        this.math = math;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        Type type = TypeUtils.getScopedType(math.left().type(), context);
        ExpressionWriter.writeExpression(generatorAdapter, context, math.left());
        if (type.getSort() == Type.LONG && isShift(math.opType())) {
            // The JVM shifts a long by an int distance, which the model converts to the type of the operation
            ExpressionDef distance = math.right();
            if (distance instanceof ExpressionDef.Cast cast && cast.expressionDef().type() instanceof TypeDef.Primitive primitive
                && isInt(TypeUtils.getType(primitive))) {
                // An int widened to long only to be narrowed again
                distance = cast.expressionDef();
            }
            ExpressionWriter.writeExpression(generatorAdapter, context, distance);
            if (TypeUtils.getScopedType(distance.type(), context).getSort() == Type.LONG) {
                generatorAdapter.cast(Type.LONG_TYPE, Type.INT_TYPE);
            }
        } else {
            ExpressionWriter.writeExpression(generatorAdapter, context, math.right());
        }
        generatorAdapter.math(getMathOp(math.opType()), type);
        narrow(generatorAdapter, type);
    }

    /**
     * Narrows the int the JVM computes a byte, short or char operation in to the type of the operation, as a cast of
     * the operation does in Java: {@code (byte) (a + b)}.
     *
     * @param generatorAdapter The adapter
     * @param type             The type of the operation
     */
    static void narrow(GeneratorAdapter generatorAdapter, Type type) {
        if (type.getSort() == Type.BYTE || type.getSort() == Type.SHORT || type.getSort() == Type.CHAR) {
            generatorAdapter.cast(Type.INT_TYPE, type);
        }
    }

    private static boolean isInt(Type type) {
        return type.getSort() == Type.INT || type.getSort() == Type.SHORT || type.getSort() == Type.BYTE || type.getSort() == Type.CHAR;
    }

    private static boolean isShift(ExpressionDef.MathBinaryOperation.OpType opType) {
        return opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
    }

    private static int getMathOp(ExpressionDef.MathBinaryOperation.OpType opType) {
        return switch (opType) {
            case ADDITION -> GeneratorAdapter.ADD;
            case SUBTRACTION -> GeneratorAdapter.SUB;
            case MULTIPLICATION -> GeneratorAdapter.MUL;
            case DIVISION -> GeneratorAdapter.DIV;
            case MODULUS -> GeneratorAdapter.REM;

            case BITWISE_AND -> GeneratorAdapter.AND;
            case BITWISE_OR -> GeneratorAdapter.OR;
            case BITWISE_XOR -> GeneratorAdapter.XOR;
            case BITWISE_LEFT_SHIFT -> GeneratorAdapter.SHL;
            case BITWISE_RIGHT_SHIFT -> GeneratorAdapter.SHR;
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> GeneratorAdapter.USHR;
        };
    }
}
