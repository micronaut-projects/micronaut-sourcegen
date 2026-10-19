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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lambdas and method references: the functional interface method they implement, the receiver
 * they capture and the scope of the type variables they use.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "MissingOverride", "UnusedTypeParameter", "TypeParameterShadowing", "unchecked", "varargs",
    "rawtypes", "TypeParameterUnusedInFormals", "unused", "EqualsIncompatibleType", "UnusedMethod",
    "UnusedVariable"
})
public abstract class LambdaAndReferenceTck extends AbstractByteCodeWriterTck {

    /**
     * A bound method reference evaluates its receiver once, where it is created, so replacing the receiver later
     * does not change what the reference calls. A receiver that is the result of a call is read exactly once.
     *
     * @param kind          What the receiver is: a field, a local or the result of a call
     * @param receiverReads How many times creating the reference calls the method that returns the receiver
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @ParameterizedTest(name = "a reference to a {0} receiver captures it before it changes, calling next() {1} time(s)")
    @CsvSource({"field, 0", "local, 0", "result, 1"})
    public void referencesCaptureTheirReceiverBeforeItChanges(String kind, int receiverReads) throws Exception {
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
        var loader = load(target, caller);
        var cls = loader.loadClass(caller.getName());
        var instance = cls.getConstructor().newInstance();
        @SuppressWarnings("unchecked") var captured = (Function<Object, Object>) cls.getMethod("capture").invoke(instance);
        cls.getMethod("replace").invoke(instance);
        assertEquals("first", captured.apply("one"));
        assertEquals("first", captured.apply("two"));
        assertEquals(receiverReads, cls.getMethod("reads").invoke(instance));
    }

    /**
     * A bound reference checks its receiver where it is created, as `target::apply` does in source, not where the
     * reference is invoked.
     *
     * @throws Exception If the generated class cannot be used
     * @since 2.3
     */
    @Test
    public void boundReferenceRejectsANullReceiverWhereItIsCreated() throws Exception {
        var function = TypeDef.parameterized(Function.class, Object.class, Object.class);
        var apply = Function.class.getMethod("apply", Object.class);
        var def = ClassDef.builder("test.NullReceiverReference").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", function).returns(function)
                .build((self, p) -> function.methodReference(p.getFirst(), MethodDef.of(apply)).returning())).build();
        var cls = load(def).loadClass(def.getName());
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> cls.getMethod("reference", Function.class).invoke(cls.getConstructor().newInstance(), new Object[]{null}));
        assertInstanceOf(NullPointerException.class, error.getCause());
    }

    /**
     * A reference through `super` calls the method of the superclass, not the override of the class that creates it.
     *
     * @throws Exception If the generated class cannot be used
     * @since 2.3
     */
    @Test
    public void superReferenceCallsTheMethodOfTheSuperclass() throws Exception {
        var supplier = TypeDef.parameterized(java.util.function.Supplier.class, String.class);
        var name = NamedParent.class.getMethod("name");
        var def = ClassDef.builder("test.SuperReference").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(NamedParent.class))
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build((self, p) -> supplier.methodReference(self.superRef(ClassTypeDef.of(NamedParent.class)), MethodDef.of(name)).returning())).build();
        var cls = load(def).loadClass(def.getName());
        var reference = (java.util.function.Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("parent", reference.get());
    }

    /**
     * A void functional target discards the result of a reference through super.
     *
     * @throws Exception If the generated reference cannot be used
     * @since 2.3
     */
    @Test
    public void superReferenceCanDiscardTheReferencedResult() throws Exception {
        var runnable = ClassTypeDef.of(Runnable.class);
        var name = NamedParent.class.getMethod("name");
        var def = ClassDef.builder("test.DiscardingSuperReference").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(NamedParent.class))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(runnable)
                .build((self, p) -> runnable.methodReference(self.superRef(ClassTypeDef.of(NamedParent.class)),
                    MethodDef.of(name)).returning())).build();
        var cls = load(def).loadClass(def.getName());
        var reference = (Runnable) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        reference.run();
    }

    @Test
    public void explicitGenericMethodReferenceKeepsErasedDescriptor() throws Exception {
        var variable = TypeDef.variable("U", TypeDef.of(Number.class));
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(variable).addParameter("value", variable).returns(variable)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.GenericReferenceTarget").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        var function = TypeDef.parameterized(Function.class, TypeDef.of(Integer.class), TypeDef.of(Integer.class));
        var caller = ClassDef.builder("test.GenericReferenceCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(function)
                .build((self, p) -> function.staticMethodReference(target.asTypeDef(), identity).returning())).build();
        var loader = load(target, caller);
        @SuppressWarnings("unchecked")
        var reference = (Function<Integer, Integer>) loader.loadClass(caller.getName()).getMethod("reference").invoke(null);
        assertEquals(7, reference.apply(7));
    }

    @Test
    public void boundMethodReferenceUsesTheTargetsClassScope() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.edges.ReferenceTarget").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(identity).build();
        var function = TypeDef.parameterized(Function.class, TypeDef.of(Integer.class), TypeDef.of(Integer.class));
        var definition = ClassDef.builder("test.edges.ReferenceCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("target", TypeDef.parameterized(target.asTypeDef(), TypeDef.of(Integer.class)))
                .returns(function).build((self, p) -> function.methodReference(p.getFirst(), identity).returning())).build();
        var loader = load(target, definition);
        var targetClass = loader.loadClass(target.getName());
        @SuppressWarnings("unchecked")
        var reference = (Function<Integer, Integer>) loader.loadClass(definition.getName()).getMethod("call", targetClass)
            .invoke(null, targetClass.getConstructor().newInstance());
        assertEquals(7, reference.apply(7));
    }

    @Test
    public void staticMethodReferenceUsesTheTargetsMethodScope() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.edges.StaticReferenceTarget").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        var function = TypeDef.parameterized(Function.class, TypeDef.of(Integer.class), TypeDef.of(Integer.class));
        @SuppressWarnings("unchecked")
        var reference = (Function<Integer, Integer>) run(caller(function.staticMethodReference(target.asTypeDef(), identity)), target);
        assertEquals(7, reference.apply(7));
    }

    @Test
    public void lambdaCaptureRetainsTheEnclosingMethodsBound() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, TypeDef.of(Integer.class));
        var intValue = MethodDef.of(Number.class.getMethod("intValue"));
        var definition = ClassDef.builder("test.edges.GenericLambda").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.variable("T")).returns(supplier)
                .build((self, p) -> supplier.getLambda().implement((lambdaSelf, ignored) ->
                    p.getFirst().invoke(intValue).returning()).returning())).build();
        var result = (Supplier<?>) define(definition).getMethod("call", Number.class).invoke(null, 7);
        assertEquals(7, result.get());
    }

    @Test
    public void genericConstructorReferenceUsesConstructorScope() throws Exception {
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addParameter("value", TypeDef.variable("T"))
            .build((self, p) -> self.superRef().invokeSuperConstructor());
        var target = ClassDef.builder("test.additional.ReferencedConstructor").addModifiers(Modifier.PUBLIC).addMethod(constructor).build();
        var function = TypeDef.parameterized(Function.class, TypeDef.of(Integer.class), target.asTypeDef());
        var reference = (Function<Integer, ?>) run(function.constructorReference(target.asTypeDef(), constructor), target);
        assertEquals(target.getName(), reference.apply(7).getClass().getName());
    }

    @Test
    public void genericMethodBoundReferenceAcceptsATypeVariableReceiver() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, TypeDef.of(Integer.class));
        var referenced = MethodDef.of(CharSequence.class.getMethod("length"));
        var generated = ClassDef.builder("test.completeness.VariableReference").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
                .addParameter("value", TypeDef.variable("T")).returns(supplier)
                .build((self, p) -> supplier.methodReference(p.getFirst(), referenced).returning())).build();
        var reference = (Supplier<?>) define(generated).getMethod("call", CharSequence.class).invoke(null, "value");
        assertEquals(5, reference.get());
    }

    @Test
    public void genericVarargsMethodReferencePacksItsArguments() throws Exception {
        Function<String, String> oracle = CaptureFixtures.Calls::arrayComponent;
        var function = TypeDef.parameterized(Function.class, TypeDef.STRING, TypeDef.STRING);
        var referenced = MethodDef.of(CaptureFixtures.Calls.class.getMethod("arrayComponent", Object[].class));
        var definition = ClassDef.builder("test.completeness.VarargsReference").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(function)
                .build((self, p) -> function.staticMethodReference(ClassTypeDef.of(CaptureFixtures.Calls.class), referenced).returning())).build();
        var reference = (Function<String, String>) define(definition).getMethod("call").invoke(null);
        assertEquals(oracle.apply("value"), reference.apply("value"));
    }

    @Test
    public void voidAdaptedGenericSuperReferenceDiscardsItsReturnValue() throws Exception {
        var consumer = TypeDef.parameterized(Consumer.class, TypeDef.STRING);
        var referenced = MethodDef.of(ReferenceParent.class.getMethod("accept", Object.class));
        var definition = ClassDef.builder("test.completeness.GenericSuperReference").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ReferenceParent.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("callback").addModifiers(Modifier.PUBLIC).returns(consumer)
                .build((self, p) -> consumer.methodReference(self.superRef(), referenced).returning())).build();
        var instance = define(definition).getConstructor().newInstance();
        var reference = (Consumer<String>) instance.getClass().getMethod("callback").invoke(instance);
        reference.accept("value");
        assertEquals("value", ((ReferenceParent<?>) instance).last);
    }

    @Test
    public void methodReferenceSamUsesTheEnclosingMethodBound() throws Exception {
        var functionType = TypeDef.parameterized(Function.class, TypeDef.variable("T"), TypeDef.STRING);
        var referenced = MethodDef.of(InferenceFixtures.Calls.class.getMethod("number", Number.class));
        var definition = ClassDef.builder("test.inference.MethodReferenceScope").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).returns(functionType)
                .build((self, p) -> functionType.staticMethodReference(ClassTypeDef.of(InferenceFixtures.Calls.class), referenced).returning())).build();
        var function = (Function<Number, String>) define(definition).getMethod("reference").invoke(null);
        assertEquals("7", function.apply(7));
    }

    @Test
    public void methodReferenceSamMethodBoundShadowsClassBound() throws Exception {
        var functionType = TypeDef.parameterized(Function.class, TypeDef.variable("T"), TypeDef.STRING);
        var referenced = MethodDef.of(InferenceFixtures.Calls.class.getMethod("number", Number.class));
        var definition = ClassDef.builder("test.inference.MethodReferenceShadow").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).returns(functionType)
                .build((self, p) -> functionType.staticMethodReference(ClassTypeDef.of(InferenceFixtures.Calls.class), referenced).returning())).build();
        var type = define(definition);
        var function = (Function<Number, String>) type.getMethod("reference").invoke(type.getConstructor().newInstance());
        assertEquals("7", function.apply(7));
    }

    @Test
    public void lambdaSamUsesTheEnclosingMethodBound() throws Exception {
        var functionType = TypeDef.parameterized(Function.class, TypeDef.variable("T"), TypeDef.STRING);
        var referenced = MethodDef.of(InferenceFixtures.Calls.class.getMethod("number", Number.class));
        var definition = ClassDef.builder("test.inference.LambdaMethodScope").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).returns(functionType)
                .build((self, p) -> functionType.getLambda().implement((lambdaSelf, args) ->
                    ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic(referenced, args.getFirst()).returning()).returning())).build();
        var function = (Function<Number, String>) define(definition).getMethod("reference").invoke(null);
        assertEquals("7", function.apply(7));
    }

    @Test
    public void methodReferenceByNameToAnObjectMethodOfAnInterfaceReceiver() throws Exception {
        Runnable value = () -> { };
        Supplier<String> expected = value::toString;
        assertCall(outcome(expected::get), List.of(TypeDef.of(Runnable.class)), List.of(value),
            p -> TypeDef.parameterized(Supplier.class, TypeDef.STRING).methodReference(p.getFirst(), "toString")
                .invoke("get", TypeDef.OBJECT));
    }

    @Test
    public void methodReferenceByNameToAMethodWithABridge() throws Exception {
        Integer value = 5;
        java.util.function.ToIntFunction<Integer> expected = value::compareTo;
        assertCall(expected.applyAsInt(3), List.of(TypeDef.of(Integer.class)), List.of(value),
            p -> TypeDef.parameterized(java.util.function.ToIntFunction.class, Integer.class).methodReference(p.getFirst(), "compareTo")
                .invoke("applyAsInt", TypeDef.Primitive.INT, ExpressionDef.constant(3).cast(TypeDef.of(Integer.class))));
    }

    /**
     * A superclass with a method to refer to through `super`.
     *
     * @since 2.3
     */
    public static class NamedParent {
        /**
         * @return The name
         */
        public String name() {
            return "parent";
        }
    }

    /** Generic superclass for a void-adapted reference.
     * @param <T> Consumed value
     * @since 2.3
     */
    public static class ReferenceParent<T> {
        private T last;

        public T accept(T value) {
            last = value;
            return value;
        }
    }
}
