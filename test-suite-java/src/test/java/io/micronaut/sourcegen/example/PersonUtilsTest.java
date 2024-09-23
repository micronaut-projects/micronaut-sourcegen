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

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PersonUtilsTest {

    @Test
    public void testToString() {
        var person = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3});
        assertNotNull(person.toString());
        assertTrue(person.toString().contains("Person4Utils["));
    }


    @Test
    public void testEquals() {
        var person = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3});
        var personSame = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3});
        var personDiffAll = new Person4Utils(124L, "Cédric 2", 21, new byte[]{1,2,3, 4});
        var personDiffPrimitive = new Person4Utils(123L, "Cédric", 21, new byte[]{1,2,3});
        var personDiffObject = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3, 4});

        assertTrue(person.equals(personSame));
        assertTrue(person.equals(person));
        assertFalse(person.equals(personDiffAll));
        assertFalse(person.equals(personDiffPrimitive));
        assertFalse(person.equals(personDiffObject));
    }

    @Test
    public void testHashCode() {
        var person = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3});
        var personSame = new Person4Utils(123L, "Cédric", 20, new byte[]{1,2,3});
        var personDiffAll = new Person4Utils(124L, "Cédric 2", 21, new byte[]{1,2,3, 4});
        assertEquals(person.hashCode(), personSame.hashCode());
        assertNotEquals(person.hashCode(), personDiffAll.hashCode());
    }

}
