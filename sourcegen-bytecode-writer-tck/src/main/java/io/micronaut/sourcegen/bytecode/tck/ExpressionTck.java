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

import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Operators and array expressions: numeric promotion of arithmetic and shifts, structural and referential
 * equality, and array creation and access, compared with what javac writes for the same source.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
public abstract class ExpressionTck extends AbstractByteCodeWriterTck {

    @Test
    public void byteArithmeticIsNarrowedToTheTypeOfTheOperation() throws Exception {
        // (Object) (byte) (a + b): the model types the sum as byte
        Method run = run("test.hardening.ByteSum", TypeDef.OBJECT, List.of(TypeDef.Primitive.BYTE, TypeDef.Primitive.BYTE), (self, p) ->
            p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).cast(TypeDef.OBJECT).returning());
        assertEquals((byte) -56, invoke(run, (byte) 100, (byte) 100));
    }

    @Test
    public void characterUnboxesBeforeWideningToInt() throws Exception {
        // (int) c for a Character c: unboxing then widening, legal in Java
        Method run = run("test.hardening.CharacterToInt", TypeDef.Primitive.INT, List.of(TypeDef.of(Character.class)), (self, p) ->
            p.get(0).cast(TypeDef.Primitive.INT).returning());
        assertEquals(97, invoke(run, 'a'));
    }

    @Test
    public void notEqualsReferentiallyAcceptsAPrimitiveOperand() throws Exception {
        // EqualsReferentially boxes its operands, NotEqualsReferentially does not
        Method run = run("test.hardening.NotSame", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.Primitive.INT, TypeDef.of(Integer.class)), (self, p) ->
            p.get(0).notEqualsReferentially(p.get(1)).returning());
        assertEquals(true, invoke(run, 5, 7));
    }

    @Test
    public void newArrayOfSizeKeepsEveryDimension() throws Exception {
        // new int[2][]
        Method run = run("test.hardening.TwoDimensions", TypeDef.OBJECT, List.of(), (self, p) ->
            new ExpressionDef.NewArrayOfSize(TypeDef.array(TypeDef.Primitive.INT, 2), 2).returning());
        assertSame(int[][].class, invoke(run).getClass());
    }

    @Test
    public void longShiftTakesAnIntDistance() throws Exception {
        // a << b for a long a: the model converts the distance to long, the JVM shifts by an int
        Method run = run("test.hardening.LongShift", TypeDef.Primitive.LONG, List.of(TypeDef.Primitive.LONG, TypeDef.Primitive.INT), (self, p) ->
            p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT, p.get(1)).returning());
        assertEquals(1L << 40, invoke(run, 1L, 40));
    }

    @Test
    public void equalsStructurallyComparesFloatsNumerically() throws Exception {
        // a == b for two floats: NaN is not equal to itself
        Method run = run("test.hardening.FloatEquals", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.Primitive.FLOAT, TypeDef.Primitive.FLOAT), (self, p) ->
            p.get(0).equalsStructurally(p.get(1)).returning());
        assertEquals(false, invoke(run, Float.NaN, Float.NaN));
    }

    @Test
    public void elementOfATwoDimensionalArrayIsAnArray() throws Exception {
        // int[] row = m[0]
        Method run = run("test.hardening.MatrixRow", TypeDef.Primitive.INT.array(), List.of(TypeDef.array(TypeDef.Primitive.INT, 2)), (self, p) ->
            p.get(0).arrayElement(0).returning());
        int[] row = {1, 2};
        assertSame(row, invoke(run, (Object) new int[][]{row}));
    }

    @ParameterizedTest(name = "{0} {1} with NaN operands matches javac, as a value and as a branch")
    @MethodSource("floatingPointComparisons")
    public void floatingPointComparisonOfNaNMatchesJavac(TypeDef.Primitive type, ExpressionDef.ComparisonOperation.OpType operation) throws Exception {
        // boolean value(T a, T b) { return a < b; }  int branch(T a, T b) { return a < b ? 1 : 0; }
        Method value = run("test.hardening.NaNValue", TypeDef.Primitive.BOOLEAN, List.of(type, type), (self, p) ->
            p.get(0).compare(operation, p.get(1)).returning());
        Method branch = run("test.hardening.NaNBranch", TypeDef.Primitive.INT, List.of(type, type), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).compare(operation, p.get(1)), ExpressionDef.constant(1), ExpressionDef.constant(0),
                TypeDef.Primitive.INT).returning());
        double[] samples = {Double.NaN, -1, 0, 1};
        for (double left : samples) {
            for (double right : samples) {
                boolean expected = javacCompares(operation, left, right);
                Object a = type == TypeDef.Primitive.FLOAT ? (Object) (float) left : (Object) left;
                Object b = type == TypeDef.Primitive.FLOAT ? (Object) (float) right : (Object) right;
                assertEquals(expected, invoke(value, a, b), left + " " + operation + " " + right);
                assertEquals(expected ? 1 : 0, invoke(branch, a, b), left + " " + operation + " " + right);
            }
        }
    }

    private static Stream<Arguments> floatingPointComparisons() {
        return Stream.of(TypeDef.Primitive.FLOAT, TypeDef.Primitive.DOUBLE)
            .flatMap(type -> Arrays.stream(ExpressionDef.ComparisonOperation.OpType.values())
                .map(operation -> Arguments.of(Named.of(type.name(), type), operation)));
    }

    private static boolean javacCompares(ExpressionDef.ComparisonOperation.OpType operation, double left, double right) {
        return switch (operation) {
            case EQUAL_TO -> left == right;
            case NOT_EQUAL_TO -> left != right;
            case LESS_THAN -> left < right;
            case LESS_THAN_OR_EQUAL -> left <= right;
            case GREATER_THAN -> left > right;
            case GREATER_THAN_OR_EQUAL -> left >= right;
        };
    }

    @ParameterizedTest(name = "{0} == and != of primitives compare their values as javac promotes them")
    @MethodSource("primitiveEqualities")
    public void referentialEqualityOfPrimitivesComparesThePromotedValues(String name, TypeDef.Primitive left, TypeDef.Primitive right,
                                                                       List<?> leftValues, List<?> rightValues) throws Exception {
        // boolean same(L a, R b) { return a == b; }  boolean notSame(L a, R b) { return a != b; }
        Method same = run("test.hardening.PrimitiveSame", TypeDef.Primitive.BOOLEAN, List.of(left, right), (self, p) ->
            p.get(0).equalsReferentially(p.get(1)).returning());
        Method notSame = run("test.hardening.PrimitiveNotSame", TypeDef.Primitive.BOOLEAN, List.of(left, right), (self, p) ->
            p.get(0).notEqualsReferentially(p.get(1)).returning());
        Method same2 = run("test.hardening.PrimitiveSameBranch", TypeDef.Primitive.INT, List.of(left, right), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).equalsReferentially(p.get(1)), ExpressionDef.constant(1), ExpressionDef.constant(0),
                TypeDef.Primitive.INT).returning());
        Method javacEquals = OperatorFixtures.class.getMethod(name + "Equals" + capitalize(right.name()), same.getParameterTypes());
        Method javacNotEquals = OperatorFixtures.class.getMethod(name + "NotEquals" + capitalize(right.name()), same.getParameterTypes());
        for (Object a : leftValues) {
            for (Object b : rightValues) {
                boolean expected = (boolean) javacEquals.invoke(null, a, b);
                assertEquals(expected, invoke(same, a, b), a + " == " + b);
                assertEquals(expected ? 1 : 0, invoke(same2, a, b), a + " == " + b);
                assertEquals(javacNotEquals.invoke(null, a, b), invoke(notSame, a, b), a + " != " + b);
            }
        }
    }

    @Test
    public void structuralEqualityOfAnObjectAndACharCastsTheObjectToCharacter() throws Exception {
        // boolean run(Object o, char c) { return (char) o == c; } - an Integer is no Character
        Method run = run("test.hardening.ObjectEqualsChar", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.OBJECT, TypeDef.Primitive.CHAR),
            (self, p) -> p.get(0).equalsStructurally(p.get(1)).returning());
        assertEquals(true, invoke(run, 'a', 'a'));
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> invoke(run, 97, 'a'));
        assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    private static Stream<Arguments> primitiveEqualities() {
        List<Object> ints = List.of(0, 97, -1, 1000, 16_777_217, Integer.MAX_VALUE);
        List<Object> chars = List.of('\0', 'a', (char) 1000, '￿');
        List<Object> bytes = List.of((byte) 0, (byte) 97, (byte) -1);
        List<Object> longs = List.of(0L, 97L, -1L, 16_777_217L, (long) Integer.MAX_VALUE, Long.MAX_VALUE);
        List<Object> floats = List.of(0f, 97f, -1f, 16_777_216f, Float.NaN);
        List<Object> doubles = List.of(0d, -0d, 97d, -1d, 1000d, 16_777_217d, Double.NaN);
        return Stream.of(
            equality("int", TypeDef.Primitive.INT, ints, TypeDef.Primitive.CHAR, chars),
            equality("char", TypeDef.Primitive.CHAR, chars, TypeDef.Primitive.BYTE, bytes),
            equality("double", TypeDef.Primitive.DOUBLE, doubles, TypeDef.Primitive.INT, ints),
            equality("float", TypeDef.Primitive.FLOAT, floats, TypeDef.Primitive.INT, ints),
            equality("long", TypeDef.Primitive.LONG, longs, TypeDef.Primitive.FLOAT, floats),
            equality("byte", TypeDef.Primitive.BYTE, bytes, TypeDef.Primitive.DOUBLE, doubles),
            equality("int", TypeDef.Primitive.INT, ints, TypeDef.Primitive.LONG, longs),
            equality("int", TypeDef.Primitive.INT, ints, TypeDef.Primitive.INT, ints),
            equality("double", TypeDef.Primitive.DOUBLE, doubles, TypeDef.Primitive.DOUBLE, doubles)
        );
    }

    private static Arguments equality(String name, TypeDef.Primitive left, List<Object> leftValues,
                                      TypeDef.Primitive right, List<Object> rightValues) {
        return Arguments.of(Named.of(name + " and " + right.name(), name), left, right, leftValues, rightValues);
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @ParameterizedTest(name = "-{0} unboxes, negates and boxes the {1}")
    @MethodSource("boxedNegations")
    public void negationOfABoxedOperandUnboxesIt(TypeDef.Primitive primitive, Object value, Object negated) throws Exception {
        // Object negate(Integer a) { return -a; }
        ClassTypeDef wrapper = primitive.wrapperType();
        Method run = run("test.hardening.BoxedNegation", TypeDef.OBJECT, List.of(wrapper), (self, p) ->
            p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).cast(TypeDef.OBJECT).returning());
        assertEquals(negated, invoke(run, value));
        InvocationTargetException nullOperand = assertThrows(InvocationTargetException.class, () -> invoke(run, (Object) null));
        assertInstanceOf(NullPointerException.class, nullOperand.getCause());
    }

    private static Stream<Arguments> boxedNegations() {
        return Stream.of(
            Arguments.of(Named.of("Integer", TypeDef.Primitive.INT), 5, -5),
            Arguments.of(Named.of("Long", TypeDef.Primitive.LONG), Long.MIN_VALUE, Long.MIN_VALUE),
            Arguments.of(Named.of("Float", TypeDef.Primitive.FLOAT), 0.5f, -0.5f),
            Arguments.of(Named.of("Double", TypeDef.Primitive.DOUBLE), 2.5, -2.5),
            // The model types the negation as its operand, which a byte, a short or a char is narrowed to
            Arguments.of(Named.of("Short", TypeDef.Primitive.SHORT), (short) 7, (short) -7),
            Arguments.of(Named.of("Character", TypeDef.Primitive.CHAR), 'a', (char) -'a')
        );
    }
}
