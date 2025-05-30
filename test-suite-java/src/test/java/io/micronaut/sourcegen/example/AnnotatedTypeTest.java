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
package io.micronaut.sourcegen.example;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
public class AnnotatedTypeTest {

    @Inject
    Validator validator;

    @Test
    public void testSuccess() {
        AnnotatedProperty annotatedProperty = new AnnotatedProperty(List.of(3, 4, 5));
        List<Integer> numbers = List.of(3, 4, 5);
        assertEquals(numbers, annotatedProperty.numbers());
    }

    @Test
    public void testException() {
        AnnotatedProperty annotatedProperty = new AnnotatedProperty(null);
        Set<ConstraintViolation<AnnotatedProperty>> violations = validator.validate(annotatedProperty);
        assertEquals(1, violations.size());

        annotatedProperty = new AnnotatedProperty(List.of());
        violations = validator.validate(annotatedProperty);
        assertEquals(0, violations.size());

        annotatedProperty = new AnnotatedProperty(List.of(100, -10));
        violations = validator.validate(annotatedProperty);
        assertEquals(2, violations.size());
    }
}
