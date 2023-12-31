package io.micronaut.sourcegen.example

import io.micronaut.sourcegen.example.Person
import io.micronaut.sourcegen.example.PersonBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PersonBuilderTest {
    @Test
    fun buildsPerson() {
        val person: Person = PersonBuilder.builder()
            .id(123L)
            .name("Cédric")
            .build()
        assertEquals("Cédric", person.name)
        assertEquals(123L, person.id)
    }
}
