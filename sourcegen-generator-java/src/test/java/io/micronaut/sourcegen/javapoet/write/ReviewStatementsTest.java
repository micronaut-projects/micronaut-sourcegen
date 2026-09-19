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
package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType;
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.model.ExpressionDef.constant;
import static io.micronaut.sourcegen.model.StatementDef.doTry;
import static io.micronaut.sourcegen.model.StatementDef.multi;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Review probes for statements, control flow, naming scopes, fields and type declarations: models the bytecode
 * writer accepts, rendered as Java source that has to compile and behave as the bytecode would.
 *
 * @since 2.2.2
 */
public class ReviewStatementsTest {

    private static final ClassTypeDef ISE = ClassTypeDef.of(IllegalStateException.class);
    private static final ClassTypeDef IAE = ClassTypeDef.of(IllegalArgumentException.class);
    private static final Method INCREMENT = method(AtomicInteger.class, "incrementAndGet");
    private static final ClassTypeDef STRING_SUPPLIER = TypeDef.parameterized(Supplier.class, String.class);

    // ---------------------------------------------------------------------------------------------------------
    // Reachability: statements after one that cannot complete normally are dropped, all others must be kept
    // ---------------------------------------------------------------------------------------------------------

    /** A {@code while (true)} with a conditional return inside is terminal; the fallback after it is dropped. */
    @Test
    void whileTrueWithAConditionalReturnDropsTheFallback() throws Throwable {
        var def = program("test.LoopUntil", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                ExpressionDef.trueValue().isTrue().whileLoop(
                    increment(p.get(0)).compare(OpType.GREATER_THAN, constant(3)).doIf(constant("done").returning())),
                constant("unreachable").returning())));
        var counter = new AtomicInteger();
        assertEquals("done", call(def, counter));
        assertEquals(4, counter.get());
    }

    /**
     * Confirmed defect (pre-existing): a loop over a constant false comparison, {@code while (1 > 2)}, is valid
     * bytecode, but javac reports its body as an unreachable statement.
     */
    @Test
    void constantFalseLoopConditionKeepsTheBodyCompilable() throws Throwable {
        var def = program("test.NeverLoop", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> multi(
                constant(1).compare(OpType.GREATER_THAN, constant(2)).whileLoop(constant("loop").returning()),
                constant("after").returning())));
        assertEquals("after", call(def));
    }

    /** A switch statement without a default can complete normally: the statement after it is kept. */
    @Test
    void fallbackAfterASwitchWithoutDefaultIsKept() throws Throwable {
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(constant(1), constant("one").returning());
        cases.put(constant(2), constant("two").returning());
        var def = program("test.OpenSwitch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", int.class).returns(String.class)
            .build((self, p) -> multi(p.get(0).asStatementSwitch(TypeDef.Primitive.INT, cases), constant("other").returning())));
        assertEquals("one", call(def, 1));
        assertEquals("two", call(def, 2));
        assertEquals("other", call(def, 3));
    }

    /** A switch whose default falls through keeps the statement after it. */
    @Test
    void fallbackAfterAPartiallyReturningSwitchIsKept() throws Throwable {
        var def = program("test.PartialSwitch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", int.class).addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                p.get(0).asStatementSwitch(TypeDef.Primitive.INT, Map.of(constant(1), constant("one").returning()), increment(p.get(1))),
                constant("fell").returning())));
        var counter = new AtomicInteger();
        assertEquals("one", call(def, 1, counter));
        assertEquals("fell", call(def, 5, counter));
        assertEquals(1, counter.get());
    }

    /** javac treats {@code if} specially: the statement after {@code if (true) return} is reachable and must stay. */
    @Test
    void fallbackAfterIfTrueReturnIsKept() throws Throwable {
        var def = program("test.IfTrue", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> multi(
                ExpressionDef.trueValue().isTrue().doIf(constant("a").returning()),
                constant("b").returning())));
        assertEquals("a", call(def));
    }

    /** A try that returns with a catch that completes normally can complete normally: the fallback is kept. */
    @Test
    void fallbackAfterATryReturningWithAnEmptyCatchIsKept() throws Throwable {
        var def = program("test.TryEmptyCatch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("fail", boolean.class).returns(String.class)
            .build((self, p) -> multi(
                doTry(multi(p.get(0).isTrue().doIf(ISE.instantiate().doThrow()), constant("tried").returning()))
                    .doCatch(IllegalStateException.class, e -> multi()),
                constant("recovered").returning())));
        assertEquals("tried", call(def, false));
        assertEquals("recovered", call(def, true));
    }

    /** A try that throws with every catch returning is terminal: the fallback is dropped. */
    @Test
    void tryAndEveryCatchReturningDropsTheFallback() throws Throwable {
        var def = program("test.TryCatchReturn", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> multi(
                doTry(ISE.instantiate(constant("x")).doThrow())
                    .doCatch(IllegalStateException.class, e -> e.invoke("getMessage", TypeDef.STRING).returning()),
                constant("unreachable").returning())));
        assertEquals("x", call(def));
    }

    /** A finally that throws ends the try whatever its body does. */
    @Test
    void finallyThatThrowsEndsTheBlock() throws Throwable {
        var def = program("test.ThrowingFinally", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                doTry(increment(p.get(0))).doFinally(ISE.instantiate(constant("final")).doThrow()),
                constant("unreachable").returning())));
        var counter = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> call(def, counter));
        assertEquals("final", failure.getMessage());
        assertEquals(1, counter.get());
    }

    /** A throw inside a nested block drops what follows it in the nested block and in the enclosing one. */
    @Test
    void throwInANestedBlockDropsWhatFollowsAtEveryLevel() throws Throwable {
        var def = program("test.NestedThrow", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                multi(increment(p.get(0)), ISE.instantiate(constant("inner")).doThrow(), increment(p.get(0))),
                increment(p.get(0)),
                constant("unreachable").returning())));
        var counter = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> call(def, counter));
        assertEquals(1, counter.get());
    }

    /** A return inside a lambda body does not make the statements after the lambda unreachable. */
    @Test
    void returnInsideALambdaBodyDoesNotEndTheEnclosingBody() throws Throwable {
        var def = program("test.LambdaReturn", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> STRING_SUPPLIER.getLambda()
                .implement((aThis, params) -> multi(increment(p.get(0)), constant("lambda").returning()))
                .newLocal("supplier", s -> s.invoke("get", TypeDef.OBJECT).stringConcat(constant("!")).returning())));
        var counter = new AtomicInteger();
        assertEquals("lambda!", call(def, counter));
        assertEquals(1, counter.get());
    }

    /** Empty and nested-empty blocks in every statement kind render as empty bodies. */
    @Test
    void emptyBlocksNestInEveryStatement() throws Throwable {
        var def = program("test.EmptyBlocks", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("flag", boolean.class).addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                p.get(0).isTrue().doIfElse(multi(), multi(multi())),
                increment(p.get(1)).compare(OpType.GREATER_THAN, constant(100)).whileLoop(multi()),
                doTry(multi()).doCatch(RuntimeException.class, e -> multi()).doFinally(multi()),
                new StatementDef.Synchronized(self, multi()),
                constant(1).asStatementSwitch(TypeDef.Primitive.INT, Map.of(constant(1), multi()), multi()),
                constant("ok").returning())));
        var counter = new AtomicInteger();
        assertEquals("ok", call(def, true, counter));
        assertEquals(1, counter.get());
    }

    /** A constant cast to its wrapper, {@code (Boolean) true}, is not a constant expression for javac. */
    @Test
    void boxedConstantLoopConditionAgreesWithJavac() throws Throwable {
        var def = program("test.BoxedLoop", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                constant(true).cast(Boolean.class).isTrue().whileLoop(
                    increment(p.get(0)).compare(OpType.GREATER_THAN, constant(2)).doIf(constant("loop").returning())),
                constant("after").returning())));
        var counter = new AtomicInteger();
        assertEquals("loop", call(def, counter));
        assertEquals(3, counter.get());
    }

    /** A statement switch with a default whose every branch returns is terminal, the fallback after it is dropped. */
    @Test
    void exhaustiveStatementSwitchDropsTheFallback() throws Throwable {
        var def = program("test.ExhaustiveSwitch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", int.class).returns(String.class)
            .build((self, p) -> multi(
                p.get(0).asStatementSwitch(TypeDef.Primitive.INT, Map.of(constant(1), constant("one").returning()), constant("other").returning()),
                ExpressionDef.nullValue().returning())));
        assertEquals("one", call(def, 1));
        assertEquals("other", call(def, 2));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Definite assignment of fields
    // ---------------------------------------------------------------------------------------------------------

    /** A blank final static assigned in a try whose catch rethrows is definitely assigned once: it keeps final. */
    @Test
    void blankFinalStaticAssignedInATryWithARethrowingCatchKeepsFinal() throws Throwable {
        var owner = ClassTypeDef.of("test.ParsedOnce");
        var field = FieldDef.builder("VALUE", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(doTry(owner.getStaticField(field)
                .put(ClassTypeDef.of(Integer.class).invokeStatic("parseInt", TypeDef.Primitive.INT, constant("42"))))
                .doCatch(NumberFormatException.class, e -> ISE.instantiate(List.of(TypeDef.of(Throwable.class)), e).doThrow()))
            .build();
        var source = render(def);
        assertTrue(source.contains("public static final int VALUE;"), source);
        try (var loader = JavaCompileAssertions.compileAndLoad(source)) {
            assertEquals(42, loader.loadClass(def.getName()).getField("VALUE").get(null));
        }
    }

    /** A lambda cannot assign a final field: one assigned in a lambda of the static initializer loses final. */
    @Test
    void blankFinalStaticAssignedInsideALambdaOfTheStaticInitializer() throws Throwable {
        var owner = ClassTypeDef.of("test.LambdaInitialized");
        var field = FieldDef.builder("VALUE", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var lambda = ClassTypeDef.of(Runnable.class).getLambda()
            .implement((aThis, params) -> owner.getStaticField(field).put(constant(7)));
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(lambda.newLocal("initializer", r -> r.invoke("run", TypeDef.VOID)))
            .build();
        var source = render(def);
        assertFalse(source.contains("final int VALUE"), source);
        try (var loader = JavaCompileAssertions.compileAndLoad(source)) {
            assertEquals(7, loader.loadClass(def.getName()).getField("VALUE").get(null));
        }
    }

    /** A blank final static assigned twice in sequence cannot stay final. */
    @Test
    void blankFinalStaticAssignedTwiceDropsFinal() throws Throwable {
        var owner = ClassTypeDef.of("test.AssignedTwice");
        var field = FieldDef.builder("VALUE", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(multi(owner.getStaticField(field).put(constant(1)), owner.getStaticField(field).put(constant(2))))
            .build();
        var source = render(def);
        assertFalse(source.contains("final int VALUE"), source);
        try (var loader = JavaCompileAssertions.compileAndLoad(source)) {
            assertEquals(2, loader.loadClass(def.getName()).getField("VALUE").get(null));
        }
    }

    /** A blank final static read through its qualified name before its assignment reads the default, as bytecode does. */
    @Test
    void blankFinalStaticReadBeforeItsAssignmentThroughItsQualifiedName() throws Throwable {
        var owner = ClassTypeDef.of("test.ReadEarly");
        var a = FieldDef.builder("A", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var b = FieldDef.builder("B", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(a).addField(b)
            .addStaticInitializer(multi(
                owner.getStaticField(b).put(owner.getStaticField(a).math(MathBinaryOperation.OpType.ADDITION, constant(1))),
                owner.getStaticField(a).put(constant(5))))
            .build();
        var source = render(def);
        assertTrue(source.contains("public static final int A;"), source);
        assertTrue(source.contains("public static final int B;"), source);
        try (var loader = JavaCompileAssertions.compileAndLoad(source)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(5, cls.getField("A").get(null));
            assertEquals(1, cls.getField("B").get(null));
        }
    }

    /** A blank final instance field assigned in every constructor compiles and keeps final. */
    @Test
    void finalInstanceFieldAssignedInEveryConstructor() throws Throwable {
        var field = FieldDef.builder("name", String.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var def = ClassDef.builder("test.EveryConstructor").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> self.field(field).put(constant("default"))))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("name", String.class)
                .build((self, p) -> self.field(field).put(p.get(0))))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.field(field).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("default", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            assertEquals("given", cls.getMethod("call").invoke(cls.getConstructor(String.class).newInstance("given")));
        }
    }

    /**
     * Confirmed defect (pre-existing): a final instance field the constructors do not definitely assign exactly
     * once - assigned in one constructor only, conditionally, or after an initializer - is valid bytecode (putfield
     * in an {@code <init>}), but javac rejects the field; only blank final static fields lose their {@code final}.
     */
    @TestFactory
    Stream<DynamicTest> finalInstanceFieldNotDefinitelyAssignedOnce() {
        return Stream.of("oneConstructorOnly", "conditional", "afterAnInitializer").map(kind -> dynamicTest(kind, () -> {
            var fieldBuilder = FieldDef.builder("name", String.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL);
            if (kind.equals("afterAnInitializer")) {
                fieldBuilder.initializer(constant("initial"));
            }
            var field = fieldBuilder.build();
            var builder = ClassDef.builder("test.Partial" + kind).addModifiers(Modifier.PUBLIC).addField(field)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                    .build((self, p) -> self.field(field).returning()));
            Object[] arguments;
            Class<?>[] parameterTypes;
            switch (kind) {
                case "oneConstructorOnly" -> {
                    builder.addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                        .build((self, p) -> self.field(field).put(constant("set"))));
                    builder.addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("ignored", int.class).build());
                    arguments = new Object[0];
                    parameterTypes = new Class<?>[0];
                }
                case "conditional" -> {
                    builder.addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class)
                        .build((self, p) -> p.get(0).isTrue().doIf(self.field(field).put(constant("set")))));
                    arguments = new Object[]{true};
                    parameterTypes = new Class<?>[]{boolean.class};
                }
                default -> {
                    builder.addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                        .build((self, p) -> self.field(field).put(constant("set"))));
                    arguments = new Object[0];
                    parameterTypes = new Class<?>[0];
                }
            }
            var def = builder.build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                assertEquals("set", cls.getMethod("call").invoke(cls.getConstructor(parameterTypes).newInstance(arguments)));
            }
        }));
    }

    /**
     * Confirmed defect (pre-existing, contrived): a {@code return} in a static initializer returns from
     * {@code <clinit>} in bytecode; in source it is a return outside a method.
     */
    @Test
    void returnInAStaticInitializer() throws Throwable {
        var owner = ClassTypeDef.of("test.EarlyExit");
        var field = FieldDef.builder("VALUE", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(multi(
                ClassTypeDef.of(Boolean.class).invokeStatic("getBoolean", TypeDef.Primitive.BOOLEAN, constant("review.skip")).isTrue()
                    .doIf(new StatementDef.Return(null)),
                owner.getStaticField(field).put(constant(1))))
            .build();
        try (var loader = compile(def)) {
            assertEquals(1, loader.loadClass(def.getName()).getField("VALUE").get(null));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Switch
    // ---------------------------------------------------------------------------------------------------------

    /** A string switch statement with an empty case and the default given in the middle of the cases. */
    @Test
    void switchOnStringWithAnEmptyCaseAndADefaultInTheMiddle() throws Throwable {
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(constant("a"), constant("A").returning());
        cases.put(ExpressionDef.nullValue(), constant("D").returning());
        cases.put(constant("b"), multi());
        var def = program("test.StringSwitch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class).returns(String.class)
            .build((self, p) -> multi(p.get(0).asStatementSwitch(TypeDef.STRING, cases, null), constant("fell").returning())));
        assertEquals("A", call(def, "a"));
        assertEquals("fell", call(def, "b"));
        assertEquals("D", call(def, "z"));
    }

    /** A switch expression used as a condition, as an argument and as the receiver of a call. */
    @TestFactory
    Stream<DynamicTest> switchExpressionAsAConditionAnArgumentAndAReceiver() {
        return Stream.of("condition", "argument", "receiver").map(kind -> dynamicTest(kind, () -> {
            var def = program("test.SwitchUse" + kind, MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", int.class).returns(String.class)
                .build((self, p) -> {
                    var words = p.get(0).asExpressionSwitch(TypeDef.STRING, Map.of(constant(1), constant("one")), constant("other"));
                    return switch (kind) {
                        case "condition" -> multi(
                            p.get(0).asExpressionSwitch(TypeDef.Primitive.BOOLEAN, Map.of(constant(1), ExpressionDef.trueValue()), ExpressionDef.falseValue())
                                .isTrue().doIf(constant("one").returning()),
                            constant("other").returning());
                        case "argument" -> ClassTypeDef.of(Objects.class).invokeStatic("toString", TypeDef.STRING, words).returning();
                        default -> ClassTypeDef.of(String.class).invokeStatic("valueOf", TypeDef.STRING, words.invoke("length", TypeDef.Primitive.INT)).returning();
                    };
                }));
            assertEquals(kind.equals("receiver") ? "3" : "one", call(def, 1));
            assertEquals(kind.equals("receiver") ? "5" : "other", call(def, 2));
        }));
    }

    /** A switch expression in the yield block of another switch expression. */
    @Test
    void nestedSwitchExpressions() throws Throwable {
        var def = program("test.NestedSwitches", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("outer", int.class).addParameter("inner", int.class).returns(String.class)
            .build((self, p) -> {
                var innerSwitch = p.get(1).asExpressionSwitch(TypeDef.STRING, Map.of(constant(1), constant("a")), constant("b"));
                Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                cases.put(constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
                    innerSwitch.newLocal("chosen", local -> local.stringConcat(constant("!")).returning())));
                return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("d")).returning();
            }));
        assertEquals("a!", call(def, 1, 1));
        assertEquals("b!", call(def, 1, 2));
        assertEquals("d", call(def, 2, 1));
    }

    /**
     * Confirmed defect: in bytecode every {@code Return} inside a {@code SwitchYieldCase} yields the value, wherever
     * it is nested; the source writes {@code return} for one nested in a try, a switch statement or a synchronized
     * block, which javac rejects inside a switch expression.
     */
    @TestFactory
    Stream<DynamicTest> switchYieldBlockReturningFromANestedStatement() {
        return Stream.of("try", "switch", "synchronized").map(kind -> dynamicTest(kind, () -> {
            var def = program("test.NestedYield" + kind, MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", int.class).addParameter("other", int.class).returns(String.class)
                .build((self, p) -> {
                    StatementDef nested = switch (kind) {
                        case "try" -> doTry(ISE.instantiate(constant("inner")).doThrow())
                            .doCatch(IllegalStateException.class, e -> e.invoke("getMessage", TypeDef.STRING).returning());
                        case "switch" -> p.get(1).asStatementSwitch(TypeDef.Primitive.INT, Map.of(constant(1), constant("inner").returning()));
                        default -> new StatementDef.Synchronized(self, constant("inner").returning());
                    };
                    Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                    cases.put(constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING, multi(nested, constant("outer").returning())));
                    return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("default")).returning();
                }));
            assertEquals("inner", call(def, 1, 1));
            assertEquals("default", call(def, 2, 1));
        }));
    }

    /**
     * Confirmed defect: a yield block that always throws is accepted by the bytecode writer; the generator insists
     * that the last statement of a {@code SwitchYieldCase} is a return and throws an {@code IllegalStateException}.
     */
    @Test
    void switchYieldBlockEndingInAThrow() throws Throwable {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING, constant("one").returning()));
        var def = program("test.ThrowingYield", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", int.class).returns(String.class)
            .build((self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING, cases,
                new ExpressionDef.SwitchYieldCase(TypeDef.STRING, ISE.instantiate(constant("unsupported")).doThrow())).returning()));
        assertEquals("one", call(def, 1));
        assertEquals("unsupported", assertThrows(IllegalStateException.class, () -> call(def, 2)).getMessage());
    }

    /** Two string cases with the same hash code ({@code "Aa"} and {@code "BB"}) are told apart. */
    @Test
    void stringSwitchWithCollidingHashCodes() throws Throwable {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(constant("Aa"), constant("first"));
        cases.put(constant("BB"), constant("second"));
        var def = program("test.HashCollision", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("none")).returning()));
        assertEquals("first", call(def, "Aa"));
        assertEquals("second", call(def, "BB"));
        assertEquals("none", call(def, "Ab"));
    }

    /** Enum constants in case labels are written qualified, which javac accepts since Java 21. */
    @Test
    void enumCaseLabelsAreWrittenQualified() throws Throwable {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(constant(DayOfWeek.SATURDAY), constant("weekend"));
        cases.put(constant(DayOfWeek.SUNDAY), constant("weekend"));
        var def = program("test.EnumSwitch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("day", DayOfWeek.class).returns(String.class)
            .build((self, p) -> p.get(0).asExpressionSwitch(TypeDef.STRING, cases, constant("weekday")).returning()));
        assertEquals("weekend", call(def, DayOfWeek.SUNDAY));
        assertEquals("weekday", call(def, DayOfWeek.MONDAY));
    }

    /**
     * Confirmed defect (pre-existing): a {@code char} constant is written as the character itself, {@code return x;},
     * not as the literal {@code 'x'}.
     */
    @Test
    void charConstantIsWrittenAsACharacterLiteral() throws Throwable {
        var def = program("test.CharConstant", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(char.class)
            .build((self, p) -> constant('x').returning()));
        assertEquals('x', call(def));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Try / catch
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Confirmed defect (pre-existing): catching a supertype before its subtype is a valid exception table whose
     * second handler is dead; javac reports the subtype as already caught.
     */
    @Test
    void catchOfASupertypeBeforeItsSubtype() throws Throwable {
        var def = program("test.CatchOrder", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> doTry(IAE.instantiate(constant("bad")).doThrow())
                .doCatch(RuntimeException.class, e -> constant("runtime").returning())
                .doCatch(IllegalArgumentException.class, e -> constant("argument").returning())));
        assertEquals("runtime", call(def));
    }

    /**
     * Confirmed defect (pre-existing): a catch of a checked exception the try body cannot throw is a dead handler in
     * bytecode; javac reports that the exception is never thrown in the body of the try.
     */
    @Test
    void catchOfACheckedExceptionTheBodyCannotThrow() throws Throwable {
        var def = program("test.DeadCatch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                doTry(increment(p.get(0))).doCatch(IOException.class, e -> constant("io").returning()),
                constant("ok").returning())));
        assertEquals("ok", call(def, new AtomicInteger()));
    }

    /** A local declared after the try can take the name the catch parameter was given. */
    @Test
    void catchVariableNameReusedByAFollowingLocal() throws Throwable {
        var def = program("test.CatchThenLocal", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(String.class)
            .build((self, p) -> multi(
                doTry(ISE.instantiate().doThrow()).doCatch(IllegalStateException.class, e -> increment(p.get(0))),
                constant("after").newLocal("e0", local -> local.returning()))));
        var counter = new AtomicInteger();
        assertEquals("after", call(def, counter));
        assertEquals(1, counter.get());
    }

    /** A try nested in a catch: the outer exception is read before the nested one shadows the exception variable. */
    @Test
    void outerExceptionReadBeforeANestedCatch() throws Throwable {
        var def = program("test.NestedCatch", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> doTry(ISE.instantiate(constant("outer")).doThrow())
                .doCatch(IllegalStateException.class, outer -> outer.invoke("getMessage", TypeDef.STRING).newLocal("message", message ->
                    doTry(IAE.instantiate(constant("inner")).doThrow())
                        .doCatch(IllegalArgumentException.class, inner -> message.stringConcat(constant(":"))
                            .stringConcat(inner.invoke("getMessage", TypeDef.STRING)).returning())))));
        assertEquals("outer:inner", call(def));
    }

    /** The exception variable is effectively final and can be captured by a lambda in the catch block. */
    @Test
    void exceptionVariableCapturedByALambdaInTheCatch() throws Throwable {
        var def = program("test.CapturedException", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
            .build((self, p) -> doTry(ISE.instantiate(constant("captured")).doThrow())
                .doCatch(IllegalStateException.class, e -> STRING_SUPPLIER.getLambda()
                    .implement((aThis, params) -> e.invoke("getMessage", TypeDef.STRING).returning())
                    .newLocal("message", s -> s.invoke("get", TypeDef.OBJECT).returning()))));
        assertEquals("captured", call(def));
    }

    /** {@code throw null} is valid Java and throws a NullPointerException like the bytecode does. */
    @Test
    void throwingNullThrowsANullPointerException() throws Throwable {
        var def = program("test.ThrowNull", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> ExpressionDef.nullValue().doThrow()));
        assertThrows(NullPointerException.class, () -> call(def));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Naming scopes
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Confirmed defect (pre-existing): a local named like a parameter of its method takes a slot of its own in
     * bytecode; javac reports the variable as already defined.
     */
    @Test
    void localNamedLikeAMethodParameter() throws Throwable {
        var def = program("test.ShadowedParameter", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class).returns(String.class)
            .build((self, p) -> constant("local").newLocal("value", local -> local.returning())));
        assertEquals("local", call(def, "parameter"));
    }

    /**
     * Confirmed defect: a lambda body is a method of its own in bytecode, so it can declare a local named like one
     * of the enclosing method; Java forbids a lambda body from redeclaring a local in scope, and only lambda
     * parameters are renamed.
     */
    @Test
    void lambdaBodyLocalNamedLikeAnEnclosingLocal() throws Throwable {
        var def = program("test.LambdaLocal", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> constant("outer").newLocal("text", outer -> STRING_SUPPLIER.getLambda()
                .implement((aThis, params) -> constant("inner").newLocal("text", inner -> inner.returning()))
                .newLocal("supplier", s -> outer.stringConcat(s.invoke("get", TypeDef.OBJECT)).returning()))));
        assertEquals("outerinner", call(def));
    }

    /** A local of an if block and one after the block can share a name. */
    @Test
    void siblingBlocksReuseALocalName() throws Throwable {
        var def = program("test.SiblingLocals", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("flag", boolean.class).returns(String.class)
            .build((self, p) -> multi(
                p.get(0).isTrue().doIf(constant("yes").newLocal("v", v -> v.returning())),
                constant("no").newLocal("v", v -> v.returning()))));
        assertEquals("yes", call(def, true));
        assertEquals("no", call(def, false));
    }

    /** A local may shadow a field; the field stays reachable through {@code this}. */
    @Test
    void localNamedLikeAField() throws Throwable {
        var field = FieldDef.builder("value", String.class).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.ShadowedField").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> constant("local").newLocal("value", local -> multi(
                    self.field(field).put(local.stringConcat(constant("!"))),
                    self.field(field).returning()))))
            .build();
        assertEquals("local!", call(def));
    }

    /** The helper names of an adapted bound reference ({@code target}, {@code arg}) avoid the locals in scope. */
    @Test
    void adaptedReferenceHelperNamesAvoidTheLocals() throws Throwable {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var concat = method(String.class, "concat", String.class);
        var def = program("test.HelperNames", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
            .build((self, p) -> constant("abc").newLocal("target", target -> constant("x").newLocal("arg", arg ->
                function.methodReference(target, concat).newLocal("reference", reference ->
                    reference.invoke("apply", TypeDef.OBJECT, arg).returning())))));
        assertEquals("abcx", call(def));
    }

    /** Contextual keywords are valid local names. */
    @Test
    void localsNamedLikeContextualKeywords() throws Throwable {
        var def = program("test.ContextualKeywords", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> constant("v").newLocal("var", v -> constant("r").newLocal("record", r ->
                constant("s").newLocal("sealed", s -> constant("p").newLocal("permits", pm -> constant("y").newLocal("yield", y ->
                    v.stringConcat(r).stringConcat(s).stringConcat(pm).stringConcat(y).returning()
                )))))));
        assertEquals("vrspy", call(def));
    }

    /**
     * Confirmed defect (pre-existing, contrived): a local named like a reserved word is a valid bytecode name
     * and an invalid Java identifier.
     */
    @Test
    void localNamedLikeAReservedWord() throws Throwable {
        var def = program("test.ReservedWord", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> constant("kw").newLocal("class", local -> local.returning())));
        assertEquals("kw", call(def));
    }

    /** Local names with {@code $} and non-ASCII letters. */
    @Test
    void dollarAndUnicodeLocalNames() throws Throwable {
        var def = program("test.UnicodeLocals", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> constant("a").newLocal("$välue$", a -> constant("b").newLocal("π", b -> a.stringConcat(b).returning()))));
        assertEquals("ab", call(def));
    }

    /** A local initialized with {@code null} is typed {@code Object}. */
    @Test
    void nullLocalIsTypedAsObject() throws Throwable {
        var def = program("test.NullLocal", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().newLocal("nothing", local -> local.returning())));
        assertNull(call(def));
    }

    /** Locals typed by {@code TypeDef.THIS} and {@code TypeDef.SUPER}. */
    @Test
    void localsOfTheSelfAndSuperType() throws Throwable {
        var def = program("test.SelfLocals", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(boolean.class)
            .build((self, p) -> self.newLocal("me", me -> new VariableDef.Local("parent", TypeDef.SUPER).defineAndAssign(self)
                .after(me.equalsReferentially(new VariableDef.Local("parent", TypeDef.SUPER)).returning()))));
        assertEquals(true, call(def));
    }

    /** A lambda parameter named like a local declared later in the enclosing block. */
    @Test
    void lambdaParameterNamedLikeALaterLocal() throws Throwable {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = program("test.LateLocal", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
            .build((self, p) -> function.getLambda().implement((aThis, params) -> params.get(0).stringConcat(constant("!")).returning())
                .newLocal("f", f -> constant("x").newLocal("v", v -> f.invoke("apply", TypeDef.OBJECT, v).returning()))));
        assertEquals("x!", call(def));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fields and type declarations
    // ---------------------------------------------------------------------------------------------------------

    /** Static, final, volatile and transient fields whose initializers read other fields and {@code this}. */
    @Test
    void fieldsOfEveryKindWithInitializersReadingOtherFields() throws Throwable {
        var owner = ClassTypeDef.of("test.FieldKinds");
        var a = FieldDef.builder("A", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer(constant(1)).build();
        var b = FieldDef.builder("B", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .initializer(owner.getStaticField(a).math(MathBinaryOperation.OpType.ADDITION, constant(1))).build();
        var c = FieldDef.builder("c", int.class).addModifiers(Modifier.PRIVATE, Modifier.VOLATILE).initializer(constant(3)).build();
        var d = FieldDef.builder("d", String.class).addModifiers(Modifier.PRIVATE, Modifier.TRANSIENT).initializer(constant("d")).build();
        var e = FieldDef.builder("e", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(new VariableDef.This().field(c).math(MathBinaryOperation.OpType.ADDITION, owner.getStaticField(b))).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(a).addField(b).addField(c).addField(d).addField(e)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> constant("").stringConcat(owner.getStaticField(a)).stringConcat(owner.getStaticField(b))
                    .stringConcat(self.field(c)).stringConcat(self.field(d)).stringConcat(self.field(e)).returning()))
            .build();
        assertEquals("123d5", call(def));
    }

    /** A static field cannot be of the class's type variable: it is written as the variable's bound. */
    @Test
    void staticFieldOfATypeVariableTypeIsWrittenAsItsBound() throws Throwable {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var def = ClassDef.builder("test.VariableFields").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addField(FieldDef.builder("shared", t).addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .addField(FieldDef.builder("own", t).addModifiers(Modifier.PUBLIC).build())
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(Number.class, cls.getField("shared").getGenericType());
            assertEquals("T", cls.getField("own").getGenericType().getTypeName());
        }
    }

    /** An F-bounded class variable, {@code <T extends Comparable<T>>}, is declared with its recursive bound. */
    @Test
    void fBoundedClassTypeVariable() throws Throwable {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, TypeDef.variable("T")));
        var compareTo = method(Comparable.class, "compareTo", Object.class);
        var def = ClassDef.builder("test.Bounded").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("a", t).addParameter("b", t).returns(t)
                .build((self, p) -> p.get(0).invoke(compareTo, p.get(1)).compare(OpType.GREATER_THAN_OR_EQUAL, constant(0))
                    .doIfElse(p.get(0).returning(), p.get(1).returning())))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("java.lang.Comparable<T>", cls.getTypeParameters()[0].getBounds()[0].getTypeName());
            assertEquals("b", cls.getMethod("call", Comparable.class, Comparable.class).invoke(cls.getConstructor().newInstance(), "a", "b"));
        }
    }

    /** A method variable bounded by a parameterization of another of the method's variables. */
    @Test
    void methodTypeVariableBoundedByAParameterizationOfAnother() throws Throwable {
        var a = TypeDef.variable("A");
        var b = TypeDef.variable("B", TypeDef.parameterized(List.class, a));
        var size = method(List.class, "size");
        var def = program("test.ChainedBounds", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(a).addTypeVariable(b).addParameter("list", b).returns(int.class)
            .build((self, p) -> p.get(0).invoke(size).returning()));
        assertEquals(2, call(def, List.of("x", "y")));
    }

    /** A field and a local of a nested generic array type, {@code List<String>[][]}, created raw. */
    @Test
    void nestedGenericArrayFieldAndLocal() throws Throwable {
        var grid = TypeDef.array(TypeDef.parameterized(List.class, String.class), 2);
        var field = FieldDef.builder("grid", grid).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.Grid").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> grid.instantiate(2).newLocal("local", local -> multi(
                    self.field(field).put(local),
                    self.field(field).arrayElement(0).ifNull(constant(2), constant(-1)).returning()))))
            .build();
        assertEquals(2, call(def));
    }

    /**
     * Confirmed defect (pre-existing): an enum annotation member whose constant has a body is written through
     * {@code value.getClass()}, the anonymous class of the constant, which JavaPoet cannot name; the bytecode
     * writer uses {@code getDeclaringClass()}.
     */
    @Test
    void annotationEnumMemberWithAConstantBody() throws Throwable {
        var def = ClassDef.builder("test.ShapedClass").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Shaped.class).addMember("value", Shape.ROUND).build())
            .build();
        try (var loader = compile(def)) {
            assertSame(Shape.ROUND, loader.loadClass(def.getName()).getAnnotation(Shaped.class).value());
        }
    }

    /**
     * Confirmed defect (pre-existing): a class annotation member given as a {@code ClassTypeDef} is written by its
     * simple name, {@code List.class}, without an import.
     */
    @Test
    void annotationClassMemberGivenAsAClassTypeDef() throws Throwable {
        var def = ClassDef.builder("test.TypedClass").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Typed.class).addMember("value", ClassTypeDef.of(List.class)).build())
            .build();
        try (var loader = compile(def)) {
            assertSame(List.class, loader.loadClass(def.getName()).getAnnotation(Typed.class).value());
        }
    }

    /** A record with an instance method reading its component and a static factory. */
    @Test
    void recordWithStaticAndInstanceMethods() throws Throwable {
        var type = ClassTypeDef.of("test.Named");
        var def = RecordDef.builder(type.getName()).addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String.class).build())
            .addMethod(MethodDef.builder("shout").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.field("name", TypeDef.STRING).invoke("toUpperCase", TypeDef.STRING).returning()))
            .addMethod(MethodDef.builder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("name", String.class).returns(type)
                .build((self, p) -> type.instantiate(p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var named = cls.getMethod("of", String.class).invoke(null, "bob");
            assertEquals("BOB", cls.getMethod("shout").invoke(named));
        }
    }

    /** An interface with a private method called from a default method, and a static method. */
    @Test
    void interfaceWithStaticDefaultAndPrivateMethods() throws Throwable {
        var prefix = MethodDef.builder("prefix").addModifiers(Modifier.PRIVATE).returns(String.class)
            .build((self, p) -> constant("Hi ").returning());
        var greeter = InterfaceDef.builder("test.Greeter").addModifiers(Modifier.PUBLIC)
            .addMethod(prefix)
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).addParameter("name", String.class).returns(String.class)
                .build((self, p) -> self.invoke(prefix).stringConcat(p.get(0)).returning()))
            .addMethod(MethodDef.builder("shout").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("text", String.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke("toUpperCase", TypeDef.STRING).returning()))
            .build();
        var impl = ClassDef.builder("test.GreeterImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(greeter.asTypeDef()).build();
        try (var loader = compile(greeter, impl)) {
            var greeterClass = loader.loadClass(greeter.getName());
            var instance = loader.loadClass(impl.getName()).getConstructor().newInstance();
            assertEquals("Hi Bob", greeterClass.getMethod("greet", String.class).invoke(instance, "Bob"));
            assertEquals("LOUD", greeterClass.getMethod("shout", String.class).invoke(null, "loud"));
        }
    }

    /** A static nested class and an inner class instantiated from the outer class. */
    @Test
    void innerClassesAreInstantiatedFromTheOuter() throws Throwable {
        var nested = ClassDef.builder("Nested").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("tag").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("N").returning())).build();
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("tag").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("I").returning())).build();
        var def = ClassDef.builder("test.Outer").addModifiers(Modifier.PUBLIC).addInnerType(nested).addInnerType(inner)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ClassTypeDef.of("test.Outer$Nested", true).instantiate().invoke("tag", TypeDef.STRING)
                    .stringConcat(ClassTypeDef.of("test.Outer$Inner", true).instantiate().invoke("tag", TypeDef.STRING)).returning()))
            .build();
        assertEquals("NI", call(def));
    }

    /** A class named like a {@code java.lang} type must qualify its references to the {@code java.lang} one. */
    @Test
    void classNamedLikeAJavaLangType() throws Throwable {
        var field = FieldDef.builder("text", String.class).addModifiers(Modifier.PRIVATE).initializer(constant("ok")).build();
        var def = ClassDef.builder("test.String").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.field(field).invoke("trim", TypeDef.STRING).returning()))
            .build();
        assertEquals("ok", call(def));
    }

    /** Two generated types of the same simple name from two packages referenced by a third. */
    @Test
    void conflictingSimpleNamesFromTwoPackages() throws Throwable {
        var first = ClassDef.builder("test.a.Thing").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("a").returning())).build();
        var second = ClassDef.builder("test.b.Thing").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("b").returning())).build();
        var fieldA = FieldDef.builder("first", first.asTypeDef()).addModifiers(Modifier.PRIVATE).initializer(first.asTypeDef().instantiate()).build();
        var fieldB = FieldDef.builder("second", second.asTypeDef()).addModifiers(Modifier.PRIVATE).initializer(second.asTypeDef().instantiate()).build();
        var def = ClassDef.builder("test.Things").addModifiers(Modifier.PUBLIC).addField(fieldA).addField(fieldB)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.field(fieldA).invoke("name", TypeDef.STRING).stringConcat(self.field(fieldB).invoke("name", TypeDef.STRING)).returning()))
            .build();
        try (var loader = compile(first, second, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("ab", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    /** A nested class extending another nested class of the same file, calling it through {@code super}. */
    @Test
    void classExtendingAGeneratedNestedClassOfTheSameFile() throws Throwable {
        var base = ClassDef.builder("Base").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("base").returning())).build();
        var derived = ClassDef.builder("Derived").addModifiers(Modifier.PUBLIC, Modifier.STATIC).superclass(ClassTypeDef.of("test.Family$Base", true))
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
                .build((self, p) -> constant("derived").stringConcat(self.superRef().invoke("name", TypeDef.STRING)).returning())).build();
        var def = ClassDef.builder("test.Family").addModifiers(Modifier.PUBLIC).addInnerType(base).addInnerType(derived)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ClassTypeDef.of("test.Family$Derived", true).instantiate().invoke("name", TypeDef.STRING).returning()))
            .build();
        assertEquals("derivedbase", call(def));
    }

    /** An abstract class with an abstract and a synchronized method, implemented by a nested subclass. */
    @Test
    void abstractClassWithASynchronizedMethodImplementedByANestedSubclass() throws Throwable {
        var value = MethodDef.builder("value").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(int.class).build();
        var impl = ClassDef.builder("Impl").addModifiers(Modifier.PUBLIC, Modifier.STATIC).superclass(ClassTypeDef.of("test.Abstract"))
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC).overrides().returns(int.class).build((self, p) -> constant(21).returning()))
            .build();
        var def = ClassDef.builder("test.Abstract").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addMethod(value)
            .addMethod(MethodDef.builder("twice").addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED).returns(int.class)
                .build((self, p) -> self.invoke(value).math(MathBinaryOperation.OpType.MULTIPLICATION, constant(2)).returning()))
            .addInnerType(impl)
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass("test.Abstract$Impl");
            assertEquals(42, cls.getMethod("twice").invoke(cls.getConstructor().newInstance()));
        }
    }

    /** Nested while loops with counters, returning from the inner loop. */
    @Test
    void nestedWhileLoopsReturnFromTheInnerLoop() throws Throwable {
        var def = program("test.NestedLoops", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
            .build((self, p) -> constant(0).newLocal("i", i -> multi(
                i.compare(OpType.LESS_THAN, constant(3)).whileLoop(constant(0).newLocal("j", j -> multi(
                    j.compare(OpType.LESS_THAN, constant(3)).whileLoop(multi(
                        i.math(MathBinaryOperation.OpType.MULTIPLICATION, constant(3)).math(MathBinaryOperation.OpType.ADDITION, j)
                            .compare(OpType.EQUAL_TO, constant(4))
                            .doIf(i.math(MathBinaryOperation.OpType.MULTIPLICATION, constant(10)).math(MathBinaryOperation.OpType.ADDITION, j).returning()),
                        j.assign(j.math(MathBinaryOperation.OpType.ADDITION, constant(1))))),
                    i.assign(i.math(MathBinaryOperation.OpType.ADDITION, constant(1)))))),
                constant(-1).returning()))));
        assertEquals(11, call(def));
    }

    /** A {@code Boolean} condition is unboxed by the {@code if}. */
    @Test
    void booleanObjectConditionUnboxes() throws Throwable {
        var def = program("test.BoxedCondition", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("flag", Boolean.class).returns(String.class)
            .build((self, p) -> multi(p.get(0).isTrue().doIf(constant("yes").returning()), constant("no").returning())));
        assertEquals("yes", call(def, Boolean.TRUE));
        assertEquals("no", call(def, Boolean.FALSE));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Second batch
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Confirmed defect (pre-existing, model ambiguity): an inner type without {@code static} is written as a Java
     * inner class, which needs an outer instance; the bytecode writer gives it none, so the model instantiates it
     * from a static method.
     */
    @Test
    void innerClassInstantiatedFromAStaticMethodOfTheOuter() throws Throwable {
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("tag").addModifiers(Modifier.PUBLIC).returns(String.class).build((self, p) -> constant("I").returning())).build();
        var def = ClassDef.builder("test.Host").addModifiers(Modifier.PUBLIC).addInnerType(inner)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(String.class)
                .build((self, p) -> ClassTypeDef.of("test.Host$Inner", true).instantiate().invoke("tag", TypeDef.STRING).returning()))
            .build();
        assertEquals("I", call(def));
    }

    /**
     * Confirmed defect (pre-existing): javadoc is passed to JavaPoet as a format string, so a {@code $} in it is
     * read as a placeholder.
     */
    @Test
    void javadocContainingADollarSign() throws Throwable {
        var def = ClassDef.builder("test.Priced").addModifiers(Modifier.PUBLIC).addJavadoc("Costs $5, not $L.")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class).addJavadoc("Returns $s.")
                .build((self, p) -> constant("priced").returning()))
            .build();
        var source = render(def);
        assertTrue(source.contains("Costs $5, not $L."), source);
        assertEquals("priced", call(def));
    }

    /** A class literal as the monitor of a synchronized block. */
    @Test
    void synchronizedOnAClassLiteral() throws Throwable {
        var def = program("test.ClassMonitor", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> new StatementDef.Synchronized(constant(TypeDef.of(String.class)), constant("locked").returning())));
        assertEquals("locked", call(def));
    }

    /** A type-use annotation on a parameter type and a repeated annotation on the class. */
    @Test
    void typeUseAndRepeatedAnnotations() throws Throwable {
        var marked = ClassTypeDef.of(String.class).annotated(AnnotationDef.builder(Marked.class).build());
        var def = ClassDef.builder("test.Annotated").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Tag.class).addMember("value", "a").build())
            .addAnnotation(AnnotationDef.builder(Tag.class).addMember("value", "b").build())
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("text", marked).returns(marked)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(2, cls.getAnnotationsByType(Tag.class).length);
            var method = cls.getMethod("call", String.class);
            assertTrue(method.getAnnotatedParameterTypes()[0].isAnnotationPresent(Marked.class));
            assertTrue(method.getAnnotatedReturnType().isAnnotationPresent(Marked.class));
        }
    }

    /** A method declaring and throwing its own type variable, {@code <E extends Exception> void call(E) throws E}. */
    @Test
    void methodThrowingItsTypeVariable() throws Throwable {
        var e = TypeDef.variable("E", TypeDef.of(Exception.class));
        var def = program("test.Thrower", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(e)
            .addParameter("failure", e).addThrows(e).returns(void.class)
            .build((self, p) -> p.get(0).doThrow()));
        var thrown = assertThrows(IOException.class, () -> call(def, new IOException("checked")));
        assertEquals("checked", thrown.getMessage());
    }

    /** A field of an unbounded wildcard type. */
    @Test
    void unboundedWildcardField() throws Throwable {
        var any = TypeDef.parameterized(List.class, TypeDef.wildcard());
        var field = FieldDef.builder("any", any).addModifiers(Modifier.PRIVATE)
            .initializer(ClassTypeDef.of(List.class).invokeStatic("of", any)).build();
        var def = ClassDef.builder("test.Wild").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> self.field(field).invoke(method(List.class, "size")).returning()))
            .build();
        assertEquals(0, call(def));
    }

    /** The catch parameter may take the name of a local of the try block, whose scope has ended. */
    @Test
    void catchParameterMayReuseATryBlockLocalName() throws Throwable {
        var def = program("test.TryLocal", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> doTry(constant("inside").newLocal("e0", local -> ISE.instantiate(local).doThrow()))
                .doCatch(IllegalStateException.class, e -> e.invoke("getMessage", TypeDef.STRING).returning())));
        assertEquals("inside", call(def));
    }

    /** A lambda parameter named like the catch parameter enclosing it is renamed. */
    @Test
    void lambdaParameterNamedLikeTheEnclosingCatchParameter() throws Throwable {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = program("test.CatchLambda", MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
            .build((self, p) -> doTry(ISE.instantiate(constant("m")).doThrow())
                .doCatch(IllegalStateException.class, e -> function.getLambda()
                    .implement(List.of("e0"), (aThis, params) -> params.get(0).stringConcat(e.invoke("getMessage", TypeDef.STRING)).returning())
                    .newLocal("f", f -> f.invoke("apply", TypeDef.OBJECT, constant("x")).returning()))));
        assertEquals("xm", call(def));
    }

    /** The cases of a switch statement can each declare a local of the same name. */
    @Test
    void switchCasesReuseALocalName() throws Throwable {
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(constant(1), constant("one").newLocal("v", v -> v.returning()));
        cases.put(constant(2), constant("two").newLocal("v", v -> v.returning()));
        var def = program("test.CaseLocals", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", int.class).returns(String.class)
            .build((self, p) -> p.get(0).asStatementSwitch(TypeDef.Primitive.INT, cases, constant("other").newLocal("v", v -> v.returning()))));
        assertEquals("one", call(def, 1));
        assertEquals("other", call(def, 3));
    }

    /** Enum constant arguments reading a static field of another type. */
    @Test
    void enumConstantArgumentsReadAStaticFieldOfAnotherType() throws Throwable {
        var value = FieldDef.builder("value", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var def = io.micronaut.sourcegen.model.EnumDef.builder("test.Limits").addModifiers(Modifier.PUBLIC)
            .addEnumConstant("MAX", ClassTypeDef.of(Integer.class).getStaticField("MAX_VALUE", TypeDef.Primitive.INT))
            .addEnumConstant("ZERO", constant(0))
            .addField(value).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC).returns(int.class).build((self, p) -> self.field(value).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(Integer.MAX_VALUE, cls.getMethod("value").invoke(cls.getField("MAX").get(null)));
        }
    }

    /** A returned void call in tail position of a synchronized try with a finally runs once, then the finally. */
    @Test
    void voidReturnInTailPositionOfNestedBlocks() throws Throwable {
        var def = program("test.TailVoid", MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("counter", AtomicInteger.class).returns(void.class)
            .build((self, p) -> new StatementDef.Synchronized(self,
                doTry(new StatementDef.Return(p.get(0).invoke("set", TypeDef.VOID, constant(1)))).doFinally(increment(p.get(0))))));
        var counter = new AtomicInteger();
        assertNull(call(def, counter));
        assertEquals(2, counter.get());
    }

    /** A blank final static assigned in every branch of a switch statement with a default keeps final. */
    @Test
    void blankFinalStaticAssignedInEveryBranchOfASwitchKeepsFinal() throws Throwable {
        var owner = ClassTypeDef.of("test.SwitchInitialized");
        var field = FieldDef.builder("VALUE", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(ClassTypeDef.of(Integer.class).invokeStatic("parseInt", TypeDef.Primitive.INT, constant("1"))
                .asStatementSwitch(TypeDef.Primitive.INT, Map.of(constant(1), owner.getStaticField(field).put(constant(7))),
                    owner.getStaticField(field).put(constant(9))))
            .build();
        var source = render(def);
        assertTrue(source.contains("public static final int VALUE;"), source);
        try (var loader = JavaCompileAssertions.compileAndLoad(source)) {
            assertEquals(7, loader.loadClass(def.getName()).getField("VALUE").get(null));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private static ExpressionDef.InvokeInstanceMethod increment(ExpressionDef counter) {
        return counter.invoke(INCREMENT);
    }

    private static ClassDef program(String name, MethodDef method) {
        return ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addMethod(method).build();
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compiles the definition and invokes its {@code call} method on a new instance, unwrapping what it throws. */
    private static Object call(ObjectDef def, Object... args) throws Throwable {
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = Arrays.stream(cls.getMethods())
                .filter(m -> m.getName().equals("call") && m.getParameterCount() == args.length)
                .findFirst().orElseThrow();
            try {
                return method.invoke(cls.getConstructor().newInstance(), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws IOException {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            sources.add(render(definition));
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    private static String render(ObjectDef definition) throws IOException {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        return writer.toString();
    }

    /**
     * An enum with a constant body.
     *
     * @since 2.2.2
     */
    public enum Shape {
        ROUND {
            @Override
            public String toString() {
                return "round";
            }
        },
        SQUARE
    }

    /**
     * An annotation with an enum member.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Shaped {
        /**
         * @return The shape
         */
        Shape value();
    }

    /**
     * An annotation with a class member.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Typed {
        /**
         * @return The type
         */
        Class<?> value();
    }

    /**
     * A type-use annotation.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Marked {
    }

    /**
     * A repeatable annotation.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(Tags.class)
    public @interface Tag {
        /**
         * @return The tag
         */
        String value();
    }

    /**
     * The container of {@link Tag}.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tags {
        /**
         * @return The tags
         */
        Tag[] value();
    }
}
