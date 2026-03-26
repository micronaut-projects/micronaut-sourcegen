/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example

import io.micronaut.core.beans.BeanIntrospection
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class HeronTest {
    @Test
    fun test() {
        val intro = BeanIntrospection.getIntrospection(
            BlueHeron::class.java
        )

        val simple = intro.getAnnotation(
            Heron.Simple::class.java
        )
        Assertions.assertNotNull(simple)
        Assertions.assertEquals("A", simple!!.stringValue("name").get())
        Assertions.assertEquals(12, simple.intValue("age").asInt)
        Assertions.assertEquals(
            Heron.Color.WHITE, simple.enumValue(
                "color",
                Heron.Color::class.java
            ).get()
        )

        val nested = intro.getAnnotation(
            Heron.Nested::class.java
        )
        Assertions.assertNotNull(nested)
        Assertions.assertNotNull(nested!!.getAnnotation<Annotation>("simple"))

        val multi = intro.getAnnotation(
            Heron.MultiValue::class.java
        )
        Assertions.assertNotNull(multi)
        val simples = multi!!.getAnnotations(
            "simples",
            Heron.Simple::class.java
        )
        Assertions.assertEquals(2, simples.size)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), multi.intValues("values"))
        Assertions.assertArrayEquals(arrayOf("a", "b"), multi.stringValues("strings"))

        val boos = intro.getAnnotationValuesByType(
            Heron.Boo::class.java
        )
        // There should actually be 2 Boo annotations
        Assertions.assertEquals(1, boos.size)
        Assertions.assertEquals("boom", boos[0].stringValue("name").get())
        //Assertions.assertEquals("bam", boos[1].stringValue("name").get())
    }
}
