/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode.tck;

import java.util.List;

/**
 * Compiled fixtures, shared by several TCK topics, for inference constraints: wildcard containment,
 * lower bounds, least upper bounds and generic variable arity calls.
 *
 * @since 2.3
 */
@SuppressWarnings({"unchecked", "varargs", "rawtypes"})
public final class InferenceFixtures {

    private InferenceFixtures() {
    }


    /**
     * javac overload oracle.
     * @since 2.3
     */
    public static class Calls {
        public static <T> String lower(List<? super T> list, T value) {
            return "generic";
        }

        public static String lower(Object list, Object value) {
            return "fallback";
        }

        public static <T> String nested(List<? extends List<T>> list, T value) {
            return "generic";
        }

        public static String nested(Object list, Object value) {
            return "fallback";
        }

        public static <T> String arrayArgument(List<T[]> list, T value) {
            return "generic";
        }

        public static String arrayArgument(Object list, Object value) {
            return "fallback";
        }

        public static String upper(List<? extends Number> list) {
            return "number";
        }

        public static String upper(Object list) {
            return "fallback";
        }

        public static String supertype(List<? super Integer> list) {
            return "integer";
        }

        public static String supertype(Object list) {
            return "fallback";
        }

        public static <T extends CharSequence, U extends T> String dependent(U first, T second) {
            return "generic";
        }

        public static String dependent(Object first, Object second) {
            return "fallback";
        }

        public static <T extends Shared> String shared(T first, T second) {
            return "generic";
        }

        public static String shared(Object first, Object second) {
            return "fallback";
        }

        public static <T extends Shared> String sharedArray(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static String bound(Shared value) {
            return "shared";
        }

        public static String bound(Object value) {
            return "object";
        }

        public static String any(List<?> value) {
            return "list";
        }

        public static String any(Object value) {
            return "object";
        }

        public static <A extends B, B extends Number> String chain(A value) {
            return "number";
        }

        public static String chain(Object value) {
            return "object";
        }

        public static String number(Number value) {
            return value.toString();
        }

        public static <T extends Number> String capture(List<T> value) {
            return "number";
        }

        public static String capture(Object value) {
            return "object";
        }

        public static String invariantArray(List<List<String>[]> value) {
            return "list";
        }

        public static String invariantArray(Object value) {
            return "object";
        }
    }

    /**
     * Common interface for unrelated classes.
     * @since 2.3
     */
    public interface Shared { }

    /**
     * First unrelated implementation.
     * @since 2.3
     */
    public static class Left implements Shared { }

    /**
     * Raw bounded class oracle.
     * @param <T> Number
     * @since 2.3
     */
    public static class RawBoundedReceiver<T extends Number> {
        public String choose(T value) {
            return "number";
        }

        public String choose(Object value) {
            return "object";
        }
    }
}
