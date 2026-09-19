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

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument, return, assignment and cast conversions of the Java generator. Every model here was run through the
 * ASM bytecode writer, which writes and runs it as the assertions expect: it converts each argument, returned,
 * assigned, stored, branch and element value to the declared type ({@code ExpressionWriter.writeExpressionCheckCast}),
 * and names the invoked overload by its descriptor.
 *
 * @since 2.2.2
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

    // `String.valueOf(int)` with a char: the bytecode calls the `int` overload ("97"); the source `valueOf(p0)` binds `valueOf(char)` ("a").
    @Test
    void charPassedToIntParameterKeepsIntOverload() throws Exception {
        var valueOf = String.class.getMethod("valueOf", int.class);
        var def = single("CharIntOverload", String.class, List.of(TypeDef.Primitive.CHAR),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(valueOf, p.get(0)).returning());
        assertEquals("97", run(def, 'a'));
    }

    // `String.valueOf(float)` with an int: the bytecode converts i2f ("1.0"); the source binds `valueOf(int)` ("1").
    @Test
    void intPassedToFloatParameterKeepsFloatOverload() throws Exception {
        var valueOf = String.class.getMethod("valueOf", float.class);
        var def = single("IntFloatOverload", String.class, List.of(TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(String.class).invokeStatic(valueOf, p.get(0)).returning());
        assertEquals("1.0", run(def, 1));
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

    // The model names `pick(Object)`; with a String value and no cast javac binds `pick(String)`.
    @Test
    void stringPassedToObjectParameterKeepsObjectOverload() throws Exception {
        var pick = Overloads.class.getMethod("pick", Object.class);
        var def = single("SubtypeOverload", String.class, List.of(TypeDef.STRING),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(pick, p.get(0)).returning());
        assertEquals("object", run(def, "a"));
    }

    // A cast of `null` is dropped, so `pick((String) null)` becomes `pick(null)`, which is ambiguous between `pick(String)` and `pick(Integer)`.
    @Test
    void castNullKeepsItsOverload() throws Exception {
        var pick = Overloads.class.getMethod("pick", String.class);
        var def = single("CastNullOverload", String.class, List.of(),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(pick, ExpressionDef.nullValue().cast(TypeDef.STRING)).returning());
        assertEquals("string", run(def));
    }

    // The model names `prim(long)`; with an int value and no cast javac binds `prim(int)`.
    @Test
    void intPassedToLongParameterKeepsLongOverload() throws Exception {
        var prim = Overloads.class.getMethod("prim", long.class);
        var def = single("WidenedOverload", String.class, List.of(TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(prim, p.get(0)).returning());
        assertEquals("long", run(def, 1));
    }

    // The model names `box(Integer)`, which the bytecode boxes the int for; javac binds `box(int)`.
    @Test
    void intPassedToBoxedParameterKeepsBoxedOverload() throws Exception {
        var box = Overloads.class.getMethod("box", Integer.class);
        var def = single("BoxOverload", String.class, List.of(TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(box, p.get(0)).returning());
        assertEquals("boxed", run(def, 1));
    }

    // The model names `box(int)`, which the bytecode unboxes the Integer for; javac binds `box(Integer)`.
    @Test
    void boxedPassedToPrimitiveParameterKeepsPrimitiveOverload() throws Exception {
        var box = Overloads.class.getMethod("box", int.class);
        var def = single("UnboxOverload", String.class, List.of(TypeDef.of(Integer.class)),
            (self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(box, p.get(0)).returning());
        assertEquals("int", run(def, 1));
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

    // ---- fixtures

    /**
     * Overloads a call in the model names one of.
     *
     * @since 2.2.2
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

    // ---- helpers

    private static ClassDef single(String name, Class<?> returns, List<TypeDef> parameters,
                                   MethodDef.MethodBodyBuilder body) {
        return single(name, TypeDef.of(returns), parameters, body);
    }

    private static ClassDef single(String name, TypeDef returns, List<TypeDef> parameters,
                                   MethodDef.MethodBodyBuilder body) {
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(returns);
        for (int i = 0; i < parameters.size(); i++) {
            method.addParameter("p" + i, parameters.get(i));
        }
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC).addMethod(method.build(body)).build();
    }

    private static Object run(ObjectDef def, Object... args) throws Exception {
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = java.util.Arrays.stream(cls.getMethods()).filter(m -> m.getName().equals("call")).findFirst().orElseThrow();
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
