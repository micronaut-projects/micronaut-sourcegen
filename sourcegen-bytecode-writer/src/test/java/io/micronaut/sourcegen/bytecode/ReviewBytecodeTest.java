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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.ADDITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes for the ASM writer as changed by PR #518: bound references check their receiver where they are
 * created, {@code super::m} is a lambda making the special call, a lambda captures {@code super} typed as the class
 * making the call, and bridges are resolved through {@code TypeHierarchy}. Every class is checked by the ASM
 * verifier ({@code checkClass}), loaded by the JVM and run; the method sets are compared with what javac produces
 * for the equivalent source where a source exists.
 *
 * <p>The class is public so that javac, compiling the equivalent sources, can extend its nested fixtures.</p>
 *
 * @since 2.2.2
 */
public class ReviewBytecodeTest {

    private static final ClassTypeDef PARENT = ClassTypeDef.of(NamedParent.class);
    private static final ClassTypeDef SUPPLIER_OF_STRING = TypeDef.parameterized(Supplier.class, String.class);
    private static final ClassTypeDef FUNCTION_OF_OBJECTS = TypeDef.parameterized(Function.class, Object.class, Object.class);

    public static final List<String> RECORDED = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------------------------
    // Bound references: the receiver is evaluated once and checked where the reference is created
    // ---------------------------------------------------------------------------------------------------------------

    // A bound reference whose receiver is a null field: NPE where the reference is created, as `field::apply` in source
    @Test
    void boundReferenceToANullFieldThrowsWhereItIsCreated() throws Exception {
        FieldDef target = FieldDef.builder("target", FUNCTION_OF_OBJECTS).addModifiers(Modifier.PRIVATE).build();
        ClassDef def = ClassDef.builder("test.NullFieldReceiver").addModifiers(Modifier.PUBLIC)
            .addField(target)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(FUNCTION_OF_OBJECTS)
                .build((self, p) -> FUNCTION_OF_OBJECTS.methodReference(self.field(target), MethodDef.of(apply())).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertNpe(() -> cls.getMethod("reference").invoke(cls.getConstructor().newInstance()));
    }

    // A bound reference whose receiver is a null local
    @Test
    void boundReferenceToANullLocalThrowsWhereItIsCreated() throws Exception {
        ClassDef def = ClassDef.builder("test.NullLocalReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(FUNCTION_OF_OBJECTS)
                .build((self, p) -> ExpressionDef.nullValue().cast(FUNCTION_OF_OBJECTS).newLocal("target",
                    local -> FUNCTION_OF_OBJECTS.methodReference(local, MethodDef.of(apply())).returning())))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertNpe(() -> cls.getMethod("reference").invoke(cls.getConstructor().newInstance()));
    }

    // A bound reference whose receiver is the null constant itself
    @Test
    void boundReferenceToANullConstantThrowsWhereItIsCreated() throws Exception {
        ClassDef def = ClassDef.builder("test.NullConstantReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(
                    ExpressionDef.nullValue().cast(String.class), MethodDef.of(method(Object.class, "toString"))).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertNpe(() -> cls.getMethod("reference").invoke(cls.getConstructor().newInstance()));
    }

    // A receiver computed by a call with a side effect: evaluated exactly once, even when it is null and the NPE is thrown
    @Test
    void boundReferenceReceiverFromACallIsEvaluatedOnceEvenWhenNull() throws Exception {
        FieldDef count = FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).build();
        MethodDef next = MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(FUNCTION_OF_OBJECTS)
            .build((self, p) -> StatementDef.multi(
                self.field(count).put(self.field(count).math(ADDITION, ExpressionDef.constant(1))),
                ExpressionDef.nullValue().cast(FUNCTION_OF_OBJECTS).returning()));
        ClassDef def = ClassDef.builder("test.NullResultReceiver").addModifiers(Modifier.PUBLIC)
            .addField(count)
            .addMethod(next)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(FUNCTION_OF_OBJECTS)
                .build((self, p) -> FUNCTION_OF_OBJECTS.methodReference(self.invoke(next), MethodDef.of(apply())).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertNpe(() -> cls.getMethod("reference").invoke(instance));
        assertEquals(1, cls.getField("count").get(instance));
    }

    // A non-null receiver: the reference is created without invoking the method, and the check does not call it either
    @Test
    void boundReferenceToANonNullReceiverIsNotInvokedWhenCreated() throws Exception {
        ClassTypeDef counting = ClassTypeDef.of(CountingFunction.class);
        ClassDef def = ClassDef.builder("test.CountingReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", counting).returns(FUNCTION_OF_OBJECTS)
                .build((self, p) -> FUNCTION_OF_OBJECTS.methodReference(p.get(0), MethodDef.of(method(CountingFunction.class, "apply", Object.class))).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        CountingFunction.calls = 0;

        @SuppressWarnings("unchecked")
        Function<Object, Object> reference = (Function<Object, Object>) cls.getMethod("reference", CountingFunction.class)
            .invoke(cls.getConstructor().newInstance(), new CountingFunction());
        assertEquals(0, CountingFunction.calls);
        assertEquals("x", reference.apply("x"));
        assertEquals(1, CountingFunction.calls);
    }

    // A receiver of a boxed primitive: `((Integer) 5)::equals` as a Function<Object, Boolean>
    @Test
    void boundReferenceToABoxedReceiver() throws Exception {
        ClassTypeDef function = TypeDef.parameterized(Function.class, Object.class, Boolean.class);
        ClassDef def = ClassDef.builder("test.BoxedReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(function)
                .build((self, p) -> function.methodReference(ExpressionDef.constant(5).cast(Integer.class),
                    MethodDef.of(method(Object.class, "equals", Object.class))).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        @SuppressWarnings("unchecked")
        Function<Object, Boolean> reference = (Function<Object, Boolean>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals(Boolean.TRUE, reference.apply(5));
        assertEquals(Boolean.FALSE, reference.apply(6));
    }

    // `this::m` is not null-checked, a field receiver is
    @Test
    void thisReceiverIsNotNullCheckedWhileAFieldReceiverIs() throws Exception {
        MethodDef name = MethodDef.builder("name").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("me").returning());
        ClassDef viaThis = ClassDef.builder("test.ThisReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(name)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self, name).returning()))
            .build();
        FieldDef other = FieldDef.builder("other", viaThis.asTypeDef()).addModifiers(Modifier.PUBLIC).build();
        ClassDef viaField = ClassDef.builder("test.FieldReceiver").addModifiers(Modifier.PUBLIC)
            .addField(other)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.field(other), name).returning()))
            .build();
        byte[] thisBytes = write(viaThis);
        byte[] fieldBytes = write(viaField);

        assertFalse(trace(thisBytes).contains("requireNonNull"), trace(thisBytes));
        assertTrue(trace(fieldBytes).contains("java/util/Objects.requireNonNull"), trace(fieldBytes));
        var loader = load(viaThis, viaField);
        Class<?> cls = loader.loadClass(viaThis.getName());
        assertEquals("me", ((Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).get());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // References through super
    // ---------------------------------------------------------------------------------------------------------------

    // `super::name` calls the superclass, `this::name` the override, in the same class
    @Test
    void superReferenceCallsTheSuperclassAndThisReferenceTheOverride() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassDef def = childOf("test.SuperAndThis")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("viaSuper").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(PARENT), MethodDef.of(name)).returning()))
            .addMethod(MethodDef.builder("viaThis").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self, MethodDef.of(name)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("parent", ((Supplier<?>) cls.getMethod("viaSuper").invoke(instance)).get());
        assertEquals("child", ((Supplier<?>) cls.getMethod("viaThis").invoke(instance)).get());
    }

    // `super::greet` where greet is protected in a superclass of another package: the special call from the lambda
    // is verified against the class making it
    @Test
    void superReferenceToAProtectedMethodOfAnotherPackage() throws Exception {
        Method greet = NamedParent.class.getDeclaredMethod("greet");
        ClassDef def = childOf("test.ProtectedSuper")
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PROTECTED).overrides().returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(PARENT), MethodDef.of(greet)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertEquals("hello from parent", ((Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).get());
    }

    // `super::hidden` where hidden is package-private in a generated superclass of the same package
    @Test
    void superReferenceToAPackagePrivateMethodOfAGeneratedSuperclass() throws Exception {
        MethodDef hidden = MethodDef.builder("hidden").returns(String.class)
            .build((self, p) -> ExpressionDef.constant("parent").returning());
        ClassDef parent = ClassDef.builder("test.PkgModelParent").addModifiers(Modifier.PUBLIC).addMethod(hidden).build();
        ClassDef child = ClassDef.builder("test.PkgModelChild").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("hidden").overrides().returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(parent.asTypeDef()), hidden).returning()))
            .build();
        Class<?> cls = load(parent, child).loadClass(child.getName());

        assertEquals("parent", ((Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).get());
    }

    // `super::echo` of a generic method `<T> T echo(T)`
    @Test
    void superReferenceToAGenericMethod() throws Exception {
        Method echo = NamedParent.class.getMethod("echo", Object.class);
        ClassDef def = childOf("test.GenericSuper")
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(FUNCTION_OF_OBJECTS)
                .build((self, p) -> FUNCTION_OF_OBJECTS.methodReference(self.superRef(PARENT), MethodDef.of(echo)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        @SuppressWarnings("unchecked")
        Function<Object, Object> reference = (Function<Object, Object>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("echoed", reference.apply("echoed"));
    }

    // `super::record` of a void method as a Consumer, overridden in the class
    @Test
    void superReferenceToAVoidMethod() throws Exception {
        Method record = NamedParent.class.getMethod("record", String.class);
        ClassTypeDef consumer = TypeDef.parameterized(Consumer.class, String.class);
        ClassDef def = childOf("test.VoidSuper")
            .addMethod(MethodDef.builder("record").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class)
                .build((self, p) -> ClassTypeDef.of(RuntimeException.class).instantiate().doThrow()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(consumer)
                .build((self, p) -> consumer.methodReference(self.superRef(PARENT), MethodDef.of(record)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        RECORDED.clear();

        @SuppressWarnings("unchecked")
        Consumer<String> reference = (Consumer<String>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        reference.accept("x");
        assertEquals(List.of("parent:x"), RECORDED);
    }

    // `super::count` returning int, as an IntSupplier and as a Supplier<Integer> (boxed on return)
    @Test
    void superReferenceReturningAPrimitiveBoxedAndUnboxed() throws Exception {
        Method count = NamedParent.class.getMethod("count");
        ClassTypeDef intSupplier = ClassTypeDef.of(IntSupplier.class);
        ClassTypeDef supplierOfInteger = TypeDef.parameterized(Supplier.class, Integer.class);
        ClassDef def = childOf("test.PrimitiveSuper")
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC).overrides().returns(int.class)
                .build((self, p) -> ExpressionDef.constant(2).returning()))
            .addMethod(MethodDef.builder("primitive").addModifiers(Modifier.PUBLIC).returns(intSupplier)
                .build((self, p) -> intSupplier.methodReference(self.superRef(PARENT), MethodDef.of(count)).returning()))
            .addMethod(MethodDef.builder("boxedReference").addModifiers(Modifier.PUBLIC).returns(supplierOfInteger)
                .build((self, p) -> supplierOfInteger.methodReference(self.superRef(PARENT), MethodDef.of(count)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals(1, ((IntSupplier) cls.getMethod("primitive").invoke(instance)).getAsInt());
        assertEquals(1, ((Supplier<?>) cls.getMethod("boxedReference").invoke(instance)).get());
    }

    // `super::boxed` returning Integer as an IntSupplier (unboxed on return)
    @Test
    void superReferenceReturningABoxedValueAsAPrimitive() throws Exception {
        Method boxed = NamedParent.class.getMethod("boxed");
        ClassTypeDef intSupplier = ClassTypeDef.of(IntSupplier.class);
        ClassDef def = childOf("test.UnboxingSuper")
            .addMethod(MethodDef.builder("boxed").addModifiers(Modifier.PUBLIC).overrides().returns(Integer.class)
                .build((self, p) -> ExpressionDef.constant(20).cast(Integer.class).returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(intSupplier)
                .build((self, p) -> intSupplier.methodReference(self.superRef(PARENT), MethodDef.of(boxed)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertEquals(10, ((IntSupplier) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).getAsInt());
    }

    // `super::fmt(String, int)` as a BiFunction<String, Integer, String>: the boxed argument is unboxed
    @Test
    void superReferenceWithAPrimitiveParameter() throws Exception {
        Method fmt = NamedParent.class.getMethod("fmt", String.class, int.class);
        ClassTypeDef biFunction = TypeDef.parameterized(BiFunction.class, String.class, Integer.class, String.class);
        ClassDef def = childOf("test.PrimitiveParameterSuper")
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(biFunction)
                .build((self, p) -> biFunction.methodReference(self.superRef(PARENT), MethodDef.of(fmt)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        @SuppressWarnings("unchecked")
        BiFunction<String, Integer, String> reference = (BiFunction<String, Integer, String>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("ababab", reference.apply("ab", 3));
    }

    // `Runnable r = super::name` where name returns a value: valid Java, the result is discarded
    @Test
    void superReferenceToAValueReturningMethodAsAVoidInterface() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassTypeDef runnable = ClassTypeDef.of(Runnable.class);
        ClassDef def = childOf("test.DiscardingSuper")
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(runnable)
                .build((self, p) -> runnable.methodReference(self.superRef(PARENT), MethodDef.of(name)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        ((Runnable) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).run();
    }

    // `Described.super::describe`: a reference to the default method of a superinterface, overridden in the class
    @Test
    void superReferenceToAnInterfaceDefaultMethod() throws Exception {
        Method describe = Described.class.getMethod("describe");
        ClassTypeDef described = ClassTypeDef.of(Described.class);
        ClassDef def = ClassDef.builder("test.DefaultSuper").addModifiers(Modifier.PUBLIC).addSuperinterface(described)
            .addMethod(override("describe", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(described), MethodDef.of(describe)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("child", ((Described) instance).describe());
        assertEquals("default", ((Supplier<?>) cls.getMethod("reference").invoke(instance)).get());
    }

    // `super::name` created in a constructor: the lambda it becomes needs a legal name
    @Test
    void superReferenceInAConstructor() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        FieldDef ref = FieldDef.builder("ref", SUPPLIER_OF_STRING).addModifiers(Modifier.PUBLIC).build();
        ClassDef def = childOf("test.ConstructorSuper")
            .addField(ref)
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> StatementDef.multi(
                    self.superRef(PARENT).invokeSuperConstructor(),
                    self.field(ref).put(SUPPLIER_OF_STRING.methodReference(self.superRef(PARENT), MethodDef.of(name))))))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("parent", ((Supplier<?>) cls.getField("ref").get(instance)).get());
    }

    // `super::name` through the unnamed super (`aThis.superRef()`, TypeDef.SUPER)
    @Test
    void superReferenceThroughTheUnnamedSuper() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassDef def = childOf("test.UnnamedSuper")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(), MethodDef.of(name)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertEquals("parent", ((Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).get());
    }

    // `super::join(String...)` as a Function<String[], String>
    @Test
    void superReferenceToAVarargsMethod() throws Exception {
        Method join = NamedParent.class.getMethod("join", String[].class);
        ClassTypeDef function = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING.array(), TypeDef.STRING);
        ClassDef def = childOf("test.VarargsSuper")
            .addMethod(MethodDef.builder("join").addModifiers(Modifier.PUBLIC).overrides().addParameter("parts", String[].class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(function)
                .build((self, p) -> function.methodReference(self.superRef(PARENT), MethodDef.of(join)).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        @SuppressWarnings("unchecked")
        Function<String[], String> reference = (Function<String[], String>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("a+b", reference.apply(new String[] {"a", "b"}));
    }

    // `super::name` created inside a lambda body: the enclosing lambda captures super for the nested one
    @Test
    void superReferenceInsideALambda() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), SUPPLIER_OF_STRING);
        ClassDef def = childOf("test.NestedSuperReference")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((self, p) -> outer.getLambda().implement((aThis, lp) ->
                    SUPPLIER_OF_STRING.methodReference(self.superRef(PARENT), MethodDef.of(name)).returning()).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        Supplier<?> outerSupplier = (Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("parent", ((Supplier<?>) outerSupplier.get()).get());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lambdas capturing super
    // ---------------------------------------------------------------------------------------------------------------

    // A lambda capturing a parameter, super, this, a long local and a String local: capture order and slot typing
    @Test
    void lambdaCapturesParameterSuperThisAndLocals() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        Method get = Supplier.class.getMethod("get");
        ClassDef def = childOf("test.CaptureEverything")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).addParameter("prefix", String.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant(7L).newLocal("big", big -> ExpressionDef.constant("!").newLocal("suffix", suffix ->
                    SUPPLIER_OF_STRING.getLambda().implement((aThis, lp) -> p.get(0)
                        .stringConcat(self.superRef(PARENT).invoke(name))
                        .stringConcat(self.invoke(name))
                        .stringConcat(big)
                        .stringConcat(suffix)
                        .returning()).invoke(get).cast(String.class).returning()))))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertEquals("pre-parentchild7!", cls.getMethod("describe", String.class).invoke(cls.getConstructor().newInstance(), "pre-"));
    }

    // A lambda returning a lambda that calls super.name(): super is captured through two levels
    @Test
    void nestedLambdaCapturesSuper() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), SUPPLIER_OF_STRING);
        ClassDef def = childOf("test.NestedSuperLambda")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((self, p) -> outer.getLambda().implement((aThis, lp) ->
                    SUPPLIER_OF_STRING.getLambda().implement((inner, ip) -> self.superRef(PARENT).invoke(name).returning()).returning()).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        Supplier<?> outerSupplier = (Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("parent", ((Supplier<?>) outerSupplier.get()).get());
    }

    // A lambda returning a lambda that calls this.name(): the same shape without super, to tell a capture that fails
    // for every nested lambda from one that fails for super
    @Test
    void nestedLambdaCapturesThis() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), SUPPLIER_OF_STRING);
        ClassDef def = childOf("test.NestedThisLambda")
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((self, p) -> outer.getLambda().implement((aThis, lp) ->
                    SUPPLIER_OF_STRING.getLambda().implement((inner, ip) -> self.invoke(name).returning()).returning()).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        Supplier<?> outerSupplier = (Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
        assertEquals("child", ((Supplier<?>) outerSupplier.get()).get());
    }

    // A bound reference created inside a lambda body to a parameter the lambda captured: checked where created
    @Test
    void boundReferenceInsideALambdaToACapturedParameter() throws Exception {
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), FUNCTION_OF_OBJECTS);
        ClassDef def = ClassDef.builder("test.CapturedReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", FUNCTION_OF_OBJECTS).returns(outer)
                .build((self, p) -> outer.getLambda().implement((aThis, lp) ->
                    FUNCTION_OF_OBJECTS.methodReference(p.get(0), MethodDef.of(apply())).returning()).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        Supplier<?> withNull = (Supplier<?>) cls.getMethod("reference", Function.class).invoke(instance, new Object[] {null});
        assertThrows(NullPointerException.class, withNull::get);
        Supplier<?> withValue = (Supplier<?>) cls.getMethod("reference", Function.class).invoke(instance, (Function<Object, Object>) value -> "got " + value);
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) withValue.get();
        assertEquals("got x", function.apply("x"));
    }

    // A bound reference whose receiver is a null static field
    @Test
    void boundReferenceToANullStaticFieldThrowsWhereItIsCreated() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("test.NullStaticReceiver");
        FieldDef target = FieldDef.builder("TARGET", FUNCTION_OF_OBJECTS).addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        ClassDef def = ClassDef.builder(self.getName()).addModifiers(Modifier.PUBLIC)
            .addField(target)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(FUNCTION_OF_OBJECTS)
                .build((ignored, p) -> FUNCTION_OF_OBJECTS.methodReference(self.getStaticField(target), MethodDef.of(apply())).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());

        assertNpe(() -> cls.getMethod("reference").invoke(null));
        cls.getField("TARGET").set(null, (Function<Object, Object>) value -> "static " + value);
        @SuppressWarnings("unchecked")
        Function<Object, Object> reference = (Function<Object, Object>) cls.getMethod("reference").invoke(null);
        assertEquals("static x", reference.apply("x"));
    }

    // `super::toString` where the superclass inherits toString from Object: the special call resolves upwards
    @Test
    void superReferenceToAMethodInheritedByTheSuperclass() throws Exception {
        ClassDef def = childOf("test.GrandparentSuper")
            .addMethod(override("toString", "child"))
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.methodReference(self.superRef(PARENT), MethodDef.of(method(Object.class, "toString"))).returning()))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("child", instance.toString());
        String viaSuper = (String) ((Supplier<?>) cls.getMethod("reference").invoke(instance)).get();
        assertTrue(viaSuper.startsWith("test.GrandparentSuper@"), viaSuper);
    }

    // A lambda calling super.name() created in a constructor
    @Test
    void lambdaCapturingSuperInAConstructor() throws Exception {
        Method name = NamedParent.class.getMethod("name");
        FieldDef ref = FieldDef.builder("ref", SUPPLIER_OF_STRING).addModifiers(Modifier.PUBLIC).build();
        ClassDef def = childOf("test.ConstructorSuperLambda")
            .addField(ref)
            .addMethod(override("name", "child"))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> StatementDef.multi(
                    self.superRef(PARENT).invokeSuperConstructor(),
                    self.field(ref).put(SUPPLIER_OF_STRING.getLambda().implement((aThis, lp) -> self.superRef(PARENT).invoke(name).returning())))))
            .build();
        Class<?> cls = load(def).loadClass(def.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("parent", ((Supplier<?>) cls.getField("ref").get(instance)).get());
    }

    // A lambda in an interface default method calling `Described.super.describe()`: super is captured as the interface
    @Test
    void lambdaCapturingSuperInAnInterfaceDefaultMethod() throws Exception {
        Method describe = Described.class.getMethod("describe");
        ClassTypeDef described = ClassTypeDef.of(Described.class);
        InterfaceDef iface = InterfaceDef.builder("test.Describing").addModifiers(Modifier.PUBLIC).addSuperinterface(described)
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).overrides().returns(String.class)
                .build((self, p) -> ExpressionDef.constant("interface").returning()))
            .addMethod(MethodDef.builder("deferred").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(SUPPLIER_OF_STRING)
                .build((self, p) -> SUPPLIER_OF_STRING.getLambda().implement((aThis, lp) -> self.superRef(described).invoke(describe).returning()).returning()))
            .build();
        ClassDef impl = ClassDef.builder("test.DescribingImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(iface.asTypeDef()).build();
        var loader = load(iface, impl);
        Class<?> cls = loader.loadClass(impl.getName());
        Object instance = cls.getConstructor().newInstance();

        assertEquals("interface", ((Described) instance).describe());
        assertEquals("default", ((Supplier<?>) cls.getMethod("deferred").invoke(instance)).get());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bridges, compared with javac
    // ---------------------------------------------------------------------------------------------------------------

    // Function<String, String> with String apply(String): the bridge Object apply(Object), as javac writes it
    @Test
    void bridgeForAFunctionOfStringsMatchesJavac() throws Exception {
        ClassDef def = ClassDef.builder("test.Upper").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(method(String.class, "toUpperCase")).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.Upper", """
            package test;
            public class Upper implements java.util.function.Function<String, String> {
                @Override public String apply(String value) { return value.toUpperCase(); }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) generated.getConstructor().newInstance();
        assertEquals("A", function.apply("a"));
    }

    // A reflected method `<T extends Object & Comparable<T>> Object pick(T)` erases to pick(Object) (leftmost bound);
    // an override String pick(Object) needs the bridge Object pick(Object), which javac writes
    @Test
    void bridgeForAMultiBoundMethodVariableErasedToItsLeftmostBound() throws Exception {
        ClassDef def = ClassDef.builder("test.Picker").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(MultiBoundParent.class))
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child:").stringConcat(p.get(0)).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.Picker", """
            package test;
            public class Picker extends io.micronaut.sourcegen.bytecode.ReviewBytecodeTest.MultiBoundParent {
                @Override public String pick(Object value) { return "child:" + value; }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        MultiBoundParent instance = (MultiBoundParent) generated.getConstructor().newInstance();
        assertEquals("child:x", instance.pick("x"));
    }

    // A model class variable `T extends Object & Comparable<T>`: javac erases T pick(T) to Object pick(Object)
    @Test
    void modelMultiBoundClassVariableErasesToItsLeftmostBoundLikeJavac() throws Exception {
        TypeDef.TypeVariable t = TypeDef.variable("T", TypeDef.OBJECT, TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        ClassDef def = ClassDef.builder("test.MultiBound").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
                .build((self, p) -> p.get(0).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.MultiBound", """
            package test;
            public class MultiBound<T extends Object & Comparable<T>> {
                public T pick(T value) { return value; }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
    }

    // A member type Outer$Inner implementing Function<String, String> gets its bridge
    @Test
    void bridgeForAMemberType() throws Exception {
        ClassDef outer = ClassDef.builder("example.BridgeOuter").addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
                .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class).returns(String.class)
                    .build((self, p) -> ExpressionDef.constant("inner:").stringConcat(p.get(0)).returning()))
                .build())
            .build();
        var loader = load(outer);
        Class<?> inner = loader.loadClass("example.BridgeOuter$Inner");

        assertTrue(methods(inner).contains("bridge synthetic java.lang.Object apply(java.lang.Object)"), methods(inner).toString());
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) inner.getConstructor().newInstance();
        assertEquals("inner:x", function.apply("x"));
    }

    // An enum implementing Function<String, String> gets its bridge and no duplicate
    @Test
    void bridgeForAnEnum() throws Exception {
        EnumDef def = EnumDef.builder("test.Mode").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addEnumConstant("UPPER")
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(method(String.class, "toUpperCase")).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());

        List<String> applies = methods(generated).stream().filter(method -> method.contains(" apply(")).toList();
        assertEquals(List.of("bridge synthetic java.lang.Object apply(java.lang.Object)", "java.lang.String apply(java.lang.String)"), applies);
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) generated.getEnumConstants()[0];
        assertEquals("A", function.apply("a"));
    }

    // A record implementing Comparable<Self> with compareTo(Self) gets the bridge compareTo(Object)
    @Test
    void bridgeForARecordImplementingComparable() throws Exception {
        ClassTypeDef self = ClassTypeDef.of("test.Pair");
        RecordDef def = RecordDef.builder("test.Pair").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(int.class).build())
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), self))
            .addMethod(MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC).overrides().addParameter("other", self).returns(int.class)
                .build((aThis, p) -> ClassTypeDef.of(Integer.class).invokeStatic(method(Integer.class, "compare", int.class, int.class),
                    aThis.invoke(MethodDef.builder("value").returns(int.class).build()),
                    p.get(0).invoke(MethodDef.builder("value").returns(int.class).build())).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.Pair", """
            package test;
            public record Pair(int value) implements Comparable<Pair> {
                @Override public int compareTo(Pair other) { return Integer.compare(value, other.value); }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        @SuppressWarnings("unchecked")
        Comparable<Object> two = (Comparable<Object>) generated.getConstructor(int.class).newInstance(2);
        assertEquals(1, two.compareTo(generated.getConstructor(int.class).newInstance(1)));
    }

    // A raw supertype: String get() over the raw RawParent<T> needs the covariant bridge Object get()
    @Test
    void bridgeForACovariantReturnOverARawSupertype() throws Exception {
        ClassDef def = ClassDef.builder("test.RawChild").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(RawParent.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
                .build((self, p) -> ExpressionDef.constant("raw").returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.RawChild", """
            package test;
            @SuppressWarnings("rawtypes")
            public class RawChild extends io.micronaut.sourcegen.bytecode.ReviewBytecodeTest.RawParent {
                @Override public String get() { return "raw"; }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        assertEquals("raw", ((RawParent<?>) generated.getConstructor().newInstance()).get());
    }

    // A deep hierarchy: Base<T> { T id(T) }, model Middle<U> extends Base<List<U>>, Leaf extends Middle<String> with
    // List id(List): the bridge id(Object) is written in Leaf
    @Test
    void bridgeThroughADeepHierarchy() throws Exception {
        TypeDef.TypeVariable u = TypeDef.variable("U");
        ClassDef middle = ClassDef.builder("test.Middle").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(u)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(Base.class), TypeDef.parameterized(ClassTypeDef.of(List.class), u)))
            .build();
        ClassDef leaf = ClassDef.builder("test.Leaf").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(middle.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", List.class).returns(List.class)
                .build((self, p) -> ClassTypeDef.of(List.class).invokeStatic(method(List.class, "of", Object.class), ExpressionDef.constant("leaf")).returning()))
            .build();
        var loader = load(middle, leaf);
        Class<?> generated = loader.loadClass(leaf.getName());

        assertTrue(methods(generated).contains("bridge synthetic java.lang.Object id(java.lang.Object)"), methods(generated).toString());
        assertEquals(List.of(), methods(loader.loadClass(middle.getName())));
        @SuppressWarnings("unchecked")
        Base<Object> base = (Base<Object>) generated.getConstructor().newInstance();
        assertEquals(List.of("leaf"), base.id(List.of("x")));
    }

    // An interface narrowing Function<String, String>.apply: javac writes the bridge as a concrete default method
    @Test
    void bridgeInAnInterfaceMatchesJavac() throws Exception {
        InterfaceDef def = InterfaceDef.builder("test.UpperFunction").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides()
                .addParameter("value", String.class).returns(String.class).build())
            .build();
        ClassDef impl = ClassDef.builder("test.UpperFunctionImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(def.asTypeDef())
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(method(String.class, "toUpperCase")).returning()))
            .build();
        var loader = loadUnchecked(def, impl);
        Class<?> generated = loader.loadClass(def.getName());
        Class<?> compiled = javac("test.UpperFunction", """
            package test;
            public interface UpperFunction extends java.util.function.Function<String, String> {
                @Override String apply(String value);
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        Method bridge = generated.getMethod("apply", Object.class);
        assertTrue(bridge.isBridge() && bridge.isDefault(), bridge.toString());
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) loader.loadClass(impl.getName()).getConstructor().newInstance();
        assertEquals("A", function.apply("a"));
    }

    // A generated generic interface Transformer<T> implemented with Transformer<String>: the bridge as javac writes it
    @Test
    void bridgeForAGeneratedInterfaceMatchesJavac() throws Exception {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        InterfaceDef transformer = InterfaceDef.builder("test.Transformer").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("transform").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", t).returns(t).build())
            .build();
        ClassDef shouter = ClassDef.builder("test.Shouter").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(transformer.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("transform").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(method(String.class, "toUpperCase")).returning()))
            .build();
        var loader = loadUnchecked(transformer, shouter);
        Class<?> generated = loader.loadClass(shouter.getName());
        Class<?> compiled = javac("test.Shouter", """
            package test;
            interface Transformer<T> { T transform(T value); }
            public class Shouter implements Transformer<String> {
                @Override public String transform(String value) { return value.toUpperCase(); }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        Object instance = generated.getConstructor().newInstance();
        assertEquals("A", loader.loadClass(transformer.getName()).getMethod("transform", Object.class).invoke(instance, "a"));
    }

    // A method of the same name and parameters as an inherited one but an unrelated return type hides it in bytecode
    // (javac rejects the source); it is no override, so no bridge casting one type to the other belongs there
    @Test
    void sameNameMethodWithAnUnrelatedReturnTypeGetsNoBridge() throws Exception {
        ClassTypeDef supplierOfInteger = TypeDef.parameterized(Supplier.class, Integer.class);
        ClassDef def = childOf("test.HidingReturn")
            .addMethod(MethodDef.builder("boxed").addModifiers(Modifier.PUBLIC).returns(supplierOfInteger)
                .build((self, p) -> supplierOfInteger.getLambda().implement((aThis, lp) -> ExpressionDef.constant(5).cast(Integer.class).returning()).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());

        assertEquals(List.of("java.util.function.Supplier boxed()"), methods(generated));
        assertEquals(10, ((NamedParent) generated.getConstructor().newInstance()).boxed());
    }

    // An abstract method with a parameter, written by the checking writer the TCK uses: the parameter must not go
    // into a local variable table of a method that has no code
    @Test
    void abstractMethodWithAParameterIsWrittenInCheckMode() throws Exception {
        ClassDef def = ClassDef.builder("test.AbstractTaker").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("take").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", String.class).returns(String.class).build())
            .build();
        Class<?> generated = load(def).loadClass(def.getName());

        assertTrue(java.lang.reflect.Modifier.isAbstract(generated.getMethod("take", String.class).getModifiers()));
    }

    // The same abstract method written without the check: the class loads and the method is abstract
    @Test
    void abstractMethodWithAParameterLoadsWithoutTheCheck() throws Exception {
        ClassDef def = ClassDef.builder("test.AbstractTakerUnchecked").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("take").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", String.class).returns(String.class).build())
            .build();
        Class<?> generated = loadUnchecked(def).loadClass(def.getName());

        assertTrue(java.lang.reflect.Modifier.isAbstract(generated.getMethod("take", String.class).getModifiers()));
    }

    // A bridge the model declares itself (Object apply(Object) next to String apply(String)) is not written twice
    @Test
    void declaredBridgeIsNotDuplicated() throws Exception {
        MethodDef typed = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("typed").returning());
        ClassDef def = ClassDef.builder("test.OwnBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(typed)
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(typed, p.get(0).cast(String.class)).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());

        assertEquals(2, Arrays.stream(generated.getDeclaredMethods()).filter(method -> method.getName().equals("apply")).count());
        @SuppressWarnings("unchecked")
        Function<Object, Object> function = (Function<Object, Object>) generated.getConstructor().newInstance();
        assertEquals("typed", function.apply("x"));
    }

    // A package-private inherited method of another package is not overridden, so not bridged; in the same package it is
    @Test
    void packagePrivateMethodIsBridgedOnlyInItsPackage() throws Exception {
        String samePackage = ReviewBytecodeTest.class.getPackageName();
        Function<String, ClassDef> definition = name -> ClassDef.builder(name).addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(PkgParent.class, String.class))
            .addMethod(MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .build();
        String source = """
            package %s;
            public class %s extends io.micronaut.sourcegen.bytecode.ReviewBytecodeTest.PkgParent<String> {
                public String take(String value) { return "child"; }
            }
            """;
        ClassDef otherPackage = definition.apply("test.OtherPackageTaker");
        ClassDef ownPackage = definition.apply(samePackage + ".OwnPackageTaker");

        assertEquals(methods(javac("test.OtherPackageTaker", source.formatted("test", "OtherPackageTaker"))),
            methods(load(otherPackage).loadClass(otherPackage.getName())));
        List<String> own = methods(javac(samePackage + ".OwnPackageTaker", source.formatted(samePackage, "OwnPackageTaker")));
        assertTrue(own.contains("bridge synthetic java.lang.String take(java.lang.Object)"), own.toString());
        assertEquals(own, methods(load(ownPackage).loadClass(ownPackage.getName())));
    }

    // A final inherited method is not overridden by keep(String), so no bridge, as javac
    @Test
    void finalInheritedMethodIsNotBridged() throws Exception {
        ClassDef def = ClassDef.builder("test.Keeper").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(FinalParent.class))
            .addMethod(MethodDef.builder("keep").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        Class<?> compiled = javac("test.Keeper", """
            package test;
            public class Keeper extends io.micronaut.sourcegen.bytecode.ReviewBytecodeTest.FinalParent {
                public String keep(String value) { return value; }
            }
            """);

        assertEquals(methods(compiled), methods(load(def).loadClass(def.getName())));
    }

    // A generic inherited method `<U extends Number> T echo(T, U)` of a Parent<String>: the override echo(String, Number)
    // is bridged as echo(Object, Number), as javac
    @Test
    void bridgeForAGenericMethodWithItsOwnVariableMatchesJavac() throws Exception {
        ClassDef def = ClassDef.builder("test.Echoer").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(GenericMethodParent.class, String.class))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", String.class).addParameter("count", Number.class).returns(String.class)
                .build((self, p) -> p.get(0).stringConcat(p.get(1)).returning()))
            .build();
        Class<?> generated = load(def).loadClass(def.getName());
        Class<?> compiled = javac("test.Echoer", """
            package test;
            public class Echoer extends io.micronaut.sourcegen.bytecode.ReviewBytecodeTest.GenericMethodParent<String> {
                @Override public String echo(String value, Number count) { return value + count; }
            }
            """);

        assertEquals(methods(compiled), methods(generated));
        @SuppressWarnings("unchecked")
        GenericMethodParent<String> parent = (GenericMethodParent<String>) generated.getConstructor().newInstance();
        assertEquals("a1", parent.echo("a", 1));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Binary names
    // ---------------------------------------------------------------------------------------------------------------

    // A simple name resolves to the member type of the class being written; a qualified top-level name of the same
    // simple name stays what it is
    @Test
    void simpleNameResolvesToTheMemberTypeAndAQualifiedNameStays() throws Exception {
        ClassDef topLevel = ClassDef.builder("example.Inner").addModifiers(Modifier.PUBLIC).build();
        ClassDef outer = ClassDef.builder("example.NamesOuter").addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .addMethod(MethodDef.builder("member").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("Inner"))
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("topLevel").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("example.Inner"))
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("NamesOuter"))
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        var loader = load(topLevel, outer);
        Class<?> cls = loader.loadClass(outer.getName());

        assertEquals("example.NamesOuter$Inner", cls.getMethod("member").getReturnType().getName());
        assertEquals("example.Inner", cls.getMethod("topLevel").getReturnType().getName());
        assertEquals("example.NamesOuter", cls.getMethod("self").getReturnType().getName());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A superclass whose methods the generated classes refer to through super.
     */
    public static class NamedParent {
        public String name() {
            return "parent";
        }

        protected String greet() {
            return "hello from parent";
        }

        public int count() {
            return 1;
        }

        public Integer boxed() {
            return 10;
        }

        public String fmt(String text, int times) {
            return text.repeat(times);
        }

        public <T> T echo(T value) {
            return value;
        }

        public void record(String value) {
            RECORDED.add("parent:" + value);
        }

        public String join(String... parts) {
            return String.join("+", parts);
        }
    }

    /**
     * An interface with a default method to refer to through {@code Described.super}.
     */
    public interface Described {
        default String describe() {
            return "default";
        }
    }

    /**
     * A generic method whose variable lists Object first: javac erases it to Object.
     */
    public static class MultiBoundParent {
        public <T extends Object & Comparable<T>> Object pick(T value) {
            return "parent:" + value;
        }
    }

    /**
     * A generic parent to inherit raw.
     *
     * @param <T> The type
     */
    public static class RawParent<T> {
        public T get() {
            return null;
        }
    }

    /**
     * The root of a deep hierarchy.
     *
     * @param <T> The type
     */
    public static class Base<T> {
        public T id(T value) {
            return value;
        }
    }

    /**
     * A parent with a package-private generic method.
     *
     * @param <T> The type
     */
    public static class PkgParent<T> {
        String take(T value) {
            return "parent";
        }
    }

    /**
     * A parent with a final method.
     */
    public static class FinalParent {
        public final Object keep(Object value) {
            return value;
        }
    }

    /**
     * A parent with a generic method declaring a variable of its own.
     *
     * @param <T> The type
     */
    public static class GenericMethodParent<T> {
        public <U extends Number> T echo(T value, U count) {
            return value;
        }
    }

    /**
     * A function counting its calls.
     */
    public static class CountingFunction implements Function<Object, Object> {
        public static int calls;

        @Override
        public Object apply(Object value) {
            calls++;
            return value;
        }
    }

    private static Method apply() {
        return method(Function.class, "apply", Object.class);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClassDef.ClassDefBuilder childOf(String name) {
        return ClassDef.builder(name).addModifiers(Modifier.PUBLIC).superclass(PARENT);
    }

    private static MethodDef override(String name, String value) {
        return MethodDef.builder(name).addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
            .build((self, p) -> ExpressionDef.constant(value).returning());
    }

    private static void assertNpe(org.junit.jupiter.api.function.Executable executable) {
        InvocationTargetException error = assertThrows(InvocationTargetException.class, executable);
        assertInstanceOf(NullPointerException.class, error.getCause(), () -> String.valueOf(error.getCause()));
    }

    private static byte[] write(ObjectDef definition) {
        return new ByteCodeWriter(true, true).write(definition);
    }

    private static MapClassLoader load(ObjectDef... definitions) {
        return load(new ByteCodeWriter(true, true), definitions);
    }

    /**
     * Loads through a writer that does not check the classes it produces, for definitions the check rejects although
     * the class it writes is fine (see {@link #abstractMethodWithAParameterIsWrittenInCheckMode()}).
     */
    private static MapClassLoader loadUnchecked(ObjectDef... definitions) {
        return load(new ByteCodeWriter(false, true), definitions);
    }

    private static MapClassLoader load(ByteCodeWriter writer, ObjectDef... definitions) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (ObjectDef definition : definitions) {
            classes.put(definition.getName(), writer.write(definition));
            for (ObjectDef inner : definition.getInnerTypes()) {
                classes.put(inner.getName(), writer.write(inner, definition.asTypeDef()));
            }
        }
        return new MapClassLoader(classes);
    }

    private static String trace(byte[] bytes) {
        StringWriter writer = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(writer)), 0);
        return writer.toString();
    }

    /**
     * The declared methods of a class, as javac and the writers must agree on them: the lambda bodies and the
     * enum values holder are left out, as their names are the compiler's.
     */
    static List<String> methods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> !method.getName().startsWith("lambda$") && !method.getName().equals("$values"))
            .map(method -> (method.isBridge() ? "bridge " : "") + (method.isSynthetic() ? "synthetic " : "")
                + method.getReturnType().getName() + " " + method.getName()
                + Arrays.stream(method.getParameterTypes()).map(Class::getName).toList().toString().replace('[', '(').replace(']', ')'))
            .sorted()
            .toList();
    }

    /**
     * Compiles a source with javac against the test classpath and loads the class it declares.
     */
    static Class<?> javac(String className, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path out = Files.createTempDirectory("review-javac");
        StringWriter log = new StringWriter();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            boolean ok = compiler.getTask(log, fileManager, null,
                List.of("-d", out.toString(), "-classpath", System.getProperty("java.class.path"), "-proc:none", "-Xlint:none"),
                null, List.of(unit)).call();
            assertTrue(ok, log::toString);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                classes.put(name, Files.readAllBytes(file));
            }
        }
        return new MapClassLoader(classes).loadClass(className);
    }

    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private MapClassLoader(Map<String, byte[]> classes) {
            super(ReviewBytecodeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
