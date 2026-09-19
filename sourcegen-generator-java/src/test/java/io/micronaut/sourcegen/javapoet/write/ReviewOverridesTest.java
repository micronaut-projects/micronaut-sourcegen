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
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probes for override resolution and the supertype walk: every model declares the erased override the
 * bytecode writer accepts, and the Java source must compile and dispatch through the supertype as the bytecode does.
 *
 * @since 2.2.2
 */
public class ReviewOverridesTest {

    /** A compiled generic superclass whose method the generated child overrides erased. */
    public abstract static class CompiledBase<T> {
        public abstract T get();
    }

    /** A compiled generic middle type carrying its argument into a compiled base. */
    public abstract static class CompiledMiddle<T> extends ArrayList<List<T>> {
    }

    /**
     * A compiled generic method whose own variable lists {@code Object} ahead of its real bound: javac erases
     * {@code U} to {@code Object} (JLS 4.6, the leftmost bound), so its descriptor is {@code pick(Object, Object)}.
     */
    public static class ObjectBoundBase<T> {
        public <U extends Object & Comparable<U>> T pick(T value, U other) {
            return null;
        }
    }

    /** A compiled base of another package with a protected generic method. */
    public abstract static class ProtectedBase<T> {
        protected abstract T make();

        public T made() {
            return make();
        }
    }

    /** A compiled base of another package with a package-private generic method. */
    public static class PkgPrivateBase<T> {
        T make() {
            return null;
        }

        public Object made() {
            return make();
        }
    }

    private static URLClassLoaderHolder compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            sources.add(write(definition));
        }
        return new URLClassLoaderHolder(JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new)), sources);
    }

    private static String write(ObjectDef definition) throws IOException {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        return writer.toString();
    }

    /** The loader and the sources, so that a failing assertion can show what was compiled. */
    private record URLClassLoaderHolder(java.net.URLClassLoader loader, List<String> sources) implements AutoCloseable {
        Object newInstance(String name) throws Exception {
            var constructor = loader.loadClass(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }

        Class<?> load(String name) throws Exception {
            return loader.loadClass(name);
        }

        String source(int index) {
            return sources.get(index);
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }
    }

    private static MethodDef erased(String name, TypeDef returnType, Function<List<VariableDef.MethodParameter>, StatementDef> body, TypeDef... parameterTypes) {
        MethodDef.MethodDefBuilder builder = MethodDef.builder(name).addModifiers(Modifier.PUBLIC).overrides().returns(returnType);
        for (int i = 0; i < parameterTypes.length; i++) {
            builder.addParameter("p" + i, parameterTypes[i]);
        }
        return builder.build((self, p) -> body.apply(p));
    }

    private static InterfaceDef genericInterface(String name, String methodName, boolean returnsVariable, TypeDef... boundsOfT) {
        var t = boundsOfT.length == 0 ? TypeDef.variable("T") : TypeDef.variable("T", boundsOfT);
        return InterfaceDef.builder(name).addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder(methodName).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", t).returns(returnsVariable ? t : TypeDef.OBJECT).build())
            .build();
    }

    // ---- two generic interfaces with the same method ----

    /** `Object apply(Object)` for `Function<String, String>` and a generated `Transformer<String>` with `T apply(T)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void functionAndGeneratedTransformerWithTheSameMethod() throws Exception {
        var transformer = genericInterface("test.Transformer", "apply", true);
        var def = ClassDef.builder("test.Both").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addSuperinterface(TypeDef.parameterized(transformer.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).invoke("toUpperCase", TypeDef.STRING).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(transformer, def)) {
            Object instance = compiled.newInstance(def.getName());
            assertEquals("A", ((Function) instance).apply("a"));
            assertEquals("B", compiled.load(transformer.getName()).getMethod("apply", Object.class).invoke(instance, "b"));
        }
    }

    /** `int compareTo(Object)` for `Comparable<Self>` and a generated `Ordered<Self>` with `int compareTo(T)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void comparableAndGeneratedOrderedWithTheSameMethod() throws Exception {
        var t = TypeDef.variable("T");
        var ordered = InterfaceDef.builder("test.Ordered").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("other", t).returns(int.class).build())
            .build();
        var self = ClassTypeDef.of("test.Rank");
        var def = ClassDef.builder("test.Rank").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), self))
            .addSuperinterface(TypeDef.parameterized(ordered.asTypeDef(), self))
            .addMethod(erased("compareTo", TypeDef.Primitive.INT, p -> ExpressionDef.constant(-1).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(ordered, def)) {
            Object instance = compiled.newInstance(def.getName());
            assertEquals(-1, ((Comparable) instance).compareTo(instance));
            assertEquals(-1, compiled.load(ordered.getName()).getMethod("compareTo", Object.class).invoke(instance, instance));
        }
    }

    /** `Object get()` for `Supplier<String>` and a generated `Provider<CharSequence>`: the narrower `String get()`. */
    @Test
    void supplierAndGeneratedProviderOfAWiderType() throws Exception {
        var t = TypeDef.variable("T");
        var provider = InterfaceDef.builder("test.Provider").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var def = ClassDef.builder("test.Wider").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addSuperinterface(TypeDef.parameterized(provider.asTypeDef(), TypeDef.of(CharSequence.class)))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("x").returning()))
            .build();
        try (var compiled = compile(provider, def)) {
            assertTrue(compiled.source(1).contains("public String get()"), compiled.source(1));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("x", ((Supplier<?>) instance).get());
            assertEquals("x", compiled.load(provider.getName()).getMethod("get").invoke(instance));
        }
    }

    // ---- compiled generic superclasses ----

    /** `Object get(int)` and `int size()` in a class extending the compiled `AbstractList<String>`. */
    @Test
    void compiledGenericSuperclassAbstractList() throws Exception {
        var def = ClassDef.builder("test.Strings").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("item").returning(), TypeDef.Primitive.INT))
            .addMethod(erased("size", TypeDef.Primitive.INT, p -> ExpressionDef.constant(1).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public String get(int p0)"), compiled.source(0));
            List<?> list = (List<?>) compiled.newInstance(def.getName());
            assertEquals(List.of("item"), List.copyOf(list));
        }
    }

    /** `Object get(Object)` narrowed to `Integer get(Object)` next to a raw `Set entrySet()` in an `AbstractMap<String, Integer>`. */
    @Test
    void compiledGenericSuperclassAbstractMap() throws Exception {
        var def = ClassDef.builder("test.Counts").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(AbstractMap.class, String.class, Integer.class))
            .addMethod(erased("entrySet", ClassTypeDef.of(Set.class), p -> ClassTypeDef.of(Set.class).invokeStatic("of", ClassTypeDef.of(Set.class)).returning()))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant(7).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Integer get(Object p0)"), compiled.source(0));
            Map<?, ?> map = (Map<?, ?>) compiled.newInstance(def.getName());
            assertEquals(7, map.get("any"));
        }
    }

    /** The protected `Object initialValue()` of a `ThreadLocal<String>`. */
    @Test
    void threadLocalInitialValue() throws Exception {
        var def = ClassDef.builder("test.Local").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ThreadLocal.class, String.class))
            .addMethod(MethodDef.builder("initialValue").addModifiers(Modifier.PROTECTED).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("initial").returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("protected String initialValue()"), compiled.source(0));
            assertEquals("initial", ((ThreadLocal<?>) compiled.newInstance(def.getName())).get());
        }
    }

    /** `Object apply(Object)` for `UnaryOperator<String>`, whose `apply` is inherited from `Function`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unaryOperatorInheritsApplyFromFunction() throws Exception {
        var def = ClassDef.builder("test.Upper").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(UnaryOperator.class, String.class))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).invoke("toUpperCase", TypeDef.STRING).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertEquals("A", ((UnaryOperator) compiled.newInstance(def.getName())).apply("a"));
        }
    }

    // ---- generated generic superclasses ----

    /** A member `Base<T>` and a member `Impl extends Base<String>` in the same file, overriding `Object get()`. */
    @Test
    void generatedGenericSuperclassInTheSameFile() throws Exception {
        var t = TypeDef.variable("T");
        var base = ClassDef.builder("Base").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.ABSTRACT).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var impl = ClassDef.builder("Impl").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .superclass(TypeDef.parameterized(base.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("same file").returning()))
            .build();
        var outer = ClassDef.builder("test.Outer").addModifiers(Modifier.PUBLIC).addInnerType(base).addInnerType(impl).build();
        try (var compiled = compile(outer)) {
            assertTrue(compiled.source(0).contains("public String get()"), compiled.source(0));
            Object instance = compiled.newInstance("test.Outer$Impl");
            assertEquals("same file", compiled.load("test.Outer$Base").getMethod("get").invoke(instance));
        }
    }

    /** A generated `Parent<T> implements Supplier<T>` in another file and a `Child extends Parent<String>` overriding `Object get()`. */
    @Test
    void generatedGenericSuperclassInAnotherFile() throws Exception {
        var t = TypeDef.variable("T");
        var parent = ClassDef.builder("test.Parent").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .build();
        var child = ClassDef.builder("test.Child").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("other file").returning()))
            .build();
        try (var compiled = compile(parent, child)) {
            assertTrue(compiled.source(1).contains("public String get()"), compiled.source(1));
            assertEquals("other file", ((Supplier<?>) compiled.newInstance(child.getName())).get());
        }
    }

    /** Depth 3: `Child extends GenMiddle<String>`, `GenMiddle<T> extends CompiledBase<List<T>>`: `Object get()` is `List<String> get()`. */
    @Test
    void depthThreeMixingGeneratedAndCompiledTypes() throws Exception {
        var t = TypeDef.variable("T");
        var middle = ClassDef.builder("test.GenMiddle").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(CompiledBase.class), TypeDef.parameterized(ClassTypeDef.of(List.class), t)))
            .build();
        var child = ClassDef.builder("test.Deep").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(middle.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ClassTypeDef.of(List.class).invokeStatic("of", ClassTypeDef.of(List.class), ExpressionDef.constant("deep")).returning()))
            .build();
        try (var compiled = compile(middle, child)) {
            assertTrue(compiled.source(1).contains("public List<String> get()"), compiled.source(1));
            assertEquals(List.of("deep"), ((CompiledBase<?>) compiled.newInstance(child.getName())).get());
        }
    }

    /** Depth 3 through a compiled middle: `Child extends CompiledMiddle<String>` where `CompiledMiddle<T> extends ArrayList<List<T>>`. */
    @Test
    void depthThreeThroughACompiledMiddle() throws Exception {
        var child = ClassDef.builder("test.DeepCompiled").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(CompiledMiddle.class, String.class))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ClassTypeDef.of(List.class).invokeStatic("of", ClassTypeDef.of(List.class), ExpressionDef.constant("compiled")).returning(), TypeDef.Primitive.INT))
            .build();
        try (var compiled = compile(child)) {
            assertTrue(compiled.source(0).contains("public List<String> get(int p0)"), compiled.source(0));
            assertEquals(List.of("compiled"), ((List<?>) compiled.newInstance(child.getName())).get(3));
        }
    }

    /** A raw supertype in the middle: `Child extends GenMiddle` keeps `Object get()`, which is the erased member. */
    @Test
    void rawSupertypeInTheMiddleKeepsTheErasedOverride() throws Exception {
        var t = TypeDef.variable("T");
        var middle = ClassDef.builder("test.RawMiddle").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(CompiledBase.class), t))
            .build();
        var child = ClassDef.builder("test.RawChild").addModifiers(Modifier.PUBLIC)
            .superclass(middle.asTypeDef())
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("raw").returning()))
            .build();
        try (var compiled = compile(middle, child)) {
            assertTrue(compiled.source(1).contains("public Object get()"), compiled.source(1));
            assertEquals("raw", ((CompiledBase<?>) compiled.newInstance(child.getName())).get());
        }
    }

    // ---- type arguments of various shapes ----

    /** Array and primitive array type arguments: `Function<String[], int[]>`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void arrayTypeArguments() throws Exception {
        var def = ClassDef.builder("test.Lengths").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING.array(), TypeDef.Primitive.INT.array()))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> TypeDef.Primitive.INT.array().instantiate(List.of(p.get(0).cast(TypeDef.STRING.array()).arrayElement(0).invoke("length", TypeDef.Primitive.INT))).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int[] apply(String[] p0)"), compiled.source(0));
            int[] result = (int[]) ((Function) compiled.newInstance(def.getName())).apply(new String[]{"abc"});
            assertEquals(3, result[0]);
        }
    }

    /** A nested generic type argument, `Supplier<Map<String, List<Integer>>>`. */
    @Test
    void nestedGenericTypeArgument() throws Exception {
        var def = ClassDef.builder("test.Nested").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(ClassTypeDef.of(Map.class), TypeDef.STRING, TypeDef.parameterized(List.class, Integer.class))))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ClassTypeDef.of(Map.class).invokeStatic("of", ClassTypeDef.of(Map.class)).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Map<String, List<Integer>> get()"), compiled.source(0));
            assertEquals(Map.of(), ((Supplier<?>) compiled.newInstance(def.getName())).get());
        }
    }

    /** Wildcard-parameterized type arguments, `Function<Class<?>, List<? extends Number>>`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wildcardParameterizedTypeArguments() throws Exception {
        var def = ClassDef.builder("test.Wild").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class),
                TypeDef.parameterized(ClassTypeDef.of(Class.class), TypeDef.wildcard()),
                TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> ClassTypeDef.of(List.class).invokeStatic("of", ClassTypeDef.of(List.class), ExpressionDef.constant(1)).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public List<? extends Number> apply(Class<?> p0)"), compiled.source(0));
            assertEquals(List.of(1), ((Function) compiled.newInstance(def.getName())).apply(String.class));
        }
    }

    /** A generated `Arr<T>` with `int count(T[])` implemented for `Arr<String[]>`: `count(Object[])` is `count(String[][])`. */
    @Test
    void arrayOfATypeVariableBoundToAnArray() throws Exception {
        var t = TypeDef.variable("T");
        var arr = InterfaceDef.builder("test.Arr").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("values", t.array()).returns(int.class).build())
            .build();
        var def = ClassDef.builder("test.Matrix").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(arr.asTypeDef(), TypeDef.STRING.array()))
            .addMethod(erased("count", TypeDef.Primitive.INT, p -> p.get(0).cast(TypeDef.array(TypeDef.STRING, 2)).arrayElement(1).cast(TypeDef.STRING.array()).arrayElement(0).invoke("length", TypeDef.Primitive.INT).returning(), TypeDef.OBJECT.array()))
            .build();
        try (var compiled = compile(arr, def)) {
            assertTrue(compiled.source(1).contains("public int count(String[][] p0)"), compiled.source(1));
            Object instance = compiled.newInstance(def.getName());
            assertEquals(2, compiled.load(arr.getName()).getMethod("count", Object[].class).invoke(instance, (Object) new String[][]{{"a"}, {"bb"}}));
        }
    }

    // ---- type variables of the implementing class ----

    /** `Box<T extends Number> implements Comparable<T>` with `int compareTo(Object)`: `compareTo(T)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void boundedClassVariableInComparable() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var def = ClassDef.builder("test.NumberBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), t))
            .addMethod(erased("compareTo", TypeDef.Primitive.INT, p -> p.get(0).invoke("intValue", TypeDef.Primitive.INT).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int compareTo(T p0)"), compiled.source(0));
            assertEquals(42, ((Comparable) compiled.newInstance(def.getName())).compareTo(42));
        }
    }

    /** An intersection bound: `Box<T extends Number & Comparable<T>> implements Supplier<T>, Consumer<T>`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void intersectionBoundOfTheClassVariable() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var value = FieldDef.builder("value", t).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.IntersectionBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addField(value)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(void.class)
                .build((self, p) -> self.field(value).put(p.get(0))))
            .addMethod(MethodDef.builder("stored").addModifiers(Modifier.PUBLIC).returns(Object.class).build((self, p) -> self.field(value).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public T get()"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public void accept(T v)"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            ((Consumer) instance).accept(5);
            assertEquals(5, instance.getClass().getMethod("stored").invoke(instance));
        }
    }

    /** `Foo<T> implements Function<T, T>` with `Object apply(Object)`: the parameter stays, the return is `T`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void classVariableNamedLikeTheInterfaces() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.Same").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), t, t))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public T apply(Object p0)"), compiled.source(0));
            assertEquals("v", ((Function) compiled.newInstance(def.getName())).apply("v"));
        }
    }

    /**
     * A generated `Picker<T extends Object & Comparable<T>>`: the bytecode writer erases `T` to `Object`, its
     * leftmost bound (JLS 4.6), so the bytecode model declares `Object pick(Object)`, which the resolver rewrites
     * to `Integer pick(Integer)`. The written interface keeps the `Object` bound, so javac erases it to `Object`
     * as well, as it does for a compiled type: see reflectedMethodVariableListingObjectBeforeItsBound.
     */
    @Test
    void generatedVariableListingObjectBeforeItsBound() throws Exception {
        var t = TypeDef.variable("T", TypeDef.OBJECT, TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var picker = InterfaceDef.builder("test.Picker").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", t).returns(t).build())
            .build();
        var def = ClassDef.builder("test.IntPicker").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(picker.asTypeDef(), TypeDef.of(Integer.class)))
            .addMethod(erased("pick", TypeDef.OBJECT, p -> p.get(0).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(picker, def)) {
            assertTrue(compiled.source(0).contains("<T extends Object & Comparable<T>>"), compiled.source(0));
            assertTrue(compiled.source(1).contains("public Integer pick(Integer p0)"), compiled.source(1));
            Object instance = compiled.newInstance(def.getName());
            var pick = java.util.Arrays.stream(compiled.load(picker.getName()).getMethods()).filter(m -> m.getName().equals("pick")).findFirst().orElseThrow();
            assertEquals(Object.class, pick.getParameterTypes()[0]);
            assertEquals(3, pick.invoke(instance, 3));
        }
    }

    /**
     * A compiled `<U extends Object & Comparable<U>> T pick(T, U)` of `ObjectBoundBase<T>`: javac's descriptor is
     * `pick(Object, Object)`, which the model declares. The resolver erases `U` to `Comparable` and writes
     * `String pick(String, Comparable)`, an overload that overrides nothing: a call through the base reaches the
     * base. Expected: `String pick(String, Object)`, the erasure as a member of `ObjectBoundBase<String>`.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reflectedMethodVariableListingObjectBeforeItsBound() throws Exception {
        var def = ClassDef.builder("test.ObjectBoundChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ObjectBoundBase.class, String.class))
            .addMethod(erased("pick", TypeDef.OBJECT, p -> ExpressionDef.constant("child").returning(), TypeDef.OBJECT, TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            ObjectBoundBase<String> base = (ObjectBoundBase<String>) compiled.newInstance(def.getName());
            assertEquals("child", base.pick("value", 1), compiled.source(0));
        }
    }

    // ---- generic methods overridden erased ----

    /** `<T> T[] toArray(T[])` of `Collection` overridden as `Object[] toArray(Object[])` in an `AbstractList<String>`. */
    @Test
    void genericToArrayOverriddenErased() throws Exception {
        var def = ClassDef.builder("test.ToArray").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("item").returning(), TypeDef.Primitive.INT))
            .addMethod(erased("size", TypeDef.Primitive.INT, p -> ExpressionDef.constant(1).returning()))
            .addMethod(erased("toArray", TypeDef.OBJECT.array(), p -> TypeDef.STRING.array().instantiate(List.of(ExpressionDef.constant("overridden"))).returning(), TypeDef.OBJECT.array()))
            .build();
        try (var compiled = compile(def)) {
            List<?> list = (List<?>) compiled.newInstance(def.getName());
            assertEquals("overridden", list.toArray(new String[0])[0]);
        }
    }

    /** `<R> R accept(Visitor<R>)` of a generated interface overridden as `Object accept(Visitor)`. */
    @Test
    void genericVisitorMethodOverriddenErased() throws Exception {
        var r = TypeDef.variable("R");
        var visitor = InterfaceDef.builder("test.Visitor").addModifiers(Modifier.PUBLIC).addTypeVariable(r)
            .addMethod(MethodDef.builder("visit").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("node", Object.class).returns(r).build())
            .build();
        var node = InterfaceDef.builder("test.Node").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(r)
                .addParameter("visitor", TypeDef.parameterized(visitor.asTypeDef(), r)).returns(r).build())
            .build();
        var def = ClassDef.builder("test.Leaf").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(node.asTypeDef())
            .addMethod(erased("accept", TypeDef.OBJECT, p -> p.get(0).invoke("visit", TypeDef.OBJECT, ExpressionDef.constant("leaf")).returning(), ClassTypeDef.of(visitor)))
            .build();
        try (var compiled = compile(visitor, node, def)) {
            Object leaf = compiled.newInstance(def.getName());
            Object stringVisitor = java.lang.reflect.Proxy.newProxyInstance(compiled.loader(), new Class<?>[]{compiled.load(visitor.getName())},
                (proxy, method, args) -> method.getName().equals("visit") ? "visited " + args[0] : null);
            assertEquals("visited leaf", compiled.load(node.getName()).getMethod("accept", compiled.load(visitor.getName())).invoke(leaf, stringVisitor));
        }
    }

    // ---- covariant and primitive returns ----

    /** `Object get()` overriding `Number get()` of a generated superclass: the narrower `Number get()`, its value cast. */
    @Test
    void covariantReturnOverAGeneratedSuperclass() throws Exception {
        var source = ClassDef.builder("test.NumberSource").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(Number.class).build())
            .build();
        var def = ClassDef.builder("test.FiveSource").addModifiers(Modifier.PUBLIC).superclass(source.asTypeDef())
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant(5).returning()))
            .build();
        try (var compiled = compile(source, def)) {
            assertTrue(compiled.source(1).contains("public Number get()"), compiled.source(1));
            assertEquals(5, compiled.load(source.getName()).getMethod("get").invoke(compiled.newInstance(def.getName())));
        }
    }

    /** `Object text()` overriding `CharSequence text()` of a generated interface, returning a String. */
    @Test
    void covariantCharSequenceReturn() throws Exception {
        var text = InterfaceDef.builder("test.Text").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("text").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(CharSequence.class).build())
            .build();
        var def = ClassDef.builder("test.Hello").addModifiers(Modifier.PUBLIC).addSuperinterface(text.asTypeDef())
            .addMethod(erased("text", TypeDef.OBJECT, p -> ExpressionDef.constant("hello").returning()))
            .build();
        try (var compiled = compile(text, def)) {
            assertEquals("hello", compiled.load(text.getName()).getMethod("text").invoke(compiled.newInstance(def.getName())));
        }
    }

    /** Primitive returns and parameters through `Comparable<Self>`, `IntSupplier` and `ToIntFunction<String>`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void primitiveReturnsThroughGenericAndNonGenericInterfaces() throws Exception {
        var self = ClassTypeDef.of("test.Prim");
        var def = ClassDef.builder("test.Prim").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), self))
            .addSuperinterface(ClassTypeDef.of(IntSupplier.class))
            .addSuperinterface(TypeDef.parameterized(ToIntFunction.class, String.class))
            .addMethod(erased("compareTo", TypeDef.Primitive.INT, p -> ExpressionDef.constant(1).returning(), TypeDef.OBJECT))
            .addMethod(erased("getAsInt", TypeDef.Primitive.INT, p -> ExpressionDef.constant(2).returning()))
            .addMethod(erased("applyAsInt", TypeDef.Primitive.INT, p -> p.get(0).invoke("length", TypeDef.Primitive.INT).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int applyAsInt(String p0)"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            assertEquals(1, ((Comparable) instance).compareTo(instance));
            assertEquals(2, ((IntSupplier) instance).getAsInt());
            assertEquals(3, ((ToIntFunction) instance).applyAsInt("abc"));
        }
    }

    // ---- Object methods ----

    /** `equals`, `hashCode`, `toString` next to `compare(Object, Object)` in a `Comparator<String>`, which declares `equals` too. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void objectMethodsNextToAComparatorDeclaringEquals() throws Exception {
        var def = ClassDef.builder("test.ByLength").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Comparator.class, String.class))
            .addMethod(erased("compare", TypeDef.Primitive.INT, p -> p.get(0).invoke("length", TypeDef.Primitive.INT)
                .math(ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION, p.get(1).invoke("length", TypeDef.Primitive.INT)).returning(), TypeDef.OBJECT, TypeDef.OBJECT))
            .addMethod(erased("equals", TypeDef.Primitive.BOOLEAN, p -> p.get(0).instanceOf(ClassTypeDef.of("test.ByLength")).returning(), TypeDef.OBJECT))
            .addMethod(erased("hashCode", TypeDef.Primitive.INT, p -> ExpressionDef.constant(11).returning()))
            .addMethod(erased("toString", TypeDef.STRING, p -> ExpressionDef.constant("ByLength").returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int compare(String p0, String p1)"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public boolean equals(Object p0)"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            assertEquals(2, ((Comparator) instance).compare("abc", "a"));
            assertTrue(instance.equals(compiled.newInstance(def.getName())));
            assertEquals(11, instance.hashCode());
            assertEquals("ByLength", instance.toString());
        }
    }

    /** `Object clone()` marked as an override in a `Cloneable` that also implements `Supplier<String>`. */
    @Test
    void cloneReturningObjectNextToAGenericInterface() throws Exception {
        var def = ClassDef.builder("test.Copy").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(Cloneable.class))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(erased("clone", TypeDef.OBJECT, p -> ExpressionDef.constant("copy").returning()))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("got").returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Object clone()"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("copy", instance.getClass().getMethod("clone").invoke(instance));
            assertEquals("got", ((Supplier<?>) instance).get());
        }
    }

    // ---- what is and is not an override ----

    /** A method without `overrides()` is written as declared; one marked `overrides()` that overrides nothing is kept too. */
    @Test
    void methodsThatAreNotErasedOverridesAreNotRewritten() throws Exception {
        var handler = ClassDef.builder("test.Handler").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("handle").addModifiers(Modifier.PUBLIC).addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("base").returning()))
            .build();
        var def = ClassDef.builder("test.Handling").addModifiers(Modifier.PUBLIC).superclass(handler.asTypeDef())
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            // Not marked as an override: written as declared, which still overrides `handle(Object)`
            .addMethod(MethodDef.builder("handle").addModifiers(Modifier.PUBLIC).addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            // Marked as an override of nothing
            .addMethod(erased("compute", TypeDef.OBJECT, p -> p.get(0).returning(), TypeDef.OBJECT))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("got").returning()))
            .build();
        try (var compiled = compile(handler, def)) {
            assertTrue(compiled.source(1).contains("public Object handle(Object v)"), compiled.source(1));
            assertTrue(compiled.source(1).contains("public Object compute(Object p0)"), compiled.source(1));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("child", compiled.load(handler.getName()).getMethod("handle", Object.class).invoke(instance, "x"));
        }
    }

    /** A protected generic method inherited from a compiled class of another package, called from the child too. */
    @Test
    void protectedMethodInheritedFromAnotherPackage() throws Exception {
        var make = MethodDef.builder("make").addModifiers(Modifier.PROTECTED).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.constant("made").returning());
        var def = ClassDef.builder("test.Maker").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ProtectedBase.class, String.class))
            .addMethod(make)
            .addMethod(MethodDef.builder("twice").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> self.invoke(make).invoke("repeat", TypeDef.STRING, ExpressionDef.constant(2)).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("protected String make()"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("made", ((ProtectedBase<?>) instance).made());
            assertEquals("mademade", instance.getClass().getMethod("twice").invoke(instance));
        }
    }

    /** A package-private generic method of a compiled class of another package is not overridden: the override is a new method. */
    @Test
    void packagePrivateMethodOfAnotherPackageIsNotOverridden() throws Exception {
        var def = ClassDef.builder("test.PkgMaker").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(PkgPrivateBase.class, String.class))
            .addMethod(MethodDef.builder("make").overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("made").returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("Object make()"), compiled.source(0));
            // As in bytecode: a package-private method is not overridden from another package
            assertNull(((PkgPrivateBase<?>) compiled.newInstance(def.getName())).made());
        }
    }

    /** A default method of a generated interface re-implemented erased. */
    @Test
    void defaultMethodOfAGeneratedInterfaceReimplemented() throws Exception {
        var t = TypeDef.variable("T");
        var echo = InterfaceDef.builder("test.Echo").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).addParameter("v", t).returns(t)
                .build((self, p) -> p.get(0).returning()))
            .build();
        var def = ClassDef.builder("test.LoudEcho").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(echo.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("echo", TypeDef.OBJECT, p -> p.get(0).invoke("toUpperCase", TypeDef.STRING).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(echo, def)) {
            assertTrue(compiled.source(1).contains("public String echo(String p0)"), compiled.source(1));
            assertEquals("A", compiled.load(echo.getName()).getMethod("echo", Object.class).invoke(compiled.newInstance(def.getName()), "a"));
        }
    }

    // ---- records, enums, member types ----

    /** A record implementing `Comparable<Rec>` and `Supplier<String>` with the erased signatures. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordImplementingComparableAndSupplier() throws Exception {
        var rec = ClassTypeDef.of("test.Rec");
        var def = RecordDef.builder("test.Rec").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(String.class).build())
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), rec))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(erased("compareTo", TypeDef.Primitive.INT, p -> ExpressionDef.constant(3).returning(), TypeDef.OBJECT))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field("value", TypeDef.STRING).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int compareTo(Rec p0)"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public String get()"), compiled.source(0));
            Object instance = compiled.load(def.getName()).getConstructor(String.class).newInstance("v");
            assertEquals(3, ((Comparable) instance).compareTo(instance));
            assertEquals("v", ((Supplier<?>) instance).get());
        }
    }

    /** An enum implementing `Function<String, String>` and a generated `Labeled<String>` with erased methods. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void enumImplementingGenericInterfaces() throws Exception {
        var labeled = genericInterface("test.Labeled", "label", true);
        var def = EnumDef.builder("test.Mode").addModifiers(Modifier.PUBLIC).addEnumConstant("ON").addEnumConstant("OFF")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addSuperinterface(TypeDef.parameterized(labeled.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).invoke("concat", TypeDef.STRING, ExpressionDef.constant("!")).returning(), TypeDef.OBJECT))
            .addMethod(MethodDef.builder("label").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke("name", TypeDef.STRING).invoke("concat", TypeDef.STRING, p.get(0)).returning()))
            .build();
        try (var compiled = compile(labeled, def)) {
            Object off = compiled.load(def.getName()).getField("OFF").get(null);
            assertEquals("a!", ((Function) off).apply("a"));
            assertEquals("OFF:", compiled.load(labeled.getName()).getMethod("label", Object.class).invoke(off, ":"));
        }
    }

    /**
     * A member class implementing `Supplier<T>` with the type variable of its outer class, overriding `Object get()`.
     * The bytecode writer gives a member class no outer instance, so the model's inner class is a static nested class
     * of the source, where `T` of the outer class is not in scope: the variable is erased to its bound, and
     * `Object get()` implements `Supplier<Object>`, as the bytecode does.
     */
    @Test
    void innerClassOverridingWithTheOuterClassVariable() throws Exception {
        var t = TypeDef.variable("T");
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.nullValue().returning()))
            .build();
        var outer = ClassDef.builder("test.Holder").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addInnerType(inner).build();
        try (var compiled = compile(outer)) {
            assertTrue(compiled.source(0).contains("public static class Inner implements Supplier<Object>"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public Object get()"), compiled.source(0));
            Object instance = compiled.newInstance("test.Holder$Inner");
            assertNull(((Supplier<?>) instance).get());
        }
    }

    /** A static nested class implementing a generic member interface of its outer class, `Impl implements Inner<String>`. */
    @Test
    void nestedClassImplementingAMemberInterface() throws Exception {
        var t = TypeDef.variable("T");
        var inner = InterfaceDef.builder("Inner").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var impl = ClassDef.builder("Impl").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addSuperinterface(TypeDef.parameterized(inner.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("member").returning()))
            .build();
        var outer = ClassDef.builder("test.Members").addModifiers(Modifier.PUBLIC).addInnerType(inner).addInnerType(impl).build();
        try (var compiled = compile(outer)) {
            assertTrue(compiled.source(0).contains("public String get()"), compiled.source(0));
            assertEquals("member", compiled.load("test.Members$Inner").getMethod("get").invoke(compiled.newInstance("test.Members$Impl")));
        }
    }

    /** A supertype known only by name, without a VisitorContext: the erased signature is kept and nothing crashes. */
    @Test
    void supertypeKnownOnlyByNameKeepsTheErasedSignature() throws Exception {
        var def = ClassDef.builder("test.ByName").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of("test.Unknown"), TypeDef.STRING))
            .addSuperinterface(ClassTypeDef.of("test.UnknownInterface"))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.nullValue().returning()))
            .build();
        String source = write(def);
        assertTrue(source.contains("public Object get()"), source);
    }

    // ---- overloads, annotations, bodies ----

    /** Several overloads where only `apply(Object)` overrides `Function<String, String>.apply`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onlyOneOverloadIsAnOverride() throws Exception {
        var applyInteger = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).addParameter("n", Integer.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("integer").returning());
        var applyTwo = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).addParameter("a", Object.class).addParameter("b", Object.class).returns(Object.class)
            .build((self, p) -> ExpressionDef.constant("two").returning());
        var def = ClassDef.builder("test.Overloads").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(applyInteger).addMethod(applyTwo)
            .addMethod(erased("apply", TypeDef.OBJECT, p -> ExpressionDef.constant("one").returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public String apply(String p0)"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public String apply(Integer n)"), compiled.source(0));
            assertTrue(compiled.source(0).contains("public Object apply(Object a, Object b)"), compiled.source(0));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("one", ((Function) instance).apply("s"));
            assertEquals("integer", instance.getClass().getMethod("apply", Integer.class).invoke(instance, 1));
        }
    }

    /** Annotations, `throws` and parameter names of the override are kept when its signature is rewritten. */
    @Test
    void annotationsThrowsAndParameterNamesAreKept() throws Exception {
        var t = TypeDef.variable("T");
        var thrower = InterfaceDef.builder("test.Thrower").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("v", t).returns(t)
                .addThrows(TypeDef.of(IOException.class)).build())
            .build();
        var def = ClassDef.builder("test.Caller").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(thrower.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).overrides().addAnnotation(Deprecated.class)
                .addThrows(TypeDef.of(IOException.class))
                .addParameter(ParameterDef.builder("input", TypeDef.OBJECT).addAnnotation(Deprecated.class).build())
                .returns(Object.class)
                .build((self, p) -> ClassTypeDef.of(IOException.class).instantiate(p.get(0).cast(String.class)).doThrow()))
            .build();
        try (var compiled = compile(thrower, def)) {
            String source = compiled.source(1);
            assertTrue(source.contains("@Deprecated\n  public String call(@Deprecated String input) throws IOException"), source);
            Object instance = compiled.newInstance(def.getName());
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> compiled.load(thrower.getName()).getMethod("call", Object.class).invoke(instance, "boom"));
            assertEquals("boom", thrown.getCause().getMessage());
        }
    }

    /** The narrowed parameter assigned to an Object local, passed to `List.add(Object)`, and returned as the narrowed type. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void narrowedParameterThroughAnObjectLocal() throws Exception {
        var items = FieldDef.builder("items", TypeDef.parameterized(List.class, Object.class)).addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(ClassTypeDef.of(ArrayList.class).instantiate()).build();
        var local = new VariableDef.Local("o", TypeDef.OBJECT);
        var def = ClassDef.builder("test.ObjectLocal").addModifiers(Modifier.PUBLIC).addField(items)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> StatementDef.multi(
                    new StatementDef.DefineAndAssign(local, p.get(0)),
                    self.field(items).invoke("add", TypeDef.Primitive.BOOLEAN, local),
                    local.returning())))
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> self.field(items).invoke("size", TypeDef.Primitive.INT).returning()))
            .build();
        try (var compiled = compile(def)) {
            Object instance = compiled.newInstance(def.getName());
            assertEquals("v", ((Function) instance).apply("v"));
            assertEquals(1, instance.getClass().getMethod("count").invoke(instance));
        }
    }

    /** The narrowed parameter passed to the `pick(Object)` overload the model names, next to `pick(String)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void narrowedParameterPassedToTheObjectOverload() throws Exception {
        var pickObject = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("v", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var pickString = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("v", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("string").returning());
        var def = ClassDef.builder("test.PickOverload").addModifiers(Modifier.PUBLIC).addMethod(pickObject).addMethod(pickString)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(pickObject, p.get(0)).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertEquals("object", ((Function) compiled.newInstance(def.getName())).apply("v"));
        }
    }

    /** An abstract erased override in an abstract class, implemented erased again by its subclass. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void abstractErasedOverrideImplementedBySubclass() throws Exception {
        var abs = ClassDef.builder("test.Abs").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides().addParameter("v", Object.class).returns(Object.class).build())
            .build();
        var concrete = ClassDef.builder("test.Concrete").addModifiers(Modifier.PUBLIC).superclass(abs.asTypeDef())
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).invoke("trim", TypeDef.STRING).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(abs, concrete)) {
            assertTrue(compiled.source(0).contains("public abstract String apply(String v)"), compiled.source(0));
            assertTrue(compiled.source(1).contains("public String apply(String p0)"), compiled.source(1));
            assertEquals("t", ((Function) compiled.newInstance(concrete.getName())).apply(" t "));
        }
    }

    /** An interface redeclaring `Supplier<String>.get` erased, implemented erased by a class. */
    @Test
    void interfaceRedeclaringAnErasedOverride() throws Exception {
        var str = InterfaceDef.builder("test.Str").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides().returns(Object.class).build())
            .build();
        var def = ClassDef.builder("test.StrImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(str.asTypeDef())
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("s").returning()))
            .build();
        try (var compiled = compile(str, def)) {
            assertTrue(compiled.source(0).contains("String get();"), compiled.source(0));
            assertEquals("s", ((Supplier<?>) compiled.newInstance(def.getName())).get());
        }
    }

    /** `Map.Entry<String, Integer>`, a member interface of a compiled class, with `Object setValue(Object)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void memberInterfaceOfACompiledClass() throws Exception {
        var def = ClassDef.builder("test.Pair").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Map.Entry.class, String.class, Integer.class))
            .addMethod(erased("getKey", TypeDef.OBJECT, p -> ExpressionDef.constant("k").returning()))
            .addMethod(erased("getValue", TypeDef.OBJECT, p -> ExpressionDef.constant(1).returning()))
            .addMethod(erased("setValue", TypeDef.OBJECT, p -> p.get(0).returning(), TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Integer setValue(Integer p0)"), compiled.source(0));
            Map.Entry entry = (Map.Entry) compiled.newInstance(def.getName());
            assertEquals("k", entry.getKey());
            assertEquals(1, entry.getValue());
            assertEquals(2, entry.setValue(2));
        }
    }

    /** A generated `Factory<T>` with `T create(Class<? extends T>)` implemented as `Object create(Class)`: the raw parameter stays. */
    @Test
    void wildcardBoundedParameterKeptRaw() throws Exception {
        var t = TypeDef.variable("T");
        var factory = InterfaceDef.builder("test.Factory").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("type", TypeDef.parameterized(ClassTypeDef.of(Class.class), TypeDef.wildcardSubtypeOf(t))).returns(t).build())
            .build();
        var def = ClassDef.builder("test.StringFactory").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(factory.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("create", TypeDef.OBJECT, p -> p.get(0).invoke("getSimpleName", TypeDef.STRING).returning(), ClassTypeDef.of(Class.class)))
            .build();
        try (var compiled = compile(factory, def)) {
            assertTrue(compiled.source(1).contains("public String create(Class p0)"), compiled.source(1));
            assertEquals("String", compiled.load(factory.getName()).getMethod("create", Class.class).invoke(compiled.newInstance(def.getName()), String.class));
        }
    }

    /** `Iterable<String>` with a raw `Iterator iterator()`: a valid override as is. */
    @Test
    void rawReturnForAParameterizedOneIsKept() throws Exception {
        var def = ClassDef.builder("test.Iter").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Iterable.class, String.class))
            .addMethod(erased("iterator", ClassTypeDef.of(Iterator.class), p -> ClassTypeDef.of(List.class)
                .invokeStatic("of", ClassTypeDef.of(List.class), ExpressionDef.constant("i")).invoke("iterator", ClassTypeDef.of(Iterator.class)).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Iterator iterator()"), compiled.source(0));
            assertEquals("i", ((Iterable<?>) compiled.newInstance(def.getName())).iterator().next());
        }
    }

    // ---- calls to the rewritten methods from other generated classes ----

    /** Calls through parameterized, raw, wildcard and type-variable receivers to `accept(T)` and `T get()` of a `Box<T extends Number>`. */
    @Test
    void callsThroughReceiversOfEveryKind() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var value = FieldDef.builder("value", t).addModifiers(Modifier.PRIVATE).build();
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(void.class)
            .build((self, p) -> self.field(value).put(p.get(0)));
        var get = erased("get", TypeDef.OBJECT, p -> ExpressionDef.nullValue().returning());
        var box = ClassDef.builder("test.NumBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addField(value)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(accept)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class).build((self, p) -> self.field(value).returning()))
            .build();
        var u = TypeDef.variable("U", TypeDef.of(Number.class));
        var calls = ClassDef.builder("test.NumCalls").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("parameterized").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), TypeDef.of(Integer.class))).addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> StatementDef.multi(p.get(0).invoke(accept, p.get(1)), p.get(0).invoke(get).returning())))
            .addMethod(MethodDef.builder("raw").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("box", box.asTypeDef()).addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> StatementDef.multi(p.get(0).invoke(accept, p.get(1)), p.get(0).invoke(get).returning())))
            .addMethod(MethodDef.builder("wildcard").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), TypeDef.wildcard())).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(get).returning()))
            .addMethod(MethodDef.builder("variable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(u)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), u)).addParameter("v", Object.class).returns(Object.class)
                .build((self, p) -> StatementDef.multi(p.get(0).invoke(accept, p.get(1)), p.get(0).invoke(get).returning())))
            .build();
        try (var compiled = compile(box, calls)) {
            Class<?> boxClass = compiled.load(box.getName());
            Class<?> callsClass = compiled.load(calls.getName());
            assertEquals(1, callsClass.getMethod("parameterized", boxClass, Object.class).invoke(null, compiled.newInstance(box.getName()), 1));
            assertEquals(2L, callsClass.getMethod("raw", boxClass, Object.class).invoke(null, compiled.newInstance(box.getName()), 2L));
            assertEquals(3.0, callsClass.getMethod("variable", boxClass, Object.class).invoke(null, compiled.newInstance(box.getName()), 3.0));
            assertNull(callsClass.getMethod("wildcard", boxClass).invoke(null, compiled.newInstance(box.getName())));
        }
    }

    /** A call to a narrowed method inherited through two generated classes, `GrandChild extends Child<String>`, `Child<U> extends Parent<List<U>>`. */
    @Test
    void callToANarrowedMethodInheritedThroughTwoGeneratedClasses() throws Exception {
        var t = TypeDef.variable("T");
        var get = erased("get", TypeDef.OBJECT, p -> ExpressionDef.nullValue().returning());
        var parent = ClassDef.builder("test.GParent").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(get)
            .build();
        var u = TypeDef.variable("U");
        var child = ClassDef.builder("test.GChild").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.parameterized(ClassTypeDef.of(List.class), u)))
            .build();
        var grandChild = ClassDef.builder("test.GGrandChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(child.asTypeDef(), TypeDef.STRING))
            .build();
        var size = MethodDef.builder("size").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("target", grandChild.asTypeDef()).returns(int.class)
            .build((self, p) -> p.get(0).invoke(get).cast(TypeDef.parameterized(List.class, String.class)).invoke("size", TypeDef.Primitive.INT).returning());
        var caller = ClassDef.builder("test.GCaller").addModifiers(Modifier.PUBLIC).addMethod(size).build();
        try (var compiled = compile(parent, child, grandChild, caller)) {
            assertTrue(compiled.source(0).contains("public T get()"), compiled.source(0));
            Object target = compiled.newInstance(grandChild.getName());
            assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> compiled.load(caller.getName()).getMethod("size", target.getClass()).invoke(null, target));
        }
    }

    // ---- a listed limit, checked to fail loudly ----

    /**
     * Listed limit: `Parent<T> implements Consumer<T>` keeps `accept(Object)`, and `Child extends Parent<String>`
     * overrides it erased again. There is no Java signature for the child's method: it must fail to compile
     * rather than compile as an unrelated overload.
     */
    @Test
    void childOverridingWhatItsGenericParentKeptErasedFailsLoudly() throws Exception {
        var t = TypeDef.variable("T");
        var parent = ClassDef.builder("test.KeptParent").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(void.class)
                .build((self, p) -> StatementDef.multi()))
            .build();
        var child = ClassDef.builder("test.KeptChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(void.class)
                .build((self, p) -> StatementDef.multi()))
            .build();
        String parentSource = write(parent);
        String childSource = write(child);
        assertTrue(parentSource.contains("public void accept(Object v)"), parentSource);
        assertThrows(AssertionFailedError.class, () -> JavaCompileAssertions.assertCompiles(parentSource, childSource), childSource);
    }

    /** The definition of an enum lists `Enum<E>` in the walk: `Object get()` next to `Comparable` from `Enum` resolves through `Supplier<Mode>`. */
    @Test
    void enumSupplyingItself() throws Exception {
        var type = ClassTypeDef.of("test.Cycle");
        var def = EnumDef.builder("test.Cycle").addModifiers(Modifier.PUBLIC).addEnumConstant("A").addEnumConstant("B")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), type))
            .addMethod(erased("get", TypeDef.OBJECT, p -> type.getStaticField("B", type).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Cycle get()"), compiled.source(0));
            Object a = compiled.load(def.getName()).getField("A").get(null);
            assertSame(compiled.load(def.getName()).getField("B").get(null), ((Supplier<?>) a).get());
        }
    }

    // ---- second batch ----

    /** Three interfaces: `Supplier<CharSequence>`, a generated `Provider<Object>` and `Producer<String>`: `String get()`. */
    @Test
    void mostSpecificOfThreeInheritedReturnTypes() throws Exception {
        var t = TypeDef.variable("T");
        var provider = InterfaceDef.builder("test.Provider3").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build()).build();
        var producer = InterfaceDef.builder("test.Producer3").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build()).build();
        var def = ClassDef.builder("test.Three").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, CharSequence.class))
            .addSuperinterface(TypeDef.parameterized(provider.asTypeDef(), TypeDef.OBJECT))
            .addSuperinterface(TypeDef.parameterized(producer.asTypeDef(), TypeDef.STRING))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("three").returning()))
            .build();
        try (var compiled = compile(provider, producer, def)) {
            assertTrue(compiled.source(2).contains("public String get()"), compiled.source(2));
            Object instance = compiled.newInstance(def.getName());
            assertEquals("three", ((Supplier<?>) instance).get());
            assertEquals("three", compiled.load(producer.getName()).getMethod("get").invoke(instance));
        }
    }

    /** `Box<T> implements Supplier<T[]>` with `Object[] get()`: `T[] get()`. */
    @Test
    void arrayOfTheClassVariableAsReturn() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.ArrayBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t.array()))
            .addMethod(erased("get", TypeDef.OBJECT.array(), p -> TypeDef.OBJECT.array().instantiate(List.of(ExpressionDef.constant("e"))).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public T[] get()"), compiled.source(0));
            assertEquals("e", ((Object[]) ((Supplier<?>) compiled.newInstance(def.getName())).get())[0]);
        }
    }

    /** `BiFunction<Integer, Long, Boolean>` with `Object apply(Object, Object)`, boxed primitives as type arguments. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void boxedTypeArgumentsOfABiFunction() throws Exception {
        var def = ClassDef.builder("test.Greater").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(java.util.function.BiFunction.class, Integer.class, Long.class, Boolean.class))
            .addMethod(erased("apply", TypeDef.OBJECT, p -> p.get(0).invoke("longValue", TypeDef.Primitive.LONG)
                .compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, p.get(1).invoke("longValue", TypeDef.Primitive.LONG)).returning(), TypeDef.OBJECT, TypeDef.OBJECT))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public Boolean apply(Integer p0, Long p1)"), compiled.source(0));
            assertEquals(true, ((java.util.function.BiFunction) compiled.newInstance(def.getName())).apply(5, 2L));
        }
    }

    /** `Callable<String>` with `Object call() throws Exception`: the throws clause is kept on the rewritten method. */
    @Test
    void callableKeepsItsThrowsClause() throws Exception {
        var def = ClassDef.builder("test.Task").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(java.util.concurrent.Callable.class, String.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class).addThrows(TypeDef.of(Exception.class))
                .build((self, p) -> ExpressionDef.constant("called").returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public String call() throws Exception"), compiled.source(0));
            assertEquals("called", ((java.util.concurrent.Callable<?>) compiled.newInstance(def.getName())).call());
        }
    }

    /** A default method of a generated interface calls its own erased redeclaration of `Supplier<String>.get`, narrowed to `String get()`. */
    @Test
    void interfaceDefaultMethodCallsItsNarrowedAbstractMethod() throws Exception {
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides().returns(Object.class).build();
        var str = InterfaceDef.builder("test.Lengthy").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(get)
            .addMethod(MethodDef.builder("length").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(int.class)
                .build((self, p) -> self.invoke(get).cast(String.class).invoke("length", TypeDef.Primitive.INT).returning()))
            .build();
        var def = ClassDef.builder("test.LengthyImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(str.asTypeDef())
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("four").returning()))
            .build();
        try (var compiled = compile(str, def)) {
            assertEquals(4, compiled.load(str.getName()).getMethod("length").invoke(compiled.newInstance(def.getName())));
        }
    }

    /** A generic record `Rec<T extends Comparable<T>>(T value) implements Comparable<Rec<T>>` with `int compareTo(Object)`. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void genericRecordComparableToItself() throws Exception {
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var recOfT = TypeDef.parameterized(ClassTypeDef.of("test.GRec"), t);
        var def = RecordDef.builder("test.GRec").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addProperty(PropertyDef.builder("value").ofType(t).build())
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), recOfT))
            .addMethod(MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC).overrides().addParameter("other", Object.class).returns(int.class)
                .build((self, p) -> self.field("value", t).invoke("compareTo", TypeDef.Primitive.INT,
                    p.get(0).cast(recOfT).invoke("value", t)).returning()))
            .build();
        try (var compiled = compile(def)) {
            assertTrue(compiled.source(0).contains("public int compareTo(GRec<T> other)"), compiled.source(0));
            var constructor = compiled.load(def.getName()).getConstructor(Comparable.class);
            assertEquals(1, ((Comparable) constructor.newInstance(2)).compareTo(constructor.newInstance(1)));
        }
    }

    /** A generated base of another package with a package-private generic method: the child's method overrides nothing and is kept. */
    @Test
    void packagePrivateMethodOfAGeneratedBaseOfAnotherPackage() throws Exception {
        var t = TypeDef.variable("T");
        var made = MethodDef.builder("made").addModifiers(Modifier.PUBLIC).returns(Object.class).build();
        var base = ClassDef.builder("other.GenPkgBase").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("make").returns(t).build((self, p) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("made").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> self.invoke("make", t).returning()))
            .build();
        var def = ClassDef.builder("test.OtherPkgMaker").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(base.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("make").overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("child").returning()))
            .build();
        try (var compiled = compile(base, def)) {
            assertTrue(compiled.source(1).contains("Object make()"), compiled.source(1));
            assertNull(compiled.load(base.getName()).getMethod("made").invoke(compiled.newInstance(def.getName())));
        }
    }

    /** A top-level class named `Foo$Intercepted` in the package of a compiled base with a package-private `T make()`. */
    @Test
    void dollarNamedClassOverridesAPackagePrivateMethodOfItsPackage() throws Exception {
        String packageName = ReviewOverridesTest.class.getPackageName();
        var def = ClassDef.builder(packageName + ".Foo$Intercepted").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(OverrideResolutionRegressionTest.PkgBase.class, String.class))
            .addMethod(MethodDef.builder("make").overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("intercepted").returning()))
            .build();
        try (var compiled = compile(def)) {
            // Loaded by another class loader the class is of another runtime package, so the override is only
            // checked as source: javac accepts `String make()` as the override of the package-private `T make()`
            assertTrue(compiled.source(0).contains("  String make() {"), compiled.source(0));
            assertNotNull(compiled.newInstance(def.getName()));
        }
    }

    /**
     * Listed limit: a call through a receiver typed by a wildcard, `NumBox<? super Integer>`, to `accept(T)` narrowed
     * from `accept(Object)` is not converted. Java would take `(Integer) v`; the unconverted call must fail to compile
     * rather than run differently.
     */
    @Test
    void callThroughALowerBoundedWildcardReceiverFailsLoudly() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", Object.class).returns(void.class)
            .build((self, p) -> StatementDef.multi());
        var box = ClassDef.builder("test.LowerBox").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(accept)
            .build();
        var caller = ClassDef.builder("test.LowerCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class)))).addParameter("v", Object.class).returns(void.class)
                .build((self, p) -> p.get(0).invoke(accept, p.get(1))))
            .build();
        String boxSource = write(box);
        String callerSource = write(caller);
        assertTrue(boxSource.contains("public void accept(T v)"), boxSource);
        assertThrows(AssertionFailedError.class, () -> JavaCompileAssertions.assertCompiles(boxSource, callerSource), callerSource);
    }

    /** `Supplier<GenBase[]>` and a generated `Provider<GenChild[]>` of generated types: `GenChild[] get()`. */
    @Test
    void mostSpecificOfArraysOfGeneratedTypes() throws Exception {
        var base = ClassDef.builder("test.GenBase").addModifiers(Modifier.PUBLIC).build();
        var child = ClassDef.builder("test.GenChild").addModifiers(Modifier.PUBLIC).superclass(base.asTypeDef()).build();
        var t = TypeDef.variable("T");
        var provider = InterfaceDef.builder("test.ArrayProvider").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build()).build();
        var def = ClassDef.builder("test.Arrays").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), base.asTypeDef().array()))
            .addSuperinterface(TypeDef.parameterized(provider.asTypeDef(), child.asTypeDef().array()))
            .addMethod(erased("get", TypeDef.OBJECT, p -> child.asTypeDef().array().instantiate(List.of(child.asTypeDef().instantiate())).returning()))
            .build();
        try (var compiled = compile(base, child, provider, def)) {
            assertTrue(compiled.source(3).contains("public GenChild[] get()"), compiled.source(3));
            Object[] result = (Object[]) ((Supplier<?>) compiled.newInstance(def.getName())).get();
            assertEquals("test.GenChild", result[0].getClass().getName());
        }
    }

    /** A generated `Repo<T extends Entity>` with `T find()`, implemented for a generated `User extends Entity` as `Entity find()`. */
    @Test
    void boundedVariableOfAGeneratedBoundNarrowedToAGeneratedSubtype() throws Exception {
        var entity = ClassDef.builder("test.Entity").addModifiers(Modifier.PUBLIC).build();
        var user = ClassDef.builder("test.User").addModifiers(Modifier.PUBLIC).superclass(entity.asTypeDef()).build();
        var t = TypeDef.variable("T", entity.asTypeDef());
        var repo = InterfaceDef.builder("test.Repo").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("find").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build()).build();
        var def = ClassDef.builder("test.UserRepo").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(repo.asTypeDef(), user.asTypeDef()))
            .addMethod(erased("find", entity.asTypeDef(), p -> user.asTypeDef().instantiate().returning()))
            .build();
        try (var compiled = compile(entity, user, repo, def)) {
            assertTrue(compiled.source(3).contains("public User find()"), compiled.source(3));
            assertEquals("test.User", compiled.load(repo.getName()).getMethod("find").invoke(compiled.newInstance(def.getName())).getClass().getName());
        }
    }

    /** `Box<T extends CharSequence> implements Supplier<T>` and a generated `Provider<CharSequence>`: `T get()` satisfies both. */
    @Test
    void classVariableNextToItsBoundAcrossTwoInterfaces() throws Exception {
        var v = TypeDef.variable("V", TypeDef.of(CharSequence.class));
        var t = TypeDef.variable("T");
        var provider = InterfaceDef.builder("test.CsProvider").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build()).build();
        var def = ClassDef.builder("test.CsBox").addModifiers(Modifier.PUBLIC).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), v))
            .addSuperinterface(TypeDef.parameterized(provider.asTypeDef(), TypeDef.of(CharSequence.class)))
            .addMethod(erased("get", TypeDef.OBJECT, p -> ExpressionDef.constant("cs").returning()))
            .build();
        try (var compiled = compile(provider, def)) {
            assertTrue(compiled.source(1).contains("public V get()"), compiled.source(1));
            assertEquals("cs", ((Supplier<?>) compiled.newInstance(def.getName())).get());
        }
    }

    /** The model declares the parameter bridge itself: `accept(String)` next to a synthetic `accept(Object)` calling it. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void modelDeclaringTheParameterBridgeItself() throws Exception {
        var proper = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("v", String.class).returns(void.class)
            .build((self, p) -> p.get(0).invoke("length", TypeDef.Primitive.INT));
        var def = ClassDef.builder("test.DeclaredParameterBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Consumer.class, String.class))
            .addMethod(proper)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().synthetic(true).addParameter("v", Object.class).returns(void.class)
                .build((self, p) -> self.invoke(proper, p.get(0).cast(String.class))))
            .build();
        try (var compiled = compile(def)) {
            ((Consumer) compiled.newInstance(def.getName())).accept("x");
        }
    }

    /** A record's erased `Object get()` next to the `Record` methods it declares itself, `String toString()`. */
    @Test
    void recordOverridingItsRecordMethodsToo() throws Exception {
        var def = RecordDef.builder("test.Named").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String.class).build())
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field("name", TypeDef.STRING).returning()))
            .addMethod(erased("toString", TypeDef.STRING, p -> ExpressionDef.constant("named").returning()))
            .addMethod(erased("hashCode", TypeDef.Primitive.INT, p -> ExpressionDef.constant(9).returning()))
            .build();
        try (var compiled = compile(def)) {
            Object instance = compiled.load(def.getName()).getConstructor(String.class).newInstance("n");
            assertEquals("n", ((Supplier<?>) instance).get());
            assertEquals("named", instance.toString());
            assertEquals(9, instance.hashCode());
            assertNotNull(instance);
        }
    }
}
