package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.Deprecated
import java.lang.reflect.Modifier

class MyBean1Test {

    @Test
    @Throws(Exception::class)
    fun validatesClone() {
        val clone = MyBean1()
        clone.id = 123
        clone.name = "TheName"
        clone.age = 55
        assertEquals("TheName", clone.name)
        assertEquals(123, clone.id)
        assertEquals(55, clone.age)

        Assertions.assertTrue(
            Modifier.isPrivate(
                clone.javaClass.getDeclaredField("id").modifiers
            )
        )
        Assertions.assertTrue(
            clone.javaClass.getDeclaredField("id").declaredAnnotations[0] is Deprecated
        )
        Assertions.assertTrue(
            Modifier.isPublic(
                clone.javaClass.getDeclaredMethod("getId").modifiers
            )
        )
        val deprecated = clone.javaClass.getDeclaredField("age").declaredAnnotations[0] as Deprecated
        assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }
}

