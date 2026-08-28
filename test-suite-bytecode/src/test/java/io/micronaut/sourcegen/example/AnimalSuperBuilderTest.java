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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimalSuperBuilderTest {

    @Test
    public void testCat() {
        Cat cat = new CatSuperBuilder()
            .name("MrPurr")
            .age(2)
            .bread("British")
            .meowLevel(100)
            .color("Red")
            .build();

        assertEquals("MrPurr", cat.getName());
        assertEquals(2, cat.getAge());
        assertEquals("British", cat.getBread());
        assertEquals(100, cat.getMeowLevel());
        assertEquals("Red", cat.getColor());
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

        assertEquals("MrDog", dog.getName());
        assertEquals(3, dog.getAge());
        assertEquals("JackR", dog.getBread());
        assertEquals(20, dog.getBarkLevel());
        assertEquals("Blue", dog.getColor());
        assertTrue(dog.isBig());
    }

    @Test
    public void testBuildFromTheRootBuilderType() {
        // Dispatches `self` and `build` through the erasure declared by the root builder, which only
        // resolves when the generated bridge methods are in place
        AbstractAnimalSuperBuilder<Cat, ?> builder = new CatSuperBuilder().name("MrPurr").age(2);

        Animal animal = builder.build();
        assertEquals("MrPurr", animal.getName());
        assertEquals(2, animal.getAge());
    }

    @Test
    public void catBridges() {
        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class, AbstractCatSuperBuilder.class),
            bridgeReturnTypes(CatSuperBuilder.class, "self")
        );
        assertEquals(
            List.of(Animal.class),
            bridgeReturnTypes(CatSuperBuilder.class, "build")
        );
        // The abstract builders redeclare `self` and `build`, so they carry abstract bridges too
        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class),
            bridgeReturnTypes(AbstractCatSuperBuilder.class, "self")
        );
        assertEquals(
            List.of(Animal.class),
            bridgeReturnTypes(AbstractCatSuperBuilder.class, "build")
        );
        // The root builder declares the erasure everything else bridges to
        assertEquals(List.of(), bridgeReturnTypes(AbstractAnimalSuperBuilder.class, "self"));
        assertEquals(List.of(), bridgeReturnTypes(AbstractAnimalSuperBuilder.class, "build"));
    }

    @Test
    public void dogBridges() {
        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class, AbstractDogSuperBuilder.class),
            bridgeReturnTypes(DogSuperBuilder.class, "self")
        );
        assertEquals(
            List.of(Animal.class),
            bridgeReturnTypes(DogSuperBuilder.class, "build")
        );
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

    private static List<Class<?>> bridgeReturnTypes(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .filter(Method::isBridge)
            .map(Method::getReturnType)
            .sorted(Comparator.comparing(Class::getName))
            .toList();
    }
}
