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
package io.micronaut.sourcegen.bytecode.tck;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loops, switches, try, catch and finally, and the statements that complete them, compared with what javac
 * writes for the same source.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
public abstract class ControlFlowTck extends AbstractByteCodeWriterTck {

    private static final ExpressionDef.NewInstance BOOM = ClassTypeDef.of(IllegalStateException.class)
        .instantiate(ExpressionDef.constant("boom"));

    @Test
    public void writesWhileLoopsAndFinallyOnReturnAndThrow() throws Exception {
        VariableDef.Local current = new VariableDef.Local("current", TypeDef.Primitive.INT);
        ClassDef definition = ClassDef.builder("example.TckControlParity")
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
    public void writesSwitchesSharingOneBodyAcrossManyKeys() throws Exception {
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
        ClassDef definition = ClassDef.builder("example.TckSharedSwitchParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
            .build();

        byte[] bytes = write(definition);
        Class<?> generated = new GeneratedClassLoader(Map.of(definition.getName(), bytes))
            .loadClass(definition.getName());

        assertEquals("shared", generated.getMethod("classify", int.class).invoke(null, 0));
        assertEquals("shared", generated.getMethod("classify", int.class).invoke(null, 30));
        assertEquals("last", generated.getMethod("classify", int.class).invoke(null, keys.length - 1));
        assertEquals("none", generated.getMethod("classify", int.class).invoke(null, 999));
        // Two distinct bodies, not sixty: the shared body is emitted once
        assertTrue(bytes.length < 2000, () -> "Expected a compact switch, got " + bytes.length + " bytes");
    }

    @Test
    public void writesTryWhoseCatchCompletesInsideAnIfBranch() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("example.TckTryCompletionParity");
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
    public void writesStaticFinalFieldsAssignedInTryAndCatch() throws Exception {
        ClassTypeDef type = ClassTypeDef.of("example.TckStaticFinalInTryCatch");
        FieldDef value = FieldDef.builder("VALUE", String.class)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        FieldDef failure = FieldDef.builder("FAILURE", Throwable.class)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassDef definition = ClassDef.builder(type.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(value)
            .addField(failure)
            .addStaticInitializer(StatementDef.multi(
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("a")))
                    .doCatch(Throwable.class, exceptionVar -> type.getStaticField(failure).put(exceptionVar)),
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("b")))
                    .doCatch(Throwable.class, exceptionVar -> type.getStaticField(value).put(ExpressionDef.constant("c")))
            ))
            .build();

        Class<?> generated = define(definition);

        assertEquals("b", generated.getField("VALUE").get(null));
        assertNull(generated.getField("FAILURE").get(null));
    }

    @Test
    public void writesReturnedVoidCallsAndFallbacksAfterExhaustiveStatements() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("example.TckUnreachableFallback");
        FieldDef runs = FieldDef.builder("runs", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build();
        MethodDef run = MethodDef.builder("run").addModifiers(Modifier.PUBLIC)
            .build((aThis, parameters) -> self.getStaticField(runs)
                .put(self.getStaticField(runs).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))));
        ClassDef definition = ClassDef.builder(self.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(runs)
            .addMethod(run)
            .addMethod(MethodDef.builder("delegate").addModifiers(Modifier.PUBLIC)
                .build((aThis, parameters) -> aThis.invoke(run).returning()))
            .addMethod(MethodDef.builder("select").addModifiers(Modifier.PUBLIC)
                .addParameter("index", int.class)
                .returns(Object.class)
                .build((aThis, parameters) -> StatementDef.multi(
                    parameters.get(0).asStatementSwitch(
                        TypeDef.OBJECT,
                        Map.of(ExpressionDef.constant(0), ExpressionDef.constant("zero").returning()),
                        ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()
                    ),
                    ExpressionDef.nullValue().returning()
                )))
            .addMethod(MethodDef.builder("guarded").addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .build((aThis, parameters) -> StatementDef.multi(
                    StatementDef.doTry(ExpressionDef.constant("value").returning())
                        .doCatch(Throwable.class, exceptionVar -> ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()),
                    ExpressionDef.nullValue().returning()
                )))
            .build();

        Class<?> generated = define(definition);
        Object instance = generated.getConstructor().newInstance();

        generated.getMethod("delegate").invoke(instance);
        assertEquals(1, generated.getField("runs").get(null));
        assertEquals("zero", generated.getMethod("select", int.class).invoke(instance, 0));
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> generated.getMethod("select", int.class).invoke(instance, 1));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("value", generated.getMethod("guarded").invoke(instance));
    }

    @Test
    public void innerHandlerWinsOverAnOuterHandlerOfTheSameType() throws Exception {
        // try { try { throw ISE } catch (ISE) { return "inner" } } catch (ISE) { return "outer" }
        Method run = run("test.hardening.NestedHandlers", TypeDef.STRING, List.of(), (self, p) ->
            StatementDef.doTry(StatementDef.doTry(BOOM.doThrow())
                    .doCatch(IllegalStateException.class, e -> ExpressionDef.constant("inner").returning()))
                .doCatch(IllegalStateException.class, e -> ExpressionDef.constant("outer").returning()));
        assertEquals("inner", invoke(run));
    }

    @Test
    public void innerFinallyRunsBeforeTheOuterCatch() throws Exception {
        // String s = ""; try { try { throw ISE } finally { s += "f" } } catch (ISE) { s += "c" } return s
        Method run = run("test.hardening.InnerFinally", TypeDef.STRING, List.of(), (self, p) ->
            ExpressionDef.constant("").newLocal("s", s -> StatementDef.multi(
                StatementDef.doTry(StatementDef.doTry(BOOM.doThrow())
                        .doFinally(((VariableDef.Local) s).assign(s.stringConcat(ExpressionDef.constant("f")))))
                    .doCatch(IllegalStateException.class, e -> ((VariableDef.Local) s).assign(s.stringConcat(ExpressionDef.constant("c")))),
                s.returning())));
        assertEquals("fc", invoke(run));
    }

    @Test
    public void synchronizedBlockReleasesItsMonitorWhenAnOuterCatchHandlesTheException() throws Exception {
        // try { synchronized (this) { throw ISE } } catch (ISE) { } return Thread.holdsLock(this)
        Method run = run("test.hardening.SynchronizedInTry", TypeDef.Primitive.BOOLEAN, List.of(), (self, p) ->
            StatementDef.multi(
                StatementDef.doTry(new StatementDef.Synchronized(self, BOOM.doThrow()))
                    .doCatch(IllegalStateException.class, e -> ExpressionDef.constant(0).newLocal("ignored")),
                ClassTypeDef.of(Thread.class).invokeStatic("holdsLock", TypeDef.Primitive.BOOLEAN, self).returning()));
        assertEquals(false, invoke(run));
    }

    @Test
    public void finallyReturnOverridesTheReturnOfTheTry() throws Exception {
        // try { return x; } finally { return 42; }
        Method run = run("test.hardening.FinallyReturn", TypeDef.Primitive.INT, List.of(TypeDef.Primitive.INT), (self, p) ->
            StatementDef.doTry(p.get(0).returning()).doFinally(ExpressionDef.constant(42).returning()));
        assertEquals(42, invoke(run, 1));
    }

    @Test
    public void finallyThatReturnsRunsOnceAfterACatchCompletes() throws Exception {
        // int count = 0; try { throw ISE } catch (ISE) { } finally { count++; return count; }
        VariableDef.Local count = new VariableDef.Local("count", TypeDef.Primitive.INT);
        Method run = run("test.hardening.FinallyReturnAfterCatch", TypeDef.Primitive.INT, List.of(), (self, p) ->
            StatementDef.multi(
                count.defineAndAssign(ExpressionDef.constant(0)),
                StatementDef.doTry(BOOM.doThrow())
                    .doCatch(IllegalStateException.class, e -> StatementDef.multi())
                    .doFinally(StatementDef.multi(
                        count.assign(count.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
                        count.returning()))));
        assertEquals(1, invoke(run));
    }

    @Test
    public void finallyThatThrowsAfterAReturnRunsOnce() throws Exception {
        // try { return 1; } catch (ISE) { return 2; } finally { counter++; throw ISE }
        ClassDef definition = ClassDef.builder("example.TckThrowingFinallyAfterReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("counter", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(ExpressionDef.constant(1).returning())
                    .doCatch(IllegalStateException.class, exception -> ExpressionDef.constant(2).returning())
                    .doFinally(StatementDef.multi(
                        increment(parameters.get(0)),
                        throwIllegalState("finally")
                    ))))
            .build();

        AtomicInteger counter = new AtomicInteger();
        assertThrowsIllegalState("finally", define(definition).getMethod("run", AtomicInteger.class), counter);
        assertEquals(1, counter.get());
    }

    @Test
    public void finallyThatThrowsAfterAReturnInACatchRunsOnce() throws Exception {
        // try { throw ISE } catch (ISE) { return 2; } finally { counter++; throw ISE }
        ClassDef definition = ClassDef.builder("example.TckThrowingFinallyAfterCatchReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("counter", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(throwIllegalState("boom"))
                    .doCatch(IllegalStateException.class, exception -> ExpressionDef.constant(2).returning())
                    .doFinally(StatementDef.multi(
                        increment(parameters.get(0)),
                        throwIllegalState("finally")
                    ))))
            .build();

        AtomicInteger counter = new AtomicInteger();
        assertThrowsIllegalState("finally", define(definition).getMethod("run", AtomicInteger.class), counter);
        assertEquals(1, counter.get());
    }

    @Test
    public void returnInAnInnerTryRunsTheInnerAndTheOuterFinally() throws Exception {
        // try { try { return 1; } finally { inner++; } } finally { outer++; }
        ClassDef definition = ClassDef.builder("example.TckNestedFinallyAfterReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("inner", AtomicInteger.class)
                .addParameter("outer", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(
                        StatementDef.doTry(ExpressionDef.constant(1).returning())
                            .doFinally(increment(parameters.get(0))))
                    .doFinally(increment(parameters.get(1)))))
            .build();

        AtomicInteger inner = new AtomicInteger();
        AtomicInteger outer = new AtomicInteger();
        Method run = define(definition).getMethod("run", AtomicInteger.class, AtomicInteger.class);
        assertEquals(1, run.invoke(null, inner, outer));
        assertEquals(1, inner.get());
        assertEquals(1, outer.get());
    }

    @Test
    public void returnInATryWithoutFinallyRunsTheOuterFinally() throws Exception {
        // try { try { return 1; } catch (ISE) { return 2; } } finally { counter++; }
        ClassDef definition = ClassDef.builder("example.TckOuterFinallyAfterCatchingTry")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("fail", TypeDef.Primitive.BOOLEAN)
                .addParameter("counter", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(
                        StatementDef.doTry(StatementDef.multi(
                                parameters.get(0).isTrue().doIf(throwIllegalState("boom")),
                                ExpressionDef.constant(1).returning()
                            ))
                            .doCatch(IllegalStateException.class, exception -> ExpressionDef.constant(2).returning()))
                    .doFinally(increment(parameters.get(1)))))
            .build();

        AtomicInteger counter = new AtomicInteger();
        Method run = define(definition).getMethod("run", boolean.class, AtomicInteger.class);
        assertEquals(1, run.invoke(null, false, counter));
        assertEquals(1, counter.get());
        assertEquals(2, run.invoke(null, true, counter));
        assertEquals(2, counter.get());
    }

    @Test
    public void innerFinallyThatThrowsAfterAReturnRunsOnceAndThenTheOuterFinally() throws Exception {
        // try { try { return 1; } finally { inner++; throw ISE } } finally { outer++; }
        ClassDef definition = ClassDef.builder("example.TckThrowingInnerFinallyAfterReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("inner", AtomicInteger.class)
                .addParameter("outer", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(
                        StatementDef.doTry(ExpressionDef.constant(1).returning())
                            .doFinally(StatementDef.multi(
                                increment(parameters.get(0)),
                                throwIllegalState("inner")
                            )))
                    .doFinally(increment(parameters.get(1)))))
            .build();

        AtomicInteger inner = new AtomicInteger();
        AtomicInteger outer = new AtomicInteger();
        Method run = define(definition).getMethod("run", AtomicInteger.class, AtomicInteger.class);
        assertThrowsIllegalState("inner", run, inner, outer);
        assertEquals(1, inner.get());
        assertEquals(1, outer.get());
    }

    @Test
    public void outerFinallyThatThrowsAfterAReturnInAnInnerTryRunsOnce() throws Exception {
        // try { try { return 1; } finally { inner++; } } finally { outer++; throw ISE }
        ClassDef definition = ClassDef.builder("example.TckThrowingOuterFinallyAfterReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("inner", AtomicInteger.class)
                .addParameter("outer", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(
                        StatementDef.doTry(ExpressionDef.constant(1).returning())
                            .doFinally(increment(parameters.get(0))))
                    .doFinally(StatementDef.multi(
                        increment(parameters.get(1)),
                        throwIllegalState("outer")
                    ))))
            .build();

        AtomicInteger inner = new AtomicInteger();
        AtomicInteger outer = new AtomicInteger();
        Method run = define(definition).getMethod("run", AtomicInteger.class, AtomicInteger.class);
        assertThrowsIllegalState("outer", run, inner, outer);
        assertEquals(1, inner.get());
        assertEquals(1, outer.get());
    }

    @Test
    public void returnInATryInsideASynchronizedBlockReleasesTheMonitor() throws Exception {
        // synchronized (lock) { try { return 1; } catch (ISE) { return 2; } }
        ClassDef definition = ClassDef.builder("example.TckReturnInTryInSynchronized")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("lock", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> new StatementDef.Synchronized(parameters.get(0),
                    StatementDef.doTry(ExpressionDef.constant(1).returning())
                        .doCatch(IllegalStateException.class, exception -> ExpressionDef.constant(2).returning()))))
            .build();

        Object lock = new Object();
        assertEquals(1, define(definition).getMethod("run", Object.class).invoke(null, lock));
        assertFalse(Thread.holdsLock(lock));
    }

    @Test
    public void synchronizedBlockReleasesItsMonitorOnceWhenTheFinallyAfterAReturnThrows() throws Exception {
        // try { synchronized (lock) { return 1; } } finally { counter++; throw ISE }
        ClassDef definition = ClassDef.builder("example.TckThrowingFinallyAfterSynchronizedReturn")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("lock", TypeDef.OBJECT)
                .addParameter("counter", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> StatementDef.doTry(
                        new StatementDef.Synchronized(parameters.get(0), ExpressionDef.constant(1).returning()))
                    .doFinally(StatementDef.multi(
                        increment(parameters.get(1)),
                        throwIllegalState("finally")
                    ))))
            .build();

        Method run = define(definition).getMethod("run", Object.class, AtomicInteger.class);
        Object lock = new Object();
        AtomicInteger counter = new AtomicInteger();
        // Releasing the monitor a second time throws from the handler releasing it, which the handler
        // may protect as javac does, so a failure can loop there
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThrowsIllegalState("finally", run, lock, counter);
            assertFalse(Thread.holdsLock(lock));
        });
        assertEquals(1, counter.get());
    }

    @Test
    public void finallyThatThrowsAfterAYieldRunsOnce() throws Exception {
        // return switch (value) { case 1 -> { try { yield 1; } finally { counter++; throw ISE } } default -> 0 };
        ClassDef definition = ClassDef.builder("example.TckThrowingFinallyAfterYield")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .addParameter("counter", AtomicInteger.class)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> new ExpressionDef.Switch(
                    parameters.get(0),
                    TypeDef.Primitive.INT,
                    Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.Primitive.INT,
                        StatementDef.doTry(ExpressionDef.constant(1).returning())
                            .doFinally(StatementDef.multi(
                                increment(parameters.get(1)),
                                throwIllegalState("finally")
                            )))),
                    ExpressionDef.constant(0)
                ).returning()))
            .build();

        AtomicInteger counter = new AtomicInteger();
        assertThrowsIllegalState("finally", define(definition).getMethod("run", int.class, AtomicInteger.class), 1, counter);
        assertEquals(1, counter.get());
    }

    @Test
    public void stringSwitchDistinguishesCasesWithTheSameHashCode() throws Exception {
        // "Aa".hashCode() == "BB".hashCode()
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant("Aa"), ExpressionDef.constant(1));
        cases.put(ExpressionDef.constant("BB"), ExpressionDef.constant(2));
        Method run = run("test.hardening.CollidingSwitch", TypeDef.Primitive.INT, List.of(TypeDef.STRING), (self, p) ->
            p.get(0).asExpressionSwitch(TypeDef.Primitive.INT, cases, ExpressionDef.constant(0)).returning());
        assertEquals(1, invoke(run, "Aa"));
        assertEquals(2, invoke(run, "BB"));
    }

    @Test
    public void switchYieldCasesMayDeclareTheSameLocalName() throws Exception {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), yieldingLocal("one"));
        cases.put(ExpressionDef.constant(2), yieldingLocal("two"));
        Method run = run("test.hardening.YieldLocals", TypeDef.STRING, List.of(TypeDef.Primitive.INT), (self, p) ->
            p.get(0).asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("other")).returning());
        assertEquals("two!", invoke(run, 2));
    }

    @ParameterizedTest(name = "a switch on a {0} selector widens it to int, as javac switches on it")
    @MethodSource("switchSelectors")
    public void switchOnANarrowOrBoxedSelectorWidensItToInt(TypeDef selector, List<ExpressionDef.Constant> keys,
                                                            List<Object> inputs, List<String> expected) throws Exception {
        // String expression(char c) { return switch (c) { case 'a' -> "case0"; case 'Z' -> "case1"; default -> "default"; }; }
        Method expression = run("test.hardening.SelectorExpression", TypeDef.STRING, List.of(selector), (self, p) -> {
            Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                cases.put(keys.get(i), ExpressionDef.constant("case" + i));
            }
            return p.get(0).asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("default")).returning();
        });
        // String statement(char c) { switch (c) { case 'a' -> { return "case0"; } ... } return "default"; }
        Method statement = run("test.hardening.SelectorStatement", TypeDef.STRING, List.of(selector), (self, p) -> {
            Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                cases.put(keys.get(i), ExpressionDef.constant("case" + i).returning());
            }
            return StatementDef.multi(p.get(0).asStatementSwitch(selector, cases, null), ExpressionDef.constant("default").returning());
        });
        for (int i = 0; i < inputs.size(); i++) {
            assertEquals(expected.get(i), invoke(expression, inputs.get(i)), "switch expression on " + inputs.get(i));
            assertEquals(expected.get(i), invoke(statement, inputs.get(i)), "switch statement on " + inputs.get(i));
        }
        if (!selector.isPrimitive()) {
            // javac unboxes the selector, which throws for null
            InvocationTargetException fromExpression = assertThrows(InvocationTargetException.class, () -> invoke(expression, (Object) null));
            assertInstanceOf(NullPointerException.class, fromExpression.getCause());
            InvocationTargetException fromStatement = assertThrows(InvocationTargetException.class, () -> invoke(statement, (Object) null));
            assertInstanceOf(NullPointerException.class, fromStatement.getCause());
        }
    }

    private static Stream<Arguments> switchSelectors() {
        List<ExpressionDef.Constant> chars = List.of(ExpressionDef.constant('a'), ExpressionDef.constant('Z'));
        List<ExpressionDef.Constant> bytes = List.of(ExpressionDef.primitiveConstant((byte) 1), ExpressionDef.primitiveConstant((byte) -3));
        List<ExpressionDef.Constant> shorts = List.of(ExpressionDef.primitiveConstant((short) 1), ExpressionDef.primitiveConstant((short) 300));
        List<ExpressionDef.Constant> ints = List.of(ExpressionDef.constant(1), ExpressionDef.constant(-5), ExpressionDef.constant(1000));
        List<String> twoCases = List.of("case0", "case1", "default");
        return Stream.of(
            Arguments.of(Named.of("char", TypeDef.Primitive.CHAR), chars, List.of('a', 'Z', 'b'), twoCases),
            Arguments.of(Named.of("byte", TypeDef.Primitive.BYTE), bytes, List.of((byte) 1, (byte) -3, (byte) 3), twoCases),
            Arguments.of(Named.of("short", TypeDef.Primitive.SHORT), shorts, List.of((short) 1, (short) 300, (short) 44), twoCases),
            Arguments.of(Named.of("Character", TypeDef.of(Character.class)), chars, List.of('a', 'Z', 'b'), twoCases),
            Arguments.of(Named.of("Byte", TypeDef.of(Byte.class)), bytes, List.of((byte) 1, (byte) -3, (byte) 3), twoCases),
            Arguments.of(Named.of("Short", TypeDef.of(Short.class)), shorts, List.of((short) 1, (short) 300, (short) 44), twoCases),
            Arguments.of(Named.of("Integer", TypeDef.of(Integer.class)), ints, List.of(1, -5, 1000, 7), List.of("case0", "case1", "case2", "default"))
        );
    }

    private static StatementDef throwIllegalState(String message) {
        return ClassTypeDef.of(IllegalStateException.class).instantiate(ExpressionDef.constant(message)).doThrow();
    }

    private static StatementDef increment(VariableDef counter) {
        return counter.invoke("incrementAndGet", TypeDef.Primitive.INT);
    }

    private static void assertThrowsIllegalState(String message, Method method, Object... arguments) {
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
            () -> method.invoke(null, arguments));
        assertEquals(message, assertInstanceOf(IllegalStateException.class, exception.getCause()).getMessage());
    }

    private static ExpressionDef yieldingLocal(String value) {
        return new ExpressionDef.SwitchYieldCase(TypeDef.STRING, ExpressionDef.constant(value)
            .newLocal("y", y -> y.stringConcat(ExpressionDef.constant("!")).returning()));
    }
}
