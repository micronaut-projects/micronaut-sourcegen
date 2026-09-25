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

import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import java.util.List;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Override resolution and the shared type resolution it relies on (TypeHierarchy, OverrideResolver): erased and
 * narrowed overrides, generic methods whose variables shadow or are bounded by the class's, members of parameterized
 * enclosing types, and calls of inherited generic methods. Every model here is valid for the bytecode writer - each
 * erased override has the descriptor of the inherited method - and is rendered as Java source, compiled by javac,
 * loaded and run with the asserted behaviour.
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
            assertEquals("other", ((Function) JavaCompileAssertions.newInstance(loader, def.getName())).apply("a"));
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
            assertEquals("cs", ((Function) JavaCompileAssertions.newInstance(loader, def.getName())).apply("a"));
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
            assertEquals("other", ((Function) JavaCompileAssertions.newInstance(loader, def.getName())).apply("a"));
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
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
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
            Supplier result = (Supplier) ((Function) JavaCompileAssertions.newInstance(loader, def.getName())).apply("q");
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
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
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
            assertNull(((Conv) JavaCompileAssertions.newInstance(loader, def.getName())).convert("a", String.class));
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
            assertEquals("a", ((Function) JavaCompileAssertions.newInstance(loader, def.getName())).apply("a"));
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
            assertEquals("x", ((Supplier<?>) JavaCompileAssertions.newInstance(loader, def.getName())).get());
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
            assertEquals("x", ((Supplier<?>) JavaCompileAssertions.newInstance(loader, def.getName())).get());
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
            var instance = JavaCompileAssertions.newInstance(loader, outer.getName());
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
            assertEquals("A", ((Supplier<?>) JavaCompileAssertions.newInstance(loader, def.getName())).get().toString());
        }
    }

    /**
     * A (non-static) inner class of a generic generated class implements {@code Supplier<T>} with the {@code T} of
     * its enclosing class, and overrides {@code get()} with the erased {@code Object get()}, which is the descriptor
     * of {@code Supplier.get()}. The generator (JavaPoetNames.isVariablePartOfTheDefinition) and the resolver
     * (the declared variables of OverrideResolver) take only the variables the inner class declares itself as in
     * scope: the source is {@code class Inner implements Supplier<Object>} with {@code Object get()} - it compiles,
     * but the inner class is no longer a {@code Supplier<T>}. Expected: {@code Supplier<T>} and {@code T get()}.
     */
    @Test
    void innerClassOverridesWithTheEnclosingClassVariableAsReturn() throws Exception {
        var t = TypeDef.variable("T");
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        var outer = ClassDef.builder("test.InnerSupplierOuter").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addInnerType(inner)
            .build();
        try (var loader = compile(outer)) {
            var innerClass = loader.loadClass("test.InnerSupplierOuter$Inner");
            assertEquals("T", innerClass.getMethod("get").getGenericReturnType().getTypeName());
        }
    }

    /**
     * A generated interface with a generic method whose own variable {@code T} is unbounded, and a class whose own
     * {@code T extends Number} has the same name. The class overrides {@code <T> T id(X, T)} of {@code Api<String>}
     * with {@code <T> T id(Object, T)} - the descriptor {@code (Object, Object)Object} of the interface method. The
     * resolver erases the method's name-only {@code T} with the bound of the class's {@code T}, finds
     * {@code (Object, Number)}, and matches nothing: {@code id(Object, T)} is written, which javac rejects as a name
     * clash. Expected: {@code <T> T id(String, T)}.
     */
    @Test
    void unboundedMethodVariableShadowingABoundedClassVariable() throws Exception {
        var x = TypeDef.variable("X");
        var methodT = TypeDef.variable("T");
        var api = InterfaceDef.builder("test.ShadowApi").addModifiers(Modifier.PUBLIC).addTypeVariable(x)
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(methodT)
                .addParameter("key", x).addParameter("value", methodT).returns(methodT).build())
            .build();
        var impl = ClassDef.builder("test.ShadowImpl").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(api.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(methodT)
                .addParameter("key", Object.class).addParameter("value", methodT).returns(methodT)
                .build((self, p) -> p.get(1).returning()))
            .build();
        try (var loader = compile(api, impl)) {
            var instance = JavaCompileAssertions.newInstance(loader, impl.getName());
            assertEquals(7, instance.getClass().getMethod("id", String.class, Object.class).invoke(instance, "k", 7));
        }
    }

    /**
     * {@code B<U> implements A<List<U>>} overrides {@code <U> void m(T, U)} of {@code A} with the erased
     * {@code <U> void m(Object, U)}. The resolver substitutes {@code T} with {@code List<U>} - the {@code U} of the
     * class - and writes it into a method that declares its own {@code U}: {@code <U> void m(List<U> t, U u)}, where
     * {@code List<U>} now names the method's variable. javac: "name clash ... have the same erasure, yet neither
     * overrides the other". Expected: the method variable renamed, {@code <V> void m(List<U> t, V u)}.
     */
    @Test
    void substitutedParameterIsCapturedByAMethodVariableOfTheSameName() throws Exception {
        var t = TypeDef.variable("T");
        var u = TypeDef.variable("U");
        var a = InterfaceDef.builder("test.CaptureApi").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(u)
                .addParameter("t", t).addParameter("u", u).returns(void.class).build())
            .build();
        var b = ClassDef.builder("test.CaptureImpl").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(a.asTypeDef(), TypeDef.parameterized(ClassTypeDef.of(List.class), u)))
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(u)
                .addParameter("t", Object.class).addParameter("u", u).returns(void.class)
                .build((self, p) -> new StatementDef.Return(null)))
            .build();
        try (var loader = compile(a, b)) {
            loader.loadClass(b.getName()).getMethod("m", List.class, Object.class);
        }
    }

    /**
     * {@code <U extends T> U narrow(T)} of a generated {@code Conv<T>}, overridden in a {@code Conv<Number>} with
     * {@code <U> U narrow(Object)} - the descriptor {@code (Object)Object} of the interface method. The parameter is
     * resolved to {@code Number}, but the method keeps the type variables the model declares: {@code <U> U
     * narrow(Number)} does not have the type parameters of {@code <U extends Number> U narrow(Number)}, and javac
     * reports a name clash. Expected: {@code <U extends Number> U narrow(Number)}.
     */
    @Test
    void methodVariableBoundedByAClassVariableKeepsTheSubstitutedBound() throws Exception {
        var t = TypeDef.variable("T");
        var conv = InterfaceDef.builder("test.NarrowConv").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("narrow").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(TypeDef.variable("U", t)).addParameter("value", t).returns(TypeDef.variable("U")).build())
            .build();
        var u = TypeDef.variable("U");
        var impl = ClassDef.builder("test.NarrowNumbers").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(conv.asTypeDef(), TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("narrow").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(u)
                .addParameter("value", Object.class).returns(u)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        try (var loader = compile(conv, impl)) {
            var instance = JavaCompileAssertions.newInstance(loader, impl.getName());
            assertNull(instance.getClass().getMethod("narrow", Number.class).invoke(instance, 1));
        }
    }

    /**
     * A generated {@code Sink<T extends Object & Comparable<T>>}: the bytecode writer erases {@code T} to its leftmost
     * bound, {@code Object} (JLS 4.6), so a {@code Sink<String>} overrides {@code accept(T)} with
     * {@code accept(Object)}. TypeHierarchy erases {@code T} to the first bound that is not Object -
     * {@code Comparable} - so the declaration is not matched and {@code accept(Object)} is written, which javac
     * rejects ("name clash"). Expected: {@code accept(String)}.
     */
    @Test
    void intersectionBoundLedByObjectErasesToObject() throws Exception {
        var t = TypeDef.variable("T", TypeDef.OBJECT,
            TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var sink = InterfaceDef.builder("test.OrderedSink").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(void.class).build())
            .build();
        var impl = ClassDef.builder("test.OrderedStringSink").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(sink.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(void.class)
                .build((self, p) -> new StatementDef.Return(null)))
            .build();
        try (var loader = compile(sink, impl)) {
            loader.loadClass(impl.getName()).getMethod("accept", String.class);
        }
    }

    /**
     * {@code Object make()} overriding {@code <X> Outer<X>.Member make()}: the resolver narrows the return to the
     * substituted type, renaming the method's own {@code X} to {@code "X (method)"} so that it is not taken for a
     * variable of the class. Whether the result still names a variable is asked of
     * {@code TypeHierarchy.containsVariableOtherThan}, which does not look into the enclosing type of a
     * {@code MemberOf}, so {@code Outer<X (method)>.Member make()} is written verbatim - not Java. Expected: the
     * erasure, {@code Outer.Member make()}.
     */
    @Test
    void genericMethodReturningAMemberOfAParameterizedEnclosingType() throws Exception {
        var make = MethodDef.builder("make").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var def = ClassDef.builder("test.MemberMakerImpl").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(MemberMaker.class))
            .addMethod(make)
            .build();
        // The Java generator happens to write the leaked variable as its bound, so the source compiles; the resolved
        // signature itself names a variable that exists nowhere, which any other consumer writes as is
        try (var loader = compile(def)) {
            assertNull(((MemberMaker) JavaCompileAssertions.newInstance(loader, def.getName())).make());
        }
        var resolved = OverrideResolver.resolve(def, make, GenerationScope.none());
        assertNotNull(resolved);
        assertFalse(resolved.returnType().toString().contains("(method)"), resolved.returnType().toString());
    }

    /**
     * A call of the generic {@code <U> U convert(T, Class<U>)} that {@code ConvChild} inherits from
     * {@code ConvParent<String>}, with the erased method of the model - {@code convert(Object, Class)}, no type
     * variables, which is the descriptor. The receiver arguments are looked up through the superclass only where the
     * compiled method has as many type variables as the model's method, so {@code T} is not bound to {@code String}
     * and the value is passed uncast: javac reports "Object cannot be converted to String". The non-generic
     * {@code echo(T)} of the same class is cast. Expected: {@code child.convert((String) value, type)}.
     */
    @Test
    void erasedCallOfAGenericMethodInheritedThroughAParameterizedSuperclass() throws Exception {
        var convert = MethodDef.builder("convert").addModifiers(Modifier.PUBLIC)
            .addParameter("in", Object.class).addParameter("type", Class.class).returns(Object.class).build();
        var def = ClassDef.builder("test.ConvCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("child", ConvChild.class).addParameter("value", Object.class)
                .addParameter("type", Class.class).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(convert, p.get(1), p.get(2)).returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
            assertEquals("v", instance.getClass().getMethod("call", ConvChild.class, Object.class, Class.class)
                .invoke(instance, new ConvChild(), "v", String.class));
        }
    }

    /**
     * A call of {@code accept(T)} of {@code RawParent<T extends CharSequence>} on a raw {@code RawChild} receiver -
     * {@code RawChild<N>} extends {@code RawParent<String>}. The supertypes of a raw type are raw (JLS 4.8), so the
     * parameter is a {@code CharSequence}, the descriptor of the model's {@code accept(CharSequence)}, and a
     * {@code StringBuilder} is accepted. The receiver arguments are carried through the superclass without regard to
     * the raw receiver, {@code T} is bound to {@code String}, and the value is cast to it: the call fails with a
     * ClassCastException that the bytecode of the model does not throw. Expected: {@code child.accept(value)}.
     */
    @Test
    void callOnARawReceiverDoesNotBindTheArgumentsItsGenericSupertypeIsInheritedWith() throws Exception {
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence.class).returns(String.class).build();
        var def = ClassDef.builder("test.RawReceiverCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("child", RawChild.class).addParameter("value", CharSequence.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(accept, p.get(1)).returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
            assertEquals("sb", instance.getClass().getMethod("call", RawChild.class, CharSequence.class)
                .invoke(instance, new RawChild<Integer>(), new StringBuilder("sb")));
        }
    }

    /**
     * A call of {@code put(A, B, C)} on an {@code Outer3<String>.Mid<Integer>.Inner<Long>} receiver, with the erased
     * method of the model - {@code put(Object, Object, Object)}. The receiver's arguments are bound for the
     * variables of {@code Inner} only - {@code C} - and not for those of its enclosing types, so {@code a} and
     * {@code b} are passed uncast: javac reports "Object cannot be converted to String". Expected:
     * {@code in.put((String) a, (Integer) b, (Long) c)}.
     */
    @Test
    void callOnAMemberOfParameterizedEnclosingTypesBindsTheirArguments() throws Exception {
        var receiver = io.micronaut.sourcegen.model.TypeHierarchy.typeDefOf(
            Outer3.class.getMethod("sample").getGenericReturnType());
        var put = MethodDef.builder("put").addModifiers(Modifier.PUBLIC)
            .addParameter("a", Object.class).addParameter("b", Object.class).addParameter("c", Object.class)
            .returns(String.class).build();
        var def = ClassDef.builder("test.DeepMemberCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("in", receiver).addParameter("a", Object.class).addParameter("b", Object.class)
                .addParameter("c", Object.class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(put, p.get(1), p.get(2), p.get(3)).returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
            var inner = Outer3.sample();
            assertEquals("s12", instance.getClass().getMethod("call", inner.getClass(), Object.class, Object.class, Object.class)
                .invoke(instance, inner, "s", 1, 2L));
        }
    }

    /**
     * Five levels mixing a compiled class and generated ones, each renaming the variable and wrapping it:
     * {@code L1<X> extends ChainBase<List<X>>}, {@code L2<Y> extends L1<Map<String, Y>>}, {@code L3<Z> extends
     * L2<Z[]>}, {@code L4 extends L3<Integer>}, and {@code L5 extends L4} overriding {@code Object get()}.
     * Expected: {@code List<Map<String, Integer[]>> get()}.
     */
    @Test
    void deepChainOfRenamedVariablesMixingCompiledAndGeneratedClasses() throws Exception {
        var x = TypeDef.variable("X");
        var y = TypeDef.variable("Y");
        var z = TypeDef.variable("Z");
        var l1 = ClassDef.builder("test.ChainL1").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(x)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(ChainBase.class), TypeDef.parameterized(ClassTypeDef.of(List.class), x)))
            .build();
        var l2 = ClassDef.builder("test.ChainL2").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(y)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(l1),
                TypeDef.parameterized(ClassTypeDef.of(java.util.Map.class), TypeDef.STRING, y)))
            .build();
        var l3 = ClassDef.builder("test.ChainL3").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(z)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(l2), z.array()))
            .build();
        var l4 = ClassDef.builder("test.ChainL4").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(l3), TypeDef.of(Integer.class)))
            .build();
        var l5 = ClassDef.builder("test.ChainL5").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(l4))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        try (var loader = compile(l1, l2, l3, l4, l5)) {
            assertEquals("java.util.List<java.util.Map<java.lang.String, java.lang.Integer[]>>",
                loader.loadClass(l5.getName()).getMethod("get").getGenericReturnType().getTypeName());
        }
    }

    /**
     * A static member class extends its generic enclosing class, which it can only name - the enclosing definition is
     * built after it: {@code Sub extends SelfNamedOuter<String>}, overriding {@code T get()} with {@code Object get()}.
     * The walk skips a supertype known only by name unless a lookup finds its element, and the resolver's lookup is the
     * visitor context only - not the definitions of the file being written, which {@code OverrideResolver.writing}
     * records and {@code definitionOf} uses for calls. {@code Object get()} is kept: javac reports "return type
     * Object is not compatible with String". Expected: {@code String get()}.
     */
    @Test
    void memberTypeExtendingItsEnclosingTypeByName() throws Exception {
        var t = TypeDef.variable("T");
        var sub = ClassDef.builder("Sub").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of("test.SelfNamedOuter"), TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("sub").returning()))
            .build();
        var outer = ClassDef.builder("test.SelfNamedOuter").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(t)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .addInnerType(sub)
            .build();
        try (var loader = compile(outer)) {
            assertEquals("sub", ((Supplier<?>) JavaCompileAssertions.newInstance(loader, "test.SelfNamedOuter$Sub")).get());
        }
    }

    /**
     * {@code Object get()} implementing both {@code Supplier<String> get()} of {@code SupplierSource} and
     * {@code T get()} of an {@code AnySource<Box<String>.Getter>}: the most specific return is
     * {@code Box<String>.Getter}, which is a {@code Supplier<String>}. OverrideResolver's own reflective
     * {@code asSupertype} binds the variables of {@code Getter} alone, not those of its enclosing {@code Box<String>},
     * so it reads {@code Getter} as a {@code Supplier<T>}, finds neither return a subtype of the other, and keeps
     * {@code Object get()} - javac: "return type Object is not compatible with Supplier<String>". Expected:
     * {@code Box<String>.Getter get()}.
     */
    @Test
    void mostSpecificReturnIsAMemberOfAParameterizedEnclosingType() throws Exception {
        var getter = TypeHierarchy.memberType(TypeDef.parameterized(Box.class, String.class), ClassTypeDef.of(Box.Getter.class));
        var def = ClassDef.builder("test.GetterSource").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(SupplierSource.class))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(AnySource.class), getter))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        try (var loader = compile(def)) {
            assertNull(((SupplierSource) JavaCompileAssertions.newInstance(loader, def.getName())).get());
        }
    }

    /**
     * A record with an {@code Object value} component implementing {@code AnySource<String>}-like {@code T value()}:
     * the implicit accessor {@code Object value()} has the descriptor of the interface method, so the bytecode is
     * valid. The resolver only resolves declared methods, not the accessors of record components, and javac rejects
     * the record: "value() in ValueRecord cannot implement value() in ValueSource; return type Object is not
     * compatible with String". Expected: an accessor returning {@code String}.
     */
    @Test
    void recordComponentAccessorImplementingAGenericInterfaceMethod() throws Exception {
        var t = TypeDef.variable("T");
        var source = InterfaceDef.builder("test.ValueSource").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var record = io.micronaut.sourcegen.model.RecordDef.builder("test.ValueRecord").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(source.asTypeDef(), TypeDef.STRING))
            .addProperty(io.micronaut.sourcegen.model.PropertyDef.builder("value").ofType(Object.class).build())
            .build();
        try (var loader = compile(source, record)) {
            assertEquals(String.class, loader.loadClass(record.getName()).getMethod("value").getReturnType());
        }
    }

    /** {@code String take(Object[])} for a {@code VarSink<String>} of {@code take(T...)}: expected {@code take(String[])}. */
    @Test
    void genericVarargsParameter() throws Exception {
        var def = ClassDef.builder("test.VarSinkImpl").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(VarSink.class), TypeDef.STRING))
            .addMethod(MethodDef.builder("take").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", Object[].class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("taken").returning()))
            .build();
        try (var loader = compile(def)) {
            @SuppressWarnings("unchecked")
            VarSink<String> sink = (VarSink<String>) JavaCompileAssertions.newInstance(loader, def.getName());
            assertEquals("taken", sink.take("a", "b"));
        }
    }

    /** {@code MyBuilder extends SelfBuilder<MyBuilder>} overriding {@code Object self()}: expected {@code MyBuilder self()}. */
    @Test
    void selfTypedBuilder() throws Exception {
        var def = ClassDef.builder("test.MyBuilder").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(SelfBuilder.class), ClassTypeDef.of("test.MyBuilder")))
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.returning()))
            .build();
        try (var loader = compile(def)) {
            var instance = JavaCompileAssertions.newInstance(loader, def.getName());
            assertEquals(instance, ((SelfBuilder<?>) instance).self());
            assertEquals(def.getName(), instance.getClass().getMethod("self").getReturnType().getName());
        }
    }

    /** {@code Object get()} below {@code CovariantMid extends Supplier<CharSequence>} declaring {@code String get()}. */
    @Test
    void covariantReturnInTheMiddleOfAChain() throws Exception {
        var def = ClassDef.builder("test.CovariantImpl").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(CovariantMid.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("c").returning()))
            .build();
        try (var loader = compile(def)) {
            assertEquals("c", ((CovariantMid) JavaCompileAssertions.newInstance(loader, def.getName())).get());
        }
    }

    /**
     * {@code SwapSub<U, T> extends SwapBase<T, U>} and {@code SwapLeaf extends SwapSub<String, Integer>} overriding
     * {@code Object apply(Object, Object)}: each variable is substituted once. Expected:
     * {@code Integer apply(Integer, String)}.
     */
    @Test
    void variablesPassedOnSwapped() throws Exception {
        var t = TypeDef.variable("T");
        var u = TypeDef.variable("U");
        var sub = ClassDef.builder("test.SwapSub").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(u).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(SwapBase.class), t, u))
            .build();
        var leaf = ClassDef.builder("test.SwapLeaf").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(sub), TypeDef.STRING, TypeDef.of(Integer.class)))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("t", Object.class).addParameter("u", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(sub, leaf)) {
            var method = loader.loadClass(leaf.getName()).getMethod("apply", Integer.class, String.class);
            assertEquals(Integer.class, method.getReturnType());
        }
    }

    /**
     * Two member types of one generated class: {@code Base<T>} with a package-private {@code abstract T make()}, and
     * {@code Impl extends Base<String>} overriding it with {@code Object make()}. {@code Impl} names its superclass
     * with the definition the caller holds, whose name is still the simple {@code Base} ({@code addInnerType} only
     * qualifies the copy it stores): TypeHierarchy reads its package as the default package, the package-private
     * method is taken for one of another package, and {@code Object make()} is kept - javac: "return type Object is
     * not compatible with String". Expected: {@code String make()}.
     */
    @Test
    void packagePrivateMethodOfASiblingMemberTypeNamedByItsSimpleName() throws Exception {
        var t = TypeDef.variable("T");
        var base = ClassDef.builder("Base").addModifiers(Modifier.ABSTRACT, Modifier.STATIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.ABSTRACT).returns(t).build())
            .build();
        var impl = ClassDef.builder("Impl").addModifiers(Modifier.STATIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(base), TypeDef.STRING))
            .addMethod(MethodDef.builder("make").overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("made").returning()))
            .build();
        var outer = ClassDef.builder("test.SiblingMembers").addModifiers(Modifier.PUBLIC)
            .addInnerType(base).addInnerType(impl)
            .build();
        try (var loader = compile(outer)) {
            assertEquals(String.class, loader.loadClass("test.SiblingMembers$Impl").getDeclaredMethod("make").getReturnType());
        }
    }

    @Test
    void overrideResolvesBoundsOfNameOnlyMethodVariables() throws Exception {
        var t = TypeDef.variable("T");
        var u = TypeDef.variable("U", TypeDef.of(Number.class));
        var contract = InterfaceDef.builder("test.GenericContract").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(t)
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(u).addParameter("value", TypeDef.variable("U")).returns(t).build()).build();
        var def = ClassDef.builder("test.GenericImplementation").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(contract.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC).overrides()
                .addTypeVariable(u).addParameter("value", TypeDef.variable("U")).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("ok").returning())).build();
        try (var loader = compile(contract, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("ok", cls.getMethod("convert", Number.class).invoke(cls.getConstructor().newInstance(), 1));
        }
    }

    @Test
    void interfaceBridgeIsNotWrittenTwice() throws Exception {
        var erased = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides()
            .addParameter("value", Object.class).returns(Object.class).build();
        var specialized = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).overrides()
            .addParameter("value", String.class).returns(String.class).build();
        var def = InterfaceDef.builder("test.BridgedFunction").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(erased).addMethod(specialized).build();
        try (var ignored = compile(def)) {
            // Both model signatures must collapse to one source declaration.
        }
    }

    /** A generic class with a member class that is no generic class itself. */
    public static class Outer<T> {
        /** An inner class of a generic class. */
        public class Member {
        }
    }

    /** A generic method returning a member of a parameterized enclosing type. */
    public interface MemberMaker {
        <X> Outer<X>.Member make();
    }

    /** A generic class with a generic method that uses the class variable, and a non-generic one. */
    public static class ConvParent<T> {
        public <U> U convert(T in, Class<U> type) {
            return type.cast(in);
        }

        public T echo(T in) {
            return in;
        }
    }

    /** Binds the variable of its generic superclass. */
    public static class ConvChild extends ConvParent<String> {
    }

    /** Member classes three levels deep, each generic, the innermost using the variables of all. */
    public static class Outer3<A> {
        /** The middle member. */
        public class Mid<B> {
            /** The innermost member. */
            public class Inner<C> {
                public String put(A a, B b, C c) {
                    return String.valueOf(a) + b + c;
                }
            }
        }

        public static Outer3<String>.Mid<Integer>.Inner<Long> sample() {
            return new Outer3<String>().new Mid<Integer>().new Inner<Long>();
        }
    }

    /** A compiled generic class at the bottom of a chain of generated ones. */
    public abstract static class ChainBase<T> implements Supplier<T> {
    }

    /** A self-typed builder. */
    public abstract static class SelfBuilder<B extends SelfBuilder<B>> {
        public abstract B self();
    }

    /** A covariant return in the middle of a chain. */
    public interface CovariantMid extends Supplier<CharSequence> {
        @Override
        String get();
    }

    /** Two variables, passed on swapped by the generated subclass. */
    public abstract static class SwapBase<T, U> implements java.util.function.BiFunction<T, U, T> {
    }

    /** A generic class whose inner class supplies the enclosing type's argument. */
    public static class Box<T> {
        /** A {@code Supplier<T>} of the enclosing {@code T}. */
        public class Getter implements Supplier<T> {
            @Override
            public T get() {
                return null;
            }
        }
    }

    /** A supplier of a supplier. */
    public interface SupplierSource {
        Supplier<String> get();
    }

    /** A generic source. */
    public interface AnySource<T> {
        T get();
    }

    /** A generic class whose variable is bounded. */
    public static class RawParent<T extends CharSequence> {
        public String accept(T value) {
            return value.toString();
        }
    }

    /** A generic class binding the variable of its superclass: used raw, its superclass is raw as well (JLS 4.8). */
    public static class RawChild<N extends Number> extends RawParent<String> {
    }

    /** A generic varargs parameter. */
    public interface VarSink<T> {
        String take(T... values);
    }
}
