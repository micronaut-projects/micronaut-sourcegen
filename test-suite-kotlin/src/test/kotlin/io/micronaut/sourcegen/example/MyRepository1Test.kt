package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*


internal class MyRepository1Test {
    @Test
    @Throws(Exception::class)
    fun test() {
        val myInstance = MyRepository1Instance()
        val myEntity1 = MyEntity1()
        Assertions.assertTrue(myInstance is CrudRepository1<*, *>)
        Assertions.assertTrue(myInstance.findById(123L).isEmpty())
        myInstance.save(myEntity1)
        Assertions.assertEquals(myEntity1, myInstance.findById(123L).get())
        Assertions.assertEquals(myEntity1, myInstance.findAll().iterator().next())
    }

    internal class MyRepository1Instance : MyRepository1 {
        var myEntity1: MyEntity1? = null
        override fun findById(id: Long): Optional<MyEntity1> {
            return Optional.ofNullable<MyEntity1>(myEntity1)
        }

        override fun findAll(): List<MyEntity1> {
            return java.util.List.of<MyEntity1>(myEntity1)
        }

        override fun save(entity: MyEntity1) {
            myEntity1 = entity
        }
    }
}
