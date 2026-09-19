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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes for method references and lambdas in the Java generator: each shape is a model the bytecode writer
 * accepts, rendered, compiled and run, and the behaviour asserted is the one the bytecode would have.
 *
 * @since 2.2.2
 */
public class ReviewReferencesTest {

    private static final ClassTypeDef OBJECT_FUNCTION = TypeDef.parameterized(Function.class, Object.class, Object.class);
    private static final ClassTypeDef STRING_FUNCTION = TypeDef.parameterized(Function.class, String.class, String.class);
    private static final ClassTypeDef STRING_SUPPLIER = TypeDef.parameterized(Supplier.class, String.class);

    /** `Object apply(Object)` of a `Function<String, String>`, written as `String apply(String)`. */
    private static MethodDef narrowedApply() {
        return MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(Object.class).build((self, p) -> p.getFirst().returning());
    }

    private static ClassDef target(String name, MethodDef apply) {
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC)
            .addSuperinterface(STRING_FUNCTION).addMethod(apply).build();
    }

    @SuppressWarnings("unchecked")
    private static Object applyFn(Object function, Object value) {
        return ((Function<Object, Object>) function).apply(value);
    }

    // ------------------------------------------------------------ static and constructor references

    @Test
    void staticReferenceToJdkOverloadKeepsTheMethodOfTheModel() throws Exception {
        // The model references `String.valueOf(Object)` for a `Function<char[], String>`; `String::valueOf` in the
        // source resolves to the more specific `valueOf(char[])`, which writes the characters instead of `[C@...`
        var function = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.Primitive.CHAR.array(), TypeDef.STRING);
        var valueOf = MethodDef.of(String.class.getMethod("valueOf", Object.class));
        var def = ClassDef.builder("test.R1").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(function)
                .build((s, p) -> function.staticMethodReference(TypeDef.STRING, valueOf).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var result = (String) applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), new char[]{'a', 'b'});
            assertTrue(result.startsWith("[C@"), "valueOf(Object) of the model, not valueOf(char[]): " + result + "\n" + render(def));
        }
    }

    @Test
    void staticReferenceToGeneratedOverloadKeepsTheMethodOfTheModel() throws Exception {
        // As referenceToLessSpecificOverload, for a static method: `R2::pick` for a Function<String, Object> resolves
        // to pick(String) where the model references pick(Object)
        var owner = ClassTypeDef.of("test.R2");
        var pickObject = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", Object.class).returns(Object.class).build((self, p) -> ExpressionDef.constant("object").returning());
        var pickString = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", String.class).returns(Object.class).build((self, p) -> ExpressionDef.constant("string").returning());
        var functional = TypeDef.parameterized(Function.class, String.class, Object.class);
        var def = ClassDef.builder("test.R2").addModifiers(Modifier.PUBLIC).addMethod(pickObject).addMethod(pickString)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.staticMethodReference(owner, pickObject).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("object", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"), render(def));
        }
    }

    @Test
    void constructorReferenceToOverloadedConstructorKeepsTheConstructorOfTheModel() throws Exception {
        // `R3Target::new` for a Function<String, R3Target> resolves to R3Target(String) where the model references
        // R3Target(Object)
        var kind = FieldDef.builder("kind", String.class).addModifiers(Modifier.PUBLIC).build();
        var objectConstructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", Object.class)
            .build((self, p) -> self.field(kind).put(ExpressionDef.constant("object")));
        var stringConstructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", String.class)
            .build((self, p) -> self.field(kind).put(ExpressionDef.constant("string")));
        var target = ClassDef.builder("test.R3Target").addModifiers(Modifier.PUBLIC).addField(kind)
            .addMethod(objectConstructor).addMethod(stringConstructor).build();
        var functional = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING, target.asTypeDef());
        var def = ClassDef.builder("test.R3").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.constructorReference(target.asTypeDef(), objectConstructor).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var created = applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x");
            assertEquals("object", created.getClass().getField("kind").get(created), render(def));
        }
    }

    @Test
    void staticReferencesToCompiledMethodsWithPrimitives() throws Exception {
        // `Integer::parseInt` as a ToIntFunction<String>, `String::valueOf` as an IntFunction<String>,
        // `Objects::toString` as a Function<Object, String>
        var toInt = TypeDef.parameterized(ToIntFunction.class, String.class);
        var ofInt = TypeDef.parameterized(IntFunction.class, String.class);
        var toText = TypeDef.parameterized(Function.class, Object.class, String.class);
        var parseInt = MethodDef.of(Integer.class.getMethod("parseInt", String.class));
        var valueOfInt = MethodDef.of(String.class.getMethod("valueOf", int.class));
        var objectsToString = MethodDef.of(Objects.class.getMethod("toString", Object.class));
        var def = ClassDef.builder("test.R4").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("parse").addModifiers(Modifier.PUBLIC).returns(toInt)
                .build((s, p) -> toInt.staticMethodReference(ClassTypeDef.of(Integer.class), parseInt).returning()))
            .addMethod(MethodDef.builder("format").addModifiers(Modifier.PUBLIC).returns(ofInt)
                .build((s, p) -> ofInt.staticMethodReference(TypeDef.STRING, valueOfInt).returning()))
            .addMethod(MethodDef.builder("text").addModifiers(Modifier.PUBLIC).returns(toText)
                .build((s, p) -> toText.staticMethodReference(ClassTypeDef.of(Objects.class), objectsToString).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(42, ((ToIntFunction<String>) cls.getMethod("parse").invoke(instance)).applyAsInt("42"));
            assertEquals("7", ((IntFunction<?>) cls.getMethod("format").invoke(instance)).apply(7));
            assertEquals("null", applyFn(cls.getMethod("text").invoke(instance), null));
        }
    }

    @Test
    void staticReferenceToVarargsMethod() throws Exception {
        // `String::format` as a BiFunction<String, Object[], String>
        var functional = TypeDef.parameterized(ClassTypeDef.of(BiFunction.class), TypeDef.STRING, TypeDef.OBJECT.array(), TypeDef.STRING);
        var format = MethodDef.of(String.class.getMethod("format", String.class, Object[].class));
        var def = ClassDef.builder("test.R5").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.staticMethodReference(TypeDef.STRING, format).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var reference = (BiFunction<String, Object[], String>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
            assertEquals("a-b", reference.apply("%s-%s", new Object[]{"a", "b"}));
        }
    }

    @Test
    void staticReferenceToMethodNamedWithADollar() throws Exception {
        // `R6::apply$x`
        var owner = ClassTypeDef.of("test.R6");
        var method = MethodDef.builder("apply$x").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", String.class).returns(String.class).build((self, p) -> p.getFirst().invoke("trim", TypeDef.STRING).returning());
        var def = ClassDef.builder("test.R6").addModifiers(Modifier.PUBLIC).addMethod(method)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.staticMethodReference(owner, method).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), " x "));
        }
    }

    // ------------------------------------------------------------------------------- receivers

    @Test
    void superReceiverOfAMethodTheChildOverrides() throws Exception {
        // `super::describe` as a Supplier<String> calls the parent's method, not the override
        var parentDescribe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("parent").returning());
        var parent = ClassDef.builder("test.R7Parent").addModifiers(Modifier.PUBLIC).addMethod(parentDescribe).build();
        var childDescribe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
            .build((self, p) -> ExpressionDef.constant("child").returning());
        var def = ClassDef.builder("test.R7").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef()).addMethod(childDescribe)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(STRING_SUPPLIER)
                .build((s, p) -> STRING_SUPPLIER.methodReference(s.superRef(parent.asTypeDef()), parentDescribe).returning())).build();
        try (var loader = compile(parent, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("parent", ((Supplier<?>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance())).get());
        }
    }

    @Test
    void castReceiverOfANarrowedMethod() throws Exception {
        // `((R8Target) value)::apply` where apply is narrowed: the cast receiver is read once into the Optional
        var apply = narrowedApply();
        var target = target("R8Target", apply);
        var def = ClassDef.builder("test.R8").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst().cast(target.asTypeDef()), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", Object.class).invoke(cls.getConstructor().newInstance(),
                targetClass.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void conditionalReceiverOfANarrowedMethod() throws Exception {
        // `(flag ? this.first : this.second)::apply` where apply is narrowed
        var label = FieldDef.builder("label", String.class).addModifiers(Modifier.PRIVATE).build();
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> self.field(label).returning());
        var target = ClassDef.builder("test.R9Target").addModifiers(Modifier.PUBLIC).addField(label).addAllFieldsConstructor(Modifier.PUBLIC)
            .addSuperinterface(STRING_FUNCTION).addMethod(apply).build();
        var first = FieldDef.builder("first", target.asTypeDef()).addModifiers(Modifier.PRIVATE)
            .initializer(target.asTypeDef().instantiate(ExpressionDef.constant("first"))).build();
        var second = FieldDef.builder("second", target.asTypeDef()).addModifiers(Modifier.PRIVATE)
            .initializer(target.asTypeDef().instantiate(ExpressionDef.constant("second"))).build();
        var def = ClassDef.builder("test.R9").addModifiers(Modifier.PUBLIC).addField(first).addField(second)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(
                    p.getFirst().isTrue().doIfElse(s.field(first), s.field(second)), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("first", applyFn(cls.getMethod("reference", boolean.class).invoke(instance, true), "x"));
            assertEquals("second", applyFn(cls.getMethod("reference", boolean.class).invoke(instance, false), "x"));
        }
    }

    @Test
    void nullFieldReceiverOfANarrowedMethodFailsWhereTheReferenceIsCreated() throws Exception {
        // As `invokedynamic` with a bound receiver, and the bytecode writer's requireNonNull: the NPE is thrown by
        // `reference()`, not by the later `apply`
        var apply = narrowedApply();
        var target = target("R10Target", apply);
        var field = FieldDef.builder("target", target.asTypeDef()).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.R10").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s.field(field), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var error = assertThrows(InvocationTargetException.class, () -> cls.getMethod("reference").invoke(cls.getConstructor().newInstance()));
            assertInstanceOf(NullPointerException.class, error.getCause());
        }
    }

    @Test
    void nullReceiverOfACompiledMethodFailsWhereTheReferenceIsCreated() throws Exception {
        // `((String) null)::concat`, not adapted: Java and the bytecode writer both fail at creation
        var concat = MethodDef.of(String.class.getMethod("concat", String.class));
        var def = ClassDef.builder("test.R11").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("text", String.class).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(p.getFirst(), concat).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var error = assertThrows(InvocationTargetException.class, () -> cls.getMethod("reference", String.class).invoke(cls.getConstructor().newInstance(), (Object) null));
            assertInstanceOf(NullPointerException.class, error.getCause());
            assertEquals("ab", applyFn(cls.getMethod("reference", String.class).invoke(cls.getConstructor().newInstance(), "a"), "b"));
        }
    }

    @Test
    void referenceToAMethodOfAnotherGeneratedClassThatIsNotNarrowed() throws Exception {
        // `target::helper` where `String helper(String)` is written as declared
        var helper = MethodDef.builder("helper").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("toUpperCase", TypeDef.STRING).returning());
        var target = ClassDef.builder("test.R12Target").addModifiers(Modifier.PUBLIC).addMethod(helper).build();
        var def = ClassDef.builder("test.R12").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(p.getFirst(), helper).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("X", applyFn(cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(),
                targetClass.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceThroughAParameterizedGeneratedReceiver() throws Exception {
        // `GenericTarget<T> implements Function<T, T>` with the erased `Object apply(Object)`, written `T apply(T)`;
        // `target::apply` on a `GenericTarget<String>` for a Function<Object, Object> converts its argument
        var t = TypeDef.variable("T");
        var apply = narrowedApply();
        var target = ClassDef.builder("test.R13Target").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), t, t)).addMethod(apply).build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.STRING);
        var def = ClassDef.builder("test.R13").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", receiver).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(),
                targetClass.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceThroughARawGeneratedReceiver() throws Exception {
        // The same on a raw `GenericTarget`: `apply` is the erased apply(Object), which the reference names as is
        var t = TypeDef.variable("T");
        var apply = narrowedApply();
        var target = ClassDef.builder("test.R14Target").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), t, t)).addMethod(apply).build();
        var def = ClassDef.builder("test.R14").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(),
                targetClass.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceToANarrowedMethodInsideADefaultMethod() throws Exception {
        // Within the interface, `this::apply` names its own narrowed `apply(String)`
        var apply = MethodDef.override(Function.class.getMethod("apply", Object.class)).addModifiers(Modifier.DEFAULT)
            .build((self, p) -> p.getFirst().returning());
        var interfaceDef = InterfaceDef.builder("test.R15Fn").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("asObjectFunction").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s, apply).returning())).build();
        var def = ClassDef.builder("test.R15").addModifiers(Modifier.PUBLIC).addSuperinterface(interfaceDef.asTypeDef()).build();
        try (var loader = compile(interfaceDef, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("asObjectFunction").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void adaptedReferenceAsTheReceiverOfACall() throws Exception {
        // `(this::apply).apply("x")` where the reference is written as a lambda: it needs a target type
        var apply = narrowedApply();
        var def = ClassDef.builder("test.R16").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s, apply).invoke("apply", TypeDef.OBJECT, ExpressionDef.constant("x")).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void adaptedReferenceInsideALambdaWhoseParameterIsNamedTarget() throws Exception {
        // The lambda parameter `target` is the receiver; the name the adaptation allocates avoids it
        var apply = narrowedApply();
        var target = target("R17Target", apply);
        var outer = TypeDef.parameterized(ClassTypeDef.of(Function.class), target.asTypeDef(), OBJECT_FUNCTION);
        var def = ClassDef.builder("test.R17").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("factory").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((s, p) -> outer.getLambda().implement(List.of("target"),
                    (ls, lp) -> OBJECT_FUNCTION.methodReference(lp.getFirst(), apply).returning()).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            var factory = cls.getMethod("factory").invoke(cls.getConstructor().newInstance());
            assertEquals("x", applyFn(applyFn(factory, targetClass.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void boundReferenceToACompiledOverloadTakingAPrimitive() throws Exception {
        // The model references `List.remove(Object)` for an IntFunction<Object>; `list::remove` resolves to
        // `remove(int)` in the source, which removes by index. The bytecode boxes and removes the element
        var list = TypeDef.parameterized(List.class, Integer.class);
        var remove = MethodDef.of(List.class.getMethod("remove", Object.class));
        var functional = TypeDef.parameterized(IntFunction.class, Object.class);
        var def = ClassDef.builder("test.R18").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("list", list).returns(functional)
                .build((s, p) -> functional.methodReference(p.getFirst(), remove).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var values = new ArrayList<>(List.of(5, 6));
            var reference = (IntFunction<?>) cls.getMethod("reference", List.class).invoke(cls.getConstructor().newInstance(), values);
            assertEquals(Boolean.TRUE, reference.apply(5));
            assertEquals(List.of(6), values);
        }
    }

    @Test
    void boundReferenceToAMethodNamedWithADollar() throws Exception {
        // `target::apply$1`
        var method = MethodDef.builder("apply$1").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("trim", TypeDef.STRING).returning());
        var target = ClassDef.builder("test.R19Target").addModifiers(Modifier.PUBLIC).addMethod(method).build();
        var def = ClassDef.builder("test.R19").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(p.getFirst(), method).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(),
                targetClass.getConstructor().newInstance()), " x "));
        }
    }

    // ---------------------------------------------------------------- functional interface shapes

    @Test
    void consumerOfANarrowedValueReturningMethod() throws Exception {
        // `this::apply` as a Consumer<Object>: the adapted lambda discards the result
        var seen = FieldDef.builder("seen", String.class).addModifiers(Modifier.PUBLIC).build();
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> StatementDef.multi(self.field(seen).put(p.getFirst().cast(TypeDef.STRING)), p.getFirst().returning()));
        var consumer = TypeDef.parameterized(Consumer.class, Object.class);
        var def = ClassDef.builder("test.R20").addModifiers(Modifier.PUBLIC).addField(seen).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(consumer)
                .build((s, p) -> consumer.methodReference(s, apply).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            @SuppressWarnings("unchecked") var reference = (Consumer<Object>) cls.getMethod("reference").invoke(instance);
            reference.accept("x");
            assertEquals("x", cls.getField("seen").get(instance));
        }
    }

    @Test
    void wildcardParameterizedFunctionTarget() throws Exception {
        // `this::apply` as a Function<? super String, ? extends Object>, where apply is narrowed
        var apply = narrowedApply();
        var functional = TypeDef.parameterized(ClassTypeDef.of(Function.class),
            TypeDef.wildcardSupertypeOf(TypeDef.STRING), TypeDef.wildcardSubtypeOf(TypeDef.OBJECT));
        var def = ClassDef.builder("test.R21").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.methodReference(s, apply).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceAndLambdaAsArgumentsOfAGenericIdentityMethod() throws Exception {
        // `<T> T id(T)`: an argument that is a lambda or a reference infers no T without a cast
        var t = TypeDef.variable("T");
        var id = MethodDef.builder("id").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var apply = narrowedApply();
        var def = ClassDef.builder("test.R22").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply).addMethod(id)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> s.invoke(id, OBJECT_FUNCTION.methodReference(s, apply)).returning()))
            .addMethod(MethodDef.builder("lambda").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> s.invoke(id, STRING_SUPPLIER.getLambda().implement((ls, lp) -> ExpressionDef.constant("held").returning())).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("x", applyFn(cls.getMethod("reference").invoke(instance), "x"));
            assertEquals("held", ((Supplier<?>) cls.getMethod("lambda").invoke(instance)).get());
        }
    }

    @Test
    void lambdaWithAVoidCompatibleBodyPassedToAMethodOverloadedOnFunctionalInterfaces() throws Exception {
        // `take((arg0) -> arg0.toString())` is compatible with both take(Function) and take(Consumer): the model's
        // Function lambda needs its target type
        var consumer = TypeDef.parameterized(Consumer.class, Object.class);
        var takeFunction = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("f", OBJECT_FUNCTION).returns(String.class)
            .build((s, p) -> ExpressionDef.constant("function").returning());
        var takeConsumer = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("f", consumer).returns(String.class)
            .build((s, p) -> ExpressionDef.constant("consumer").returning());
        var def = ClassDef.builder("test.R23").addModifiers(Modifier.PUBLIC).addMethod(takeFunction).addMethod(takeConsumer)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> s.invoke(takeFunction, OBJECT_FUNCTION.getLambda().implement(
                    (ls, lp) -> lp.getFirst().invoke("toString", TypeDef.STRING).returning())).returning()))
            .addMethod(MethodDef.builder("callConsumer").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> s.invoke(takeConsumer, consumer.getLambda().implement(
                    (ls, lp) -> lp.getFirst().invoke("toString", TypeDef.STRING))).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("function", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            assertEquals("consumer", cls.getMethod("callConsumer").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void supplierOfAPrimitiveArrayReference() throws Exception {
        // `this::values` as a Supplier<int[]>, and as a Supplier<Object>
        var values = MethodDef.builder("values").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT.array())
            .build((self, p) -> TypeDef.Primitive.INT.array().instantiate(ExpressionDef.constant(1), ExpressionDef.constant(2)).returning());
        var typed = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.Primitive.INT.array());
        var untyped = TypeDef.parameterized(Supplier.class, Object.class);
        var def = ClassDef.builder("test.R24").addModifiers(Modifier.PUBLIC).addMethod(values)
            .addMethod(MethodDef.builder("typed").addModifiers(Modifier.PUBLIC).returns(typed)
                .build((s, p) -> typed.methodReference(s, values).returning()))
            .addMethod(MethodDef.builder("untyped").addModifiers(Modifier.PUBLIC).returns(untyped)
                .build((s, p) -> untyped.methodReference(s, values).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(2, ((int[]) ((Supplier<?>) cls.getMethod("typed").invoke(instance)).get()).length);
            assertEquals(2, ((int[]) ((Supplier<?>) cls.getMethod("untyped").invoke(instance)).get()).length);
        }
    }

    @Test
    void lambdasOfZeroOneAndTwoParameters() throws Exception {
        // Runnable, Predicate<String> and BiFunction<String, String, String>
        var count = FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant(0)).build();
        var runnable = ClassTypeDef.of(Runnable.class);
        var predicate = TypeDef.parameterized(Predicate.class, String.class);
        var biFunction = TypeDef.parameterized(BiFunction.class, String.class, String.class, String.class);
        var def = ClassDef.builder("test.R25").addModifiers(Modifier.PUBLIC).addField(count)
            .addMethod(MethodDef.builder("runnable").addModifiers(Modifier.PUBLIC).returns(runnable)
                .build((s, p) -> runnable.getLambda().implement((ls, lp) -> s.field(count).put(
                    s.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1)))).returning()))
            .addMethod(MethodDef.builder("predicate").addModifiers(Modifier.PUBLIC).returns(predicate)
                .build((s, p) -> predicate.getLambda().implement((ls, lp) -> lp.getFirst().invoke("isEmpty", TypeDef.Primitive.BOOLEAN).returning()).returning()))
            .addMethod(MethodDef.builder("biFunction").addModifiers(Modifier.PUBLIC).returns(biFunction)
                .build((s, p) -> biFunction.getLambda().implement((ls, lp) -> lp.get(0).invoke("concat", TypeDef.STRING, lp.get(1)).returning()).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            ((Runnable) cls.getMethod("runnable").invoke(instance)).run();
            assertEquals(1, cls.getField("count").get(instance));
            @SuppressWarnings("unchecked") var predicateValue = (Predicate<String>) cls.getMethod("predicate").invoke(instance);
            assertTrue(predicateValue.test(""));
            @SuppressWarnings("unchecked") var biFunctionValue = (BiFunction<String, String, String>) cls.getMethod("biFunction").invoke(instance);
            assertEquals("ab", biFunctionValue.apply("a", "b"));
        }
    }

    @Test
    void referenceForAGeneratedFunctionalInterfaceWithADefaultMethod() throws Exception {
        // `interface Fn { String apply(String); default String twice(String) }` implemented by `this::helper`
        var fnType = ClassTypeDef.of("test.R26Fn");
        var abstractApply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", String.class).returns(String.class).build();
        var fn = InterfaceDef.builder("test.R26Fn").addModifiers(Modifier.PUBLIC).addMethod(abstractApply)
            .addMethod(MethodDef.builder("twice").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).addParameter("value", String.class).returns(String.class)
                .build((s, p) -> s.invoke(abstractApply, s.invoke(abstractApply, p.getFirst())).returning())).build();
        var helper = MethodDef.builder("helper").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("concat", TypeDef.STRING, ExpressionDef.constant("!")).returning());
        var def = ClassDef.builder("test.R26").addModifiers(Modifier.PUBLIC).addMethod(helper)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(fn.asTypeDef())
                .build((s, p) -> fn.asTypeDef().methodReference(s, helper).returning()))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> s.invoke("reference", fnType).invoke("twice", TypeDef.STRING, ExpressionDef.constant("x")).returning())).build();
        try (var loader = compile(fn, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x!!", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void lambdaReturningAVoidCall() throws Exception {
        // `Runnable r = () -> { return this.run(); }` in the model: the return of a void call is the call
        var count = FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant(0)).build();
        var run = MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(void.class)
            .build((self, p) -> self.field(count).put(self.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))));
        var runnable = ClassTypeDef.of(Runnable.class);
        var def = ClassDef.builder("test.R27").addModifiers(Modifier.PUBLIC).addField(count).addMethod(run)
            .addMethod(MethodDef.builder("runnable").addModifiers(Modifier.PUBLIC).returns(runnable)
                .build((s, p) -> runnable.getLambda().implement((ls, lp) -> s.invoke(run).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            ((Runnable) cls.getMethod("runnable").invoke(instance)).run();
            assertEquals(1, cls.getField("count").get(instance));
        }
    }

    @Disabled("A reference returned where the return type is a type variable bounded by its functional interface needs an unchecked cast to the variable")
    @Test
    void referenceReturnedAsATypeVariableBoundedByItsInterface() throws Exception {
        // `<F extends Function<Object, Object>> F make()` returning `this::apply`: the verifier sees a Function
        var apply = narrowedApply();
        var f = TypeDef.variable("F", OBJECT_FUNCTION);
        var def = ClassDef.builder("test.R28").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC).addTypeVariable(f).returns(f)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s, apply).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("make").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void lambdaInAConditionalReturnedAsAnObject() throws Exception {
        // `return flag ? () -> "a" : null;` in a method returning Object: the lambda has no target type
        var def = ClassDef.builder("test.R29").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class).returns(Object.class)
                .build((s, p) -> p.getFirst().isTrue().doIfElse(
                    STRING_SUPPLIER.getLambda().implement((ls, lp) -> ExpressionDef.constant("a").returning()),
                    ExpressionDef.nullValue()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("a", ((Supplier<?>) cls.getMethod("call", boolean.class).invoke(cls.getConstructor().newInstance(), true)).get());
        }
    }

    // ------------------------------------------------------------------------ lambdas and scope

    @Disabled("A lambda capturing a local that is not effectively final: the bytecode captures the value where the lambda is created, Java needs a copy into a final local")
    @Test
    void lambdaCapturingALocalAssignedTwice() throws Exception {
        // `String x = "a"; x = "b"; Supplier<String> s = () -> x;` - the bytecode captures the value the local has
        // where the lambda is created; Java only captures an effectively final local
        var def = ClassDef.builder("test.R30").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> {
                    var local = new VariableDef.Local("x", TypeDef.STRING);
                    return StatementDef.multi(
                        local.defineAndAssign(ExpressionDef.constant("a")),
                        local.assign(ExpressionDef.constant("b")),
                        STRING_SUPPLIER.getLambda().implement((ls, lp) -> local.returning())
                            .newLocal("supplier", supplier -> supplier.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning()));
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("b", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Disabled("A lambda capturing a local that is not effectively final: the bytecode captures the value where the lambda is created, Java needs a copy into a final local")
    @Test
    void lambdaCapturingALocalAssignedAfterTheLambdaIsCreated() throws Exception {
        // `String x = "a"; Supplier<String> s = () -> x; x = "b"; return s.get();` - the bytecode captured "a"
        var def = ClassDef.builder("test.R31").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> {
                    var local = new VariableDef.Local("x", TypeDef.STRING);
                    var supplier = new VariableDef.Local("supplier", STRING_SUPPLIER);
                    return StatementDef.multi(
                        local.defineAndAssign(ExpressionDef.constant("a")),
                        supplier.defineAndAssign(STRING_SUPPLIER.getLambda().implement((ls, lp) -> local.returning())),
                        local.assign(ExpressionDef.constant("b")),
                        supplier.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning());
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("a", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void lambdaCapturingACatchVariable() throws Exception {
        // A catch parameter is effectively final: `() -> e.getMessage()`
        var def = ClassDef.builder("test.R32").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> StatementDef.multi(
                    StatementDef.doTry(ClassTypeDef.of(IllegalStateException.class).instantiate(ExpressionDef.constant("boom")).doThrow())
                        .doCatch(RuntimeException.class, e -> STRING_SUPPLIER.getLambda()
                            .implement((ls, lp) -> e.invoke("getMessage", TypeDef.STRING).returning())
                            .newLocal("supplier", supplier -> supplier.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning())),
                    ExpressionDef.constant("unreachable").returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("boom", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void lambdaBlockBodyDeclaringALocalNamedLikeAnEnclosingLocal() throws Exception {
        // The lambda body is a method of its own in the bytecode; in the source its `tmp` redeclares the enclosing one
        var def = ClassDef.builder("test.R33").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> {
                    var outer = new VariableDef.Local("tmp", TypeDef.STRING);
                    var inner = new VariableDef.Local("tmp", TypeDef.STRING);
                    return StatementDef.multi(
                        outer.defineAndAssign(ExpressionDef.constant("outer")),
                        STRING_FUNCTION.getLambda().implement((ls, lp) -> StatementDef.multi(
                                inner.defineAndAssign(lp.getFirst().invoke("trim", TypeDef.STRING)),
                                inner.returning()))
                            .newLocal("function", function -> function.invoke("apply", TypeDef.OBJECT, outer).cast(TypeDef.STRING).returning()));
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("outer", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void lambdaParameterShadowingAField() throws Exception {
        // A field may be shadowed: `(value) -> this.value.concat(value)`
        var value = FieldDef.builder("value", String.class).addModifiers(Modifier.PRIVATE).initializer(ExpressionDef.constant("field-")).build();
        var def = ClassDef.builder("test.R34").addModifiers(Modifier.PUBLIC).addField(value)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.getLambda().implement(List.of("value"),
                    (ls, lp) -> s.field(value).invoke("concat", TypeDef.STRING, lp.getFirst()).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("field-x", applyFn(cls.getMethod("function").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void lambdaParametersNamedLikeTheMethodParameters() throws Exception {
        // The lambda's `a` and `b` share their names with the method's parameters: a MethodParameter of that name in
        // the body is the lambda's own, in the bytecode writer (captureVariables seeds the names with the lambda's
        // parameters) as in the source, where they are renamed `a1`, `b1` and every use follows the rename
        var biFunction = TypeDef.parameterized(BiFunction.class, String.class, String.class, String.class);
        var def = ClassDef.builder("test.R35").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).addParameter("a", String.class).addParameter("b", String.class).returns(biFunction)
                .build((s, p) -> biFunction.getLambda().implement(List.of("a", "b"),
                    (ls, lp) -> lp.get(0).invoke("concat", TypeDef.STRING, lp.get(1))
                        .invoke("concat", TypeDef.STRING, p.get(0)).invoke("concat", TypeDef.STRING, p.get(1)).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var function = (BiFunction<String, String, String>) cls.getMethod("function", String.class, String.class)
                .invoke(cls.getConstructor().newInstance(), "C", "D");
            assertEquals("ABAB", function.apply("A", "B"));
        }
    }

    @Test
    void lambdaBlockBodyWithTryLoopAndSwitch() throws Exception {
        // A block body with a loop over a local, a statement switch returning from its cases, and a try whose catch
        // returns; the fallback after them is unreachable
        var def = ClassDef.builder("test.R36").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.getLambda().implement((ls, lp) -> {
                    var counter = new VariableDef.Local("counter", TypeDef.Primitive.INT);
                    var text = new VariableDef.Local("text", TypeDef.STRING);
                    Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
                    cases.put(ExpressionDef.constant("a"), text.invoke("concat", TypeDef.STRING, ExpressionDef.constant("A")).returning());
                    cases.put(ExpressionDef.constant("b"), text.invoke("concat", TypeDef.STRING, ExpressionDef.constant("B")).returning());
                    return StatementDef.multi(
                        counter.defineAndAssign(ExpressionDef.constant(0)),
                        text.defineAndAssign(ExpressionDef.constant("")),
                        counter.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, ExpressionDef.constant(2)).whileLoop(StatementDef.multi(
                            text.assign(text.invoke("concat", TypeDef.STRING, ExpressionDef.constant("."))),
                            counter.assign(counter.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))))),
                        StatementDef.doTry(lp.getFirst().asStatementSwitch(TypeDef.STRING, cases,
                                ClassTypeDef.of(IllegalArgumentException.class).instantiate(lp.getFirst()).doThrow()))
                            .doCatch(IllegalArgumentException.class, e -> text.invoke("concat", TypeDef.STRING, e.invoke("getMessage", TypeDef.STRING)).returning()),
                        ExpressionDef.constant("unreachable").returning());
                }).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var function = cls.getMethod("function").invoke(cls.getConstructor().newInstance());
            assertEquals("..A", applyFn(function, "a"));
            assertEquals("..B", applyFn(function, "b"));
            assertEquals("..z", applyFn(function, "z"));
        }
    }

    @Test
    void lambdaInAStaticInitializer() throws Exception {
        // `static { SUPPLIER = () -> "static"; }`
        var owner = ClassTypeDef.of("test.R37");
        var field = FieldDef.builder("SUPPLIER", STRING_SUPPLIER).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        var def = ClassDef.builder("test.R37").addModifiers(Modifier.PUBLIC).addField(field)
            .addStaticInitializer(owner.getStaticField(field).put(STRING_SUPPLIER.getLambda()
                .implement((ls, lp) -> ExpressionDef.constant("static").returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("static", ((Supplier<?>) cls.getField("SUPPLIER").get(null)).get());
        }
    }

    @Test
    void lambdaInAFieldInitializerAndInAConstructor() throws Exception {
        // `private final Supplier<String> fromField = () -> "field";` and `this.fromConstructor = () -> value;`
        var fromField = FieldDef.builder("fromField", STRING_SUPPLIER).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .initializer(STRING_SUPPLIER.getLambda().implement((ls, lp) -> ExpressionDef.constant("field").returning())).build();
        var fromConstructor = FieldDef.builder("fromConstructor", STRING_SUPPLIER).addModifiers(Modifier.PUBLIC, Modifier.FINAL).build();
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", String.class)
            .build((self, p) -> self.field(fromConstructor).put(STRING_SUPPLIER.getLambda().implement((ls, lp) -> p.getFirst().returning())));
        var def = ClassDef.builder("test.R38").addModifiers(Modifier.PUBLIC).addField(fromField).addField(fromConstructor).addMethod(constructor).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor(String.class).newInstance("ctor");
            assertEquals("field", ((Supplier<?>) cls.getField("fromField").get(instance)).get());
            assertEquals("ctor", ((Supplier<?>) cls.getField("fromConstructor").get(instance)).get());
        }
    }

    @Test
    void lambdaInAGenericMethodUsingItsVariable() throws Exception {
        // `<T> Supplier<T> hold(T value) { return () -> value; }`
        var t = TypeDef.variable("T");
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t);
        var def = ClassDef.builder("test.R39").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("hold").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("value", t).returns(supplier)
                .build((s, p) -> supplier.getLambda().implement((ls, lp) -> p.getFirst().returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("held", ((Supplier<?>) cls.getMethod("hold", Object.class).invoke(cls.getConstructor().newInstance(), "held")).get());
        }
    }

    @Test
    void lambdaAndReferenceOverTheClassTypeVariable() throws Exception {
        // `class Box<T> { Function<T, T> identity() { return (t) -> t; } Supplier<T> supplier() { return this::get; } }`
        var t = TypeDef.variable("T");
        var value = FieldDef.builder("value", t).addModifiers(Modifier.PRIVATE).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(t).build((self, p) -> self.field(value).returning());
        var function = TypeDef.parameterized(ClassTypeDef.of(Function.class), t, t);
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t);
        var def = ClassDef.builder("test.R40").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addField(value).addAllFieldsConstructor(Modifier.PUBLIC).addMethod(get)
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).returns(function)
                .build((s, p) -> function.getLambda().implement((ls, lp) -> lp.getFirst().returning()).returning()))
            .addMethod(MethodDef.builder("supplier").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build((s, p) -> supplier.methodReference(s, get).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor(Object.class).newInstance("boxed");
            assertEquals("x", applyFn(cls.getMethod("identity").invoke(instance), "x"));
            assertEquals("boxed", ((Supplier<?>) cls.getMethod("supplier").invoke(instance)).get());
        }
    }

    @Test
    void lambdaCapturingSuper() throws Exception {
        // `() -> super.describe()` where the child overrides describe
        var parentDescribe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("parent").returning());
        var parent = ClassDef.builder("test.R41Parent").addModifiers(Modifier.PUBLIC).addMethod(parentDescribe).build();
        var childDescribe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
            .build((self, p) -> ExpressionDef.constant("child").returning());
        var def = ClassDef.builder("test.R41").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef()).addMethod(childDescribe)
            .addMethod(MethodDef.builder("supplier").addModifiers(Modifier.PUBLIC).returns(STRING_SUPPLIER)
                .build((s, p) -> STRING_SUPPLIER.getLambda().implement((ls, lp) -> s.superRef(parent.asTypeDef()).invoke(parentDescribe).returning()).returning())).build();
        try (var loader = compile(parent, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("parent", ((Supplier<?>) cls.getMethod("supplier").invoke(cls.getConstructor().newInstance())).get());
        }
    }

    @Test
    void lambdaInsideASwitchExpressionYield() throws Exception {
        // `case 1 -> { Supplier<String> s = () -> "one"; yield s.get(); }`
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
            STRING_SUPPLIER.getLambda().implement((ls, lp) -> ExpressionDef.constant("one").returning())
                .newLocal("supplier", supplier -> supplier.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning())));
        var def = ClassDef.builder("test.R42").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", int.class).returns(String.class)
                .build((s, p) -> p.getFirst().asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("other")).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("one", cls.getMethod("call", int.class).invoke(instance, 1));
            assertEquals("other", cls.getMethod("call", int.class).invoke(instance, 2));
        }
    }

    @Test
    void lambdaParameterNamedWithADollar() throws Exception {
        // `($ctx) -> $ctx.trim()`: the name is written as a `$L` argument, not spliced into the format
        var def = ClassDef.builder("test.R43").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.getLambda().implement(List.of("$ctx"),
                    (ls, lp) -> lp.getFirst().invoke("trim", TypeDef.STRING).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("function").invoke(cls.getConstructor().newInstance()), " x "));
        }
    }

    @Test
    void nestedLambdasCapturingTheOuterParameter() throws Exception {
        // `(a) -> (b) -> a.concat(b)` as a Function<String, Function<String, String>>
        var outer = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING, STRING_FUNCTION);
        var def = ClassDef.builder("test.R44").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((s, p) -> outer.getLambda().implement(List.of("a"), (ls, lp) -> STRING_FUNCTION.getLambda().implement(List.of("b"),
                    (ils, ilp) -> lp.getFirst().invoke("concat", TypeDef.STRING, ilp.getFirst()).returning()).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("ab", applyFn(applyFn(cls.getMethod("function").invoke(cls.getConstructor().newInstance()), "a"), "b"));
        }
    }

    @Test
    void adaptedReferenceBesideParametersNamedLikeItsHelpers() throws Exception {
        // The method's parameters are `arg`, `target`, `it` and `value`; the adapted lambda's names avoid them
        var apply = narrowedApply();
        var target = target("R45Target", apply);
        var def = ClassDef.builder("test.R45").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC)
                .addParameter("arg", String.class).addParameter("target", target.asTypeDef()).addParameter("it", String.class).addParameter("value", String.class)
                .returns(OBJECT_FUNCTION)
                .build((s, p) -> StatementDef.multi(
                    OBJECT_FUNCTION.methodReference(p.get(1), apply).newLocal("other"),
                    OBJECT_FUNCTION.methodReference(s, apply).returning()))).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", String.class, targetClass, String.class, String.class)
                .invoke(cls.getConstructor().newInstance(), "a", targetClass.getConstructor().newInstance(), "i", "v"), "x"));
        }
    }

    @Test
    void referenceReceiverWithSideEffectsIsEvaluatedOnceAndCheckedEagerly() throws Exception {
        // `this.next()::apply` where `next()` counts its calls and returns null the second time: the first reference
        // reads it once, the second fails where it is created
        var apply = narrowedApply();
        var target = target("R46Target", apply);
        var count = FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant(0)).build();
        var next = MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(target.asTypeDef())
            .build((self, p) -> StatementDef.multi(
                self.field(count).put(self.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
                self.field(count).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant(1))
                    .doIfElse(target.asTypeDef().instantiate(), ExpressionDef.nullValue().cast(target.asTypeDef())).returning()));
        var def = ClassDef.builder("test.R46").addModifiers(Modifier.PUBLIC).addField(count).addMethod(next)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s.invoke(next), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var reference = cls.getMethod("reference").invoke(instance);
            assertEquals("x", applyFn(reference, "x"));
            assertEquals("y", applyFn(reference, "y"));
            assertEquals(1, cls.getField("count").get(instance));
            var error = assertThrows(InvocationTargetException.class, () -> cls.getMethod("reference").invoke(instance));
            assertInstanceOf(NullPointerException.class, error.getCause());
            assertEquals(2, cls.getField("count").get(instance));
        }
    }

    @Test
    void referenceStoredInAnObjectLocalAndCastBack() throws Exception {
        // `Object o = this::apply; return ((Function) o).apply("x");`
        var apply = narrowedApply();
        var def = ClassDef.builder("test.R47").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s, apply).cast(TypeDef.OBJECT)
                    .newLocal("o", o -> o.cast(OBJECT_FUNCTION).invoke("apply", TypeDef.OBJECT, ExpressionDef.constant("x")).returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void referenceInAStaticMethodToAStaticMethodOfTheClass() throws Exception {
        // `static Function<String, String> make() { return R48::shout; }`
        var owner = ClassTypeDef.of("test.R48");
        var shout = MethodDef.builder("shout").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("toUpperCase", TypeDef.STRING).returning());
        var def = ClassDef.builder("test.R48").addModifiers(Modifier.PUBLIC).addMethod(shout)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.staticMethodReference(owner, shout).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("X", applyFn(cls.getMethod("make").invoke(null), "x"));
        }
    }

    @Test
    void lambdaInAStaticMethodCapturingItsParameter() throws Exception {
        // `static Supplier<String> hold(String value) { return () -> value; }`
        var def = ClassDef.builder("test.R49").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("hold").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", String.class).returns(STRING_SUPPLIER)
                .build((s, p) -> STRING_SUPPLIER.getLambda().implement((ls, lp) -> p.getFirst().returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("held", ((Supplier<?>) cls.getMethod("hold", String.class).invoke(null, "held")).get());
        }
    }

    @Test
    void adaptedReferenceReturningAPrimitiveThroughItsWrapperFromAnotherClass() throws Exception {
        // `target::apply` as a ToIntFunction<Object> where `Number apply(Number)` is narrowed on another class:
        // the result is unboxed from the Integer the lambda casts to
        var apply = narrowedApply();
        var target = ClassDef.builder("test.R50Target").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, Number.class, Number.class)).addMethod(apply).build();
        var functional = TypeDef.parameterized(ToIntFunction.class, Object.class);
        var def = ClassDef.builder("test.R50").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(functional)
                .build((s, p) -> functional.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var reference = (ToIntFunction<Object>) cls.getMethod("reference", targetClass)
                .invoke(cls.getConstructor().newInstance(), targetClass.getConstructor().newInstance());
            assertEquals(7, reference.applyAsInt(7));
        }
    }

    @Test
    void referenceToAnInstanceMethodOfTheSameClassWhichIsNotNarrowed() throws Exception {
        // `this::helper` as a Function<String, String>, a private method
        var helper = MethodDef.builder("helper").addModifiers(Modifier.PRIVATE).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("toUpperCase", TypeDef.STRING).returning());
        var def = ClassDef.builder("test.R51").addModifiers(Modifier.PUBLIC).addMethod(helper)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(s, helper).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("X", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void lambdaCapturingAnEnclosingLambdaParameterOfARenamedName() throws Exception {
        // The method parameter is `context` and so is the outer lambda's, which is renamed `context1`; the inner
        // lambda names `context` twice, and both resolve to the outer lambda's parameter, as the bytecode writer's
        // capture does (the method's `context` is shadowed in the model, so `lambdalambda` is the value of both)
        var outer = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING, STRING_SUPPLIER);
        var def = ClassDef.builder("test.R52").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).addParameter("context", String.class).returns(outer)
                .build((s, p) -> outer.getLambda().implement(List.of("context"), (ls, lp) -> STRING_SUPPLIER.getLambda()
                    .implement((ils, ilp) -> lp.getFirst().invoke("concat", TypeDef.STRING, p.getFirst()).returning()).returning()).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var function = cls.getMethod("function", String.class).invoke(cls.getConstructor().newInstance(), "method");
            assertEquals("lambdalambda", ((Supplier<?>) applyFn(function, "lambda")).get());
        }
    }

    @Test
    void lambdaCapturingALocalOfALoopBody() throws Exception {
        // A local declared inside the loop body is fresh on each iteration, and effectively final
        var list = TypeDef.parameterized(List.class, Supplier.class);
        var def = ClassDef.builder("test.R53").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(list)
                .build((s, p) -> {
                    var counter = new VariableDef.Local("counter", TypeDef.Primitive.INT);
                    var suppliers = new VariableDef.Local("suppliers", list);
                    var label = new VariableDef.Local("label", TypeDef.STRING);
                    return StatementDef.multi(
                        counter.defineAndAssign(ExpressionDef.constant(0)),
                        suppliers.defineAndAssign(ClassTypeDef.of(ArrayList.class).instantiate()),
                        counter.compare(ExpressionDef.ComparisonOperation.OpType.LESS_THAN, ExpressionDef.constant(2)).whileLoop(StatementDef.multi(
                            label.defineAndAssign(TypeDef.STRING.invokeStatic("valueOf", TypeDef.STRING, List.of(counter))),
                            suppliers.invoke("add", TypeDef.Primitive.BOOLEAN, STRING_SUPPLIER.getLambda().implement((ls, lp) -> label.returning())),
                            counter.assign(counter.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))))),
                        suppliers.returning());
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var suppliers = (List<Supplier<?>>) cls.getMethod("call").invoke(cls.getConstructor().newInstance());
            assertEquals(List.of("0", "1"), suppliers.stream().map(Supplier::get).toList());
        }
    }

    @Test
    void referenceOfTheSameReceiverTwiceReadsItTwice() throws Exception {
        // Two references through `this.next()` in one method read it twice, in order, as the bytecode does
        var apply = narrowedApply();
        var target = target("R54Target", apply);
        var count = FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant(0)).build();
        var next = MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(target.asTypeDef())
            .build((self, p) -> StatementDef.multi(
                self.field(count).put(self.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
                target.asTypeDef().instantiate().returning()));
        var pair = TypeDef.parameterized(List.class, Object.class);
        var def = ClassDef.builder("test.R54").addModifiers(Modifier.PUBLIC).addField(count).addMethod(next)
            .addMethod(MethodDef.builder("references").addModifiers(Modifier.PUBLIC).returns(pair)
                .build((s, p) -> ClassTypeDef.of(List.class).invokeStatic("of", pair, List.of(
                    OBJECT_FUNCTION.methodReference(s.invoke(next), apply), OBJECT_FUNCTION.methodReference(s.invoke(next), apply))).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var references = (List<?>) cls.getMethod("references").invoke(instance);
            assertEquals(2, references.size());
            assertEquals(2, cls.getField("count").get(instance));
            assertEquals("x", applyFn(references.get(1), "x"));
        }
    }

    @Test
    void lambdaCapturingThisInsideAnAdaptedReferenceLambda() throws Exception {
        // The adapted lambda for `target::apply` is created inside another lambda which captures `this`
        var apply = narrowedApply();
        var target = target("R55Target", apply);
        var field = FieldDef.builder("target", target.asTypeDef()).addModifiers(Modifier.PRIVATE).initializer(target.asTypeDef().instantiate()).build();
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), OBJECT_FUNCTION);
        var def = ClassDef.builder("test.R55").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("supplier").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build((s, p) -> supplier.getLambda().implement((ls, lp) -> OBJECT_FUNCTION.methodReference(s.field(field), apply).returning()).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(((Supplier<?>) cls.getMethod("supplier").invoke(cls.getConstructor().newInstance())).get(), "x"));
        }
    }

    @Test
    void sameReferenceIsCreatedFreshOnEachCall() throws Exception {
        // Two calls of the method give distinct references bound to the receiver each call saw
        var apply = narrowedApply();
        var target = target("R56Target", apply);
        var field = FieldDef.builder("target", target.asTypeDef()).addModifiers(Modifier.PUBLIC).build();
        var def = ClassDef.builder("test.R56").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s.field(field), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            cls.getField("target").set(instance, targetClass.getConstructor().newInstance());
            var first = cls.getMethod("reference").invoke(instance);
            var second = cls.getMethod("reference").invoke(instance);
            assertTrue(first != second);
            assertSame("x", applyFn(first, "x"));
        }
    }

    // ---------------------------------------------------------------- second batch

    @Test
    void referenceThroughAParameterizedCompiledReceiverInAStaticMethod() throws Exception {
        // `static Function<Object, Object> reference(Optional<String> optional)` returning `optional::orElse`: the
        // receiver binds `orElse(T)` to a String, which the Function<Object, Object> passes an Object for, so the
        // reference is a lambda casting it, reading the receiver once
        var optional = TypeDef.parameterized(java.util.Optional.class, String.class);
        var orElse = MethodDef.of(java.util.Optional.class.getMethod("orElse", Object.class));
        var def = ClassDef.builder("test.R57").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("optional", optional).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst(), orElse).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference", java.util.Optional.class).invoke(null, java.util.Optional.empty()), "x"));
            assertEquals("v", applyFn(cls.getMethod("reference", java.util.Optional.class).invoke(null, java.util.Optional.of("v")), "x"));
        }
    }

    @Test
    void nestedLambdaCapturingALocalOfTheEnclosingLambda() throws Exception {
        // `(a) -> { String t = a.trim(); return () -> t; }` as a Function<String, Supplier<String>>
        var outer = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING, STRING_SUPPLIER);
        var def = ClassDef.builder("test.R69").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("function").addModifiers(Modifier.PUBLIC).returns(outer)
                .build((s, p) -> outer.getLambda().implement(List.of("a"), (ls, lp) -> lp.getFirst().invoke("trim", TypeDef.STRING)
                    .newLocal("t", t -> STRING_SUPPLIER.getLambda().implement((ils, ilp) -> t.returning()).returning())).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", ((Supplier<?>) applyFn(cls.getMethod("function").invoke(cls.getConstructor().newInstance()), " x ")).get());
        }
    }

    @Test
    void newInstanceAndArrayElementReceivers() throws Exception {
        // `new R58Target()::apply` and `targets[0]::apply`, narrowed, and `new R58Target()::helper`, not narrowed
        var apply = narrowedApply();
        var helper = MethodDef.builder("helper").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> p.getFirst().invoke("toUpperCase", TypeDef.STRING).returning());
        var target = ClassDef.builder("test.R58Target").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply).addMethod(helper).build();
        var def = ClassDef.builder("test.R58").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("created").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(target.asTypeDef().instantiate(), apply).returning()))
            .addMethod(MethodDef.builder("element").addModifiers(Modifier.PUBLIC).addParameter("targets", target.asTypeDef().array()).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst().arrayElement(0), apply).returning()))
            .addMethod(MethodDef.builder("plain").addModifiers(Modifier.PUBLIC).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(target.asTypeDef().instantiate(), helper).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("x", applyFn(cls.getMethod("created").invoke(instance), "x"));
            var targets = java.lang.reflect.Array.newInstance(targetClass, 1);
            java.lang.reflect.Array.set(targets, 0, targetClass.getConstructor().newInstance());
            assertEquals("x", applyFn(cls.getMethod("element", targets.getClass()).invoke(instance, targets), "x"));
            assertEquals("X", applyFn(cls.getMethod("plain").invoke(instance), "x"));
        }
    }

    @Test
    void lambdaAsTheArgumentOfAGenericVarargsMethod() throws Exception {
        // `List.of(() -> "x")` infers `E` from nothing: the lambda needs its target type
        var list = TypeDef.parameterized(List.class, Object.class);
        var def = ClassDef.builder("test.R59").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(list)
                .build((s, p) -> ClassTypeDef.of(List.class).invokeStatic("of", list, List.of(
                    STRING_SUPPLIER.getLambda().implement((ls, lp) -> ExpressionDef.constant("x").returning()))).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var result = (List<?>) cls.getMethod("call").invoke(cls.getConstructor().newInstance());
            assertEquals("x", ((Supplier<?>) result.getFirst()).get());
        }
    }

    @Test
    void referencePassedForAnObjectParameter() throws Exception {
        // `this.take(this::apply)` where take(Object): a reference has no target type there
        var apply = narrowedApply();
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> p.getFirst().returning());
        var concat = MethodDef.of(String.class.getMethod("concat", String.class));
        var def = ClassDef.builder("test.R60").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply).addMethod(take)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> s.invoke(take, OBJECT_FUNCTION.methodReference(s, apply)).returning()))
            .addMethod(MethodDef.builder("callPlain").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> s.invoke(take, STRING_FUNCTION.methodReference(ExpressionDef.constant("a"), concat)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("x", applyFn(cls.getMethod("call").invoke(instance), "x"));
            assertEquals("ax", applyFn(cls.getMethod("callPlain").invoke(instance), "x"));
        }
    }

    @Test
    void generatedFunctionalInterfaceWithPrimitivesOverANarrowedMethod() throws Exception {
        // `interface IntOp { int apply(int value); }` implemented by `this::apply` of a Function<Integer, Integer>
        // written `Integer apply(Integer)`: the int is boxed for the call, the Integer unboxed for the result
        var apply = narrowedApply();
        var intOp = InterfaceDef.builder("test.R61Op").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", int.class).returns(int.class).build()).build();
        var def = ClassDef.builder("test.R61").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, Integer.class, Integer.class)).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(intOp.asTypeDef())
                .build((s, p) -> intOp.asTypeDef().methodReference(s, apply).returning()))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((s, p) -> s.invoke("reference", intOp.asTypeDef()).invoke("apply", TypeDef.Primitive.INT, ExpressionDef.constant(7)).returning())).build();
        try (var loader = compile(intOp, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(7, cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void intFunctionOverANarrowedMethod() throws Exception {
        // `IntFunction<Object>` implemented by `target::apply` written `Integer apply(Integer)`
        var apply = narrowedApply();
        var target = ClassDef.builder("test.R62Target").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, Integer.class, Integer.class)).addMethod(apply).build();
        var functional = TypeDef.parameterized(IntFunction.class, Object.class);
        var def = ClassDef.builder("test.R62").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(functional)
                .build((s, p) -> functional.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            var reference = (IntFunction<?>) cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(), targetClass.getConstructor().newInstance());
            assertEquals(7, reference.apply(7));
        }
    }

    @Test
    void twoArgumentAdaptedReference() throws Exception {
        // `BiFunction<Object, Object, Object>` implemented by `this::apply` of a BiFunction<String, String, String>
        // written `String apply(String, String)`: two converted arguments
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("left", Object.class).addParameter("right", Object.class).returns(Object.class)
            .build((self, p) -> p.get(0).cast(TypeDef.STRING).invoke("concat", TypeDef.STRING, p.get(1).cast(TypeDef.STRING)).returning());
        var objectBiFunction = TypeDef.parameterized(BiFunction.class, Object.class, Object.class, Object.class);
        var def = ClassDef.builder("test.R63").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(BiFunction.class, String.class, String.class, String.class)).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(objectBiFunction)
                .build((s, p) -> objectBiFunction.methodReference(s, apply).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var reference = (BiFunction<Object, Object, Object>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
            assertEquals("ab", reference.apply("a", "b"));
        }
    }

    @Test
    void adaptedReferenceWhoseReceiverIsACallTakingAnotherAdaptedReference() throws Exception {
        // `this.pick(this::apply)::apply`, both narrowed: the inner reference is an argument of the receiver call
        var apply = narrowedApply();
        var target = target("R64Target", apply);
        var pick = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("function", OBJECT_FUNCTION).returns(target.asTypeDef())
            .build((self, p) -> target.asTypeDef().instantiate().returning());
        var def = ClassDef.builder("test.R64").addModifiers(Modifier.PUBLIC).addSuperinterface(STRING_FUNCTION).addMethod(apply).addMethod(pick)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s.invoke(pick, OBJECT_FUNCTION.methodReference(s, apply)), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void lambdaInADefaultMethodCapturingThis() throws Exception {
        // `default Supplier<String> supplier() { return () -> this.name(); }`
        var name = MethodDef.builder("name").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(String.class).build();
        var interfaceDef = InterfaceDef.builder("test.R65Named").addModifiers(Modifier.PUBLIC).addMethod(name)
            .addMethod(MethodDef.builder("supplier").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(STRING_SUPPLIER)
                .build((s, p) -> STRING_SUPPLIER.getLambda().implement((ls, lp) -> s.invoke(name).returning()).returning())).build();
        var def = ClassDef.builder("test.R65").addModifiers(Modifier.PUBLIC).addSuperinterface(interfaceDef.asTypeDef())
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
                .build((s, p) -> ExpressionDef.constant("named").returning())).build();
        try (var loader = compile(interfaceDef, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("named", ((Supplier<?>) cls.getMethod("supplier").invoke(cls.getConstructor().newInstance())).get());
        }
    }

    @Test
    void adaptedReferenceUnboxesANumberResultAsTheBytecodeDoes() throws Exception {
        // `target::apply` as a ToIntFunction<Object> where apply is written `Number apply(Number)` and returns a Long:
        // invokedynamic adapts a non-wrapper reference result to `int` with `checkcast Number; intValue()`
        // (TypeConvertingMethodAdapter.convertType), so the bytecode gives 7; a cast to Integer would fail
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> ClassTypeDef.of(Long.class).invokeStatic("valueOf", TypeDef.of(Long.class), List.of(ExpressionDef.constant(7L))).returning());
        var target = ClassDef.builder("test.R66Target").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, Number.class, Number.class)).addMethod(apply).build();
        var functional = TypeDef.parameterized(ToIntFunction.class, Object.class);
        var def = ClassDef.builder("test.R66").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(functional)
                .build((s, p) -> functional.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var targetClass = loader.loadClass(target.getName());
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked") var reference = (ToIntFunction<Object>) cls.getMethod("reference", targetClass)
                .invoke(cls.getConstructor().newInstance(), targetClass.getConstructor().newInstance());
            var source = render(def);
            assertEquals(7, assertDoesNotThrow(() -> reference.applyAsInt(1), source), source);
        }
    }

    @Test
    void lambdaBlockBodyDeclaringALocalNamedLikeAMethodParameter() throws Exception {
        // As the enclosing-local case, for a parameter of the enclosing method: `value` is in scope of the body
        var def = ClassDef.builder("test.R67").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
                .build((s, p) -> {
                    var inner = new VariableDef.Local("value", TypeDef.STRING);
                    return STRING_FUNCTION.getLambda().implement((ls, lp) -> StatementDef.multi(
                            inner.defineAndAssign(lp.getFirst().invoke("trim", TypeDef.STRING)),
                            inner.returning()))
                        .newLocal("function", function -> function.invoke("apply", TypeDef.OBJECT, p.getFirst()).cast(TypeDef.STRING).returning());
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", cls.getMethod("call", String.class).invoke(cls.getConstructor().newInstance(), " x "));
        }
    }

    @Test
    void referenceReceiverThatIsAStringConcatenation() throws Exception {
        // `(prefix + "_")::concat`: the receiver is parenthesised
        var concat = MethodDef.of(String.class.getMethod("concat", String.class));
        var def = ClassDef.builder("test.R68").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("prefix", String.class).returns(STRING_FUNCTION)
                .build((s, p) -> STRING_FUNCTION.methodReference(
                    new ExpressionDef.StringConcatenation(p.getFirst(), ExpressionDef.constant("_")), concat).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("a_x", applyFn(cls.getMethod("reference", String.class).invoke(cls.getConstructor().newInstance(), "a"), "x"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            sources.add(render(definition));
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    private static String render(ObjectDef definition) throws Exception {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        return writer.toString();
    }
}
