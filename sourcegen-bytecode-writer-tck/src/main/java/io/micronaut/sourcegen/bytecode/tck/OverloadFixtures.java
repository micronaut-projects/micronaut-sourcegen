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

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Compiled fixtures, shared by several TCK topics, for overload selection by primitive widening, boxing,
 * variable arity and least upper bounds, and for calls on interface and intersection receivers.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "unchecked", "rawtypes", "varargs", "unused", "EqualsIncompatibleType", "UnusedMethod",
    "UnusedVariable"
})
public final class OverloadFixtures {

    private OverloadFixtures() {
    }


    /** Overloads the calls choose between.
     * @since 2.3
     */
    public static class Calls {

        public static String widest(int value) {
            return "int";
        }

        public static String widest(long value) {
            return "long";
        }

        public static String widest(Integer value) {
            return "Integer";
        }

        public static String widest(Object value) {
            return "Object";
        }

        public static String narrow(char value) {
            return "char";
        }

        public static String narrow(int value) {
            return "int";
        }

        public static String small(short value) {
            return "short";
        }

        public static String small(char value) {
            return "char";
        }

        public static String small(int value) {
            return "int";
        }

        public static String nullable(Object value) {
            return "object";
        }

        public static String nullable(char[] value) {
            return "chars";
        }

        public static String unboxing(long value) {
            return "long:" + value;
        }

        public static String unboxing(String value) {
            return "string";
        }

        public static <T extends Integer> String unboxingVariable(T value) {
            return unboxing(value);
        }

        public static String character(int value) {
            return "int:" + value;
        }

        public static String character(String value) {
            return "string";
        }

        public static <T extends Character> String characterVariable(T value) {
            return character(value);
        }

        public static String characterLong(long value) {
            return "long:" + value;
        }

        public static String characterLong(String value) {
            return "string";
        }

        public static String real(double value) {
            return "double:" + value;
        }

        public static String array(Object value) {
            return "object";
        }

        public static String array(Object... values) {
            return "varargs:" + values.length;
        }

        public static String ints(int... values) {
            return "int...";
        }

        public static String ints(long... values) {
            return "long...";
        }

        public static String longs(long... values) {
            return "sum:" + Arrays.stream(values).sum();
        }

        public static String objects(Object... values) {
            return Arrays.stream(values).map(value -> value.getClass().getSimpleName()).collect(Collectors.joining(","));
        }

        public static <T> String generic(T value) {
            return "generic";
        }

        public static String generic(String value) {
            return "string";
        }

        @SafeVarargs
        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getName();
        }

        public static <T> T identity(T value) {
            return value;
        }

        public static <T extends Comparable<T>> String recursive(T value) {
            return "recursive";
        }

        public static String recursive(Object value) {
            return "object";
        }

        public static <T extends Number> String boxed(NumberBox<T> box) {
            return "number";
        }

        public static String boxed(Object box) {
            return "object";
        }

        public static String ambiguous(Object first, String second) {
            return "object-string";
        }

        public static String ambiguous(String first, Object second) {
            return "string-object";
        }
    }

    /** A class whose variable is bounded.
     * @param <E> The bounded variable
     * @since 2.3
     */
    public static class NumberBox<E extends Number> { }

    /** An overload of one bound.
     * @since 2.3
     */
    public interface ObjectChooser {
        default String pick(Object value) {
            return "object";
        }
    }

    /** A more specific overload of another bound.
     * @since 2.3
     */
    public interface StringChooser {
        default String pick(String value) {
            return "string";
        }
    }

    /** An overload the argument does not apply to.
     * @since 2.3
     */
    public interface IntegerTaker {
        default String take(Integer value) {
            return "integer";
        }
    }

    /** The overload the argument applies to.
     * @since 2.3
     */
    public interface StringTaker {
        default String take(String value) {
            return "string";
        }
    }

    /** javac's calls on receivers.
     * @since 2.3
     */
    public static class Receivers {
        public static <T extends ObjectChooser & StringChooser> String intersection(T value) {
            return value.pick("x");
        }

        public static <T extends IntegerTaker & StringTaker> String takers(T value) {
            return value.take("x");
        }

        @SuppressWarnings("static-access")
        public static String staticOnInstance(String value) {
            return value.valueOf(1);
        }

        public static Object castToZetas(Alpha[] values) {
            return (Zeta[]) values;
        }
    }

    /** Marker.
     * @since 2.3
     */
    public interface Zeta { }

    /** Marker.
     * @since 2.3
     */
    public interface Alpha { }

    /** Implements the markers in declaration order.
     * @since 2.3
     */
    public static class ZetaAlphaOne implements Zeta, Alpha { }

    /** javac's enum bridge.
     * @since 2.3
     */
    public enum EnumSupplier implements Supplier<String> {
        A;

        @Override
        public String get() {
            return "a";
        }
    }
}
