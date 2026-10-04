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

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Compiled fixtures, shared by several TCK topics, for the applicability of generic overloads:
 * wildcard, recursive and dependent bounds, and generic variable arity calls.
 *
 * @since 2.3
 */
@SuppressWarnings({"TypeParameterShadowing", "UnusedTypeParameter", "unchecked", "varargs"})
public final class ApplicabilityFixtures {

    private ApplicabilityFixtures() {
    }


    /** Generic inherited implementation.
     * @param <T> The numeric value
     * @since 2.3
     */
    public static class NumericIdentity<T extends Number> {
        public T apply(T input) {
            return input;
        }
    }

    /** Compiled overload oracle.
     * @since 2.3
     */
    public static class Overloads {
        public static String numeric(long input) {
            return "long";
        }

        public static String numeric(Integer input) {
            return "boxed";
        }

        public static <T> String rows(T[]... values) {
            return values.getClass().getComponentType().getName();
        }

        public static String upper(List<? extends Number> value) {
            return "numbers";
        }

        public static String upper(Object value) {
            return "object";
        }

        public static String lower(List<? super Number> value) {
            return "numbers";
        }

        public static String lower(Object value) {
            return "object";
        }

        public static <T extends Comparable<T>> String recursive(T value) {
            return "comparable";
        }

        public static String recursive(Object value) {
            return "object";
        }

        public static <T> String unify(List<T> values, T value) {
            return "list";
        }

        public static String unify(Collection<?> values, Object value) {
            return "collection";
        }

        public static <A extends Number, B extends A> String dependent(B value) {
            return "number";
        }

        public static String dependent(Object value) {
            return "object";
        }

        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static <T> String seeded(T seed, T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static String serializables(Serializable... values) {
            return values.length + ":" + values[0].getClass().getName();
        }

        public static String nullChoice(Object value) {
            return "object";
        }

        public static String nullChoice(String value) {
            return "string";
        }
    }
}
