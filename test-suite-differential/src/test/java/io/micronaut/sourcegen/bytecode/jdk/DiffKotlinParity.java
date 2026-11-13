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

import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.*;
import static io.micronaut.sourcegen.model.ExpressionDef.constant;

/**
 * The statement models whose Kotlin source disagreed with the other backends: captured locals, a negated cast,
 * the keys of a switch on a wrapper, a {@code null} value of a conditional or a switch, a narrow array index and a
 * call through a bridge.
 */
final class DiffKotlinParity {

    private static final String OTHER = "other";

    private DiffKotlinParity() {
    }

    static void generate(DiffModels m) {
        captures(m);
        boxedSwitchKeys(m);
        nullValues(m);
        conversions(m);
        boxingConversions(m);
        logicalComparisons(m);
    }

    static void boxingConversions(DiffModels m) {
        for (Ty from : List.of(INT, CHAR, BYTE, SHORT)) {
            for (Ty to : List.of(LONG_W, DOUBLE_W, FLOAT_W)) {
                m.add("implicit return " + from + " as " + to, to, List.of(from), (self, p) -> p.get(0).returning());
                m.add("implicit local " + from + " as " + to, OBJECT, List.of(from), (self, p) -> {
                    var define = new StatementDef.DefineAndAssign(new VariableDef.Local("x", to.def()), p.get(0));
                    return StatementDef.multi(define, define.variable().returning());
                });
            }
        }
        String name = m.nextName();
        var owner = io.micronaut.sourcegen.model.ClassTypeDef.of(name);
        var echo = io.micronaut.sourcegen.model.MethodDef.builder("echo")
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC)
            .addParameter("value", LONG_W.def()).returns(TypeDef.OBJECT).build((self, p) -> p.get(0).returning());
        var run = io.micronaut.sourcegen.model.MethodDef.builder("run").addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .addParameter("p0", TypeDef.Primitive.INT).returns(TypeDef.OBJECT)
            .build((self, p) -> owner.invokeStatic(echo, p.get(0)).returning());
        m.addClass("implicit argument int as Long", io.micronaut.sourcegen.model.ClassDef.builder(name)
                .addModifiers(javax.lang.model.element.Modifier.PUBLIC).addMethod(echo).addMethod(run),
            new Class<?>[]{int.class}, List.<Object[]>of(new Object[]{5}, new Object[]{-1}));
    }

    static void logicalComparisons(DiffModels m) {
        m.add("comparison of a conjunction", BOOLEAN, List.of(BOOLEAN, BOOLEAN, BOOLEAN), (self, p) ->
            new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                new ExpressionDef.And(p.get(0).isTrue(), p.get(1).isTrue()), p.get(2)).returning());
        m.add("comparison of a disjunction", BOOLEAN, List.of(BOOLEAN, BOOLEAN, BOOLEAN), (self, p) ->
            new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO,
                new ExpressionDef.Or(p.get(0).isTrue(), p.get(1).isTrue()), p.get(2)).returning());
    }

    static void captures(DiffModels m) {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        m.add("lambda captures local reassigned after", STRING, List.of(STRING), (self, p) ->
            p.get(0).newLocal("text", text ->
                supplier.getLambda().implement((ls, lp) -> text.returning()).newLocal("supplier", s -> StatementDef.multi(
                    ((VariableDef.Local) text).assign(constant("second")),
                    s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning()))));
    }

    static void boxedSwitchKeys(DiffModels m) {
        for (Ty t : List.of(BYTE_W, SHORT_W, CHAR_W)) {
            m.add("switch statement int keys " + t, STRING, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
                cases.put(constant(3), constant("three").returning());
                cases.put(constant(97), constant("a").returning());
                return StatementDef.multi(p.get(0).asStatementSwitch(t.def(), cases, null), constant(OTHER).returning());
            });
            m.add("switch expression int keys " + t, STRING, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                cases.put(constant(3), constant("three"));
                cases.put(constant(97), constant("a"));
                return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant(OTHER)).returning();
            });
        }
    }

    static void nullValues(DiffModels m) {
        m.add("conditional with null branch into local", STRING, List.of(BOOLEAN), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), constant("a"), ExpressionDef.nullValue(), TypeDef.STRING)
                .newLocal("s", s -> s.returning()));
        m.add("switch expression null case", STRING, List.of(INT), (self, p) ->
            p.get(0).asExpressionSwitch(TypeDef.STRING, Map.of(constant(7), ExpressionDef.nullValue()), constant("a")).returning());
    }

    static void conversions(DiffModels m) {
        m.add("isFalse of Object cast to boolean", BOOLEAN, List.of(BOOLEAN_W), (self, p) ->
            p.get(0).cast(TypeDef.OBJECT).cast(TypeDef.Primitive.BOOLEAN).isFalse().returning());
        for (Ty t : List.of(BYTE, SHORT, CHAR)) {
            m.add("array element at " + t + " index", INT, List.of(INT_ARRAY, t), (self, p) ->
                new ExpressionDef.ArrayElement(p.get(0), TypeDef.Primitive.INT, p.get(1)).returning(),
                List.<Object[]>of(new Object[]{new int[]{1, 2}, sample(t)}));
        }
        m.add("compareTo through the bridge of String", INT, List.of(STRING, OBJECT), (self, p) -> {
            try {
                return p.get(0).invoke(Comparable.class.getMethod("compareTo", Object.class), p.get(1)).returning();
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }, List.<Object[]>of(new Object[]{"a", "a"}, new Object[]{"a", "b"}));
    }

    private static Object sample(Ty t) {
        return switch (t.id()) {
            case "byte" -> (byte) 1;
            case "short" -> (short) 1;
            default -> (char) 1;
        };
    }
}
