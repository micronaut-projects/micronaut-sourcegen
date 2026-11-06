/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Constants of the model written as Kotlin literals: bytes and shorts that box as their own type, `Long.MIN_VALUE`,
 * and negative constants as receivers. Every program is compiled and run.
 */
class ConstantWriteTest {

    /**
     * A method returning an expression of constants returns what the other backends return. Each case names the
     * literal the source used to write wrongly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("returnedConstants")
    fun constantIsReturnedAsTheBytecodeReturnsIt(constant: ConstantCase) {
        val result = runMethod("test.${constant.className}", constant.returns, listOf()) { _, _ -> constant.value.returning() }
        assertEquals(constant.expected, result)
    }

    /** A constant expression returned from the method `run` of the class `test.<className>`. */
    data class ConstantCase(
        val description: String,
        val className: String,
        val returns: TypeDef,
        val value: ExpressionDef,
        val expected: Any
    ) {
        override fun toString() = description
    }

    companion object {
        @JvmStatic
        fun returnedConstants() = listOf(
            ConstantCase("a byte constant returned as an Object boxes as a Byte, not an Int",
                "SmallByteConstant", TypeDef.OBJECT, TypeDef.Primitive.BYTE.constant(5.toByte()), 5.toByte()),
            ConstantCase("a short constant returned as an Object boxes as a Short, not an Int",
                "SmallShortConstant", TypeDef.OBJECT, TypeDef.Primitive.SHORT.constant(5.toShort()), 5.toShort()),
            ConstantCase("a byte constant cast to Object boxes as a Byte",
                "ByteConstantBox", TypeDef.OBJECT, ExpressionDef.primitiveConstant(5.toByte()).cast(TypeDef.OBJECT), 5.toByte()),
            // Long.hashCode(-1L) is 0; Kotlin reads -1L.hashCode() as -(1L.hashCode())
            ConstantCase("a negative constant receiver is parenthesized, not read as -(1L.hashCode())",
                "NegativeReceiver", TypeDef.Primitive.INT, ExpressionDef.constant(-1L).invokeHashCode(), 0),
            ConstantCase("Long.MIN_VALUE compiles, although its magnitude is no Long literal",
                "LongMinValue", TypeDef.Primitive.LONG, ExpressionDef.constant(Long.MIN_VALUE), Long.MIN_VALUE)
        )
    }
}
