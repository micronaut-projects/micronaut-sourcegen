package io.micronaut.sourcegen.example;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@MicronautTest
public class OtherBeanTest {
    @Test
    void testBeanFromGenerated(OtherBean otherBean) {
        Assertions.assertNotNull(otherBean);
    }
}
