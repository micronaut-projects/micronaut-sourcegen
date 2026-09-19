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
import java.io.StringWriter;
import java.io.Serializable;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Exercises invocation and completion behavior through DSL-generated programs.
 *
 * @since 2.2.2
 */
public class InvocationProgramRegressionTest {
    @Test
    void inheritedGenericCallIgnoresUnrelatedSubclassOverload() throws Exception {
        var def = receiver("OverloadedInheritedCall", ClassTypeDef.of(OverloadedChild.class));
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("text", cls.getMethod("call", OverloadedChild.class, Object.class)
                .invoke(cls.getConstructor().newInstance(), new OverloadedChild(), "text"));
        }
    }

    @Test
    void generatedSubclassBindsCompiledParent() throws Exception {
        var child = ClassDef.builder("test.GeneratedChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(CalleeBoundsRegressionTest.Parent.class, String.class)).build();
        var def = receiver("MixedHierarchyCall", child.asTypeDef());
        try (var loader = compile(child, def)) {
            var cls = loader.loadClass(def.getName());
            var target = loader.loadClass(child.getName());
            assertEquals("text", cls.getMethod("call", target, Object.class)
                .invoke(cls.getConstructor().newInstance(), target.getConstructor().newInstance(), "text"));
        }
    }

    @Test
    void boundReferenceRejectsNullReceiverWhenCreated() throws Exception {
        var apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(Object.class).build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.ReferenceTarget").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class)).addMethod(apply).build();
        var function = TypeDef.parameterized(Function.class, Object.class, Object.class);
        var def = ClassDef.builder("test.NullReferenceCall").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("target", target.asTypeDef()).returns(function)
                .build((self, p) -> function.methodReference(p.getFirst(), apply).returning())).build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            var targetClass = loader.loadClass(target.getName());
            var error = assertThrows(InvocationTargetException.class,
                () -> cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(), new Object[]{null}));
            assertInstanceOf(NullPointerException.class, error.getCause());
        }
    }

    @TestFactory
    Stream<DynamicTest> inheritedGenericCallsFollowEveryGeneratedSuperclass() {
        return Stream.of(1, 9, 10).map(depth -> dynamicTest("depth " + depth, () -> {
            var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
            var u = TypeDef.variable("U", x);
            var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
                .addParameter("value", u).returns(u).build((self, p) -> p.getFirst().returning());
            var parent = ClassDef.builder("test.HierarchyRoot").addModifiers(Modifier.PUBLIC).addTypeVariable(x).addMethod(identity).build();
            var definitions = new ArrayList<ObjectDef>();
            definitions.add(parent);
            ClassTypeDef supertype = TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING);
            for (int i = 0; i < depth; i++) {
                var child = ClassDef.builder("test.HierarchyLevel" + i).addModifiers(Modifier.PUBLIC).superclass(supertype).build();
                definitions.add(child);
                supertype = child.asTypeDef();
            }
            var caller = receiver("DeepHierarchyCall" + depth, supertype);
            definitions.add(caller);
            try (var loader = compile(definitions.toArray(ObjectDef[]::new))) {
                var cls = loader.loadClass(caller.getName());
                var target = loader.loadClass(supertype.getName());
                assertEquals("text", cls.getMethod("call", target, Object.class)
                    .invoke(cls.getConstructor().newInstance(), target.getConstructor().newInstance(), "text"));
            }
        }));
    }

    @Test
    void genericArrayArgumentsSatisfyEveryComponentBound() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class), TypeDef.of(Serializable.class));
        var method = MethodDef.builder("intersectionArray").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("value", t.array()).returns(Object.class).build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.IntersectionArrayCall").addModifiers(Modifier.PUBLIC).addMethod(method)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(method, p.getFirst()).returning())).build();
        // Multiple component bounds need more than an ordinary array cast. Require a compilable
        // program without prescribing a particular helper or lowering strategy in a source snapshot.
        try (var loader = JavaCompileAssertions.compileAndLoad(render(def))) {
            var cls = loader.loadClass(def.getName());
            var value = new String[]{"text"};
            assertEquals(value, cls.getMethod("call", Object.class).invoke(cls.getConstructor().newInstance(), (Object) value));
        }
    }

    @TestFactory
    Stream<DynamicTest> terminalBlocksDiscardUnreachableFallbacks() {
        return Stream.of("finally", "finallyReturn", "monitor", "nested", "loop").map(kind -> dynamicTest(kind, () -> {
            var def = ClassDef.builder("test.Terminal" + kind).addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                    .build((self, p) -> {
                        var result = ExpressionDef.constant("reached").returning();
                        StatementDef body = switch (kind) {
                            case "finally" -> StatementDef.doTry(result).doFinally(StatementDef.multi());
                            case "finallyReturn" -> StatementDef.doTry(StatementDef.multi()).doFinally(result);
                            case "loop" -> ExpressionDef.trueValue().isTrue().whileLoop(result);
                            case "monitor" -> synchronizedBlock(ExpressionDef.constant("lock"), result);
                            default -> ExpressionDef.trueValue().isTrue().doIfElse(
                                StatementDef.multi(result, ClassTypeDef.of(System.class).invokeStatic("nanoTime", TypeDef.Primitive.LONG)), result);
                        };
                        return StatementDef.multi(body, ExpressionDef.constant("unreachable").returning());
                    })).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                assertEquals("reached", cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            }
        }));
    }

    private static StatementDef synchronizedBlock(ExpressionDef monitor, StatementDef body) {
        return new StatementDef.Synchronized(monitor, body);
    }

    private static ClassDef receiver(String name, ClassTypeDef receiver) {
        var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
        var u = TypeDef.variable("U", x);
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u).returns(u).build();
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("target", receiver)
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(method, p.get(1)).returning())).build();
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var source = render(definition);
            var resource = "/invocation-programs/java/" + definition.getSimpleName() + ".txt";
            try (var expected = InvocationProgramRegressionTest.class.getResourceAsStream(resource)) {
                assertNotNull(expected, resource);
                var snapshot = new String(expected.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(snapshot, source, definition.getName());
            }
            sources.add(source);
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    private static String render(ObjectDef definition) throws Exception {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        return writer.toString();
    }

    /**
     * A same-arity overload must not hide the inherited generic declaration.
     *
     * @since 2.2.2
     */
    public static class OverloadedChild extends CalleeBoundsRegressionTest.Parent<String> {
        /**
         * Returns the integer argument of the unrelated overload.
         *
         * @param value The input
         * @return The same value
         * @since 2.2.2
         */
        public Integer identity(Integer value) {
            return value;
        }
    }
}
