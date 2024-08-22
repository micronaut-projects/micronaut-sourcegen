package io.micronaut.sourcegen.example;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class GoodModelTest {
    @Test
    void testWither() {
        GoodModel model = new GoodModel(1, "test");
        GoodModel updated = model.withData("updated");
        assertEquals("updated", updated.data());
    }
}
