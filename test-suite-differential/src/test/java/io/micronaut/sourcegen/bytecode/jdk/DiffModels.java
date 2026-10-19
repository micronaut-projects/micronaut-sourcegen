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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The models of the differential test: the types with their sample values and constants, and the
 * builder of a model class with one {@code run} method.
 */
final class DiffModels {

    /** A type of the universe, with the values passed for a parameter of it. */
    record Ty(String id, TypeDef def, Class<?> cls, List<Object> samples) {
        boolean primitive() {
            return cls.isPrimitive();
        }

        boolean numeric() {
            return primitive() && cls != boolean.class;
        }

        boolean integral() {
            return cls == int.class || cls == long.class || cls == char.class || cls == byte.class || cls == short.class;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    static final Ty INT = new Ty("int", TypeDef.Primitive.INT, int.class, List.of(0, 7, -3, 200, Integer.MAX_VALUE, Integer.MIN_VALUE));
    static final Ty LONG = new Ty("long", TypeDef.Primitive.LONG, long.class, List.of(0L, 5L, -9L, Long.MAX_VALUE, 1L << 40));
    static final Ty CHAR = new Ty("char", TypeDef.Primitive.CHAR, char.class, List.of('a', '0', (char) 0, (char) 0xFFFF));
    static final Ty BYTE = new Ty("byte", TypeDef.Primitive.BYTE, byte.class, List.of((byte) 0, (byte) 100, (byte) -128, (byte) 127));
    static final Ty SHORT = new Ty("short", TypeDef.Primitive.SHORT, short.class, List.of((short) 0, (short) 300, (short) -32768, (short) 32767));
    static final Ty DOUBLE = new Ty("double", TypeDef.Primitive.DOUBLE, double.class, List.of(0.0, -0.0, 2.5, -7.75, Double.NaN, Double.POSITIVE_INFINITY, 1e20));
    static final Ty FLOAT = new Ty("float", TypeDef.Primitive.FLOAT, float.class, List.of(0f, 2.5f, Float.NaN, -1.5f));
    static final Ty BOOLEAN = new Ty("boolean", TypeDef.Primitive.BOOLEAN, boolean.class, List.of(true, false));
    static final Ty INTEGER = new Ty("Integer", TypeDef.of(Integer.class), Integer.class, List.of(5, 1000, -128));
    static final Ty LONG_W = new Ty("Long", TypeDef.of(Long.class), Long.class, List.of(5L, 1000L));
    static final Ty CHAR_W = new Ty("Character", TypeDef.of(Character.class), Character.class, List.of('a', 'Z'));
    static final Ty DOUBLE_W = new Ty("Double", TypeDef.of(Double.class), Double.class, List.of(2.5, -0.0));
    static final Ty BOOLEAN_W = new Ty("Boolean", TypeDef.of(Boolean.class), Boolean.class, List.of(true, false));
    static final Ty BYTE_W = new Ty("Byte", TypeDef.of(Byte.class), Byte.class, List.of((byte) 3));
    static final Ty SHORT_W = new Ty("Short", TypeDef.of(Short.class), Short.class, List.of((short) 3));
    static final Ty FLOAT_W = new Ty("Float", TypeDef.of(Float.class), Float.class, List.of(1.5f));
    static final Ty OBJECT = new Ty("Object", TypeDef.OBJECT, Object.class, List.of("s", 1000, 2.5, 'c', 5L));
    static final Ty STRING = new Ty("String", TypeDef.STRING, String.class, List.of("", "abc", "Aa", "BB", "$x"));
    static final Ty NUMBER = new Ty("Number", TypeDef.of(Number.class), Number.class, List.of(1000, 2.5, 5L));
    static final Ty CHAR_SEQ = new Ty("CharSequence", TypeDef.of(CharSequence.class), CharSequence.class, List.of("xyz"));
    static final Ty COMPARABLE = new Ty("Comparable", TypeDef.of(Comparable.class), Comparable.class, List.of("xyz", 4));
    static final Ty INT_ARRAY = new Ty("int[]", TypeDef.Primitive.INT.array(), int[].class, List.of(new int[]{1, 2, 3}, new int[0]));
    static final Ty CHAR_ARRAY = new Ty("char[]", TypeDef.Primitive.CHAR.array(), char[].class, List.of(new char[]{'h', 'i'}));
    static final Ty LONG_ARRAY = new Ty("long[]", TypeDef.Primitive.LONG.array(), long[].class, List.of(new long[]{4L, 5L}));
    static final Ty OBJECT_ARRAY = new Ty("Object[]", TypeDef.OBJECT.array(), Object[].class, List.of((Object) new Object[]{"a", 1}));
    static final Ty STRING_ARRAY = new Ty("String[]", TypeDef.STRING.array(), String[].class, List.of((Object) new String[]{"x", "y"}));
    static final Ty LIST_STRING = new Ty("List<String>", TypeDef.parameterized(List.class, String.class), List.class, List.of(List.of("p", "q")));

    static final List<Ty> NUMERIC = List.of(INT, LONG, CHAR, BYTE, SHORT, DOUBLE, FLOAT);
    static final List<Ty> BOXED = List.of(INTEGER, LONG_W, CHAR_W, DOUBLE_W, BOOLEAN_W, BYTE_W, SHORT_W, FLOAT_W);
    static final List<Ty> REFS = List.of(OBJECT, STRING, NUMBER, CHAR_SEQ, COMPARABLE);
    static final List<Ty> ARRAYS = List.of(INT_ARRAY, CHAR_ARRAY, LONG_ARRAY, OBJECT_ARRAY, STRING_ARRAY);
    static final List<Ty> ALL;

    static {
        List<Ty> all = new ArrayList<>(NUMERIC);
        all.add(BOOLEAN);
        all.addAll(BOXED);
        all.addAll(REFS);
        all.addAll(ARRAYS);
        all.add(LIST_STRING);
        ALL = List.copyOf(all);
    }

    private int counter;
    final List<DiffBackends.Model> models = new ArrayList<>();
    int rejected;
    final List<String> rejections = new ArrayList<>();

    /** Adds a model {@code public R run(P... params)} built by {@code body}. */
    void add(String description, Ty returns, List<Ty> params, BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> body) {
        add(description, returns.def(), params, body, null);
    }

    void add(String description, Ty returns, List<Ty> params, BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> body,
             List<Object[]> inputs) {
        add(description, returns.def(), params, body, inputs);
    }

    void add(String description, TypeDef returns, List<Ty> params,
             BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> body,
             List<Object[]> inputs) {
        String simpleName = "M" + (counter++);
        try {
            MethodDef.MethodDefBuilder method = MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(returns);
            for (int i = 0; i < params.size(); i++) {
                method.addParameter("p" + i, params.get(i).def());
            }
            MethodDef run = method.build(body::apply);
            ClassDef def = ClassDef.builder("dt." + simpleName).addModifiers(Modifier.PUBLIC).addMethod(run).build();
            Class<?>[] types = params.stream().map(Ty::cls).toArray(Class<?>[]::new);
            models.add(new DiffBackends.Model(simpleName, def, types, inputs != null ? inputs : inputs(params), description));
        } catch (Throwable e) {
            rejected++;
            if (rejections.size() < 400) {
                rejections.add(description + " -> " + e);
            }
        }
    }

    void addClass(String description, ClassDef.ClassDefBuilder builder, Class<?>[] types, List<Object[]> inputs) {
        try {
            models.add(new DiffBackends.Model(builder.build().getSimpleName(), builder.build(), types, inputs, description));
        } catch (Throwable e) {
            rejected++;
            rejections.add(description + " -> " + e);
        }
    }

    String nextName() {
        return "dt.M" + (counter++);
    }

    /** Cross product of the parameter samples, capped. */
    static List<Object[]> inputs(List<Ty> params) {
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[params.size()]);
        for (int i = 0; i < params.size(); i++) {
            List<Object[]> next = new ArrayList<>();
            for (Object[] prefix : result) {
                for (Object sample : params.get(i).samples()) {
                    Object[] copy = prefix.clone();
                    copy[i] = sample;
                    next.add(copy);
                }
            }
            result = next;
        }
        if (result.size() > 24) {
            List<Object[]> capped = new ArrayList<>();
            int step = result.size() / 24 + 1;
            for (int i = 0; i < result.size(); i += step) {
                capped.add(result.get(i));
            }
            result = capped;
        }
        return result;
    }

    static ExpressionDef[] constants(Ty ty) {
        return switch (ty.id()) {
            case "int" -> new ExpressionDef[]{ExpressionDef.constant(0), ExpressionDef.constant(1), ExpressionDef.constant(-1),
                ExpressionDef.constant(1000), ExpressionDef.constant(Integer.MIN_VALUE), ExpressionDef.constant(Integer.MAX_VALUE)};
            case "long" -> new ExpressionDef[]{ExpressionDef.constant(0L), ExpressionDef.constant(3L), ExpressionDef.constant(-1L),
                ExpressionDef.constant(Long.MIN_VALUE), ExpressionDef.constant(Long.MAX_VALUE), ExpressionDef.constant(1L << 33)};
            case "char" -> new ExpressionDef[]{ExpressionDef.constant('a'), ExpressionDef.constant('\n'), ExpressionDef.constant('\''),
                ExpressionDef.constant('\\'), ExpressionDef.constant('$'), ExpressionDef.constant((char) 0), ExpressionDef.constant((char) 0xFFFF),
                ExpressionDef.constant('"'), ExpressionDef.constant((char) 0xE9), ExpressionDef.constant((char) 0xD83D)};
            case "byte" -> new ExpressionDef[]{ExpressionDef.primitiveConstant((byte) -128), ExpressionDef.primitiveConstant((byte) 127),
                ExpressionDef.primitiveConstant((byte) 5), ExpressionDef.primitiveConstant((byte) -1)};
            case "short" -> new ExpressionDef[]{ExpressionDef.primitiveConstant((short) -32768), ExpressionDef.primitiveConstant((short) 300),
                ExpressionDef.primitiveConstant((short) -1)};
            case "double" -> new ExpressionDef[]{ExpressionDef.constant(0.0), ExpressionDef.constant(-0.0), ExpressionDef.constant(2.5),
                ExpressionDef.constant(Double.NaN), ExpressionDef.constant(Double.POSITIVE_INFINITY), ExpressionDef.constant(Double.NEGATIVE_INFINITY),
                ExpressionDef.constant(1e300), ExpressionDef.constant(Double.MIN_VALUE), ExpressionDef.constant(0.1), ExpressionDef.constant(1e20)};
            case "float" -> new ExpressionDef[]{ExpressionDef.constant(1.5f), ExpressionDef.constant(Float.NaN), ExpressionDef.constant(-0.0f),
                ExpressionDef.constant(Float.MAX_VALUE), ExpressionDef.constant(Float.MIN_VALUE), ExpressionDef.constant(0.1f),
                ExpressionDef.constant(Float.NEGATIVE_INFINITY)};
            case "boolean" -> new ExpressionDef[]{ExpressionDef.constant(true), ExpressionDef.constant(false)};
            case "Integer" -> new ExpressionDef[]{ExpressionDef.constant((Object) 5), ExpressionDef.constant((Object) 1000), ExpressionDef.constant((Object) Integer.MIN_VALUE)};
            case "Long" -> new ExpressionDef[]{ExpressionDef.constant((Object) 5L), ExpressionDef.constant((Object) Long.MIN_VALUE)};
            case "Character" -> new ExpressionDef[]{ExpressionDef.constant((Object) 'x'), ExpressionDef.constant((Object) '\'')};
            case "Double" -> new ExpressionDef[]{ExpressionDef.constant((Object) 2.5), ExpressionDef.constant((Object) Double.NaN)};
            case "Boolean" -> new ExpressionDef[]{ExpressionDef.constant((Object) Boolean.TRUE)};
            case "Byte" -> new ExpressionDef[]{ExpressionDef.constant((Object) (byte) -5)};
            case "Short" -> new ExpressionDef[]{ExpressionDef.constant((Object) (short) 7)};
            case "Float" -> new ExpressionDef[]{ExpressionDef.constant((Object) 0.5f)};
            case "String" -> new ExpressionDef[]{ExpressionDef.constant(""), ExpressionDef.constant("abc"), ExpressionDef.constant("Aa"),
                ExpressionDef.constant("BB"), ExpressionDef.constant("$x"), ExpressionDef.constant("${y}"), ExpressionDef.constant("a\"b"),
                ExpressionDef.constant("line\nbreak"), ExpressionDef.constant("\\"), ExpressionDef.constant(new String(new char[]{0xE9, 0xD83D, 0xDE00})),
                ExpressionDef.constant("tab\there"), ExpressionDef.constant(String.valueOf((char) 0))};
            default -> new ExpressionDef[0];
        };
    }
}
