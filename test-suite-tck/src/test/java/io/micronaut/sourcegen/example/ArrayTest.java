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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArrayTest {

    @Test
    void test1() {
        Array1 array1 = new Array1();
        String[] test = array1.test("");
        Assertions.assertArrayEquals(new String[10], test);
    }

    @Test
    void test2() {
        Array2 array1 = new Array2();
        String[] test = array1.test("");
        Assertions.assertArrayEquals(new String[]{"A", "B", "C"}, test);
    }
}
