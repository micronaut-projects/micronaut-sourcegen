package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MyBean3Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        val bean1: MyBean3 = MyBean3()
        Assertions.assertNull(bean1.otherName)

        val bean2: MyBean3 = MyBean3("xyz")
        assertEquals("xyz", bean2.otherName)
    }
}

