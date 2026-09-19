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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.AbstractList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review probes for the bridges resolved from the supertype walk.
 *
 * @since 2.2.2
 */
class ReviewBridgeResolverTest {

    private static final String OBJECT = "Ljava/lang/Object;";

    /** A compiled generic superclass. */
    public abstract static class CompiledBase<T> {
        public abstract T get();
    }

    /**
     * A compiled generic method whose own variable lists {@code Object} ahead of its real bound: javac erases
     * {@code U} to {@code Object} (JLS 4.6, the leftmost bound), so the class file declares {@code pick(Object, Object)}.
     */
    public static class ObjectBoundBase<T> {
        public <U extends Object & Comparable<U>> T pick(T value, U other) {
            return null;
        }
    }

    /**
     * The child overrides with javac's erasure as a member of `ObjectBoundBase<String>`: `pick(String, Object)`.
     * The walk erases `U` to `Comparable` (its first bound that is not Object), which does not match the declared
     * `Object`, so no bridge is resolved and `pick(Object, Object)` of the base is not overridden. Expected: the
     * bridge `pick(Object, Object)Object`.
     */
    @Test
    void reflectedMethodVariableListingObjectBeforeItsBoundIsErasedToObject() {
        MethodDef pick = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", TypeDef.STRING).addParameter("other", TypeDef.OBJECT).returns(TypeDef.STRING)
            .build((aThis, p) -> p.get(0).returning());
        ClassDef child = ClassDef.builder("example.ObjectBoundChild")
            .superclass(TypeDef.parameterized(ObjectBoundBase.class, String.class))
            .addMethod(pick)
            .build();

        assertEquals(List.of("(" + OBJECT + OBJECT + ")" + OBJECT), descriptors(child, pick));
    }

    /** `String get(int)` in a class extending the compiled `AbstractList<String>`. */
    @Test
    void abstractListGetIsBridged() {
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("index", TypeDef.Primitive.INT).returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        ClassDef def = ClassDef.builder("example.Strings")
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(get)
            .build();

        assertEquals(List.of("(I)" + OBJECT), descriptors(def, get));
    }

    /** `Map.Entry<String, Integer>`, a member interface of a compiled class. */
    @Test
    void memberInterfaceOfACompiledClassIsBridged() {
        MethodDef setValue = MethodDef.builder("setValue").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", TypeDef.of(Integer.class)).returns(TypeDef.of(Integer.class))
            .build((aThis, p) -> p.get(0).returning());
        ClassDef def = ClassDef.builder("example.Pair")
            .addSuperinterface(TypeDef.parameterized(Map.Entry.class, String.class, Integer.class))
            .addMethod(setValue)
            .build();

        assertEquals(List.of("(" + OBJECT + ")" + OBJECT), descriptors(def, setValue));
    }

    /** The protected `String initialValue()` of a `ThreadLocal<String>`. */
    @Test
    void protectedMethodOfAnotherPackageIsBridged() {
        MethodDef initialValue = MethodDef.builder("initialValue").addModifiers(Modifier.PROTECTED).overrides()
            .returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        ClassDef def = ClassDef.builder("example.Local")
            .superclass(TypeDef.parameterized(ThreadLocal.class, String.class))
            .addMethod(initialValue)
            .build();

        assertEquals(List.of("()" + OBJECT), descriptors(def, initialValue));
    }

    /** `Box<T extends Number> implements Comparable<T>` with `compareTo(T)`: `T` erases to Number, the bridge takes an Object. */
    @Test
    void boundedClassVariableOfAComparableIsBridged() {
        TypeDef.TypeVariable t = TypeDef.variable("T", TypeDef.of(Number.class));
        MethodDef compareTo = MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("other", TypeDef.variable("T")).returns(TypeDef.Primitive.INT)
            .build((aThis, p) -> ExpressionDef.constant(0).returning());
        ClassDef def = ClassDef.builder("example.NumberBox").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparable.class), t))
            .addMethod(compareTo)
            .build();

        assertEquals(List.of("(" + OBJECT + ")I"), descriptors(def, compareTo));
    }

    /** A generated `Arr<T>` with `int count(T[])` implemented for `Arr<String[]>` as `count(String[][])`. */
    @Test
    void arrayOfATypeVariableBoundToAnArrayIsBridged() {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        InterfaceDef arr = InterfaceDef.builder("example.Arr").addTypeVariable(t)
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("values", t.array()).returns(TypeDef.Primitive.INT).build())
            .build();
        MethodDef count = MethodDef.builder("count").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("values", TypeDef.array(TypeDef.STRING, 2)).returns(TypeDef.Primitive.INT)
            .build((aThis, p) -> ExpressionDef.constant(0).returning());
        ClassDef def = ClassDef.builder("example.Matrix")
            .addSuperinterface(TypeDef.parameterized(arr.asTypeDef(), TypeDef.STRING.array()))
            .addMethod(count)
            .build();

        assertEquals(List.of("([" + OBJECT + ")I"), descriptors(def, count));
    }

    /** A package-private generic method of a generated member type, `pkg.Outer$Base`, overridden from `pkg` and from another package. */
    @Test
    void packagePrivateMethodOfAGeneratedMemberTypeIsOfTheOuterPackage() {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        ClassDef base = ClassDef.builder("Base").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.ABSTRACT).addTypeVariable(t)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.ABSTRACT).returns(t).build())
            .build();
        ClassDef outer = ClassDef.builder("pkg.Outer").addInnerType(base).build();
        ObjectDef storedBase = outer.getInnerTypes().get(0);
        MethodDef make = MethodDef.builder("make").overrides().returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        ClassDef samePackage = ClassDef.builder("pkg.Child")
            .superclass(TypeDef.parameterized(storedBase.asTypeDef(), TypeDef.STRING)).addMethod(make).build();
        ClassDef otherPackage = ClassDef.builder("other.Child")
            .superclass(TypeDef.parameterized(storedBase.asTypeDef(), TypeDef.STRING)).addMethod(make).build();

        assertEquals("pkg.Outer$Base", storedBase.asTypeDef().getName());
        assertEquals(List.of("()" + OBJECT), descriptors(samePackage, make));
        assertEquals(List.of(), descriptors(otherPackage, make));
    }

    /** Depth 3: `Child extends GenMiddle<String>`, `GenMiddle<T> extends CompiledBase<List<T>>`, with `List get()`. */
    @Test
    void depthThreeMixingGeneratedAndCompiledTypes() {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        ClassDef middle = ClassDef.builder("example.GenMiddle").addModifiers(Modifier.ABSTRACT).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(CompiledBase.class), TypeDef.parameterized(ClassTypeDef.of(List.class), t)))
            .build();
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
            .returns(TypeDef.parameterized(List.class, String.class))
            .build((aThis, p) -> ExpressionDef.nullValue().returning());
        ClassDef child = ClassDef.builder("example.Deep")
            .superclass(TypeDef.parameterized(middle.asTypeDef(), TypeDef.STRING))
            .addMethod(get)
            .build();

        assertEquals(List.of("()" + OBJECT), descriptors(child, get));
    }

    /** A raw generated middle type: the erased member `Object get()` is still bridged to from `String get()`. */
    @Test
    void rawSupertypeInTheMiddleStillBridgesTheErasedMember() {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        ClassDef middle = ClassDef.builder("example.RawMiddle").addModifiers(Modifier.ABSTRACT).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(CompiledBase.class), t))
            .build();
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        ClassDef child = ClassDef.builder("example.RawChild").superclass(ClassTypeDef.of(middle)).addMethod(get).build();

        assertEquals(List.of("()" + OBJECT), descriptors(child, get));
    }

    /**
     * An enum supplying itself: `Cycle get()` is bridged. The walk now passes through `Enum<Cycle>` and its
     * `Comparable<Cycle>`, so `String name()` (final in Enum) gets no bridge; an enum overriding `compareTo` is
     * not valid Java (final) and is not probed.
     */
    @Test
    void enumWalksThroughEnum() {
        ClassTypeDef type = ClassTypeDef.of("example.Cycle");
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(type)
            .build((aThis, p) -> ExpressionDef.nullValue().returning());
        MethodDef name = MethodDef.builder("name").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("A").returning());
        EnumDef def = EnumDef.builder("example.Cycle").addEnumConstant("A")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), type))
            .addMethod(get).addMethod(name)
            .build();

        assertEquals(List.of("()" + OBJECT), descriptors(def, get));
        assertEquals(List.of(), descriptors(def, name));
    }

    /** A record supplying a String: `get` is bridged, and its own `String toString()` of `Record` is not. */
    @Test
    void recordWalksThroughRecord() {
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        MethodDef toString = MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
            .build((aThis, p) -> ExpressionDef.constant("x").returning());
        RecordDef def = RecordDef.builder("example.Rec")
            .addProperty(PropertyDef.builder("value").ofType(String.class).build())
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(get).addMethod(toString)
            .build();

        assertEquals(List.of("()" + OBJECT), descriptors(def, get));
        assertEquals(List.of(), descriptors(def, toString));
    }

    /** `equals(Object)` next to `compare(String, String)` of a `Comparator<String>`, which declares `equals` as well. */
    @Test
    void objectMethodsOfAComparatorGetNoBridge() {
        MethodDef compare = MethodDef.builder("compare").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("a", TypeDef.STRING).addParameter("b", TypeDef.STRING).returns(TypeDef.Primitive.INT)
            .build((aThis, p) -> ExpressionDef.constant(0).returning());
        MethodDef equals = MethodDef.builder("equals").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("o", TypeDef.OBJECT).returns(TypeDef.Primitive.BOOLEAN)
            .build((aThis, p) -> ExpressionDef.trueValue().returning());
        ClassDef def = ClassDef.builder("example.ByLength")
            .addSuperinterface(TypeDef.parameterized(Comparator.class, String.class))
            .addMethod(compare).addMethod(equals)
            .build();

        assertEquals(List.of("(" + OBJECT + OBJECT + ")I"), descriptors(def, compare));
        assertEquals(List.of(), descriptors(def, equals));
    }

    /** `<T> T[] toArray(T[])` of `Collection` overridden erased, and with a variable of its own: no bridge either way. */
    @Test
    void genericToArrayGetsNoBridge() {
        MethodDef erased = MethodDef.builder("toArray").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("a", TypeDef.OBJECT.array()).returns(TypeDef.OBJECT.array())
            .build((aThis, p) -> p.get(0).returning());
        TypeDef.TypeVariable t = TypeDef.variable("T");
        MethodDef generic = MethodDef.builder("toArray").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(t)
            .addParameter("a", t.array()).returns(t.array())
            .build((aThis, p) -> p.get(0).returning());
        ClassDef def = ClassDef.builder("example.ToArray")
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(erased)
            .build();
        ClassDef genericDef = ClassDef.builder("example.GenericToArray")
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(generic)
            .build();

        assertEquals(List.of(), descriptors(def, erased));
        assertEquals(List.of(), descriptors(genericDef, generic));
    }

    /** `Object get()` overriding `Number get()` of a generated superclass: the bridge with the narrower return, as the writer casts. */
    @Test
    void widerReturnOverANarrowerDeclarationIsBridged() {
        ClassDef source = ClassDef.builder("example.NumberSource").addModifiers(Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(Number.class).build())
            .build();
        MethodDef get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build((aThis, p) -> ExpressionDef.constant(5).returning());
        ClassDef def = ClassDef.builder("example.FiveSource").superclass(ClassTypeDef.of(source)).addMethod(get).build();

        assertEquals(List.of("()Ljava/lang/Number;"), descriptors(def, get));
    }

    private static List<String> descriptors(ObjectDef objectDef, MethodDef methodDef) {
        return BridgeResolver.resolve(objectDef, methodDef).stream()
            .map(bridge -> bridge.parameterTypes().stream()
                .map(type -> TypeUtils.getDescriptor(type, null))
                .reduce("", String::concat)
                .transform(parameters -> "(" + parameters + ")" + TypeUtils.getDescriptor(bridge.returnType(), null)))
            .sorted()
            .toList();
    }
}
