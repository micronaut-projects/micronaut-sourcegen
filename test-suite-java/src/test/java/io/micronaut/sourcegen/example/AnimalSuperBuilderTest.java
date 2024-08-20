/*
 * Copyright 2003-2024 the original author or authors.
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

import java.lang.reflect.Modifier;

import io.micronaut.core.beans.BeanIntrospection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimalSuperBuilderTest {

    //tag::test[]
    @Test
    public void testCat() {
        Cat cat = new CatSuperBuilder()
            .name("MrPurr")
            .age(2)
            .bread("British")
            .meowLevel(100)
            .color("Red")
            .build();

        assertEquals(cat.getName(), "MrPurr");
        assertEquals(cat.getAge(), 2);
        assertEquals(cat.getBread(), "British");
        assertEquals(cat.getMeowLevel(), 100);
        assertEquals(cat.getColor(), "Red");
    }

    @Test
    public void testDog() {
        Dog dog = new DogSuperBuilder()
            .name("MrDog")
            .age(3)
            .bread("JackR")
            .barkLevel(20)
            .color("Blue")
            .big(true)
            .build();

        assertEquals(dog.getName(), "MrDog");
        assertEquals(dog.getAge(), 3);
        assertEquals(dog.getBread(), "JackR");
        assertEquals(dog.getBarkLevel(), 20);
        assertEquals(dog.getColor(), "Blue");
        assertTrue(dog.isBig());
    }
//end::test[]

    @Test
    public void dogIntrospection() {
        var introspection = BeanIntrospection.getIntrospection(DogSuperBuilder.class);
        assertNotNull(introspection);
    }

    @Test
    public void internalTest() {
        assertTrue(Modifier.isPublic(CatSuperBuilder.class.getModifiers()));
        assertEquals(3, AbstractAnimalSuperBuilder.class.getDeclaredFields().length);

        assertEquals(0, CatSuperBuilder.class.getDeclaredFields().length);

        assertEquals(2, AbstractCatSuperBuilder.class.getDeclaredFields().length);

        assertEquals(0, DogSuperBuilder.class.getDeclaredFields().length);

        assertEquals(3, AbstractDogSuperBuilder.class.getDeclaredFields().length);
    }
}
