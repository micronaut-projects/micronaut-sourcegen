package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JavadocTest {

    @Test
    void testJavadocClass() {
        Javadoc doc = new Javadoc();
        Assertions.assertNotNull(doc);

        doc.setName("hi");
        Assertions.assertEquals("hi", doc.getName());
    }
}
