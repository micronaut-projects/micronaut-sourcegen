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

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.intMethod;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.invoke;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.stringMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Statements and control flow the bytecode writer accepts and the Java source has to write as a program that compiles
 * and behaves the same: loops over constant conditions, which javac folds (JLS 14.22), blocks of switch expressions,
 * try and catch, checked exceptions, initializers and definite assignment, and void conditionals and switches. Every
 * program is compiled and run; the expected results are what the bytecode writer's class does.
 */
class ControlFlowWriteTest {

    // ---- Reachability: constant conditions javac folds and the rules do not -------------------------------

    /**
     * A {@code static final} field with a constant initializer is a constant variable, and its qualified name is a
     * constant expression: {@code while (Owner.ENABLED)} cannot complete, so javac rejects the fallback after it.
     */
    @Test
    void staticFinalConstantFieldLoopDropsUnreachableFallback() throws Exception {
        var enabled = FieldDef.builder("ENABLED", boolean.class)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(ExpressionDef.constant(true)).build();
        var owner = ClassTypeDef.of("test.ConstantFieldLoop");
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(enabled)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> StatementDef.multi(
                    owner.getStaticField(enabled).isTrue().whileLoop(ExpressionDef.constant(1).returning()),
                    ExpressionDef.constant(2).returning())))
            .build();
        assertEquals(1, run(def));
    }

    /**
     * A loop over a condition javac takes for a constant expression (JLS 15.29) cannot complete when the condition is
     * true, so javac rejects the fallback after it, and its body is unreachable when the condition is false (JLS
     * 14.22), where the bytecode writer emits a loop that is never entered. The source keeps exactly the statements
     * javac accepts, and the method returns what the bytecode writer's does: 1 from the body, 2 from the fallback.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("constantLoopConditions")
    void loopOverAConstantConditionReturnsWhatTheBytecodeReturns(ConstantLoop loop) throws Exception {
        var def = intMethod("test." + loop.className(), StatementDef.multi(
            loop.condition().whileLoop(ExpressionDef.constant(1).returning()),
            ExpressionDef.constant(2).returning()));
        assertEquals(loop.expected(), run(def));
    }

    static Stream<ConstantLoop> constantLoopConditions() {
        var nan = ExpressionDef.constant(Double.NaN);
        var boxed = TypeDef.of(Integer.class);
        return Stream.of(
            new ConstantLoop("a constant of a compiled class, Integer.MAX_VALUE > 0, is a constant variable too",
                "JdkConstantLoop", ClassTypeDef.of(Integer.class).getStaticField("MAX_VALUE", TypeDef.Primitive.INT)
                    .compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, ExpressionDef.constant(0)), 1),
            new ConstantLoop("a conditional of constants, true ? true : false, is a constant expression",
                "ConditionalLoop", new ExpressionDef.IfElse(ExpressionDef.trueValue().isTrue(),
                    ExpressionDef.trueValue(), ExpressionDef.falseValue(), TypeDef.Primitive.BOOLEAN).isTrue(), 1),
            new ConstantLoop("a structural equality of primitives is written 1 == 1, which javac folds like the referential one",
                "StructuralLoop", ExpressionDef.constant(1).equalsStructurally(ExpressionDef.constant(1)), 1),
            new ConstantLoop("\"a\" == \"a\" is a constant expression, JLS 15.29 admits == of String constants",
                "StringIdentityLoop", ExpressionDef.constant("a").equalsReferentially(ExpressionDef.constant("a")), 1),
            new ConstantLoop("a condition cast to Boolean is no constant for the rules, but the cast is not written and 1 == 1 is",
                "CastConditionLoop", new ExpressionDef.IsTrue(ExpressionDef.constant(1)
                    .compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant(1)).cast(Boolean.class)), 1),
            new ConstantLoop("a NaN constant is not written NaNd, and the rules fold NaN != NaN",
                "NaNLoop", nan.compare(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO, nan), 1),
            new ConstantLoop("-0.0 == 0.0 is folded with the comparison semantics of Java",
                "SignedZeroLoop", ExpressionDef.constant(-0.0d)
                    .compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant(0.0d)), 1),
            new ConstantLoop("a boxed true is no constant expression for javac, so the fallback it requires is kept",
                "BoxedLoop", new ExpressionDef.IsTrue(ExpressionDef.constant(true).cast(TypeDef.of(Boolean.class))), 1),
            new ConstantLoop("an arithmetic comparison of constants, 1 + 1 == 2, is folded",
                "ArithmeticLoop", ExpressionDef.constant(1).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                    ExpressionDef.constant(1)).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant(2)), 1),
            new ConstantLoop("a sum of byte constants is a byte, (byte) (127 + 1) > 0 is false, so the body is never entered",
                "ByteSumLoop", new ExpressionDef.Constant(TypeDef.Primitive.BYTE, (byte) 127).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                    new ExpressionDef.Constant(TypeDef.Primitive.BYTE, (byte) 1)).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN,
                    ExpressionDef.constant(0)), 2),
            new ConstantLoop("a negated char constant is a char, (char) -'a' == (char) 0xff9f is true, so the loop cannot complete",
                "CharNegatedLoop", ExpressionDef.constant('a').math(ExpressionDef.MathUnaryOperation.OpType.NEGATE)
                    .compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant('\uff9f')), 1),
            new ConstantLoop("the body of a loop over 1 > 2 is unreachable and never entered",
                "WhileFalse", ExpressionDef.constant(1)
                    .compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, ExpressionDef.constant(2)), 2),
            new ConstantLoop("boxed constants are compared as the distinct references Integer.valueOf(1000) gives, not folded as 1000 == 1000",
                "BoxedIdentityLoop", new ExpressionDef.Constant(boxed, 1000)
                    .equalsReferentially(new ExpressionDef.Constant(boxed, 1000)), 2)
        );
    }

    /**
     * A loop over a constant condition, followed by a fallback, in the method {@code call} of {@code test.<className>}.
     *
     * @param description What the case shows
     * @param className The simple name of the class
     * @param condition The loop condition
     * @param expected What the bytecode writer's method returns
     */
    record ConstantLoop(String description, String className, ExpressionDef condition, int expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    // ---- Switch expression blocks --------------------------------------------------------------------------

    /**
     * A block of a switch expression may end with a {@code throw}; the bytecode writer takes any statement for it.
     */
    @Test
    void switchYieldBlockEndingInThrow() throws Exception {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
            ClassTypeDef.of(IllegalStateException.class).instantiate(ExpressionDef.constant("boom")).doThrow()));
        var def = ClassDef.builder("test.YieldThrow").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("key", int.class)
                .returns(String.class)
                .build((self, p) -> p.getFirst().asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("default"))
                    .returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("default", cls.getMethod("call", int.class).invoke(instance, 2));
            var error = assertThrows(InvocationTargetException.class, () -> cls.getMethod("call", int.class).invoke(instance, 1));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
    }

    /**
     * A block of a switch expression that ends with a try whose body and catch both yield.
     */
    @Test
    void switchYieldBlockEndingInTry() throws Exception {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
            StatementDef.doTry(ExpressionDef.constant("body").returning())
                .doCatch(RuntimeException.class, e -> ExpressionDef.constant("caught").returning())));
        var def = ClassDef.builder("test.YieldTry").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("key", int.class)
                .returns(String.class)
                .build((self, p) -> p.getFirst().asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("default"))
                    .returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("body", cls.getMethod("call", int.class).invoke(cls.getConstructor().newInstance(), 1));
        }
    }

    @Test
    void switchYieldInsideTryDoesNotReturnFromEnclosingMethod() throws Exception {
        var block = new ExpressionDef.SwitchYieldCase(TypeDef.STRING, StatementDef.multi(
            StatementDef.doTry(ExpressionDef.constant("yielded").returning())
                .doCatch(RuntimeException.class, error -> ExpressionDef.constant("caught").returning()),
            ExpressionDef.constant("fallback").returning()));
        var def = ClassDef.builder("test.TryYield").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ExpressionDef.constant(1).asExpressionSwitch(TypeDef.STRING,
                    Map.of(ExpressionDef.constant(1), block), ExpressionDef.constant("default"))
                    .newLocal("result", value -> value.stringConcat(ExpressionDef.constant("!")).returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("yielded!", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    // ---- Try / catch ---------------------------------------------------------------------------------------

    /**
     * A catch of a subclass after one of its superclass is dead in bytecode - the first handler takes the
     * exception - and javac rejects it: "exception IllegalStateException has already been caught".
     */
    @Test
    void catchOfSubclassAfterItsSuperclass() throws Exception {
        var def = stringMethod("test.ShadowedCatch", StatementDef.doTry(
                ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())
            .doCatch(RuntimeException.class, e -> ExpressionDef.constant("runtime").returning())
            .doCatch(IllegalStateException.class, e -> ExpressionDef.constant("state").returning()));
        assertEquals("runtime", run(def));
    }

    /**
     * A catch of a checked exception the body cannot throw is an error for javac ("exception IOException is never
     * thrown in body of corresponding try statement"), and a handler that is never entered for the bytecode writer.
     */
    @Test
    void catchOfCheckedExceptionTheBodyCannotThrow() throws Exception {
        var def = stringMethod("test.UnthrownCatch", StatementDef.doTry(ExpressionDef.constant("body").returning())
            .doCatch(IOException.class, e -> ExpressionDef.constant("io").returning()));
        assertEquals("body", run(def));
    }

    /**
     * A call of a method that declares a checked exception, from a method that declares none: the bytecode writer
     * has no notion of checked exceptions, javac reports "unreported exception".
     */
    @Test
    void undeclaredCheckedExceptionOfACall() throws Exception {
        var callable = TypeDef.parameterized(Callable.class, String.class);
        var def = ClassDef.builder("test.UndeclaredChecked").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("task", callable)
                .returns(Object.class)
                .build((self, p) -> p.getFirst().invoke("call", TypeDef.OBJECT).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            Callable<String> task = () -> "done";
            assertEquals("done", cls.getMethod("call", Callable.class).invoke(cls.getConstructor().newInstance(), task));
        }
    }

    // A lambda that throws a checked exception its functional method does not declare.
    @Test
    void lambdaThrowsCheckedException() throws Exception {
        var run = Runnable.class.getMethod("run");
        var def = single("CheckedLambda", void.class, List.of(), (self, p) ->
            ClassTypeDef.of(Runnable.class).getLambda().implement((ls, lp) ->
                ClassTypeDef.of(Exception.class).instantiate(ExpressionDef.constant("boom")).doThrow()).invoke(run));
        try (var loader = compile(def)) {
            var error = assertThrows(Exception.class, () -> invoke(loader, def));
            assertEquals("boom", error.getMessage());
        }
    }

    // As above for a method that does not declare it.
    @Test
    void methodThrowsUndeclaredCheckedException() throws Exception {
        var def = single("CheckedMethod", void.class, List.of(), (self, p) ->
            ClassTypeDef.of(java.io.IOException.class).instantiate(ExpressionDef.constant("io")).doThrow());
        try (var loader = compile(def)) {
            var error = assertThrows(java.io.IOException.class, () -> invoke(loader, def));
            assertEquals("io", error.getMessage());
        }
    }

    // ---- Initializers, constructors and blank finals -------------------------------------------------------

    /**
     * A static initializer that cannot complete normally: javac rejects it ("initializer must be able to complete
     * normally"), the JVM fails the initialization of the class with the exception.
     */
    @Test
    void staticInitializerThatAlwaysThrows() throws Exception {
        var def = ClassDef.builder("test.FailingInitializer").addModifiers(Modifier.PUBLIC)
            .addStaticInitializer(ClassTypeDef.of(IllegalStateException.class).instantiate(ExpressionDef.constant("boom")).doThrow())
            .build();
        try (var loader = compile(def)) {
            var error = assertThrows(ExceptionInInitializerError.class, () -> Class.forName(def.getName(), true, loader));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
    }

    /**
     * A return in a static initializer ends {@code <clinit>} in bytecode; javac reports "return outside method".
     */
    @Test
    void staticInitializerWithAnEarlyReturn() throws Exception {
        var owner = ClassTypeDef.of("test.EarlyReturnInitializer");
        var value = FieldDef.builder("VALUE", String.class).addModifiers(Modifier.PRIVATE, Modifier.STATIC).build();
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(value)
            .addStaticInitializer(StatementDef.multi(
                owner.getStaticField(value).isNull().doIf(StatementDef.multi(
                    owner.getStaticField(value).put(ExpressionDef.constant("early")),
                    new StatementDef.Return(null))),
                owner.getStaticField(value).put(ExpressionDef.constant("late"))))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> owner.getStaticField(value).returning()))
            .build();
        assertEquals("early", run(def));
    }

    /**
     * A blank final instance field no constructor assigns: the bytecode writer leaves it at its default value,
     * javac reports "variable value not initialized in the default constructor".
     */
    @Test
    void blankFinalInstanceFieldNeverAssigned() throws Exception {
        var value = FieldDef.builder("value", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var def = ClassDef.builder("test.UnassignedFinal").addModifiers(Modifier.PUBLIC).addField(value)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> self.field(value).returning()))
            .build();
        assertEquals(0, run(def));
    }

    /**
     * A blank final instance field assigned by the body of a try and by its catch: once at runtime, "might already
     * have been assigned" for javac - the static field of the same shape loses its {@code final}, this one does not.
     */
    @Test
    void blankFinalInstanceFieldAssignedInTryAndCatch() throws Exception {
        var value = FieldDef.builder("value", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var def = ClassDef.builder("test.TryAssignedFinal").addModifiers(Modifier.PUBLIC).addField(value)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> StatementDef.doTry(self.field(value).put(ClassTypeDef.of(Integer.class)
                        .invokeStatic("parseInt", TypeDef.Primitive.INT, ExpressionDef.constant("x"))))
                    .doCatch(NumberFormatException.class, e -> self.field(value).put(ExpressionDef.constant(-1)))))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> self.field(value).returning()))
            .build();
        assertEquals(-1, run(def));
    }

    /**
     * A blank final static field assigned again inside a block of a switch expression: the definite assignment
     * rules do not look into expressions, keep {@code final}, and javac reports "variable X might already have been
     * assigned"; {@code <clinit>} assigns it twice.
     */
    @Test
    void staticBlankFinalReassignedInASwitchYieldBlock() throws Exception {
        var owner = ClassTypeDef.of("test.YieldAssignedFinal");
        var x = FieldDef.builder("X", int.class).addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).build();
        var y = FieldDef.builder("Y", int.class).addModifiers(Modifier.PRIVATE, Modifier.STATIC).build();
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.Primitive.INT, StatementDef.multi(
            owner.getStaticField(x).put(ExpressionDef.constant(5)),
            ExpressionDef.constant(1).returning())));
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(x).addField(y)
            .addStaticInitializer(StatementDef.multi(
                owner.getStaticField(x).put(ExpressionDef.constant(0)),
                owner.getStaticField(y).put(ExpressionDef.constant(1)
                    .asExpressionSwitch(TypeDef.Primitive.INT, cases, ExpressionDef.constant(2)))))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> owner.getStaticField(x).returning()))
            .build();
        assertEquals(5, run(def));
    }

    /**
     * Both bytecode writers hoist the constructor call to the front of a constructor of a class with instance field
     * initializers (ByteCodeWriter#adjustConstructorStatements, JdkClassFileWriter); the source keeps the model's
     * order, which Java 25 accepts as a constructor prologue and runs before the super constructor.
     */
    @Test
    void constructorStatementBeforeTheSuperCall() throws Exception {
        var baseType = ClassTypeDef.of("test.LoggingBase");
        var log = FieldDef.builder("LOG", String.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .initializer(ExpressionDef.constant("")).build();
        var logField = baseType.getStaticField(log);
        var base = ClassDef.builder(baseType.getName()).addModifiers(Modifier.PUBLIC).addField(log)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> logField.put(logField.stringConcat(ExpressionDef.constant("super;")))))
            .build();
        var tag = FieldDef.builder("tag", String.class).addModifiers(Modifier.PRIVATE)
            .initializer(ExpressionDef.constant("tag")).build();
        var def = ClassDef.builder("test.LoggingDerived").addModifiers(Modifier.PUBLIC).superclass(baseType).addField(tag)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> StatementDef.multi(
                    logField.put(logField.stringConcat(ExpressionDef.constant("pre;"))),
                    self.superRef().invokeSuperConstructor())))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> logField.returning()))
            .build();
        try (var loader = compile(base, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("super;pre;", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    // ---- Returning void ------------------------------------------------------------------------------------

    /**
     * {@code return flag ? a() : b();} of a void method: the bytecode writer evaluates the branch and returns, a
     * conditional of void calls is not a statement in Java.
     */
    @Test
    void returningAVoidConditional() throws Exception {
        var owner = ClassTypeDef.of("test.VoidConditional");
        var last = lastField();
        var a = recorder(owner, last, "a");
        var b = recorder(owner, last, "b");
        var run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter("flag", boolean.class)
            .build((self, p) -> new ExpressionDef.IfElse(p.getFirst().isTrue(), owner.invokeStatic(a), owner.invokeStatic(b)).returning());
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(last)
            .addMethod(a).addMethod(b).addMethod(run)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> StatementDef.multi(
                    owner.invokeStatic(run, ExpressionDef.constant(false)),
                    owner.getStaticField(last).returning())))
            .build();
        assertEquals("b", run(def));
    }

    /**
     * The same conditional as the body of a {@code Runnable}: an expression body of a void lambda has to be a
     * statement expression.
     */
    @Test
    void voidConditionalAsTheBodyOfARunnable() throws Exception {
        var owner = ClassTypeDef.of("test.VoidConditionalLambda");
        var last = lastField();
        var a = recorder(owner, last, "a");
        var b = recorder(owner, last, "b");
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(last)
            .addMethod(a).addMethod(b)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ClassTypeDef.of(Runnable.class).getLambda().implement((ls, lp) ->
                        new ExpressionDef.IfElse(ExpressionDef.falseValue().isTrue(), owner.invokeStatic(a), owner.invokeStatic(b)).returning())
                    .newLocal("task", task -> StatementDef.multi(
                        task.invoke("run", TypeDef.VOID),
                        owner.getStaticField(last).returning()))))
            .build();
        assertEquals("b", run(def));
    }

    /**
     * A switch of void calls as the body of a {@code Runnable} - returned from a void method it is written as a
     * switch statement and compiles, as the body of a lambda it is a switch expression.
     */
    @Test
    void voidSwitchAsTheBodyOfARunnable() throws Exception {
        var owner = ClassTypeDef.of("test.VoidSwitchLambda");
        var last = lastField();
        var a = recorder(owner, last, "a");
        var b = recorder(owner, last, "b");
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), owner.invokeStatic(a));
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(last)
            .addMethod(a).addMethod(b)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ClassTypeDef.of(Runnable.class).getLambda().implement((ls, lp) ->
                        ExpressionDef.constant(2).asExpressionSwitch(TypeDef.VOID, cases, owner.invokeStatic(b)).returning())
                    .newLocal("task", task -> StatementDef.multi(
                        task.invoke("run", TypeDef.VOID),
                        owner.getStaticField(last).returning()))))
            .build();
        assertEquals("b", run(def));
    }

    /**
     * {@code return switch (k) { case 1 -> a(); default -> b(); };} of a void method is written as a switch
     * statement.
     */
    @Test
    void returningAVoidSwitch() throws Exception {
        var owner = ClassTypeDef.of("test.VoidSwitch");
        var last = lastField();
        var a = recorder(owner, last, "a");
        var b = recorder(owner, last, "b");
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), owner.invokeStatic(a));
        var run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE, Modifier.STATIC).addParameter("key", int.class)
            .build((self, p) -> p.getFirst().asExpressionSwitch(TypeDef.VOID, cases, owner.invokeStatic(b)).returning());
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(last)
            .addMethod(a).addMethod(b).addMethod(run)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> StatementDef.multi(
                    owner.invokeStatic(run, ExpressionDef.constant(2)),
                    owner.getStaticField(last).returning())))
            .build();
        assertEquals("b", run(def));
    }

    // ---- Helpers -------------------------------------------------------------------------------------------

    private static FieldDef lastField() {
        return FieldDef.builder("last", String.class).addModifiers(Modifier.PRIVATE, Modifier.STATIC).build();
    }

    /**
     * A static void method recording its name in the {@code last} field.
     */
    private static MethodDef recorder(ClassTypeDef owner, FieldDef last, String name) {
        return MethodDef.builder(name).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .build((self, p) -> owner.getStaticField(last).put(ExpressionDef.constant(name)));
    }
}
