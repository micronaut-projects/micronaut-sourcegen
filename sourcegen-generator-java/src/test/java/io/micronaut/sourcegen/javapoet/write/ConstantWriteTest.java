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
package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constants of the model written as Java literals: characters, bytes and shorts, non-finite floating point values,
 * arrays, enums and class objects, and constants used as receivers. Every program is compiled and run; the expected
 * results are what the bytecode writer's class does.
 */
public class ConstantWriteTest {

    /**
     * A method returning an expression of constants returns the value the bytecode writer's class returns. Each case
     * names the literal the source used to write wrongly.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("returnedConstants")
    void constantIsReturnedAsTheBytecodeReturnsIt(ConstantCase constant) throws Exception {
        var def = single(constant.className(), constant.returns(), List.of(), (self, p) -> constant.value().returning());
        assertEquals(constant.expected(), run(def));
    }

    /**
     * An array of constants, whose elements are written as literals of their own, is returned with the elements the
     * bytecode writer's array has.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("returnedArrayConstants")
    void arrayConstantIsReturnedWithItsElements(ConstantCase constant) throws Exception {
        var def = single(constant.className(), constant.returns(), List.of(), (self, p) -> constant.value().returning());
        // Wrapped, so that the arrays are compared element by element whatever their component type
        assertArrayEquals(new Object[] {constant.expected()}, new Object[] {run(def)});
    }

    static Stream<ConstantCase> returnedConstants() throws NoSuchMethodException {
        var byteToString = Byte.class.getMethod("toString", byte.class);
        var intValue = Integer.class.getMethod("intValue");
        return Stream.of(
            new ConstantCase("a char constant is a char literal, not the bare character: return a;",
                "CharConstant", TypeDef.Primitive.CHAR, ExpressionDef.constant('a'), 'a'),
            new ConstantCase("a boxed Character constant is a char literal, not return x;",
                "BoxedCharConstant", TypeDef.OBJECT, ExpressionDef.constant((Object) Character.valueOf('x')), 'x'),
            new ConstantCase("a byte constant passed to a byte parameter is narrowed, not the int literal of Byte.toString(7)",
                "ByteConstantArgument", TypeDef.STRING,
                ClassTypeDef.of(Byte.class).invokeStatic(byteToString, new ExpressionDef.Constant(TypeDef.Primitive.BYTE, (byte) 7)),
                "7"),
            new ConstantCase("a boxed Short constant boxes as a Short, not the Integer of 3",
                "BoxedShortConstant", TypeDef.OBJECT, ExpressionDef.constant((Object) Short.valueOf((short) 3)), (short) 3),
            new ConstantCase("a byte constant cast to Object boxes as a Byte",
                "ByteConstantBox", TypeDef.OBJECT, ExpressionDef.primitiveConstant((byte) 5).cast(TypeDef.OBJECT), (byte) 5),
            new ConstantCase("a NaN double constant compiles, not as NaNd",
                "NotANumber", TypeDef.Primitive.DOUBLE, ExpressionDef.constant(Double.NaN), Double.NaN),
            new ConstantCase("a method called on a boxed Integer constant parses, unlike 5.intValue()",
                "BoxedConstantReceiver", TypeDef.Primitive.INT, ExpressionDef.constant((Object) 5).invoke(intValue), 5),
            new ConstantCase("the hash code of a boxed constant is called on the box",
                "BoxedConstantHashCode", TypeDef.Primitive.INT, ExpressionDef.constant((Object) 5).invokeHashCode(), 5),
            new ConstantCase("a multi-line String constant is parenthesized as a receiver",
                "MultiLineReceiver", TypeDef.STRING, ExpressionDef.constant("line\nbreak").invoke("toUpperCase", TypeDef.STRING),
                "LINE\nBREAK"),
            new ConstantCase("a constant of a Class object is a class literal, not class java.lang.String",
                "ClassObjectConstant", TypeDef.OBJECT, ExpressionDef.constant(String.class), String.class),
            new ConstantCase("a String constant typed as a CharSequence is quoted, not return abc;",
                "CharSequenceConstant", TypeDef.OBJECT, new ExpressionDef.Constant(TypeDef.of(CharSequence.class), "abc"), "abc"),
            new ConstantCase("an enum constant with a body, of an anonymous class that is no enum, is not written as its bare name",
                "EnumBodyConstant", TypeDef.OBJECT, ExpressionDef.constant(Mode.A), Mode.A),
            new ConstantCase("the negation of a negative constant is not the decrement --1",
                "NegatedNegative", TypeDef.Primitive.INT,
                ExpressionDef.constant(-1).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE), 1),
            new ConstantCase("a boxed Integer constant cast to long is converted, not the 5.longValue() that does not parse",
                "BoxedConstantToLong", TypeDef.Primitive.LONG, ExpressionDef.constant((Object) 5).cast(TypeDef.Primitive.LONG), 5L),
            new ConstantCase("a negative boxed Integer constant cast to long is converted, not (-2147483648).longValue()",
                "NegativeBoxedConstantToLong", TypeDef.Primitive.LONG,
                ExpressionDef.constant((Object) Integer.MIN_VALUE).cast(TypeDef.Primitive.LONG), (long) Integer.MIN_VALUE),
            new ConstantCase("a boxed Character constant cast to int is converted, not 'a'.charValue()",
                "BoxedCharConstantToInt", TypeDef.Primitive.INT, ExpressionDef.constant((Object) 'a').cast(TypeDef.Primitive.INT), 97)
        );
    }

    /**
     * An unpaired surrogate has no UTF-8 encoding, so a source file cannot hold it as it is: the literal escapes it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("surrogateConstants")
    void unpairedSurrogateIsEscaped(ConstantCase constant) throws Exception {
        var def = single(constant.className(), constant.returns(), List.of(), (self, p) -> constant.value().returning());
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(render(def)), "The source is not encodable as UTF-8");
        assertEquals(constant.expected(), run(def));
    }

    static Stream<ConstantCase> surrogateConstants() {
        return Stream.of(
            new ConstantCase("a char constant of an unpaired surrogate", "SurrogateCharConstant", TypeDef.Primitive.CHAR,
                ExpressionDef.constant('\ud83d'), '\ud83d'),
            new ConstantCase("a String constant holding an unpaired surrogate", "SurrogateStringConstant", TypeDef.STRING,
                ExpressionDef.constant("a\ud83db"), "a\ud83db")
        );
    }

    static Stream<ConstantCase> returnedArrayConstants() {
        var quote = ExpressionDef.constant('\'');
        return Stream.of(
            new ConstantCase("the elements of a char[] constant are char literals, not new char[] {a, b}",
                "CharArrayConstant", TypeDef.OBJECT, ExpressionDef.constant(new char[] {'a', 'b'}), new char[] {'a', 'b'}),
            new ConstantCase("quote char elements of an initialized char[] are escaped",
                "CharConstants", TypeDef.OBJECT,
                new ExpressionDef.NewArrayInitialized(TypeDef.Primitive.CHAR.array(), List.of(quote, quote)),
                new char[] {'\'', '\''}),
            new ConstantCase("NaN and infinity elements are not written NaNd and Infinityf",
                "NonFinite", TypeDef.OBJECT,
                TypeDef.OBJECT.array().instantiate(ExpressionDef.constant(Double.NaN), ExpressionDef.constant(Float.POSITIVE_INFINITY)),
                new Object[] {Double.NaN, Float.POSITIVE_INFINITY}),
            new ConstantCase("the elements of a two-dimensional array constant are arrays, not new int[] {[I@1b2c}",
                "NestedArrayConstant", TypeDef.OBJECT, ExpressionDef.constant(new int[][] {{1, 2}, {3}}), new int[][] {{1, 2}, {3}}),
            new ConstantCase("an array constant of a nested enum names it, not new State[] {...}",
                "NestedEnumArrayConstant", TypeDef.OBJECT, ExpressionDef.constant(new Thread.State[] {Thread.State.NEW}),
                new Thread.State[] {Thread.State.NEW})
        );
    }

    /**
     * A constant expression returned from the method {@code call} of the class {@code test.<className>}.
     *
     * @param description What the case shows
     * @param className The simple name of the class
     * @param returns The return type of the method
     * @param value The returned expression
     * @param expected The value the bytecode writer's class returns
     */
    record ConstantCase(String description, String className, TypeDef returns, ExpressionDef value, Object expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * An enum with a constant that has a body.
     *
     * @since 2.3
     */
    public enum Mode {
        /** A constant with a body. */
        A {
            @Override
            public String toString() {
                return "a";
            }
        },
        /** A plain constant. */
        B
    }
}
