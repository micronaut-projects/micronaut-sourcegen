/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.docs.objects

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    //end::test[]

    //tag::testt[]
    @Test
    fun testMultipleDimensionArrays() {
        val elephant = Elephant("Daisy", 5, false, 1)
        val elephantDiff = Elephant("Daisy", 5, false, 2)
        val elephantSame = Elephant("Dumbo", 5, false, 1)

        assertNotEquals(elephant.hashCode(), elephantDiff.hashCode())
        assertEquals(elephant.hashCode(), elephantSame.hashCode())
    }

    @Test
    fun testEqualsWithExclude() {
        val elephant = Elephant("Daisy", 5, false, 1)
        val elephantDiff = Elephant("Daisy", 5, false, 2)
        val elephantSame = Elephant("Dumbo", 5, false, 1)

        assertNotEquals(elephant, elephantDiff)
        assertEquals(elephant, elephantSame)
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

        assertEquals(person, person)
        assertEquals(person, personSame)

        assertNotEquals(person, personDiffPrimitive)
        assertNotEquals(person, personDiffEnum)
        assertNotEquals(person, personDiffObject)
        assertNotEquals(person, personDiffArray)
    }

    @Test
    fun testEqualsWithNulls() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDoubleNull1 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personDoubleNull2 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personSingleNull = Person4(124L, Person4.Title.MR, "Cédric", null)

        assertFalse(person.equals(null))
        assertNotEquals(Any(), person)

        assertEquals(personDoubleNull1, personDoubleNull2)
        assertNotEquals(personSingleNull, person)
        assertNotEquals(person, personSingleNull)
    }

    @Test
    fun testHashCodeWithNulls() {
        val person = Person4(123L, Person4.Title.MR, "Cédric", byteArrayOf(1, 2, 3))
        val personDoubleNull1 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personDoubleNull2 = Person4(123L, Person4.Title.MR, null, byteArrayOf(1, 2, 3))
        val personSingleNull = Person4(124L, Person4.Title.MR, "Cédric", null)

        assertEquals(personDoubleNull1.hashCode(), personDoubleNull2.hashCode())
        assertNotEquals(personSingleNull.hashCode(), person.hashCode())
        assertNotEquals(person.hashCode(), personSingleNull.hashCode())
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
    }
    //end::testt[]
}
