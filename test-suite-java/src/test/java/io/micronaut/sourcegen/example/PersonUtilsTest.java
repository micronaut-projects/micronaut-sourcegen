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
        var person = new Person4(123L, "Cédric", new byte[]{1,2,3});
        assertNotNull(Person4Utils.toString(person));
        assertTrue(Person4Utils.toString(person).contains("Person4["));
    }


    @Test
    public void testEquals() {
        var person = new Person4(123L, "Cédric", new byte[]{1,2,3});
        var personSame = new Person4(123L, "Cédric", new byte[]{1,2,3});
        var personDiffAll = new Person4(124L, "Cédric 2", new byte[]{1,2,3, 4});
        var personDiffPrimitive = new Person4(124L, "Cédric", new byte[]{1,2,3});
        var personDiffObject = new Person4(123L, "Cédric", new byte[]{1,2,3, 4});

        assertTrue(Person4Utils.equals(person, personSame));
        assertTrue(Person4Utils.equals(person, person));
        assertFalse(Person4Utils.equals(person, personDiffAll));
        assertFalse(Person4Utils.equals(person, personDiffPrimitive));
        assertFalse(Person4Utils.equals(person, personDiffObject));
    }

    @Test
    public void testHashCode() {
        var person = new Person4(123L, "Cédric", new byte[]{1,2,3});
        var personSame = new Person4(123L, "Cédric", new byte[]{1,2,3});
        var personDiffAll = new Person4(124L, "Cédric 2", new byte[]{1,2,3, 4});
        assertEquals(Person4Utils.hashCode(person), Person4Utils.hashCode(personSame));
        assertNotEquals(Person4Utils.hashCode(person), Person4Utils.hashCode(personDiffAll));
    }

}
