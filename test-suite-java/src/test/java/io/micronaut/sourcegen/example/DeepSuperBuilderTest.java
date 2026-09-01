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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Animal -&gt; Bird -&gt; Parrot, a three level {@code @SuperBuilder} hierarchy. Every builder redeclares
 * {@code self} and {@code build} with a narrower erasure than the one it extends, so each of them needs
 * a bridge per ancestor.
 */
class DeepSuperBuilderTest {

    @Test
    void buildTheLeafOfTheHierarchy() {
        Parrot parrot = ParrotSuperBuilder.builder()
            .name("Polly")
            .age(4)
            .color("Green")
            .wingSpan(30)
            .vocabulary("hello")
            .build();

        assertEquals("Polly", parrot.getName());
        assertEquals(4, parrot.getAge());
        assertEquals("Green", parrot.getColor());
        assertEquals(30, parrot.getWingSpan());
        assertEquals("hello", parrot.getVocabulary());
    }

    @Test
    void buildThroughEveryAncestorErasure() {
        // Each static type resolves `build` to the erasure declared by that builder, so this walks
        // every bridge in turn
        AbstractParrotSuperBuilder<Parrot, ?> asParrot = ParrotSuperBuilder.builder().name("Polly").wingSpan(30);
        assertEquals(30, asParrot.build().getWingSpan());

        AbstractBirdSuperBuilder<Parrot, ?> asBird = ParrotSuperBuilder.builder().name("Polly").wingSpan(30);
        assertEquals(30, asBird.build().getWingSpan());

        AbstractAnimalSuperBuilder<Parrot, ?> asAnimal = ParrotSuperBuilder.builder().name("Polly").wingSpan(30);
        Animal animal = asAnimal.build();
        assertEquals("Polly", animal.getName());
    }

    @Test
    void everyBuilderBridgesToEachAncestor() {
        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class),
            bridgeReturnTypes(AbstractBirdSuperBuilder.class, "self")
        );
        assertEquals(
            List.of(Animal.class),
            bridgeReturnTypes(AbstractBirdSuperBuilder.class, "build")
        );

        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class, AbstractBirdSuperBuilder.class),
            bridgeReturnTypes(AbstractParrotSuperBuilder.class, "self")
        );
        assertEquals(
            List.of(Animal.class, Bird.class),
            bridgeReturnTypes(AbstractParrotSuperBuilder.class, "build")
        );

        assertEquals(
            List.of(AbstractAnimalSuperBuilder.class, AbstractBirdSuperBuilder.class, AbstractParrotSuperBuilder.class),
            bridgeReturnTypes(ParrotSuperBuilder.class, "self")
        );
        // `build` returns Parrot already, so only the two wider erasures are bridged
        assertEquals(
            List.of(Animal.class, Bird.class),
            bridgeReturnTypes(ParrotSuperBuilder.class, "build")
        );
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
