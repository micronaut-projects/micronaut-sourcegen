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

import org.junit.jupiter.api.Assertions.assertEquals

class LambdaTest {

    @Test
    fun testStatelessLambda() {
        val owner = MyClassWithLambda()
        assertEquals("ello!", owner.callLambda("Hello!"))
    }

    @Test
    fun testStatefulLambda() {
        val owner = MyClassWithLambda()
        owner.name = "Tree"
        assertEquals("prefix_ello!MyClassTree", owner.callStatefulLambda("Hello!"))
    }

    @Test
    fun testGenericLambda() {
        val owner = MyClassWithLambda()
        assertEquals("prefix_ello!", owner.callGenericLambda("Hello!"))
    }

    @Test
    fun testStaticMethodReference() {
        val owner = MyClassWithLambda()
        assertEquals("static_Hello!", owner.callStaticMethodReference("Hello!"))
    }

    @Test
    fun testBoundMethodReference() {
        val owner = MyClassWithLambda()
        assertEquals("bound_Hello!", owner.callBoundMethodReference("Hello!"))
    }


}
