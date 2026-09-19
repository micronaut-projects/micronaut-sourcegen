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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * override resolution. Every model here is written, loaded and run by the ASM bytecode writer with
 * the asserted behaviour; the Java source either does not compile or behaves differently.
 */
public class OverrideResolutionRegressionTest {

    /** A generic method that keeps a variable of its own next to the variable of the type. */
    public interface Conv<T> {
        <U> U convert(T in, Class<U> type);
    }

    /** A compiled parent that overrides with the erasure of the parameter, which Java accepts. */
    public static class ErasedParent<T> implements Function<T, T> {
        @Override
        public T apply(Object value) {
            return null;
        }
    }

    /** A parent with a package-private generic method. */
    public abstract static class PkgBase<T> {
        abstract T make();
    }

    /** A supertype whose result is any enum. */
    public interface EnumSup {
        Enum<?> get();
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var writer = new StringWriter();
            new JavaPoetSourceGenerator().write(definition, writer);
            sources.add(writer.toString());
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    private static Object newInstance(URLClassLoader loader, String name) throws Exception {
        var constructor = loader.loadClass(name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static MethodDef erasedApply(String result) {
        return MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(Object.class)
            .build((self, p) -> ExpressionDef.constant(result).returning());
    }

    /**
     * `value instanceof Integer` on the parameter of an erased `apply(Object)` of a `Function<String, String>`: the
     * parameter is narrowed to `String`, and javac rejects `value instanceof Integer` - "String cannot be converted
     * to Integer". Expected: the test is written against the type the model tests, `(Object) value instanceof Integer`.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void instanceOfOnANarrowedParameter() throws Exception {
        var def = ClassDef.builder("test.InstanceOfNarrowed").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).instanceOf(ClassTypeDef.of(Integer.class))
                    .doIfElse(ExpressionDef.constant("int").returning(), ExpressionDef.constant("other").returning())))
            .build();
        try (var loader = compile(def)) {
            assertEquals("other", ((Function) newInstance(loader, def.getName())).apply("a"));
        }
    }

    /**
     * A cast of the narrowed parameter to a class its narrowed type does not relate to - `(Integer) value`, in a
     * branch that does not run for a String: javac rejects `(Integer) value` for a `String value`. Expected:
     * `(Integer) (Object) value`.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void castOfANarrowedParameterToAnUnrelatedType() throws Exception {
        var def = ClassDef.builder("test.CastNarrowed").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).instanceOf(ClassTypeDef.of(CharSequence.class))
                    .doIfElse(ExpressionDef.constant("cs").returning(),
                        p.get(0).cast(Integer.class).invoke("toString", TypeDef.STRING).returning())))
            .build();
        try (var loader = compile(def)) {
            assertEquals("cs", ((Function) newInstance(loader, def.getName())).apply("a"));
        }
    }

    /**
     * `value == this.marker` with an `Integer marker`: comparable as Objects in the model, "incomparable types:
     * String and Integer" once the parameter is narrowed. Expected: one operand is cast to Object.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void referenceComparisonOfANarrowedParameter() throws Exception {
        var field = FieldDef.builder("marker", Integer.class).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.CompareNarrowed").addModifiers(Modifier.PUBLIC).addField(field)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).equalsReferentially(self.field(field))
                    .doIfElse(ExpressionDef.constant("same").returning(), ExpressionDef.constant("other").returning())))
            .build();
        try (var loader = compile(def)) {
            assertEquals("other", ((Function) newInstance(loader, def.getName())).apply("a"));
        }
    }

    /**
     * The same for the narrowed result of a generated method: `this.get() instanceof Integer` for a `get()` narrowed
     * to `String get()` is rejected by javac.
     */
    @Test
    void instanceOfOnANarrowedResult() throws Exception {
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.constant("ab").returning());
        var def = ClassDef.builder("test.InstanceOfResult").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(get)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .build((self, p) -> self.invoke(get).instanceOf(ClassTypeDef.of(Integer.class)).returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = newInstance(loader, def.getName());
            assertEquals(false, instance.getClass().getMethod("run").invoke(instance));
        }
    }

    /**
     * A lambda captures the narrowed parameter and passes it to the `pick(Object)` overload the model names: the
     * source type of a parameter is looked up in the lambda's own parameters only, so no `(Object)` cast is written
     * and javac selects `pick(String)`. Outside a lambda the cast is written. Wrong behaviour: "string" for "object".
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void lambdaCapturingANarrowedParameterKeepsTheOverload() throws Exception {
        var pickObject = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("v", Object.class)
            .returns(String.class).build((self, p) -> ExpressionDef.constant("object").returning());
        var pickString = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("v", String.class)
            .returns(String.class).build((self, p) -> ExpressionDef.constant("string").returning());
        var supplier = ClassTypeDef.of(Supplier.class);
        var def = ClassDef.builder("test.LambdaCapture").addModifiers(Modifier.PUBLIC)
            .addMethod(pickObject).addMethod(pickString)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING, supplier))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> supplier.getLambda().implement((aThis, params) ->
                    self.invoke(pickObject, p.get(0)).returning()).returning()))
            .build();
        try (var loader = compile(def)) {
            Supplier result = (Supplier) ((Function) newInstance(loader, def.getName())).apply("q");
            assertEquals("object", result.get());
        }
    }

    /**
     * Not specific to overrides, found as the control of the case above: the model invokes `take(Object)` with a
     * `StringBuilder` value next to a `take(StringBuilder)` overload; `this.take(value)` is written without a cast
     * to the declared parameter type, so javac selects the other overload. Wrong behaviour: "builder" for "generic".
     */
    @Test
    void invocationKeepsTheOverloadTheModelNamesForAMoreSpecificArgument() throws Exception {
        var generic = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("generic").returning());
        var def = ClassDef.builder("test.OverloadControl").addModifiers(Modifier.PUBLIC)
            .addMethod(generic)
            .addMethod(MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
                .addParameter("value", StringBuilder.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("builder").returning()))
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC)
                .addParameter("value", StringBuilder.class).returns(String.class)
                .build((self, p) -> self.invoke(generic, p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = newInstance(loader, def.getName());
            assertEquals("generic",
                instance.getClass().getMethod("run", StringBuilder.class).invoke(instance, new StringBuilder()));
        }
    }

    /**
     * An override that keeps the variable of the inherited generic method and erases the variable of the type -
     * `<U> U convert(Object in, Class<U> type)` for `Conv<String>`: the resolver returns nothing for a method that
     * declares type variables, and javac reports a name clash. Expected: `<U> U convert(String in, Class<U> type)`.
     * The bytecode writer needs no bridge for it.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void genericOverrideKeepingItsOwnVariable() throws Exception {
        var u = TypeDef.variable("U");
        var def = ClassDef.builder("test.GenericOverride").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Conv.class, String.class))
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(u)
                .addParameter("in", Object.class)
                .addParameter("type", TypeDef.parameterized(ClassTypeDef.of(Class.class), u)).returns(u)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        try (var loader = compile(def)) {
            assertNull(((Conv) newInstance(loader, def.getName())).convert("a", String.class));
        }
    }

    /**
     * `Object apply(String)` for a `Function<String, String>`: the bridge resolver matches the substituted
     * parameters and writes the `apply(Object)` bridge, so the class runs. The override resolver only matches the
     * erased declaration - `apply(Object)` - so the return type stays `Object`, which javac rejects. Expected:
     * `String apply(String)`.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void erasedReturnNextToSubstitutedParameters() throws Exception {
        var def = ClassDef.builder("test.ErasedReturn").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", String.class).returns(Object.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(def)) {
            assertEquals("a", ((Function) newInstance(loader, def.getName())).apply("a"));
        }
    }

    /**
     * `CharSequence get()` for a `Supplier<String>`: narrower than the erasure, wider than the type argument. The
     * bytecode writer bridges `get()Object`; the source keeps `CharSequence get()`, which javac rejects. Expected:
     * `String get()`.
     */
    @Test
    void returnBetweenTheErasureAndTheTypeArgument() throws Exception {
        var def = ClassDef.builder("test.PartlyNarrowed").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(CharSequence.class)
                .build((self, p) -> ExpressionDef.constant("x").returning()))
            .build();
        try (var loader = compile(def)) {
            assertEquals("x", ((Supplier<?>) newInstance(loader, def.getName())).get());
        }
    }

    /**
     * A model that declares the bridge itself - `String get()` and a synthetic `Object get()` calling it - is two
     * methods as bytecode, where the bridge resolver skips the taken descriptor. As source the erased one is
     * rewritten to `String get()` as well: "method get() is already defined". Expected: the erased (synthetic)
     * duplicate is not written, javac adds that bridge. The same holds for `accept(Object)` next to `accept(String)`.
     */
    @Test
    void modelDeclaringTheBridgeItself() throws Exception {
        var proper = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(String.class)
            .build((self, p) -> ExpressionDef.constant("x").returning());
        var def = ClassDef.builder("test.DeclaredBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(proper)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().synthetic(true)
                .returns(Object.class).build((self, p) -> self.invoke(proper).returning()))
            .build();
        try (var loader = compile(def)) {
            assertEquals("x", ((Supplier<?>) newInstance(loader, def.getName())).get());
        }
    }

    /**
     * A static nested type calls the narrowed `accept(String)` of its outer type with an Object. The outer type can
     * only be named - its definition is built after the nested one - and the receiver's definition is looked for in
     * the owner type or in the definition being written, which here is the nested one: no conversion is written and
     * javac rejects `target.accept(value)`. Expected: `target.accept((String) value)`, the enclosing definition
     * being known while its member types are written.
     */
    @Test
    void nestedTypeCallsANarrowedMethodOfItsOuterType() throws Exception {
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> p.get(0).invoke("toString", TypeDef.STRING));
        var nested = ClassDef.builder("Nested").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("target", ClassTypeDef.of("test.NarrowedOuter")).addParameter("value", Object.class)
                .returns(void.class)
                .build((aThis, p) -> p.get(0).invoke(accept, p.get(1))))
            .build();
        var outer = ClassDef.builder("test.NarrowedOuter").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Consumer.class, String.class))
            .addMethod(accept).addInnerType(nested)
            .build();
        try (var loader = compile(outer)) {
            var instance = newInstance(loader, outer.getName());
            loader.loadClass("test.NarrowedOuter$Nested").getMethod("run", instance.getClass(), Object.class)
                .invoke(null, instance, "v");
        }
    }

    /**
     * A member type overrides a package-private generic method of a class of its own package. The package of a
     * member definition is read as `pkg.Outer` - `ClassTypeDef.getPackageName()` cuts the canonical name at the last
     * dot - so the inherited method is taken for one of another package and the erased `Object make()` is kept,
     * which javac rejects; the same override in the top level type is resolved. Expected: `String make()` in both.
     */
    @Test
    void memberTypeOverridesAPackagePrivateMethodOfItsPackage() throws Exception {
        String packageName = OverrideResolutionRegressionTest.class.getPackageName();
        var make = MethodDef.builder("make").overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.constant("made").returning());
        var member = ClassDef.builder("Member").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .superclass(TypeDef.parameterized(PkgBase.class, String.class))
            .addMethod(make)
            .build();
        var top = ClassDef.builder(packageName + ".MemberTop").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(PkgBase.class, String.class))
            .addMethod(make)
            .addInnerType(member)
            .build();
        try (var loader = compile(top)) {
            loader.loadClass(packageName + ".MemberTop$Member");
        }
    }

    /**
     * `Object get()` implementing `Supplier<Kind>` of a generated enum and `Enum<?> get()`: a generated enum is not
     * known to extend `Enum` - the model lists its interfaces only, and `Enum` is a terminal type of the walk - so
     * neither resolved return type is a subtype of the other, nothing is resolved, and `Object get()` is kept, which
     * javac rejects. Expected: `Kind get()`. A generated record and `Record` are the same.
     */
    @Test
    void generatedEnumIsAnEnum() throws Exception {
        var kind = EnumDef.builder("test.Kind").addModifiers(Modifier.PUBLIC).addEnumConstant("A").build();
        var kindType = kind.asTypeDef();
        var def = ClassDef.builder("test.KindSupplier").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), kindType))
            .addSuperinterface(ClassTypeDef.of(EnumSup.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> kindType.getStaticField("A", kindType).returning()))
            .build();
        try (var loader = compile(kind, def)) {
            assertEquals("A", ((Supplier<?>) newInstance(loader, def.getName())).get().toString());
        }
    }

}
