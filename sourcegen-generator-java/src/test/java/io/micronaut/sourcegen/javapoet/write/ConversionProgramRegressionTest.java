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
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Executes DSL programs at conversion and scope boundaries.
 *
 * @since 2.2.2
 */
public class ConversionProgramRegressionTest {
    @TestFactory
    Stream<DynamicTest> arrayHelperPreservesCallerVariables() {
        return Stream.of("T", "R", "method").map(kind -> dynamicTest(kind, () -> {
            var callerVariable = TypeDef.variable(kind.equals("method") ? "R" : kind);
            var x = TypeDef.variable("X");
            var u = TypeDef.variable("U", TypeDef.parameterized(ClassTypeDef.of(List.class), x), TypeDef.of(Serializable.class));
            var arrays = MethodDef.builder("arrays").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
                .addParameter("value", u.array()).returns(Object.class).build();
            var caller = ClassDef.builder("test.ArrayScope" + kind).addModifiers(Modifier.PUBLIC);
            var call = MethodDef.builder("call").addModifiers(Modifier.PUBLIC);
            if (kind.equals("method")) {
                call.addTypeVariable(callerVariable);
            } else {
                caller.addTypeVariable(callerVariable);
            }
            var def = caller.addMethod(call.addParameter("target", TypeDef.parameterized(ClassTypeDef.of(IntersectionTarget.class), callerVariable))
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(arrays, p.get(1)).returning())).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                var value = new ArrayList<?>[]{new ArrayList<>(List.of("text"))};
                assertSame(value, cls.getMethod("call", IntersectionTarget.class, Object.class)
                    .invoke(cls.getConstructor().newInstance(), new IntersectionTarget<String>(), value));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> inheritedCallsMatchTheFullGenericSignature() {
        return Stream.of("overload", "array").map(kind -> dynamicTest(kind, () -> {
            var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
            var u = TypeDef.variable("U", x);
            var parameter = kind.equals("array") ? TypeDef.variable("U").array() : u;
            var method = MethodDef.builder(kind.equals("array") ? "arrays" : "identity").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(u).addParameter("value", parameter).returns(Object.class).build();
            var receiver = kind.equals("array") ? ArrayChild.class : GenericOverloadedChild.class;
            var def = ClassDef.builder("test.GenericSignature" + kind).addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("target", receiver)
                    .addParameter("value", Object.class).returns(Object.class)
                    .build((self, p) -> p.getFirst().invoke(method, p.get(1)).returning())).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                Object value = kind.equals("array") ? new String[]{"text"} : "text";
                assertSame(value, cls.getMethod("call", receiver, Object.class)
                    .invoke(cls.getConstructor().newInstance(), receiver.getConstructor().newInstance(), value));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> rawReceiverBoundsDoNotCaptureCallerVariables() {
        return Stream.of("X", "Y").map(name -> dynamicTest(name, () -> {
            var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
            var u = TypeDef.variable("U", x);
            var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
                .addParameter("value", u).returns(Object.class).build();
            var def = ClassDef.builder("test.RawReceiver" + name).addModifiers(Modifier.PUBLIC).addTypeVariable(TypeDef.variable(name))
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("target", CalleeBoundsRegressionTest.Parent.class)
                    .addParameter("value", Object.class).returns(Object.class)
                    .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                assertEquals("text", cls.getMethod("call", CalleeBoundsRegressionTest.Parent.class, Object.class)
                    .invoke(cls.getConstructor().newInstance(), new CalleeBoundsRegressionTest.Parent<String>(), "text"));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> loopCompletionMatchesJavaConstantExpressionRules() {
        return Stream.of("primitive", "boxed", "negated", "comparison").map(kind -> dynamicTest(kind, () -> {
            var condition = switch (kind) {
                case "boxed" -> ExpressionDef.trueValue().cast(Boolean.class).isTrue();
                case "negated" -> ExpressionDef.falseValue().isFalse();
                case "comparison" -> ExpressionDef.constant(1).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ExpressionDef.constant(1));
                default -> ExpressionDef.trueValue().isTrue();
            };
            var def = ClassDef.builder("test.ConstantLoop" + kind).addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                    .build((self, p) -> StatementDef.multi(condition.whileLoop(ExpressionDef.constant(1).returning()), ExpressionDef.constant(2).returning()))).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                assertEquals(1, cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            }
        }));
    }

    @Test
    void adaptedReferenceCanReturnRawGeneratedGenericType() throws Exception {
        var box = ClassDef.builder("test.Box").addModifiers(Modifier.PUBLIC).addTypeVariable(TypeDef.variable("E")).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var target = ClassDef.builder("test.BoxSource").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.parameterized(ClassTypeDef.of(box), TypeDef.STRING)))
            .addMethod(get).build();
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.parameterized(ClassTypeDef.of(box), TypeDef.OBJECT));
        var def = ClassDef.builder("test.BoxReferenceCall").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(supplier)
                .build((self, p) -> supplier.methodReference(p.getFirst(), get).returning())).build();
        try (var loader = compile(box, target, def)) {
            var cls = loader.loadClass(def.getName());
            var targetClass = loader.loadClass(target.getName());
            var reference = (Supplier<?>) cls.getMethod("reference", targetClass)
                .invoke(cls.getConstructor().newInstance(), targetClass.getConstructor().newInstance());
            assertNull(reference.get());
        }
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var writer = new StringWriter();
            new JavaPoetSourceGenerator().write(definition, writer);
            var source = writer.toString();
            var resource = "/conversion-programs/java/" + definition.getSimpleName() + ".txt";
            try (var expected = ConversionProgramRegressionTest.class.getResourceAsStream(resource)) {
                assertNotNull(expected, resource);
                var snapshot = new String(expected.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(snapshot, source, definition.getName());
            }
            sources.add(source);
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    /**
     * Supplies an array parameter with a parameterized intersection bound.
     * @param <X> The element type
     * @since 2.2.2
     */
    public static class IntersectionTarget<X> {
        /**
         * @param value The array
         * @param <U> The component type
         * @return The same array
         */
        public <U extends List<X> & Serializable> Object arrays(U[] value) {
            return value;
        }
    }

    /**
     * Adds an unrelated generic overload to an inherited generic method.
     * @since 2.2.2
     */
    public static class GenericOverloadedChild extends CalleeBoundsRegressionTest.Parent<String> {
        /**
         * @param value The number
         * @param <U> The number type
         * @return The same number
         */
        public <U extends Number> U identity(U value) {
            return value;
        }
    }

    /**
     * Supplies an inherited generic array method.
     * @param <X> The component bound
     * @since 2.2.2
     */
    public static class ArrayParent<X extends CharSequence> {
        /**
         * @param value The array
         * @param <U> The component type
         * @return The same array
         */
        public <U extends X> Object arrays(U[] value) {
            return value;
        }
    }

    /**
     * Specializes the inherited array component bound.
     * @since 2.2.2
     */
    public static class ArrayChild extends ArrayParent<String> {
    }
}
