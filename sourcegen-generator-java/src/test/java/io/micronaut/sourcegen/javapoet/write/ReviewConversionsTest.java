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

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType;
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation;
import io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.lang.annotation.RetentionPolicy;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Review probes of the value conversions, casts and overload selection of the Java generator: each model is one the
 * bytecode writer accepts, and the assertion is the behaviour the bytecode has - a value is converted to the declared
 * type wherever it is passed, returned, stored or yielded, and the invoked overload is the one the model names.
 *
 * @since 2.2.2
 */
public class ReviewConversionsTest {

    // ---- primitive casts and boxing

    // `(char) p0` of an int.
    @Test
    void intCastToChar() throws Exception {
        var def = single("IntToChar", TypeDef.Primitive.CHAR, List.of(TypeDef.Primitive.INT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.CHAR).returning());
        assertEquals('a', run(def, 97));
    }

    // `(int) p0` of a char.
    @Test
    void charCastToInt() throws Exception {
        var def = single("CharToInt", TypeDef.Primitive.INT, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).returning());
        assertEquals(97, run(def, 'a'));
    }

    // A narrowing cast of a constant overflows as the bytecode's I2B does.
    @Test
    void intConstantCastToByteOverflows() throws Exception {
        var def = single("ByteOverflow", TypeDef.Primitive.BYTE, List.of(),
            (self, p) -> ExpressionDef.constant(200).cast(TypeDef.Primitive.BYTE).returning());
        assertEquals((byte) -56, run(def));
    }

    // Two primitive casts in a row keep both: `(long) (char) p0` truncates before it widens.
    @Test
    void longCastToCharAndBack() throws Exception {
        var def = single("LongCharLong", TypeDef.Primitive.LONG, List.of(TypeDef.Primitive.LONG),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.CHAR).cast(TypeDef.Primitive.LONG).returning());
        assertEquals(65L, run(def, 65601L));
    }

    // `(int) p0` of a double truncates.
    @Test
    void doubleCastToInt() throws Exception {
        var def = single("DoubleToInt", TypeDef.Primitive.INT, List.of(TypeDef.Primitive.DOUBLE),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).returning());
        assertEquals(3, run(def, 3.99));
    }

    // A byte passed to `Character.toString(char)`: the bytecode converts it (I2C); `Character.toString(int)` is another overload.
    @Test
    void bytePassedToCharParameterKeepsCharOverload() throws Exception {
        var toString = Character.class.getMethod("toString", char.class);
        var def = single("ByteToCharParam", TypeDef.STRING, List.of(TypeDef.Primitive.BYTE),
            (self, p) -> ClassTypeDef.of(Character.class).invokeStatic(toString, p.get(0)).returning());
        assertEquals("A", run(def, (byte) 65));
    }

    // A char passed to `Short.toString(short)`: the bytecode converts it (I2S); Java does not widen char to short.
    @Test
    void charPassedToShortParameterIsNarrowed() throws Exception {
        var toString = Short.class.getMethod("toString", short.class);
        var def = single("CharToShortParam", TypeDef.STRING, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> ClassTypeDef.of(Short.class).invokeStatic(toString, p.get(0)).returning());
        assertEquals("65", run(def, 'A'));
    }

    // A char passed to `Long.toString(long)` widens without a cast.
    @Test
    void charPassedToLongParameterWidens() throws Exception {
        var toString = Long.class.getMethod("toString", long.class);
        var def = single("CharToLongParam", TypeDef.STRING, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> ClassTypeDef.of(Long.class).invokeStatic(toString, p.get(0)).returning());
        assertEquals("65", run(def, 'A'));
    }

    // An int cast to `Number` is boxed: `(Number) p0`.
    @Test
    void intCastToNumberIsBoxed() throws Exception {
        var def = single("IntToNumber", TypeDef.of(Number.class), List.of(TypeDef.Primitive.INT),
            (self, p) -> p.get(0).cast(TypeDef.of(Number.class)).returning());
        assertEquals(5, run(def, 5));
    }

    // An int cast to `Comparable<Integer>` is boxed, then widened as a reference.
    @Test
    void intCastToComparableIsBoxed() throws Exception {
        var comparable = TypeDef.parameterized(Comparable.class, Integer.class);
        var def = single("IntToComparable", comparable, List.of(TypeDef.Primitive.INT),
            (self, p) -> p.get(0).cast(comparable).returning());
        assertEquals(5, run(def, 5));
    }

    // An `Object` cast to `char` unboxes a `Character`, as the bytecode's `Character.charValue` does.
    @Test
    void objectCastToChar() throws Exception {
        var def = single("ObjectToChar", TypeDef.Primitive.CHAR, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.CHAR).returning());
        assertEquals('a', run(def, 'a'));
    }

    // An `Object` cast to `boolean` unboxes a `Boolean`.
    @Test
    void objectCastToBoolean() throws Exception {
        var def = single("ObjectToBoolean", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.BOOLEAN).returning());
        assertEquals(true, run(def, true));
    }

    // An `Object` cast to `double` goes through `Number`, so an `Integer` converts as in bytecode.
    @Test
    void objectCastToDoubleUnboxesAnInteger() throws Exception {
        var def = single("ObjectToDouble", TypeDef.Primitive.DOUBLE, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.DOUBLE).returning());
        assertEquals(5.0, run(def, 5));
    }

    // A `Long` cast to `int` is `p0.intValue()`, which narrows.
    @Test
    void longWrapperCastToInt() throws Exception {
        var def = single("LongWrapperToInt", TypeDef.Primitive.INT, List.of(TypeDef.of(Long.class)),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).returning());
        assertEquals(5, run(def, 5L));
    }

    // ---- an Object unboxed in every context

    // `((Number) p0).intValue() + 1`.
    @Test
    void objectUnboxedInMath() throws Exception {
        var def = single("UnboxedMath", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).math(MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1)).returning());
        assertEquals(42, run(def, 41));
    }

    // An `Object` as the index of an array element.
    @Test
    void objectUnboxedAsArrayIndex() throws Exception {
        var def = single("UnboxedIndex", TypeDef.STRING, List.of(TypeDef.OBJECT, TypeDef.STRING.array()),
            (self, p) -> p.get(1).arrayElement(p.get(0).cast(TypeDef.Primitive.INT)).returning());
        assertEquals("b", run(def, 1, new String[]{"a", "b"}));
    }

    // An `Object` as the selector of a switch expression.
    @Test
    void objectUnboxedAsSwitchSelector() throws Exception {
        var def = single("UnboxedSelector", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).asExpressionSwitch(TypeDef.STRING,
                Map.of(ExpressionDef.constant(1), ExpressionDef.constant("one")), ExpressionDef.constant("other")).returning());
        assertEquals("one", run(def, 1));
        assertEquals("other", run(def, 2));
    }

    // An `Object` as the condition of an `if`.
    @Test
    void objectUnboxedAsIfCondition() throws Exception {
        var def = single("UnboxedIf", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.BOOLEAN).isTrue()
                .doIfElse(ExpressionDef.constant("yes").returning(), ExpressionDef.constant("no").returning()));
        assertEquals("yes", run(def, true));
        assertEquals("no", run(def, false));
    }

    // An `Object` as an operand of a numeric comparison.
    @Test
    void objectUnboxedInComparison() throws Exception {
        var def = single("UnboxedComparison", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.INT).compare(OpType.GREATER_THAN, ExpressionDef.constant(0))
                .doIfElse(ExpressionDef.constant("pos"), ExpressionDef.constant("neg")).returning());
        assertEquals("pos", run(def, 5));
        assertEquals("neg", run(def, -5));
    }

    // An `Object` as the bound of a loop condition, and a local reassigned with math.
    @Test
    void objectUnboxedInLoopCondition() throws Exception {
        var def = single("UnboxedLoop", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT), (self, p) -> {
            var i = new VariableDef.Local("i", TypeDef.Primitive.INT);
            return StatementDef.multi(
                i.defineAndAssign(ExpressionDef.constant(0)),
                i.compare(OpType.LESS_THAN, p.get(0).cast(TypeDef.Primitive.INT))
                    .whileLoop(i.assign(i.math(MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1)))),
                i.returning());
        });
        assertEquals(3, run(def, 3));
    }

    // An `Object` unboxed as an operand of a concatenation.
    @Test
    void objectUnboxedInConcatenation() throws Exception {
        var def = single("UnboxedConcat", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ExpressionDef.constant("n=").stringConcat(p.get(0).cast(TypeDef.Primitive.INT)).returning());
        assertEquals("n=5", run(def, 5));
    }

    // An `Object` returned from an `int` method without a cast in the model.
    @Test
    void objectReturnedFromIntMethod() throws Exception {
        var def = single("ObjectAsInt", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT), (self, p) -> p.get(0).returning());
        assertEquals(7, run(def, 7));
    }

    // An `Object` yielded by a block case of an `int` switch.
    @Test
    void objectYieldedInIntSwitch() throws Exception {
        var def = single("UnboxedYield", TypeDef.Primitive.INT, List.of(TypeDef.Primitive.INT, TypeDef.OBJECT),
            (self, p) -> p.get(0).asExpressionSwitch(TypeDef.Primitive.INT,
                Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.Primitive.INT, p.get(1).returning())),
                ExpressionDef.constant(-1)).returning());
        assertEquals(9, run(def, 1, 9));
    }

    // `equalsStructurally` of an `Object` and an `int`: the bytecode unboxes the object (`(int) p0 == 5`); the source
    // writes `p0 == 5`, which javac rejects (bad operand types Object and int).
    @Test
    void objectComparedStructurallyToAnInt() throws Exception {
        var def = single("ObjectEqualsInt", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).equalsStructurally(ExpressionDef.constant(5)).returning());
        assertEquals(true, run(def, 5));
        assertEquals(false, run(def, 6));
    }

    // `equalsStructurally` of an `Integer` and an `int` unboxes.
    @Test
    void integerComparedStructurallyToAnInt() throws Exception {
        var def = single("IntegerEqualsInt", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.of(Integer.class), TypeDef.Primitive.INT),
            (self, p) -> p.get(0).equalsStructurally(p.get(1)).returning());
        assertEquals(true, run(def, 1000, 1000));
    }

    // `equalsReferentially` of two `Integer`s compares the references, as IF_ACMP does.
    @Test
    void integersComparedReferentially() throws Exception {
        var def = single("IntegerSame", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.of(Integer.class), TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).equalsReferentially(p.get(1)).returning());
        Integer shared = 1000;
        assertEquals(true, run(def, shared, shared));
        assertEquals(false, run(def, Integer.valueOf(1000), Integer.valueOf(1000)));
    }

    // ---- null and overloads

    // `StringBuilder.append(Object)` with `null`: `append(null)` would be ambiguous.
    @Test
    void nullPassedToStringBuilderAppendObject() throws Exception {
        var append = StringBuilder.class.getMethod("append", Object.class);
        var toString = StringBuilder.class.getMethod("toString");
        var def = single("AppendNullObject", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(StringBuilder.class).instantiate().invoke(append, ExpressionDef.nullValue()).invoke(toString).returning());
        assertEquals("null", run(def));
    }

    // `StringBuilder.append(CharSequence)` with `null`.
    @Test
    void nullPassedToStringBuilderAppendCharSequence() throws Exception {
        var append = StringBuilder.class.getMethod("append", CharSequence.class);
        var toString = StringBuilder.class.getMethod("toString");
        var def = single("AppendNullSequence", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(StringBuilder.class).instantiate().invoke(append, ExpressionDef.nullValue()).invoke(toString).returning());
        assertEquals("null", run(def));
    }

    // A conditional typed `Integer` with a `null` branch and an `Object` branch.
    @Test
    void nullInAConditionalBranchTypedInteger() throws Exception {
        var def = single("NullBranch", TypeDef.of(Integer.class), List.of(TypeDef.OBJECT),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isNull(), ExpressionDef.nullValue(), p.get(0), TypeDef.of(Integer.class)).returning());
        assertEquals(5, run(def, 5));
        assertNull(run(def, (Object) null));
    }

    // ---- JDK overloads with Object and primitive values

    // `Math.max(long, long)` with ints: `max(int, int)` is another overload.
    @Test
    void intsPassedToMathMaxOfLongs() throws Exception {
        var max = Math.class.getMethod("max", long.class, long.class);
        var def = single("MaxLong", TypeDef.Primitive.LONG, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(Math.class).invokeStatic(max, p.get(0), p.get(1)).returning());
        assertEquals(2L, run(def, 1, 2));
    }

    // Known limit ("a value that is not an array passed for varargs stays one element"): `Objects.hash(Object...)` with an
    // `Object` that is an `Object[]` at runtime. The bytecode passes it as the array (checkcast Object[]), the source as
    // one element - silently another hash.
    @Test
    void objectPassedToObjectsHashVarargsIsTheArray() throws Exception {
        var hash = Objects.class.getMethod("hash", Object[].class);
        var def = single("HashVarargs", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Objects.class).invokeStatic(hash, p.get(0)).returning());
        assertEquals(Objects.hash(1, 2), run(def, (Object) new Object[]{1, 2}));
    }

    // An `Object[]` passed to `Arrays.asList(T...)` is the array.
    @Test
    void objectArrayPassedToArraysAsList() throws Exception {
        var asList = Arrays.class.getMethod("asList", Object[].class);
        var def = single("AsListArray", TypeDef.of(List.class), List.of(TypeDef.OBJECT.array()),
            (self, p) -> ClassTypeDef.of(Arrays.class).invokeStatic(asList, p.get(0)).returning());
        assertEquals(List.of("a", "b"), run(def, (Object) new Object[]{"a", "b"}));
    }

    // A `String[]` passed to `Object...` is the array.
    @Test
    void stringArrayPassedToObjectVarargs() throws Exception {
        var size = Fixtures.class.getMethod("size", Object[].class);
        var def = single("StringArrayVarargs", TypeDef.Primitive.INT, List.of(TypeDef.STRING.array()),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(size, p.get(0)).returning());
        assertEquals(2, run(def, (Object) new String[]{"a", "b"}));
    }

    // Known limit (as above, for `String...`): the bytecode casts the `Object` to `String[]`; the source passes an `Object`
    // where a `String` element is expected, which does not compile.
    @Test
    void objectPassedToStringVarargsIsTheArray() throws Exception {
        var joined = Fixtures.class.getMethod("joined", String[].class);
        var def = single("ObjectStringVarargs", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(joined, p.get(0)).returning());
        assertEquals("ab", run(def, (Object) new String[]{"a", "b"}));
    }

    // `null` passed for varargs is the array, which is `null`: both throw on `values.length`.
    @Test
    void nullPassedToVarargsIsANullArray() throws Exception {
        var size = Fixtures.class.getMethod("size", Object[].class);
        var def = single("NullVarargs", TypeDef.Primitive.INT, List.of(),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(size, ExpressionDef.nullValue()).returning());
        assertThrows(NullPointerException.class, () -> run(def));
    }

    // An initialized array of several elements passed for varargs.
    @Test
    void arrayOfManyElementsPassedToVarargs() throws Exception {
        var size = Fixtures.class.getMethod("size", Object[].class);
        var def = single("ManyVarargs", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT, TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(size, TypeDef.OBJECT.array().instantiate(p.get(0), p.get(1), p.get(2))).returning());
        assertEquals(3, run(def, "a", 1, null));
    }

    // `EnumSet.of(E, E)` with `Object` values: cast to the erased bound `Enum`.
    @Test
    void objectsPassedToEnumSetOf() throws Exception {
        var of = EnumSet.class.getMethod("of", Enum.class, Enum.class);
        var def = single("EnumSetOf", TypeDef.of(Set.class), List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(EnumSet.class).invokeStatic(of, p.get(0), p.get(1)).returning());
        assertEquals(EnumSet.of(RetentionPolicy.RUNTIME, RetentionPolicy.SOURCE), run(def, RetentionPolicy.RUNTIME, RetentionPolicy.SOURCE));
    }

    // `Collections.max(Collection<? extends T>)` with an `Object`, through the raw type.
    @Test
    void objectPassedToCollectionsMax() throws Exception {
        var max = Collections.class.getMethod("max", Collection.class);
        var def = single("CollectionsMax", TypeDef.OBJECT, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Collections.class).invokeStatic(max, p.get(0)).returning());
        assertEquals(3, run(def, List.of(1, 3, 2)));
    }

    // `Objects.requireNonNull(T)` with an `Object`, returned as a `String`.
    @Test
    void objectPassedToRequireNonNullReturnedAsString() throws Exception {
        var requireNonNull = Objects.class.getMethod("requireNonNull", Object.class);
        var def = single("RequireNonNull", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Objects.class).invokeStatic(requireNonNull, p.get(0)).returning());
        assertEquals("a", run(def, "a"));
    }

    // `Optional.ofNullable(T)` with an `Object`, returned from a method returning `Optional<String>`: the bytecode returns
    // the raw `Optional`; javac infers `T` from the return type and rejects the `Object` argument.
    @Test
    void objectPassedToOptionalOfNullableReturnedParameterized() throws Exception {
        var ofNullable = Optional.class.getMethod("ofNullable", Object.class);
        var def = single("OptionalOfNullable", TypeDef.parameterized(Optional.class, String.class), List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Optional.class).invokeStatic(ofNullable, p.get(0)).returning());
        assertEquals(Optional.of("a"), run(def, "a"));
    }

    // `List.of(E)` with an `Object`.
    @Test
    void objectPassedToListOf() throws Exception {
        var of = List.class.getMethod("of", Object.class);
        var def = single("ListOf", TypeDef.of(List.class), List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(List.class).invokeStatic(of, p.get(0)).returning());
        assertEquals(List.of("a"), run(def, "a"));
    }

    // `String.format(String, Object...)` with an `Object`: the array, as the bytecode's checkcast to `Object[]` makes it
    // - a value that is not an array fails the cast, as it does in bytecode.
    @Test
    void objectPassedToStringFormat() throws Exception {
        var format = String.class.getMethod("format", String.class, Object[].class);
        var def = single("Format", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(format, ExpressionDef.constant("<%s>"), p.get(0)).returning());
        assertEquals("<5>", run(def, (Object) new Object[]{5}));
        assertThrows(ClassCastException.class, () -> run(def, 5));
    }

    // `String.format(String, Object...)` with an `Integer`: one element, which the bytecode wraps as an `Object[]` of one.
    @Test
    void integerPassedToStringFormat() throws Exception {
        var format = String.class.getMethod("format", String.class, Object[].class);
        var def = single("FormatInteger", TypeDef.STRING, List.of(TypeDef.of(Integer.class)),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(format, ExpressionDef.constant("<%s>"), p.get(0)).returning());
        assertEquals("<5>", run(def, 5));
    }

    // ---- receivers

    // `List<? extends Number>.get(int)` returned as a `Number`.
    @Test
    void wildcardExtendsReceiverResultReturnedAsNumber() throws Exception {
        var get = List.class.getMethod("get", int.class);
        var def = single("WildcardGet", TypeDef.of(Number.class),
            List.of(TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))),
            (self, p) -> p.get(0).invoke(get, ExpressionDef.constant(0)).returning());
        assertEquals(5, run(def, List.of(5)));
    }

    // Known limit (a wildcard type argument is not converted to): `List<? super Integer>.add(Object)`; the source `p0.add(p1)`
    // does not compile, where `p0.add((Integer) p1)` would.
    @Test
    void wildcardSuperReceiverAddOfObject() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var def = single("WildcardSuperAdd", TypeDef.Primitive.BOOLEAN,
            List.of(TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class))), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(add, p.get(1)).returning());
        var list = new ArrayList<Number>();
        assertEquals(true, run(def, list, 5));
        assertEquals(List.of(5), list);
    }

    // A raw `List.add(Object)` takes the `Object`.
    @Test
    void rawReceiverAddOfObject() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var def = single("RawAdd", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.of(List.class), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(add, p.get(1)).returning());
        var list = new ArrayList<Object>();
        assertEquals(true, run(def, list, "a"));
        assertEquals(List.of("a"), list);
    }

    // A receiver typed as a variable bounded by `List<String>`: the bytecode calls `List.add(Object)` on the erasure; the
    // source `p0.add(p1)` sees `add(String)` and does not compile.
    @Test
    void boundedTypeVariableReceiverAddOfObject() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, String.class));
        var def = ClassDef.builder("test.VariableReceiverAdd").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
                .addParameter("p0", t).addParameter("p1", Object.class).returns(boolean.class)
                .build((self, p) -> p.get(0).invoke(add, p.get(1)).returning())).build();
        var list = new ArrayList<String>();
        assertEquals(true, run(def, list, "a"));
        assertEquals(List.of("a"), list);
    }

    // A receiver typed as a variable of an intersection bound `CharSequence & Comparable<String>`: `compareTo(Object)` in the
    // model is `compareTo(String)` in the source.
    @Test
    void intersectionBoundedReceiverCompareToObject() throws Exception {
        var compareTo = Comparable.class.getMethod("compareTo", Object.class);
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class), TypeDef.parameterized(Comparable.class, String.class));
        var def = ClassDef.builder("test.IntersectionReceiverCompare").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
                .addParameter("p0", t).addParameter("p1", Object.class).returns(int.class)
                .build((self, p) -> p.get(0).invoke(compareTo, p.get(1)).returning())).build();
        assertEquals(0, run(def, "a", "a"));
    }

    // An element of a `List<String>[]` as the receiver of `add(Object)`.
    @Test
    void elementOfArrayOfParameterizedReceiverAddOfObject() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var def = single("ArrayElementReceiver", TypeDef.Primitive.BOOLEAN,
            List.of(TypeDef.parameterized(List.class, String.class).array(), TypeDef.OBJECT),
            (self, p) -> p.get(0).arrayElement(0).invoke(add, p.get(1)).returning());
        var list = new ArrayList<String>();
        assertEquals(true, run(def, (Object) new List[]{list}, "a"));
        assertEquals(List.of("a"), list);
    }

    // `this.add(Object)` in a class extending the compiled `ArrayList<String>`: the superclass binds `E` to `String`.
    @Test
    void objectPassedToInheritedMethodOfCompiledGenericSuperclass() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var def = ClassDef.builder("test.StringListChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ArrayList.class, String.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(boolean.class)
                .build((self, p) -> self.invoke(add, p.get(0)).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(true, cls.getMethod("call", Object.class).invoke(instance, "a"));
            assertEquals(List.of("a"), instance);
        }
    }

    // `this.get(int)` inherited from `ArrayList<String>` with an `Object` index, returned as a `String`.
    @Test
    void objectIndexPassedToInheritedGetOfCompiledGenericSuperclass() throws Exception {
        var get = List.class.getMethod("get", int.class);
        var def = ClassDef.builder("test.StringListGetter").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ArrayList.class, String.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(String.class)
                .build((self, p) -> self.invoke(get, p.get(0)).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            @SuppressWarnings("unchecked") var list = (List<String>) instance;
            list.add("a");
            list.add("b");
            assertEquals("b", cls.getMethod("call", Object.class).invoke(instance, 1));
        }
    }

    // ---- constructors

    // `new ArrayList(Collection)` with an `Object`.
    @Test
    void objectPassedToArrayListCollectionConstructor() throws Exception {
        var constructor = ArrayList.class.getConstructor(Collection.class);
        var def = single("ArrayListOfObject", TypeDef.of(List.class), List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(ArrayList.class).instantiate(constructor, p.get(0)).returning());
        assertEquals(List.of(1), run(def, List.of(1)));
    }

    // `new ArrayList<String>(Collection)` with an `Object`.
    @Test
    void objectPassedToParameterizedArrayListConstructor() throws Exception {
        var constructor = ArrayList.class.getConstructor(Collection.class);
        var type = TypeDef.parameterized(ArrayList.class, String.class);
        var def = single("TypedArrayListOfObject", type, List.of(TypeDef.OBJECT),
            (self, p) -> type.instantiate(constructor, p.get(0)).returning());
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    // `new StringBuilder(int)` with an `Object`: `StringBuilder(String)` and `StringBuilder(CharSequence)` are other overloads.
    @Test
    void objectPassedToStringBuilderCapacityConstructor() throws Exception {
        var constructor = StringBuilder.class.getConstructor(int.class);
        var capacity = StringBuilder.class.getMethod("capacity");
        var def = single("BuilderCapacity", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(StringBuilder.class).instantiate(constructor, p.get(0)).invoke(capacity).returning());
        assertEquals(42, run(def, 42));
    }

    // A constructor of a nested class with an `Object` argument.
    @Test
    void objectPassedToNestedClassConstructor() throws Exception {
        var constructor = Fixtures.Nested.class.getConstructor(String.class);
        var def = single("NestedConstructed", TypeDef.of(Fixtures.Nested.class), List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Fixtures.Nested.class).instantiate(constructor, p.get(0)).returning());
        assertEquals("x", ((Fixtures.Nested) run(def, "x")).label);
    }

    // ---- an Object returned where the method returns another type

    @Test
    void objectReturnedFromStringArrayMethod() throws Exception {
        var def = single("ObjectAsStringArray", TypeDef.STRING.array(), List.of(TypeDef.OBJECT), (self, p) -> p.get(0).returning());
        assertArrayEquals(new String[]{"a"}, (String[]) run(def, (Object) new String[]{"a"}));
    }

    @Test
    void objectReturnedFromParameterizedMethod() throws Exception {
        var def = single("ObjectAsListOfString", TypeDef.parameterized(List.class, String.class), List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).returning());
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    @Test
    void objectReturnedFromTypeVariableMethod() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.ObjectAsVariable").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("p0", Object.class).returns(t)
                .build((self, p) -> p.get(0).returning())).build();
        assertEquals("a", run(def, "a"));
    }

    @Test
    void objectReturnedFromBooleanMethod() throws Exception {
        var def = single("ObjectAsBoolean", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.OBJECT), (self, p) -> p.get(0).returning());
        assertEquals(true, run(def, true));
    }

    @Test
    void objectReturnedFromCharMethod() throws Exception {
        var def = single("ObjectAsChar", TypeDef.Primitive.CHAR, List.of(TypeDef.OBJECT), (self, p) -> p.get(0).returning());
        assertEquals('z', run(def, 'z'));
    }

    // A `Long` returned from an `int` method: the bytecode unboxes through `Number.intValue`.
    @Test
    void longWrapperReturnedFromIntMethod() throws Exception {
        var def = single("LongAsInt", TypeDef.Primitive.INT, List.of(TypeDef.of(Long.class)), (self, p) -> p.get(0).returning());
        assertEquals(5, run(def, 5L));
    }

    // An `Integer` returned from a `double` method unboxes and widens.
    @Test
    void integerReturnedFromDoubleMethod() throws Exception {
        var def = single("IntegerAsDouble", TypeDef.Primitive.DOUBLE, List.of(TypeDef.of(Integer.class)), (self, p) -> p.get(0).returning());
        assertEquals(5.0, run(def, 5));
    }

    // A `Character` returned from a `long` method.
    @Test
    void characterReturnedFromLongMethod() throws Exception {
        var def = single("CharacterAsLong", TypeDef.Primitive.LONG, List.of(TypeDef.of(Character.class)), (self, p) -> p.get(0).returning());
        assertEquals(97L, run(def, 'a'));
    }

    // ---- compound expressions

    // A conditional typed `Integer` whose branches are an `int` and an `Integer`: the bytecode boxes the `int` and returns the
    // `Integer` as is, `null` included; Java types `c ? int : Integer` as `int` and unboxes the `null` (NPE).
    @Test
    void conditionalOfIntAndIntegerBranchesKeepsANullResult() throws Exception {
        var def = single("MixedConditional", TypeDef.of(Integer.class), List.of(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT, TypeDef.of(Integer.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.of(Integer.class)).returning());
        assertEquals(1, run(def, true, 1, null));
        assertNull(run(def, false, 1, null));
    }

    // A conditional typed `Object` whose branches are an `Integer` and a `Long`: the bytecode returns the `Integer`; Java
    // promotes `c ? Integer : Long` to `long` and returns a `Long`.
    @Test
    void conditionalOfIntegerAndLongBranchesKeepsTheIntegerBoxed() throws Exception {
        var def = single("PromotedConditional", TypeDef.OBJECT, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer.class), TypeDef.of(Long.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT).returning());
        assertEquals(Integer.valueOf(1), run(def, true, 1, 2L));
        assertEquals(Long.valueOf(2), run(def, false, 1, 2L));
    }

    // A conditional of an `int` and an `Integer` passed to `pick(Object)`, where `pick(Integer)` would take it.
    @Test
    void conditionalOfIntAndIntegerPassedToObjectOverload() throws Exception {
        var pick = Fixtures.class.getMethod("pick", Object.class);
        var def = single("ConditionalToObject", TypeDef.STRING, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT, TypeDef.of(Integer.class)),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(pick, p.get(0).isTrue().doIfElse(p.get(1), p.get(2))).returning());
        assertEquals("object", run(def, true, 1, 2));
    }

    // A switch expression typed `Integer` with an `int` result and an `Integer` result, returned.
    @Test
    void switchOfIntAndIntegerResultsReturnedAsInteger() throws Exception {
        var def = single("MixedSwitch", TypeDef.of(Integer.class), List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT, TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).asExpressionSwitch(TypeDef.of(Integer.class), Map.of(ExpressionDef.constant(1), p.get(1)), p.get(2)).returning());
        assertEquals(1, run(def, 1, 1, null));
        assertNull(run(def, 2, 1, null));
    }

    // A switch expression typed `Object` yielding an `int` and a `String`, passed to `pick(Object)`.
    @Test
    void switchOfIntAndStringResultsPassedToObjectOverload() throws Exception {
        var pick = Fixtures.class.getMethod("pick", Object.class);
        var def = single("SwitchToObject", TypeDef.STRING, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT, TypeDef.STRING),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(pick,
                p.get(0).asExpressionSwitch(TypeDef.OBJECT, Map.of(ExpressionDef.constant(1), p.get(1)), p.get(2))).returning());
        assertEquals("object", run(def, 1, 5, "s"));
        assertEquals("object", run(def, 2, 5, "s"));
    }

    // A sum of ints passed to `prim(long)`: `prim(int)` would take it.
    @Test
    void sumOfIntsPassedToLongOverload() throws Exception {
        var prim = Fixtures.class.getMethod("prim", long.class);
        var def = single("SumToLong", TypeDef.STRING, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(prim, p.get(0).math(MathBinaryOperation.OpType.ADDITION, p.get(1))).returning());
        assertEquals("long", run(def, 1, 2));
    }

    // ---- constants

    // A `char` constant is a character literal, quoted and escaped; the bytecode pushes its value.
    @TestFactory
    Stream<DynamicTest> charConstantsAreCharacterLiterals() {
        return Stream.of('b', '\'', '\n', '\\', 'é', '"', '$').map(c -> dynamicTest("'" + c + "'", () -> {
            var def = single("CharConstant" + (int) c, TypeDef.Primitive.CHAR, List.of(),
                (self, p) -> ExpressionDef.constant(c).returning());
            assertEquals(c, run(def));
        }));
    }

    // A boxed `Character` constant.
    @Test
    void boxedCharacterConstantIsACharacterLiteral() throws Exception {
        var def = single("CharacterConstant", TypeDef.of(Character.class), List.of(),
            (self, p) -> ExpressionDef.constant(Character.valueOf('b')).returning());
        assertEquals('b', run(def));
    }

    // A `byte` constant passed to a `byte` parameter: the bytecode pushes a byte; the source `Byte.toString(1)` passes an `int`
    // literal, which a method argument does not narrow.
    @Test
    void byteConstantPassedToByteParameter() throws Exception {
        var toString = Byte.class.getMethod("toString", byte.class);
        var def = single("ByteConstantParam", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(Byte.class).invokeStatic(toString, new ExpressionDef.Constant(TypeDef.Primitive.BYTE, (byte) 1)).returning());
        assertEquals("1", run(def));
    }

    // As above for a `short` constant.
    @Test
    void shortConstantPassedToShortParameter() throws Exception {
        var toString = Short.class.getMethod("toString", short.class);
        var def = single("ShortConstantParam", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(Short.class).invokeStatic(toString, new ExpressionDef.Constant(TypeDef.Primitive.SHORT, (short) 1)).returning());
        assertEquals("1", run(def));
    }

    // A `String` constant with quotes, a `$`, a newline, a backslash and a non-ASCII character.
    @Test
    void stringConstantWithSpecialCharacters() throws Exception {
        var text = "a\"b$c\ndé\\e\tf";
        var def = single("StringConstant", TypeDef.STRING, List.of(), (self, p) -> ExpressionDef.constant(text).returning());
        assertEquals(text, run(def));
    }

    // `NaN` and the infinities have no literal; the bytecode pushes them from the constant pool.
    @TestFactory
    Stream<DynamicTest> nanAndInfinityConstants() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, Double.MIN_VALUE)
            .map(value -> dynamicTest(String.valueOf(value), () -> {
                var def = single("DoubleConstant" + Math.abs(value.hashCode()), TypeDef.Primitive.DOUBLE, List.of(),
                    (self, p) -> ExpressionDef.constant(value.doubleValue()).returning());
                assertEquals(value, run(def));
            }));
    }

    // As above for floats.
    @TestFactory
    Stream<DynamicTest> floatNanAndInfinityConstants() {
        return Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, Float.MIN_VALUE)
            .map(value -> dynamicTest(String.valueOf(value), () -> {
                var def = single("FloatConstant" + Math.abs(value.hashCode()), TypeDef.Primitive.FLOAT, List.of(),
                    (self, p) -> ExpressionDef.constant(value.floatValue()).returning());
                assertEquals(value, run(def));
            }));
    }

    // Negative zero keeps its sign.
    @Test
    void negativeZeroConstant() throws Exception {
        var def = single("NegativeZero", TypeDef.Primitive.DOUBLE, List.of(), (self, p) -> ExpressionDef.constant(-0.0d).returning());
        assertEquals(Double.NEGATIVE_INFINITY, 1 / (double) run(def));
    }

    // The minimum values of `long` and `int`, and a negative constant as the right operand of a subtraction.
    @Test
    void minimumValueConstantsInMath() throws Exception {
        var def = single("MinValues", TypeDef.Primitive.LONG.array(), List.of(TypeDef.Primitive.INT),
            (self, p) -> TypeDef.Primitive.LONG.array().instantiate(
                ExpressionDef.constant(Long.MIN_VALUE),
                ExpressionDef.constant(Integer.MIN_VALUE).math(MathBinaryOperation.OpType.SUBTRACTION, p.get(0)),
                p.get(0).math(MathBinaryOperation.OpType.SUBTRACTION, ExpressionDef.constant(-1))).returning());
        assertArrayEquals(new long[]{Long.MIN_VALUE, Integer.MIN_VALUE - 1, 2}, (long[]) run(def, 1));
    }

    // The negation of a negative constant: `--1` is a decrement to javac.
    @Test
    void negationOfANegativeConstant() throws Exception {
        var def = single("NegatedNegative", TypeDef.Primitive.INT, List.of(),
            (self, p) -> ExpressionDef.constant(-1).math(MathUnaryOperation.OpType.NEGATE).returning());
        assertEquals(1, run(def));
    }

    // A `Class` value as a constant: the bytecode pushes the type; the source needs `String.class`.
    @Test
    void classValueConstantIsATypeLiteral() throws Exception {
        var def = single("ClassConstant", TypeDef.CLASS, List.of(), (self, p) -> ExpressionDef.constant(String.class).returning());
        assertEquals(String.class, run(def));
    }

    // Type literals of a primitive, arrays and a nested class.
    @Test
    void typeLiteralsOfPrimitivesArraysAndNestedTypes() throws Exception {
        var def = single("TypeLiterals", TypeDef.CLASS.array(), List.of(),
            (self, p) -> TypeDef.CLASS.array().instantiate(
                ExpressionDef.constant(TypeDef.Primitive.INT),
                ExpressionDef.constant(TypeDef.of(String[].class)),
                ExpressionDef.constant(TypeDef.of(int[][].class)),
                ExpressionDef.constant(TypeDef.of(Map.Entry.class))).returning());
        assertArrayEquals(new Class<?>[]{int.class, String[].class, int[][].class, Map.Entry.class}, (Class<?>[]) run(def));
    }

    // An enum constant.
    @Test
    void enumConstant() throws Exception {
        var def = single("EnumConstant", TypeDef.of(RetentionPolicy.class), List.of(),
            (self, p) -> ExpressionDef.constant(RetentionPolicy.RUNTIME).returning());
        assertSame(RetentionPolicy.RUNTIME, run(def));
    }

    // A boxed `Integer` constant passed to `box(int)`: `box(Integer)` is the other overload.
    @Test
    void boxedIntegerConstantPassedToIntOverload() throws Exception {
        var box = Fixtures.class.getMethod("box", int.class);
        var def = single("BoxedConstantToInt", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(box, ExpressionDef.constant(Integer.valueOf(1))).returning());
        assertEquals("int", run(def));
    }

    // A `long` constant passed to `pick(Object)`.
    @Test
    void longConstantPassedToObjectOverload() throws Exception {
        var pick = Fixtures.class.getMethod("pick", Object.class);
        var def = single("LongConstantToObject", TypeDef.STRING, List.of(),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(pick, ExpressionDef.constant(5L)).returning());
        assertEquals("object", run(def));
    }

    // ---- string concatenation

    // A `char` and an `int` concatenated: neither is a String, the `char` is written as its character.
    @Test
    void charConcatenatedWithInt() throws Exception {
        var def = single("CharIntConcat", TypeDef.STRING, List.of(TypeDef.Primitive.CHAR, TypeDef.Primitive.INT),
            (self, p) -> new ExpressionDef.StringConcatenation(p.get(0), p.get(1)).returning());
        assertEquals("a1", run(def, 'a', 1));
    }

    // `null` on either side of a concatenation.
    @Test
    void nullConcatenatedWithString() throws Exception {
        var def = single("NullConcat", TypeDef.STRING, List.of(),
            (self, p) -> ExpressionDef.nullValue().stringConcat(ExpressionDef.constant("x")).stringConcat(ExpressionDef.nullValue()).returning());
        assertEquals("nullxnull", run(def));
    }

    // A `long` sum with a widened `char` inside a concatenation keeps its grouping.
    @Test
    void longArithmeticInsideConcatenation() throws Exception {
        var def = single("LongCharConcat", TypeDef.STRING, List.of(TypeDef.Primitive.LONG, TypeDef.Primitive.CHAR),
            (self, p) -> ExpressionDef.constant("v").stringConcat(
                p.get(0).math(MathBinaryOperation.OpType.ADDITION, p.get(1).cast(TypeDef.Primitive.LONG))).returning());
        assertEquals("v66", run(def, 1L, 'A'));
    }

    // Two `Object`s concatenated: `String.valueOf((Object) p0) + p1`.
    @Test
    void objectConcatenatedWithObject() throws Exception {
        var def = single("ObjectConcat", TypeDef.STRING, List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> new ExpressionDef.StringConcatenation(p.get(0), p.get(1)).returning());
        assertEquals("a1", run(def, "a", 1));
        assertEquals("nullnull", run(def, null, null));
    }

    // ---- instanceof and chained casts

    // `instanceof` guarding a cast to the tested type.
    @Test
    void instanceOfThenCastToTheTestedType() throws Exception {
        var length = String.class.getMethod("length");
        var def = single("InstanceOfCast", TypeDef.Primitive.INT, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).instanceOf(ClassTypeDef.of(String.class))
                .doIfElse(p.get(0).cast(TypeDef.STRING).invoke(length), ExpressionDef.constant(-1)).returning());
        assertEquals(2, run(def, "ab"));
        assertEquals(-1, run(def, 5));
    }

    // `(String) (Object) 1` throws in both.
    @Test
    void chainedCastThroughObjectToStringThrows() throws Exception {
        var def = single("ChainedCastString", TypeDef.STRING, List.of(),
            (self, p) -> ExpressionDef.constant(1).cast(TypeDef.OBJECT).cast(TypeDef.STRING).returning());
        assertThrows(ClassCastException.class, () -> run(def));
    }

    // `(int) (Object) p0` of an `Integer` unboxes through `Number`.
    @Test
    void chainedCastThroughObjectToInt() throws Exception {
        var def = single("ChainedCastInt", TypeDef.Primitive.INT, List.of(TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).cast(TypeDef.OBJECT).cast(TypeDef.Primitive.INT).returning());
        assertEquals(5, run(def, 5));
    }

    // A cast `null` returned.
    @Test
    void castNullReturned() throws Exception {
        var def = single("CastNull", TypeDef.STRING, List.of(), (self, p) -> ExpressionDef.nullValue().cast(TypeDef.STRING).returning());
        assertNull(run(def));
    }

    // ---- arrays

    // `new List<String>[2]` is created raw.
    @Test
    void arrayOfParameterizedTypeCreatedRaw() throws Exception {
        var type = TypeDef.parameterized(List.class, String.class).array();
        var def = single("GenericArray", type, List.of(), (self, p) -> new ExpressionDef.NewArrayOfSize(type, 2).returning());
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // `new T[2]` of a method variable: the bytecode creates an array of the erasure; Java has no generic array creation.
    @Test
    void arrayOfATypeVariableIsCreatedAsItsBound() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.VariableArray").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).returns(t.array())
                .build((self, p) -> new ExpressionDef.NewArrayOfSize(t.array(), 2).returning())).build();
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // `Object` elements of an `int[]` are unboxed.
    @Test
    void objectElementsOfPrimitiveArray() throws Exception {
        var def = single("ObjectIntElements", TypeDef.Primitive.INT.array(), List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> TypeDef.Primitive.INT.array().instantiate(p.get(0), p.get(1)).returning());
        assertArrayEquals(new int[]{1, 2}, (int[]) run(def, 1, 2L));
    }

    // An `int` element of a `char[]` is narrowed, and an `Integer` element of an `int[]` unboxed.
    @Test
    void intElementOfCharArrayAndIntegerElementOfIntArray() throws Exception {
        var def = single("NarrowedElements", TypeDef.OBJECT.array(), List.of(TypeDef.Primitive.INT, TypeDef.of(Integer.class)),
            (self, p) -> TypeDef.OBJECT.array().instantiate(
                TypeDef.Primitive.CHAR.array().instantiate(p.get(0)),
                TypeDef.Primitive.INT.array().instantiate(p.get(1))).returning());
        var arrays = (Object[]) run(def, 97, 5);
        assertArrayEquals(new char[]{'a'}, (char[]) arrays[0]);
        assertArrayEquals(new int[]{5}, (int[]) arrays[1]);
    }

    // A multi-dimensional array of a size.
    @Test
    void multiDimensionalArrayOfSize() throws Exception {
        var type = TypeDef.Primitive.INT.array(2);
        var def = single("MultiArray", type, List.of(), (self, p) -> new ExpressionDef.NewArrayOfSize(type, 3).returning());
        var array = (int[][]) run(def);
        assertEquals(3, array.length);
        assertNull(array[0]);
    }

    // ---- stored values

    // An `Object` stored in a `long` local, a `char` local and an `int` local from a `long`.
    @Test
    void objectStoredInPrimitiveLocals() throws Exception {
        var def = single("StoredLocals", TypeDef.OBJECT.array(), List.of(TypeDef.OBJECT, TypeDef.OBJECT, TypeDef.Primitive.LONG), (self, p) -> {
            var l = new VariableDef.Local("l", TypeDef.Primitive.LONG);
            var c = new VariableDef.Local("c", TypeDef.Primitive.CHAR);
            var i = new VariableDef.Local("i", TypeDef.Primitive.INT);
            return StatementDef.multi(
                l.defineAndAssign(p.get(0)),
                c.defineAndAssign(p.get(1)),
                i.defineAndAssign(p.get(2)),
                TypeDef.OBJECT.array().instantiate(l, c, i).returning());
        });
        assertArrayEquals(new Object[]{5L, 'x', 7}, (Object[]) run(def, 5, 'x', 7L));
    }

    // ---- second batch: more overload, condition and store shapes

    // An untyped `null` passed to `pick(String)` of the class being written: the cast to the parameter is computed but a
    // cast of `null` is dropped when written, so `pick(null)` is ambiguous between `pick(String)` and `pick(Integer)`.
    @Test
    void nullPassedToNonObjectOverloadOfGeneratedMethod() throws Exception {
        var takeObject = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var takeString = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("string").returning());
        var takeInteger = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", Integer.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("integer").returning());
        var def = ClassDef.builder("test.NullToGeneratedOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(takeObject).addMethod(takeString).addMethod(takeInteger)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.invoke(takeString, ExpressionDef.nullValue()).returning())).build();
        assertEquals("string", run(def));
    }

    // A switch over a `char` selector with `char` case constants.
    @Test
    void switchOverCharConstants() throws Exception {
        var def = single("CharSwitch", TypeDef.STRING, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING,
                Map.of(ExpressionDef.constant('a'), ExpressionDef.constant("first")), ExpressionDef.constant("other")).returning());
        assertEquals("first", run(def, 'a'));
        assertEquals("other", run(def, 'b'));
    }

    // A `long` passed to `pick(Object)`: boxed as a `Long`.
    @Test
    void longPassedToObjectOverload() throws Exception {
        var pick = Fixtures.class.getMethod("pick", Object.class);
        var def = single("LongToObject", TypeDef.STRING, List.of(TypeDef.Primitive.LONG),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(pick, p.get(0)).returning());
        assertEquals("object", run(def, 1L));
    }

    // A `char` passed to `pick(Object)`: boxed as a `Character`, where `pick(Integer)` is not applicable.
    @Test
    void charPassedToObjectOverload() throws Exception {
        var pick = Fixtures.class.getMethod("pick", Object.class);
        var def = single("CharToObject", TypeDef.STRING, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(pick, p.get(0)).returning());
        assertEquals("object", run(def, 'a'));
    }

    // An `Integer` passed to `prim(long)`, where `prim(int)` would take it unboxed.
    @Test
    void integerPassedToLongOverload() throws Exception {
        var prim = Fixtures.class.getMethod("prim", long.class);
        var def = single("IntegerToLong", TypeDef.STRING, List.of(TypeDef.of(Integer.class)),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(prim, p.get(0)).returning());
        assertEquals("long", run(def, 1));
    }

    // A `Long` passed to `prim(int)`: unboxed and narrowed through `Number.intValue`.
    @Test
    void longWrapperPassedToIntOverload() throws Exception {
        var prim = Fixtures.class.getMethod("prim", int.class);
        var def = single("LongWrapperToInt", TypeDef.STRING, List.of(TypeDef.of(Long.class)),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(prim, p.get(0)).returning());
        assertEquals("int", run(def, 1L));
    }

    // An `Object` passed to `box(Integer)` and to `box(int)`.
    @Test
    void objectPassedToBoxedAndPrimitiveOverloads() throws Exception {
        var boxed = Fixtures.class.getMethod("box", Integer.class);
        var primitive = Fixtures.class.getMethod("box", int.class);
        var def = single("ObjectToBoxOverloads", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(boxed, p.get(0))
                .stringConcat(ClassTypeDef.of(Fixtures.class).invokeStatic(primitive, p.get(0))).returning());
        assertEquals("boxedint", run(def, 1));
    }

    // An `Object` stored in an `int` field.
    @Test
    void objectStoredInPrimitiveField() throws Exception {
        var count = io.micronaut.sourcegen.model.FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).build();
        var def = ClassDef.builder("test.PrimitiveFieldStore").addModifiers(Modifier.PUBLIC).addField(count)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(int.class)
                .build((self, p) -> StatementDef.multi(self.field(count).put(p.get(0)), self.field(count).returning()))).build();
        assertEquals(5, run(def, 5L));
    }

    // An `int` returned from an `Object` method is boxed.
    @Test
    void intReturnedFromObjectMethodIsBoxed() throws Exception {
        var def = single("IntAsObject", TypeDef.OBJECT, List.of(TypeDef.Primitive.INT), (self, p) -> p.get(0).returning());
        assertEquals(Integer.valueOf(5), run(def, 5));
    }

    // `instanceof` of a primitive value tests its wrapper.
    @Test
    void primitiveInstanceOfItsWrapper() throws Exception {
        var def = single("PrimitiveInstanceOf", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.Primitive.INT),
            (self, p) -> p.get(0).instanceOf(ClassTypeDef.of(Integer.class)).returning());
        assertEquals(true, run(def, 5));
    }

    // `Object`s unboxed in a negated condition and in a conjunction.
    @Test
    void objectUnboxedInNegatedAndConjoinedConditions() throws Exception {
        var def = single("UnboxedConditions", TypeDef.Primitive.BOOLEAN.array(), List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> TypeDef.Primitive.BOOLEAN.array().instantiate(
                p.get(0).cast(TypeDef.Primitive.BOOLEAN).isFalse(),
                p.get(0).cast(TypeDef.Primitive.BOOLEAN).isTrue().and(p.get(1).cast(TypeDef.Primitive.BOOLEAN).isTrue()),
                p.get(0).cast(TypeDef.Primitive.BOOLEAN).isTrue().or(p.get(1).cast(TypeDef.Primitive.BOOLEAN).isTrue())).returning());
        assertArrayEquals(new boolean[]{false, false, true}, (boolean[]) run(def, true, false));
    }

    // A `Boolean` as the condition of an `if`.
    @Test
    void boxedBooleanAsIfCondition() throws Exception {
        var def = single("BoxedIf", TypeDef.STRING, List.of(TypeDef.of(Boolean.class)),
            (self, p) -> p.get(0).isTrue().doIfElse(ExpressionDef.constant("yes").returning(), ExpressionDef.constant("no").returning()));
        assertEquals("yes", run(def, true));
    }

    // An `Object` passed to a `Class<?>` parameter.
    @Test
    void objectPassedToWildcardParameterizedParameter() throws Exception {
        var name = Fixtures.class.getMethod("name", Class.class);
        var def = single("ObjectToClass", TypeDef.STRING, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Fixtures.class).invokeStatic(name, p.get(0)).returning());
        assertEquals("String", run(def, String.class));
    }

    // An `Object` passed to a `List<String>` parameter of a generated method.
    @Test
    void objectPassedToParameterizedParameterOfGeneratedMethod() throws Exception {
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.parameterized(List.class, String.class)).returns(Object.class)
            .build((self, p) -> p.get(0).returning());
        var def = ClassDef.builder("test.ObjectToTypedList").addModifiers(Modifier.PUBLIC).addMethod(take)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(take, p.get(0)).returning())).build();
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    // An `Object` passed to an `Object[]` parameter of a generated method, which is not varargs: cast to the array.
    @Test
    void objectPassedToArrayParameterOfGeneratedMethod() throws Exception {
        var size = MethodDef.builder("size").addModifiers(Modifier.PUBLIC).addParameter("values", Object[].class).returns(int.class)
            .build((self, p) -> ClassTypeDef.of(java.lang.reflect.Array.class).invokeStatic("getLength", TypeDef.Primitive.INT, p.get(0)).returning());
        var def = ClassDef.builder("test.ObjectToArrayParameter").addModifiers(Modifier.PUBLIC).addMethod(size)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(int.class)
                .build((self, p) -> self.invoke(size, p.get(0)).returning())).build();
        assertEquals(2, run(def, (Object) new Object[]{1, 2}));
    }

    // An `Object` element of an `Object[][]` is cast to `Object[]`.
    @Test
    void objectElementOfTwoDimensionalArray() throws Exception {
        var def = single("ObjectRowElement", TypeDef.OBJECT.array(2), List.of(TypeDef.OBJECT),
            (self, p) -> TypeDef.OBJECT.array(2).instantiate(p.get(0)).returning());
        var rows = (Object[][]) run(def, (Object) new Object[]{"a"});
        assertArrayEquals(new Object[]{"a"}, rows[0]);
    }

    // `==` of two `Integer` operands compares the references, as the bytecode's IF_ACMP does.
    @Test
    void boxedOperandsOfEqualityOperatorAreComparedReferentially() throws Exception {
        var def = single("BoxedEquality", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.of(Integer.class), TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).compare(OpType.EQUAL_TO, p.get(1)).returning());
        assertEquals(false, run(def, Integer.valueOf(1000), Integer.valueOf(1000)));
        Integer shared = 1000;
        assertEquals(true, run(def, shared, shared));
    }

    // ---- fixtures

    /**
     * Overloads and varargs a call in the model names one of.
     *
     * @since 2.2.2
     */
    @SuppressWarnings("unused")
    public static class Fixtures {

        /**
         * @param value The value
         * @return The overload
         */
        public static String pick(Object value) {
            return "object";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String pick(String value) {
            return "string";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String pick(Integer value) {
            return "integer";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String prim(int value) {
            return "int";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String prim(long value) {
            return "long";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String box(int value) {
            return "int";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String box(Integer value) {
            return "boxed";
        }

        /**
         * @param values The values
         * @return Their count
         */
        public static int size(Object... values) {
            return values.length;
        }

        /**
         * @param values The values
         * @return The values joined
         */
        public static String joined(String... values) {
            return String.join("", values);
        }

        /**
         * @param type The type
         * @return Its simple name
         */
        public static String name(Class<?> type) {
            return type.getSimpleName();
        }

        /**
         * A nested class with a typed constructor.
         *
         * @since 2.2.2
         */
        public static class Nested {
            /** The label. */
            public final String label;

            /** @param label The label */
            public Nested(String label) {
                this.label = label;
            }
        }
    }

    // ---- helpers

    private static ClassDef single(String name, TypeDef returns, List<TypeDef> parameters, MethodDef.MethodBodyBuilder body) {
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(returns);
        for (int i = 0; i < parameters.size(); i++) {
            method.addParameter("p" + i, parameters.get(i));
        }
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC).addMethod(method.build(body)).build();
    }

    private static Object run(ObjectDef def, Object... args) throws Exception {
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = Arrays.stream(cls.getMethods()).filter(m -> m.getName().equals("call")).findFirst().orElseThrow();
            try {
                return method.invoke(cls.getConstructor().newInstance(), args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            sources.add(render(definition));
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    private static String render(ObjectDef definition) throws Exception {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        return writer.toString();
    }
}
