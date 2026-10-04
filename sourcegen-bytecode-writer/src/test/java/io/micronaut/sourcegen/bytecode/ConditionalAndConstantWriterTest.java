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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.bytecode.tck.GeneratedClassLoader;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.util.List;
import java.util.function.Function;

import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.GREATER_THAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs the bytecode written for every conditional expression, both where the condition jumps when
 * it is false (a plain condition) and where it jumps when it is true (each side of an {@code or}),
 * and for constants of every boxed type.
 */
class ConditionalAndConstantWriterTest {

    private static final Object[] NULLS = {null, 0, 0, false, null};
    private static final Object[] MATCHING = {"a", 1, 1, true, "a"};
    private static final Object[] DIFFERENT = {1, -1, -1, true, "b"};

    @Test
    void writesEveryConditionWhenJumpingOnFalse() throws Exception {
        Class<?> type = define(conditions("example.ElseConditions", c -> c));
        assertConditions(type);
    }

    @Test
    void writesEveryConditionWhenJumpingOnTrue() throws Exception {
        // Each side of an `or` jumps to the true branch when it holds
        Class<?> type = define(conditions("example.IfConditions", c -> c.or(c)));
        assertConditions(type);
    }

    @Test
    void writesBoxedConstants() throws Exception {
        List<Object> values = List.of(true, DayOfWeek.MONDAY, String.class, 2L, 3.5d, 4.5f, 'c', (short) 5, (byte) 6, 7, "text");
        ClassDef.ClassDefBuilder builder = ClassDef.builder("example.Constants").addModifiers(Modifier.PUBLIC);
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            builder.addMethod(MethodDef.builder("constant" + i)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.OBJECT)
                .build((aThis, parameters) -> new ExpressionDef.Constant(ClassTypeDef.of(value.getClass()), value).returning()));
        }
        Class<?> type = define(builder.build());
        for (int i = 0; i < values.size(); i++) {
            assertEquals(values.get(i), type.getMethod("constant" + i).invoke(null));
        }
    }

    @Test
    void rejectsNonNumericComparisons() {
        ExpressionDef number = ExpressionDef.constant(1);
        ExpressionDef nullValue = ExpressionDef.nullValue();
        ExpressionDef string = ExpressionDef.constant("a");
        assertThrows(IllegalStateException.class, () -> nullValue.compare(GREATER_THAN, number));
        assertThrows(IllegalStateException.class, () -> number.compare(GREATER_THAN, nullValue));
        assertThrows(IllegalStateException.class, () -> string.compare(GREATER_THAN, number));
        assertThrows(IllegalStateException.class, () -> number.compare(GREATER_THAN, string));
    }

    private static void assertConditions(Class<?> type) throws Exception {
        assertCondition(type, "instanceOf", false, true, false);
        assertCondition(type, "greaterThan", false, true, false);
        assertCondition(type, "boxedGreaterThan", false, true, false);
        assertCondition(type, "isNull", true, false, false);
        assertCondition(type, "isNonNull", false, true, true);
        assertCondition(type, "isTrue", false, true, true);
        assertCondition(type, "isFalse", true, false, false);
        assertCondition(type, "equalsReferentially", true, true, false);
        assertCondition(type, "equalsStructurally", true, true, false);
        assertCondition(type, "notEqualsReferentially", false, false, true);
        assertCondition(type, "notEqualsStructurally", false, false, true);
    }

    private static void assertCondition(Class<?> type, String name, boolean nulls, boolean matching, boolean different) throws Exception {
        Method method = type.getMethod(name, Object.class, int.class, Integer.class, boolean.class, Object.class);
        assertEquals(nulls, method.invoke(null, NULLS), name);
        assertEquals(matching, method.invoke(null, MATCHING), name);
        assertEquals(different, method.invoke(null, DIFFERENT), name);
    }

    /**
     * A class with one static method per condition over the parameters
     * {@code (Object value, int number, Integer boxed, boolean flag, Object other)}, returning
     * whether the condition holds.
     */
    private static ClassDef conditions(String name, Function<ExpressionDef.ConditionExpressionDef, ExpressionDef.ConditionExpressionDef> shape) {
        ClassDef.ClassDefBuilder builder = ClassDef.builder(name).addModifiers(Modifier.PUBLIC);
        addCondition(builder, shape, "instanceOf", p -> p.get(0).instanceOf(ClassTypeDef.of(String.class)));
        addCondition(builder, shape, "greaterThan", p -> p.get(1).compare(GREATER_THAN, ExpressionDef.constant(0)));
        addCondition(builder, shape, "boxedGreaterThan", p -> p.get(2).compare(GREATER_THAN, ExpressionDef.constant(0)));
        addCondition(builder, shape, "isNull", p -> p.get(0).isNull());
        addCondition(builder, shape, "isNonNull", p -> p.get(0).isNonNull());
        addCondition(builder, shape, "isTrue", p -> p.get(3).isTrue());
        addCondition(builder, shape, "isFalse", p -> p.get(3).isFalse());
        addCondition(builder, shape, "equalsReferentially", p -> p.get(0).equalsReferentially(p.get(4)));
        addCondition(builder, shape, "equalsStructurally", p -> p.get(0).equalsStructurally(p.get(4)));
        addCondition(builder, shape, "notEqualsReferentially", p -> p.get(0).notEqualsReferentially(p.get(4)));
        addCondition(builder, shape, "notEqualsStructurally", p -> p.get(0).notEqualsStructurally(p.get(4)));
        return builder.build();
    }

    private static void addCondition(ClassDef.ClassDefBuilder builder,
                                     Function<ExpressionDef.ConditionExpressionDef, ExpressionDef.ConditionExpressionDef> shape,
                                     String name,
                                     Function<List<VariableDef.MethodParameter>, ExpressionDef.ConditionExpressionDef> condition) {
        builder.addMethod(MethodDef.builder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT)
            .addParameter("number", TypeDef.Primitive.INT)
            .addParameter("boxed", ClassTypeDef.of(Integer.class))
            .addParameter("flag", TypeDef.Primitive.BOOLEAN)
            .addParameter("other", TypeDef.OBJECT)
            .returns(TypeDef.Primitive.BOOLEAN)
            .build((aThis, parameters) -> shape.apply(condition.apply(parameters))
                .doIfElse(ExpressionDef.trueValue(), ExpressionDef.falseValue())
                .returning()));
    }

    private static Class<?> define(ClassDef classDef) {
        byte[] bytes = new ByteCodeWriter().write(classDef);
        return GeneratedClassLoader.defineDirectly(classDef.getName(), bytes);
    }
}
