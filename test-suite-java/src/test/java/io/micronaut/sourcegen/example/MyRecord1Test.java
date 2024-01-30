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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyRecord1Test {
    @Test
    public void test() throws Exception {
        MyRecord1 bean = new MyRecord1(List.of("Copied Address"), 123, "TheName", 55, List.of("Address 1"), List.of("X", "Y"));

        assertEquals("TheName", bean.name());
        assertEquals(123, bean.id());
        assertEquals(55, bean.age());
        assertEquals(List.of("Address 1"), bean.addresses());
        assertEquals(List.of("Copied Address"), bean.copyAddresses());
        assertEquals(List.of("X", "Y"), bean.tags());

        assertTrue(Modifier.isPrivate(
            bean.getClass().getDeclaredField("id").getModifiers()
        ));
        assertInstanceOf(Deprecated.class, bean.getClass().getDeclaredField("id").getDeclaredAnnotations()[0]);
        assertTrue(Modifier.isPublic(
            bean.getClass().getDeclaredMethod("id").getModifiers()
        ));
        Deprecated deprecated = (Deprecated) bean.getClass().getDeclaredField("age").getDeclaredAnnotations()[0];
        assertEquals(deprecated.since(), "xyz");
        assertTrue(deprecated.forRemoval());
    }

    @Test
    public void testBuilder() throws Exception {
        MyRecord1 bean = MyRecord1Builder.builder()
            .id(123)
            .name("TheName")
            .age(55)
            .addresses(List.of("Address 1"))
            .tags(List.of("X", "Y"))
            .build();

        assertEquals("TheName", bean.name());
        assertEquals(123, bean.id());
        assertEquals(55, bean.age());
        assertEquals(List.of("Address 1"), bean.addresses());
        assertEquals(List.of("X", "Y"), bean.tags());

        assertTrue(Modifier.isPrivate(
            bean.getClass().getDeclaredField("id").getModifiers()
        ));
        assertInstanceOf(Deprecated.class, bean.getClass().getDeclaredField("id").getDeclaredAnnotations()[0]);
        assertTrue(Modifier.isPublic(
            bean.getClass().getDeclaredMethod("id").getModifiers()
        ));
        Deprecated deprecated = (Deprecated) bean.getClass().getDeclaredField("age").getDeclaredAnnotations()[0];
        assertEquals(deprecated.since(), "xyz");
        assertTrue(deprecated.forRemoval());
    }

    @Test
    public void testBuilder2() throws Exception {
        MyRecord1 bean = MyRecord1.builder()
            .id(123)
            .name("TheName")
            .age(55)
            .addresses(List.of("Address 1"))
            .tags(List.of("X", "Y"))
            .build();

        assertEquals("TheName", bean.name());
        assertEquals(123, bean.id());
        assertEquals(55, bean.age());
        assertEquals(List.of("Address 1"), bean.addresses());
        assertEquals(List.of("X", "Y"), bean.tags());

        assertTrue(Modifier.isPrivate(
            bean.getClass().getDeclaredField("id").getModifiers()
        ));
        assertInstanceOf(Deprecated.class, bean.getClass().getDeclaredField("id").getDeclaredAnnotations()[0]);
        assertTrue(Modifier.isPublic(
            bean.getClass().getDeclaredMethod("id").getModifiers()
        ));
        Deprecated deprecated = (Deprecated) bean.getClass().getDeclaredField("age").getDeclaredAnnotations()[0];
        assertEquals(deprecated.since(), "xyz");
        assertTrue(deprecated.forRemoval());
    }
}
