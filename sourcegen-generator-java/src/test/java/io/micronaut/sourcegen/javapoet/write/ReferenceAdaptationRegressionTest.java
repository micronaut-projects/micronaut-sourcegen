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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Method references and lambdas, compiled and run; the source they are written as is not prescribed.
 *
 * @since 2.2.2
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
     * @since 2.2.2
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

    // ---------------------------------------------------------------- helpers

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var writer = new StringWriter();
            new JavaPoetSourceGenerator().write(definition, writer);
            sources.add(writer.toString());
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }
}
