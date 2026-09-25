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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.bytecode.tck.GeneratedClassLoader;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bridges the bytecode writer resolves through TypeHierarchy: each model is written, loaded
 * and run, and compared with what javac writes for the same Java source.
 */
class TypeHierarchyBridgeTest {

    /**
     * {@code OrderedSink<T extends Object & Comparable<T>>}: the writer describes {@code accept(T)} as
     * {@code accept(Object)} - the leftmost bound (JLS 4.6, TypeUtils) - but TypeHierarchy erases {@code T} to the first
     * bound that is not Object, so an {@code OrderedSink<String>} implementing {@code accept(String)} is given the
     * bridge {@code accept(Comparable)} rather than {@code accept(Object)}. Calling the interface method then fails with
     * an AbstractMethodError. Expected: the bridge {@code accept(Object)}, as javac writes it.
     */
    @Test
    void intersectionBoundLedByObjectIsBridgedWithItsLeftmostBound() throws Exception {
        var sink = InterfaceDef.builder("test.BridgedOrderedSink").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.OBJECT,
                TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T"))))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(String.class).build())
            .build();
        var impl = ClassDef.builder("test.BridgedOrderedStringSink").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(sink.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", String.class).returns(String.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        var loader = load(sink, impl);
        var sinkType = loader.loadClass(sink.getName());
        var instance = loader.loadClass(impl.getName()).getConstructor().newInstance();

        assertEquals("x", sinkType.getMethod("accept", Object.class).invoke(instance, "x"));
    }

    /**
     * {@code Base<T extends Number>} declares {@code <T> String m(T)} - its own, unbounded {@code T}, erased to
     * {@code m(Object)} - and an unrelated overload {@code String m(Number)}. {@code Impl extends Base<Integer>}
     * overrides the generic one. TypeHierarchy erases the method's name-only {@code T} with the bound of the class's
     * {@code T}, so the writer adds a bridge {@code m(Number)} to {@code Impl}, which overrides the unrelated
     * {@code Base.m(Number)} and sends it to the generic override. Expected: {@code Impl} declares no bridge, as javac
     * writes it, and {@code m(Number)} runs {@code Base}'s.
     */
    @Test
    void unboundedMethodVariableShadowingABoundedClassVariableAddsNoBridge() throws Exception {
        var classT = TypeDef.variable("T", TypeDef.of(Number.class));
        var methodT = TypeDef.variable("T");
        var base = ClassDef.builder("test.ShadowBase").addModifiers(Modifier.PUBLIC).addTypeVariable(classT)
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC).addTypeVariable(methodT)
                .addParameter("value", methodT).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("generic").returning()))
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Number.class).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("number").returning()))
            .build();
        var impl = ClassDef.builder("test.ShadowSub").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(base), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(methodT)
                .addParameter("value", methodT).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("override").returning()))
            .build();
        var loader = load(base, impl);
        var baseType = loader.loadClass(base.getName());
        var implType = loader.loadClass(impl.getName());
        var instance = implType.getConstructor().newInstance();

        assertEquals("number", baseType.getMethod("m", Number.class).invoke(instance, 1),
            Arrays.toString(implType.getDeclaredMethods()));
    }

    /**
     * Control: the models {@code OverrideResolutionRegressionTest} renders as Java source that javac rejects are valid for the
     * bytecode writer - each erased override has the descriptor of the inherited method - and run.
     */
    @Test
    void erasedOverridesJavacRejectsAsSourceRunAsBytecode() throws Exception {
        // <T> T id(Object, T) of a ShadowApi<String>, in a class whose own T is bounded
        var x = TypeDef.variable("X");
        var methodT = TypeDef.variable("T");
        var api = InterfaceDef.builder("test.ControlShadowApi").addModifiers(Modifier.PUBLIC).addTypeVariable(x)
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(methodT)
                .addParameter("key", x).addParameter("value", methodT).returns(methodT).build())
            .build();
        var impl = ClassDef.builder("test.ControlShadowImpl").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(api.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(methodT)
                .addParameter("key", Object.class).addParameter("value", methodT).returns(methodT)
                .build((self, p) -> p.get(1).returning()))
            .build();
        // <U> U narrow(Object) of a NarrowConv<Number> declaring <U extends T> U narrow(T)
        var t = TypeDef.variable("T");
        var conv = InterfaceDef.builder("test.ControlNarrowConv").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("narrow").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(TypeDef.variable("U", t)).addParameter("value", t).returns(TypeDef.variable("U")).build())
            .build();
        var u = TypeDef.variable("U");
        var narrow = ClassDef.builder("test.ControlNarrowNumbers").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(conv.asTypeDef(), TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("narrow").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(u)
                .addParameter("value", Object.class).returns(u)
                .build((self, p) -> p.get(0).returning()))
            .build();
        // <U> String m(Object, U) of a CaptureApi<List<U>> in a class whose own variable is U as well
        var captureApi = InterfaceDef.builder("test.ControlCaptureApi").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(u)
                .addParameter("t", t).addParameter("u", u).returns(String.class).build())
            .build();
        var captureImpl = ClassDef.builder("test.ControlCaptureImpl").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(captureApi.asTypeDef(),
                TypeDef.parameterized(ClassTypeDef.of(java.util.List.class), u)))
            .addMethod(MethodDef.builder("m").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(u)
                .addParameter("t", Object.class).addParameter("u", u).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("captured").returning()))
            .build();
        var loader = load(api, impl, conv, narrow, captureApi, captureImpl);

        var captured = loader.loadClass(captureImpl.getName()).getConstructor().newInstance();
        assertEquals("captured", loader.loadClass(captureApi.getName()).getMethod("m", Object.class, Object.class)
            .invoke(captured, java.util.List.of(), 1));
        var shadow = loader.loadClass(impl.getName()).getConstructor().newInstance();
        assertEquals(7, loader.loadClass(api.getName()).getMethod("id", Object.class, Object.class).invoke(shadow, "k", 7));
        var numbers = loader.loadClass(narrow.getName()).getConstructor().newInstance();
        assertEquals(3, loader.loadClass(conv.getName()).getMethod("narrow", Object.class).invoke(numbers, 3));
    }

    /**
     * A record whose {@code Object value} component implements {@code T value()} of a {@code ValueSource<String>}:
     * the implicit accessor {@code value()Object} already has the descriptor of the interface method. The bridge
     * resolver takes the descriptors the definition declares from its methods only - an accessor of a component is
     * none - and adds the bridge {@code value()Object} as well: "ClassFormatError: Duplicate method name value with
     * signature ()Ljava.lang.Object;". Expected: no bridge, the accessor implements the method.
     */
    @Test
    void recordAccessorWithTheErasedDescriptorIsNotBridgedAgain() throws Exception {
        var t = TypeDef.variable("T");
        var source = InterfaceDef.builder("test.BridgedValueSource").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var record = io.micronaut.sourcegen.model.RecordDef.builder("test.BridgedValueRecord").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(source.asTypeDef(), TypeDef.STRING))
            .addProperty(io.micronaut.sourcegen.model.PropertyDef.builder("value").ofType(Object.class).build())
            .build();
        var loader = load(source, record);

        var value = loader.loadClass(record.getName()).getConstructor(Object.class).newInstance("v");
        assertEquals("v", loader.loadClass(source.getName()).getMethod("value").invoke(value));
    }

    /**
     * The same for the getter of a class property: {@code Object value} of a class implementing
     * {@code T getValue()} of a {@code ValueHolder<String>} is given the bridge {@code getValue()Object} next to its
     * getter of that descriptor - "ClassFormatError: Duplicate method name getValue".
     */
    @Test
    void propertyGetterWithTheErasedDescriptorIsNotBridgedAgain() throws Exception {
        var t = TypeDef.variable("T");
        var holder = InterfaceDef.builder("test.BridgedValueHolder").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("getValue").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(t).build())
            .build();
        var bean = ClassDef.builder("test.BridgedValueBean").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(holder.asTypeDef(), TypeDef.STRING))
            .addProperty(io.micronaut.sourcegen.model.PropertyDef.builder("value").addModifiers(Modifier.PUBLIC)
                .ofType(Object.class).build())
            .build();
        var loader = load(holder, bean);

        var instance = loader.loadClass(bean.getName()).getConstructor().newInstance();
        assertEquals(null, loader.loadClass(holder.getName()).getMethod("getValue").invoke(instance));
    }

    /**
     * A (non-static) inner class of a generic class implementing {@code Supplier<T>} with the enclosing class's
     * {@code T}: the bytecode writer, like the Java source generator (see
     * {@code OverrideResolutionRegressionTest#innerClassOverridesWithTheEnclosingClassVariableAsReturn}), takes only the
     * variables a definition declares itself as in scope and writes the signature {@code Supplier<Object>}.
     * Expected: {@code Supplier<T>}, as javac writes it for the same source.
     */
    @Test
    void innerClassKeepsTheEnclosingClassVariableInItsSignature() throws Exception {
        var t = TypeDef.variable("T");
        var inner = ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(java.util.function.Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.nullValue().returning()))
            .build();
        var outer = ClassDef.builder("test.BytecodeInnerSupplierOuter").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addInnerType(inner)
            .build();
        var storedInner = outer.getInnerTypes().get(0);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(outer.getName(), new ByteCodeWriter(true, true).write(outer));
        classes.put(storedInner.getName(), new ByteCodeWriter(true, true).write(storedInner, outer.asTypeDef()));
        var loader = new GeneratedClassLoader(classes);

        assertEquals("java.util.function.Supplier<T>",
            loader.loadClass(storedInner.getName()).getGenericInterfaces()[0].getTypeName());
    }

    private static ClassLoader load(ObjectDef... definitions) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (ObjectDef definition : definitions) {
            classes.put(definition.getName(), new ByteCodeWriter(true, true).write(definition));
        }
        return new GeneratedClassLoader(classes);
    }
}
