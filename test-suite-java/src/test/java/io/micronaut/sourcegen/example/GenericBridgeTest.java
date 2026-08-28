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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated types implement generic supertypes, so every call made through the erased supertype
 * signature goes through a bridge method.
 */
class GenericBridgeTest {

    @Test
    void genericSuperClassCoversCovariantAndErasedParameter() {
        // The static type is the generic super class, so `set` and `get` are called with the erased
        // `(Object)void` and `()Object` signatures
        GenericHolder<String> holder = new StringHolder();
        assertNull(holder.get());

        holder.set("hello");
        assertEquals("hello", holder.get());
    }

    @Test
    void erasedParameterBridgeChecksTheArgument() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        GenericHolder<Object> raw = (GenericHolder) new StringHolder();

        // The cast the bridge performs before delegating is what rejects the argument
        assertThrows(ClassCastException.class, () -> raw.set(42));
    }

    @Test
    void genericInterfaceErasesBothParameterAndReturnType() {
        Function<String, Integer> function = new LengthFunction();
        assertEquals(5, function.apply("hello"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        Function<Object, Object> raw = (Function) function;
        assertEquals(5, raw.apply("hello"));
        assertThrows(ClassCastException.class, () -> raw.apply(42));
    }

    @Test
    void genericInterfaceWithPrimitiveReturnType() {
        Comparator<String> comparator = new LengthComparator();
        assertEquals(0, comparator.compare("a", "a"));
        assertTrue(comparator.compare("a", "b") < 0);

        // Sorting dispatches through `compare(Object, Object)`, which only exists as a bridge
        List<String> values = new ArrayList<>(List.of("c", "a", "b"));
        values.sort(comparator);
        assertEquals(List.of("a", "b", "c"), values);
    }

    @Test
    void declaredBridges() {
        assertEquals(List.of("()Ljava/lang/Object;"), bridges(StringHolder.class, "get"));
        assertEquals(List.of("(Ljava/lang/Object;)V"), bridges(StringHolder.class, "set"));
        assertEquals(List.of("(Ljava/lang/Object;)Ljava/lang/Object;"), bridges(LengthFunction.class, "apply"));
        assertEquals(List.of("(Ljava/lang/Object;Ljava/lang/Object;)I"), bridges(LengthComparator.class, "compare"));
        // The generic super class declares the erasure the others bridge to
        assertEquals(List.of(), bridges(GenericHolder.class, "get"));
        assertEquals(List.of(), bridges(GenericHolder.class, "set"));
    }

    private static List<String> bridges(Class<?> type, String methodName) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName))
            .filter(Method::isBridge)
            .map(GenericBridgeTest::descriptorOf)
            .sorted()
            .toList();
    }

    private static String descriptorOf(Method method) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            builder.append(descriptorOf(parameterType));
        }
        return builder.append(')').append(descriptorOf(method.getReturnType())).toString();
    }

    private static String descriptorOf(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == int.class) {
            return "I";
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
