package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class MyInterface1Test {
    @Test
    @Throws(Exception::class)
    fun test() {
        val myInstance = MyInstance()
        Assertions.assertEquals(123L, myInstance.findLong())
        myInstance.saveString("abc")
        Assertions.assertEquals("abc", myInstance.myString)
    }

    internal class MyInstance : MyInterface1 {
        var myString: String? = null
        override fun findLong(): Long {
            return 123L
        }

        override fun saveString(myString: String) {
            this.myString = myString
        }
    }
}
