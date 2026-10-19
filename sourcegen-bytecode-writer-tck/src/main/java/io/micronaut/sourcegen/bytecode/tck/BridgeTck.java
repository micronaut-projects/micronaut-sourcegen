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
package io.micronaut.sourcegen.bytecode.tck;

import io.micronaut.sourcegen.bytecode.tck.ApplicabilityFixtures.NumericIdentity;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.EnumSupplier;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Bridge methods: which bridges a generated class needs, their flags and descriptors, and the dispatch through
 * them, compared with the bridges javac writes.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "MissingOverride", "UnusedTypeParameter", "TypeParameterShadowing", "unchecked", "varargs",
    "rawtypes", "unused", "EqualsIncompatibleType", "UnusedMethod", "UnusedVariable"
})
public abstract class BridgeTck extends AbstractByteCodeWriterTck {

    @Test
    public void abstractSpecializationProvidesExecutableBridge() throws Exception {
        var parent = ClassDef.builder("test.AbstractSpecialization").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.STRING).build()).build();
        // A separately generated subclass need only implement the narrow abstract method. Its
        // parent must provide the erased forwarding bridge, just as a javac-generated parent does.
        var child = ClassDef.builder("test.SpecializedChild").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent.getName()))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("ok").returning())).build();
        var loader = load(parent, child);
        var instance = loader.loadClass(child.getName()).getConstructor().newInstance();
        assertEquals("ok", ((Supplier<?>) instance).get());
    }

    @Test
    public void abstractInterfaceSpecializationProvidesDefaultBridge() throws Exception {
        var parent = InterfaceDef.builder("test.InterfaceSpecialization").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.STRING).build()).build();
        Method bridge = java.util.Arrays.stream(define(parent).getDeclaredMethods())
            .filter(Method::isBridge).findFirst().orElseThrow();
        assertEquals(true, bridge.isDefault());
    }

    @Test
    public void inheritedImplementationSatisfiesNewGenericInterface() throws Exception {
        var child = ClassDef.builder("test.InheritedSupplier").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(StringSupplier.class))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING)).build();
        assertEquals("inherited", ((Supplier<?>) define(child).getConstructor().newInstance()).get());
    }

    @Test
    public void concreteBridgeThroughMultipleGenericLevels() throws Exception {
        var middle = ClassDef.builder("test.GenericMiddle").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addSuperinterface(TypeDef.parameterized(Function.class, TypeDef.variable("T"), TypeDef.variable("T"))).build();
        var child = ClassDef.builder("test.GenericLeaf").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(middle.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> p.getFirst().returning())).build();
        var type = load(middle, child).loadClass(child.getName());
        @SuppressWarnings("unchecked")
        var function = (Function<Object, Object>) type.getConstructor().newInstance();
        assertEquals("ok", function.apply("ok"));
    }

    @Test
    public void bridgesCovariantGenericMethodWithItsOwnVariable() throws Exception {
        var variable = TypeDef.variable("U", TypeDef.of(Number.class));
        var child = ClassDef.builder("test.GenericMethodBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(GenericContract.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC).addTypeVariable(variable)
                .addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("ok").returning())).build();
        var instance = (GenericContract<?>) define(child).getConstructor().newInstance();
        assertEquals("ok", instance.convert(7));
    }

    @Test
    public void rawGenericSuperclassDoesNotSpecializeItsMethod() throws Exception {
        var child = ClassDef.builder("test.RawGenericChild").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(GenericParent.class))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.STRING).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("overload").returning())).build();
        @SuppressWarnings("unchecked")
        var instance = (GenericParent<Object>) define(child).getConstructor().newInstance();
        assertEquals("original", instance.identity("original"));
    }

    @Test
    public void genericArrayBridgePreservesRuntimeArray() throws Exception {
        var child = ClassDef.builder("test.ArrayBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ArrayContract.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.STRING.array(2)).returns(TypeDef.STRING.array(2))
                .build((self, p) -> p.getFirst().returning())).build();
        @SuppressWarnings("unchecked")
        var instance = (ArrayContract<String>) define(child).getConstructor().newInstance();
        var value = new String[][] {{"ok"}};
        assertSame(value, instance.identity(value));
    }

    @Test
    public void bridgeUsesTheMethodsNamedTypeVariableBound() throws Exception {
        var definition = ClassDef.builder("test.edges.NamedBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(BridgeTck.GenericContract.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("U", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.variable("U")).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("ok").returning())).build();
        var instance = (BridgeTck.GenericContract<?>) define(definition).getConstructor().newInstance();
        assertEquals("ok", instance.convert(1));
    }

    @Test
    public void inheritedGeneratedBridgeKeepsSuperclassVariableScope() throws Exception {
        var parent = ClassDef.builder("test.edges.GenericSupplierParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.variable("T"))
                .build((self, p) -> ExpressionDef.constant(7).returning())).build();
        var child = ClassDef.builder("test.edges.GenericSupplierChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class)))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.of(Integer.class))).build();
        var loader = load(parent, child);
        var instance = (Supplier<?>) loader.loadClass(child.getName()).getConstructor().newInstance();
        assertEquals(7, instance.get());
    }

    @Test
    public void inheritedSpecializedImplementationGetsInterfaceBridge() throws Exception {
        assertEquals(7, new SignatureFixtures.InheritedSpecializedBridge().apply(7));
        var definition = ClassDef.builder("test.additional.SpecializedFunction").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(NumericIdentity.class, TypeDef.of(Integer.class)))
            .addSuperinterface(TypeDef.parameterized(Function.class, TypeDef.of(Integer.class), TypeDef.of(Integer.class)))
            .build();
        var instance = (Function<Integer, Integer>) define(definition).getConstructor().newInstance();
        assertEquals(7, instance.apply(7));
    }

    @Test
    public void abstractInterfaceRedeclarationPreservesBothErasedContracts() throws Exception {
        var method = MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.STRING).build();
        var definition = InterfaceDef.builder("test.inference.Combined").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addSuperinterface(TypeDef.of(SequenceSupplier.class)).addMethod(method).build();
        assertEquals(List.of("java.lang.CharSequence", "java.lang.Object", "java.lang.String"),
            Arrays.stream(define(definition).getDeclaredMethods()).map(m -> m.getReturnType().getName()).sorted().toList());
    }

    @Test
    public void boundedGeneratedClassBridgesItsVariableParameter() throws Exception {
        var variable = TypeDef.variable("T");
        var target = ClassDef.builder("test.inference.VariableBridge").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(Function.class, variable, variable))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).addParameter("value", variable).returns(variable)
                .build((self, p) -> p.getFirst().returning())).build();
        var function = (Function<Object, Object>) define(target).getConstructor().newInstance();
        assertEquals(7, function.apply(7));
    }

    @Test
    public void inheritedMethodBoundsAreSpecializedForBridgeMatching() throws Exception {
        assertEquals(7, ((MethodBoundFunction<Integer>) new MethodBoundChild()).apply(7));
        var definition = ClassDef.builder("test.inference.InheritedMethodBounds").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(MethodBoundParent.class, TypeDef.of(Integer.class)))
            .addSuperinterface(TypeDef.parameterized(MethodBoundFunction.class, TypeDef.of(Integer.class))).build();
        var instance = (MethodBoundFunction<Integer>) define(definition).getConstructor().newInstance();
        assertEquals(7, instance.apply(7));
    }

    @Test
    public void privateSuperclassMethodDoesNotSuppressRequiredBridge() throws Exception {
        var definition = ClassDef.builder("test.resolution.InheritedBridge").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(PrivateBridgeParent.class))
            .addSuperinterface(TypeDef.parameterized(java.util.function.Consumer.class, String.class)).build();
        java.util.function.Consumer<String> generated = (java.util.function.Consumer<String>) define(definition).getConstructor().newInstance();
        generated.accept("value");
        assertEquals("value", ((PrivateBridgeParent) generated).seen);
    }

    @Test
    public void twoInterfacesNeedingTheSameBridgeGetOne() throws Exception {
        var definition = ClassDef.builder("test.hardening.SameBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addSuperinterface(TypeDef.parameterized(Getter.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("v").returning())).build();
        assertEquals(shape(SameBridge.class), shape(define(definition)));
    }

    @Test
    public void twoInterfacesNeedingDifferentBridgesGetBoth() throws Exception {
        var definition = ClassDef.builder("test.hardening.TwoBridges").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, Integer.class))
            .addSuperinterface(ClassTypeDef.of(NumberSource.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.of(Integer.class))
                .build((self, p) -> ExpressionDef.constant(7).cast(TypeDef.of(Integer.class)).returning())).build();
        Class<?> generated = define(definition);
        assertEquals(shape(TwoBridges.class), shape(generated));
        Object instance = generated.getConstructor().newInstance();
        assertEquals(7, ((Supplier<?>) instance).get());
        assertEquals(7, ((NumberSource) instance).get());
    }

    @Test
    public void genericSignaturesOfABridgedGenericMethodMatchJavac() throws Exception {
        var variable = TypeDef.variable("U");
        var definition = ClassDef.builder("test.hardening.StringPick").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(GenericPick.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addTypeVariable(variable)
                .addParameter("first", variable).addParameter("second", TypeDef.STRING).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("sub").returning())).build();
        assertEquals(genericShape(StringPick.class), genericShape(define(definition)));
    }

    @Test
    public void covariantReturnWithoutGenericsIsBridged() throws Exception {
        var definition = ClassDef.builder("test.hardening.IntegerGetter").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(NumberGetter.class))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.of(Integer.class))
                .build((self, p) -> ExpressionDef.constant(7).cast(TypeDef.of(Integer.class)).returning())).build();
        NumberGetter generated = (NumberGetter) define(definition).getConstructor().newInstance();
        assertEquals(new IntegerGetter().get(), generated.get());
        assertEquals(shape(IntegerGetter.class), shape(generated.getClass()));
    }

    @Test
    public void covariantCloneIsBridged() throws Exception {
        var definition = ClassDef.builder("test.hardening.CloneFixture").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(Cloneable.class))
            .addMethod(MethodDef.builder("clone").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("test.hardening.CloneFixture"))
                .build((self, p) -> self.returning())).build();
        assertEquals(shape(CloneFixture.class), shape(define(definition)));
    }

    @Test
    public void covariantCloneOfAnExplicitSuperclassIsBridged() throws Exception {
        var definition = ClassDef.builder("test.hardening.ExplicitCloneFixture").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(PlainBase.class))
            .addSuperinterface(ClassTypeDef.of(Cloneable.class))
            .addMethod(MethodDef.builder("clone").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("test.hardening.ExplicitCloneFixture"))
                .build((self, p) -> self.returning())).build();
        assertEquals(shape(ExplicitCloneFixture.class), shape(define(definition)));
    }

    @Test
    public void bridgeOfAProtectedMethodIsProtected() throws Exception {
        var definition = ClassDef.builder("test.hardening.ProtectedBridge").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ProtectedGeneric.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PROTECTED)
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("v").returning())).build();
        assertEquals(shape(ProtectedBridge.class), shape(define(definition)));
    }

    @Test
    public void bridgeOfAFinalSynchronizedMethodIsNeither() throws Exception {
        var definition = ClassDef.builder("test.hardening.FinalBridge").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.FINAL, Modifier.SYNCHRONIZED)
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("v").returning())).build();
        assertEquals(shape(FinalBridge.class), shape(define(definition)));
    }

    @Test
    public void defaultMethodOfAnInterfaceIsBridged() throws Exception {
        var definition = InterfaceDef.builder("test.hardening.DefaultSupplier").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("d").returning())).build();
        assertEquals(shape(DefaultSupplier.class), shape(define(definition)));
    }

    @Test
    public void publicSubclassOfAPackagePrivateClassBridgesItsPublicMethods() throws Exception {
        var definition = ClassDef.builder(AbstractByteCodeWriterTck.class.getPackageName() + ".HardeningVisibleChild")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(HiddenBase.class)).build();
        Class<?> generated = defineInPackage(definition);
        assertEquals(shape(VisibleChild.class), shape(generated));
    }

    @Test
    public void enumImplementingAGenericInterfaceIsBridged() throws Exception {
        var definition = EnumDef.builder("test.hardening.EnumSupplier").addModifiers(Modifier.PUBLIC)
            .addEnumConstant("A")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("a").returning())).build();
        Class<?> generated = define(definition);
        assertEquals(EnumSupplier.A.get(), ((Supplier<?>) generated.getEnumConstants()[0]).get());
    }

    @Test
    public void genericMethodOverriddenInAParameterizedSubclassIsBridged() throws Exception {
        var variable = TypeDef.variable("U");
        var definition = ClassDef.builder("test.hardening.StringPick").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(GenericPick.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addTypeVariable(variable)
                .addParameter("first", variable).addParameter("second", TypeDef.STRING).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("sub").returning())).build();
        GenericPick<String> generated = (GenericPick<String>) define(definition).getConstructor().newInstance();
        assertEquals(new StringPick().pick(1, "x"), generated.pick(1, "x"));
        assertEquals(shape(StringPick.class), shape(generated.getClass()));
    }

    /**
     * A generic contract whose method declares a separate bounded variable.
     *
     * @param <T> The result type
     * @since 2.3
     */
    public interface GenericContract<T> {
        /**
         * Converts a number.
         *
         * @param value The number
         * @param <U> The numeric type
         * @return The converted value
         */
        <U extends Number> T convert(U value);
    }

    /**
     * An inherited implementation that predates a subclass implementing Supplier.
     *
     * @since 2.3
     */
    public static class StringSupplier {
        /**
         * Returns the inherited result.
         *
         * @return The result
         */
        public String get() {
            return "inherited";
        }
    }

    /**
     * A generic superclass used raw by a generated subclass.
     *
     * @param <T> The value type
     * @since 2.3
     */
    public static class GenericParent<T> {
        /**
         * Returns the unchanged value.
         *
         * @param value The value
         * @return The unchanged value
         */
        public T identity(T value) {
            return value;
        }
    }

    /**
     * A contract requiring a multidimensional generic array bridge.
     *
     * @param <T> The element type
     * @since 2.3
     */
    public interface ArrayContract<T> {
        /**
         * Returns the unchanged array.
         *
         * @param value The array
         * @return The unchanged array
         */
        T[][] identity(T[][] value);
    }

    /**
     * A method bound depends on the class variable.
     * @param <T> Bound
     * @since 2.3
     */
    public static class MethodBoundParent<T extends Number> {
        public <U extends T> U apply(U value) {
            return value;
        }
    }

    /**
     * A contract whose generic method erases to Object.
     * @param <T> Bound
     * @since 2.3
     */
    public interface MethodBoundFunction<T> {
        <U extends T> U apply(U value);
    }

    /**
     * javac verifies the inherited generic method implements the contract.
     * @since 2.3
     */
    public static class MethodBoundChild extends MethodBoundParent<Integer> implements MethodBoundFunction<Integer> { }

    /**
     * Another erased return contract.
     * @since 2.3
     */
    public interface SequenceSupplier {
        CharSequence get();
    }

    /** Private erased overload cannot satisfy an inherited public interface contract.
     * @since 2.3
     */
    public static class PrivateBridgeParent {
        private String seen;

        private void accept(Object value) {
            throw new AssertionError("private overload");
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @param value The fixture input
         *
         * @since 2.3
         */
        public void accept(String value) {
            seen = value;
        }
    }

    /** javac emits a public accept(Object) bridge despite the private superclass method.
     * @since 2.3
     */
    public static class PrivateBridgeChild extends PrivateBridgeParent implements java.util.function.Consumer<String> { }

    /** A second generic getter.
     * @param <T> The result
     * @since 2.3
     */
    public interface Getter<T> {
        T get();
    }

    /** javac's one bridge for two interfaces.
     * @since 2.3
     */
    public static class SameBridge implements Supplier<String>, Getter<String> {
        @Override
        public String get() {
            return "v";
        }
    }

    /** A covariant source.
     * @since 2.3
     */
    public interface NumberSource {
        Number get();
    }

    /** javac's two bridges.
     * @since 2.3
     */
    public static class TwoBridges implements Supplier<Integer>, NumberSource {
        @Override
        public Integer get() {
            return 7;
        }
    }

    /** A superclass declaring nothing.
     * @since 2.3
     */
    public static class PlainBase { }

    /** javac's covariant clone below an explicit superclass.
     * @since 2.3
     */
    public static class ExplicitCloneFixture extends PlainBase implements Cloneable {
        @Override
        public ExplicitCloneFixture clone() {
            return this;
        }
    }

    /** A protected generic method.
     * @param <T> The result
     * @since 2.3
     */
    public static class ProtectedGeneric<T> {
        protected T get() {
            return null;
        }
    }

    /** javac's protected bridge.
     * @since 2.3
     */
    public static class ProtectedBridge extends ProtectedGeneric<String> {
        @Override
        protected String get() {
            return "v";
        }
    }

    /** A plain covariant return.
     * @since 2.3
     */
    public static class NumberGetter {
        public Number get() {
            return 1;
        }
    }

    /** javac's narrowing override.
     * @since 2.3
     */
    public static class IntegerGetter extends NumberGetter {
        @Override
        public Integer get() {
            return 7;
        }
    }

    /** javac's covariant clone.
     * @since 2.3
     */
    public static class CloneFixture implements Cloneable {
        @Override
        public CloneFixture clone() {
            return this;
        }
    }

    /** javac's bridge of a final synchronized method.
     * @since 2.3
     */
    public static class FinalBridge implements Supplier<String> {
        @Override
        public final synchronized String get() {
            return "v";
        }
    }

    /** javac's bridge of a default method.
     * @since 2.3
     */
    public interface DefaultSupplier extends Supplier<String> {
        @Override
        default String get() {
            return "d";
        }
    }

    /** A package-private superclass.
     * @since 2.3
     */
    static class HiddenBase {
        public String value() {
            return "base";
        }
    }

    /** javac's visibility bridge.
     * @since 2.3
     */
    public static class VisibleChild extends HiddenBase { }

    /** A generic method of a generic class.
     * @param <T> The class variable
     * @since 2.3
     */
    public static class GenericPick<T> {
        public <U> T pick(U first, T second) {
            return second;
        }
    }

    /** javac's override in a parameterized subclass.
     * @since 2.3
     */
    public static class StringPick extends GenericPick<String> {
        @Override
        public <U> String pick(U first, String second) {
            return "sub";
        }
    }
}
