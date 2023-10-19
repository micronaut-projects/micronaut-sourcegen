package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.Deprecated
import java.lang.reflect.Modifier

class MyRecord1Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        val bean = MyRecord1(123, "TheName", 55, listOf("Address 1"), listOf("X", "Y"))

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
        assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }
}

