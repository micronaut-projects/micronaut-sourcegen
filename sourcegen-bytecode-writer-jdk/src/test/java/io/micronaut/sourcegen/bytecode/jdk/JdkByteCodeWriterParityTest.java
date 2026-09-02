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
        assertTrue(new JdkClassFileWriter(true).write(additionalConstructor, null).isEmpty());
        byte[] bytes = new ByteCodeWriter().write(additionalConstructor);
        Class<?> fallbackClass = new MapClassLoader(Map.of(additionalConstructor.getName(), bytes))
            .loadClass(additionalConstructor.getName());
        Object value = fallbackClass.getConstructor(String.class).newInstance("Ada");
        assertEquals("JdkConstructorRecordParity[name=Ada, age=0]", value.toString());
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
