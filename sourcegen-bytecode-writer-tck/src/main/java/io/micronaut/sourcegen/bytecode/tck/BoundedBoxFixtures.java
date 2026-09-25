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
 * Compiled fixtures for a generic class that declares the bound of its type parameter.
 *
 * @since 2.3
 */
public final class BoundedBoxFixtures {

    private BoundedBoxFixtures() {
    }


    /**
     * A generic class that declares the bound of its parameter.
     *
     * @param <E> The element type
     */
    public static class NumberBox<E extends Number> {
    }

    /**
     * The overloads the generated calls choose between, called from javac to tell the expected result.
     */
    public static final class Calls {
        private Calls() {
        }

        /**
         * @param box The box
         * @return The overload
         */
        public static String boxed(Object box) {
            return "object";
        }

        /**
         * @param box The box
         * @param <T> The element type
         * @return The overload
         */
        public static <T extends Number> String boxed(NumberBox<T> box) {
            return "box";
        }

        /**
         * @param value The value
         * @param <T>   The type
         * @return The value
         */
        public static <T extends Number> T identity(T value) {
            return value;
        }
    }
}
