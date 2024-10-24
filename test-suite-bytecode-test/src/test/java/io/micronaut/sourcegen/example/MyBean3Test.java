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

import io.micronaut.core.annotation.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MyBean3Test {
    @Test
    public void test() throws Exception {
        MyBean3 bean1 = new MyBean3();
        assertNull(bean1.otherName);

        MyBean3 bean2 = new MyBean3("xyz");
        assertEquals("xyz", bean2.otherName);
        assertNotNull(
            MyBean3.class.getDeclaredConstructor(Integer.class).getParameters()[0].getDeclaredAnnotation(Nullable.class)
        );
    }
}
