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
package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated annotation type is applied to a class here, so it has to be a usable annotation type
 * for the compiler as well as for reflection.
 */
@MyAnnotation(
    string = "this",
    primitive = 3,
    floats = {1.0f, 2.0f},
    inner = @Target(ElementType.TYPE)
)
class MyAnnotationTest {

    @Test
    void theGeneratedAnnotationTypeDeclaresItsMetaAnnotations() {
        assertTrue(MyAnnotation.class.isAnnotation());
        assertEquals(RetentionPolicy.RUNTIME, MyAnnotation.class.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, MyAnnotation.class.getAnnotation(Target.class).value());
    }

    @Test
    void theValuesOfAnApplicationAreRead() {
        MyAnnotation annotation = MyAnnotationTest.class.getAnnotation(MyAnnotation.class);

        assertEquals("this", annotation.string());
        assertEquals(3, annotation.primitive());
        assertArrayEquals(new float[]{1.0f, 2.0f}, annotation.floats());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, annotation.inner().value());
    }

    @Test
    void theDefaultsOfTheMembersLeftOutAreRead() {
        MyAnnotation annotation = MyAnnotationTest.class.getAnnotation(MyAnnotation.class);

        assertEquals(ElementType.TYPE, annotation.enumValue());
        assertArrayEquals(new int[]{1, 2, 3}, annotation.array());
    }
}
