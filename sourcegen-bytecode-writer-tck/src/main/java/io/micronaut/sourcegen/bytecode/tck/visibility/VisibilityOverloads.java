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
package io.micronaut.sourcegen.bytecode.tck.visibility;

/**
 * Access checks are performed from the TCK's different package, just as for generated callers.
 * @since 2.3
 */
public final class VisibilityOverloads {
    private VisibilityOverloads() {
    }

    public static String packageChoice(Object value) {
        return "public";
    }

    static String packageChoice(String value) {
        return "package";
    }

    public static String protectedChoice(Object value) {
        return "public";
    }

    protected static String protectedChoice(String value) {
        return "protected";
    }

    public static String siblingChoice(Object value) {
        return "object";
    }

    public static String siblingChoice(Comparable<?> value) {
        return "comparable";
    }

    static String siblingChoice(CharSequence value) {
        return "package";
    }

    /** Constructor overloads with package access.
     * @since 2.3
     */
    public static class PackageConstructor {
        private final String selected;

        public PackageConstructor(Object value) {
            selected = "public";
        }

        PackageConstructor(String value) {
            selected = "package";
        }

        /** Returns the selected constructor.
         * @return The selected overload
         */
        public String selected() {
            return selected;
        }
    }

    /** Constructor overloads with protected access.
     * @since 2.3
     */
    public static class ProtectedConstructor {
        private final String selected;

        public ProtectedConstructor(Object value) {
            selected = "public";
        }

        protected ProtectedConstructor(String value) {
            selected = "protected";
        }

        /** Returns the selected constructor.
         * @return The selected overload
         */
        public String selected() {
            return selected;
        }
    }
}
