/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObjectTest {
    //tag::test[]
    @Test
    fun testToString() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))

        assertNotNull(Person4Object.toString(person))
        assertTrue(person.toString().contains("Person4["))
        assertEquals("Person4[id=123, title=MR, name=Cédric, bytes=[1, 2, 3]]", person.toString())
    }

    @Test
    fun testToStringWithExclude() {
        val elephant: Elephant = Elephant("Daisy", 5, false, 1)

        assertNotNull(ElephantObject.toString(elephant))
        assertEquals("Elephant[name=Daisy, age=5, isHasSibling=false]", elephant.toString())
    }

    //end::test[]
    //tag::testt[]
    @Test
    fun testMultipleDimensionArrays() {
        val elephant: Elephant = Elephant("Daisy", 5, false, 1)
        val elephantDiff: Elephant = Elephant("Daisy", 5, false, 2)
        val elephantSame: Elephant = Elephant("Dumbo", 5, false, 1)

        assertNotEquals(elephant.hashCode(), elephantDiff.hashCode())
        assertEquals(elephant.hashCode(), elephantSame.hashCode())
    }

    @Test
    fun testEqualsWithExclude() {
        val elephant: Elephant = Elephant("Daisy", 5, false, 1)
        val elephantDiff: Elephant = Elephant("Daisy", 5, false, 2)
        val elephantSame: Elephant = Elephant("Dumbo", 5, false, 1)

        Assertions.assertFalse(elephant == elephantDiff)
        assertTrue(elephant == elephantSame)
    }

    @Test
    fun testEqualsWithCorrectObjects() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personSame = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffPrimitive = Person4(124L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffEnum = Person4(123L, Person4.Title.MRS, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffObject = Person4(123L, Person4.Title.MR, "Cédric Jr.", byteArrayOf(1, 2, 3))
        val personDiffArray = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 4))

        assertNotNull(Person4Object.equals(person, personSame))

        assertTrue(person == person)
        assertTrue(person == personSame)

        Assertions.assertFalse(person == personDiffPrimitive)
        Assertions.assertFalse(person == personDiffEnum)
        Assertions.assertFalse(person == personDiffObject)
        Assertions.assertFalse(person == personDiffArray)
    }

    @Test
    fun testEqualsWithNulls() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDoubleNull1 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personDoubleNull2 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personSingleNull = Person4(124L, Person4.Title.MR, "Cédric", null)

        Assertions.assertFalse(person.equals(null))
        Assertions.assertFalse(person == Any())

        assertTrue(personDoubleNull1 == personDoubleNull2)
        Assertions.assertFalse(personSingleNull == person)
        Assertions.assertFalse(person == personSingleNull)
    }

    @Test
    fun testHashCodeWithNulls() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDoubleNull1 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personDoubleNull2 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personSingleNull = Person4(124L, Person4.Title.MR, "Cédric", null)

        assertTrue(personDoubleNull1.hashCode() == personDoubleNull2.hashCode())
        Assertions.assertFalse(personSingleNull.hashCode() == person.hashCode())
        Assertions.assertFalse(person.hashCode() == personSingleNull.hashCode())
    }

    @Test
    fun testHashCode() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personSame = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffPrimitive = Person4(124L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffEnum = Person4(123L, Person4.Title.MRS, "Cédric", byteArrayOf(1, 2, 3))
        val personDiffObject = Person4(123L, Person4.Title.MR, "Cédric Jr.", byteArrayOf(1, 2, 3))
        val personDiffArray = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 4))

        assertNotNull(Person4Object.hashCode(person))
        assertEquals(person.hashCode(), person.hashCode())
        assertEquals(person.hashCode(), personSame.hashCode())
        assertNotEquals(person.hashCode(), personDiffPrimitive.hashCode())
        assertNotEquals(person.hashCode(), personDiffEnum.hashCode())
        assertNotEquals(person.hashCode(), personDiffObject.hashCode())
        assertNotEquals(person.hashCode(), personDiffArray.hashCode())
    } //end::testt[]
}
