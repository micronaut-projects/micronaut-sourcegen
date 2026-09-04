/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.sourcegen.example.Heron.Color;
import io.micronaut.sourcegen.example.Heron.Simple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HeronTest {

    @Test
    void copiesEveryMemberOfAnAnnotation() {
        BeanIntrospection<BlueHeron> intro = BeanIntrospection.getIntrospection(BlueHeron.class);

        AnnotationValue<Simple> simple = intro.getAnnotation(Simple.class);
        assertNotNull(simple);
        assertEquals("A", simple.stringValue("name").get());
        assertEquals(12, simple.intValue("age").getAsInt());
        assertEquals(Color.WHITE, simple.enumValue("color", Color.class).get());
        assertSame(String.class, simple.classValue("type").get());
    }
}
