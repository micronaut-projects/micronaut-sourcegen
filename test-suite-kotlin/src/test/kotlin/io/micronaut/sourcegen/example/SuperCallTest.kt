package io.micronaut.sourcegen.example

import MultiParamChildClass
import NoParamChildClass
import SpecificSuperTypeReferenceClass
import SuperTypeReferenceClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

class SuperCallTest {
    @Test
    fun testSuperTypeReference() {
        val superTypeReferenceClass = SuperTypeReferenceClass()
        Assertions.assertNotNull(superTypeReferenceClass.simpleSuperCall())
    }

    @Test
    fun testSpecificSuperTypeReference() {
        val specificSuperTypeReferenceClass = SpecificSuperTypeReferenceClass()
        Assertions.assertEquals(specificSuperTypeReferenceClass.specificSuperCall(), "Parent.specificMethod")
    }

    @Test
    fun testSuperConstructorWithNoParam() {
        val noParamChildClass = NoParamChildClass()
        Assertions.assertNotNull(noParamChildClass)
    }

    @Test
    fun testSuperConstructorWithParam() {
        val multiParamChildClass = MultiParamChildClass(123, 456L)
        Assertions.assertNotNull(multiParamChildClass)
    }
}
