package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.Deprecated
import java.lang.reflect.Modifier

class MyBean1Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        val bean = MyBean1(123, "TheName", 55, listOf(), listOf())
        assertEquals("TheName", bean.name)
        assertEquals(123, bean.id)
        assertEquals(55, bean.age)
        bean.name = "Xyz"
        bean.id = 987
        bean.age = 123
        bean.addresses = listOf("Address 1", "Address 2")
        bean.tags = listOf("Tag 1", "Tag 2")
        assertEquals("Xyz", bean.name)
        assertEquals(987, bean.id)
        assertEquals(123, bean.age)
        assertEquals(listOf("Address 1", "Address 2"), bean.addresses)
        assertEquals(listOf("Tag 1", "Tag 2"), bean.tags)

        Assertions.assertTrue(
            Modifier.isPrivate(
                bean.javaClass.getDeclaredField("id").modifiers
            )
        )
        Assertions.assertFalse(
            Modifier.isFinal(
                bean.javaClass.getDeclaredField("id").modifiers
            )
        )
        // For primary constructor bean the annotation are set on the constructor parameters
        Assertions.assertTrue(
            bean.javaClass.constructors[0].parameters[0].declaredAnnotations[0] is Deprecated
        )
        Assertions.assertTrue(
            Modifier.isPublic(
                bean.javaClass.getDeclaredMethod("getId").modifiers
            )
        )
        Assertions.assertNotNull(
                bean.javaClass.declaredMethods.find { it.name == "setId" }
        )
        val deprecated = bean.javaClass.constructors[0].parameters[2].declaredAnnotations[0] as Deprecated
        assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }
}

