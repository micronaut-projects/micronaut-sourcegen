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

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBean2Test {
    @Test
    public void test() throws Exception {
        MyBean2 bean = new MyBean2();
        bean.setId(123);
        bean.setName("TheName");
        bean.setAge(55);

        assertEquals("TheName", bean.getName());
        assertEquals(123, bean.getId());
        assertEquals(55, bean.getAge());

        bean.setId(987);
        bean.setName("Xyz");
        bean.setAge(123);

        assertEquals("Xyz", bean.getName());
        assertEquals(987, bean.getId());
        assertEquals(123, bean.getAge());

        assertTrue(Modifier.isPrivate(
            bean.getClass().getDeclaredField("id").getModifiers()
        ));
        assertInstanceOf(Deprecated.class, bean.getClass().getDeclaredField("id").getDeclaredAnnotations()[0]);
        assertTrue(Modifier.isPublic(
            bean.getClass().getDeclaredMethod("getId").getModifiers()
        ));
        Deprecated deprecated = (Deprecated) bean.getClass().getDeclaredField("age").getDeclaredAnnotations()[0];
        assertEquals(deprecated.since(), "xyz");
        assertTrue(deprecated.forRemoval());
    }
}
