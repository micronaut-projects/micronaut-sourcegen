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

class MyEnum2Test {

    @Test
    void test() {
        assertEquals(3, MyEnum2.values().length);
        assertEquals("A", MyEnum2.A.myName());
        assertEquals("B", MyEnum2.B.myName());
        assertEquals("C", MyEnum2.C.myName());
    }

    @Test
    void testValues() {
        assertEquals(0, MyEnum2.A.myValue);
        assertEquals(1, MyEnum2.B.myValue);
        assertEquals(2, MyEnum2.C.myValue);
    }
}

