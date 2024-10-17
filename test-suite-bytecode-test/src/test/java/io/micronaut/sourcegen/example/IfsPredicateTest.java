/*
 * Copyright 2003-2021 the original author or authors.
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
package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IfsPredicateTest {

    @Test
    public void testIf() {
        IfPredicate predicate = new IfPredicate();
        assertTrue(predicate.test(null));
        assertFalse(predicate.test(""));
    }

    @Test
    public void testIfNon() {
        IfNonPredicate predicate = new IfNonPredicate();
        assertFalse(predicate.test(null));
        assertTrue(predicate.test(""));
    }

    @Test
    public void testIfElse() {
        IfElsePredicate predicate = new IfElsePredicate();
        assertTrue(predicate.test(null));
        assertFalse(predicate.test(""));
    }

    @Test
    public void testIfElse2() {
        IfElsePredicate2 predicate = new IfElsePredicate2();
        assertEquals(predicate.test(null), 1);
        assertEquals(predicate.test(""), 2);
    }

    @Test
    public void testIfElseNon() {
        IfNonElsePredicate predicate = new IfNonElsePredicate();
        assertTrue(predicate.test(null));
        assertFalse(predicate.test(""));
    }

    @Test
    public void testIfExpressionElse() {
        IfNonElseExpressionPredicate predicate = new IfNonElseExpressionPredicate();
        assertTrue(predicate.test(null));
        assertFalse(predicate.test(""));
    }

    @Test
    public void testIfExpressionElse2() {
        IfNonElseExpressionPredicate2 predicate = new IfNonElseExpressionPredicate2();
        assertEquals(predicate.test(null), 1);
        assertEquals(predicate.test(""), 2);
    }
}
