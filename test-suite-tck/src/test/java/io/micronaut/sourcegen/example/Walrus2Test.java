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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Walrus2Test {

//tag::test[]
    @Test
    public void testWitherAndBuilder() throws Exception {
        Walrus2 walrus = new Walrus2("Abc", 123, new byte[]{56});

        assertEquals("Abc", walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{56}, walrus.chipInfo());

        // The name property is NOT annotated with @NotNull so `withName(null)` method should NOT fail
        walrus = walrus.withName(null);

        assertNull(walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{56}, walrus.chipInfo());

        walrus = walrus.withName("Xyz");

        assertEquals("Xyz", walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{56}, walrus.chipInfo());

        walrus = walrus.withAge(99);

        assertEquals("Xyz", walrus.name());
        assertEquals(99, walrus.age());
        assertArrayEquals(new byte[]{56}, walrus.chipInfo());

        walrus = walrus.withChipInfo(new byte[]{1, 2, 3});

        assertEquals("Xyz", walrus.name());
        assertEquals(99, walrus.age());
        assertArrayEquals(new byte[]{1, 2, 3}, walrus.chipInfo());

        walrus = walrus.with().build();

        assertEquals("Xyz", walrus.name());
        assertEquals(99, walrus.age());
        assertArrayEquals(new byte[]{1, 2, 3}, walrus.chipInfo());

        walrus = walrus.with().name("Foobar").build();

        assertEquals("Foobar", walrus.name());
        assertEquals(99, walrus.age());
        assertArrayEquals(new byte[]{1, 2, 3}, walrus.chipInfo());

        walrus = walrus.with().name("Abc").age(123).chipInfo(new byte[]{9, 8, 7}).build();

        assertEquals("Abc", walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{9, 8, 7}, walrus.chipInfo());

        walrus = walrus.with(builder -> builder.name("Denis"));

        assertEquals("Denis", walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{9, 8, 7}, walrus.chipInfo());

        walrus = walrus.with(builder -> builder.name("Kevin").age(1).chipInfo(new byte[]{123}));

        assertEquals("Kevin", walrus.name());
        assertEquals(1, walrus.age());
        assertArrayEquals(new byte[]{123}, walrus.chipInfo());
    }
//end::test[]
}
