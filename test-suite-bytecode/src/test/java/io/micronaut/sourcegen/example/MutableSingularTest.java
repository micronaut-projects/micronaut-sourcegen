package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutableSingularTest {

    @Test
    void buildsWritableSingularProperty() {
        MutableSingular value = MutableSingularBuilder.builder()
            .name("one")
            .name("two")
            .build();

        assertEquals(List.of("one", "two"), value.getNames());
    }
}
