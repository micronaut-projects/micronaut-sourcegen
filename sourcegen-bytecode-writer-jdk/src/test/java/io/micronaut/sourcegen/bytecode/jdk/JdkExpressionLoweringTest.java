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
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime behaviour of the expression and statement kinds the direct writer lowers, exercised
 * through generated classes rather than by inspecting bytecode.
 */
class JdkExpressionLoweringTest {

    private static Class<?> define(ClassDef definition) throws ClassNotFoundException {
        byte[] bytes = new JdkClassFileWriter(true).write(definition, null)
            .orElseThrow(() -> new AssertionError("Expected direct lowering of " + definition.getName()));
        return new MapClassLoader(Map.of(definition.getName(), bytes)).loadClass(definition.getName());
    }

    @Test
    void lowersAStringSwitchAsAStatementAndAsAnExpression() throws Exception {
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant("red"), ExpressionDef.constant("warm").returning());
        cases.put(ExpressionDef.constant("blue"), ExpressionDef.constant("cold").returning());
        Map<ExpressionDef.Constant, ExpressionDef> values = new LinkedHashMap<>();
        values.put(ExpressionDef.constant("red"), ExpressionDef.constant(1));
        values.put(ExpressionDef.constant("blue"), ExpressionDef.constant(2));

        ClassDef definition = ClassDef.builder("example.JdkStringSwitch")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("classify")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("colour", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).asStatementSwitch(
                    TypeDef.STRING, cases, ExpressionDef.constant("unknown").returning())))
            .addMethod(MethodDef.builder("rank")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("colour", TypeDef.STRING)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0).asExpressionSwitch(
                    TypeDef.Primitive.INT, values, ExpressionDef.constant(0)).returning()))
            .build();

        Class<?> generated = define(definition);

        assertEquals("warm", generated.getMethod("classify", String.class).invoke(null, "red"));
        assertEquals("cold", generated.getMethod("classify", String.class).invoke(null, "blue"));
        assertEquals("unknown", generated.getMethod("classify", String.class).invoke(null, "green"));
        assertEquals(1, generated.getMethod("rank", String.class).invoke(null, "red"));
        assertEquals(2, generated.getMethod("rank", String.class).invoke(null, "blue"));
        assertEquals(0, generated.getMethod("rank", String.class).invoke(null, "green"));
    }

    @Test
    void lowersEveryLongOperationAndUnaryNegation() throws Exception {
        var builder = ClassDef.builder("example.JdkLongOps").addModifiers(Modifier.PUBLIC);
        for (ExpressionDef.MathBinaryOperation.OpType operation
            : ExpressionDef.MathBinaryOperation.OpType.values()) {
            builder.addMethod(MethodDef.builder(operation.name())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.Primitive.LONG)
                .addParameter("right", TypeDef.Primitive.LONG)
                .returns(TypeDef.Primitive.LONG)
                .build((ignored, parameters) ->
                    parameters.get(0).math(operation, parameters.get(1)).returning()));
        }

        Class<?> generated = define(builder.build());

        assertEquals(23L, generated.getMethod("ADDITION", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(17L, generated.getMethod("SUBTRACTION", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(60L, generated.getMethod("MULTIPLICATION", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(6L, generated.getMethod("DIVISION", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(2L, generated.getMethod("MODULUS", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(0L, generated.getMethod("BITWISE_AND", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(23L, generated.getMethod("BITWISE_OR", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(23L, generated.getMethod("BITWISE_XOR", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(160L, generated.getMethod("BITWISE_LEFT_SHIFT", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(2L, generated.getMethod("BITWISE_RIGHT_SHIFT", long.class, long.class).invoke(null, 20L, 3L));
        assertEquals(2L, generated.getMethod("BITWISE_UNSIGNED_RIGHT_SHIFT", long.class, long.class)
            .invoke(null, 20L, 3L));
    }

    @Test
    void boxesAndUnboxesEveryPrimitive() throws Exception {
        record Case(String name, TypeDef.Primitive primitive, Class<?> boxed, Object value) {
        }
        var cases = java.util.List.of(
            new Case("aBoolean", TypeDef.Primitive.BOOLEAN, Boolean.class, true),
            new Case("aByte", TypeDef.Primitive.BYTE, Byte.class, (byte) 7),
            new Case("aChar", TypeDef.Primitive.CHAR, Character.class, 'x'),
            new Case("aShort", TypeDef.Primitive.SHORT, Short.class, (short) 9),
            new Case("anInt", TypeDef.Primitive.INT, Integer.class, 11),
            new Case("aLong", TypeDef.Primitive.LONG, Long.class, 13L),
            new Case("aFloat", TypeDef.Primitive.FLOAT, Float.class, 1.5f),
            new Case("aDouble", TypeDef.Primitive.DOUBLE, Double.class, 2.5d)
        );
        var builder = ClassDef.builder("example.JdkBoxing").addModifiers(Modifier.PUBLIC);
        for (Case aCase : cases) {
            builder.addMethod(MethodDef.builder("box" + aCase.name())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", aCase.primitive())
                .returns(ClassTypeDef.of(aCase.boxed()))
                .build((ignored, parameters) ->
                    parameters.get(0).cast(ClassTypeDef.of(aCase.boxed())).returning()));
            builder.addMethod(MethodDef.builder("unbox" + aCase.name())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(aCase.primitive())
                .build((ignored, parameters) -> parameters.get(0).cast(aCase.primitive()).returning()));
        }

        Class<?> generated = define(builder.build());

        for (Case aCase : cases) {
            Class<?> primitive = (Class<?>) aCase.boxed().getField("TYPE").get(null);
            assertEquals(aCase.value(),
                generated.getMethod("box" + aCase.name(), primitive).invoke(null, aCase.value()),
                aCase.name());
            assertEquals(aCase.value(),
                generated.getMethod("unbox" + aCase.name(), Object.class).invoke(null, aCase.value()),
                aCase.name());
        }
    }

    @Test
    void lowersEveryConditionInBothValueAndBranchPositions() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkConditions")
            .addModifiers(Modifier.PUBLIC)
            // As a branch: the condition jumps, so the inverted opcode is used
            .addMethod(MethodDef.builder("branch")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.STRING)
                .addParameter("right", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).isNull()
                    .or(parameters.get(1).isNull())
                    .doIfElse(ExpressionDef.constant("absent").returning(),
                        parameters.get(0).equalsStructurally(parameters.get(1))
                            .and(parameters.get(0).notEqualsReferentially(parameters.get(1)))
                            .doIfElse(ExpressionDef.constant("equal-copies").returning(),
                                ExpressionDef.constant("other").returning()))))
            // As a value: the condition materialises 0 or 1
            .addMethod(MethodDef.builder("sameInstance")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.STRING)
                .addParameter("right", TypeDef.STRING)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) ->
                    parameters.get(0).equalsReferentially(parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("differs")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.STRING)
                .addParameter("right", TypeDef.STRING)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) ->
                    parameters.get(0).notEqualsStructurally(parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("isText")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((ignored, parameters) ->
                    parameters.get(0).instanceOf(ClassTypeDef.of(String.class)).returning()))
            .build();

        Class<?> generated = define(definition);
        String copy = new String("same".toCharArray());

        assertEquals("absent", generated.getMethod("branch", String.class, String.class)
            .invoke(null, null, "x"));
        assertEquals("equal-copies", generated.getMethod("branch", String.class, String.class)
            .invoke(null, "same", copy));
        assertEquals("other", generated.getMethod("branch", String.class, String.class)
            .invoke(null, "a", "b"));
        assertTrue((boolean) generated.getMethod("sameInstance", String.class, String.class)
            .invoke(null, "same", "same"));
        assertFalse((boolean) generated.getMethod("sameInstance", String.class, String.class)
            .invoke(null, "same", copy));
        assertTrue((boolean) generated.getMethod("differs", String.class, String.class)
            .invoke(null, "a", "b"));
        assertTrue((boolean) generated.getMethod("isText", Object.class).invoke(null, "text"));
        assertFalse((boolean) generated.getMethod("isText", Object.class).invoke(null, 1));
    }

    @Test
    void lowersArraysPropertyReadsAndObjectMethods() throws Exception {
        FieldDef field = FieldDef.builder("name", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build();
        ClassDef definition = ClassDef.builder("example.JdkArraysAndObjects")
            .addModifiers(Modifier.PUBLIC)
            .addField(field)
            .addMethod(MethodDef.builder("sized")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("size", TypeDef.Primitive.INT)
                .returns(TypeDef.STRING.array())
                .build((ignored, parameters) ->
                    new ExpressionDef.NewArrayOfSize(TypeDef.STRING.array(), 3).returning()))
            .addMethod(MethodDef.builder("firstOf")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("values", TypeDef.STRING.array())
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).arrayElement(0).returning()))
            .addMethod(MethodDef.builder("typeName")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).invokeGetClass()
                    .invoke("getName", TypeDef.STRING, java.util.List.of()).returning()))
            .addMethod(MethodDef.builder("hash")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0).invokeHashCode().returning()))
            .addMethod(MethodDef.builder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) -> new ExpressionDef.StringConcatenation(
                    ExpressionDef.constant("name="), aThis.field(field)).returning()))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor().newInstance();
        generated.getField("name").set(instance, "Ada");

        assertEquals(3, ((String[]) generated.getMethod("sized", int.class).invoke(null, 3)).length);
        assertEquals("a", generated.getMethod("firstOf", String[].class)
            .invoke(null, (Object) new String[] {"a", "b"}));
        assertEquals("java.lang.String", generated.getMethod("typeName", Object.class).invoke(null, "x"));
        assertEquals("x".hashCode(), generated.getMethod("hash", Object.class).invoke(null, "x"));
        assertEquals("name=Ada", generated.getMethod("describe").invoke(instance));
    }

    @Test
    void lowersWhileLoopsAndSynchronizedBlocks() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkLoops")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("countTo")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("limit", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> ExpressionDef.constant(0).newLocal("total", total ->
                    StatementDef.multi(
                        total.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN,
                            parameters.get(0)).whileLoop(total.assign(total.math(
                                ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                                ExpressionDef.constant(1)))),
                        total.returning()))))
            .addMethod(MethodDef.builder("guarded")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("monitor", TypeDef.OBJECT)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> StatementDef.multi(
                    new StatementDef.Synchronized(parameters.get(0),
                        ExpressionDef.constant("inside").returning()))))
            .build();

        Class<?> generated = define(definition);

        assertEquals(5, generated.getMethod("countTo", int.class).invoke(null, 5));
        assertEquals(0, generated.getMethod("countTo", int.class).invoke(null, 0));
        assertEquals("inside", generated.getMethod("guarded", Object.class).invoke(null, new Object()));
    }

    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private MapClassLoader(Map<String, byte[]> classes) {
            super(JdkExpressionLoweringTest.class.getClassLoader());
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
