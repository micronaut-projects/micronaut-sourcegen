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

class WalrusTest {

//tag::test[]
    @Test
    public void test() throws Exception {
        Walrus walrus = new Walrus("Abc", 123, new byte[]{56});

        assertEquals(walrus.name(), "Abc");
        assertEquals(walrus.age(), 123);
        assertArrayEquals(walrus.chipInfo(), new byte[]{56});

        walrus = walrus.withName("Xyz");

        assertEquals(walrus.name(), "Xyz");
        assertEquals(walrus.age(), 123);
        assertArrayEquals(walrus.chipInfo(), new byte[]{56});

        walrus = walrus.withAge(99);

        assertEquals(walrus.name(), "Xyz");
        assertEquals(walrus.age(), 99);
        assertArrayEquals(walrus.chipInfo(), new byte[]{56});

        walrus = walrus.withChipInfo(new byte[]{1, 2, 3});

        assertEquals(walrus.name(), "Xyz");
        assertEquals(walrus.age(), 99);
        assertArrayEquals(walrus.chipInfo(), new byte[]{1, 2, 3});
    }
//end::test[]
}
