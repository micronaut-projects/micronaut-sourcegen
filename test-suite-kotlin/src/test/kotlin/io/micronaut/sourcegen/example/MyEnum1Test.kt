package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MyEnum1Test {

    @Test
    @Throws(Exception::class)
    fun test() {
        assertEquals(3, MyEnum1.entries.size)
        assertEquals("A", MyEnum1.A.myName())
        assertEquals("B", MyEnum1.B.myName())
        assertEquals("C", MyEnum1.C.myName())
    }
}

