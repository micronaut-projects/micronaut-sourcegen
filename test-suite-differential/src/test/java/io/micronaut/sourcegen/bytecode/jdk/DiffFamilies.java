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

import io.micronaut.sourcegen.bytecode.jdk.DiffModels.Ty;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.*;

/**
 * The expression families of the differential test: casts, arithmetic, comparisons, equality,
 * unary checks, string concatenation, conditionals and constants.
 */
final class DiffFamilies {

    private DiffFamilies() {
    }

    /**
     * Generates the models of the families: the expression families and the random programs first,
     * then the statement family.
     *
     * @param m The models
     * @param families The families, comma separated, or {@code all}
     * @param seed The seed of the random programs
     * @param randomCount The number of random programs
     * @return The number of models generated before the statement family
     */
    static int generate(DiffModels m, String families, long seed, int randomCount) {
        Set<String> f = Set.of(families.split(","));
        boolean all = f.contains("all");
        if (all || f.contains("cast")) {
            casts(m);
        }
        if (all || f.contains("math")) {
            math(m);
        }
        if (all || f.contains("compare")) {
            comparisons(m);
        }
        if (all || f.contains("equality")) {
            equality(m);
        }
        if (all || f.contains("unary")) {
            unaryChecks(m);
        }
        if (all || f.contains("concat")) {
            concat(m);
        }
        if (all || f.contains("cond")) {
            conditionals(m);
        }
        if (all || f.contains("constant")) {
            constants(m);
        }
        if (all || f.contains("random")) {
            new DiffRandom(m, seed).generate(randomCount);
        }
        int expressions = m.models.size();
        if (all || f.contains("stmt")) {
            DiffStatements.generate(m);
        }
        return expressions;
    }

    // ------------------------------------------------------------------ casts

    static void casts(DiffModels m) {
        List<Ty> types = new ArrayList<>(ALL);
        for (Ty from : types) {
            for (Ty to : types) {
                if (from == to) {
                    continue;
                }
                m.add("cast param " + from + " -> " + to, to, List.of(from), (self, p) -> p.get(0).cast(to.def()).returning());
            }
        }
        // Casts of constants: the constant's own type differs from the target
        for (Ty from : List.of(INT, LONG, CHAR, BYTE, SHORT, DOUBLE, FLOAT, INTEGER, LONG_W, CHAR_W, DOUBLE_W)) {
            for (Ty to : List.of(INT, LONG, CHAR, BYTE, SHORT, DOUBLE, FLOAT, INTEGER, LONG_W, CHAR_W, DOUBLE_W, OBJECT, NUMBER, STRING)) {
                if (from == to) {
                    continue;
                }
                ExpressionDef[] cs = DiffModels.constants(from);
                for (int i = 0; i < cs.length; i++) {
                    ExpressionDef c = cs[i];
                    m.add("cast constant " + from + "#" + i + " -> " + to, to, List.of(), (self, p) -> c.cast(to.def()).returning());
                }
            }
        }
        // Cast returning Object, so that the value (and its box class) is observable
        for (Ty from : NUMERIC) {
            for (Ty to : NUMERIC) {
                m.add("cast param " + from + " -> " + to + " returned as Object", OBJECT, List.of(from), (self, p) -> p.get(0).cast(to.def()).cast(TypeDef.OBJECT).returning());
            }
        }
    }

    // ------------------------------------------------------------------ math

    static void math(DiffModels m) {
        for (var op : ExpressionDef.MathBinaryOperation.OpType.values()) {
            for (Ty left : NUMERIC) {
                for (Ty right : NUMERIC) {
                    m.add("math " + left + " " + op + " " + right, left, List.of(left, right),
                        (self, p) -> p.get(0).math(op, p.get(1)).returning());
                }
                // Observed through Object and through a wider result
                m.add("math " + left + " " + op + " " + left + " as Object", OBJECT, List.of(left, left),
                    (self, p) -> p.get(0).math(op, p.get(1)).cast(TypeDef.OBJECT).returning());
                m.add("math " + left + " " + op + " " + left + " as long", LONG, List.of(left, left),
                    (self, p) -> p.get(0).math(op, p.get(1)).cast(TypeDef.Primitive.LONG).returning());
                m.add("math " + left + " " + op + " int constant 1", left, List.of(left),
                    (self, p) -> p.get(0).math(op, ExpressionDef.constant(1)).returning());
                m.add("math (" + left + " " + op + " " + left + ") compared to 0", BOOLEAN, List.of(left, left),
                    (self, p) -> p.get(0).math(op, p.get(1)).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, ExpressionDef.constant(0)).returning());
                m.add("math (" + left + " " + op + " " + left + ") concatenated", STRING, List.of(left, left),
                    (self, p) -> ExpressionDef.constant("r=").stringConcat(p.get(0).math(op, p.get(1))).returning());
            }
            // boxed left operands
            for (Ty left : List.of(INTEGER, LONG_W, CHAR_W, DOUBLE_W)) {
                m.add("math boxed " + left + " " + op + " int", OBJECT, List.of(left, INT),
                    (self, p) -> p.get(0).math(op, p.get(1)).cast(TypeDef.OBJECT).returning());
            }
        }
        for (Ty t : NUMERIC) {
            m.add("negate " + t, t, List.of(t), (self, p) -> p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning());
            m.add("negate " + t + " as Object", OBJECT, List.of(t), (self, p) -> p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).cast(TypeDef.OBJECT).returning());
            m.add("negate " + t + " as String", STRING, List.of(t), (self, p) -> ExpressionDef.constant("").stringConcat(p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE)).returning());
            for (ExpressionDef c : DiffModels.constants(t)) {
                m.add("negate constant " + t + " " + ((ExpressionDef.Constant) c).value(), t, List.of(), (self, p) -> c.math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning());
            }
        }
        for (Ty t : List.of(INTEGER, LONG_W, DOUBLE_W, CHAR_W)) {
            m.add("negate boxed " + t, OBJECT, List.of(t), (self, p) -> p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).cast(TypeDef.OBJECT).returning());
        }
    }

    // ------------------------------------------------------------------ comparisons

    static void comparisons(DiffModels m) {
        List<Ty> types = new ArrayList<>(NUMERIC);
        types.addAll(List.of(INTEGER, LONG_W, CHAR_W, DOUBLE_W, BYTE_W, SHORT_W, FLOAT_W));
        for (var op : ExpressionDef.ComparisonOperation.OpType.values()) {
            for (Ty left : types) {
                for (Ty right : types) {
                    m.add("compare " + left + " " + op + " " + right, BOOLEAN, List.of(left, right),
                        (self, p) -> p.get(0).compare(op, p.get(1)).returning());
                }
            }
            for (Ty left : List.of(OBJECT, STRING, BOOLEAN, BOOLEAN_W, NUMBER, INT_ARRAY)) {
                for (Ty right : List.of(OBJECT, STRING, BOOLEAN, BOOLEAN_W, NUMBER, INTEGER, INT)) {
                    m.add("compare " + left + " " + op + " " + right, BOOLEAN, List.of(left, right),
                        (self, p) -> p.get(0).compare(op, p.get(1)).returning());
                }
            }
        }
        // Identity of boxes outside the cache
        m.add("compare Integer.valueOf(1000) == Integer.valueOf(1000)", BOOLEAN, List.of(INT), (self, p) ->
            ClassTypeDef.of(Integer.class).invokeStatic("valueOf", TypeDef.of(Integer.class), p.get(0))
                .compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                    ClassTypeDef.of(Integer.class).invokeStatic("valueOf", TypeDef.of(Integer.class), p.get(0))).returning(),
            List.<Object[]>of(new Object[]{1000}, new Object[]{5}));
    }

    // ------------------------------------------------------------------ equality

    static void equality(DiffModels m) {
        List<Ty> types = List.of(INT, LONG, CHAR, DOUBLE, FLOAT, BOOLEAN, BYTE, INTEGER, LONG_W, CHAR_W, DOUBLE_W, BOOLEAN_W, OBJECT, STRING, NUMBER, INT_ARRAY);
        for (Ty left : types) {
            for (Ty right : types) {
                m.add("equalsStructurally " + left + " " + right, BOOLEAN, List.of(left, right),
                    (self, p) -> p.get(0).equalsStructurally(p.get(1)).returning());
                m.add("notEqualsStructurally " + left + " " + right, BOOLEAN, List.of(left, right),
                    (self, p) -> p.get(0).notEqualsStructurally(p.get(1)).returning());
                m.add("equalsReferentially " + left + " " + right, BOOLEAN, List.of(left, right),
                    (self, p) -> p.get(0).equalsReferentially(p.get(1)).returning());
                m.add("notEqualsReferentially " + left + " " + right, BOOLEAN, List.of(left, right),
                    (self, p) -> p.get(0).notEqualsReferentially(p.get(1)).returning());
            }
        }
        // Same value both sides, boxed out of the cache
        for (Ty t : List.of(INTEGER, LONG_W, DOUBLE_W, STRING)) {
            m.add("equalsReferentially same " + t, BOOLEAN, List.of(t), (self, p) -> p.get(0).equalsReferentially(p.get(0)).returning());
        }
        m.add("equalsReferentially boxed 1000", BOOLEAN, List.of(INT, INT),
            (self, p) -> p.get(0).cast(TypeDef.of(Integer.class)).equalsReferentially(p.get(1).cast(TypeDef.of(Integer.class))).returning(),
            List.<Object[]>of(new Object[]{1000, 1000}, new Object[]{5, 5}));
        m.add("EQUAL_TO boxed 1000", BOOLEAN, List.of(INT, INT),
            (self, p) -> p.get(0).cast(TypeDef.of(Integer.class)).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, p.get(1).cast(TypeDef.of(Integer.class))).returning(),
            List.<Object[]>of(new Object[]{1000, 1000}, new Object[]{5, 5}));
        m.add("equalsStructurally boxed 1000", BOOLEAN, List.of(INT, INT),
            (self, p) -> p.get(0).cast(TypeDef.of(Integer.class)).equalsStructurally(p.get(1).cast(TypeDef.of(Integer.class))).returning(),
            List.<Object[]>of(new Object[]{1000, 1000}, new Object[]{5, 5}));
        m.add("equalsStructurally new String", BOOLEAN, List.of(STRING),
            (self, p) -> ClassTypeDef.of(String.class).instantiate(p.get(0)).equalsStructurally(p.get(0)).returning());
        m.add("equalsReferentially new String", BOOLEAN, List.of(STRING),
            (self, p) -> ClassTypeDef.of(String.class).instantiate(p.get(0)).equalsReferentially(p.get(0)).returning());
        m.add("EQUAL_TO new String", BOOLEAN, List.of(STRING),
            (self, p) -> ClassTypeDef.of(String.class).instantiate(p.get(0)).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, p.get(0)).returning());
    }

    // ------------------------------------------------------------------ null, instanceof, hashCode, getClass

    static void unaryChecks(DiffModels m) {
        for (Ty t : ALL) {
            m.add("isNull " + t, BOOLEAN, List.of(t), (self, p) -> p.get(0).isNull().returning());
            m.add("isNonNull " + t, BOOLEAN, List.of(t), (self, p) -> p.get(0).isNonNull().returning());
            m.add("hashCode " + t, INT, List.of(t), (self, p) -> p.get(0).invokeHashCode().returning());
            if (!t.primitive()) {
                m.add("getClass " + t, STRING, List.of(t), (self, p) -> p.get(0).invokeGetClass().invoke("getName", TypeDef.STRING).returning());
                m.add("toString " + t, STRING, List.of(t), (self, p) -> p.get(0).invoke("toString", TypeDef.STRING).returning());
            }
            for (Class<?> target : List.of(Integer.class, Number.class, String.class, Object.class, Comparable.class)) {
                m.add("instanceOf " + t + " " + target.getSimpleName(), BOOLEAN, List.of(t),
                    (self, p) -> p.get(0).instanceOf(ClassTypeDef.of(target)).returning());
            }
            if (t == BOOLEAN || t == BOOLEAN_W) {
                m.add("isTrue " + t, BOOLEAN, List.of(t), (self, p) -> p.get(0).isTrue().returning());
                m.add("isFalse " + t, BOOLEAN, List.of(t), (self, p) -> p.get(0).isFalse().returning());
            }
        }
        for (Ty t : ALL) {
            for (ExpressionDef c : DiffModels.constants(t)) {
                m.add("hashCode constant " + t + " " + ((ExpressionDef.Constant) c).value(), INT, List.of(), (self, p) -> c.invokeHashCode().returning());
            }
        }
        m.add("isNull null constant", BOOLEAN, List.of(), (self, p) -> ExpressionDef.nullValue().isNull().returning());
        m.add("hashCode null constant", INT, List.of(), (self, p) -> ExpressionDef.nullValue().invokeHashCode().returning());
        m.add("equalsStructurally null constant", BOOLEAN, List.of(STRING), (self, p) -> ExpressionDef.nullValue().equalsStructurally(p.get(0)).returning());
    }

    // ------------------------------------------------------------------ string concatenation

    static void concat(DiffModels m) {
        List<Ty> types = new ArrayList<>(NUMERIC);
        types.addAll(List.of(BOOLEAN, INTEGER, CHAR_W, DOUBLE_W, OBJECT, STRING, NUMBER, CHAR_ARRAY));
        for (Ty left : types) {
            for (Ty right : types) {
                m.add("concat " + left + " + " + right, STRING, List.of(left, right),
                    (self, p) -> p.get(0).stringConcat(p.get(1)).returning());
            }
        }
        for (Ty a : List.of(INT, CHAR, LONG, DOUBLE, STRING, INTEGER, CHAR_W)) {
            for (Ty b : List.of(INT, CHAR, LONG, DOUBLE, STRING, INTEGER, CHAR_W)) {
                for (Ty c : List.of(INT, CHAR, STRING)) {
                    m.add("concat " + a + " + (" + b + " + " + c + ")", STRING, List.of(a, b, c),
                        (self, p) -> p.get(0).stringConcat(p.get(1).stringConcat(p.get(2))).returning());
                    m.add("concat (" + a + " + " + b + ") + " + c, STRING, List.of(a, b, c),
                        (self, p) -> p.get(0).stringConcat(p.get(1)).stringConcat(p.get(2)).returning());
                }
                m.add("concat (" + a + " + " + b + ") as Object", OBJECT, List.of(a, b),
                    (self, p) -> p.get(0).stringConcat(p.get(1)).cast(TypeDef.OBJECT).returning());
            }
        }
        for (Ty t : types) {
            for (ExpressionDef c : DiffModels.constants(t)) {
                m.add("concat constant " + t + " " + ((ExpressionDef.Constant) c).value() + " + param", STRING, List.of(t),
                    (self, p) -> c.stringConcat(p.get(0)).returning());
                m.add("concat param + constant " + t + " " + ((ExpressionDef.Constant) c).value(), STRING, List.of(t),
                    (self, p) -> p.get(0).stringConcat(c).returning());
            }
        }
        m.add("concat null + null", STRING, List.of(), (self, p) -> ExpressionDef.nullValue().stringConcat(ExpressionDef.nullValue()).returning());
        m.add("concat math in concat", STRING, List.of(INT, INT), (self, p) -> ExpressionDef.constant("x").stringConcat(p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1))).returning());
        m.add("concat math before concat", STRING, List.of(INT, INT), (self, p) -> p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).stringConcat(ExpressionDef.constant("x")).returning());
    }

    // ------------------------------------------------------------------ conditionals

    static void conditionals(DiffModels m) {
        List<Ty> types = List.of(INT, LONG, CHAR, DOUBLE, BYTE, INTEGER, LONG_W, DOUBLE_W, CHAR_W, STRING, OBJECT, NUMBER, INT_ARRAY);
        List<Ty> results = List.of(OBJECT, INT, LONG, DOUBLE, CHAR, INTEGER, NUMBER, STRING, BOOLEAN);
        for (Ty a : types) {
            for (Ty b : types) {
                for (Ty r : results) {
                    m.add("ifElse(" + a + ", " + b + ") : " + r, r, List.of(BOOLEAN, a, b),
                        (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), r.def()).returning());
                }
                m.add("ifElse(" + a + ", " + b + ") implicit, as Object", OBJECT, List.of(BOOLEAN, a, b),
                    (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2)).cast(TypeDef.OBJECT).returning());
            }
        }
        // Constant branches
        for (Ty a : List.of(INT, CHAR, LONG, DOUBLE, INTEGER, STRING)) {
            for (Ty b : List.of(INT, CHAR, LONG, DOUBLE, INTEGER, STRING)) {
                ExpressionDef ca = DiffModels.constants(a)[1];
                ExpressionDef cb = DiffModels.constants(b)[0];
                m.add("ifElse constants(" + a + ", " + b + ") : Object", OBJECT, List.of(BOOLEAN),
                    (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), ca, cb, TypeDef.OBJECT).returning());
                m.add("ifElse constants(" + a + ", " + b + ") concat", STRING, List.of(BOOLEAN),
                    (self, p) -> ExpressionDef.constant("").stringConcat(new ExpressionDef.IfElse(p.get(0).isTrue(), ca, cb, TypeDef.OBJECT)).returning());
            }
        }
        // Nulls in branches
        for (Ty r : List.of(OBJECT, INTEGER, STRING, INT)) {
            m.add("ifElse(null, Integer) : " + r, r, List.of(BOOLEAN, INTEGER),
                (self, p) -> new ExpressionDef.IfElse(p.get(0).isTrue(), ExpressionDef.nullValue(), p.get(1), r.def()).returning());
        }
    }

    // ------------------------------------------------------------------ constants

    static void constants(DiffModels m) {
        for (Ty t : ALL) {
            ExpressionDef[] cs = DiffModels.constants(t);
            for (int i = 0; i < cs.length; i++) {
                ExpressionDef c = cs[i];
                m.add("constant " + t + "#" + i, t, List.of(), (self, p) -> c.returning());
                m.add("constant " + t + "#" + i + " as Object", OBJECT, List.of(), (self, p) -> c.cast(TypeDef.OBJECT).returning());
                m.add("constant " + t + "#" + i + " as local", t, List.of(), (self, p) -> c.newLocal("v", v -> v.returning()));
                m.add("constant " + t + "#" + i + " in array", OBJECT, List.of(), (self, p) -> new ExpressionDef.NewArrayInitialized(t.def().array(), List.of(c, c)).returning());
            }
        }
        // Arrays of every type, sized and initialized
        for (Ty t : ALL) {
            m.add("new array of size " + t, OBJECT, List.of(), (self, p) -> new ExpressionDef.NewArrayOfSize(t.def().array(), 2).returning());
            m.add("new 2d array of size " + t, OBJECT, List.of(), (self, p) -> new ExpressionDef.NewArrayOfSize(TypeDef.array(t.def(), 2), 2).returning());
            m.add("new array element of size " + t, t, List.of(), (self, p) -> new ExpressionDef.NewArrayOfSize(t.def().array(), 2).arrayElement(1).returning());
            m.add("param array element " + t, t, List.of(t.def() instanceof TypeDef.Array ? t : arrayOf(t)),
                (self, p) -> p.get(0).arrayElement(0).returning(), null);
        }
    }

    private static Ty arrayOf(Ty t) {
        Object sample;
        if (t.cls().isPrimitive()) {
            sample = java.lang.reflect.Array.newInstance(t.cls(), 1);
            java.lang.reflect.Array.set(sample, 0, t.samples().get(t.samples().size() - 1));
        } else {
            sample = java.lang.reflect.Array.newInstance(t.cls(), 1);
            java.lang.reflect.Array.set(sample, 0, t.samples().get(0));
        }
        return new Ty(t.id() + "[]", t.def().array(), sample.getClass(), List.of(sample));
    }

    static <K, V> Map<K, V> linked(Object... kv) {
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kv[i];
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            map.put(k, v);
        }
        return map;
    }

    static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }

    static StatementDef ret(ExpressionDef e) {
        return e.returning();
    }
}
