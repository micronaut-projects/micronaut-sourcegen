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
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DSL-generated programs for the shapes of the generator that no other test of this module reaches - found by
 * line coverage - which are rendered, compared with their snapshot, compiled and run.
 *
 * @since 2.2.2
 */
public class UncoveredShapeProgramTest {

    @Test
    void annotationMembersOfEveryKindAreReadBack() throws Exception {
        var nested = AnnotationDef.builder(ClassTypeDef.of(Nested.class)).addMember("value", "inner").build();
        var annotation = AnnotationDef.builder(ClassTypeDef.of(Members.class))
            .addMember("texts", new String[]{"a", "$b"})
            .addMember("numbers", new int[]{1, 2})
            .addMember("none", new String[0])
            .addMember("emptyList", List.of())
            .addMember("types", new Class<?>[]{String.class, int.class})
            .addMember("policies", List.of(RetentionPolicy.RUNTIME, RetentionPolicy.SOURCE))
            .addMember("type", ClassTypeDef.of(StringBuilder.class))
            .addMember("letter", '\'')
            .addMember("ratio", 1.5f)
            .addMember("nested", nested)
            .addMember("nestedArray", List.of(nested, nested))
            .build();
        var def = ClassDef.builder("test.AnnotatedMembers").addModifiers(Modifier.PUBLIC).addAnnotation(annotation).build();
        try (var loader = compile(def)) {
            Members members = loader.loadClass(def.getName()).getAnnotation(Members.class);
            assertArrayEquals(new String[]{"a", "$b"}, members.texts());
            assertArrayEquals(new int[]{1, 2}, members.numbers());
            assertEquals(0, members.none().length);
            assertEquals(0, members.emptyList().length);
            assertArrayEquals(new Class<?>[]{String.class, int.class}, members.types());
            assertArrayEquals(new RetentionPolicy[]{RetentionPolicy.RUNTIME, RetentionPolicy.SOURCE}, members.policies());
            assertEquals(StringBuilder.class, members.type());
            assertEquals('\'', members.letter());
            assertEquals(1.5f, members.ratio());
            assertEquals("inner", members.nested().value());
            assertEquals(2, members.nestedArray().length);
        }
    }

    @Test
    void nestedMathKeepsTheGroupingOfTheModel() throws Exception {
        var def = ClassDef.builder("test.MathGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", int.class).addParameter("b", int.class).addParameter("c", int.class).returns(int[].class)
                .build((self, p) -> {
                    ExpressionDef a = p.get(0);
                    ExpressionDef b = p.get(1);
                    ExpressionDef c = p.get(2);
                    return TypeDef.Primitive.INT.array().instantiate(
                        a.math(OpType.ADDITION, b).math(OpType.MULTIPLICATION, c),
                        a.math(OpType.SUBTRACTION, b.math(OpType.SUBTRACTION, c)),
                        a.math(OpType.SUBTRACTION, b).math(OpType.SUBTRACTION, c),
                        a.math(OpType.DIVISION, b.math(OpType.MULTIPLICATION, c)),
                        a.math(OpType.BITWISE_OR, b).math(OpType.BITWISE_AND, c),
                        a.math(OpType.BITWISE_LEFT_SHIFT, b.math(OpType.ADDITION, c)),
                        a.math(OpType.BITWISE_XOR, b.math(OpType.BITWISE_OR, c)),
                        a.math(OpType.ADDITION, b).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE),
                        a.math(OpType.MODULUS, b.math(OpType.MODULUS, c))
                    ).returning();
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            int a = 40;
            int b = 7;
            int c = 3;
            assertArrayEquals(
                new int[]{(a + b) * c, a - (b - c), a - b - c, a / (b * c), (a | b) & c, a << (b + c), a ^ (b | c), -(a + b), a % (b % c)},
                (int[]) cls.getMethod("call", int.class, int.class, int.class).invoke(cls.getConstructor().newInstance(), a, b, c));
        }
    }

    @Test
    void conditionsKeepTheirGroupingAndNegation() throws Exception {
        var def = ClassDef.builder("test.ConditionGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", boolean.class).addParameter("b", boolean.class).addParameter("c", boolean.class)
                .addParameter("x", int.class).addParameter("text", Object.class).returns(boolean[].class)
                .build((self, p) -> {
                    var a = p.get(0).isTrue();
                    var b = p.get(1).isTrue();
                    var c = p.get(2).isTrue();
                    return TypeDef.Primitive.BOOLEAN.array().instantiate(
                        a.or(b).and(c),
                        a.and(b.or(c)),
                        a.or(b.and(c)),
                        a.or(b).isFalse(),
                        a.and(b).isFalse().or(c),
                        p.get(3).equalsReferentially(ExpressionDef.constant(1)).isFalse(),
                        p.get(4).notEqualsStructurally(ExpressionDef.constant("text")),
                        p.get(4).isNull().isFalse().and(p.get(4).instanceOf(ClassTypeDef.of(String.class))),
                        a.doIfElse(b, c).isTrue().or(a)
                    ).returning();
                })).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = cls.getMethod("call", boolean.class, boolean.class, boolean.class, int.class, Object.class);
            var instance = cls.getConstructor().newInstance();
            for (int bits = 0; bits < 8; bits++) {
                boolean a = (bits & 1) != 0;
                boolean b = (bits & 2) != 0;
                boolean c = (bits & 4) != 0;
                int x = bits % 2;
                Object text = a ? "text" : b ? "other" : null;
                assertArrayEquals(
                    new boolean[]{(a || b) && c, a && (b || c), a || (b && c), !(a || b), !(a && b) || c, !(x == 1),
                        !"text".equals(text), !(text == null) && text instanceof String, (a ? b : c) || a},
                    (boolean[]) method.invoke(instance, a, b, c, x, text), "bits " + bits);
            }
        }
    }

    @Test
    void elementOfNarrowedArrayParameterKeepsTheOverloadOfTheModel() throws Exception {
        var choose = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var chooseText = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("string").returning());
        var def = ClassDef.builder("test.NarrowedElement").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(First.class, String.class))
            .addMethod(choose).addMethod(chooseText)
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", Object[].class).returns(Object.class)
                .build((self, p) -> p.get(0).arrayElement(0).returning()))
            .addMethod(MethodDef.builder("chosen").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", Object[].class).returns(String.class)
                .build((self, p) -> self.invoke(choose, p.get(0).arrayElement(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            @SuppressWarnings("unchecked") var first = (First<String>) loader.loadClass(def.getName()).getConstructor().newInstance();
            assertEquals("text", first.first(new String[]{"text"}));
            // The model calls choose(Object): the bytecode writer binds that overload whatever the element is
            assertEquals("object", first.chosen(new String[]{"text"}));
        }
    }

    @Test
    void typesOfEveryKindAreWritten() throws Exception {
        var marker = AnnotationDef.builder(ClassTypeDef.of(TypeMarker.class)).build();
        var holder = ClassTypeDef.of(Holder.class);
        var consumer = TypeDef.parameterized(Consumer.class, TypeDef.wildcardSupertypeOf(TypeDef.STRING));
        var comparator = TypeDef.parameterized(Comparator.class, TypeDef.wildcardSupertypeOf(TypeDef.of(Number.class)));
        var sort = List.class.getMethod("sort", Comparator.class);
        var accept = Consumer.class.getMethod("accept", Object.class);
        var unmodifiable = Collections.class.getMethod("unmodifiableList", List.class);
        var def = ClassDef.builder("test.TypeKinds").addModifiers(Modifier.PUBLIC).superclass(holder)
            .addMethod(MethodDef.builder("feed").addModifiers(Modifier.PUBLIC).addParameter("consumer", consumer)
                .addParameter("value", TypeDef.STRING.annotated(marker)).returns(TypeDef.SUPER)
                .build((self, p) -> StatementDef.multi(p.get(0).invoke(accept, p.get(1)), self.returning())))
            .addMethod(MethodDef.builder("sorted").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.parameterized(ArrayList.class, Integer.class)).addParameter("order", comparator)
                .returns(TypeDef.parameterized(List.class, Integer.class))
                .build((self, p) -> StatementDef.multi(p.get(0).invoke(sort, p.get(1)),
                    ClassTypeDef.of(Collections.class).invokeStatic(unmodifiable, p.get(0)).returning())))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var consumed = new ArrayList<Object>();
            assertEquals(instance, cls.getMethod("feed", Consumer.class, String.class).invoke(instance, (Consumer<Object>) consumed::add, "text"));
            assertEquals(List.of("text"), consumed);
            assertEquals(List.of(1, 2, 3), cls.getMethod("sorted", ArrayList.class, Comparator.class)
                .invoke(instance, new ArrayList<>(List.of(3, 1, 2)), Comparator.comparingInt(Number::intValue)));
        }
    }

    @Test
    void fieldOfAnotherTypeIsReadThroughItsDeclaringType() throws Exception {
        var holder = ClassTypeDef.of(Holder.class);
        var label = FieldDef.builder("label", String.class).addModifiers(Modifier.PUBLIC).build();
        var def = ClassDef.builder("test.ForeignField").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("label").addModifiers(Modifier.PUBLIC).addParameter("other", Object.class).returns(String.class)
                .build((self, p) -> new VariableDef.Field(p.get(0), holder, label.getName(), TypeDef.STRING).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("held", cls.getMethod("label", Object.class).invoke(cls.getConstructor().newInstance(), new Holder()));
        }
    }

    @Test
    void generatedInterfaceDefaultMethodIsCalledThroughItsSuper() throws Exception {
        var greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("hello").returning());
        var greeter = InterfaceDef.builder("test.DefaultGreeter").addModifiers(Modifier.PUBLIC).addMethod(greet).build();
        var def = ClassDef.builder("test.LoudGreeter").addModifiers(Modifier.PUBLIC).addSuperinterface(greeter.asTypeDef())
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
                .build((self, p) -> self.superRef(greeter.asTypeDef()).invoke(greet).stringConcat(ExpressionDef.constant("!")).returning()))
            .build();
        try (var loader = compile(greeter, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("hello!", cls.getMethod("greet").invoke(cls.getConstructor().newInstance()));
        }
    }

    @Test
    void interfaceStaticMethodAndImplementationAreCalled() throws Exception {
        var describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", int.class).returns(String.class).build();
        var twice = MethodDef.builder("twice").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", int.class).returns(int.class)
            .build((self, p) -> p.get(0).math(OpType.MULTIPLICATION, ExpressionDef.constant(2)).returning());
        var describer = InterfaceDef.builder("test.Describer").addModifiers(Modifier.PUBLIC).addMethod(describe).addMethod(twice).build();
        var def = ClassDef.builder("test.TwiceDescriber").addModifiers(Modifier.PUBLIC).addSuperinterface(describer.asTypeDef())
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", int.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("twice ").stringConcat(describer.asTypeDef().invokeStatic(twice, p.get(0))).returning()))
            .build();
        try (var loader = compile(describer, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("twice 42", cls.getMethod("describe", int.class).invoke(cls.getConstructor().newInstance(), 21));
        }
    }

    @Test
    void enumWithConstructorStaticMethodAndSwitchOverItsConstants() throws Exception {
        var weight = FieldDef.builder("weight", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var type = ClassTypeDef.of("test.Weighted");
        var heavier = MethodDef.builder("heavier").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("left", type).addParameter("right", type).returns(type)
            .build((self, p) -> p.get(0).field(weight).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, p.get(1).field(weight))
                .doIfElse(p.get(0), p.get(1)).returning());
        var def = io.micronaut.sourcegen.model.EnumDef.builder(type.getName()).addModifiers(Modifier.PUBLIC)
            .addEnumConstant("LIGHT", ExpressionDef.constant(1)).addEnumConstant("HEAVY", ExpressionDef.constant(10))
            .addField(weight).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(heavier)
            .addMethod(MethodDef.builder("weight").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> self.field(weight).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            Object light = cls.getField("LIGHT").get(null);
            Object heavy = cls.getField("HEAVY").get(null);
            assertEquals(heavy, cls.getMethod("heavier", cls, cls).invoke(null, light, heavy));
            assertEquals(10, cls.getMethod("weight").invoke(heavy));
        }
    }

    @Test
    void declaredCheckedExceptionIsThrown() throws Exception {
        var def = ClassDef.builder("test.CheckedThrower").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addThrows(TypeDef.of(java.io.IOException.class)).returns(void.class)
                .build((self, p) -> ClassTypeDef.of(java.io.IOException.class).instantiate(ExpressionDef.constant("checked")).doThrow()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = cls.getMethod("call");
            assertArrayEquals(new Class<?>[]{java.io.IOException.class}, method.getExceptionTypes());
            var error = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(cls.getConstructor().newInstance()));
            assertEquals("checked", error.getCause().getMessage());
        }
    }

    @Test
    void superConstructorTakesAnErasedArgument() throws Exception {
        var def = ClassDef.builder("test.ErasedSuperArgument").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled.class))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("label", Object.class)
                .build((self, p) -> self.superRef().invokeSuperConstructor(List.of(TypeDef.STRING), p.get(0))))
            .addMethod(MethodDef.builder("typeName").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.invokeGetClass().invoke("getSimpleName", TypeDef.STRING).returning()))
            .addMethod(MethodDef.builder("stableHash").addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .build((self, p) -> self.invokeHashCode().compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, self.invokeHashCode()).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor(Object.class).newInstance("text");
            assertEquals("text", ((Labelled) instance).label);
            assertEquals("ErasedSuperArgument", cls.getMethod("typeName").invoke(instance));
            assertEquals(true, cls.getMethod("stableHash").invoke(instance));
        }
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var writer = new StringWriter();
            new JavaPoetSourceGenerator().write(definition, writer);
            var source = writer.toString();
            var resource = "/uncovered-programs/java/" + definition.getSimpleName() + ".txt";
            try (var expected = UncoveredShapeProgramTest.class.getResourceAsStream(resource)) {
                assertNotNull(expected, resource + "\n" + source);
                assertEquals(new String(expected.readAllBytes(), StandardCharsets.UTF_8), source, definition.getName());
            }
            sources.add(source);
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    /**
     * A generic method over an array of the class's variable.
     *
     * @param <T> The element type
     * @since 2.2.2
     */
    public interface First<T> {
        /**
         * @param values The values
         * @return The first of them
         */
        T first(T[] values);

        /**
         * @param values The values
         * @return The name of the overload the first of them selects
         */
        String chosen(T[] values);
    }

    /**
     * A compiled type with a field.
     *
     * @since 2.2.2
     */
    public static class Holder {
        /**
         * The label.
         */
        public String label = "held";
    }

    /**
     * A compiled superclass with a typed constructor.
     *
     * @since 2.2.2
     */
    public static class Labelled {
        /**
         * The label.
         */
        public final String label;

        /**
         * @param label The label
         */
        public Labelled(String label) {
            this.label = label;
        }
    }

    /**
     * A type annotation.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    public @interface TypeMarker {
    }

    /**
     * A nested annotation.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Nested {
        /**
         * @return The value
         */
        String value();
    }

    /**
     * Members of every kind.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    @SuppressWarnings("checkstyle:MissingJavadocMethod")
    public @interface Members {
        String[] texts();

        int[] numbers();

        String[] none();

        String[] emptyList();

        Class<?>[] types();

        RetentionPolicy[] policies();

        Class<?> type();

        char letter();

        float ratio();

        Nested nested();

        Nested[] nestedArray();
    }
}
