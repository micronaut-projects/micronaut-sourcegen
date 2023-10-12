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

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBean1Test {
    @Test
    public void validatesClone() throws Exception {
        MyBean1 clone = new MyBean1();
        clone.setId(123);
        clone.setName("TheName");
        clone.setAge(55);

        assertEquals("TheName", clone.getName());
        assertEquals(123, clone.getId());
        assertEquals(55, clone.getAge());

        assertTrue(Modifier.isPrivate(
            clone.getClass().getDeclaredField("id").getModifiers()
        ));
        assertTrue(
            clone.getClass().getDeclaredField("id").getDeclaredAnnotations()[0] instanceof Deprecated
        );
        assertTrue(Modifier.isPublic(
            clone.getClass().getDeclaredMethod("getId").getModifiers()
        ));
        Deprecated deprecated = (Deprecated) clone.getClass().getDeclaredField("age").getDeclaredAnnotations()[0];
        assertEquals(deprecated.since(), "xyz");
        assertTrue(deprecated.forRemoval());
    }
}
