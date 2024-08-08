package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EmptyTest {

    @Test
    void testEmptyBuilder() {
        EmptyThing built = EmptyThingBuilder.builder().build();
        Assertions.assertNotNull(built);
    }

    @Test
    void testEmptySuperBuilder() {
        EmptyChild built = EmptyChildSuperBuilder.builder().build();
        Assertions.assertNotNull(built);
    }
}
