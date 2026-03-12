package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PersonBuilderTest {
    @Test
    fun buildsPerson() {
        val person: Person = PersonBuilder.builder()
            .id(123L)
            .name("Cédric")
            .bytes(arrayOf(1, 2, 3))
            .build()
        assertEquals("Cédric", person.name)
        assertEquals(3, person.bytes.size)
        assertEquals(123L, person.id)
    }
    @Test
    fun strictBuildPerson() {
        val person: PersonStrict = PersonStrictBuilder.builder()
            .id(123L)
            .name("Cédric")
            .bytes(arrayOf(1, 2, 3))
            .build()
        assertEquals("Cédric", person.name)
        assertEquals(3, person.bytes.size)
        assertEquals(123L, person.id)

        val exception = assertThrows< IllegalStateException>("Should throw an Exception") {
            PersonStrictBuilder.builder()
                .id(123L)
                .name("Cédric")
                .name("test")
                .bytes(arrayOf(1, 2, 3))
                .build()
        }
        assertEquals("name cannot be reinitialized.", exception.message)
    }
}
