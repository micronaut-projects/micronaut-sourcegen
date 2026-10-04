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
 * Compiled fixtures: javac's {@code ==} and {@code !=} of two primitives, which promotes both operands to a common type
 * (binary numeric promotion) - a {@code long} compared with a {@code float} is compared as a {@code float}. A generated
 * referential comparison of the same operands must answer the same.
 *
 * @since 2.3
 */
@SuppressWarnings("unused")
public final class OperatorFixtures {

    private OperatorFixtures() {
    }

    public static boolean intEqualsChar(int left, char right) {
        return left == right;
    }

    public static boolean intNotEqualsChar(int left, char right) {
        return left != right;
    }

    public static boolean charEqualsByte(char left, byte right) {
        return left == right;
    }

    public static boolean charNotEqualsByte(char left, byte right) {
        return left != right;
    }

    public static boolean doubleEqualsInt(double left, int right) {
        return left == right;
    }

    public static boolean doubleNotEqualsInt(double left, int right) {
        return left != right;
    }

    public static boolean floatEqualsInt(float left, int right) {
        return left == right;
    }

    public static boolean floatNotEqualsInt(float left, int right) {
        return left != right;
    }

    public static boolean longEqualsFloat(long left, float right) {
        return left == right;
    }

    public static boolean longNotEqualsFloat(long left, float right) {
        return left != right;
    }

    public static boolean byteEqualsDouble(byte left, double right) {
        return left == right;
    }

    public static boolean byteNotEqualsDouble(byte left, double right) {
        return left != right;
    }

    public static boolean intEqualsLong(int left, long right) {
        return left == right;
    }

    public static boolean intNotEqualsLong(int left, long right) {
        return left != right;
    }

    public static boolean intEqualsInt(int left, int right) {
        return left == right;
    }

    public static boolean intNotEqualsInt(int left, int right) {
        return left != right;
    }

    public static boolean doubleEqualsDouble(double left, double right) {
        return left == right;
    }

    public static boolean doubleNotEqualsDouble(double left, double right) {
        return left != right;
    }
}
