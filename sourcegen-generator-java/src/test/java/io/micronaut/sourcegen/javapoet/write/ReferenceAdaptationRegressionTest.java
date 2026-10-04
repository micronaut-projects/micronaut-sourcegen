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
import io.micronaut.sourcegen.model.TypeDef;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Method references and lambdas, compiled and run; the source they are written as is not prescribed.
 *
 * @since 2.3
 */
public class ReferenceAdaptationRegressionTest {

    private static final ClassTypeDef OBJECT_FUNCTION = TypeDef.parameterized(Function.class, Object.class, Object.class);

    /** `Object apply(Object)` written as `String apply(String)`. */
    private static MethodDef narrowedApply() {
        return MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(Object.class).build((self, p) -> p.getFirst().returning());
    }

    private static ClassDef target(String name, MethodDef apply) {
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply).build();
    }

    @SuppressWarnings("unchecked")
    private static Object applyFn(Object function, Object value) {
        return ((Function<Object, Object>) function).apply(value);
    }

    /**
     * A functional interface with a generic method.
     *
     * @since 2.3
     */
    public interface GenericFn {
        <T> Object apply(T a);
    }

    @Test
    void referenceToOverloadBesideNarrowedMethod() throws Exception {
        // The model references apply(CharSequence); after narrowing apply(Object) to apply(String), `this::apply` for a
        // Function<String, Object> resolves to apply(String)
        var apply = narrowedApply();
        var other = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence.class).returns(Object.class).build((self, p) -> ExpressionDef.constant("other").returning());
        var functional = TypeDef.parameterized(Function.class, String.class, Object.class);
        var def = ClassDef.builder("test.P13").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply).addMethod(other)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.methodReference(s, other).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("other", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceToLessSpecificOverload() throws Exception {
        // The model references pick(Object); `this::pick` for a Function<String, Object> resolves to pick(String)
        var pickObject = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class).returns(Object.class).build((self, p) -> ExpressionDef.constant("object").returning());
        var pickString = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class).returns(Object.class).build((self, p) -> ExpressionDef.constant("string").returning());
        var functional = TypeDef.parameterized(Function.class, String.class, Object.class);
        var def = ClassDef.builder("test.P14").addModifiers(Modifier.PUBLIC).addMethod(pickObject).addMethod(pickString)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.methodReference(s, pickObject).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("object", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void nullConstantReceiver() throws Exception {
        // `Optional.of(null)` loses the type of the receiver: the cast of the `null` is dropped
        var apply = narrowedApply();
        var target = target("P16Target", apply);
        var def = ClassDef.builder("test.P16").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("nothing").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(ExpressionDef.nullValue().cast(target.asTypeDef()), apply).returning()))
            .build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> cls.getMethod("nothing").invoke(cls.getConstructor().newInstance()));
            assertEquals(NullPointerException.class, error.getCause().getClass());
        }
    }

    @Test
    void localNamedAsTheParameterOfTheLambdaItHolds() throws Exception {
        // The scope of a local includes its initializer, but the local is only declared in the render scope after the
        // initializer is written, so the lambda parameter is not renamed: `Function<String, String> t = (t) -> t`
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = ClassDef.builder("test.B1").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("lambda").addModifiers(Modifier.PUBLIC).returns(function)
                .build((s, p) -> function.getLambda().implement(List.of("t"), (ls, lp) -> lp.getFirst().returning())
                    .newLocal("t", local -> local.returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("lambda").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void localNamedArgHoldsAdaptedReference() throws Exception {
        // As above for the parameters an adapted reference allocates: `Function<Object, Object> arg = (arg) -> ...`
        var apply = narrowedApply();
        var def = ClassDef.builder("test.B2").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(s, apply).newLocal("arg", local -> local.returning()))).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("reference").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void adaptedReferenceAsArgumentOfOverloadedMethod() throws Exception {
        // `this.take(this::apply)` selects take(Function); the implicitly typed lambda it is written as is ambiguous
        var apply = narrowedApply();
        var consumer = TypeDef.parameterized(Consumer.class, Object.class);
        var takeFunction = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("f", OBJECT_FUNCTION).returns(String.class)
            .build((s, p) -> ExpressionDef.constant("function").returning());
        var takeConsumer = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("f", consumer).returns(String.class)
            .build((s, p) -> ExpressionDef.constant("consumer").returning());
        var def = ClassDef.builder("test.B3").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply)
            .addMethod(takeFunction).addMethod(takeConsumer)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> s.invoke(takeFunction, OBJECT_FUNCTION.methodReference(s, apply)).returning()))
            .addMethod(MethodDef.builder("callConsumer").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((s, p) -> s.invoke(takeConsumer, consumer.methodReference(s, apply)).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("function", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            assertEquals("consumer", cls.getMethod("callConsumer").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void referenceThroughParameterizedCompiledReceiver() throws Exception {
        // `Function<String, String> f` referenced as `f::apply` for a Function<Object, Object>: the bytecode binds
        // apply(Object), the source does not compile
        var stringFunction = TypeDef.parameterized(Function.class, String.class, String.class);
        var apply = MethodDef.of(Function.class.getMethod("apply", Object.class));
        var def = ClassDef.builder("test.C2").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("f", stringFunction).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            Function<String, String> f = value -> value;
            assertEquals("x", applyFn(cls.getMethod("reference", Function.class).invoke(cls.getConstructor().newInstance(), f), "x"));
        }
    }

    @Test
    void lambdaAsReceiverOfACall() throws Exception {
        // A lambda the model invokes a method on is written without parentheses and a target type, so the call
        // becomes part of the lambda body: `(arg0) -> arg0.trim().apply(" x ")`
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = ClassDef.builder("test.D1").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> function.getLambda().implement((ls, lp) -> lp.getFirst().invoke("trim", TypeDef.STRING).returning())
                    .invoke("apply", TypeDef.OBJECT, ExpressionDef.constant(" x ")).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void lambdaWhereAnObjectIsExpected() throws Exception {
        // A lambda returned as, cast to, or passed for an Object has no target type in the source: it needs a cast
        // to its functional interface. The bytecode only needs the value to be a reference
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var list = TypeDef.parameterized(ArrayList.class, Object.class);
        var def = ClassDef.builder("test.D2").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("returned").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> function.getLambda().implement((ls, lp) -> lp.getFirst().returning()).returning()))
            .addMethod(MethodDef.builder("local").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((s, p) -> function.getLambda().implement((ls, lp) -> lp.getFirst().returning()).cast(TypeDef.OBJECT)
                    .newLocal("o", local -> local.returning())))
            .addMethod(MethodDef.builder("argument").addModifiers(Modifier.PUBLIC).addParameter("list", list).returns(boolean.class)
                .build((s, p) -> p.getFirst().invoke("add", TypeDef.Primitive.BOOLEAN,
                    function.getLambda().implement((ls, lp) -> lp.getFirst().returning())).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", applyFn(cls.getMethod("returned").invoke(cls.getConstructor().newInstance()), "x"));
        }
    }

    @Test
    void referenceWhereAnObjectIsExpected() throws Exception {
        // The same for a method reference: `return v::concat` in a method returning Object
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var trim = MethodDef.of(String.class.getMethod("concat", String.class));
        var def = ClassDef.builder("test.D3").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("returned").addModifiers(Modifier.PUBLIC).addParameter("v", String.class).returns(Object.class)
                .build((s, p) -> function.methodReference(p.getFirst(), trim).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("ax", applyFn(cls.getMethod("returned", String.class).invoke(cls.getConstructor().newInstance(), "a"), "x"));
        }
    }

    @Test
    void referenceInsideInnerTypeToOuterNarrowedMethod() throws Exception {
        // An inner type names its outer type by name - the outer definition is not built yet - so neither a
        // reference nor a call from the inner type is converted to the narrowed `apply(String)` of the outer one:
        // only the definition being written is looked up by name, and that is the inner type
        var apply = narrowedApply();
        var outerType = ClassTypeDef.of("test.G2");
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("outer", outerType).returns(OBJECT_FUNCTION)
                .build((s, p) -> OBJECT_FUNCTION.methodReference(p.getFirst(), apply).returning()))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("outer", outerType).addParameter("v", Object.class).returns(Object.class)
                .build((s, p) -> p.getFirst().invoke(apply, p.get(1)).returning())).build();
        var def = ClassDef.builder("test.G2").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply)
            .addInnerType(inner).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var innerClass = loader.loadClass("test.G2$Inner");
            assertEquals("x", applyFn(innerClass.getMethod("reference", cls).invoke(innerClass.getConstructor().newInstance(), cls.getConstructor().newInstance()), "x"));
        }
    }

    // A method reference initializing a field of another type than its own: `Object supplier = "x"::toString`.
    @Test
    void referenceFieldInitializerOfObjectField() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var field = FieldDef.builder("supplier", Object.class).addModifiers(Modifier.PUBLIC)
            .initializer(supplier.methodReference(ExpressionDef.constant("x"), String.class.getMethod("toString"))).build();
        var def = ClassDef.builder("test.ReferenceInitializer").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> self.field(field).returning())).build();
        assertEquals("x", ((Supplier<?>) run(def)).get());
    }

    // `Fixture::pick` for a `Function<String, String>` binds `pick(String)`; the model references `pick(Object)`.
    @Test
    void staticReferenceToLessSpecificOverload() throws Exception {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var pick = MethodDef.of(Fixture.class.getMethod("pick", Object.class));
        var def = single("StaticReferenceOverload", Object.class, List.of(),
            (self, p) -> function.staticMethodReference(ClassTypeDef.of(Fixture.class), pick).returning());
        assertEquals("object", apply(run(def), "a"));
    }

    // As above for a constructor: `Fixture::new` binds `Fixture(String)`.
    @Test
    void constructorReferenceToLessSpecificOverload() throws Exception {
        var function = TypeDef.parameterized(Function.class, String.class, Fixture.class);
        var constructor = MethodDef.constructor().addParameter("value", Object.class).build();
        var def = single("ConstructorReferenceOverload", Object.class, List.of(),
            (self, p) -> function.constructorReference(ClassTypeDef.of(Fixture.class), constructor).returning());
        assertEquals("object", ((Fixture) apply(run(def), "a")).kind);
    }

    // `Integer::toString` for a `Function<Integer, String>` is ambiguous between `toString(int)` and `toString()`.
    @Test
    void staticReferenceAmbiguousWithInstanceMethod() throws Exception {
        var function = TypeDef.parameterized(Function.class, Integer.class, String.class);
        var toString = MethodDef.of(Integer.class.getMethod("toString", int.class));
        var def = single("AmbiguousReference", Object.class, List.of(),
            (self, p) -> function.staticMethodReference(ClassTypeDef.of(Integer.class), toString).returning());
        assertEquals("5", apply(run(def), 5));
    }

    // The metafactory casts the result of the referenced method to the one of the functional interface: `map::get`
    // of a raw Map for a `Function<Object, String>` returns Object, which javac rejects.
    @Test
    void boundReferenceResultCastToFunctionalResult() throws Exception {
        var function = TypeDef.parameterized(Function.class, Object.class, String.class);
        var get = Map.class.getMethod("get", Object.class);
        var def = single("ReferenceResultCast", Object.class, List.of(TypeDef.of(Map.class)),
            (self, p) -> function.methodReference(p.get(0), get).returning());
        assertEquals("v", apply(run(def, Map.of("k", "v")), "k"));
    }

    // A static reference whose method returns Object, for a `Function<Object, String>`: the metafactory casts the
    // result, javac rejects the reference.
    @Test
    void staticReferenceResultCastToFunctionalResult() throws Exception {
        var function = TypeDef.parameterized(Function.class, Object.class, String.class);
        var identity = MethodDef.of(Fixture.class.getMethod("identity", Object.class));
        var def = single("StaticReferenceResultCast", Object.class, List.of(),
            (self, p) -> function.staticMethodReference(ClassTypeDef.of(Fixture.class), identity).returning());
        assertEquals("a", apply(run(def), "a"));
    }

    // A static method reference on a parameterized owner - as `ClassTypeDef.of(element)` builds it for an element with
    // type arguments - is written `Optional<String>::ofNullable` (see OverloadResolutionWriteTest for a call and a field).
    @Test
    void staticReferenceOnParameterizedOwner() throws Exception {
        var function = TypeDef.parameterized(Function.class, Object.class, Object.class);
        var ofNullable = MethodDef.of(Optional.class.getMethod("ofNullable", Object.class));
        var def = single("ParameterizedStaticReference", Object.class, List.of(),
            (self, p) -> function.staticMethodReference(TypeDef.parameterized(Optional.class, String.class), ofNullable).returning());
        assertEquals(Optional.of("a"), apply(run(def), "a"));
    }

    // A cast to the type a lambda has is not written, which leaves the lambda without a target type as a receiver.
    @Test
    void castLambdaAsReceiver() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var get = Supplier.class.getMethod("get");
        var def = single("CastLambdaReceiver", Object.class, List.of(),
            (self, p) -> supplier.getLambda().implement((ls, lp) -> ExpressionDef.constant("x").returning()).cast(supplier).invoke(get).returning());
        assertEquals("x", run(def));
    }

    // As above for a lambda returned from an Object method.
    @Test
    void castLambdaReturnedAsObject() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var def = single("CastLambdaReturned", Object.class, List.of(),
            (self, p) -> supplier.getLambda().implement((ls, lp) -> ExpressionDef.constant("x").returning()).cast(supplier).returning());
        assertEquals("x", ((Supplier<?>) run(def)).get());
    }

    // A lambda of the raw Function returns an Object; passed to `computeIfAbsent` of a `Map<String, Integer>` it is
    // written bare, and javac types its result by the target: `? extends Integer`, which Object is not.
    @Test
    void rawLambdaResultThroughBoundedTarget() throws Exception {
        var computeIfAbsent = Map.class.getMethod("computeIfAbsent", Object.class, Function.class);
        var def = single("RawLambdaResult", Object.class, List.of(TypeDef.parameterized(Map.class, String.class, Integer.class), TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(computeIfAbsent, ExpressionDef.constant("k"),
                ClassTypeDef.of(Function.class).getLambda().implement((ls, lp) -> p.get(1).returning())).returning());
        var map = new java.util.HashMap<String, Integer>();
        assertEquals(5, run(def, map, 5));
        assertEquals(Map.of("k", 5), map);
    }

    // As above for the parameter: javac types it by the target, a String, and binds `pick(String)` where the model
    // calls `pick(Object)` with it.
    @Test
    void rawLambdaParameterTypedByTarget() throws Exception {
        var map = Optional.class.getMethod("map", Function.class);
        var get = Optional.class.getMethod("get");
        var pick = Fixture.class.getMethod("pick", Object.class);
        var def = single("RawLambdaParameter", Object.class, List.of(TypeDef.parameterized(Optional.class, String.class)),
            (self, p) -> p.get(0).invoke(map, ClassTypeDef.of(Function.class).getLambda().implement((ls, lp) ->
                ClassTypeDef.of(Fixture.class).invokeStatic(pick, lp.get(0)).returning())).invoke(get).returning());
        assertEquals("object", run(def, Optional.of("a")));
    }

    @Test
    void lambdaInsideTypedConditionalReceivesFunctionalTarget() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var def = ClassDef.builder("test.ConditionalLambda").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("flag", boolean.class)
                .returns(Object.class).build((self, p) -> new ExpressionDef.IfElse(p.getFirst().isTrue(),
                    supplier.getLambda().implement((ls, lp) -> ExpressionDef.constant("a").returning()),
                    supplier.getLambda().implement((ls, lp) -> ExpressionDef.constant("b").returning()), supplier).returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var result = (Supplier<?>) cls.getMethod("call", boolean.class).invoke(cls.getConstructor().newInstance(), true);
            assertEquals("a", result.get());
        }
    }

    @Test
    void inheritedNarrowedOverloadDoesNotStealMethodReference() throws Exception {
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(Object.class)
            .build((s, p) -> ExpressionDef.constant("parent").returning());
        var parent = ClassDef.builder("test.ReferenceParent").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply).build();
        var selected = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence.class).returns(Object.class)
            .build((s, p) -> ExpressionDef.constant("selected").returning());
        var functional = TypeDef.parameterized(Function.class, String.class, Object.class);
        var child = ClassDef.builder("test.ReferenceChild").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(selected)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(functional)
                .build((s, p) -> functional.methodReference(s, selected).returning())).build();
        try (var loader = compile(parent, child)) {
            var cls = loader.loadClass(child.getName());
            @SuppressWarnings("unchecked")
            var function = (Function<String, Object>) cls.getMethod("reference").invoke(cls.getConstructor().newInstance());
            assertEquals("selected", function.apply("input"));
        }
    }

    /**
     * Overloads a model names one of.
     *
     * @since 2.3
     */
    @SuppressWarnings("unused")
    public static class Fixture {
        /** The constructor that ran. */
        public final String kind;

        /** @param value The value */
        public Fixture(Object value) {
            kind = "object";
        }

        /** @param value The value */
        public Fixture(String value) {
            kind = "string";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String pick(Object value) {
            return "object";
        }

        /**
         * @param value The value
         * @return The overload
         */
        public static String pick(String value) {
            return "string";
        }

        /**
         * @param value The value
         * @return The value
         */
        public static Object identity(Object value) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object apply(Object function, Object value) {
        return ((Function<Object, Object>) function).apply(value);
    }
}
