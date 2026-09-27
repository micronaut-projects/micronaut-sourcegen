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
 * Compiled fixtures, shared by several TCK topics, for overload resolution that depends on the bounds of
 * the caller's type variables and on upper-only inference constraints.
 *
 * @since 2.3
 */
@SuppressWarnings({"unchecked", "rawtypes", "varargs"})
public final class ResolutionFixtures {

    private ResolutionFixtures() {
    }


    /** Type-variable bound with overloads.
     * @since 2.3
     */
    public static class Receiver {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(CharSequence value) {
            return "sequence";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(Object value) {
            return "object";
        }
    }

    /** Intersection markers for specificity.
     * @since 2.3
     */
    public interface First { }

    /** Second intersection marker.
     * @since 2.3
     */
    public interface Second { }

    /** Independent inference and overload oracles.
     * @since 2.3
     */
    public static class Calls {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static <T extends First & Second> String intersection(T value) {
            return "both";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String intersection(Second value) {
            return "second";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param first The fixture input
         * @param rest The fixture input
         *
         * @since 2.3
         */
        public static String varargs(Object first, Object... rest) {
            return "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static String varargs(String... values) {
            return "string";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getTypeName();
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static <T extends Number> String upperNumber(List<? extends T> values) {
            return "number";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static String upperNumber(Object values) {
            return "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static <T extends List<String>> String upperList(List<? super T> values) {
            return "list";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static String upperList(Object values) {
            return "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static <T extends Comparable<String>> String upperComparable(List<? super T> values) {
            return "comparable";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         *
         * @since 2.3
         */
        public static String upperComparable(Object values) {
            return "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         * @param other The fixture input
         *
         * @since 2.3
         */
        public static <T> String nested(List<List<T>> values, List<T> other) {
            return "nested";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         * @param other The fixture input
         *
         * @since 2.3
         */
        public static String nested(Object values, Object other) {
            return "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static <T extends Comparable<? super T>> String recursive(T value) {
            return "recursive";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String recursive(Object value) {
            return "object";
        }
    }
}
