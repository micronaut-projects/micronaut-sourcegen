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

import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Alpha;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Receivers;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Zeta;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.ZetaAlphaOne;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conversions: casts, boxing and unboxing, primitive widening of arguments and of requested return types,
 * and values discarded after they are converted.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "MissingOverride", "UnusedTypeParameter", "unchecked", "rawtypes", "varargs",
    "TypeParameterUnusedInFormals", "unused", "EqualsIncompatibleType", "UnusedMethod",
    "UnusedVariable"
})
public abstract class ConversionTck extends AbstractByteCodeWriterTck {

    @Test
    public void writesUnboxingOfAnyNumberAndCastsThroughUnrelatedTypes() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckUnboxParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("asInt")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.INT)
                // A Long held in an Object still unboxes to an int, as it does with the ASM backend
                .build((ignored, parameters) -> parameters.get(0).cast(TypeDef.Primitive.INT).returning()))
            .addMethod(MethodDef.builder("boxThenCast")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.STRING)
                // Boxing into an unrelated reference type must stay verifiable
                .build((ignored, parameters) -> parameters.get(0).cast(TypeDef.STRING).returning()))
            .build();

        Class<?> generated = define(definition);

        assertEquals(7, generated.getMethod("asInt", Object.class).invoke(null, 7L));
        assertEquals(7, generated.getMethod("asInt", Object.class).invoke(null, 7));
        assertThrows(InvocationTargetException.class,
            () -> generated.getMethod("boxThenCast", int.class).invoke(null, 1));
    }

    @Test
    public void writesNestedCastsWithoutUnboxingAndReboxingAReference() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckNestedCastParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("asBoolean")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(ClassTypeDef.of(Boolean.class))
                // A Kotlin default argument produces exactly this shape: the property value is
                // cast to the primitive and then back to its wrapper. Emitting both casts would
                // unbox a null and throw, so only the outer cast belongs in the bytecode.
                .build((ignored, parameters) -> new ExpressionDef.Cast(ClassTypeDef.of(Boolean.class),
                    new ExpressionDef.Cast(TypeDef.Primitive.BOOLEAN, parameters.get(0))).returning()))
            .addMethod(MethodDef.builder("narrow")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.LONG)
                // A primitive cast of something that is not Object is a real conversion and stays
                .build((ignored, parameters) -> new ExpressionDef.Cast(TypeDef.Primitive.LONG,
                    new ExpressionDef.Cast(TypeDef.Primitive.BYTE, parameters.get(0))).returning()))
            .build();

        Class<?> generated = define(definition);

        assertEquals(Boolean.TRUE, generated.getMethod("asBoolean", Object.class).invoke(null, Boolean.TRUE));
        assertNull(generated.getMethod("asBoolean", Object.class).invoke(null, new Object[] {null}));
        assertEquals(1L, generated.getMethod("narrow", int.class).invoke(null, 257));
    }

    @Test
    public void writesObjectValuesPassedToAndReturnedAsTypedValues() throws Exception {
        MethodDef take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("text", String.class)
            .returns(String.class)
            .build((aThis, parameters) -> parameters.get(0).returning());
        var stringBuilderConstructor = StringBuilder.class.getConstructor(String.class);
        ClassDef definition = ClassDef.builder("example.TckObjectToTyped")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(take)
            .addMethod(MethodDef.builder("dispatch").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(String.class)
                .build((aThis, parameters) -> aThis.invoke(take, parameters.get(0)).returning()))
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(StringBuilder.class)
                .build((aThis, parameters) -> ClassTypeDef.of(StringBuilder.class)
                    .instantiate(stringBuilderConstructor, parameters.get(0))
                    .returning()))
            .addMethod(MethodDef.builder("narrow").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(String.class)
                .build((aThis, parameters) -> parameters.get(0).returning()))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor().newInstance();

        assertEquals("a", generated.getMethod("dispatch", Object.class).invoke(instance, "a"));
        assertEquals("b", generated.getMethod("create", Object.class).invoke(instance, "b").toString());
        assertEquals("c", generated.getMethod("narrow", Object.class).invoke(instance, "c"));
    }

    @Test
    public void calleeMethodBoundIsUsedForArgumentConversion() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("U", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("U")).returns(TypeDef.variable("U"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.edges.GenericCallee").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        var definition = ClassDef.builder("test.edges.GenericCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("U", TypeDef.of(CharSequence.class)))
                .returns(TypeDef.OBJECT).build((self, p) -> target.asTypeDef()
                    .invokeStatic(identity, ExpressionDef.constant(7)).returning())).build();
        assertEquals(7, run(definition, target));
    }

    @Test
    public void castUsesTheEnclosingMethodsBound() throws Exception {
        var definition = ClassDef.builder("test.edges.MethodCast").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.variable("T"))
                .build((self, p) -> p.getFirst().cast(TypeDef.variable("T")).returning())).build();
        assertEquals(7, define(definition).getMethod("call", Object.class).invoke(null, 7));
    }

    @Test
    public void arrayCastUsesTheEnclosingMethodsBound() throws Exception {
        var definition = ClassDef.builder("test.edges.MethodArrayCast").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.variable("T").array())
                .build((self, p) -> p.getFirst().cast(TypeDef.variable("T").array()).returning())).build();
        Integer[] values = {7};
        assertEquals(values, define(definition).getMethod("call", Object.class).invoke(null, (Object) values));
    }

    @Test
    public void methodBoundCastShadowsTheClassBound() throws Exception {
        var definition = ClassDef.builder("test.edges.ShadowedMethodCast").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.variable("T"))
                .build((self, p) -> p.getFirst().cast(TypeDef.variable("T")).returning())).build();
        var type = define(definition);
        assertEquals(7, type.getMethod("call", Object.class).invoke(type.getConstructor().newInstance(), 7));
    }

    @Test
    public void calleeMethodBoundCastsAnObjectArgument() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("U", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("U")).returns(TypeDef.variable("U"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.edges.CastingCallee").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        var definition = ClassDef.builder("test.edges.CastingCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build((self, p) -> target.asTypeDef().invokeStatic(identity, p.getFirst()).returning())).build();
        var loader = load(target, definition);
        assertEquals(7, loader.loadClass(definition.getName()).getMethod("call", Object.class).invoke(null, 7));
    }

    @Test
    public void requestedWidePrimitiveOfAGenericReturnUnboxesTheDeclaration() throws Exception {
        Supplier<Long> receiver = () -> 7L;
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.OBJECT)
            .addParameter("p0", TypeDef.parameterized(Supplier.class, TypeDef.of(Long.class)));
        var definition = ClassDef.builder("test.completeness.WideCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(method.build((self, p) -> io.micronaut.sourcegen.model.StatementDef.multi(
                // Discarded as a statement, then returned
                p.getFirst().invoke("get", TypeDef.Primitive.LONG),
                p.getFirst().invoke("get", TypeDef.Primitive.LONG).returning()))).build();
        var call = Arrays.stream(define(definition).getDeclaredMethods()).filter(m -> m.getName().equals("call")).findFirst().orElseThrow();
        assertEquals(7L, call.invoke(null, receiver));
    }

    @Test
    public void requestedWidePrimitiveOfAGenericMethodIsDiscardedAndReturned() throws Exception {
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.OBJECT)
            .addParameter("p0", TypeDef.of(Long.class));
        var definition = ClassDef.builder("test.completeness.WideGenericCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(method.build((self, p) -> io.micronaut.sourcegen.model.StatementDef.multi(
                ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("identity", TypeDef.Primitive.LONG, p.getFirst()),
                ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("identity", TypeDef.Primitive.LONG, p.getFirst()).returning()))).build();
        var call = Arrays.stream(define(definition).getDeclaredMethods()).filter(m -> m.getName().equals("call")).findFirst().orElseThrow();
        assertEquals(7L, call.invoke(null, 7L));
    }

    @Test
    public void boundedTypeVariableCanUnboxAndWiden() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Integer.class));
        var definition = ClassDef.builder("test.owner.Unbox").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(t).addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(EnclosingTypeFixtures.Calls.class).invokeStatic("unbox", TypeDef.STRING, p).returning())).build();
        assertEquals(EnclosingTypeFixtures.Calls.unbox(Integer.valueOf(1)), define(definition).getMethod("call", Integer.class).invoke(null, 1));
    }

    @Test
    public void characterInferredForAGenericReturnIsUnboxedAsACharacter() throws Exception {
        // javac: int value = identity('x') - checkcast Character, charValue
        int expected = OverloadFixtures.Calls.identity('x');
        assertCall(expected, List.of(TypeDef.Primitive.CHAR), List.of('x'),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("identity", TypeDef.Primitive.INT, p));
    }

    @Test
    public void variableBoundedByIntegerUnboxesAndWidensToLong() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Integer.class));
        var definition = ClassDef.builder("test.hardening.IntegerVariable").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("unboxing", TypeDef.STRING, p).returning())).build();
        assertEquals(OverloadFixtures.Calls.unboxingVariable(5), define(definition).getMethod("call", Integer.class).invoke(null, 5));
    }

    @Test
    public void variableBoundedByCharacterUnboxesAndWidensToInt() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Character.class));
        var definition = ClassDef.builder("test.hardening.CharacterVariable").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("character", TypeDef.STRING, p).returning())).build();
        assertEquals(OverloadFixtures.Calls.characterVariable('x'), define(definition).getMethod("call", Character.class).invoke(null, 'x'));
    }

    @Test
    public void requestedPrimitiveOfABoxedReturn() throws Exception {
        int expected = Integer.valueOf("42");
        assertCall(expected, List.of(TypeDef.STRING), List.of("42"),
            p -> ClassTypeDef.of(Integer.class).invokeStatic("valueOf", TypeDef.Primitive.INT, p));
    }

    @Test
    public void requestedSupertypeOfTheReturn() throws Exception {
        CharSequence expected = String.valueOf(42);
        assertCall(expected, List.of(TypeDef.Primitive.INT), List.of(42),
            p -> ClassTypeDef.STRING.invokeStatic("valueOf", TypeDef.of(CharSequence.class), p));
    }

    @Test
    public void requestedPrimitiveOfASpecializedBoxedReturn() throws Exception {
        Supplier<Character> supplier = () -> 'x';
        // javac: invokeinterface Supplier.get()Object, checkcast Character, charValue
        int expected = supplier.get();
        assertCall(expected, List.of(TypeDef.parameterized(Supplier.class, Character.class)), List.of(supplier),
            p -> p.getFirst().invoke("get", TypeDef.Primitive.INT));
    }

    @Test
    public void castBetweenInterfaceArraysIsChecked() throws Exception {
        Alpha[] values = {new ZetaAlphaOne()};
        var definition = ClassDef.builder("test.hardening.InterfaceArrayCast").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("values", TypeDef.of(Alpha.class).array()).returns(TypeDef.OBJECT)
                .build((self, p) -> p.getFirst().cast(TypeDef.of(Zeta.class).array()).returning())).build();
        Method call = define(definition).getMethod("call", Alpha[].class);
        assertEquals(outcome(() -> Receivers.castToZetas(values)), outcome(() -> call.invoke(null, (Object) values)));
    }

    /**
     * A call by name typed as a primitive the declared return type converts to - {@code <T extends Number> T
     * identity(T)} called for a {@code long} - used as a statement. The result is discarded after it is converted,
     * when it takes two stack slots.
     */
    @Test
    public void callConvertedToALongIsDiscardedAsALong() throws Exception {
        var definition = ClassDef.builder("test.hardening.DiscardedLong").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", Long.class).returns(TypeDef.STRING)
                .build((self, p) -> StatementDef.multi(
                    ClassTypeDef.of(BoundedBoxFixtures.Calls.class).invokeStatic("identity", TypeDef.Primitive.LONG, p.getFirst()),
                    ExpressionDef.constant("done").returning())))
            .build();
        assertEquals("done", define(definition).getMethod("call", Long.class).invoke(null, 1L));
    }

    @ParameterizedTest(name = "(char) {0} converts the number it holds, as javac converts the literal")
    @MethodSource("constantsCastToChar")
    public void numericConstantCastToCharConvertsTheNumber(ExpressionDef.Constant constant, char expected) throws Exception {
        // char run() { return (char) 1000; } for the constant of a wrapper type, a boxed Integer 1000
        Method run = run("test.hardening.ConstantToChar", TypeDef.Primitive.CHAR, List.of(), (self, p) ->
            constant.cast(TypeDef.Primitive.CHAR).returning());
        assertEquals(expected, invoke(run));
    }

    private static Stream<Arguments> constantsCastToChar() {
        return Stream.of(
            Arguments.of(Named.of("Integer 1000", ExpressionDef.constant((Object) 1000)), (char) 1000),
            Arguments.of(Named.of("Integer 5", ExpressionDef.constant((Object) 5)), (char) 5),
            Arguments.of(Named.of("Long 5", ExpressionDef.constant((Object) 5L)), (char) 5L),
            Arguments.of(Named.of("Long MIN_VALUE", ExpressionDef.constant((Object) Long.MIN_VALUE)), (char) Long.MIN_VALUE),
            Arguments.of(Named.of("Double 2.5", ExpressionDef.constant((Object) 2.5)), (char) 2.5),
            Arguments.of(Named.of("Double NaN", ExpressionDef.constant((Object) Double.NaN)), (char) Double.NaN)
        );
    }

    @ParameterizedTest(name = "a conditional typed Integer converts its {0} branch to int before boxing it")
    @MethodSource("primitiveBranchesOfAnIntegerConditional")
    public void primitiveBranchOfAConditionalIsConvertedToTheConditionalsType(TypeDef.Primitive branch, Object value, Integer expected) throws Exception {
        // Integer run(boolean flag, char c, String s) { return flag ? (Integer) (int) c : (Integer) (Object) s; }
        Method primitiveFirst = run("test.hardening.PrimitiveFirstBranch", TypeDef.of(Integer.class),
            List.of(TypeDef.Primitive.BOOLEAN, branch, TypeDef.STRING), (self, p) ->
                new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.of(Integer.class)).returning());
        Method primitiveSecond = run("test.hardening.PrimitiveSecondBranch", TypeDef.of(Integer.class),
            List.of(TypeDef.Primitive.BOOLEAN, TypeDef.OBJECT, branch), (self, p) ->
                new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.of(Integer.class)).returning());
        assertEquals(expected, invoke(primitiveFirst, true, value, "text"));
        assertEquals(expected, invoke(primitiveSecond, false, "text", value));
        assertEquals(7, invoke(primitiveSecond, true, 7, value));
    }

    private static Stream<Arguments> primitiveBranchesOfAnIntegerConditional() {
        return Stream.of(
            Arguments.of(Named.of("char", TypeDef.Primitive.CHAR), 'a', 97),
            Arguments.of(Named.of("byte", TypeDef.Primitive.BYTE), (byte) -5, -5),
            Arguments.of(Named.of("short", TypeDef.Primitive.SHORT), (short) 300, 300)
        );
    }

    @Test
    public void nullBranchOfAConditionalTypedAsAPrimitiveThrowsNullPointerException() throws Exception {
        // int run(boolean flag, Integer value) { return flag ? null : value; } - javac unboxes the null
        Method run = run("test.hardening.NullPrimitiveBranch", TypeDef.Primitive.INT, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), ExpressionDef.nullValue(), p.get(1), TypeDef.Primitive.INT).returning());
        assertEquals(1000, invoke(run, false, 1000));
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> invoke(run, true, 1000));
        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }

    @ParameterizedTest(name = "{0} invokes the declared method and converts its result")
    @MethodSource("staticCallsByNameOfAnotherReturnType")
    public void staticMethodInvokedByNameWithAnotherReturnTypeConvertsTheDeclaredResult(String name, TypeDef requested, List<TypeDef> parameters,
                                                                                         List<Object> arguments, Object expected) throws Exception {
        // long run(int a, int b) { return Math.max(a, b); }
        Method run = run("test.hardening.StaticByName", requested, parameters, (self, p) ->
            ClassTypeDef.of(Math.class).invokeStatic(name, requested, new ArrayList<>(p)).returning());
        assertEquals(expected, invoke(run, arguments.toArray()));
    }

    private static Stream<Arguments> staticCallsByNameOfAnotherReturnType() {
        return Stream.of(
            Arguments.of(Named.of("Math.max(int, int) as long", "max"), TypeDef.Primitive.LONG,
                List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT), List.of(Integer.MAX_VALUE, -3), (long) Math.max(Integer.MAX_VALUE, -3)),
            Arguments.of(Named.of("Math.abs(int) as double", "abs"), TypeDef.Primitive.DOUBLE,
                List.of(TypeDef.Primitive.INT), List.of(-7), (double) Math.abs(-7)),
            Arguments.of(Named.of("Math.round(float) as long", "round"), TypeDef.Primitive.LONG,
                List.of(TypeDef.Primitive.FLOAT), List.of(2.5f), (long) Math.round(2.5f))
        );
    }

    @ParameterizedTest(name = "a {0} cast to the box of another number is converted, not checked")
    @MethodSource("numericCastsToAnotherBox")
    public void numberCastToTheBoxOfAnotherIsConvertedNumerically(TypeDef source, TypeDef target, Object argument, Object expected) throws Exception {
        // Long run(Byte p0) { return Long.valueOf((long) p0.byteValue()); }
        Method run = run("test.hardening.NumericBoxCast", target, List.of(source), (self, p) ->
            p.get(0).cast(target).returning());
        assertEquals(expected, invoke(run, argument));
    }

    private static Stream<Arguments> numericCastsToAnotherBox() {
        return Stream.of(
            Arguments.of(Named.of("Byte to Long", ClassTypeDef.of(Byte.class)), ClassTypeDef.of(Long.class), (byte) -5, -5L),
            Arguments.of(Named.of("Integer to Character", ClassTypeDef.of(Integer.class)), ClassTypeDef.of(Character.class), 1000, (char) 1000),
            Arguments.of(Named.of("Character to Integer", ClassTypeDef.of(Character.class)), ClassTypeDef.of(Integer.class), 'a', 97),
            Arguments.of(Named.of("Double to Integer", ClassTypeDef.of(Double.class)), ClassTypeDef.of(Integer.class), 2.9, 2),
            Arguments.of(Named.of("Long to Short", ClassTypeDef.of(Long.class)), ClassTypeDef.of(Short.class), 70000L, (short) 4464),
            Arguments.of(Named.of("long to Float", TypeDef.Primitive.LONG), ClassTypeDef.of(Float.class), 1099511627776L, 1.09951163E12f),
            Arguments.of(Named.of("int to Character", TypeDef.Primitive.INT), ClassTypeDef.of(Character.class), 65, 'A'),
            Arguments.of(Named.of("char to Double", TypeDef.Primitive.CHAR), ClassTypeDef.of(Double.class), 'a', 97.0)
        );
    }

    @Test
    public void nullBoxCastToTheBoxOfAnotherNumberThrowsNullPointerException() throws Exception {
        // Long run(Integer p0) { return Long.valueOf(p0.longValue()); } - the null is unboxed
        Method run = run("test.hardening.NullNumericBoxCast", ClassTypeDef.of(Long.class), List.of(ClassTypeDef.of(Integer.class)),
            (self, p) -> p.get(0).cast(ClassTypeDef.of(Long.class)).returning());
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> invoke(run, new Object[] {null}));
        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }
}
