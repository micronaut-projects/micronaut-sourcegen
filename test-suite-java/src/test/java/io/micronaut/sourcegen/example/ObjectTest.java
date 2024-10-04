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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectTest {
    //tag::test[]
    @Test
    public void testToString() {
        var person = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});

        assertNotNull(Person4Object.toString(person));
        Assertions.assertTrue(person.toString().contains("Person4["));
        assertEquals("Person4[id=123, title=MR, name=Cédric, bytes=[1, 2, 3]]", person.toString());
    }

    //end::test[]

    //tag::testt[]
    @Test
    public void testMultipleDimensionArrays() {
        var elephant = new Elephant("Daisy", 5, false, 1);
        var elephantDiff = new Elephant("Daisy", 5, false, 2);
        var elephantSame = new Elephant("Dumbo", 5, false, 1);

        assertNotEquals(elephant.hashCode(), elephantDiff.hashCode());
        assertEquals(elephant.hashCode(), elephantSame.hashCode());
    }

    @Test
    public void testEqualsWithExclude() {
        var elephant = new Elephant("Daisy", 5, false, 1);
        var elephantDiff = new Elephant("Daisy", 5, false, 2);
        var elephantSame = new Elephant("Dumbo", 5, false, 1);

        assertNotEquals(elephant, elephantDiff);
        assertEquals(elephant, elephantSame);
    }

    @Test
    public void testEqualsWithCorrectObjects() {
        var person = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personSame = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDiffPrimitive = new Person4(124L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDiffEnum = new Person4(123L, Person4.Title.MRS,"Cédric", new byte[]{1,2,3});
        var personDiffObject = new Person4(123L, Person4.Title.MR,"Cédric Jr.", new byte[]{1,2,3});
        var personDiffArray = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,4});

        assertNotNull(Person4Object.equals(person, personSame));

        assertEquals(person, person);
        assertEquals(person, personSame);

        assertNotEquals(person, personDiffPrimitive);
        assertNotEquals(person, personDiffEnum);
        assertNotEquals(person, personDiffObject);
        assertNotEquals(person, personDiffArray);
    }

    @Test
    public void testEqualsWithNulls() {
        var person = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDoubleNull1 = new Person4(123L, Person4.Title.MR,null, new byte[]{1,2,3});
        var personDoubleNull2 = new Person4(123L, Person4.Title.MR,null, new byte[]{1,2,3});
        var personSingleNull = new Person4(124L, Person4.Title.MR,"Cédric", null);

        assertNotEquals(null, person);
        assertNotEquals(person, new Object());

        assertEquals(personDoubleNull1, personDoubleNull2);
        assertNotEquals(personSingleNull, person);
        assertNotEquals(person, personSingleNull);
    }

    @Test
    public void testHashCodeWithNulls() {
        var person = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDoubleNull1 = new Person4(123L, Person4.Title.MR,null, new byte[]{1,2,3});
        var personDoubleNull2 = new Person4(123L, Person4.Title.MR,null, new byte[]{1,2,3});
        var personSingleNull = new Person4(124L, Person4.Title.MR,"Cédric", null);

        assertEquals(personDoubleNull1.hashCode(), personDoubleNull2.hashCode());
        assertNotEquals(personSingleNull.hashCode(), person.hashCode());
        assertNotEquals(person.hashCode(), personSingleNull.hashCode());
    }

    @Test
    public void testHashCode() {
        var person = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personSame = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDiffPrimitive = new Person4(124L, Person4.Title.MR,"Cédric", new byte[]{1,2,3});
        var personDiffEnum = new Person4(123L, Person4.Title.MRS,"Cédric", new byte[]{1,2,3});
        var personDiffObject = new Person4(123L, Person4.Title.MR,"Cédric Jr.", new byte[]{1,2,3});
        var personDiffArray = new Person4(123L, Person4.Title.MR,"Cédric", new byte[]{1,2,4});

        assertNotNull(Person4Object.hashCode(person));
        assertEquals(person.hashCode(), person.hashCode());
        assertEquals(person.hashCode(), personSame.hashCode());
        assertNotEquals(person.hashCode(), personDiffPrimitive.hashCode());
        assertNotEquals(person.hashCode(), personDiffEnum.hashCode());
        assertNotEquals(person.hashCode(), personDiffObject.hashCode());
        assertNotEquals(person.hashCode(), personDiffArray.hashCode());
    }
    //end::testt[]
}
