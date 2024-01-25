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
package io.micronaut.sourcegen.example

import junit.framework.TestCase.assertEquals
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.lang.Deprecated
import java.lang.reflect.Modifier

internal class MyRecord2Test {
    @Test
    @Throws(Exception::class)
    fun test() {
        val bean = `Trigger$MyRecord2`(123, "TheName", 55, listOf("Address 1"), listOf("X", "Y"))

        assertEquals("TheName", bean.name)
        assertEquals(123, bean.id)
        assertEquals(55, bean.age)
        assertEquals(listOf("Address 1"), bean.addresses)
        assertEquals(listOf("X", "Y"), bean.tags)

        Assertions.assertTrue(
            Modifier.isPrivate(
                bean.javaClass.getDeclaredField("id").modifiers
            )
        )
        Assertions.assertTrue(
            bean.javaClass.constructors[0].parameters[0].declaredAnnotations[0] is Deprecated
        )
        Assertions.assertTrue(
            Modifier.isPublic(
                bean.javaClass.getDeclaredMethod("getId").modifiers
            )
        )
        val deprecated =  bean.javaClass.constructors[0].parameters[2].declaredAnnotations[0] as Deprecated
        Assertions.assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }

    @Test
    @Throws(Exception::class)
    fun testBuilder() {
        val bean = `Trigger$MyRecord2Builder`.builder()
            .id(123)
            .name("TheName")
            .age(55)
            .addresses(listOf("Address 1"))
            .tags(listOf("X", "Y"))
            .build()

        assertEquals("TheName", bean.name)
        assertEquals(123, bean.id)
        assertEquals(55, bean.age)
        assertEquals(listOf("Address 1"), bean.addresses)
        assertEquals(listOf("X", "Y"), bean.tags)

        Assertions.assertTrue(
            Modifier.isPrivate(
                bean.javaClass.getDeclaredField("id").modifiers
            )
        )
        Assertions.assertTrue(
            bean.javaClass.constructors[0].parameters[0].declaredAnnotations[0] is Deprecated
        )
        Assertions.assertTrue(
            Modifier.isPublic(
                bean.javaClass.getDeclaredMethod("getId").modifiers
            )
        )
        val deprecated =  bean.javaClass.constructors[0].parameters[2].declaredAnnotations[0] as Deprecated
        Assertions.assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }
}
