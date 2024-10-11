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

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class ArrayTest {
    @Test
    fun test1() {
        val array1 = Array1()
        val test = array1.test("")
        Assertions.assertArrayEquals(arrayOfNulls<String>(10), test)
    }

    @Test
    fun test2() {
        val array1 = Array2()
        val test = array1.test("")
        Assertions.assertArrayEquals(arrayOf("A", "B", "C"), test)
    }
}
