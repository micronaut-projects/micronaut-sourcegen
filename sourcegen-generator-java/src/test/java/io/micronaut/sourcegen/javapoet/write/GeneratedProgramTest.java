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
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compileMatchingSnapshots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executes DSL-generated programs across generic, control-flow, and receiver-capture boundaries. */
class GeneratedProgramTest {

    private static final String SNAPSHOTS = "/generated-programs/java";

    @ParameterizedTest(name = "a generic array of rank {0} keeps its values through the call")
    @ValueSource(ints = {1, 2, 3})
    void genericArrayCallsPreserveValuesAcrossRanks(int rank) throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Integer.class));
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("value", t.array(rank)).returns(t.array(rank))
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.Rank" + rank).addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.of(Number.class).array(rank)).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            Object value = Array.newInstance(Integer.class, new int[rank]);
            Class<?> parameter = Array.newInstance(Number.class, new int[rank]).getClass();
            assertSame(value, cls.getMethod("call", parameter).invoke(cls.getConstructor().newInstance(), value));
        }
    }

    @ParameterizedTest(name = "a chain of {0} bounds keeps the runtime identity of the value")
    @ValueSource(ints = {1, 2, 8, 9})
    void chainedBoundsKeepTheirRuntimeIdentity(int length) throws Exception {
        var variables = new ArrayList<TypeDef.TypeVariable>();
        TypeDef bound = TypeDef.of(CharSequence.class);
        for (int i = length - 1; i >= 0; i--) {
            var variable = TypeDef.variable("T" + i, bound);
            variables.addFirst(variable);
            bound = variable;
        }
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC);
        variables.forEach(method::addTypeVariable);
        var identity = method.addParameter("value", variables.getFirst()).returns(variables.getFirst())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.Chain" + length).addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("text", cls.getMethod("call", Object.class).invoke(cls.getConstructor().newInstance(), "text"));
        }
    }

    @ParameterizedTest(name = "a reference through a {0} captures its receiver before it changes")
    @ValueSource(strings = {"field", "local", "result"})
    void referencesCaptureTheirReceiverBeforeItChanges(String kind) throws Exception {
        var label = FieldDef.builder("label", String.class).addModifiers(Modifier.PRIVATE).build();
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> self.field(label).returning());
        var target = ClassDef.builder("test.CapturedTarget").addModifiers(Modifier.PUBLIC).addField(label)
            .addAllFieldsConstructor(Modifier.PUBLIC).addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply).build();
        var receiver = FieldDef.builder("receiver", target.asTypeDef()).addModifiers(Modifier.PRIVATE)
            .initializer(target.asTypeDef().instantiate(ExpressionDef.constant("first"))).build();
        var count = FieldDef.builder("count", int.class).addModifiers(Modifier.PRIVATE).initializer(ExpressionDef.constant(0)).build();
        var next = MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(target.asTypeDef())
            .build((self, p) -> StatementDef.multi(self.field(count).put(self.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))), self.field(receiver).returning()));
        var function = TypeDef.parameterized(Function.class, Object.class, Object.class);
        var caller = ClassDef.builder("test.Capture" + kind).addModifiers(Modifier.PUBLIC).addField(receiver).addField(count).addMethod(next)
            .addMethod(MethodDef.builder("reads").addModifiers(Modifier.PUBLIC).returns(int.class).build((self, p) -> self.field(count).returning()))
            .addMethod(MethodDef.builder("replace").addModifiers(Modifier.PUBLIC).returns(void.class)
                .build((self, p) -> self.field(receiver).put(target.asTypeDef().instantiate(ExpressionDef.constant("second")))))
            .addMethod(MethodDef.builder("capture").addModifiers(Modifier.PUBLIC).returns(function).build((self, p) -> switch (kind) {
                case "field" -> function.methodReference(self.field(receiver), apply).returning();
                case "local" -> self.field(receiver).newLocal("target", local -> function.methodReference(local, apply).returning());
                default -> function.methodReference(self.invoke(next), apply).returning();
            })).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, target, caller)) {
            var cls = loader.loadClass(caller.getName());
            var instance = cls.getConstructor().newInstance();
            @SuppressWarnings("unchecked") var captured = (Function<Object, Object>) cls.getMethod("capture").invoke(instance);
            cls.getMethod("replace").invoke(instance);
            assertEquals("first", captured.apply("one"));
            assertEquals("first", captured.apply("two"));
            assertEquals(kind.equals("result") ? 1 : 0, cls.getMethod("reads").invoke(instance));
        }
    }

    @Test
    void genericRecordOverrideReturnsTheComponent() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        var record = RecordDef.builder("test.SuppliedRecord").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addProperty(PropertyDef.builder("value").ofType(t).build())
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field("value", t).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, record)) {
            var cls = loader.loadClass(record.getName());
            assertEquals("text", ((Supplier<?>) cls.getConstructor(CharSequence.class).newInstance("text")).get());
        }
    }

    @Test
    void enumOverrideIsCallableThroughItsGenericInterface() throws Exception {
        var def = EnumDef.builder("test.SuppliedEnum").addModifiers(Modifier.PUBLIC).addEnumConstant("ONE")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("one").returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            assertEquals("one", ((Supplier<?>) loader.loadClass(def.getName()).getField("ONE").get(null)).get());
        }
    }

    @Test
    void typedConstructorConvertsAnErasedArgument() throws Exception {
        var constructor = StringBuilder.class.getConstructor(CharSequence.class);
        var def = ClassDef.builder("test.TypedConstructor").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(StringBuilder.class)
                .build((self, p) -> ClassTypeDef.of(StringBuilder.class).instantiate(constructor, p.getFirst()).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("text", cls.getMethod("create", Object.class).invoke(cls.getConstructor().newInstance(), "text").toString());
        }
    }

    @Test
    void finallyRunsOnceOnBothReturnAndThrow() throws Exception {
        var increment = AtomicInteger.class.getMethod("incrementAndGet");
        var def = ClassDef.builder("test.FinallyPaths").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("counter", AtomicInteger.class)
                .addParameter("fail", boolean.class).returns(int.class)
                .build((self, p) -> StatementDef.doTry(StatementDef.multi(
                    p.get(1).isTrue().doIf(ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()), ExpressionDef.constant(7).returning()
                )).doFinally(p.getFirst().invoke(increment)))).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var method = cls.getMethod("call", AtomicInteger.class, boolean.class);
            var counter = new AtomicInteger();
            assertEquals(7, method.invoke(instance, counter, false));
            var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(instance, counter, true));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(2, counter.get());
        }
    }

    @ParameterizedTest(name = "a static final field assigned through {0} is initialized once")
    @ValueSource(strings = {"ifTrue", "ifFalse", "switchAll", "switchPartial", "loop", "monitor"})
    void staticInitializationHandlesEveryAssignmentPath(String kind) throws Exception {
        var owner = ClassTypeDef.of("test.Initialize" + kind);
        var field = FieldDef.builder("value", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var assign = owner.getStaticField(field).put(ExpressionDef.constant(7));
        StatementDef body = switch (kind) {
            case "ifTrue" -> ExpressionDef.trueValue().isTrue().doIf(assign);
            case "ifFalse" -> ExpressionDef.falseValue().isTrue().doIf(assign);
            case "switchAll" -> ExpressionDef.constant(1).asStatementSwitch(TypeDef.Primitive.INT,
                Map.of(ExpressionDef.constant(1), assign), owner.getStaticField(field).put(ExpressionDef.constant(9)));
            case "switchPartial" -> ExpressionDef.constant(0).asStatementSwitch(TypeDef.Primitive.INT,
                Map.of(ExpressionDef.constant(1), assign), StatementDef.multi());
            case "loop" -> ExpressionDef.constant(0).newLocal("index", local -> local.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, ExpressionDef.constant(2))
                .whileLoop(StatementDef.multi(owner.getStaticField(field).put(local), local.assign(local.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))))));
            default -> new StatementDef.Synchronized(ExpressionDef.constant("lock"), assign);
        };
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field).addStaticInitializer(body).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            int expected = switch (kind) { case "ifFalse", "switchPartial" -> 0; case "loop" -> 1; default -> 7; };
            assertEquals(expected, loader.loadClass(def.getName()).getField("value").get(null));
        }
    }

    @ParameterizedTest(name = "a local declared in a {0} cannot capture the static field assignment")
    @ValueSource(strings = {"if", "switch", "loop", "monitor"})
    void nestedLocalNamesCannotCaptureAStaticFieldAssignment(String kind) throws Exception {
        var owner = ClassTypeDef.of("test.Shadow" + kind);
        var field = FieldDef.builder("value", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var local = ExpressionDef.constant(1).newLocal("value", ignored -> StatementDef.multi());
        var parse = Boolean.class.getMethod("parseBoolean", String.class);
        StatementDef nested = switch (kind) {
            case "if" -> ExpressionDef.trueValue().isTrue().doIf(local);
            case "switch" -> ExpressionDef.constant(0).asStatementSwitch(TypeDef.Primitive.INT, Map.of(ExpressionDef.constant(0), local), StatementDef.multi());
            case "loop" -> ClassTypeDef.of(Boolean.class).invokeStatic(parse, ExpressionDef.constant("false")).isTrue().whileLoop(local);
            default -> new StatementDef.Synchronized(ExpressionDef.constant("lock"), local);
        };
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(StatementDef.multi(nested, owner.getStaticField(field).put(ExpressionDef.constant(9)))).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            assertEquals(9, loader.loadClass(def.getName()).getField("value").get(null));
        }
    }

    @Test
    void arrayReceiverArgumentsResolveVariablesOutsideTheCallerScope() throws Exception {
        var t = TypeDef.variable("T");
        var stored = FieldDef.builder("stored", Object.class).addModifiers(Modifier.PRIVATE).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> self.field(stored).returning());
        var target = ClassDef.builder("test.ArrayReceiver").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addField(stored)
            .addAllFieldsConstructor(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t)).addMethod(get).build();
        var outOfScope = TypeDef.variable("U", TypeDef.of(CharSequence.class)).array();
        var caller = ClassDef.builder("test.ArrayReceiverCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("target", TypeDef.parameterized(target.asTypeDef(), outOfScope))
                .returns(Object.class).build((self, p) -> p.getFirst().invoke(get).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, target, caller)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(caller.getName());
            var value = new String[]{"text"};
            assertSame(value, cls.getMethod("call", targetClass)
                .invoke(cls.getConstructor().newInstance(), targetClass.getConstructor(Object.class).newInstance((Object) value)));
        }
    }

    @Test
    void methodReferenceConvertsParameterizedArrayResults() throws Exception {
        var values = FieldDef.builder("values", Object.class).addModifiers(Modifier.PRIVATE).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> self.field(values).returning());
        var target = ClassDef.builder("test.ArraySupplier").addModifiers(Modifier.PUBLIC).addField(values).addAllFieldsConstructor(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.parameterized(List.class, String.class).array())).addMethod(get).build();
        var result = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.parameterized(List.class, Object.class).array());
        var caller = ClassDef.builder("test.ArraySupplierCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(result)
                .build((self, p) -> result.methodReference(p.getFirst(), get).returning())).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, target, caller)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(caller.getName());
            var value = new List<?>[]{List.of("text")};
            var instance = targetClass.getConstructor(Object.class).newInstance((Object) value);
            var reference = (Supplier<?>) cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(), instance);
            assertSame(value, reference.get());
        }
    }

    @Test
    void exhaustiveConditionalDropsTheUnreachableFallback() throws Exception {
        var def = ClassDef.builder("test.ExhaustiveBranches").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class).returns(String.class)
                .build((self, p) -> StatementDef.multi(p.getFirst().isTrue().doIfElse(
                    ExpressionDef.constant("yes").returning(), ExpressionDef.constant("no").returning()), ExpressionDef.constant("unreachable").returning()))).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("yes", cls.getMethod("call", boolean.class).invoke(instance, true));
            assertEquals("no", cls.getMethod("call", boolean.class).invoke(instance, false));
        }
    }

    @Test
    void enumCatchVariableDoesNotShadowItsExceptionField() throws Exception {
        var field = FieldDef.builder("e0", Throwable.class).addModifiers(Modifier.PRIVATE).build();
        var def = EnumDef.builder("test.CatchingEnum").addModifiers(Modifier.PUBLIC).addEnumConstant("ONE").addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Throwable.class).build((self, p) -> StatementDef.multi(
                StatementDef.doTry(ClassTypeDef.of(IllegalStateException.class).instantiate(ExpressionDef.constant("failure")).doThrow())
                    .doCatch(Throwable.class, exception -> self.field(field).put(exception)), self.field(field).returning()))).build();
        try (var loader = compileMatchingSnapshots(SNAPSHOTS, def)) {
            var cls = loader.loadClass(def.getName());
            var value = (Throwable) cls.getMethod("call").invoke(cls.getField("ONE").get(null));
            assertInstanceOf(IllegalStateException.class, value);
            assertEquals("failure", value.getMessage());
        }
    }
}
