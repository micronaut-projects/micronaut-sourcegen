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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class IfsPredicateTest {
    @Test
    fun testIf() {
        val predicate: IfPredicate = IfPredicate()
        assertTrue(predicate.test(null))
        assertFalse(predicate.test(""))
    }

    @Test
    fun testIfNon() {
        val predicate: IfNonPredicate = IfNonPredicate()
        assertFalse(predicate.test(null))
        assertTrue(predicate.test(""))
    }

    @Test
    fun testIfElse() {
        val predicate: IfElsePredicate = IfElsePredicate()
        assertTrue(predicate.test(null))
        assertFalse(predicate.test(""))
    }

    @Test
    fun testIfElseNon() {
        val predicate: IfNonElsePredicate = IfNonElsePredicate()
        assertTrue(predicate.test(null))
        assertFalse(predicate.test(""))
    }

    @Test
    fun testIfExpressionELse() {
        val predicate: IfNonElseExpressionPredicate = IfNonElseExpressionPredicate()
        assertTrue(predicate.test(null))
        assertFalse(predicate.test(""))
    }
}
