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

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Walrus4Test {

//tag::test[]
    @Test
    public void test() {
        Walrus4 walrus = new Walrus4("Abc", 123, new byte[]{56});

        walrus = walrus.withName("Xyz").withChipInfo(new byte[]{1, 2, 3});

        assertEquals("Xyz", walrus.name());
        assertEquals(123, walrus.age());
        assertArrayEquals(new byte[]{1, 2, 3}, walrus.chipInfo());
    }

    @Test
    public void testOnlyAnnotatedComponentsHaveWithMethods() {
        // `age` is not annotated with @Wither, so no `withAge` method is generated
        assertTrue(Arrays.stream(Walrus4Wither.class.getMethods()).noneMatch(m -> m.getName().equals("withAge")));
    }
//end::test[]
}
