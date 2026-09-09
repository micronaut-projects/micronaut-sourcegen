/*
 * Copyright 2017-2025 original authors
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The builders of properties whose names the JavaBeans rules do not decapitalize the way the fields
 * behind them are named. See <a href="https://github.com/micronaut-projects/micronaut-sourcegen/issues/244">#244</a>.
 */
class WhaleTest {

    @Test
    void testBuilder() {
        Whale whale = WhaleBuilder.builder()
            .ABC("Abc")
            .x(123)
            .URL("https://example.com")
            .build();

        assertEquals("Abc", whale.getABC());
        assertEquals(123, whale.getX());
        assertEquals("https://example.com", whale.getURL());
    }

    @Test
    void testStagedBuilder() {
        Whale2 whale = Whale2StagedBuilder.builder()
            .ABC("Abc")
            .x(123)
            .URL("https://example.com")
            .build();

        assertEquals("Abc", whale.getABC());
        assertEquals(123, whale.getX());
        assertEquals("https://example.com", whale.getURL());
    }

    @Test
    void testRecordBuilder() {
        Whale3 whale = Whale3Builder.builder()
            .aBC("Abc")
            .x(123)
            .URL("https://example.com")
            .build();

        assertEquals("Abc", whale.aBC());
        assertEquals(123, whale.x());
        assertEquals("https://example.com", whale.URL());
    }

    @Test
    void testRecordWither() {
        Whale3 whale = new Whale3("Abc", 123, "https://example.com");

        assertEquals("Xyz", whale.withaBC("Xyz").aBC());
        assertEquals(99, whale.withX(99).x());
        assertEquals("https://micronaut.io", whale.withURL("https://micronaut.io").URL());

        Whale3 rebuilt = whale.with(builder -> builder.x(7));

        assertEquals("Abc", rebuilt.aBC());
        assertEquals(7, rebuilt.x());
        assertEquals("https://example.com", rebuilt.URL());
    }
}
