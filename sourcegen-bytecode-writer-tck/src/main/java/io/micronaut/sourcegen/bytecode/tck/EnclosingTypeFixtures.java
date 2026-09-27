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

/**
 * Compiled fixtures, shared by several TCK topics, whose member types and overloads depend on the
 * type arguments of an enclosing type.
 *
 * @since 2.3
 */
@SuppressWarnings({"rawtypes", "unchecked", "varargs"})
public final class EnclosingTypeFixtures {

    private EnclosingTypeFixtures() {
    }


    /** Enclosing class for member scope tests.
     * @param <T> Enclosing numeric type
     * @since 2.3
     */
    public static class Outer<T extends Number> {
        /** Generic member of the numeric enclosing class.
         * @param <U> Member type
         * @since 2.3
         */
        public class Inner<U> {

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param value The fixture input
             *
             * @since 2.3
             */
            public String choose(T value) {
                return "outer";
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

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param <V> The fixture input
             * @param value The fixture input
             *
             * @since 2.3
             */
            public <V extends T> String bounded(V value) {
                return "bound";
            }

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param value The fixture input
             *
             * @since 2.3
             */
            public String bounded(Object value) {
                return "object";
            }

            /** Nested generic member.
             * @param <V> Member type
             * @since 2.3
             */
            public class Deep<V> { }
        }

        /** Member with no variables of its own.
         * @since 2.3
         */
        public class Plain {

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param value The fixture input
             *
             * @since 2.3
             */
            public String choose(T value) {
                return "outer";
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

        /** Member inheriting an enclosing-variable parameter.
         * @since 2.3
         */
        public class Inherited extends NumericParent<T> { }

        /** Member variable shadows the enclosing variable.
         * @param <T> Member type
         * @since 2.3
         */
        public class Shadow<T extends CharSequence> {

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param value The fixture input
             *
             * @since 2.3
             */
            public String choose(T value) {
                return "inner";
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

        /** A dollar is a legal character in a member simple name.
         * @param <U> Member type
         * @since 2.3
         */
        @SuppressWarnings("checkstyle:TypeName")
        public class Dollar$Member<U> { }

        /** Base whose parameter belongs to the enclosing class.
         * @since 2.3
         */
        public class Base {

            /**
             * Supplies the javac behavior or signature used by the regression test.
             *
             * @return The javac fixture result
             * @param value The fixture input
             *
             * @since 2.3
             */
            public String accept(T value) {
                return "base";
            }
        }
    }

    /** Inherited generic parameter overloads.
     * @param <T> Fixture type
     * @since 2.3
     */
    public static class NumericParent<T extends Number> {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(T value) {
            return "number";
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

    /** Independent overload and varargs oracles.
     * @since 2.3
     */
    public static class Calls {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String owner(Outer<Integer>.Inner<String> value) {
            return "member";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String owner(Object value) {
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
        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getTypeName();
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String unbox(long value) {
            return "long";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public static String specific(CharSequence value) {
            return "sequence";
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
        public static <T> String specific(T value) {
            return "generic";
        }
    }
}
