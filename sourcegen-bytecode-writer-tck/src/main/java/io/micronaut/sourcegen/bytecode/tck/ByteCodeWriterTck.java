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

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.LambdaDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every bytecode backend must provide, asserted through the classes a backend writes
 * rather than through the bytecode it emits, so that the same tests hold for any backend.
 *
 * <p>A backend runs the TCK by extending this class and implementing {@link #write(ObjectDef)}.
 * Assertions about a particular backend's encoding, such as the exact instructions it selects or
 * the fallbacks it offers, belong in that backend's own tests.
 *
 * @since 2.2
 */
public abstract class ByteCodeWriterTck extends AbstractByteCodeWriterTck {

    @Test
    public void callsAVariableArityMethodWithMoreArgumentsThanItsFixedArityOverloadsTake() throws Exception {
        // List.of declares fixed arity overloads up to ten elements, so an eleventh can only resolve to
        // List.of(E...), whose tail is packed into an array of the requested element type
        List<ExpressionDef> values = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            values.add(ExpressionDef.constant("value" + i));
        }
        TypeDef returnType = TypeDef.parameterized(List.class, TypeDef.STRING);
        ClassDef classDef = ClassDef.builder("example.TckVariableArity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("values")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(returnType)
                .build((ignored, parameters) -> ClassTypeDef.of(List.class)
                    .invokeStatic("of", returnType, values)
                    .returning()))
            .build();

        Class<?> generated = define(classDef);

        assertEquals(List.of("value0", "value1", "value2", "value3", "value4", "value5", "value6",
            "value7", "value8", "value9", "value10", "value11", "value12", "value13"),
            generated.getMethod("values").invoke(null));
    }

    @Test
    public void writesEveryIntegerMathOperationAndWidePrimitiveOperations() throws Exception {
        var builder = ClassDef.builder("example.TckMathParity").addModifiers(Modifier.PUBLIC);
        for (ExpressionDef.MathBinaryOperation.OpType operation : ExpressionDef.MathBinaryOperation.OpType.values()) {
            builder.addMethod(binaryMethod(operation.name(), TypeDef.Primitive.INT, operation));
        }
        builder.addMethod(binaryMethod("longAddition", TypeDef.Primitive.LONG,
            ExpressionDef.MathBinaryOperation.OpType.ADDITION));
        builder.addMethod(binaryMethod("floatDivision", TypeDef.Primitive.FLOAT,
            ExpressionDef.MathBinaryOperation.OpType.DIVISION));
        builder.addMethod(binaryMethod("doubleMultiplication", TypeDef.Primitive.DOUBLE,
            ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION));
        builder.addMethod(MethodDef.builder("negateLong")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.Primitive.LONG)
            .returns(TypeDef.Primitive.LONG)
            .build((ignored, parameters) -> parameters.get(0)
                .math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning()));

        Class<?> generated = define(builder.build());
        Map<ExpressionDef.MathBinaryOperation.OpType, Integer> expected = Map.ofEntries(
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.ADDITION, 23),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, 17),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, 60),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.DIVISION, 6),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.MODULUS, 2),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_AND, 0),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_OR, 23),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_XOR, 23),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT, 160),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT, 2),
            Map.entry(ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT, 2)
        );
        for (var entry : expected.entrySet()) {
            Object actual = generated.getMethod(entry.getKey().name(), int.class, int.class).invoke(null, 20, 3);
            assertEquals(entry.getValue(), actual, entry.getKey().name());
        }
        assertEquals(12L, generated.getMethod("longAddition", long.class, long.class).invoke(null, 5L, 7L));
        assertEquals(2.5f, generated.getMethod("floatDivision", float.class, float.class).invoke(null, 5f, 2f));
        assertEquals(10d, generated.getMethod("doubleMultiplication", double.class, double.class).invoke(null, 5d, 2d));
        assertEquals(-9L, generated.getMethod("negateLong", long.class).invoke(null, 9L));
    }

    @Test
    public void writesAllComparisonsForIntegralAndFloatingPointValues() throws Exception {
        for (ExpressionDef.ComparisonOperation.OpType operation : ExpressionDef.ComparisonOperation.OpType.values()) {
            ClassDef intDefinition = ClassDef.builder("example.TckIntComparison" + operation.name())
                .addModifiers(Modifier.PUBLIC)
                .addMethod(comparisonMethod("compareInt", TypeDef.Primitive.INT, operation))
                .build();
            ClassDef doubleDefinition = ClassDef.builder("example.TckDoubleComparison" + operation.name())
                .addModifiers(Modifier.PUBLIC)
                .addMethod(comparisonMethod("compareDouble", TypeDef.Primitive.DOUBLE, operation))
                .build();
            Class<?> intGenerated = define(intDefinition);
            Class<?> doubleGenerated = define(doubleDefinition);
            boolean expected = switch (operation) {
                case EQUAL_TO -> false;
                case NOT_EQUAL_TO, GREATER_THAN, GREATER_THAN_OR_EQUAL -> true;
                case LESS_THAN, LESS_THAN_OR_EQUAL -> false;
            };
            assertEquals(expected,
                intGenerated.getMethod("compareInt", int.class, int.class).invoke(null, 7, 3));
            assertEquals(expected,
                doubleGenerated.getMethod("compareDouble", double.class, double.class).invoke(null, 7d, 3d));
            if (operation == ExpressionDef.ComparisonOperation.OpType.LESS_THAN) {
                assertFalse((boolean) doubleGenerated.getMethod("compareDouble", double.class, double.class)
                    .invoke(null, Double.NaN, 3d));
            } else if (operation == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO) {
                assertTrue((boolean) doubleGenerated.getMethod("compareDouble", double.class, double.class)
                    .invoke(null, Double.NaN, 3d));
            }
        }
    }

    @Test
    public void writesCastsBoxingNullChecksEqualityInstanceOfAndArrays() throws Exception {
        TypeDef.Array strings = TypeDef.STRING.array();
        ClassDef definition = ClassDef.builder("example.TckExpressionParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("box")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(Integer.class)
                .build((ignored, parameters) -> parameters.get(0).returning()))
            .addMethod(MethodDef.builder("unbox")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", Integer.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0).returning()))
            .addMethod(MethodDef.builder("isNull")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT.makeNullable())
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0).isNull().returning()))
            .addMethod(MethodDef.builder("isString")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0).instanceOf(TypeDef.STRING).returning()))
            .addMethod(MethodDef.builder("same")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.OBJECT)
                .addParameter("right", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0).equalsReferentially(parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("equal")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.OBJECT.makeNullable())
                .addParameter("right", TypeDef.OBJECT.makeNullable())
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0).equalsStructurally(parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("element")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("values", strings)
                .addParameter("index", TypeDef.Primitive.INT)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).arrayElement(parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("array")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(strings)
                .build((ignored, parameters) -> strings.instantiate(
                    ExpressionDef.constant("a"), ExpressionDef.constant("b")).returning()))
            .build();

        Class<?> generated = define(definition);
        assertEquals(4, generated.getMethod("box", int.class).invoke(null, 4));
        assertEquals(5, generated.getMethod("unbox", Integer.class).invoke(null, 5));
        assertTrue((boolean) generated.getMethod("isNull", Object.class).invoke(null, new Object[]{null}));
        assertTrue((boolean) generated.getMethod("isString", Object.class).invoke(null, "value"));
        Object value = new Object();
        assertTrue((boolean) generated.getMethod("same", Object.class, Object.class).invoke(null, value, value));
        assertTrue((boolean) generated.getMethod("equal", Object.class, Object.class)
            .invoke(null, new String("value"), new String("value")));
        assertEquals("b", generated.getMethod("element", String[].class, int.class)
            .invoke(null, new String[]{"a", "b"}, 1));
        assertArrayEquals(new String[]{"a", "b"},
            (String[]) generated.getMethod("array").invoke(null));
    }

    @Test
    public void writesReflectedGenericBridgeAndGenericSignatures() throws Exception {
        FieldDef names = FieldDef.builder("names", TypeDef.parameterized(List.class, String.class))
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef definition = ClassDef.builder("example.TckGenericParity")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, Integer.class))
            .addField(names)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.STRING)
                .returns(Integer.class)
                .build((ignored, parameters) -> parameters.get(0)
                    .invoke("length", TypeDef.Primitive.INT).cast(TypeDef.of(Integer.class)).returning()))
            .build();

        Class<?> generated = define(definition);
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) generated.getConstructor().newInstance();
        assertEquals(5, function.apply("hello"));
        Method bridge = Arrays.stream(generated.getDeclaredMethods())
            .filter(Method::isBridge)
            .findFirst()
            .orElseThrow();
        assertTrue(bridge.isSynthetic());
        assertEquals(Object.class, bridge.getParameterTypes()[0]);
        assertEquals(Object.class, bridge.getReturnType());
        assertEquals("java.util.List<java.lang.String>", generated.getField("names").getGenericType().getTypeName());
        assertEquals("java.util.function.Function<java.lang.String, java.lang.Integer>",
            generated.getGenericInterfaces()[0].getTypeName());
    }

    @Test
    public void verifiesHierarchyFromGeneratedModelDefinitions() throws Exception {
        ClassDef parent = ClassDef.builder("example.TckModelParent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("parent").returning()))
            .build();
        ClassDef child = ClassDef.builder("example.TckModelChild")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .build();

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
            parent.getName(), write(parent),
            child.getName(), write(child)
        ));
        Class<?> childClass = loader.loadClass(child.getName());
        assertEquals(parent.getName(), childClass.getSuperclass().getName());
        assertEquals("parent", childClass.getMethod("value").invoke(childClass.getConstructor().newInstance()));
    }

    @Test
    public void writesEmptyWideGenericAndBridgedRecords() throws Exception {
        RecordDef empty = RecordDef.builder("example.TckEmptyRecord")
            .addModifiers(Modifier.PUBLIC)
            .build();
        Class<?> emptyClass = define(empty);
        Object emptyValue = emptyClass.getConstructor().newInstance();
        assertEquals("TckEmptyRecord[]", emptyValue.toString());
        assertEquals(0, emptyClass.getRecordComponents().length);

        RecordDef wide = RecordDef.builder("example.TckWideRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("id").ofType(TypeDef.Primitive.LONG).build())
            .addProperty(PropertyDef.builder("weight").ofType(TypeDef.Primitive.DOUBLE).build())
            .addProperty(PropertyDef.builder("tags").ofType(TypeDef.STRING.array()).build())
            .build();
        Class<?> wideClass = define(wide);
        String[] tags = {"one", "two"};
        Object wideValue = wideClass.getConstructor(long.class, double.class, String[].class)
            .newInstance(3L, 1.5d, tags);
        assertEquals(3L, wideClass.getMethod("id").invoke(wideValue));
        assertEquals(1.5d, wideClass.getMethod("weight").invoke(wideValue));
        assertSame(tags, wideClass.getMethod("tags").invoke(wideValue));

        RecordDef bridged = RecordDef.builder("example.TckBridgedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addProperty(PropertyDef.builder("get").ofType(TypeDef.STRING).build())
            .build();
        Class<?> bridgedClass = define(bridged);
        @SuppressWarnings("unchecked")
        Supplier<Object> supplier = (Supplier<Object>) bridgedClass.getConstructor(String.class).newInstance("value");
        assertEquals("value", supplier.get());
        assertTrue(Arrays.stream(bridgedClass.getDeclaredMethods()).anyMatch(Method::isBridge));
    }

    @Test
    public void writesExplicitSuperConstructorsAndObjectCreation() throws Exception {
        MethodDef constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("message", TypeDef.STRING)
            .build((aThis, parameters) -> aThis.superRef().invokeSuperConstructor(parameters.get(0)));
        ClassTypeDef generatedType = ClassTypeDef.of("example.TckExceptionParity");
        ClassDef definition = ClassDef.builder(generatedType.getName())
            .addModifiers(Modifier.PUBLIC)
            .superclass(RuntimeException.class)
            .addMethod(constructor)
            .addMethod(MethodDef.builder("create")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("message", TypeDef.STRING)
                .returns(generatedType)
                .build((ignored, parameters) -> generatedType.instantiate(
                    List.of(TypeDef.STRING), parameters.get(0)).returning()))
            .build();

        Class<?> generated = define(definition);
        RuntimeException exception = (RuntimeException) generated.getMethod("create", String.class)
            .invoke(null, "message");
        assertEquals("message", exception.getMessage());
    }

    @Test
    public void writesBooleanCompositionConditionalExpressionsAndArraySizes() throws Exception {
        TypeDef.Array strings = TypeDef.STRING.array();
        ClassDef definition = ClassDef.builder("example.TckBooleanParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("condition", TypeDef.Primitive.BOOLEAN)
                .addParameter("left", TypeDef.STRING)
                .addParameter("right", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).isTrue()
                    .doIfElse(parameters.get(1), parameters.get(2)).returning()))
            .addMethod(MethodDef.builder("nullPattern")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.OBJECT.makeNullable())
                .addParameter("right", TypeDef.OBJECT.makeNullable())
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0).isNonNull()
                    .and(parameters.get(1).isNull()).returning()))
            .addMethod(MethodDef.builder("empty")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(strings)
                .build((ignored, parameters) -> strings.instantiate(3).returning()))
            .build();

        Class<?> generated = define(definition);
        assertEquals("left", generated.getMethod("choose", boolean.class, String.class, String.class)
            .invoke(null, true, "left", "right"));
        assertEquals("right", generated.getMethod("choose", boolean.class, String.class, String.class)
            .invoke(null, false, "left", "right"));
        assertTrue((boolean) generated.getMethod("nullPattern", Object.class, Object.class)
            .invoke(null, "left", null));
        assertFalse((boolean) generated.getMethod("nullPattern", Object.class, Object.class)
            .invoke(null, null, null));
        assertEquals(3, ((String[]) generated.getMethod("empty").invoke(null)).length);
    }

    @Test
    public void writesCatchVariablesThrowsAndDeclarationMetadata() throws Exception {
        ParameterDef parameter = ParameterDef.builder("value", TypeDef.STRING)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(ParameterMarker.class)).build())
            .build();
        ClassDef definition = ClassDef.builder("example.TckMetadataParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("declared")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(Deprecated.class)
                .addParameter(parameter)
                .addThrows(TypeDef.of(IOException.class))
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).returning()))
            .addMethod(MethodDef.builder("caughtMessage")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> StatementDef.doTry(
                    ClassTypeDef.of(IllegalArgumentException.class)
                        .instantiate(ExpressionDef.constant("caught")).doThrow()
                ).doCatch(IllegalArgumentException.class, exception -> exception
                    .invoke("getMessage", TypeDef.STRING).returning())))
            .build();

        Class<?> generated = define(definition);
        Method declared = generated.getMethod("declared", String.class);
        assertTrue(declared.isAnnotationPresent(Deprecated.class));
        assertEquals(List.of(IOException.class), List.of(declared.getExceptionTypes()));
        assertTrue(declared.getParameters()[0].isAnnotationPresent(ParameterMarker.class));
        assertEquals("value", declared.getParameters()[0].getName());
        assertEquals("caught", generated.getMethod("caughtMessage").invoke(null));
    }

    @Test
    @SuppressWarnings("removal")
    public void writesGenericRecordSignaturesAndRecordsWithAnAdditionalConstructor() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(Number.class));
        RecordDef generic = RecordDef.builder("example.TckGenericRecordParity")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addProperty(PropertyDef.builder("value").ofType(variable).build())
            .build();
        Class<?> genericClass = define(generic);
        assertEquals(Number.class, genericClass.getRecordComponents()[0].getType());
        assertEquals("T", genericClass.getRecordComponents()[0].getGenericType().getTypeName());
        assertEquals("T", genericClass.getTypeParameters()[0].getName());

        MethodDef canonicalConstructor = MethodDef.constructor(List.of(
            ParameterDef.of("name", TypeDef.STRING),
            ParameterDef.of("age", TypeDef.Primitive.INT)
        ), Modifier.PUBLIC);
        RecordDef additionalConstructor = RecordDef.builder("example.TckConstructorRecordParity")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING).build())
            .addProperty(PropertyDef.builder("age").ofType(TypeDef.Primitive.INT).build())
            .addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("name", TypeDef.STRING)
                .build((aThis, parameters) -> aThis.invokeConstructor(
                    canonicalConstructor, parameters.get(0), ExpressionDef.constant(0))))
            .build();
        Class<?> recordClass = define(additionalConstructor);
        Object value = recordClass.getConstructor(String.class).newInstance("Ada");
        assertEquals("TckConstructorRecordParity[name=Ada, age=0]", value.toString());
        assertTrue(recordClass.isRecord());
        assertEquals(2, recordClass.getDeclaredConstructors().length);
    }

    @Test
    public void writesSuperCallsAndSuperConstructorDelegation() throws Exception {
        FieldDef marker = FieldDef.builder("marker", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC)
            .initializer(ExpressionDef.constant("initialized"))
            .build();
        ClassDef definition = ClassDef.builder("example.TckSuperParity")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(java.util.concurrent.atomic.AtomicInteger.class))
            .addField(marker)
            .addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("initial", TypeDef.Primitive.INT)
                // The deprecated form of a super constructor call: a super receiver invocation
                .build((aThis, parameters) -> aThis.superRef().invokeConstructor(
                    MethodDef.constructor(List.of(ParameterDef.of("initialValue", TypeDef.Primitive.INT)), Modifier.PUBLIC),
                    parameters.get(0))))
            .addMethod(MethodDef.builder("toString")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                // super.toString() must dispatch with invokespecial, not virtually, or it recurses
                .build((aThis, parameters) -> aThis.superRef()
                    .invoke("toString", TypeDef.STRING, List.of()).returning()))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor(int.class).newInstance(7);

        assertEquals(7, ((java.util.concurrent.atomic.AtomicInteger) instance).get());
        assertEquals("7", instance.toString());
        // A super call written in the deprecated instance-invocation form is not a this(...)
        // delegation, so the field initializers still run
        assertEquals("initialized", generated.getField("marker").get(instance));
    }

    @Test
    public void writesArrayConstantsAndMultiDimensionalArrays() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckArrayParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("names")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.STRING.array())
                .build((ignored, parameters) ->
                    ExpressionDef.constant(new String[] {"a", "b"}).returning()))
            .addMethod(MethodDef.builder("numbers")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.Primitive.INT.array())
                .build((ignored, parameters) ->
                    ExpressionDef.constant(new int[] {3, 4, 5}).returning()))
            .addMethod(MethodDef.builder("grid")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(new TypeDef.Array(TypeDef.Primitive.INT, 2, false))
                // A two-dimensional array allocates arrays of arrays, not of the base type
                .build((ignored, parameters) -> new TypeDef.Array(TypeDef.Primitive.INT, 2, false)
                    .instantiate(ExpressionDef.constant(new int[] {1, 2})).returning()))
            .addMethod(MethodDef.builder("classConstant")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.of(Class.class))
                .build((ignored, parameters) ->
                    ExpressionDef.constant(ClassTypeDef.of(String[].class)).returning()))
            .build();

        Class<?> generated = define(definition);

        assertArrayEquals(new String[] {"a", "b"}, (String[]) generated.getMethod("names").invoke(null));
        assertArrayEquals(new int[] {3, 4, 5}, (int[]) generated.getMethod("numbers").invoke(null));
        int[][] grid = (int[][]) generated.getMethod("grid").invoke(null);
        assertArrayEquals(new int[] {1, 2}, grid[0]);
        assertEquals(String[].class, generated.getMethod("classConstant").invoke(null));
    }

    @Test
    public void writesVoidMethodsThatReturnAnExpression() throws Exception {
        MethodDef sideEffect = MethodDef.builder("record")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.VOID)
            .build();
        ClassTypeDef self = ClassTypeDef.of("example.TckVoidReturnParity");
        FieldDef calls = FieldDef.builder("calls", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build();
        ClassDef definition = ClassDef.builder(self.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(calls)
            .addMethod(MethodDef.builder("record")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.VOID)
                .build((ignored, parameters) -> self.getStaticField(calls)
                    .put(self.getStaticField(calls)
                        .math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1)))))
            // A void method whose body returns the result of a void call
            .addMethod(MethodDef.builder("delegate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.VOID)
                .build((ignored, parameters) -> self.invokeStatic(sideEffect).returning()))
            // A void method that returns the result of a value-producing call, discarding it
            .addMethod(MethodDef.builder("discard")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.VOID)
                .build((ignored, parameters) -> ExpressionDef.constant("ignored").returning()))
            .build();

        Class<?> generated = define(definition);
        generated.getMethod("delegate").invoke(null);
        generated.getMethod("discard").invoke(null);

        assertEquals(1, generated.getField("calls").get(null));
    }

    @Test
    public void writesConstructorsWhoseSuperCallUsesAnEarlierLocal() throws Exception {
        MethodDef superConstructor = MethodDef.constructor(
            List.of(ParameterDef.of("initialValue", TypeDef.Primitive.INT)), Modifier.PUBLIC);
        ClassDef definition = ClassDef.builder("example.TckConstructorLocalParity")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(java.util.concurrent.atomic.AtomicInteger.class))
            .addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("seed", TypeDef.Primitive.INT)
                // The local is defined before the super call and used by it, so the call must not
                // be hoisted above its definition
                .build((aThis, parameters) -> parameters.get(0)
                    .math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, ExpressionDef.constant(3))
                    .newLocal("scaled", scaled -> aThis.superRef()
                        .invokeConstructor(superConstructor, scaled))))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor(int.class).newInstance(4);

        assertEquals(12, ((java.util.concurrent.atomic.AtomicInteger) instance).get());
    }

    @Test
    public void writesBoxedConstantsSmallPrimitivesAndPrimitiveHashCodes() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckBoxingParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("isOne")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) -> parameters.get(0)
                    .equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning()))
            .addMethod(MethodDef.builder("flag")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Boolean.class)
                .build((ignored, parameters) -> ExpressionDef.trueValue().returning()))
            .addMethod(MethodDef.builder("hash")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.LONG)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0).invokeHashCode().returning()))
            .addMethod(MethodDef.builder("flags")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.Primitive.BOOLEAN.array())
                .build((ignored, parameters) -> TypeDef.Primitive.BOOLEAN.array()
                    .instantiate(ExpressionDef.trueValue(), ExpressionDef.falseValue()).returning()))
            .addMethod(MethodDef.builder("narrow")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.BYTE)
                .build((ignored, parameters) -> parameters.get(0).cast(TypeDef.Primitive.BYTE).returning()))
            .build();

        Class<?> generated = define(definition);

        assertEquals(true, generated.getMethod("isOne", int.class).invoke(null, 1));
        assertEquals(false, generated.getMethod("isOne", int.class).invoke(null, 2));
        assertEquals(Boolean.TRUE, generated.getMethod("flag").invoke(null));
        assertEquals(Long.hashCode(1L << 40), generated.getMethod("hash", long.class).invoke(null, 1L << 40));
        assertArrayEquals(new boolean[] {true, false}, (boolean[]) generated.getMethod("flags").invoke(null));
        assertEquals((byte) 1, generated.getMethod("narrow", int.class).invoke(null, 257));
    }

    @Test
    public void writesInterfacesWithAbstractDefaultAndStaticMethods() throws Exception {
        MethodDef name = MethodDef.builder("name")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(TypeDef.STRING)
            .build();
        InterfaceDef contract = InterfaceDef.builder("example.TckInterfaceParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(name)
            .addMethod(MethodDef.builder("greet")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) -> aThis.invoke(name).returning()))
            .addMethod(MethodDef.builder("twice")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0)
                    .math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, ExpressionDef.constant(2))
                    .returning()))
            .build();
        ClassDef implementation = ClassDef.builder("example.TckInterfaceParityImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(contract.asTypeDef())
            .addMethod(MethodDef.builder("name")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("Ada").returning()))
            .build();

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
            contract.getName(), write(contract),
            implementation.getName(), write(implementation)
        ));
        Class<?> contractClass = loader.loadClass(contract.getName());
        Class<?> implementationClass = loader.loadClass(implementation.getName());

        assertTrue(contractClass.isInterface());
        assertTrue(java.lang.reflect.Modifier.isAbstract(contractClass.getMethod("name").getModifiers()));
        assertTrue(contractClass.getMethod("greet").isDefault());
        assertTrue(java.lang.reflect.Modifier.isStatic(contractClass.getMethod("twice", int.class).getModifiers()));
        assertEquals(42, contractClass.getMethod("twice", int.class).invoke(null, 21));
        Object instance = implementationClass.getConstructor().newInstance();
        assertInstanceOf(contractClass, instance);
        assertEquals("Ada", contractClass.getMethod("greet").invoke(instance));
    }

    @Test
    public void writesEnums() throws Exception {
        EnumDef definition = EnumDef.builder("example.TckEnumParity")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("ALPHA")
            .addEnumConstant("BETA")
            .addMethod(MethodDef.builder("tag")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("tagged").returning()))
            .build();

        Class<?> generated = define(definition);

        assertTrue(generated.isEnum());
        assertEquals(Enum.class, generated.getSuperclass());
        assertTrue(java.lang.reflect.Modifier.isFinal(generated.getModifiers()));
        Object[] constants = generated.getEnumConstants();
        assertEquals(2, constants.length);
        assertEquals("ALPHA", ((Enum<?>) constants[0]).name());
        assertEquals(1, ((Enum<?>) constants[1]).ordinal());
        Object beta = generated.getMethod("valueOf", String.class).invoke(null, "BETA");
        assertSame(constants[1], beta);
        assertEquals("tagged", generated.getMethod("tag").invoke(beta));
        assertArrayEquals(constants, (Object[]) generated.getMethod("values").invoke(null));
    }

    @Test
    @SuppressWarnings("removal")
    public void writesConstructorDelegationWithoutRepeatingFieldInitializers() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("example.TckConstructorDelegationParity");
        FieldDef counter = FieldDef.builder("initializations", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build();
        MethodDef next = MethodDef.builder("next")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.Primitive.INT)
            .build((ignored, parameters) -> StatementDef.multi(
                self.getStaticField(counter).put(self.getStaticField(counter)
                    .math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
                self.getStaticField(counter).returning()
            ));
        FieldDef marker = FieldDef.builder("marker", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC)
            .initializer(self.invokeStatic(next))
            .build();
        FieldDef name = FieldDef.builder("name", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build();
        FieldDef age = FieldDef.builder("age", TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build();
        MethodDef full = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("name", TypeDef.STRING)
            .addParameter("age", TypeDef.Primitive.INT)
            .build((aThis, parameters) -> StatementDef.multi(
                aThis.field(name).assign(parameters.get(0)),
                aThis.field(age).assign(parameters.get(1))
            ));
        ClassDef definition = ClassDef.builder(self.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(counter)
            .addField(marker)
            .addField(name)
            .addField(age)
            .addMethod(next)
            .addMethod(full)
            .addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("name", TypeDef.STRING)
                .build((aThis, parameters) -> aThis.invokeConstructor(full, parameters.get(0), ExpressionDef.constant(0))))
            .build();

        Class<?> generated = define(definition);
        Object value = generated.getConstructor(String.class).newInstance("Ada");

        assertEquals("Ada", generated.getField("name").get(value));
        assertEquals(0, generated.getField("age").get(value));
        assertEquals(1, generated.getField("marker").get(value));
        assertEquals(1, generated.getField("initializations").get(null));
    }

    @Test
    public void writesSuperConstructorCallsThatNameTheSuperType() throws Exception {
        var objectConstructor = Object.class.getConstructor();
        ClassDef definition = ClassDef.builder("example.TckNamedSuperConstructor")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(Object.class))
            .addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .build((aThis, parameters) -> aThis.superRef(ClassTypeDef.of(Object.class))
                    .invokeConstructor(objectConstructor)))
            .build();

        Class<?> generated = define(definition);

        assertNotNull(generated.getConstructor().newInstance());
    }

    @Test
    public void writesSuperCallsToASuperclassMethodAndAnInterfaceDefaultMethod() throws Exception {
        ClassTypeDef iteratorType = ClassTypeDef.of(Iterator.class);
        Method removeMethod = Iterator.class.getMethod("remove");
        Method toStringMethod = Object.class.getMethod("toString");
        ClassDef definition = ClassDef.builder("example.TckNamedSuperMethods")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(iteratorType)
            .addMethod(MethodDef.builder("hasNext").addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .build((aThis, parameters) -> ExpressionDef.constant(false).returning()))
            .addMethod(MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((aThis, parameters) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("remove").addModifiers(Modifier.PUBLIC)
                .build((aThis, parameters) -> aThis.superRef(iteratorType)
                    .invoke(removeMethod)))
            .addMethod(MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).returns(String.class)
                // Dispatched virtually, super.toString() would recurse
                .build((aThis, parameters) -> aThis.superRef(ClassTypeDef.of(Object.class))
                    .invoke(toStringMethod).returning()))
            .build();

        Class<?> generated = define(definition);
        Iterator<?> instance = (Iterator<?>) generated.getConstructor().newInstance();

        // The default Iterator.remove() throws
        assertThrows(UnsupportedOperationException.class, instance::remove);
        assertTrue(instance.toString().startsWith(generated.getName() + "@"), instance.toString());
    }

    @Test
    public void writesNamesContainingDollarSigns() throws Exception {
        FieldDef field = FieldDef.builder("$field", String.class).addModifiers(Modifier.PRIVATE).build();
        MethodDef getter = MethodDef.builder("$get").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((aThis, parameters) -> aThis.field(field).returning());
        ClassDef definition = ClassDef.builder("example.$TckHolder$Definition")
            .addModifiers(Modifier.PUBLIC)
            .addField(field)
            .addMethod(getter)
            .addMethod(MethodDef.builder("$copy").addModifiers(Modifier.PUBLIC)
                .addParameter("$value", String.class)
                .returns(String.class)
                .build((aThis, parameters) -> StatementDef.multi(
                    aThis.field(field).put(parameters.get(0)),
                    aThis.invoke(getter).newLocal("$local", local -> local.returning())
                )))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor().newInstance();

        assertEquals("copied", generated.getMethod("$copy", String.class).invoke(instance, "copied"));
        assertEquals("copied", generated.getMethod("$get").invoke(instance));
    }

    @Test
    public void writesInstanceOfANestedType() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckInstanceOfNested")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("isEntry").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", Object.class)
                .returns(boolean.class)
                .build((ignored, parameters) ->
                    new ExpressionDef.InstanceOf(parameters.get(0), ClassTypeDef.of(Map.Entry.class)).returning()))
            .build();

        Method isEntry = define(definition).getMethod("isEntry", Object.class);

        assertEquals(true, isEntry.invoke(null, Map.entry("key", "value")));
        assertEquals(false, isEntry.invoke(null, "value"));
    }

    @Test
    public void writesAccessToAFieldOfAnotherType() throws Exception {
        ClassTypeDef otherType = ClassTypeDef.of("example.TckFieldOwner");
        ClassDef other = ClassDef.builder(otherType.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("name", String.class).addModifiers(Modifier.PUBLIC).build())
            .build();
        ClassDef accessor = ClassDef.builder("example.TckFieldAccessor")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", Object.class)
                .returns(String.class)
                .build((ignored, parameters) -> new VariableDef.Field(
                    parameters.get(0).cast(otherType), otherType, "name", TypeDef.STRING).returning()))
            .build();

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
            other.getName(), write(other),
            accessor.getName(), write(accessor)
        ));
        Class<?> otherClass = loader.loadClass(other.getName());
        Object owner = otherClass.getConstructor().newInstance();
        otherClass.getField("name").set(owner, "owned");

        assertEquals("owned", loader.loadClass(accessor.getName()).getMethod("read", Object.class).invoke(null, owner));
    }

    @Test
    public void writesArrayAnnotationMembersAndNestedArrayInitializers() throws Exception {
        ClassDef definition = ClassDef.builder("example.TckArrayMembers")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(TypeMarker.class)
                .addMember("value", new String[] {"unchecked", "rawtypes"})
                .build())
            .addMethod(MethodDef.builder("matrix").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.STRING.array(2))
                .build((ignored, parameters) -> TypeDef.STRING.array(2).instantiate(List.of(
                    TypeDef.STRING.array().instantiate(List.of(ExpressionDef.constant("a")))
                )).returning()))
            .build();

        Class<?> generated = define(definition);

        assertArrayEquals(new String[] {"unchecked", "rawtypes"}, generated.getAnnotation(TypeMarker.class).value());
        assertArrayEquals(new String[][] {{"a"}}, (String[][]) generated.getMethod("matrix").invoke(null));
    }

    private static MethodDef binaryMethod(String name,
                                          TypeDef type,
                                          ExpressionDef.MathBinaryOperation.OpType operation) {
        return MethodDef.builder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("left", type)
            .addParameter("right", type)
            .returns(type)
            .build((ignored, parameters) -> parameters.get(0).math(operation, parameters.get(1)).returning());
    }

    private static MethodDef comparisonMethod(String name,
                                              TypeDef type,
                                              ExpressionDef.ComparisonOperation.OpType operation) {
        return MethodDef.builder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("left", type)
            .addParameter("right", type)
            .returns(TypeDef.Primitive.BOOLEAN)
            .build((ignored, parameters) -> parameters.get(0).compare(operation, parameters.get(1)).returning());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    private @interface ParameterMarker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    private @interface TypeMarker {
        String[] value();
    }

    @Test
    public void writesLambdasDeclaredInsideNestedBlocks() throws Exception {
        LambdaDef supplier = ClassTypeDef.of(Supplier.class).getLambda(Map.of("T", TypeDef.STRING));
        VariableDef.Local top = new VariableDef.Local("top", ClassTypeDef.of(Supplier.class));
        ClassDef definition = ClassDef.builder("example.TckNestedBlockLambdas")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("afterTopLevel")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> StatementDef.multi(
                    top.defineAndAssign(prefixed(supplier, "top:", parameters.get(1))),
                    parameters.get(0).isTrue().doIf(
                        prefixed(supplier, "if:", parameters.get(1)).invoke().returning()),
                    top.invoke("get", TypeDef.OBJECT).returning()
                )))
            .addMethod(MethodDef.builder("branch")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> parameters.get(0).isTrue().doIfElse(
                    prefixed(supplier, "if:", parameters.get(1)).invoke().returning(),
                    prefixed(supplier, "else:", parameters.get(1)).invoke().returning()
                )))
            .addMethod(MethodDef.builder("caught")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> StatementDef.doTry(
                    ClassTypeDef.of(IllegalStateException.class).instantiate(parameters.get(0)).doThrow()
                ).doCatch(IllegalStateException.class, exception -> prefixed(supplier, "catch:", parameters.get(0))
                    .invoke().returning())))
            .build();

        Class<?> generated = define(definition);

        // A lambda in a nested block must not be linked to the one declared before it in the method body
        Method afterTopLevel = generated.getMethod("afterTopLevel", boolean.class, String.class);
        assertEquals("if:value", afterTopLevel.invoke(null, true, "value"));
        assertEquals("top:value", afterTopLevel.invoke(null, false, "value"));
        Method branch = generated.getMethod("branch", boolean.class, String.class);
        assertEquals("if:value", branch.invoke(null, true, "value"));
        assertEquals("else:value", branch.invoke(null, false, "value"));
        assertEquals("catch:value", generated.getMethod("caught", String.class).invoke(null, "value"));
    }

    @Test
    public void writesThisInsideANestedBlockOfALambdaBody() throws Exception {
        LambdaDef supplier = ClassTypeDef.of(Supplier.class).getLambda(Map.of("T", TypeDef.STRING));
        FieldDef name = FieldDef.builder("name", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build();
        ClassDef definition = ClassDef.builder("example.TckLambdaNestedThis")
            .addModifiers(Modifier.PUBLIC)
            .addField(name)
            .addMethod(MethodDef.builder("describe")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("named", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.OBJECT)
                .build((aThis, parameters) -> supplier.implement((ignored, lambdaParameters) -> StatementDef.multi(
                    parameters.get(0).isTrue().doIf(aThis.field(name).returning()),
                    ExpressionDef.constant("anonymous").returning()
                )).invoke().returning()))
            .build();

        Class<?> generated = define(definition);
        Object value = generated.getConstructor().newInstance();
        generated.getField("name").set(value, "Ada");

        Method describe = generated.getMethod("describe", boolean.class);
        assertEquals("Ada", describe.invoke(value, true));
        assertEquals("anonymous", describe.invoke(value, false));
    }

    private static ExpressionDef.Lambda prefixed(LambdaDef supplier, String prefix, ExpressionDef value) {
        return supplier.implement((ignored, parameters) -> ExpressionDef.constant(prefix)
            .invoke("concat", TypeDef.STRING, value).returning());
    }
}
