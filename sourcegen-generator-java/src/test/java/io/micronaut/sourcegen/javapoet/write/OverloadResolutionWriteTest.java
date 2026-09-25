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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.invoke;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Calls whose overload or inferred type arguments javac chooses differently from the method the model names: the
 * bytecode writer calls the method by its descriptor, the source has to make javac bind the same one. Covers generic
 * results and varargs, static members of parameterized owners, and receivers of a type variable or of another
 * parameterization. Every program is compiled and run.
 */
class OverloadResolutionWriteTest {

    // `return Optional.ofNullable(p0)` of an Object from an `Optional<String>` method: the return type makes javac infer
    // `T` as String, which the Object argument is not.
    @Test
    void genericResultReturnedAsParameterizedType() throws Exception {
        var ofNullable = Optional.class.getMethod("ofNullable", Object.class);
        var def = single("OptionalReturned", TypeDef.parameterized(Optional.class, String.class), List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(Optional.class).invokeStatic(ofNullable, p.get(0)).returning());
        assertEquals(Optional.of("a"), run(def, "a"));
    }

    // `Enum.valueOf(p0, "SECONDS")` with a `Class<?>`: javac infers no `T extends Enum<T>` from the capture.
    @Test
    void enumValueOfWildcardClass() throws Exception {
        var valueOf = Enum.class.getMethod("valueOf", Class.class, String.class);
        var def = single("EnumValueOf", Object.class, List.of(TypeDef.parameterized(ClassTypeDef.of(Class.class), TypeDef.wildcard())),
            (self, p) -> ClassTypeDef.of(Enum.class).invokeStatic(valueOf, p.get(0), ExpressionDef.constant("SECONDS")).returning());
        assertEquals(TimeUnit.SECONDS, run(def, TimeUnit.class));
    }

    // `Comparator.naturalOrder()` as a receiver has no target type to infer its variable from.
    @Test
    void genericResultAsReceiver() throws Exception {
        var naturalOrder = Comparator.class.getMethod("naturalOrder");
        var compare = Comparator.class.getMethod("compare", Object.class, Object.class);
        var def = single("NaturalOrderReceiver", int.class, List.of(TypeDef.STRING, TypeDef.STRING),
            (self, p) -> ClassTypeDef.of(Comparator.class).invokeStatic(naturalOrder).invoke(compare, p.get(0), p.get(1)).returning());
        assertEquals(-1, Integer.signum((Integer) run(def, "a", "b")));
    }

    // `EnumSet.of(E, E...)` with an Object and an empty array.
    @Test
    void genericVarargsWithObjectValue() throws Exception {
        var of = EnumSet.class.getMethod("of", Enum.class, Enum[].class);
        var def = single("EnumSetOf", Object.class, List.of(TypeDef.OBJECT),
            (self, p) -> ClassTypeDef.of(EnumSet.class).invokeStatic(of, p.get(0), TypeDef.of(Enum.class).array().instantiate()).returning());
        assertEquals(EnumSet.of(TimeUnit.SECONDS), run(def, TimeUnit.SECONDS));
    }

    // `Stream.of(Object)` with a String array: `Stream.of(p0)` binds `of(T...)`.
    @Test
    void streamOfSingleArrayValue() throws Exception {
        var of = Stream.class.getMethod("of", Object.class);
        var count = Stream.class.getMethod("count");
        var def = single("StreamOfArray", long.class, List.of(TypeDef.of(String[].class)),
            (self, p) -> ClassTypeDef.of(Stream.class).invokeStatic(of, p.get(0)).invoke(count).returning());
        assertEquals(1L, run(def, (Object) new String[]{"a", "b"}));
    }

    // `Math.max(float, float)` with two ints binds `max(int, int)`.
    @Test
    void mathMaxOfIntsKeepsFloatOverload() throws Exception {
        var max = Math.class.getMethod("max", float.class, float.class);
        var def = single("FloatMax", Object.class, List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
            (self, p) -> ClassTypeDef.of(Math.class).invokeStatic(max, p.get(0), p.get(1)).returning());
        assertEquals(2.0f, run(def, 1, 2));
    }

    // `compareTo(Object)` called on a String receiver: the signature is read from the bridge `compareTo(Object)` that
    // String declares, so the Object value is not cast to the `compareTo(String)` javac selects.
    @Test
    void bridgeSignatureOfConcreteReceiver() throws Exception {
        var compareTo = Comparable.class.getMethod("compareTo", Object.class);
        var def = single("BridgeReceiver", int.class, List.of(TypeDef.STRING, TypeDef.OBJECT),
            (self, p) -> p.get(0).invoke(compareTo, p.get(1)).returning());
        assertEquals(0, run(def, "a", "a"));
    }

    // A static method of a parameterized owner - as `ClassTypeDef.of(element)` builds it for an element with type
    // arguments - is called as `List<String>.of(p0)`.
    @Test
    void staticCallOnParameterizedOwner() throws Exception {
        var of = List.class.getMethod("of", Object.class);
        var def = single("ParameterizedStaticOwner", Object.class, List.of(TypeDef.STRING),
            (self, p) -> TypeDef.parameterized(List.class, String.class).invokeStatic(of, p.get(0)).returning());
        assertEquals(List.of("a"), run(def, "a"));
    }

    // As above for a static field: `Box<String>.NAME`.
    @Test
    void staticFieldOfParameterizedOwner() throws Exception {
        var t = TypeDef.variable("T");
        var name = FieldDef.builder("NAME", String.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(ExpressionDef.constant("box")).build();
        var box = ClassDef.builder("test.StaticFieldBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addField(name).build();
        var def = ClassDef.builder("test.ParameterizedStaticField").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> TypeDef.parameterized(box.asTypeDef(), TypeDef.STRING).getStaticField(name).returning())).build();
        try (var loader = compile(box, def)) {
            assertEquals("box", invoke(loader, def));
        }
    }

    // The model calls `<T> take(T)` with a String, beside `take(String)`: javac binds the more specific `take(String)`,
    // and a parameter of a variable is never cast to pin the overload.
    @Test
    void genericOverloadBesideSpecificOverload() throws Exception {
        var t = TypeDef.variable("T");
        var takeGeneric = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("value", t)
            .returns(String.class).build((self, p) -> ExpressionDef.constant("generic").returning());
        var takeString = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("value", String.class)
            .returns(String.class).build((self, p) -> ExpressionDef.constant("string").returning());
        var def = ClassDef.builder("test.GenericBesideSpecific").addModifiers(Modifier.PUBLIC).addMethod(takeGeneric).addMethod(takeString)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", String.class).returns(String.class)
                .build((self, p) -> self.invoke(takeGeneric, p.get(0)).returning())).build();
        assertEquals("generic", run(def, "a"));
    }

    // A receiver of a class variable `T extends List<String>`: `add(Object)` needs the value cast to String.
    @Test
    void objectPassedThroughVariableReceiver() throws Exception {
        var add = List.class.getMethod("add", Object.class);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, String.class));
        var def = ClassDef.builder("test.VariableBoundReceiver").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("p0", t).addParameter("p1", Object.class)
                .returns(boolean.class).build((self, p) -> p.get(0).invoke(add, p.get(1)).returning())).build();
        assertEquals(true, run(def, new ArrayList<>(), "a"));
    }

    // `p0.addAll(p1)` of a `List<String>` with a `List<Object>`: the parameter `Collection<? extends E>` is checked
    // for a raw conversion with `E` unbound, which accepts anything.
    @Test
    void mismatchedParameterizationThroughParameterizedReceiver() throws Exception {
        var addAll = List.class.getMethod("addAll", java.util.Collection.class);
        var def = single("ReceiverRawConversion", boolean.class,
            List.of(TypeDef.parameterized(List.class, String.class), TypeDef.parameterized(List.class, Object.class)),
            (self, p) -> p.get(0).invoke(addAll, p.get(1)).returning());
        var target = new ArrayList<Object>();
        assertEquals(true, run(def, target, List.of("a")));
        assertEquals(List.of("a"), target);
    }

    // As above for the constructor of a parameterized type: `new ArrayList<String>(p0)` of a `Collection<Object>`.
    @Test
    void mismatchedParameterizationThroughParameterizedConstructor() throws Exception {
        var constructor = ArrayList.class.getConstructor(java.util.Collection.class);
        var def = single("ConstructorRawConversion", Object.class, List.of(TypeDef.parameterized(java.util.Collection.class, Object.class)),
            (self, p) -> TypeDef.parameterized(ArrayList.class, String.class).instantiate(constructor, p.get(0)).returning());
        assertEquals(List.of("a"), run(def, List.of("a")));
    }

    @Test
    void generatedGenericSubtypeGetsRequiredRawConversion() throws Exception {
        var t = TypeDef.variable("T");
        var list = ClassDef.builder("test.GeneratedList").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(ArrayList.class), t)).build();
        var selected = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.parameterized(List.class, Object.class)).returns(int.class)
            .build((self, p) -> ExpressionDef.constant(1).returning());
        var def = ClassDef.builder("test.GeneratedListCall").addModifiers(Modifier.PUBLIC).addMethod(selected)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.parameterized(list.asTypeDef(), TypeDef.STRING)).returns(int.class)
                .build((self, p) -> self.invoke(selected, p.getFirst()).returning())).build();
        try (var loader = compile(list, def)) {
            var cls = loader.loadClass(def.getName());
            var listClass = loader.loadClass(list.getName());
            assertEquals(1, cls.getMethod("call", listClass).invoke(cls.getConstructor().newInstance(), listClass.getConstructor().newInstance()));
        }
    }

    @Test
    void compilerOnlyOwnerKeepsRequestedOverload() throws Exception {
        var element = mock(ClassElement.class);
        when(element.getName()).thenReturn("test.CompilerOnlyOverloads");
        when(element.getCanonicalName()).thenReturn("test.CompilerOnlyOverloads");
        when(element.getSimpleName()).thenReturn("CompilerOnlyOverloads");
        when(element.getPackageName()).thenReturn("test");
        var methods = List.of(compilerMethod(element, Object.class), compilerMethod(element, String.class));
        when(element.getEnclosedElements(any())).thenAnswer(invocation -> methods);
        var owner = ClassTypeDef.of(element);
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class).returns(String.class).build();
        var def = ClassDef.builder("test.CompilerOnlyCall").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("target", owner)
                .returns(String.class).build((self, p) -> p.getFirst().invoke(take, ExpressionDef.constant("x")).returning())).build();
        try (var loader = JavaCompileAssertions.compileAndLoad(render(def), """
            package test;
            public class CompilerOnlyOverloads {
                public String take(Object value) { return "selected"; }
                public String take(String value) { return "other"; }
            }
            """)) {
            var cls = loader.loadClass(def.getName());
            var target = loader.loadClass("test.CompilerOnlyOverloads");
            assertEquals("selected", cls.getMethod("call", target).invoke(cls.getConstructor().newInstance(), target.getConstructor().newInstance()));
        }
    }

    private static MethodElement compilerMethod(ClassElement owner, Class<?> parameterType) {
        var method = mock(MethodElement.class);
        when(method.getName()).thenReturn("take");
        when(method.getDeclaringType()).thenReturn(owner);
        when(method.getOwningType()).thenReturn(owner);
        when(method.isPublic()).thenReturn(true);
        when(method.getReturnType()).thenReturn(ClassElement.of(String.class));
        when(method.getGenericReturnType()).thenReturn(ClassElement.of(String.class));
        when(method.getParameters()).thenReturn(new ParameterElement[]{ParameterElement.of(parameterType, "value")});
        return method;
    }
}
