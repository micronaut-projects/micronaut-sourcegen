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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
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
import java.lang.classfile.ClassFile;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime contracts for feature families that have extensive ASM coverage. Tests using
 * {@link #writeDirect(ObjectDef)} fail if the implementation silently switches to javac.
 */
class JdkByteCodeWriterParityTest {

    @Test
    void directlyWritesEveryIntegerMathOperationAndWidePrimitiveOperations() throws Exception {
        var builder = ClassDef.builder("example.JdkMathParity").addModifiers(Modifier.PUBLIC);
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
    void directlyWritesAllComparisonsForIntegralAndFloatingPointValues() throws Exception {
        for (ExpressionDef.ComparisonOperation.OpType operation : ExpressionDef.ComparisonOperation.OpType.values()) {
            ClassDef intDefinition = ClassDef.builder("example.JdkIntComparison" + operation.name())
                .addModifiers(Modifier.PUBLIC)
                .addMethod(comparisonMethod("compareInt", TypeDef.Primitive.INT, operation))
                .build();
            ClassDef doubleDefinition = ClassDef.builder("example.JdkDoubleComparison" + operation.name())
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
    void directlyWritesCastsBoxingNullChecksEqualityInstanceOfAndArrays() throws Exception {
        TypeDef.Array strings = TypeDef.STRING.array();
        ClassDef definition = ClassDef.builder("example.JdkExpressionParity")
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
    void directlyWritesWhileLoopsAndFinallyOnReturnAndThrow() throws Exception {
        VariableDef.Local current = new VariableDef.Local("current", TypeDef.Primitive.INT);
        ClassDef definition = ClassDef.builder("example.JdkControlParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("count")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("limit", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.multi(
                    current.defineAndAssign(ExpressionDef.constant(0)),
                    current.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, parameters.get(0)).whileLoop(
                        current.assign(current.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                            ExpressionDef.constant(1)))
                    ),
                    current.returning()
                )))
            .addMethod(MethodDef.builder("finish")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("counter", AtomicInteger.class)
                .addParameter("fail", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(StatementDef.multi(
                    parameters.get(1).isTrue().doIf(
                        ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()),
                    ExpressionDef.constant(7).returning()
                )).doFinally(parameters.get(0).invoke("incrementAndGet", TypeDef.Primitive.INT))))
            .build();

        Class<?> generated = define(definition);
        assertEquals(6, generated.getMethod("count", int.class).invoke(null, 6));
        AtomicInteger counter = new AtomicInteger();
        Method finish = generated.getMethod("finish", AtomicInteger.class, boolean.class);
        assertEquals(7, finish.invoke(null, counter, false));
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
            () -> finish.invoke(null, counter, true));
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(2, counter.get());
    }

    @Test
    void directlyWritesReflectedGenericBridgeAndGenericSignatures() throws Exception {
        FieldDef names = FieldDef.builder("names", TypeDef.parameterized(List.class, String.class))
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef definition = ClassDef.builder("example.JdkGenericParity")
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
    void directlyInvokesMethodsThroughInterfaceBoundTypeVariables() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        ClassDef definition = ClassDef.builder("example.JdkTypeVariableInvocationParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("length")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(variable)
                .addParameter("value", variable)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0)
                    .invoke("length", TypeDef.Primitive.INT).returning()))
            .build();

        Class<?> generated = define(definition);
        assertEquals(5, generated.getMethod("length", CharSequence.class).invoke(null, "hello"));
    }

    @Test
    void directlyVerifiesHierarchyFromGeneratedModelDefinitions() throws Exception {
        ClassDef parent = ClassDef.builder("example.JdkModelParent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("parent").returning()))
            .build();
        ClassDef child = ClassDef.builder("example.JdkModelChild")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .build();

        MapClassLoader loader = new MapClassLoader(Map.of(
            parent.getName(), writeDirect(parent),
            child.getName(), writeDirect(child)
        ));
        Class<?> childClass = loader.loadClass(child.getName());
        assertEquals(parent.getName(), childClass.getSuperclass().getName());
        assertEquals("parent", childClass.getMethod("value").invoke(childClass.getConstructor().newInstance()));
    }

    @Test
    void directlyWritesEmptyWideGenericAndBridgedRecords() throws Exception {
        RecordDef empty = RecordDef.builder("example.JdkEmptyRecord")
            .addModifiers(Modifier.PUBLIC)
            .build();
        Class<?> emptyClass = define(empty);
        Object emptyValue = emptyClass.getConstructor().newInstance();
        assertEquals("JdkEmptyRecord[]", emptyValue.toString());
        assertEquals(0, emptyClass.getRecordComponents().length);

        RecordDef wide = RecordDef.builder("example.JdkWideRecord")
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

        RecordDef bridged = RecordDef.builder("example.JdkBridgedRecord")
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
    void publicWriterCoversFallbackEnumsInterfacesAndMemberTypes() throws Exception {
        ByteCodeWriter writer = new ByteCodeWriter();
        EnumDef enumDef = EnumDef.builder("example.JdkFallbackEnum")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("ONE")
            .addEnumConstant("TWO")
            .build();
        byte[] enumBytes = writer.write(enumDef);
        assertVerified(enumBytes);
        Class<?> enumClass = new MapClassLoader(Map.of(enumDef.getName(), enumBytes)).loadClass(enumDef.getName());
        assertEquals(List.of("ONE", "TWO"), Arrays.stream(enumClass.getEnumConstants()).map(Object::toString).toList());

        InterfaceDef interfaceDef = InterfaceDef.builder("example.JdkFallbackInterface")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.STRING)
                .build())
            .build();
        byte[] interfaceBytes = writer.write(interfaceDef);
        assertVerified(interfaceBytes);
        Class<?> interfaceClass = new MapClassLoader(Map.of(interfaceDef.getName(), interfaceBytes))
            .loadClass(interfaceDef.getName());
        assertTrue(interfaceClass.isInterface());

        ClassDef outer = ClassDef.builder("example.JdkFallbackOuter")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .build())
            .build();
        Map<String, byte[]> classes = writer.writeAll(outer);
        assertTrue(classes.containsKey(outer.getName()));
        assertTrue(classes.containsKey(outer.getName() + "$Inner"));
        classes.values().forEach(JdkByteCodeWriterParityTest::assertVerified);
        MapClassLoader loader = new MapClassLoader(classes);
        Class<?> outerClass = loader.loadClass(outer.getName());
        Class<?> innerClass = loader.loadClass(outer.getName() + "$Inner");
        assertSame(outerClass, innerClass.getDeclaringClass());
    }

    @Test
    void directlyWritesExplicitSuperConstructorsAndObjectCreation() throws Exception {
        MethodDef constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("message", TypeDef.STRING)
            .build((aThis, parameters) -> aThis.superRef().invokeSuperConstructor(parameters.get(0)));
        ClassTypeDef generatedType = ClassTypeDef.of("example.JdkExceptionParity");
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
    void directlyWritesBooleanCompositionConditionalExpressionsAndArraySizes() throws Exception {
        TypeDef.Array strings = TypeDef.STRING.array();
        ClassDef definition = ClassDef.builder("example.JdkBooleanParity")
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
    void directlyWritesCatchVariablesThrowsAndDeclarationMetadata() throws Exception {
        ParameterDef parameter = ParameterDef.builder("value", TypeDef.STRING)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(ParameterMarker.class)).build())
            .build();
        ClassDef definition = ClassDef.builder("example.JdkMetadataParity")
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
    void directlyWritesGenericRecordSignaturesAndFallsBackForAdditionalConstructors() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(Number.class));
        RecordDef generic = RecordDef.builder("example.JdkGenericRecordParity")
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
        RecordDef additionalConstructor = RecordDef.builder("example.JdkConstructorRecordParity")
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
        assertEquals("JdkConstructorRecordParity[name=Ada, age=0]", value.toString());
        assertTrue(recordClass.isRecord());
        assertEquals(2, recordClass.getDeclaredConstructors().length);
    }

    @Test
    void directlyWritesSuperCallsAndSuperConstructorDelegation() throws Exception {
        FieldDef marker = FieldDef.builder("marker", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC)
            .initializer(ExpressionDef.constant("initialized"))
            .build();
        ClassDef definition = ClassDef.builder("example.JdkSuperParity")
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
    void directlyWritesDefaultMethodCallsOnAnInterfaceReceiverWithInvokeInterface() throws Exception {
        MethodDef greet = MethodDef.builder("greet")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build((aThis, parameters) -> ExpressionDef.constant("hi").returning());
        InterfaceDef contract = InterfaceDef.builder("example.JdkDefaultCallContract")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(greet)
            .build();
        ClassDef implementation = ClassDef.builder("example.JdkDefaultCallImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(contract.asTypeDef())
            .build();
        ClassDef caller = ClassDef.builder("example.JdkDefaultCaller")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("target", contract.asTypeDef())
                .returns(TypeDef.STRING)
                // The model flags the target as a default method; the call is still virtual
                .build((ignored, parameters) -> new ExpressionDef.InvokeInstanceMethod(
                    parameters.get(0), greet, true, List.of()).returning()))
            .build();

        MapClassLoader loader = new MapClassLoader(Map.of(
            contract.getName(), writeDirect(contract),
            implementation.getName(), writeDirect(implementation),
            caller.getName(), writeDirect(caller)
        ));
        Object instance = loader.loadClass(implementation.getName()).getConstructor().newInstance();
        Class<?> callerClass = loader.loadClass(caller.getName());

        assertEquals("hi", callerClass.getMethod("call", loader.loadClass(contract.getName())).invoke(null, instance));
    }

    @Test
    void directlyWritesArrayConstantsAndMultiDimensionalArrays() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkArrayParity")
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
    void directlyWritesVoidMethodsThatReturnAnExpression() throws Exception {
        MethodDef sideEffect = MethodDef.builder("record")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.VOID)
            .build();
        ClassTypeDef self = ClassTypeDef.of("example.JdkVoidReturnParity");
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
    void directlyWritesUnboxingOfAnyNumberAndCastsThroughUnrelatedTypes() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkUnboxParity")
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
    void directlyWritesConstructorsWhoseSuperCallUsesAnEarlierLocal() throws Exception {
        MethodDef superConstructor = MethodDef.constructor(
            List.of(ParameterDef.of("initialValue", TypeDef.Primitive.INT)), Modifier.PUBLIC);
        ClassDef definition = ClassDef.builder("example.JdkConstructorLocalParity")
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
    void directlyWritesSwitchesSharingOneBodyAcrossManyKeys() throws Exception {
        // A wither-style dispatch maps many keys onto one statement; emitting that body once per
        // key is what pushed micronaut-core's generated dispatch past the 64KB method limit
        ExpressionDef.Constant[] keys = new ExpressionDef.Constant[60];
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        MethodDef method = MethodDef.builder("classify")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("index", TypeDef.Primitive.INT)
            .returns(TypeDef.STRING)
            .build((ignored, parameters) -> {
                StatementDef shared = ExpressionDef.constant("shared").returning();
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = ExpressionDef.constant(i);
                    cases.put(keys[i], i == keys.length - 1 ? ExpressionDef.constant("last").returning() : shared);
                }
                return StatementDef.multi(
                    parameters.get(0).asStatementSwitch(TypeDef.STRING, cases, ExpressionDef.constant("none").returning())
                );
            });
        ClassDef definition = ClassDef.builder("example.JdkSharedSwitchParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
            .build();

        byte[] bytes = writeDirect(definition);
        Class<?> generated = new MapClassLoader(Map.of(definition.getName(), bytes))
            .loadClass(definition.getName());

        assertEquals("shared", generated.getMethod("classify", int.class).invoke(null, 0));
        assertEquals("shared", generated.getMethod("classify", int.class).invoke(null, 30));
        assertEquals("last", generated.getMethod("classify", int.class).invoke(null, keys.length - 1));
        assertEquals("none", generated.getMethod("classify", int.class).invoke(null, 999));
        // Two distinct bodies, not sixty: the shared body is emitted once
        assertTrue(bytes.length < 2000, () -> "Expected a compact switch, got " + bytes.length + " bytes");
    }

    @Test
    void directlyWritesNestedCastsWithoutUnboxingAndReboxingAReference() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkNestedCastParity")
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
    void directlyWritesClassesReferencingTypesThatAreNotResolvableYet() {
        // Types generated earlier in the same compilation round have no class file to read. The
        // writer must still emit the class rather than abandoning it, because the JVM checks the
        // real hierarchy when the class is finally loaded.
        ClassTypeDef first = ClassTypeDef.of("example.NotOnTheClassPathYetOne");
        ClassTypeDef second = ClassTypeDef.of("example.NotOnTheClassPathYetTwo");
        ClassDef definition = ClassDef.builder("example.JdkUnresolvedTypeParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> parameters.get(0).isTrue()
                    .doIfElse(first.instantiate().returning(), second.instantiate().returning())))
            .build();

        assertVerified(writeDirect(definition));

        // The same holds when a compilation type lookup is supplied but knows nothing about them
        var withLookup = new JdkClassFileWriter(true, name -> java.util.Optional.empty())
            .write(definition, null);
        assertTrue(withLookup.isPresent());
        assertVerified(withLookup.orElseThrow());
    }

    @Test
    void publicWriterFallsBackToJavacForConstructsTheDirectWriterDeclines() throws Exception {
        // A char selector is not lowered directly
        ClassDef definition = ClassDef.builder("example.JdkJavacFallback")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("describe")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.CHAR)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).asStatementSwitch(TypeDef.STRING,
                    Map.of(ExpressionDef.constant(1), ExpressionDef.constant("one").returning()),
                    ExpressionDef.constant("other").returning())))
            .build();
        assertTrue(new JdkClassFileWriter(true).write(definition, null).isEmpty());

        ByteCodeWriter writer = new ByteCodeWriter();
        // Twice, so the second compilation goes through the shared file manager
        byte[] first = writer.write(definition);
        byte[] second = writer.write(definition);
        assertArrayEquals(first, second);
        assertVerified(first);
        Class<?> generated = new MapClassLoader(Map.of(definition.getName(), first)).loadClass(definition.getName());
        assertEquals("one", generated.getMethod("describe", char.class).invoke(null, (char) 1));
        assertEquals("other", generated.getMethod("describe", char.class).invoke(null, (char) 9));
    }

    @Test
    void directlyWritesBoxedConstantsSmallPrimitivesAndPrimitiveHashCodes() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkBoxingParity")
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
    void directlyWritesInterfacesWithAbstractDefaultAndStaticMethods() throws Exception {
        MethodDef name = MethodDef.builder("name")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(TypeDef.STRING)
            .build();
        InterfaceDef contract = InterfaceDef.builder("example.JdkInterfaceParity")
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
        ClassDef implementation = ClassDef.builder("example.JdkInterfaceParityImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(contract.asTypeDef())
            .addMethod(MethodDef.builder("name")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("Ada").returning()))
            .build();

        MapClassLoader loader = new MapClassLoader(Map.of(
            contract.getName(), writeDirect(contract),
            implementation.getName(), writeDirect(implementation)
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
    void directlyWritesEnums() throws Exception {
        EnumDef definition = EnumDef.builder("example.JdkEnumParity")
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
    void directlyWritesNestedTypesAsNestmates() throws Exception {
        ClassTypeDef outerType = ClassTypeDef.of("example.JdkNestParity");
        FieldDef secret = FieldDef.builder("secret", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .initializer(ExpressionDef.constant(7))
            .build();
        // A member type is declared by its simple name; adding it to the outer type gives it its binary name
        ClassDef outer = ClassDef.builder(outerType.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(secret)
            .addInnerType(ClassDef.builder("Inner")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addMethod(MethodDef.builder("peek")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(TypeDef.Primitive.INT)
                    .build((ignored, parameters) -> outerType.getStaticField(secret).returning()))
                .build())
            .build();
        ObjectDef inner = outer.getInnerTypes().get(0);
        assertEquals("example.JdkNestParity$Inner", inner.getName());

        var innerResult = new JdkClassFileWriter(true).write(inner, outer.asTypeDef());
        assertTrue(innerResult.isPresent(), "Expected direct ClassFile lowering for the member type");
        assertVerified(innerResult.orElseThrow());
        MapClassLoader loader = new MapClassLoader(Map.of(
            outer.getName(), writeDirect(outer),
            inner.getName(), innerResult.orElseThrow()
        ));
        Class<?> outerClass = loader.loadClass(outer.getName());
        Class<?> innerClass = loader.loadClass(inner.getName());

        assertEquals(outerClass, innerClass.getDeclaringClass());
        assertEquals(outerClass, innerClass.getNestHost());
        assertArrayEquals(new Class<?>[] {innerClass}, outerClass.getDeclaredClasses());
        assertEquals("Inner", innerClass.getSimpleName());
        assertTrue(java.lang.reflect.Modifier.isStatic(innerClass.getModifiers()));
        // Reading the outer type's private field only links when the two really are nestmates
        assertEquals(7, innerClass.getMethod("peek").invoke(null));
    }

    @Test
    void directlyWritesTryWhoseCatchCompletesInsideAnIfBranch() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("example.JdkTryCompletionParity");
        MethodDef boom = MethodDef.builder("boom")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.STRING)
            .build((ignored, parameters) -> ClassTypeDef.of(IllegalStateException.class)
                .instantiate(ExpressionDef.constant("boom")).doThrow());
        ClassDef definition = ClassDef.builder(self.getName())
            .addModifiers(Modifier.PUBLIC)
            .addMethod(boom)
            .addMethod(MethodDef.builder("pick")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> StatementDef.multi(
                    parameters.get(0).isTrue().doIfElse(
                        StatementDef.doTry(self.invokeStatic(boom).returning())
                            .doCatch(RuntimeException.class, exception -> StatementDef.multi()),
                        ExpressionDef.constant("else").returning()
                    ),
                    ExpressionDef.constant("after").returning()
                )))
            .build();

        Class<?> generated = define(definition);

        // The caught exception must not fall through into the else branch
        assertEquals("after", generated.getMethod("pick", boolean.class).invoke(null, true));
        assertEquals("else", generated.getMethod("pick", boolean.class).invoke(null, false));
    }

    @Test
    @SuppressWarnings("removal")
    void directlyWritesConstructorDelegationWithoutRepeatingFieldInitializers() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("example.JdkConstructorDelegationParity");
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
    void directlyWritesJoinsOfModelOnlyTypesThroughTheHierarchyResolver() throws Exception {
        ClassDef first = ClassDef.builder("example.JdkMergeFirst").addModifiers(Modifier.PUBLIC).build();
        ClassDef second = ClassDef.builder("example.JdkMergeSecond").addModifiers(Modifier.PUBLIC).build();
        ClassDef definition = ClassDef.builder("example.JdkMergeParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> parameters.get(0).isTrue()
                    .doIfElse(first.asTypeDef().instantiate(), second.asTypeDef().instantiate())
                    .returning()))
            .build();

        // The stack map at the join needs the hierarchy of two classes that exist only as models
        MapClassLoader loader = new MapClassLoader(Map.of(
            first.getName(), writeDirect(first),
            second.getName(), writeDirect(second),
            definition.getName(), writeDirect(definition)
        ));
        Class<?> generated = loader.loadClass(definition.getName());

        assertInstanceOf(loader.loadClass(first.getName()), generated.getMethod("choose", boolean.class).invoke(null, true));
        assertInstanceOf(loader.loadClass(second.getName()), generated.getMethod("choose", boolean.class).invoke(null, false));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    private @interface ParameterMarker {
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

    private static Class<?> define(ObjectDef definition) throws ClassNotFoundException {
        return new MapClassLoader(Map.of(definition.getName(), writeDirect(definition))).loadClass(definition.getName());
    }

    private static byte[] writeDirect(ObjectDef definition) {
        var result = new JdkClassFileWriter(true).write(definition, null);
        assertTrue(result.isPresent(), () -> "Expected direct ClassFile lowering for " + definition.getName());
        byte[] bytes = result.orElseThrow();
        assertVerified(bytes);
        return bytes;
    }

    private static void assertVerified(byte[] bytes) {
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
    }

    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private MapClassLoader(Map<String, byte[]> classes) {
            super(JdkByteCodeWriterParityTest.class.getClassLoader());
            this.classes = new LinkedHashMap<>(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
