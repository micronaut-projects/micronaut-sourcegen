package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WhaleBuilderTest {
    @Test
    fun buildsWhale() {
        val whale: Whale = WhaleBuilder.builder()
            .aBC("Abc")
            .x(123)
            .URL("https://example.com")
            .build()
        assertEquals("Abc", whale.aBC)
        assertEquals(123, whale.x)
        assertEquals("https://example.com", whale.URL)
    }
}
