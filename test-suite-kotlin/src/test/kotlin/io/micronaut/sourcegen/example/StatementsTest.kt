/*
 * Copyright 2003-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class StatementsTest {

    @Test
    fun tryCatchTakesTheCatchBranch() {
        assertEquals(50, MyStatements.divideOrFallback(2))
        assertEquals(-1, MyStatements.divideOrFallback(0))
    }

    @Test
    fun catchBlockSeesTheException() {
        assertEquals("50", MyStatements.catchAndDescribe(2))
        assertEquals("java.lang.ArithmeticException: / by zero", MyStatements.catchAndDescribe(0))
    }

    @Test
    fun finallyRunsAfterTheBody() {
        val builder = StringBuilder()
        assertEquals("t", MyStatements.tryFinally(builder))
        assertEquals("tf", builder.toString())
    }

    @Test
    fun synchronizedBlockRunsItsBody() {
        assertEquals("locked", MyStatements.synchronizedAssign(Any()))
    }

    @Test
    fun staticFieldIsAssignedAndRead() {
        assertEquals("none", MyStatements.recallValue())
        MyStatements.rememberValue("remembered")
        assertEquals("remembered", MyStatements.recallValue())
    }

    @Test
    fun arrayElementsAreIndexed() {
        assertEquals("b", MyStatements.elementOfObjectArray(1))
        assertEquals(30, MyStatements.elementOfPrimitiveArray(2))
        assertEquals(0L, MyStatements.sizedPrimitiveArray())
    }

    @Test
    fun instanceOfIsChecked() {
        assertTrue(MyStatements.isString("text"))
        assertFalse(MyStatements.isString(1))
    }

    @Test
    fun operandsKeepTheirPrecedence() {
        assertEquals(20, MyStatements.multiplyBySum(4, 2, 3))
        assertEquals(20, MyStatements.shiftOfSum(2, 3, 2))
        assertTrue(MyStatements.orThenAnd(false, true, true))
        assertFalse(MyStatements.orThenAnd(true, false, false))
        assertEquals(-5L, MyStatements.negatedSumAsLong(2, 3))
        assertEquals("5", MyStatements.sumAsString(2, 3))
    }

}
