package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class MyBean3Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        val bean1: MyBean3 = MyBean3()
        Assertions.assertNull(bean1.otherName)

        val bean2: MyBean3 = MyBean3("xyz")
        assertEquals("xyz", bean2.otherName)
    }

    @Test
    @Throws(Exception::class)
    fun testConcatenate() {
        val bean3 = MyBean3()

        assertEquals("Hello, Andriy", bean3.concatenation("Andriy"))
    }

    @Test
    @Throws(Exception::class)
    fun testThrows() {
        val bean3 = MyBean3()
        assertThrows<IOException> { bean3.getStringUnsafe() }
    }
}

