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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.*;
import static io.micronaut.sourcegen.model.ExpressionDef.constant;

/**
 * The statement families of the differential test: switches, try blocks, loops, fields, lambdas,
 * invocations and receivers.
 */
final class DiffStatements {

    private DiffStatements() {
    }

    static void generate(DiffModels m) {
        switches(m);
        tries(m);
        loops(m);
        fields(m);
        lambdas(m);
        invocations(m);
        receivers(m);
    }

    // ------------------------------------------------------------------ switch

    static void switches(DiffModels m) {
        // Statement switch whose cases only assign: must not fall through
        for (Ty t : List.of(INT, CHAR, STRING, INTEGER, CHAR_W, LONG, BYTE, SHORT)) {
            List<ExpressionDef.Constant> keys = switchKeys(t);
            if (keys.isEmpty()) {
                continue;
            }
            m.add("switch statement assigns " + t, STRING, List.of(t), (self, p) ->
                constant("init").newLocal("r", r -> {
                    Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
                    int i = 0;
                    for (ExpressionDef.Constant k : keys) {
                        cases.put(k, ((VariableDef.Local) r).assign(constant("case" + (i++))));
                    }
                    return StatementDef.multi(
                        p.get(0).asStatementSwitch(t.def(), cases, ((VariableDef.Local) r).assign(constant("default"))),
                        r.returning());
                }), inputsFor(t, keys));
            m.add("switch statement returns " + t, STRING, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
                int i = 0;
                for (ExpressionDef.Constant k : keys) {
                    cases.put(k, constant("case" + (i++)).returning());
                }
                return StatementDef.multi(p.get(0).asStatementSwitch(t.def(), cases, null), constant("after").returning());
            }, inputsFor(t, keys));
            m.add("switch expression " + t, STRING, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                int i = 0;
                for (ExpressionDef.Constant k : keys) {
                    cases.put(k, constant("case" + (i++)));
                }
                return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("default")).returning();
            }, inputsFor(t, keys));
            m.add("switch expression yield " + t, STRING, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                int i = 0;
                for (ExpressionDef.Constant k : keys) {
                    String v = "case" + (i++);
                    cases.put(k, new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
                        constant(v).newLocal("y", y -> y.stringConcat(constant("!")).returning())));
                }
                return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("default")).returning();
            }, inputsFor(t, keys));
            m.add("switch expression int result " + t, INT, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                int i = 0;
                for (ExpressionDef.Constant k : keys) {
                    cases.put(k, constant(10 + (i++)));
                }
                return p.get(0).asExpressionSwitch(TypeDef.Primitive.INT, cases, constant(-1)).returning();
            }, inputsFor(t, keys));
            m.add("switch expression Object result mixed " + t, OBJECT, List.of(t), (self, p) -> {
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                int i = 0;
                for (ExpressionDef.Constant k : keys) {
                    cases.put(k, (i++ % 2 == 0) ? constant(1) : constant(2L));
                }
                return p.get(0).asExpressionSwitch(TypeDef.OBJECT, cases, constant(3.5)).returning();
            }, inputsFor(t, keys));
        }
        // Null string in a switch
        m.add("switch expression null String", STRING, List.of(STRING), (self, p) -> {
            Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
            cases.put(constant("a"), constant("A"));
            return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("default")).returning();
        }, List.<Object[]>of(new Object[]{null}, new Object[]{"a"}));
    }

    private static List<ExpressionDef.Constant> switchKeys(Ty t) {
        return switch (t.id()) {
            case "int", "Integer" -> List.of(constant(1), constant(-5), constant(1000), constant(3));
            case "long" -> List.of(constant(1L), constant(5000000000L));
            case "byte" -> List.of(ExpressionDef.primitiveConstant((byte) 1), ExpressionDef.primitiveConstant((byte) -3));
            case "short" -> List.of(ExpressionDef.primitiveConstant((short) 1), ExpressionDef.primitiveConstant((short) 300));
            case "char", "Character" -> List.of(constant('a'), constant('Z'), constant('0'));
            case "String" -> List.of(constant("Aa"), constant("BB"), constant("x"), constant(""));
            default -> List.of();
        };
    }

    private static List<Object[]> inputsFor(Ty t, List<ExpressionDef.Constant> keys) {
        List<Object[]> result = new ArrayList<>();
        for (ExpressionDef.Constant k : keys) {
            result.add(new Object[]{convert(t, k.value())});
        }
        for (Object s : t.samples()) {
            result.add(new Object[]{s});
        }
        return result;
    }

    private static Object convert(Ty t, Object v) {
        return switch (t.id()) {
            case "Integer", "int" -> ((Number) v).intValue();
            case "long" -> ((Number) v).longValue();
            case "byte" -> ((Number) v).byteValue();
            case "short" -> ((Number) v).shortValue();
            default -> v;
        };
    }

    // ------------------------------------------------------------------ try / catch / finally

    static void tries(DiffModels m) {
        ExpressionDef.NewInstance ise = ClassTypeDef.of(IllegalStateException.class).instantiate(constant("boom"));
        // try returns the local, finally changes it: the value before finally is returned
        m.add("try return local, finally assigns", INT, List.of(INT), (self, p) ->
            p.get(0).newLocal("x", x -> StatementDef.doTry(x.returning())
                .doFinally(((VariableDef.Local) x).assign(constant(99)))));
        // finally overrides the try's return
        m.add("finally returns over try", INT, List.of(INT), (self, p) ->
            StatementDef.doTry(p.get(0).returning()).doFinally(constant(42).returning()));
        // finally overrides a thrown exception
        m.add("finally returns over throw", INT, List.of(INT), (self, p) ->
            StatementDef.doTry(ise.doThrow()).doFinally(constant(7).returning()));
        // division by zero caught
        m.add("catch arithmetic", INT, List.of(INT, INT), (self, p) ->
            StatementDef.doTry(p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(1)).returning())
                .doCatch(ArithmeticException.class, e -> constant(-1).returning()));
        // catch order: first matching
        m.add("catch first matching", STRING, List.of(INT, INT), (self, p) ->
            StatementDef.doTry(p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(1)).cast(TypeDef.OBJECT).invoke("toString", TypeDef.STRING).returning())
                .doCatch(ArithmeticException.class, e -> constant("arith").returning())
                .doCatch(RuntimeException.class, e -> constant("runtime").returning()));
        // catch variable used
        m.add("catch variable message", STRING, List.of(), (self, p) ->
            StatementDef.doTry(ise.doThrow())
                .doCatch(IllegalStateException.class, e -> e.invoke("getMessage", TypeDef.STRING).returning()));
        // rethrow in catch, finally still runs and changes a field? use local counter via return in outer try
        m.add("nested try rethrow", STRING, List.of(), (self, p) ->
            StatementDef.doTry(
                StatementDef.doTry(ise.doThrow())
                    .doCatch(IllegalStateException.class, e -> ClassTypeDef.of(IllegalArgumentException.class).instantiate(constant("wrapped")).doThrow()))
                .doCatch(IllegalArgumentException.class, e -> e.invoke("getMessage", TypeDef.STRING).returning()));
        // try/catch whose catch completes normally then code after
        m.add("catch completes normally", STRING, List.of(INT), (self, p) ->
            constant("start").newLocal("s", s -> StatementDef.multi(
                StatementDef.doTry(
                    ((VariableDef.Local) s).assign(constant(10).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(0)).cast(TypeDef.OBJECT).invoke("toString", TypeDef.STRING)))
                    .doCatch(ArithmeticException.class, e -> ((VariableDef.Local) s).assign(constant("caught")))
                    .doFinally(((VariableDef.Local) s).assign(s.stringConcat(constant("+f")))),
                s.returning())));
        // nested handlers of the same type: the inner one wins
        m.add("nested try same type inner wins", STRING, List.of(), (self, p) ->
            StatementDef.doTry(
                StatementDef.doTry(ise.doThrow())
                    .doCatch(IllegalStateException.class, e -> constant("inner").returning()))
                .doCatch(IllegalStateException.class, e -> constant("outer").returning()));
        m.add("nested try finally inside catch", STRING, List.of(), (self, p) ->
            constant("").newLocal("s", s -> StatementDef.multi(
                StatementDef.doTry(
                    StatementDef.doTry(ise.doThrow())
                        .doFinally(((VariableDef.Local) s).assign(s.stringConcat(constant("f")))))
                    .doCatch(IllegalStateException.class, e -> ((VariableDef.Local) s).assign(s.stringConcat(constant("c")))),
                s.returning())));
        // synchronized in a separate frame
        {
            String name = m.nextName();
            MethodDef boom = MethodDef.builder("boom").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID)
                .build((self, p) -> new StatementDef.Synchronized(self, ise.doThrow()));
            ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addMethod(boom)
                .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                    .build((self, p) -> StatementDef.multi(
                        StatementDef.doTry(self.invoke(boom))
                            .doCatch(IllegalStateException.class, e -> constant("x").newLocal("ignored", ig -> StatementDef.multi())),
                        ClassTypeDef.of(Thread.class).invokeStatic("holdsLock", TypeDef.Primitive.BOOLEAN, self).cast(TypeDef.OBJECT).invoke("toString", TypeDef.STRING).returning())));
            m.addClass("synchronized throw in callee releases", b, new Class<?>[0], List.<Object[]>of(new Object[0]));
        }
        // synchronized returning
        m.add("synchronized return", INT, List.of(INT), (self, p) -> new StatementDef.Synchronized(self, p.get(0).returning()));
        m.add("synchronized throw releases", STRING, List.of(), (self, p) ->
            StatementDef.multi(
                StatementDef.doTry(new StatementDef.Synchronized(self, ise.doThrow()))
                    .doCatch(IllegalStateException.class, e -> constant("x").newLocal("ignored", ig -> StatementDef.multi())),
                ClassTypeDef.of(Thread.class).invokeStatic("holdsLock", TypeDef.Primitive.BOOLEAN, self).cast(TypeDef.OBJECT).invoke("toString", TypeDef.STRING).returning()));
    }

    // ------------------------------------------------------------------ loops

    static void loops(DiffModels m) {
        // sum 0..n-1
        m.add("while sum", INT, List.of(INT), (self, p) ->
            constant(0).newLocal("i", i -> constant(0).newLocal("acc", acc -> StatementDef.multi(
                i.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, p.get(0)).whileLoop(StatementDef.multi(
                    ((VariableDef.Local) acc).assign(acc.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, i)),
                    ((VariableDef.Local) i).assign(i.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(1))))),
                acc.returning()))),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}, new Object[]{100}));
        // return from within the loop
        m.add("while return inside", INT, List.of(INT), (self, p) ->
            constant(0).newLocal("i", i -> StatementDef.multi(
                constant(true).isTrue().whileLoop(StatementDef.multi(
                    i.compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL, p.get(0)).doIf(i.returning()),
                    ((VariableDef.Local) i).assign(i.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(2))))),
                constant(-1).returning())),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}, new Object[]{8}));
        // long loop counter with int compare
        m.add("while long accumulator", LONG, List.of(INT), (self, p) ->
            constant(0).newLocal("i", i -> constant(1L).newLocal("acc", acc -> StatementDef.multi(
                i.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, p.get(0)).whileLoop(StatementDef.multi(
                    ((VariableDef.Local) acc).assign(acc.math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, constant(3L))),
                    ((VariableDef.Local) i).assign(i.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(1))))),
                acc.returning()))),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}, new Object[]{45}));
        // and/or short-circuit inside a loop condition
        m.add("short circuit and", BOOLEAN, List.of(INT), (self, p) ->
            p.get(0).compare(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO, constant(0))
                .and(constant(10).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(0)).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, constant(1)))
                .returning(),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}, new Object[]{20}));
        m.add("short circuit or", BOOLEAN, List.of(INT), (self, p) ->
            p.get(0).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, constant(0))
                .or(constant(10).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(0)).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, constant(1)))
                .returning(),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}, new Object[]{20}));
        m.add("ifElse expression lazy branches", INT, List.of(INT), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, constant(0)),
                constant(-1), constant(10).math(ExpressionDef.MathBinaryOperation.OpType.DIVISION, p.get(0)), TypeDef.Primitive.INT).returning(),
            List.<Object[]>of(new Object[]{0}, new Object[]{5}));
        m.add("if without else then return", STRING, List.of(BOOLEAN_W), (self, p) ->
            StatementDef.multi(p.get(0).isTrue().doIf(constant("yes").returning()), constant("no").returning()));
        m.add("isFalse on Boolean", BOOLEAN, List.of(BOOLEAN_W), (self, p) -> p.get(0).isFalse().returning());
    }

    // ------------------------------------------------------------------ fields

    static void fields(DiffModels m) {
        for (Ty t : List.of(INT, LONG, CHAR, BYTE, SHORT, DOUBLE, FLOAT, BOOLEAN, INTEGER, STRING, OBJECT, INT_ARRAY)) {
            // Uninitialized instance field: the JVM default
            {
                String name = m.nextName();
                FieldDef field = FieldDef.builder("f", t.def()).addModifiers(Modifier.PRIVATE).build();
                ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addField(field)
                    .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                        .build((self, p) -> self.field(field).cast(TypeDef.OBJECT).returning()));
                m.addClass("field default " + t, b, new Class<?>[0], List.<Object[]>of(new Object[0]));
            }
            // Static field with an initializer, read back
            ExpressionDef[] cs = DiffModels.constants(t);
            if (cs.length > 1) {
                String name = m.nextName();
                FieldDef field = FieldDef.builder("S", t.def()).addModifiers(Modifier.PRIVATE, Modifier.STATIC).initializer(cs[1]).build();
                ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addField(field)
                    .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                        .build((self, p) -> ClassTypeDef.of(name).getStaticField(field).cast(TypeDef.OBJECT).returning()));
                m.addClass("static field initializer " + t, b, new Class<?>[0], List.<Object[]>of(new Object[0]));
            }
            // Put then get through an instance field
            {
                String name = m.nextName();
                FieldDef field = FieldDef.builder("f", t.def()).addModifiers(Modifier.PRIVATE).build();
                ClassDef.ClassDefBuilder b = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addField(field)
                    .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).addParameter("v", t.def()).returns(TypeDef.OBJECT)
                        .build((self, p) -> StatementDef.multi(self.field(field).put(p.get(0)), self.field(field).cast(TypeDef.OBJECT).returning())));
                List<Object[]> in = new ArrayList<>();
                for (Object s : t.samples()) {
                    in.add(new Object[]{s});
                }
                m.addClass("field put get " + t, b, new Class<?>[]{t.cls()}, in);
            }
        }
    }

    // ------------------------------------------------------------------ lambdas and references

    static void lambdas(DiffModels m) {
        ClassTypeDef fn = TypeDef.parameterized(Function.class, Integer.class, Integer.class);
        m.add("lambda Function capture param", INTEGER, List.of(INT, INT), (self, p) ->
            fn.getLambda().implement((ls, lp) -> lp.get(0).cast(TypeDef.Primitive.INT).math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, p.get(0)).cast(TypeDef.of(Integer.class)).returning())
                .invoke("apply", TypeDef.OBJECT, p.get(1).cast(TypeDef.of(Integer.class))).cast(TypeDef.of(Integer.class)).returning());
        ClassTypeDef supplier = TypeDef.parameterized(Supplier.class, String.class);
        m.add("lambda Supplier capture local", STRING, List.of(STRING), (self, p) ->
            p.get(0).stringConcat(constant("!")).newLocal("loc", loc ->
                supplier.getLambda().implement((ls, lp) -> loc.stringConcat(loc).returning())
                    .invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning()));
        ClassTypeDef op = ClassTypeDef.of(IntBinaryOperator.class);
        m.add("lambda IntBinaryOperator", INT, List.of(INT, INT), (self, p) ->
            op.getLambda().implement((ls, lp) -> lp.get(0).math(ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, lp.get(1)).returning())
                .invoke("applyAsInt", TypeDef.Primitive.INT, p.get(0), p.get(1)).returning());
        ClassTypeDef strLen = TypeDef.parameterized(Function.class, String.class, Integer.class);
        m.add("method reference String::length", INTEGER, List.of(STRING), (self, p) ->
            strLen.methodReference(null, "length")
                .invoke("apply", TypeDef.OBJECT, p.get(0)).cast(TypeDef.of(Integer.class)).returning());
        m.add("lambda returning primitive boxed into Object function", OBJECT, List.of(INT), (self, p) ->
            TypeDef.parameterized(Function.class, Integer.class, Object.class).getLambda()
                .implement((ls, lp) -> lp.get(0).cast(TypeDef.Primitive.INT).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(1)).cast(TypeDef.OBJECT).returning())
                .invoke("apply", TypeDef.OBJECT, p.get(0).cast(TypeDef.of(Integer.class))).returning());
    }

    // ------------------------------------------------------------------ invocations by name

    static void invocations(DiffModels m) {
        ClassTypeDef math = ClassTypeDef.of(Math.class);
        ClassTypeDef string = ClassTypeDef.of(String.class);
        ClassTypeDef integer = ClassTypeDef.of(Integer.class);
        ClassTypeDef longType = ClassTypeDef.of(Long.class);
        ClassTypeDef objects = ClassTypeDef.of(Objects.class);
        ClassTypeDef arrays = ClassTypeDef.of(Arrays.class);
        ClassTypeDef character = ClassTypeDef.of(Character.class);
        m.add("Math.max(int, long) by name", OBJECT, List.of(INT, LONG), (self, p) -> math.invokeStatic("max", TypeDef.Primitive.LONG, p.get(0), p.get(1)).cast(TypeDef.OBJECT).returning());
        m.add("Math.max(int, int) by name as long", LONG, List.of(INT, INT), (self, p) -> math.invokeStatic("max", TypeDef.Primitive.LONG, p.get(0), p.get(1)).returning());
        m.add("Math.abs(char) by name", OBJECT, List.of(CHAR), (self, p) -> math.invokeStatic("abs", TypeDef.Primitive.INT, p.get(0)).cast(TypeDef.OBJECT).returning());
        m.add("Math.abs(byte) by name", OBJECT, List.of(BYTE), (self, p) -> math.invokeStatic("abs", TypeDef.Primitive.INT, p.get(0)).cast(TypeDef.OBJECT).returning());
        m.add("Integer.valueOf(char) by name", OBJECT, List.of(CHAR), (self, p) -> integer.invokeStatic("valueOf", TypeDef.of(Integer.class), p.get(0)).cast(TypeDef.OBJECT).returning());
        m.add("Long.valueOf(int) by name", OBJECT, List.of(INT), (self, p) -> longType.invokeStatic("valueOf", TypeDef.of(Long.class), p.get(0)).cast(TypeDef.OBJECT).returning());
        m.add("String.valueOf(char) by name", STRING, List.of(CHAR), (self, p) -> string.invokeStatic("valueOf", TypeDef.STRING, p.get(0)).returning());
        m.add("String.valueOf(char[]) by name", STRING, List.of(CHAR_ARRAY), (self, p) -> string.invokeStatic("valueOf", TypeDef.STRING, p.get(0)).returning());
        m.add("String.valueOf(byte) by name", STRING, List.of(BYTE), (self, p) -> string.invokeStatic("valueOf", TypeDef.STRING, p.get(0)).returning());
        m.add("String.valueOf(Object) null", STRING, List.of(), (self, p) -> string.invokeStatic("valueOf", TypeDef.STRING, ExpressionDef.nullValue()).returning());
        m.add("Objects.equals(int, long) by name", BOOLEAN, List.of(INT, LONG), (self, p) -> objects.invokeStatic("equals", TypeDef.Primitive.BOOLEAN, p.get(0), p.get(1)).returning());
        m.add("Objects.hashCode(int) by name", INT, List.of(INT), (self, p) -> objects.invokeStatic("hashCode", TypeDef.Primitive.INT, p.get(0)).returning());
        m.add("Arrays.asList(int[]) size", INT, List.of(INT_ARRAY), (self, p) -> arrays.invokeStatic("asList", TypeDef.of(List.class), p.get(0)).invoke("size", TypeDef.Primitive.INT).returning());
        m.add("Arrays.asList(String[]) size", INT, List.of(STRING_ARRAY), (self, p) -> arrays.invokeStatic("asList", TypeDef.of(List.class), p.get(0)).invoke("size", TypeDef.Primitive.INT).returning());
        m.add("Character.isDigit(char)", BOOLEAN, List.of(CHAR), (self, p) -> character.invokeStatic("isDigit", TypeDef.Primitive.BOOLEAN, p.get(0)).returning());
        m.add("Character.toChars(int) length", INT, List.of(INT), (self, p) -> character.invokeStatic("toString", TypeDef.STRING, p.get(0)).invoke("length", TypeDef.Primitive.INT).returning(),
            List.<Object[]>of(new Object[]{65}, new Object[]{0x1F600}));
        m.add("String.indexOf(char) by name", INT, List.of(STRING, CHAR), (self, p) -> p.get(0).invoke("indexOf", TypeDef.Primitive.INT, p.get(1)).returning());
        m.add("String.indexOf(int) by name", INT, List.of(STRING, INT), (self, p) -> p.get(0).invoke("indexOf", TypeDef.Primitive.INT, p.get(1)).returning());
        m.add("StringBuilder append(char)", STRING, List.of(CHAR), (self, p) ->
            ClassTypeDef.of(StringBuilder.class).instantiate().invoke("append", TypeDef.of(StringBuilder.class), p.get(0)).invoke("toString", TypeDef.STRING).returning());
        m.add("StringBuilder append(short)", STRING, List.of(SHORT), (self, p) ->
            ClassTypeDef.of(StringBuilder.class).instantiate().invoke("append", TypeDef.of(StringBuilder.class), p.get(0)).invoke("toString", TypeDef.STRING).returning());
        m.add("StringBuilder append(char[])", STRING, List.of(CHAR_ARRAY), (self, p) ->
            ClassTypeDef.of(StringBuilder.class).instantiate().invoke("append", TypeDef.of(StringBuilder.class), p.get(0)).invoke("toString", TypeDef.STRING).returning());
        m.add("List.remove(int) by name", OBJECT, List.of(INT), (self, p) ->
            ClassTypeDef.of(ArrayList.class).instantiate(ClassTypeDef.of(Arrays.class).invokeStatic("asList", TypeDef.of(List.class),
                    new ExpressionDef.NewArrayInitialized(TypeDef.OBJECT.array(), List.of(constant("a"), constant("b"), constant("c")))))
                .newLocal("l", l -> StatementDef.multi(l.invoke("remove", TypeDef.OBJECT, p.get(0)), l.invoke("toString", TypeDef.STRING).returning())),
            List.<Object[]>of(new Object[]{1}));
        m.add("String.format varargs", STRING, List.of(INT, STRING), (self, p) ->
            string.invokeStatic("format", TypeDef.STRING, constant("%d-%s"),
                new ExpressionDef.NewArrayInitialized(TypeDef.OBJECT.array(), List.of(p.get(0), p.get(1)))).returning());
        m.add("String.join varargs", STRING, List.of(STRING, STRING), (self, p) ->
            string.invokeStatic("join", TypeDef.STRING, constant(","),
                new ExpressionDef.NewArrayInitialized(TypeDef.of(CharSequence.class).array(), List.of(p.get(0), p.get(1)))).returning());
    }

    // ------------------------------------------------------------------ receivers that need parentheses

    static void receivers(DiffModels m) {
        for (Ty t : List.of(INT, LONG, DOUBLE, FLOAT, BYTE, SHORT, CHAR, STRING)) {
            ExpressionDef[] cs = DiffModels.constants(t);
            for (int i = 0; i < cs.length; i++) {
                ExpressionDef c = cs[i];
                m.add("boxed receiver constant " + t + "#" + i + " toString", STRING, List.of(),
                    (self, p) -> c.cast(TypeDef.OBJECT).invoke("toString", TypeDef.STRING).returning());
                m.add("constant " + t + "#" + i + " equalsStructurally itself", BOOLEAN, List.of(),
                    (self, p) -> c.equalsStructurally(c).returning());
            }
        }
        for (ExpressionDef c : DiffModels.constants(STRING)) {
            m.add("string constant receiver toUpperCase " + ((ExpressionDef.Constant) c).value(), STRING, List.of(),
                (self, p) -> c.invoke("toUpperCase", TypeDef.STRING).returning());
            m.add("string constant receiver length " + ((ExpressionDef.Constant) c).value(), INT, List.of(),
                (self, p) -> c.invoke("length", TypeDef.Primitive.INT).returning());
        }
        m.add("math result receiver", STRING, List.of(INT, INT), (self, p) ->
            p.get(0).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p.get(1)).cast(TypeDef.of(Integer.class)).invoke("toString", TypeDef.STRING).returning());
        m.add("negated param receiver", STRING, List.of(INT), (self, p) ->
            p.get(0).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).cast(TypeDef.of(Integer.class)).invoke("toString", TypeDef.STRING).returning());
        m.add("conditional receiver", INT, List.of(BOOLEAN, STRING, STRING), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.STRING).invoke("length", TypeDef.Primitive.INT).returning());
        // a conditional as the left operand of every binary construct
        java.util.function.Function<List<VariableDef.MethodParameter>, ExpressionDef> cond = p ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), p.get(1), p.get(2), TypeDef.Primitive.INT);
        List<Object[]> condInputs = List.<Object[]>of(new Object[]{true, 1, 2}, new Object[]{false, 1, 2});
        m.add("conditional left of math", INT, List.of(BOOLEAN, INT, INT), (self, p) ->
            cond.apply(p).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(10)).returning(), condInputs);
        m.add("conditional right of math", INT, List.of(BOOLEAN, INT, INT), (self, p) ->
            constant(10).math(ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, cond.apply(p)).returning(), condInputs);
        m.add("conditional left of compare", BOOLEAN, List.of(BOOLEAN, INT, INT), (self, p) ->
            cond.apply(p).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, constant(2)).returning(), condInputs);
        m.add("conditional left of concat", STRING, List.of(BOOLEAN, INT, INT), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), constant("a"), constant("b"), TypeDef.STRING).stringConcat(constant("!")).returning(), condInputs);
        m.add("conditional left of instanceof", BOOLEAN, List.of(BOOLEAN, INT, INT), (self, p) ->
            cond.apply(p).cast(TypeDef.OBJECT).instanceOf(ClassTypeDef.of(Integer.class)).returning(), condInputs);
        m.add("conditional left of equalsStructurally", BOOLEAN, List.of(BOOLEAN, INT, INT), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), constant("a"), constant("b"), TypeDef.STRING).equalsStructurally(constant("b")).returning(), condInputs);
        m.add("conditional left of and", BOOLEAN, List.of(BOOLEAN, INT, INT), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), constant(false), constant(true), TypeDef.Primitive.BOOLEAN).isTrue()
                .and(p.get(1).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, constant(1))).returning(), condInputs);
        m.add("conditional negated", INT, List.of(BOOLEAN, INT, INT), (self, p) ->
            cond.apply(p).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning(), condInputs);
        m.add("conditional isNull", BOOLEAN, List.of(BOOLEAN, INT, INT), (self, p) ->
            new ExpressionDef.IfElse(p.get(0).isTrue(), constant("a"), ExpressionDef.nullValue(), TypeDef.STRING).isNull().returning(), condInputs);
        m.add("switch left of math", INT, List.of(BOOLEAN, INT, INT), (self, p) ->
            p.get(1).asExpressionSwitch(TypeDef.Primitive.INT, Map.of(constant(1), constant(5)), constant(6))
                .math(ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION, constant(10)).returning(), condInputs);
        m.add("concat receiver", INT, List.of(STRING, STRING), (self, p) ->
            p.get(0).stringConcat(p.get(1)).invoke("length", TypeDef.Primitive.INT).returning());
        m.add("cast receiver", INT, List.of(OBJECT), (self, p) ->
            p.get(0).cast(TypeDef.STRING).invoke("length", TypeDef.Primitive.INT).returning(),
            List.<Object[]>of(new Object[]{"abc"}));
        m.add("instanceof in and", BOOLEAN, List.of(OBJECT), (self, p) ->
            p.get(0).instanceOf(ClassTypeDef.of(String.class)).and(p.get(0).cast(TypeDef.STRING).invoke("isEmpty", TypeDef.Primitive.BOOLEAN).isFalse()).returning());
        Ty matrix = new Ty("int[][]", TypeDef.array(TypeDef.Primitive.INT, 2), int[][].class, List.of((Object) new int[][]{{1, 2}}));
        m.add("matrix row element", INT_ARRAY, List.of(matrix), (self, p) -> p.get(0).arrayElement(0).returning());
        m.add("matrix row element length", INT, List.of(matrix), (self, p) ->
            ClassTypeDef.of(java.lang.reflect.Array.class).invokeStatic("getLength", TypeDef.Primitive.INT, p.get(0).arrayElement(0).cast(TypeDef.OBJECT)).returning());
        m.add("new array element", INT, List.of(INT), (self, p) ->
            new ExpressionDef.NewArrayInitialized(TypeDef.Primitive.INT.array(), List.of(constant(4), p.get(0))).arrayElement(1).returning());
    }
}
