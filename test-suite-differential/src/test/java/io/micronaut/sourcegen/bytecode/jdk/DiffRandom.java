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
import io.micronaut.sourcegen.model.VariableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.*;
import static io.micronaut.sourcegen.model.ExpressionDef.constant;

/**
 * Seeded random programs for the differential test. They stay away from the
 * constructs already known to disagree (char, byte and short values, non-finite constants, boxed
 * reference comparisons, mixed-type conditionals) so that new disagreements surface.
 */
final class DiffRandom {
    private static final Ty BOOL = BOOLEAN;
    private static final List<Ty> PARAMS = List.of(INT, LONG, DOUBLE, STRING, BOOL);
    private static final List<Object[]> INPUTS = List.of(
        new Object[]{0, 0L, 0.0, "", false},
        new Object[]{7, -3L, 2.5, "abc", true},
        new Object[]{-5, 1L << 40, -1.25, "Aa", true},
        new Object[]{Integer.MAX_VALUE, Long.MIN_VALUE + 1, 1e10, "BB", false},
        new Object[]{100, 12345678901L, 0.1, "hello world", true});

    private final DiffModels m;
    private final Random random;
    private final long seed;

    DiffRandom(DiffModels m, long seed) {
        this.m = m;
        this.seed = seed;
        this.random = new Random(seed);
    }

    void generate(int count) {
        for (int i = 0; i < count; i++) {
            long modelSeed = seed * 1_000_003L + i;
            Ty result = pick(List.of(INT, LONG, DOUBLE, STRING, BOOL, INTEGER, OBJECT));
            int shape = random.nextInt(6);
            m.add("random seed=" + modelSeed + " shape=" + shape + " -> " + result, result, PARAMS, (self, p) -> {
                Random r = new Random(modelSeed);
                Gen g = new Gen(r, p);
                return g.statement(shape, result);
            }, INPUTS);
        }
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static final class Gen {
        private final Random r;
        private final List<VariableDef.MethodParameter> params;
        private final Map<Ty, List<ExpressionDef>> locals = new LinkedHashMap<>();
        private int localCounter;

        Gen(Random r, List<VariableDef.MethodParameter> params) {
            this.r = r;
            this.params = params;
        }

        StatementDef statement(int shape, Ty result) {
            return switch (shape) {
                case 0 -> ret(result, expr(result, 3));
                case 1 -> {
                    Ty lt = pickTy();
                    ExpressionDef init = expr(lt, 2);
                    yield init.newLocal(local(), v -> {
                        locals.computeIfAbsent(lt, k -> new ArrayList<>()).add(v);
                        return ret(result, expr(result, 3));
                    });
                }
                case 2 -> expr(BOOL, 2).isTrue().doIfElse(ret(result, expr(result, 2)), ret(result, expr(result, 2)));
                case 3 -> StatementDef.doTry(ret(result, expr(result, 3)))
                    .doCatch(ArithmeticException.class, e -> ret(result, expr(result, 1)))
                    .doCatch(RuntimeException.class, e -> ret(result, expr(result, 1)));
                case 4 -> {
                    Ty acc = pickFrom(List.of(INT, LONG, DOUBLE, STRING));
                    yield constant(0).newLocal(local(), i -> expr(acc, 1).newLocal(local(), a -> {
                        locals.computeIfAbsent(INT, k -> new ArrayList<>()).add(i);
                        locals.computeIfAbsent(acc, k -> new ArrayList<>()).add(a);
                        StatementDef body = StatementDef.multi(
                            ((VariableDef.Local) a).assign(expr(acc, 2)),
                            ((VariableDef.Local) i).assign(i.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(1))));
                        return StatementDef.multi(
                            i.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, constant(3)).whileLoop(body),
                            ret(result, expr(result, 2)));
                    }));
                }
                default -> {
                    // switch statement assigning a local, then returning it
                    Ty st = pickFrom(List.of(INT, STRING));
                    yield expr(result, 1).newLocal(local(), v -> {
                        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
                        for (int k = 0; k < 3; k++) {
                            ExpressionDef.Constant key = st == INT ? constant(k * 7 - 5) : constant(new String[]{"", "abc", "Aa"}[k]);
                            cases.put(key, ((VariableDef.Local) v).assign(expr(result, 2)));
                        }
                        return StatementDef.multi(
                            expr(st, 1).asStatementSwitch(st.def(), cases, ((VariableDef.Local) v).assign(expr(result, 1))),
                            v.returning());
                    });
                }
            };
        }

        private String local() {
            return "l" + (localCounter++);
        }

        private StatementDef ret(Ty result, ExpressionDef value) {
            return value.returning();
        }

        private Ty pickTy() {
            return pickFrom(List.of(INT, LONG, DOUBLE, STRING, BOOL, INTEGER));
        }

        private <T> T pickFrom(List<T> values) {
            return values.get(r.nextInt(values.size()));
        }

        ExpressionDef expr(Ty t, int depth) {
            if (depth <= 0 || r.nextInt(4) == 0) {
                return leaf(t);
            }
            int d = depth - 1;
            return switch (t.id()) {
                case "int" -> switch (r.nextInt(10)) {
                    case 0, 1 -> expr(INT, d).math(intOp(), expr(INT, d));
                    case 2 -> expr(INT, d).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE);
                    case 3 -> expr(pickFrom(List.of(LONG, DOUBLE)), d).cast(TypeDef.Primitive.INT);
                    case 4 -> new ExpressionDef.IfElse(expr(BOOL, d).isTrue(), expr(INT, d), expr(INT, d), TypeDef.Primitive.INT);
                    case 5 -> expr(STRING, d).invoke("length", TypeDef.Primitive.INT);
                    case 6 -> ClassTypeDef.of(Math.class).invokeStatic(pickFrom(List.of("max", "min")), TypeDef.Primitive.INT, expr(INT, d), expr(INT, d));
                    case 7 -> expr(INTEGER, d).cast(TypeDef.Primitive.INT);
                    case 8 -> intSwitch(d);
                    default -> expr(STRING, d).invoke("indexOf", TypeDef.Primitive.INT, expr(STRING, d));
                };
                case "long" -> switch (r.nextInt(6)) {
                    case 0, 1 -> expr(LONG, d).math(longOp(), expr(LONG, d));
                    case 2 -> expr(INT, d).cast(TypeDef.Primitive.LONG);
                    case 3 -> new ExpressionDef.IfElse(expr(BOOL, d).isTrue(), expr(LONG, d), expr(LONG, d), TypeDef.Primitive.LONG);
                    case 4 -> ClassTypeDef.of(Math.class).invokeStatic("abs", TypeDef.Primitive.LONG, expr(LONG, d));
                    default -> expr(LONG, d).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE);
                };
                case "double" -> switch (r.nextInt(5)) {
                    case 0, 1 -> expr(DOUBLE, d).math(pickFrom(List.of(ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                        ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION)), expr(DOUBLE, d));
                    case 2 -> expr(pickFrom(List.of(INT, LONG)), d).cast(TypeDef.Primitive.DOUBLE);
                    case 3 -> new ExpressionDef.IfElse(expr(BOOL, d).isTrue(), expr(DOUBLE, d), expr(DOUBLE, d), TypeDef.Primitive.DOUBLE);
                    default -> ClassTypeDef.of(Math.class).invokeStatic("floor", TypeDef.Primitive.DOUBLE, expr(DOUBLE, d));
                };
                case "boolean" -> switch (r.nextInt(9)) {
                    case 0 -> expr(INT, d).compare(compareOp(), expr(INT, d));
                    case 1 -> expr(LONG, d).compare(compareOp(), expr(LONG, d));
                    case 2 -> expr(DOUBLE, d).compare(compareOp(), expr(DOUBLE, d));
                    case 3 -> expr(BOOL, d).isTrue().and(expr(BOOL, d).isTrue());
                    case 4 -> expr(BOOL, d).isTrue().or(expr(BOOL, d).isTrue());
                    case 5 -> expr(STRING, d).equalsStructurally(expr(STRING, d));
                    case 6 -> expr(STRING, d).invoke("isEmpty", TypeDef.Primitive.BOOLEAN);
                    case 7 -> expr(OBJECT, d).instanceOf(ClassTypeDef.of(pickFrom(List.of(String.class, Integer.class, Number.class))));
                    default -> expr(BOOL, d).isFalse();
                };
                case "String" -> switch (r.nextInt(8)) {
                    case 0, 1 -> expr(STRING, d).stringConcat(expr(pickFrom(List.of(INT, LONG, DOUBLE, STRING, BOOL, INTEGER)), d));
                    case 2 -> ClassTypeDef.of(String.class).invokeStatic("valueOf", TypeDef.STRING, expr(pickFrom(List.of(INT, LONG, DOUBLE, BOOL)), d));
                    case 3 -> new ExpressionDef.IfElse(expr(BOOL, d).isTrue(), expr(STRING, d), expr(STRING, d), TypeDef.STRING);
                    case 4 -> expr(STRING, d).invoke("trim", TypeDef.STRING);
                    case 5 -> expr(INTEGER, d).invoke("toString", TypeDef.STRING);
                    case 6 -> stringSwitch(d);
                    default -> ClassTypeDef.of(Integer.class).invokeStatic("toHexString", TypeDef.STRING, expr(INT, d));
                };
                case "Integer" -> r.nextBoolean()
                    ? expr(INT, d).cast(TypeDef.of(Integer.class))
                    : ClassTypeDef.of(Integer.class).invokeStatic("valueOf", TypeDef.of(Integer.class), expr(INT, d));
                case "Object" -> expr(pickFrom(List.of(INT, LONG, DOUBLE, STRING, BOOL, INTEGER)), d).cast(TypeDef.OBJECT);
                default -> leaf(t);
            };
        }

        private ExpressionDef intSwitch(int d) {
            Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
            for (int k = 0; k < 3; k++) {
                cases.put(constant(k * 5 - 5), expr(INT, d));
            }
            return expr(INT, d).asExpressionSwitch(TypeDef.Primitive.INT, cases, expr(INT, d));
        }

        private ExpressionDef stringSwitch(int d) {
            Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
            String[] keys = {"", "abc", "x"};
            for (String key : keys) {
                cases.put(constant(key), expr(STRING, d));
            }
            return expr(STRING, d).asExpressionSwitch(TypeDef.STRING, cases, expr(STRING, d));
        }

        private ExpressionDef.MathBinaryOperation.OpType intOp() {
            ExpressionDef.MathBinaryOperation.OpType[] ops = ExpressionDef.MathBinaryOperation.OpType.values();
            return ops[r.nextInt(ops.length)];
        }

        private ExpressionDef.MathBinaryOperation.OpType longOp() {
            // shifts of a long are a known disagreement
            List<ExpressionDef.MathBinaryOperation.OpType> ops = List.of(ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION,
                ExpressionDef.MathBinaryOperation.OpType.DIVISION, ExpressionDef.MathBinaryOperation.OpType.MODULUS,
                ExpressionDef.MathBinaryOperation.OpType.BITWISE_AND, ExpressionDef.MathBinaryOperation.OpType.BITWISE_OR,
                ExpressionDef.MathBinaryOperation.OpType.BITWISE_XOR);
            return pickFrom(ops);
        }

        private ExpressionDef.ComparisonOperation.OpType compareOp() {
            ExpressionDef.ComparisonOperation.OpType[] ops = ExpressionDef.ComparisonOperation.OpType.values();
            return ops[r.nextInt(ops.length)];
        }

        private ExpressionDef leaf(Ty t) {
            List<ExpressionDef> options = new ArrayList<>();
            for (int i = 0; i < PARAMS.size(); i++) {
                if (PARAMS.get(i) == t) {
                    options.add(params.get(i));
                }
            }
            options.addAll(locals.getOrDefault(t, List.of()));
            switch (t.id()) {
                case "int" -> options.addAll(List.of(constant(0), constant(1), constant(3), constant(1000), constant(Integer.MAX_VALUE)));
                case "long" -> options.addAll(List.of(constant(0L), constant(2L), constant(1L << 35)));
                case "double" -> options.addAll(List.of(constant(0.5), constant(3.0), constant(1e15)));
                case "boolean" -> options.addAll(List.of(constant(true), constant(false)));
                case "String" -> options.addAll(List.of(constant(""), constant("abc"), constant("x y")));
                case "Integer" -> options.add(params.get(0).cast(TypeDef.of(Integer.class)));
                case "Object" -> options.add(params.get(3).cast(TypeDef.OBJECT));
                default -> {
                }
            }
            return options.get(r.nextInt(options.size()));
        }
    }
}
