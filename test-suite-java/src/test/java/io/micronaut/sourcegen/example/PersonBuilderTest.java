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

class PersonBuilderTest {

//tag::test[]
    @Test
    public void buildsPerson() {
        var person = PersonBuilder.builder()
            .id(123L)
            .name("Cédric")
            .bytes(new byte[]{1,2,3})
            .build();
        assertEquals("Cédric", person.name());
        assertArrayEquals(new byte[]{1, 2, 3}, person.bytes());
        assertEquals(123L, person.id());
    }
//end::test[]

    @Test
    public void buildsPersonWithPrimitiveDefaults() {
        var person = Person2Builder.builder()
            .build();
        assertEquals("Bob\"", person.name());
        assertEquals(10L, person.id());
        assertEquals(Person2.State.SINGLE, person.state());

        var person2 = Person2Builder.builder()
            .id(20)
            .name("Fred")
            .state(Person2.State.MARRIED)
            .build();
        assertEquals("Fred", person2.name());
        assertEquals(20, person2.id());
        assertEquals(Person2.State.MARRIED, person2.state());
    }
}
