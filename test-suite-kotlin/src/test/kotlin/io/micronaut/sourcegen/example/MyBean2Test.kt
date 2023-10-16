package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.Deprecated
import java.lang.reflect.Modifier

class MyBean2Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        val bean = MyBean2()
        bean.id = 123
        bean.name = "TheName"
        bean.age = 55
        assertEquals("TheName", bean.name)
        assertEquals(123, bean.id)
        assertEquals(55, bean.age)

        Assertions.assertTrue(
            Modifier.isPrivate(
                bean.javaClass.getDeclaredField("id").modifiers
            )
        )
        Assertions.assertTrue(
            bean.javaClass.getDeclaredField("id").declaredAnnotations[0] is Deprecated
        )
        Assertions.assertTrue(
            Modifier.isPublic(
                bean.javaClass.getDeclaredMethod("getId").modifiers
            )
        )
        val deprecated = bean.javaClass.getDeclaredField("age").declaredAnnotations[0] as Deprecated
        assertEquals(deprecated.since, "xyz")
        Assertions.assertTrue(deprecated.forRemoval)
    }
}

