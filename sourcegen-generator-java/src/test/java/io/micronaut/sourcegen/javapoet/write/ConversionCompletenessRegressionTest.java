/*
 * Copyright 2017-2025 original authors
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

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument, return, assignment and cast conversions of the Java generator. Every model here was run through the
 * ASM bytecode writer, which writes and runs it as the assertions expect: it converts each argument, returned,
 * assigned, stored, branch and element value to the declared type ({@code ExpressionWriter.writeExpressionCheckCast}),
 * and names the invoked overload by its descriptor.
 *
 * @since 2.3
 */
public class ConversionCompletenessRegressionTest {

    // `String.valueOf(Object)` with a `null` constant is written `String.valueOf(null)`, which javac binds to
    // `valueOf(char[])` and throws NPE; the bytecode calls `valueOf(Object)` and returns "null".
    @Test
    void nullConstantKeepsObjectOverload() throws Exception {
        var valueOf = String.class.getMethod("valueOf", Object.class);
        var def = single("NullOverload", String.class, List.of(),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(valueOf, ExpressionDef.nullValue()).returning());
        assertEquals("null", run(def));
    }

    // `List.remove(Object)` with an int value: the bytecode boxes it and calls `remove(Object)`; the source is
    // `p0.remove(p1)`, which is `remove(int)` - here it does not even compile, as that one returns Object.
    @Test
    void intPassedToObjectParameterKeepsObjectOverload() throws Exception {
        var remove = List.class.getMethod("remove", Object.class);
        var def = single("BoxedOverload", boolean.class, List.of(TypeDef.of(List.class), TypeDef.Primitive.INT),
            (self, p) -> p.get(0).invoke(remove, p.get(1)).returning());
        var list = new ArrayList<Object>(List.of(5, 1));
        assertEquals(true, run(def, list, 1));
        assertEquals(List.of(5), list);
    }

    /**
     * A static call with one argument keeps the overload the model names, which the bytecode writer calls by its
     * descriptor after converting the argument: without a conversion in the source javac binds another overload for
     * the type of the argument. Each case names the overload javac would bind.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("argumentsOfAnotherOverload")
    void argumentKeepsTheOverloadTheModelNames(OverloadCall call) throws Exception {
        var def = single(call.className(), String.class, List.of(call.argumentType()),
            (self, p) -> ClassTypeDef.of(call.method().getDeclaringClass()).invokeStatic(call.method(), p.get(0)).returning());
        assertEquals(call.expected(), run(def, call.argument()));
    }

    static Stream<OverloadCall> argumentsOfAnotherOverload() throws NoSuchMethodException {
        return Stream.of(
            new OverloadCall("a char passed to String.valueOf(int) gives \"97\", not the \"a\" of valueOf(char)", "CharIntOverload",
                String.class.getMethod("valueOf", int.class), TypeDef.Primitive.CHAR, 'a', "97"),
            new OverloadCall("an int passed to String.valueOf(float) is converted i2f, \"1.0\", not the \"1\" of valueOf(int)",
                "IntFloatOverload", String.class.getMethod("valueOf", float.class), TypeDef.Primitive.INT, 1, "1.0"),
            new OverloadCall("a String passed to pick(Object) does not bind pick(String)", "SubtypeOverload",
                Overloads.class.getMethod("pick", Object.class), TypeDef.STRING, "a", "object"),
            new OverloadCall("an int passed to prim(long) does not bind prim(int)", "WidenedOverload",
                Overloads.class.getMethod("prim", long.class), TypeDef.Primitive.INT, 1, "long"),
            new OverloadCall("an int passed to box(Integer), which the bytecode boxes it for, does not bind box(int)", "BoxOverload",
                Overloads.class.getMethod("box", Integer.class), TypeDef.Primitive.INT, 1, "boxed"),
            new OverloadCall("an Integer passed to box(int), which the bytecode unboxes it for, does not bind box(Integer)",
                "UnboxOverload", Overloads.class.getMethod("box", int.class), TypeDef.of(Integer.class), 1, "int")
        );
    }

    /**
     * A static call of {@code method} with the parameter of the method {@code call} of {@code test.<className>}.
     *
     * @param description What the case shows
     * @param className The simple name of the class
     * @param method The overload the model names
     * @param argumentType The type of the argument
     * @param argument The argument
     * @param expected What the overload the model names returns
     */
    record OverloadCall(String description, String className, Method method, TypeDef argumentType, Object argument,
                        String expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    // A long passed to an `int` parameter is narrowed (l2i) by the bytecode writer; the source has no `(int)` cast and does not compile.
    @Test
    void longPassedToIntParameterIsNarrowed() throws Exception {
        var toString = Integer.class.getMethod("toString", int.class);
        var def = single("LongNarrowed", String.class, List.of(TypeDef.Primitive.LONG),
            (self, p) -> ClassTypeDef.of(Integer.class).invokeStatic(toString, p.get(0)).returning());
        assertEquals("5", run(def, 5L));
    }

    // `String.valueOf(Object)` with a `char[]`: the source binds `valueOf(char[])` and returns the contents instead of the identity string.
    @Test
    void charArrayPassedToObjectParameterKeepsObjectOverload() throws Exception {
        var valueOf = String.class.getMethod("valueOf", Object.class);
        var def = single("CharArrayOverload", String.class, List.of(TypeDef.of(char[].class)),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(valueOf, p.get(0)).returning());
        assertTrue(((String) run(def, (Object) new char[]{'a'})).startsWith("[C@"));
    }

    // A `Number` passed to an `int` parameter is unboxed by the bytecode writer (`Number.intValue`); the source passes it as is and does not compile.
    @Test
    void numberPassedToIntParameterIsUnboxed() throws Exception {
        var toString = Integer.class.getMethod("toString", int.class);
        var def = single("NumberUnboxed", String.class, List.of(TypeDef.of(Number.class)),
            (self, p) -> ClassTypeDef.of(Integer.class).invokeStatic(toString, p.get(0)).returning());
        assertEquals("5", run(def, 5));
    }

    // The bytecode writer casts the initializer of a local to the local's type; the source writes `String text = p0` with an Object and does not compile.
    @Test
    void objectAssignedToTypedLocal() throws Exception {
        var def = single("AssignLocal", String.class, List.of(TypeDef.OBJECT),
            (self, p) -> {
            var local = new VariableDef.Local("text", TypeDef.STRING);
            return StatementDef.multi(local.defineAndAssign(p.get(0)), local.returning());
        });
        assertEquals("a", run(def, "a"));
    }

    // As above for an assignment: `text = p0` with an Object value.
    @Test
    void objectAssignedToTypedLocalLater() throws Exception {
        var def = single("ReassignLocal", String.class, List.of(TypeDef.OBJECT), (self, p) -> {
            var local = new VariableDef.Local("text", TypeDef.STRING);
            return StatementDef.multi(local.defineAndAssign(ExpressionDef.constant("x")), local.assign(p.get(0)), local.returning());
        });
        assertEquals("a", run(def, "a"));
    }

    // The bytecode writer casts each element of an initialized array to the component type; the source writes `new String[]{p0}` with an Object.
    @Test
    void objectElementsOfTypedArray() throws Exception {
        var def = single("ArrayElements", String[].class, List.of(TypeDef.OBJECT),
            (self, p) -> TypeDef.STRING.array().instantiate(p.get(0)).returning());
        assertEquals("a", ((String[]) run(def, "a"))[0]);
    }

    // The bytecode writer casts a value put to a field to the field type; the source writes `this.text = p0` with an Object.
    @Test
    void objectAssignedToTypedField() throws Exception {
        var field = FieldDef.builder("text", String.class).addModifiers(Modifier.PUBLIC).build();
        var def = ClassDef.builder("test.PutTypedField").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(String.class)
                .build((self, p) -> StatementDef.multi(self.field(field).put(p.get(0)), self.field(field).returning()))).build();
        assertEquals("a", run(def, "a"));
    }

    // A cast of `null` is dropped, so `pick((String) null)` becomes `pick(null)`, which is ambiguous between `pick(String)` and `pick(Integer)`.
    @Test
    void castNullKeepsItsOverload() throws Exception {
        var pick = Overloads.class.getMethod("pick", String.class);
        var def = single("CastNullOverload", String.class, List.of(),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(pick, ExpressionDef.nullValue().cast(TypeDef.STRING)).returning());
        assertEquals("string", run(def));
    }

    // As for methods, for `new Overloads(Object)` with a String value: javac binds `Overloads(String)`.
    @Test
    void stringPassedToObjectConstructorKeepsObjectOverload() throws Exception {
        var constructor = Overloads.class.getConstructor(Object.class);
        var def = single("ConstructorOverload", Overloads.class, List.of(TypeDef.STRING),
            (self, p) -> ClassTypeDef.of(Overloads.class).instantiate(constructor, p.get(0)).returning());
        assertEquals("object", ((Overloads) run(def, "a")).kind);
    }

    // The bytecode writer narrows a returned long to the int the method returns; the source has no cast.
    @Test
    void longReturnedFromIntMethodIsNarrowed() throws Exception {
        var def = single("ReturnNarrowed", int.class, List.of(TypeDef.Primitive.LONG), (self, p) -> p.get(0).returning());
        assertEquals(5, run(def, 5L));
    }

    // An int constant passed to a `byte` parameter: `Byte.toString(1)` does not compile, a method argument is not narrowed implicitly.
    @Test
    void intConstantPassedToByteParameter() throws Exception {
        var toString = Byte.class.getMethod("toString", byte.class);
        var def = single("ByteConstant", String.class, List.of(),
            (self, p) -> ClassTypeDef.of(Byte.class).invokeStatic(toString, ExpressionDef.constant(1)).returning());
        assertEquals("1", run(def));
    }

    // A cast of an Object to `long` is `((Number) o).longValue()` in bytecode, which takes an Integer; the source `(long) p0` is
    // `(Long) p0` unboxed, which throws ClassCastException.
    @Test
    void objectCastToLongUnboxesAnyNumber() throws Exception {
        var def = single("CastUnboxed", long.class, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.LONG).returning());
        assertEquals(5L, run(def, 5));
    }

    // The bytecode writer casts both branches of a conditional to its type. As a receiver the source conditional keeps the type of
    // its Object branches: `(c ? p0 : p1).length()`.
    @Test
    void typedConditionalOfObjectBranchesAsReceiver() throws Exception {
        var length = String.class.getMethod("length");
        var def = single("ConditionalReceiver", int.class, List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isNonNull(), p.get(0), p.get(1), TypeDef.STRING).invoke(length).returning());
        assertEquals(1, run(def, "a", "bb"));
    }

    // The bytecode writer casts the results of a switch expression to its type; the source yields the Object values as they are.
    @Test
    void typedSwitchOfObjectResultsReturned() throws Exception {
        var def = single("SwitchResults", String.class, List.of(TypeDef.Primitive.INT, TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING, Map.of(ExpressionDef.constant(1), p.get(1)), p.get(2)).returning());
        assertEquals("a", run(def, 1, "a", "b"));
    }

    // As above for a yield of a block case: `yield p1` with an Object in a String switch.
    @Test
    void typedSwitchYieldingObject() throws Exception {
        var def = single("SwitchYields", String.class, List.of(TypeDef.Primitive.INT, TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING,
                Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING, p.get(1).returning())),
                new ExpressionDef.SwitchYieldCase(TypeDef.STRING, p.get(2).returning())).returning());
        assertEquals("a", run(def, 1, "a", "b"));
    }

    // A value of an interface passed where a generated class implementing it is declared: bytecode checkcasts, the source
    // casts only between two loaded classes, from Object, or from a variable.
    @Test
    void interfaceValuePassedToGeneratedImplementationParameter() throws Exception {
        var impl = ClassDef.builder("test.DowncastImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(TypeDef.of(Runnable.class))
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).overrides().build((self, p) -> new StatementDef.Return(null))).build();
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", impl.asTypeDef()).returns(impl.asTypeDef())
            .build((self, p) -> p.get(0).returning());
        var def = ClassDef.builder("test.DowncastArgument").addModifiers(Modifier.PUBLIC).addMethod(take)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Runnable.class).returns(Object.class)
                .build((self, p) -> self.invoke(take, p.get(0)).returning())).build();
        try (var loader = compile(impl, def)) {
            var value = loader.loadClass(impl.getName()).getConstructor().newInstance();
            var cls = loader.loadClass(def.getName());
            assertEquals(value, cls.getMethod("call", Runnable.class).invoke(cls.getConstructor().newInstance(), value));
        }
    }

    // As above for a return.
    @Test
    void interfaceValueReturnedAsGeneratedImplementation() throws Exception {
        var impl = ClassDef.builder("test.DowncastReturnImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(TypeDef.of(Runnable.class))
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).overrides().build((self, p) -> new StatementDef.Return(null))).build();
        var def = ClassDef.builder("test.DowncastReturn").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Runnable.class).returns(impl.asTypeDef())
                .build((self, p) -> p.get(0).returning())).build();
        try (var loader = compile(impl, def)) {
            var value = loader.loadClass(impl.getName()).getConstructor().newInstance();
            var cls = loader.loadClass(def.getName());
            assertEquals(value, cls.getMethod("call", Runnable.class).invoke(cls.getConstructor().newInstance(), value));
        }
    }

    // A `Collection<String>` passed to a `List` parameter: the value type is parameterized, so the downcast the bytecode writer
    // makes is not written.
    @Test
    void parameterizedValuePassedToRawSubtypeParameter() throws Exception {
        var unmodifiable = Collections.class.getMethod("unmodifiableList", List.class);
        var def = single("ParameterizedDowncast", Object.class, List.of(TypeDef.parameterized(Collection.class, String.class)),
            (self, p) -> ClassTypeDef.of(Collections.class).invokeStatic(unmodifiable, p.get(0)).returning());
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    // A raw `Collection` passed to a `List<String>` parameter of a generated method: the parameter type is parameterized, so no cast.
    @Test
    void rawValuePassedToParameterizedSubtypeParameter() throws Exception {
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.parameterized(List.class, String.class)).returns(Object.class)
            .build((self, p) -> p.get(0).returning());
        var def = ClassDef.builder("test.RawDowncast").addModifiers(Modifier.PUBLIC).addMethod(take)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Collection.class).returns(Object.class)
                .build((self, p) -> self.invoke(take, p.get(0)).returning())).build();
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    // A value whose type is known by name only, `ClassTypeDef.of("java.lang.CharSequence")`, passed to a String parameter: no cast.
    @Test
    void namedTypeValuePassedToSubtypeParameter() throws Exception {
        var concat = String.class.getMethod("concat", String.class);
        var def = single("NamedDowncast", String.class, List.of(ClassTypeDef.of("java.lang.CharSequence")),
            (self, p) -> ExpressionDef.constant("a").invoke(concat, p.get(0)).returning());
        assertEquals("ab", run(def, "b"));
    }

    // A value of a type variable passed to an `int` parameter, which the bytecode writer unboxes: no cast.
    @Test
    void typeVariableValuePassedToPrimitiveParameter() throws Exception {
        var toString = Integer.class.getMethod("toString", int.class);
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.VariableUnboxed").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("p0", t).returns(String.class)
                .build((self, p) -> ClassTypeDef.of(Integer.class).invokeStatic(toString, p.get(0)).returning())).build();
        assertEquals("5", run(def, 5));
    }

    // The bytecode flattens `p0 + (p1 + "x")` into one concatenation: "12x". The source drops the grouping: `p0 + p1 + "x"` is "3x".
    @Test
    void nestedConcatenationOfNumbersStaysAConcatenation() throws Exception {
        var def = single("ConcatNested", String.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> new ExpressionDef.StringConcatenation(p.get(0),
                new ExpressionDef.StringConcatenation(p.get(1), ExpressionDef.constant("x"))).returning());
        assertEquals("12x", run(def, 1, 2));
    }

    // A concatenation of two non-strings is written `String.valueOf(left) + right`, which for a `char[]` binds `valueOf(char[])`
    // and writes the contents, where the concatenation writes the identity string.
    @Test
    void concatenationOfCharArrayWritesItsIdentity() throws Exception {
        var def = single("ConcatCharArray", String.class, List.of(TypeDef.of(char[].class), TypeDef.Primitive.INT),
            (self, p) -> new ExpressionDef.StringConcatenation(p.get(0), p.get(1)).returning());
        assertTrue(((String) run(def, new char[]{'a'}, 1)).startsWith("[C@"));
    }

    // `List.add(Object)` on a `List<String>` receiver with an Object value: the erased parameter is Object, so nothing is cast, but
    // javac sees `add(String)`.
    @Test
    void objectPassedThroughParameterizedReceiver() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var def = single("ParameterizedReceiver", boolean.class, List.of(TypeDef.parameterized(List.class, String.class), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(add, p.get(1)).returning());
        assertEquals(true, run(def, new ArrayList<>(), "a"));
    }

    // As above on a `List<E>` of the caller's own variable: the value needs `(E)`.
    @Test
    void objectPassedThroughReceiverOfCallerVariable() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var e = TypeDef.variable("E");
        var def = ClassDef.builder("test.VariableReceiver").addModifiers(Modifier.PUBLIC).addTypeVariable(e)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("p0", TypeDef.parameterized(ClassTypeDef.of(List.class), e)).addParameter("p1", Object.class).returns(boolean.class)
                .build((self, p) -> p.get(0).invoke(add, p.get(1)).returning())).build();
        assertEquals(true, run(def, new ArrayList<>(), "a"));
    }

    // As above for `Comparable<String>.compareTo(Object)`.
    @Test
    void integerPassedThroughParameterizedComparable() throws Exception {
        var compareTo = Comparable.class.getMethod("compareTo", Object.class);
        var def = single("ComparableReceiver", int.class, List.of(TypeDef.parameterized(Comparable.class, String.class), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(compareTo, p.get(1)).returning());
        assertEquals(0, run(def, "a", "a"));
    }

    // As above for a static field: `PutTypedStaticField.text = p0` with an Object.
    @Test
    void objectAssignedToTypedStaticField() throws Exception {
        var field = FieldDef.builder("text", String.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        var type = ClassTypeDef.of("test.PutTypedStaticField");
        var def = ClassDef.builder("test.PutTypedStaticField").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", Object.class).returns(String.class)
                .build((self, p) -> StatementDef.multi(type.getStaticField(field).put(p.get(0)), type.getStaticField(field).returning()))).build();
        assertEquals("a", run(def, "a"));
    }

    // The method modelled as its element declares it, `add(E)`: the variable of the compiled receiver is out of scope and written as
    // `(Object) p1`, where the receiver's type argument `String` is needed.
    @Test
    void objectPassedToDeclaredVariableOfCompiledReceiver() throws Exception {
        // The method as a MethodElement describes it: `add(E)`
        var add = MethodDef.builder("add").addModifiers(Modifier.PUBLIC).addParameter("e", TypeDef.variable("E")).returns(boolean.class).build();
        var def = single("CompiledVariable", boolean.class, List.of(TypeDef.parameterized(List.class, String.class), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(add, p.get(1)).returning());
        assertEquals(true, run(def, new ArrayList<>(), "a"));
    }

    // A constructor `Box(T)` of a generated class, instantiated as `Box<String>`: NewInstance passes no receiver arguments, so the
    // value is cast `(Object)` instead of `(String)`.
    @Test
    void objectPassedToClassVariableOfParameterizedGeneratedConstructor() throws Exception {
        var t = TypeDef.variable("T");
        var box = ClassDef.builder("test.ParameterizedConstructedBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", t).build((self, p) -> new StatementDef.Return(null)))
            .build();
        var def = single("ParameterizedConstruction", Object.class, List.of(TypeDef.OBJECT),
            (self, p) -> TypeDef.parameterized(box.asTypeDef(), TypeDef.STRING).instantiate(List.of(t), p.get(0)).returning());
        try (var loader = compile(box, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(box.getName(), cls.getMethod("call", Object.class).invoke(cls.getConstructor().newInstance(), "a").getClass().getName());
        }
    }

    // As above for `super(value)` where the superclass is `Box<String>`.
    @Test
    void objectPassedToClassVariableOfGeneratedSuperConstructor() throws Exception {
        var t = TypeDef.variable("T");
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", t).build((self, p) -> new StatementDef.Return(null));
        var box = ClassDef.builder("test.SuperBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addMethod(constructor).build();
        var def = ClassDef.builder("test.SuperBoxChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(box.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", Object.class)
                .build((self, p) -> self.superRef().invokeConstructor(constructor, p.get(0)))).build();
        try (var loader = compile(box, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(def.getName(), cls.getConstructor(Object.class).newInstance("a").getClass().getName());
        }
    }

    // A cast of an Integer to `short` is `Number.shortValue()` in bytecode; `(short) p0` of an Integer does not compile.
    @Test
    void boxedCastToNarrowerPrimitive() throws Exception {
        var def = single("BoxedNarrowed", short.class, List.of(TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).cast(TypeDef.Primitive.SHORT).returning());
        assertEquals((short) 5, run(def, 5));
    }

    // As above where the conditional is returned: `return c ? p0 : p1` from a String method - an argument is cast, a return is not.
    @Test
    void typedConditionalOfObjectBranchesReturned() throws Exception {
        var def = single("ConditionalReturned", String.class, List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isNonNull(), p.get(0), p.get(1), TypeDef.STRING).returning());
        assertEquals("a", run(def, "a", "b"));
    }

    // An operand of a concatenation is written without parentheses: `"x" + p0 ? "a" : "b"`.
    @Test
    void concatenationOfConditional() throws Exception {
        var def = single("ConcatConditional", String.class, List.of(TypeDef.Primitive.BOOLEAN),
            (self, p) -> new ExpressionDef.StringConcatenation(ExpressionDef.constant("x"),
                p.get(0).isTrue().doIfElse(ExpressionDef.constant("a"), ExpressionDef.constant("b"))).returning());
        assertEquals("xa", run(def, true));
    }

    // An operand of a concatenation is written without parentheses: `"x" + p0 + p1` is "x12", the model says "x3".
    @Test
    void concatenationOfSum() throws Exception {
        var def = single("ConcatSum", String.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> new ExpressionDef.StringConcatenation(ExpressionDef.constant("x"),
                p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1))).returning());
        assertEquals("x3", run(def, 1, 2));
    }

    // `<T extends Comparable<T>> max(T, T)` with a String and an Object: only the Object is cast, to raw `Comparable`, and javac
    // cannot infer T from `String` and `Comparable` together.
    @Test
    void recursiveBoundInferredFromTypedAndObjectValues() throws Exception {
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var max = MethodDef.builder("max").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("a", t).addParameter("b", t).returns(Object.class).build((self, p) -> p.get(1).returning());
        var def = ClassDef.builder("test.RecursiveMixed").addModifiers(Modifier.PUBLIC).addMethod(max)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", String.class).addParameter("p1", Object.class)
                .returns(Object.class).build((self, p) -> self.invoke(max, p.get(0), p.get(1)).returning())).build();
        assertEquals("b", run(def, "a", "b"));
    }

    // As for compiled methods, for overloads the class being written declares itself.
    @Test
    void stringPassedToGeneratedObjectOverload() throws Exception {
        var takeObject = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var takeString = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("string").returning());
        var def = ClassDef.builder("test.GeneratedOverload").addModifiers(Modifier.PUBLIC).addMethod(takeObject).addMethod(takeString)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", String.class).returns(String.class)
                .build((self, p) -> self.invoke(takeObject, p.get(0)).returning())).build();
        assertEquals("object", run(def, "a"));
    }

    // The model types `a + b` of two bytes as a byte; Java promotes it to an int, which a return does not narrow.
    @Test
    void byteSumReturnedAsByte() throws Exception {
        var def = single("ByteSum", byte.class, List.of(TypeDef.Primitive.BYTE, TypeDef.Primitive.BYTE),
            (self, p) -> p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).returning());
        assertEquals((byte) 3, run(def, (byte) 1, (byte) 2));
    }

    // As above for an argument: `Short.toString(p0 * p1)`.
    @Test
    void shortProductPassedToShortParameter() throws Exception {
        var toString = Short.class.getMethod("toString", short.class);
        var def = single("ShortProduct", String.class, List.of(TypeDef.Primitive.SHORT, TypeDef.Primitive.SHORT),
            (self, p) -> ClassTypeDef.of(Short.class).invokeStatic(toString,
                p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, p.get(1))).returning());
        assertEquals("6", run(def, (short) 2, (short) 3));
    }

    // A negated short: `return -p0` is an int.
    @Test
    void negatedShortReturnedAsShort() throws Exception {
        var def = single("ShortNegated", short.class, List.of(TypeDef.Primitive.SHORT),
            (self, p) -> p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning());
        assertEquals((short) -5, run(def, (short) 5));
    }

    // The bytecode converts each branch to the conditional's type, Object: an Integer stays an Integer. Java's
    // conditional of an Integer and a Long is numeric, and unboxes and widens the Integer to a long.
    @Test
    void conditionalOfIntegerAndLongTypedObject() throws Exception {
        var def = single("MixedBoxedConditional", Object.class, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer.class), TypeDef.of(Long.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT).returning());
        assertEquals(Integer.valueOf(1), run(def, true, 1, 2L));
    }

    // Java's conditional of an Integer and an int is an int: a null Integer throws NPE, where the bytecode returns it.
    @Test
    void conditionalOfNullIntegerAndIntTypedObject() throws Exception {
        var def = single("NullBoxedConditional", Object.class, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), ExpressionDef.constant(5), TypeDef.OBJECT).returning());
        assertNull(run(def, true, null));
    }

    // Java's conditional of an int and a char is an int: the char comes back as the Integer 97, not the Character.
    @Test
    void conditionalOfIntAndCharTypedObject() throws Exception {
        var def = single("IntCharConditional", Object.class, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT, TypeDef.Primitive.CHAR),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT).returning());
        assertEquals('a', run(def, false, 1, 'a'));
    }

    // As for a numeric conditional, one of a Boolean and a boolean is a boolean in Java, which unboxes a null.
    @Test
    void conditionalOfNullBooleanAndBooleanTypedObject() throws Exception {
        var def = single("NullBooleanConditional", Object.class, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Boolean.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), ExpressionDef.constant(true), TypeDef.OBJECT).returning());
        assertNull(run(def, true, null));
    }

    // `p0 instanceof Integer` of a String does not compile; the bytecode answers false.
    @Test
    void instanceofOfUnrelatedFinalType() throws Exception {
        var def = single("UnrelatedInstanceof", boolean.class, List.of(TypeDef.STRING),
            (self, p) -> p.get(0).instanceOf(ClassTypeDef.of(Integer.class)).returning());
        assertEquals(false, run(def, "a"));
    }

    // `(Integer) p0` of a String does not compile; the bytecode checkcasts, which a null passes.
    @Test
    void castBetweenUnrelatedTypes() throws Exception {
        var def = single("UnrelatedCast", Object.class, List.of(TypeDef.STRING),
            (self, p) -> p.get(0).isNull().doIfElse(p.get(0).cast(Integer.class).returning(), ExpressionDef.nullValue().returning()));
        assertNull(run(def, (Object) null));
        assertNull(run(def, "a"));
    }

    // `p0 == p1` of a String and an Integer does not compile; the bytecode compares the references.
    @Test
    void referenceEqualityOfUnrelatedTypes() throws Exception {
        var def = single("UnrelatedEquality", boolean.class, List.of(TypeDef.STRING, TypeDef.of(Integer.class)),
            (self, p) -> p.get(0).equalsReferentially(p.get(1)).returning());
        assertEquals(false, run(def, "a", 1));
    }

    // The bytecode puts a field's initializer to the field, converted to its type; the source writes it as it is:
    // `String text = (Object) "a"`.
    @Test
    void fieldInitializerConvertedToFieldType() throws Exception {
        var field = FieldDef.builder("text", String.class).addModifiers(Modifier.PUBLIC)
            .initializer(ExpressionDef.constant("a").cast(Object.class)).build();
        var def = ClassDef.builder("test.ConvertedInitializer").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.field(field).returning())).build();
        assertEquals("a", run(def));
    }

    // A cast to the type the value already has is not written - `(int) (p0 + p1)` is `p0 + p1` - nor are the
    // parentheses a concatenation needs: `"x" + p0 + p1` is "x12".
    @Test
    void elidedCastOfSumInConcatenation() throws Exception {
        var def = single("ElidedCastConcat", String.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> ExpressionDef.constant("x").stringConcat(
                p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).cast(TypeDef.Primitive.INT)).returning());
        assertEquals("x3", run(def, 1, 2));
    }

    // As above for a conditional: `"x" + p0 ? "a" : "b"` does not compile.
    @Test
    void elidedCastOfConditionalInConcatenation() throws Exception {
        var def = single("ElidedCastConditional", String.class, List.of(TypeDef.Primitive.BOOLEAN),
            (self, p) -> ExpressionDef.constant("x").stringConcat(
                new ExpressionDef.IfElse(p.get(0).isTrue(), ExpressionDef.constant("a"), ExpressionDef.constant("b"), TypeDef.STRING)
                    .cast(TypeDef.STRING)).returning());
        assertEquals("xa", run(def, true));
    }

    // An Object cast to `char` and to `boolean`: unboxed through the wrapper.
    @Test
    void objectCastToCharAndBoolean() throws Exception {
        var def = single("ObjectToChar", String.class, List.of(TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic("valueOf", List.of(TypeDef.Primitive.CHAR), TypeDef.STRING,
                    List.of(p.get(0).cast(TypeDef.Primitive.CHAR)))
                .stringConcat(ClassTypeDef.of(String.class).invokeStatic("valueOf", List.of(TypeDef.Primitive.BOOLEAN), TypeDef.STRING,
                    List.of(p.get(1).cast(TypeDef.Primitive.BOOLEAN)))).returning());
        assertEquals("atrue", run(def, 'a', true));
    }

    // A switch expression of an Object type, standalone as an operand of a concatenation: Java promotes its char
    // and int results to an int, "x97", where the bytecode boxes the char to its type, "xa".
    @Test
    void switchOfCharAndIntInConcatenation() throws Exception {
        var def = single("NumericSwitchConcat", String.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.CHAR, TypeDef.Primitive.INT),
            (self, p) -> ExpressionDef.constant("x").stringConcat(p.get(0).asExpressionSwitch(TypeDef.OBJECT,
                Map.of(ExpressionDef.constant(1), p.get(1)), p.get(2))).returning());
        assertEquals("xa", run(def, 1, 'a', 2));
    }

    // Structural equality of an int and a double: the bytecode converts the right operand to the type of the left,
    // `2 == (int) 2.5`, where `p0 == p1` compares the doubles.
    @Test
    void structuralEqualityConvertsRightOperandToLeftType() throws Exception {
        var def = single("MixedPrimitiveEquality", boolean.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.DOUBLE),
            (self, p) -> p.get(0).equalsStructurally(p.get(1)).returning());
        assertEquals(true, run(def, 2, 2.5d));
    }

    // Structural equality of an Object and an int: the bytecode unboxes the Object through Number; `p0 == p1` does not
    // compile.
    @Test
    void structuralEqualityOfObjectAndInt() throws Exception {
        var def = single("ObjectIntEquality", boolean.class, List.of(TypeDef.OBJECT, TypeDef.Primitive.INT),
            (self, p) -> p.get(0).equalsStructurally(p.get(1)).returning());
        assertEquals(true, run(def, 5, 5));
    }

    // The operand of `instanceof` is written without parentheses: `p0 ? p1 : p2 instanceof String` tests p2 alone.
    @Test
    void instanceofOfConditional() throws Exception {
        var def = single("ConditionalInstanceof", boolean.class, List.of(TypeDef.Primitive.BOOLEAN, TypeDef.OBJECT, TypeDef.OBJECT),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT)
                .instanceOf(ClassTypeDef.of(String.class)).returning());
        assertEquals(true, run(def, true, "a", 1));
    }

    // A cast of null is not written, which leaves a concatenation of `null` and an int: `null + p0`.
    @Test
    void nullStringConcatenatedWithInt() throws Exception {
        var def = single("NullConcat", String.class, List.of(TypeDef.Primitive.INT),
            (self, p) -> ExpressionDef.nullValue().cast(TypeDef.STRING).stringConcat(p.get(0)).returning());
        assertEquals("null1", run(def, 1));
    }

    // `p0 instanceof List<String>` of a parameterized type.
    @Test
    void instanceofOfParameterizedType() throws Exception {
        var def = single("ParameterizedInstanceof", boolean.class, List.of(TypeDef.OBJECT),
            (self, p) -> p.get(0).instanceOf(TypeDef.parameterized(List.class, String.class)).returning());
        assertEquals(true, run(def, new ArrayList<>()));
    }

    @Test
    void conditionalOfAReferenceTypeKeepsTheBoxOfEachPrimitiveBranch() throws Exception {
        // (Object) (flag ? i : l): the model types the conditional as Object, so no numeric promotion
        var result = run(single("ConditionalPrimitiveBranches", TypeDef.OBJECT,
            List.of(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT, TypeDef.Primitive.LONG),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT).returning()),
            true, 5, 9L);
        assertEquals(Integer.valueOf(5), result);
    }

    @Test
    void conditionalOfAReferenceTypeDoesNotUnboxBoxedBranches() throws Exception {
        var result = run(single("ConditionalBoxedBranches", TypeDef.OBJECT,
            List.of(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer.class), TypeDef.of(Double.class)),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.OBJECT).returning()),
            true, 5, 2.5);
        assertEquals(Integer.valueOf(5), result);
    }

    @Test
    void byteArithmeticIsNarrowedToByte() throws Exception {
        // The model types a + b as byte, the type of its left operand
        var result = run(single("ByteSum", TypeDef.Primitive.BYTE, List.of(TypeDef.Primitive.BYTE, TypeDef.Primitive.BYTE),
            (self, p) -> p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).returning()),
            (byte) 100, (byte) 100);
        assertEquals((byte) -56, result);
    }

    /**
     * The model types an operation of bytes or shorts, and a negated char, by its operand - {@code short - short} is a
     * {@code short} - where Java promotes it to an int. The bytecode writers narrow the result, so the source writes
     * the cast to the operation's type wherever the value goes, not only where the target is that type: passed on as
     * an Object it is the box of that type, compared and concatenated it is the narrowed value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("narrowOperations")
    void narrowOperationHasTheTypeOfTheModel(NarrowOperation operation) throws Exception {
        var def = single(operation.className(), operation.returns(), operation.parameters(), (self, p) -> operation.body().apply(p).returning());
        assertEquals(operation.expected(), run(def, operation.arguments()));
    }

    static Stream<NarrowOperation> narrowOperations() {
        var subtraction = ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION;
        var addition = ExpressionDef.MathBinaryOperation.OpType.ADDITION;
        var leftShift = ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT;
        var unsignedShift = ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
        var negate = ExpressionDef.MathUnaryOperation.OpType.NEGATE;
        var shorts = List.<TypeDef>of(TypeDef.Primitive.SHORT, TypeDef.Primitive.SHORT);
        var bytes = List.<TypeDef>of(TypeDef.Primitive.BYTE, TypeDef.Primitive.BYTE);
        return Stream.of(
            new NarrowOperation("short - short passed on as Object is a Short", "ShortDifferenceAsObject", TypeDef.OBJECT, shorts,
                p -> p.get(0).math(subtraction, p.get(1)).cast(TypeDef.OBJECT), new Object[]{(short) -32768, (short) 1}, (short) 32767),
            new NarrowOperation("byte >>> byte passed on as Object is a Byte", "ByteShiftAsObject", TypeDef.OBJECT, bytes,
                p -> p.get(0).math(unsignedShift, p.get(1)).cast(TypeDef.OBJECT), new Object[]{(byte) -1, (byte) 1}, (byte) -1),
            new NarrowOperation("short << short widened to long is the short", "ShortShiftAsLong", TypeDef.Primitive.LONG, shorts,
                p -> p.get(0).math(leftShift, p.get(1)).cast(TypeDef.Primitive.LONG), new Object[]{(short) 1, (short) 15}, -32768L),
            new NarrowOperation("short + short compared to 0 compares the short", "ShortSumCompared", TypeDef.Primitive.BOOLEAN, shorts,
                p -> p.get(0).math(addition, p.get(1)).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, ExpressionDef.constant(0)),
                new Object[]{(short) 32767, (short) 1}, false),
            new NarrowOperation("byte << byte concatenated prints the byte", "ByteShiftConcatenated", TypeDef.STRING, bytes,
                p -> ExpressionDef.constant("r=").stringConcat(p.get(0).math(leftShift, p.get(1))), new Object[]{(byte) 64, (byte) 1}, "r=-128"),
            new NarrowOperation("a negated short concatenated prints the short", "ShortNegatedConcatenated", TypeDef.STRING,
                List.of(TypeDef.Primitive.SHORT), p -> ExpressionDef.constant("").stringConcat(p.get(0).math(negate)),
                new Object[]{(short) -32768}, "-32768"),
            new NarrowOperation("a negated char passed on as Object is a Character", "CharNegatedAsObject", TypeDef.OBJECT,
                List.of(TypeDef.Primitive.CHAR), p -> p.get(0).math(negate).cast(TypeDef.OBJECT), new Object[]{'a'}, 'ﾟ'),
            new NarrowOperation("a negated char constant concatenated prints the char", "CharConstantNegatedConcatenated", TypeDef.STRING,
                List.of(), p -> ExpressionDef.constant("").stringConcat(ExpressionDef.constant('a').math(negate)), new Object[]{}, "ﾟ"),
            new NarrowOperation("byte + byte + byte narrows each operation", "ByteSumOfSum", TypeDef.Primitive.INT, bytes,
                p -> p.get(0).math(addition, p.get(1)).math(addition, p.get(1)).cast(TypeDef.Primitive.INT),
                new Object[]{(byte) 127, (byte) 1}, -127)
        );
    }

    /**
     * An operation of narrow operands in the method {@code call} of {@code test.<className>}.
     *
     * @param description What the case shows
     * @param className   The simple name of the class
     * @param returns     The return type of the method
     * @param parameters  The parameter types of the method
     * @param body        The returned expression, of the parameters
     * @param arguments   The arguments of the call
     * @param expected    What the method returns
     */
    record NarrowOperation(String description, String className, TypeDef returns, List<TypeDef> parameters,
                           Function<List<VariableDef.MethodParameter>, ExpressionDef> body, Object[] arguments, Object expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * A cast javac rejects as inconvertible - of a primitive to another box than its own, between a primitive and an
     * array, of a String or a List to a primitive - which the bytecode writers write as the box of the value checked
     * against the target, or the value checked against the box of the target: the source writes the value through
     * Object, which compiles and fails the same way.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inconvertibleCasts")
    void inconvertibleCastThrowsAsTheBytecodeDoes(InconvertibleCast cast) {
        var def = single(cast.className(), cast.target(), List.of(cast.source()), (self, p) -> p.get(0).cast(cast.target()).returning());
        assertThrows(ClassCastException.class, () -> run(def, cast.argument()));
    }

    static Stream<InconvertibleCast> inconvertibleCasts() {
        return Stream.of(
            new InconvertibleCast("an int cast to String", "IntToString", TypeDef.Primitive.INT, TypeDef.STRING, 5),
            new InconvertibleCast("an int cast to List<String>", "IntToList", TypeDef.Primitive.INT,
                TypeDef.parameterized(List.class, String.class), 5),
            new InconvertibleCast("an int cast to int[]", "IntToArray", TypeDef.Primitive.INT, TypeDef.of(int[].class), 5),
            new InconvertibleCast("an int[] cast to int", "ArrayToInt", TypeDef.of(int[].class), TypeDef.Primitive.INT, new int[] {1}),
            new InconvertibleCast("an int[] cast to char[]", "IntArrayToCharArray", TypeDef.of(int[].class), TypeDef.of(char[].class),
                new int[] {1}),
            new InconvertibleCast("a String cast to int", "StringToInt", TypeDef.STRING, TypeDef.Primitive.INT, "1"),
            new InconvertibleCast("a String cast to char", "StringToChar", TypeDef.STRING, TypeDef.Primitive.CHAR, "a"),
            new InconvertibleCast("a List cast to char", "ListToChar", TypeDef.of(List.class), TypeDef.Primitive.CHAR, new ArrayList<>())
        );
    }

    /**
     * A cast of the parameter of the method {@code call} of {@code test.<className>}, which the bytecode writers write
     * to throw ClassCastException.
     *
     * @param description What the case shows
     * @param className   The simple name of the class
     * @param source      The type of the parameter
     * @param target      The type cast to, which the method returns
     * @param argument    The argument
     */
    record InconvertibleCast(String description, String className, TypeDef source, TypeDef target, Object argument) {
        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * The bytecode writers convert each result of a conditional to the type the model gives it; Java promotes the
     * results to a common type, or rejects them. The source converts each result as the bytecode does: a box to the
     * primitive through its number, and what javac cannot convert through Object, which throws as the bytecode's
     * checked cast does.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("conditionalResults")
    void conditionalResultIsConvertedAsTheBytecodeConvertsIt(ConditionalResult conditional) throws Exception {
        var def = single(conditional.className(), conditional.type(), List.of(TypeDef.Primitive.BOOLEAN, conditional.first(), conditional.second()),
            (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), conditional.type()).returning());
        if (conditional.expected() instanceof Class<?> exception) {
            assertThrows(exception.asSubclass(Throwable.class), () -> run(def, false, conditional.firstArgument(), conditional.secondArgument()));
        } else {
            assertEquals(conditional.expected(), run(def, false, conditional.firstArgument(), conditional.secondArgument()));
        }
    }

    static Stream<ConditionalResult> conditionalResults() {
        return Stream.of(
            new ConditionalResult("an Integer result of a char conditional is converted to its char",
                "IntegerCharResult", TypeDef.Primitive.CHAR, TypeDef.Primitive.INT, 1, TypeDef.of(Integer.class), 97, (char) 97),
            new ConditionalResult("a Character result of an int conditional is its char", "CharacterIntResult",
                TypeDef.Primitive.INT, TypeDef.Primitive.INT, 1, TypeDef.of(Character.class), 'a', 97),
            new ConditionalResult("a Long result of an int conditional is narrowed, not a lossy long", "LongIntResult",
                TypeDef.Primitive.INT, TypeDef.Primitive.INT, 1, TypeDef.of(Long.class), 5L, 5),
            new ConditionalResult("a String result of an int conditional is checked as a Number", "StringIntResult",
                TypeDef.Primitive.INT, TypeDef.Primitive.INT, 1, TypeDef.STRING, "a", ClassCastException.class),
            new ConditionalResult("an int[] result of a long conditional is checked as a Number", "ArrayLongResult",
                TypeDef.Primitive.LONG, TypeDef.Primitive.LONG, 1L, TypeDef.of(int[].class), new int[] {1}, ClassCastException.class),
            new ConditionalResult("a String result of a boolean conditional is checked as a Boolean", "StringBooleanResult",
                TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.BOOLEAN, true, TypeDef.STRING, "a", ClassCastException.class),
            new ConditionalResult("an int result of a String conditional is checked as a String", "IntStringResult",
                TypeDef.STRING, TypeDef.STRING, "a", TypeDef.Primitive.INT, 1, ClassCastException.class)
        );
    }

    /**
     * A conditional of the parameters {@code p1} and {@code p2}, returned from the method {@code call} of
     * {@code test.<className>}, which is called with {@code p0} false.
     *
     * @param description    What the case shows
     * @param className      The simple name of the class
     * @param type           The type of the conditional, which the method returns
     * @param first          The type of the first result
     * @param firstArgument  Its argument
     * @param second         The type of the second result, the one returned
     * @param secondArgument Its argument
     * @param expected       What the method returns, or the class of what it throws
     */
    record ConditionalResult(String description, String className, TypeDef type, TypeDef first, Object firstArgument,
                             TypeDef second, Object secondArgument, Object expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * A comparison or a check javac rejects between an array and another type: the bytecode writers compare the
     * references, which are distinct, and check the reference.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("incomparableOperands")
    void incomparableOperandsAreComparedAsObjects(IncomparableOperands operands) throws Exception {
        var def = single(operands.className(), TypeDef.Primitive.BOOLEAN, List.of(operands.first(), operands.second()),
            (self, p) -> operands.condition().apply(p).returning());
        assertEquals(false, run(def, operands.firstArgument(), operands.secondArgument()));
    }

    /**
     * Ints the model casts to Integer and compares by reference are compared as the boxes, which are distinct out of
     * the Integer cache, not as the ints: `p0 == p1` is true for 1000 and 1000.
     */
    @Test
    void intsCastToIntegerAreComparedByReference() throws Exception {
        var def = single("BoxedIdentity", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> p.get(0).cast(TypeDef.of(Integer.class)).equalsReferentially(p.get(1).cast(TypeDef.of(Integer.class))).returning());
        try (var loader = compile(def)) {
            assertEquals(false, JavaCompileAssertions.invoke(loader, def, 1000, 1000));
            assertEquals(true, JavaCompileAssertions.invoke(loader, def, 5, 5));
        }
    }

    static Stream<IncomparableOperands> incomparableOperands() {
        return Stream.of(
            new IncomparableOperands("an Integer is not referentially equal to an int[]", "IntegerArrayIdentity",
                TypeDef.of(Integer.class), 1, TypeDef.of(int[].class), new int[] {1}, p -> p.get(0).equalsReferentially(p.get(1))),
            new IncomparableOperands("an int[] is no Integer", "ArrayInstanceOfInteger",
                TypeDef.of(int[].class), new int[] {1}, TypeDef.Primitive.INT, 0, p -> p.get(0).instanceOf(ClassTypeDef.of(Integer.class)))
        );
    }

    /**
     * A condition of the parameters {@code p0} and {@code p1}, returned from the method {@code call} of
     * {@code test.<className>}.
     *
     * @param description    What the case shows
     * @param className      The simple name of the class
     * @param first          The type of the first parameter
     * @param firstArgument  Its argument
     * @param second         The type of the second parameter
     * @param secondArgument Its argument
     * @param condition      The condition
     */
    record IncomparableOperands(String description, String className, TypeDef first, Object firstArgument, TypeDef second,
                                Object secondArgument, Function<List<VariableDef.MethodParameter>, ExpressionDef> condition) {
        @Override
        public String toString() {
            return description;
        }
    }

    @Test
    void instanceOfKeepsTheWideningCastOfItsOperand() throws Exception {
        // ((Object) string) instanceof Integer: javac rejects the check once the cast is dropped
        var result = run(single("WidenedInstanceOf", TypeDef.Primitive.BOOLEAN, List.of(TypeDef.STRING),
            (self, p) -> p.get(0).cast(TypeDef.OBJECT).instanceOf(ClassTypeDef.of(Integer.class)).returning()), "abc");
        assertEquals(false, result);
    }

    @Test
    void elementOfANewArrayIsNotAnotherDimension() throws Exception {
        // (new Object[2])[1], not new Object[2][1]
        var result = run(single("NewArrayElement", TypeDef.OBJECT, List.of(),
            (self, p) -> new ExpressionDef.NewArrayOfSize(TypeDef.OBJECT.array(), 2).arrayElement(1).returning()));
        assertNull(result);
    }

    @Test
    void numericConditionalConvertsBranchesBeforeBoxing() throws Exception {
        var def = ClassDef.builder("test.NumericConditional").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class)
                .returns(Object.class).build((s, p) -> new ExpressionDef.IfElse(p.getFirst().isTrue(),
                    ExpressionDef.constant(1), ExpressionDef.constant(2.5d), TypeDef.Primitive.LONG).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(1L, cls.getMethod("call", boolean.class).invoke(instance, true));
            assertEquals(2L, cls.getMethod("call", boolean.class).invoke(instance, false));
        }
    }

    @Test
    void boxedCharacterCanBeExplicitlyConvertedToLong() throws Exception {
        var def = ClassDef.builder("test.CharacterConversion").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Character.class)
                .returns(long.class).build((s, p) -> p.getFirst().cast(TypeDef.Primitive.LONG).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(65L, cls.getMethod("call", Character.class).invoke(cls.getConstructor().newInstance(), 'A'));
        }
    }

    // ---- fixtures

    /**
     * Overloads a call in the model names one of.
     *
     * @since 2.3
     */
    @SuppressWarnings("unused")
    public static class Overloads {
        /** The constructor that ran. */
        public final String kind;

        /** @param value The value */
        public Overloads(Object value) {
            kind = "object";
        }

        /** @param value The value */
        public Overloads(String value) {
            kind = "string";
        }

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
    }
}
