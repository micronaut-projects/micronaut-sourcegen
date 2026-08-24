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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MyBean3Test {
    @Test
    void test() throws Exception {
        MyBean3 bean1 = new MyBean3();
        assertNull(bean1.otherName);

        MyBean3 bean2 = new MyBean3("xyz");
        assertEquals("xyz", bean2.otherName);
        Constructor<MyBean3> constructor = MyBean3.class.getDeclaredConstructor(Integer.class);
        assertNotNull(constructor);
        Parameter parameter = constructor.getParameters()[0];
        assertNotNull(parameter);
        assertNotNull(parameter.getAnnotatedType().getDeclaredAnnotation(Nullable.class));
    }

    @Test
    void testConcatenate() {
        MyBean3 bean3 = new MyBean3();

        assertEquals("Hello, Andriy", bean3.concatenation("Andriy"));
    }

    @Test
    void testThrows() {
        MyBean3 bean3 = new MyBean3();

        assertThrows(IOException.class, bean3::getStringUnsafe);
    }

    @Test
    void testConcatenateWithPrimitives() {
        MyBean3 bean3 = new MyBean3();

        assertEquals("Count: 3, price: 1.5, flag: true", bean3.concatenationWithPrimitives(3, 1.5, true));
    }
}
