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
 * Compiled fixtures, shared by several TCK topics, whose overloads javac chooses by capturing wildcards
 * and solving bounds; a generated call must choose the same overload.
 *
 * @since 2.3
 */
@SuppressWarnings({"unchecked", "rawtypes", "varargs", "TypeParameterUnusedInFormals"})
public final class CaptureFixtures {

    private CaptureFixtures() {
    }


    /** javac overload oracle.
     * @since 2.3
     */
    public static class Calls {
        public static <T extends Number> String boundedLower(List<? super T> values) {
            return "generic";
        }

        public static String boundedLower(Object values) {
            return "fallback";
        }

        public static <T> String upperOnly(List<? super T> first, List<? super T> second) {
            return "generic";
        }

        public static String upperOnly(Object first, Object second) {
            return "fallback";
        }

        public static <T> String rank(List<T[]> values, T other) {
            return "generic";
        }

        public static String rank(Object values, Object other) {
            return "fallback";
        }

        public static String numeric(Number value) {
            return "number";
        }

        public static String numeric(Object value) {
            return "object";
        }

        public static <T> String nestedUpper(List<? extends List<T>> values, T value) {
            return "generic";
        }

        public static String nestedUpper(Object values, Object value) {
            return "fallback";
        }

        public static <T> String captureValue(List<T> values, T value) {
            return "generic";
        }

        public static String captureValue(Object values, Object value) {
            return "fallback";
        }

        public static <T> String sameLists(List<T> first, List<T> second) {
            return "generic";
        }

        public static String sameLists(Object first, Object second) {
            return "fallback";
        }

        public static <T> String transfer(List<? extends T> source, List<? super T> target) {
            return "generic";
        }

        public static String transfer(Object source, Object target) {
            return "fallback";
        }

        public static <T extends Comparable<T>> String recursive(T first, T second) {
            return "generic";
        }

        public static String recursive(Object first, Object second) {
            return "fallback";
        }

        public static <T> String nestedLower(List<? super List<T>> first, List<T> second) {
            return "generic";
        }

        /**
         * @param value The input value
         * @param <T> The inferred numeric type
         * @return The input value
         */
        public static <T extends Number> T identity(T value) {
            return value;
        }

        /**
         * @param value The input value
         * @param <T> The inferred numeric type
         * @return The selected generic overload result
         */
        public static <T extends Number> T targetReturn(String value) {
            return (T) Integer.valueOf(7);
        }

        /**
         * @param value The input value
         * @return The fallback overload result
         */
        public static Integer targetReturn(Object value) {
            return -1;
        }

        /**
         * @param <T> The target type
         * @return The target value
         */
        public static <T> T create() {
            return null;
        }

        public static String nestedLower(Object first, Object second) {
            return "fallback";
        }

        public static <T extends Zeta & Alpha> String component(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static <T> String arrayComponent(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static <A extends B, B extends CharSequence> String dependent(A first, B second) {
            return first.toString() + second;
        }
    }

    /** First intersection bound.
     * @since 2.3
     */
    public interface Zeta { }

    /** Second intersection bound.
     * @since 2.3
     */
    public interface Alpha { }

    /** Common interfaces deliberately declared in the opposite order to the method bounds.
     * @since 2.3
     */
    public static class First implements Alpha, Zeta { }

    /** Other common-interface implementation.
     * @since 2.3
     */
    public static class Second implements Alpha, Zeta { }
}
