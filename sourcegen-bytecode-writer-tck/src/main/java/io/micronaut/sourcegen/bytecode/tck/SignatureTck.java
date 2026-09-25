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

import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.EnumSupplier;
import io.micronaut.sourcegen.bytecode.tck.SignatureFixtures.InheritedSpecializedBridge;
import io.micronaut.sourcegen.bytecode.tck.SignatureFixtures.Marker;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Erasure and generic signatures: descriptors, Signature attributes, type annotations and member flags of
 * generated classes, compared with the classes javac writes from the same declarations.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "unchecked", "rawtypes", "varargs", "TypeParameterUnusedInFormals", "MissingOverride",
    "UnusedTypeParameter", "unused", "EqualsIncompatibleType", "UnusedMethod", "UnusedVariable"
})
public abstract class SignatureTck extends AbstractByteCodeWriterTck {

    @Test
    public void methodVariableUsesItsDeclaredBound() throws Exception {
        var variable = TypeDef.variable("T");
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addParameter("value", variable).returns(variable)
            .build((self, p) -> p.getFirst().returning());
        var type = define(ClassDef.builder("test.MethodBound").addModifiers(Modifier.PUBLIC).addMethod(method).build());
        var reflected = type.getMethod("identity", Number.class);
        assertEquals(Number.class, reflected.getReturnType());
        assertEquals(Number.class, reflected.getTypeParameters()[0].getBounds()[0]);
        assertEquals(3, reflected.invoke(type.getConstructor().newInstance(), 3));
    }

    @Test
    public void methodVariableShadowsClassVariable() throws Exception {
        var variable = TypeDef.variable("T");
        var type = define(ClassDef.builder("test.ShadowedBound").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addParameter("value", variable).returns(variable)
                .build((self, p) -> p.getFirst().returning())).build());
        assertEquals(3, type.getMethod("identity", Number.class).invoke(type.getConstructor().newInstance(), 3));
    }

    @Test
    public void methodVariableArrayUsesChainedBound() throws Exception {
        var array = TypeDef.variable("U").array(2);
        var type = define(ClassDef.builder("test.MethodArrayBound").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .addTypeVariable(TypeDef.variable("U", TypeDef.variable("T")))
                .addParameter("value", array).returns(array)
                .build((self, p) -> p.getFirst().returning())).build());
        var values = new Integer[][] {{1}};
        assertSame(values, type.getMethod("identity", Number[][].class)
            .invoke(type.getConstructor().newInstance(), (Object) values));
    }

    @Test
    public void classVariableArrayUsesChainedBound() throws Exception {
        var array = TypeDef.variable("U").array(2);
        var type = define(ClassDef.builder("test.ClassArrayBound").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addTypeVariable(TypeDef.variable("U", TypeDef.variable("T")))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", array).returns(array)
                .build((self, p) -> p.getFirst().returning())).build());
        var values = new Integer[][] {{1}};
        assertSame(values, type.getMethod("identity", Number[][].class)
            .invoke(type.getConstructor().newInstance(), (Object) values));
    }

    @Test
    public void explicitObjectFirstIntersectionErasesToObject() throws Exception {
        var variable = TypeDef.variable("T");
        var type = define(ClassDef.builder("test.ObjectIntersection").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.OBJECT, TypeDef.of(Runnable.class)))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", variable).returns(variable)
                .build((self, p) -> p.getFirst().returning())).build());
        Runnable value = () -> { };
        assertSame(value, type.getMethod("identity", Object.class).invoke(type.getConstructor().newInstance(), value));
    }

    @Test
    public void retainsUnusedMethodTypeParameters() throws Exception {
        var type = define(ClassDef.builder("test.UnusedMethodVariable").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("ok").returning())).build());
        var variables = type.getMethod("value").getTypeParameters();
        assertEquals(1, variables.length);
        assertEquals(Number.class, variables[0].getBounds()[0]);
    }

    @Test
    public void retainsGenericExceptionVariable() throws Exception {
        var variable = TypeDef.variable("E", TypeDef.of(Exception.class));
        var type = define(ClassDef.builder("test.GenericThrows").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(variable).addParameter("failure", variable).addThrows(variable)
                .returns(TypeDef.VOID).build((self, p) -> p.getFirst().doThrow())).build());
        Method method = type.getMethod("value", Exception.class);
        assertEquals(method.getTypeParameters()[0], method.getGenericExceptionTypes()[0]);
    }

    @Test
    public void writesAbstractMethodWithParameters() throws Exception {
        var parent = InterfaceDef.builder("test.AbstractParameter").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.STRING).returns(TypeDef.VOID).build()).build();
        assertEquals(void.class, define(parent).getMethod("accept", String.class).getReturnType());
    }

    @Test
    public void genericConstructorUsesItsOwnBound() throws Exception {
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("T"))
            .build((self, p) -> self.superRef().invokeSuperConstructor());
        var type = define(ClassDef.builder("test.GenericConstructor").addModifiers(Modifier.PUBLIC)
            .addMethod(constructor).build());
        assertEquals(type, type.getConstructor(Number.class).newInstance(7).getClass());
    }

    @Test
    public void recursiveClassBoundPreservesSignature() throws Exception {
        var bound = TypeDef.parameterized(Comparable.class, TypeDef.variable("T"));
        var variable = TypeDef.variable("T", bound);
        var type = define(ClassDef.builder("test.RecursiveBound").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
                .build((self, p) -> p.getFirst().returning())).build());
        var reflectedVariable = type.getTypeParameters()[0];
        var reflectedBound = (ParameterizedType) reflectedVariable.getBounds()[0];
        assertEquals(Comparable.class, reflectedBound.getRawType());
        assertEquals(reflectedVariable, reflectedBound.getActualTypeArguments()[0]);
        assertEquals("ok", type.getMethod("identity", Comparable.class).invoke(type.getConstructor().newInstance(), "ok"));
    }

    @Test
    public void nestedWildcardsAndGenericArraysPreserveSignature() throws Exception {
        var nested = TypeDef.parameterized(Map.class, TypeDef.STRING,
            TypeDef.parameterized(List.class, TypeDef.wildcardSupertypeOf(TypeDef.variable("T").array())));
        var type = define(ClassDef.builder("test.NestedWildcards").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .addParameter("value", nested).returns(nested)
                .build((self, p) -> p.getFirst().returning())).build());
        var method = type.getMethod("identity", Map.class);
        assertEquals("java.util.Map<java.lang.String, java.util.List<? super T[]>>", method.getGenericReturnType().getTypeName());
        var value = Map.of("one", List.of(new Number[] {1}));
        assertSame(value, method.invoke(type.getConstructor().newInstance(), value));
    }

    @Test
    public void enumMethodRetainsItsOwnGenericSignatureAndErasedDescriptor() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(variable)
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var definition = EnumDef.builder("test.completeness.GenericMethodEnum").addModifiers(Modifier.PUBLIC)
            .addEnumConstant("VALUE").addMethod(method).build();
        var generated = define(definition);
        var reflected = generated.getMethod("identity", Number.class);
        assertEquals(Number.class, reflected.getReturnType());
        assertEquals("T", reflected.getGenericReturnType().getTypeName());
        assertEquals(List.of(Number.class), Arrays.asList(reflected.getTypeParameters()[0].getBounds()));
        assertEquals(7, reflected.invoke(generated.getEnumConstants()[0], 7));
    }

    /**
     * Checks metadata and descriptors independently, with name-only and inline-bound references.
     *
     * @param reference The fixture javac compiled
     * @param inline    Whether the model writes type variables inline with their bounds rather than by name
     * @param part      The part of the class compared with javac's
     * @throws Exception If the generated class cannot be loaded
     */
    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("genericSignatureCases")
    public void genericSignaturesMatchJavac(Class<?> reference, boolean inline, String part) throws Exception {
        Class<?> generated = define(model(reference, inline));
        switch (part) {
            case "class metadata" -> assertEquals(classMetadata(reference), classMetadata(generated));
            case "method metadata" -> assertEquals(methodMetadata(reference), methodMetadata(generated));
            default -> assertEquals(descriptors(reference), descriptors(generated));
        }
    }

    /**
     * Compares abstract interface declarations with javac, including covariant generic redeclarations.
     *
     * @param reference The fixture javac compiled
     * @param inline    Whether the model writes type variables inline with their bounds rather than by name
     * @param part      The part of the class compared with javac's
     * @throws Exception If the generated class cannot be loaded
     */
    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("genericInterfaceCases")
    public void genericInterfaceSignaturesMatchJavac(Class<?> reference, boolean inline, String part) throws Exception {
        Class<?> generated = define(interfaceModel(reference, inline));
        switch (part) {
            case "class metadata" -> assertEquals(classMetadata(reference), classMetadata(generated));
            case "method metadata" -> assertEquals(methodMetadata(reference), methodMetadata(generated));
            default -> assertEquals(descriptors(reference), descriptors(generated));
        }
    }

    /**
     * Checks annotations at every nested type path, as well as ordinary generic metadata.
     *
     * @param reference The fixture javac compiled
     * @param inline    Whether the model writes type variables inline with their bounds rather than by name
     * @param part      The part of the class compared with javac's
     * @throws Exception If the generated class cannot be loaded
     */
    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("annotatedSignatureCases")
    public void annotatedGenericSignaturesMatchJavac(Class<?> reference, boolean inline, String part) throws Exception {
        Class<?> generated = define(model(reference, inline));
        switch (part) {
            case "class metadata" -> assertEquals(classMetadata(reference), classMetadata(generated));
            case "method metadata" -> assertEquals(methodMetadata(reference), methodMetadata(generated));
            case "class annotations" -> assertEquals(classAnnotations(reference), classAnnotations(generated));
            case "method annotations" -> assertEquals(methodAnnotations(reference), methodAnnotations(generated));
            default -> assertEquals(descriptors(reference), descriptors(generated));
        }
    }

    /**
     * Checks generic record components and their expanded fields, accessors and constructor.
     *
     * @param reference The fixture javac compiled
     * @param inline    Whether the model writes type variables inline with their bounds rather than by name
     * @param part      The part of the class compared with javac's
     * @throws Exception If the generated class cannot be loaded
     */
    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("genericRecordCases")
    public void genericRecordSignaturesMatchJavac(Class<?> reference, boolean inline, String part) throws Exception {
        Class<?> generated = define(recordModel(reference, inline));
        switch (part) {
            case "class metadata" -> assertEquals(classMetadata(reference), classMetadata(generated));
            case "method metadata" -> assertEquals(methodMetadata(reference), methodMetadata(generated));
            case "record metadata" -> assertEquals(recordMetadata(reference), recordMetadata(generated));
            default -> assertEquals(descriptors(reference), descriptors(generated));
        }
    }

    @Test
    public void genericSignaturesOfAnEnumMatchJavac() throws Exception {
        var definition = EnumDef.builder("test.hardening.EnumSupplier").addModifiers(Modifier.PUBLIC)
            .addEnumConstant("A")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("a").returning())).build();
        Class<?> generated = define(definition);
        assertEquals(classShape(EnumSupplier.class), classShape(generated));
        assertEquals(genericShape(EnumSupplier.class).stream().filter(method -> method.contains(" get(")).toList(),
            genericShape(generated).stream().filter(method -> method.contains(" get(")).toList());
    }

    @Test
    public void synchronizedMethodKeepsItsFlag() throws Exception {
        var definition = ClassDef.builder("test.hardening.SynchronizedMethod").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED)
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("v").returning())).build();
        assertEquals(shape(SynchronizedMethod.class), shape(define(definition)));
    }

    @Test
    public void volatileAndTransientFieldsKeepTheirFlags() throws Exception {
        var definition = ClassDef.builder("test.hardening.VolatileFields").addModifiers(Modifier.PUBLIC)
            .addField(io.micronaut.sourcegen.model.FieldDef.builder("flag").addModifiers(Modifier.PUBLIC, Modifier.VOLATILE)
                .ofType(TypeDef.Primitive.BOOLEAN).build())
            .addField(io.micronaut.sourcegen.model.FieldDef.builder("cache").addModifiers(Modifier.PUBLIC, Modifier.TRANSIENT)
                .ofType(TypeDef.OBJECT).build())
            .build();
        Class<?> generated = define(definition);
        assertEquals(java.lang.reflect.Modifier.toString(VolatileFields.class.getField("flag").getModifiers()),
            java.lang.reflect.Modifier.toString(generated.getField("flag").getModifiers()));
        assertEquals(java.lang.reflect.Modifier.toString(VolatileFields.class.getField("cache").getModifiers()),
            java.lang.reflect.Modifier.toString(generated.getField("cache").getModifiers()));
    }

    static Stream<Arguments> genericSignatureCases() {
        return signatureCases(Stream.of(Plain.class, Unbounded.class, ClassBound.class, MethodBound.class,
            Shadowed.class, CrossScope.class, MethodChain.class, ForwardBounds.class,
            UnusedVariable.class, UnusedConstructorVariable.class, GenericConstructor.class,
            GenericThrows.class, ThrowsOnly.class, MixedThrows.class, Intersection.class,
            ObjectIntersection.class, InterfaceIntersection.class, Recursive.class, Mutual.class,
            Wildcards.class, ArrayOverloads.class, GenericOverloads.class, Inheritance.class,
            SelfType.class, SpecializedSupplier.class, DescriptorCollision.class, RecursiveMethod.class,
            AnnotatedClassBound.class, AnnotatedMethodBound.class, AnnotatedInterfaces.class,
            ShadowedClassBoundChain.class, ShadowedExceptionBound.class, MethodBoundToClass.class,
            GenericBridgeThrows.class, ObjectFirstBridge.class, GenericArrayBridge.class, GenericBoundBridge.class,
            LongClassChain.class, LongMethodChain.class, NestedWildcards.class, MethodIntersection.class,
            InheritedSpecializedBridge.class, InheritedThrowsBridge.class, InheritedGenericThrowsBridge.class,
            SignatureStress.class),
            "class metadata", "method metadata", "erased descriptors");
    }

    static Stream<Arguments> genericInterfaceCases() {
        return signatureCases(Stream.of(GenericInterface.class, SpecializedInterface.class, InterfaceMethodShadow.class),
            "class metadata", "method metadata", "erased descriptors");
    }

    static Stream<Arguments> annotatedSignatureCases() {
        return signatureCases(Stream.of(AnnotatedSuperclass.class, AnnotatedSuperinterface.class, AnnotatedThrows.class,
            AnnotatedWildcardTypes.class, AnnotatedNestedBounds.class, AnnotatedGenericArrays.class,
            AnnotatedVariableUses.class, AnnotatedDependentBound.class, AnnotatedObjectWildcard.class,
            AnnotatedObjectIntersection.class, AnnotatedMethodThrowsOnly.class),
            "class metadata", "method metadata", "class annotations", "method annotations", "erased descriptors");
    }

    static Stream<Arguments> genericRecordCases() {
        return signatureCases(Stream.of(BoundedRecord.class, NestedRecord.class),
            "class metadata", "method metadata", "erased descriptors", "record metadata");
    }

    /**
     * Every fixture, written with named and with inline type variables, compared part by part.
     */
    private static Stream<Arguments> signatureCases(Stream<Class<?>> references, String... parts) {
        return references.flatMap(reference -> Stream.of(false, true).flatMap(inline -> Stream.of(parts).map(part ->
            Arguments.of(Named.of(reference.getSimpleName(), reference), Named.of(inline ? "inline" : "named", inline), part))));
    }

    private static String classAnnotations(Class<?> type) {
        return annotated(type.getAnnotatedSuperclass(), type) + " / "
            + Arrays.stream(type.getAnnotatedInterfaces()).map(parent -> annotated(parent, type)).toList();
    }

    private static List<String> methodAnnotations(Class<?> type) {
        return executables(type).map(executable -> name(executable)
            + renderAll(executable.getParameterTypes(), type) + " / "
            + (executable instanceof Method method ? annotated(method.getAnnotatedReturnType(), type) : "constructor")
            + " / " + Arrays.stream(executable.getAnnotatedParameterTypes()).map(parameter -> annotated(parameter, type)).toList()
            + " throws " + Arrays.stream(executable.getAnnotatedExceptionTypes()).map(thrown -> annotated(thrown, type)).toList())
            .sorted().toList();
    }

    private static String annotated(AnnotatedType type, Class<?> self) {
        if (type == null) {
            return "null";
        }
        String annotations = Arrays.stream(type.getAnnotations()).map(annotation -> annotation.annotationType().getName()).sorted().toList().toString();
        String children = switch (type) {
            case AnnotatedArrayType array -> " component=" + annotated(array.getAnnotatedGenericComponentType(), self);
            case AnnotatedParameterizedType parameterized -> " arguments=" + Arrays.stream(parameterized.getAnnotatedActualTypeArguments())
                .map(argument -> annotated(argument, self)).toList();
            case AnnotatedWildcardType wildcard -> " upper=" + Arrays.stream(wildcard.getAnnotatedUpperBounds())
                .map(bound -> annotated(bound, self)).toList() + " lower=" + Arrays.stream(wildcard.getAnnotatedLowerBounds())
                .map(bound -> annotated(bound, self)).toList();
            default -> "";
        };
        return annotations + render(type.getType(), self) + children;
    }

    private static RecordDef recordModel(Class<?> reference, boolean inline) {
        String name = "test.signature." + reference.getSimpleName();
        var mapper = new ModelTypes(reference, name, inline);
        var builder = RecordDef.builder(name).addModifiers(Modifier.PUBLIC);
        for (TypeVariable<?> variable : reference.getTypeParameters()) {
            builder.addTypeVariable(mapper.declaration(variable));
        }
        for (var component : reference.getRecordComponents()) {
            builder.addProperty(PropertyDef.builder(component.getName()).ofType(mapper.annotated(component.getAnnotatedType(), new HashSet<>())).build());
        }
        return builder.build();
    }

    private static List<String> recordMetadata(Class<?> type) {
        return Stream.concat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName() + ":"
                + annotated(component.getAnnotatedType(), type)),
            Arrays.stream(type.getDeclaredFields()).map(field -> field.getName() + ":" + annotated(field.getAnnotatedType(), type)))
            .sorted().toList();
    }

    private static InterfaceDef interfaceModel(Class<?> reference, boolean inline) {
        String name = "test.signature." + reference.getSimpleName();
        var mapper = new ModelTypes(reference, name, inline);
        var builder = InterfaceDef.builder(name).addModifiers(Modifier.PUBLIC);
        for (TypeVariable<?> variable : reference.getTypeParameters()) {
            builder.addTypeVariable(mapper.declaration(variable));
        }
        for (AnnotatedType parent : reference.getAnnotatedInterfaces()) {
            builder.addSuperinterface(mapper.annotated(parent, new HashSet<>()));
        }
        for (Method method : reference.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            var declaration = MethodDef.builder(method.getName()).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
            mapper.executable(declaration, method);
            declaration.returns(mapper.annotated(method.getAnnotatedReturnType(), new HashSet<>()));
            builder.addMethod(declaration.build());
        }
        return builder.build();
    }

    private static ClassDef model(Class<?> reference, boolean inline) {
        String name = "test.signature." + reference.getSimpleName();
        var mapper = new ModelTypes(reference, name, inline);
        var builder = ClassDef.builder(name).addModifiers(Modifier.PUBLIC);
        for (TypeVariable<?> variable : reference.getTypeParameters()) {
            builder.addTypeVariable(mapper.declaration(variable));
        }
        builder.superclass((ClassTypeDef) mapper.annotated(reference.getAnnotatedSuperclass(), new HashSet<>()));
        for (AnnotatedType parent : reference.getAnnotatedInterfaces()) {
            builder.addSuperinterface(mapper.annotated(parent, new HashSet<>()));
        }
        for (Method method : reference.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            var declaration = MethodDef.builder(method.getName()).addModifiers(Modifier.PUBLIC);
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                declaration.addModifiers(Modifier.STATIC);
            }
            mapper.executable(declaration, method);
            declaration.returns(mapper.annotated(method.getAnnotatedReturnType(), new HashSet<>()));
            builder.addMethod(declaration.build((self, parameters) -> {
                if (method.getReturnType() == void.class) {
                    return new StatementDef.Return(null);
                }
                return (method.getReturnType().isPrimitive() ? ExpressionDef.constant(0) : ExpressionDef.nullValue()).returning();
            }));
        }
        for (Constructor<?> constructor : reference.getDeclaredConstructors()) {
            var declaration = MethodDef.constructor().addModifiers(Modifier.PUBLIC);
            mapper.executable(declaration, constructor);
            builder.addMethod(declaration.build((self, parameters) -> self.superRef().invokeSuperConstructor()));
        }
        return builder.build();
    }

    private static String classMetadata(Class<?> type) {
        return variables(type.getTypeParameters(), type) + " extends " + render(type.getGenericSuperclass(), type)
            + " implements " + renderAll(type.getGenericInterfaces(), type);
    }

    private static List<String> methodMetadata(Class<?> type) {
        return executables(type).map(executable -> {
            String result = executable instanceof Method method ? render(method.getGenericReturnType(), type) : "void";
            return name(executable) + variables(executable.getTypeParameters(), type)
                + renderAll(executable.getGenericParameterTypes(), type) + " -> " + result
                + " throws " + renderAll(executable.getGenericExceptionTypes(), type)
                + (executable.isSynthetic() ? " synthetic" : "")
                + (executable instanceof Method method && method.isBridge() ? " bridge" : "");
        }).sorted().toList();
    }

    private static List<String> descriptors(Class<?> type) {
        return executables(type).map(executable -> name(executable)
            + renderAll(executable.getParameterTypes(), type) + " -> "
            + (executable instanceof Method method ? render(method.getReturnType(), type) : "void")
            + " throws " + renderAll(executable.getExceptionTypes(), type)).sorted().toList();
    }

    private static Stream<Executable> executables(Class<?> type) {
        return Stream.concat(Arrays.stream(type.getDeclaredConstructors()), Arrays.stream(type.getDeclaredMethods()));
    }

    private static String name(Executable executable) {
        return executable instanceof Constructor<?> ? "<init>" : executable.getName();
    }

    private static String variables(TypeVariable<?>[] variables, Class<?> self) {
        return Arrays.stream(variables).map(variable -> variable.getName() + " extends "
            + Arrays.stream(variable.getAnnotatedBounds()).map(bound -> annotated(bound, self)).toList()).toList().toString();
    }

    private static String renderAll(Type[] types, Class<?> self) {
        return Arrays.stream(types).map(type -> render(type, self)).toList().toString();
    }

    private static String render(Type type, Class<?> self) {
        if (type == null) {
            return "null";
        }
        if (type instanceof Class<?> clazz) {
            return clazz.isArray() ? render(clazz.getComponentType(), self) + "[]" : clazz == self ? "SELF" : clazz.getName();
        }
        if (type instanceof TypeVariable<?> variable) {
            String scope = variable.getGenericDeclaration() instanceof Class<?> ? "class" : "method";
            return scope + ":" + variable.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            // Static nested fixtures have an enclosing test class that is not part of their
            // modeled generic signature. Other owners, such as Map.Entry's Map, remain checked.
            String owner = parameterized.getOwnerType() == null || parameterized.getRawType() == self
                ? "" : render(parameterized.getOwnerType(), self) + ".";
            return owner + render(parameterized.getRawType(), self) + renderAll(parameterized.getActualTypeArguments(), self);
        }
        if (type instanceof GenericArrayType array) {
            return render(array.getGenericComponentType(), self) + "[]";
        }
        if (type instanceof WildcardType wildcard) {
            return "? upper=" + renderAll(wildcard.getUpperBounds(), self) + " lower=" + renderAll(wildcard.getLowerBounds(), self);
        }
        throw new IllegalArgumentException("Unexpected reflection type " + type);
    }

    private record ModelTypes(Class<?> reference, String name, boolean inline) {
        private TypeDef.TypeVariable declaration(TypeVariable<?> variable) {
            Set<TypeVariable<?>> visiting = new HashSet<>();
            visiting.add(variable);
            return TypeDef.variable(variable.getName(), Arrays.stream(variable.getAnnotatedBounds())
                .map(bound -> annotated(bound, visiting)).toList());
        }

        private TypeDef annotated(AnnotatedType annotated, Set<TypeVariable<?>> visiting) {
            TypeDef type = switch (annotated) {
                case AnnotatedArrayType array -> annotated(array.getAnnotatedGenericComponentType(), visiting).array();
                case AnnotatedParameterizedType parameterized -> TypeDef.parameterized(
                    (ClassTypeDef) type(((ParameterizedType) parameterized.getType()).getRawType(), visiting),
                    Arrays.stream(parameterized.getAnnotatedActualTypeArguments()).map(argument -> annotated(argument, visiting)).toList());
                case AnnotatedWildcardType wildcard -> wildcard.getAnnotatedLowerBounds().length > 0
                    ? TypeDef.wildcardSupertypeOf(annotated(wildcard.getAnnotatedLowerBounds()[0], visiting))
                    : TypeDef.wildcardSubtypeOf(annotated(wildcard.getAnnotatedUpperBounds()[0], visiting));
                default -> type(annotated.getType(), visiting);
            };
            if (annotated.getAnnotations().length != 0) {
                type = type.annotated(Arrays.stream(annotated.getAnnotations())
                    .map(annotation -> AnnotationDef.builder(annotation.annotationType()).build()).toList());
            }
            return type;
        }

        private void executable(MethodDef.MethodDefBuilder builder, Executable executable) {
            for (TypeVariable<?> variable : executable.getTypeParameters()) {
                builder.addTypeVariable(declaration(variable));
            }
            AnnotatedType[] parameters = executable.getAnnotatedParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                builder.addParameter("p" + i, annotated(parameters[i], new HashSet<>()));
            }
            builder.addThrows(Arrays.stream(executable.getAnnotatedExceptionTypes())
                .map(exception -> annotated(exception, new HashSet<>())).toList());
        }

        private TypeDef type(Type type, Set<TypeVariable<?>> visiting) {
            if (type instanceof Class<?> clazz) {
                if (clazz.isArray()) {
                    return type(clazz.getComponentType(), visiting).array();
                }
                return clazz == reference ? ClassTypeDef.of(name) : TypeDef.of(clazz);
            }
            if (type instanceof TypeVariable<?> variable) {
                if (!inline || !visiting.add(variable)) {
                    return TypeDef.variable(variable.getName());
                }
                TypeDef converted = TypeDef.variable(variable.getName(), Arrays.stream(variable.getBounds())
                    .map(bound -> type(bound, visiting)).toList());
                visiting.remove(variable);
                return converted;
            }
            if (type instanceof ParameterizedType parameterized) {
                return TypeDef.parameterized((ClassTypeDef) type(parameterized.getRawType(), visiting),
                    Arrays.stream(parameterized.getActualTypeArguments()).map(argument -> type(argument, visiting)).toList());
            }
            if (type instanceof GenericArrayType array) {
                return type(array.getGenericComponentType(), visiting).array();
            }
            if (type instanceof WildcardType wildcard) {
                return wildcard.getLowerBounds().length > 0
                    ? TypeDef.wildcardSupertypeOf(type(wildcard.getLowerBounds()[0], visiting))
                    : TypeDef.wildcardSubtypeOf(type(wildcard.getUpperBounds()[0], visiting));
            }
            throw new IllegalArgumentException("Unexpected fixture type " + type);
        }
    }

    // These ordinary Java declarations are compiled by javac, independently of either bytecode
    // writer. Bodies intentionally do nothing: this suite checks the complete declaration shape.
    /**
     * Plain signature fixture.
     *
     * @since 2.3
     */
    public static class Plain {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param count The input value
         * @return The fixture result
         * @since 2.3
         */
        public String value(int count) {
            return null;
        }
    }

    /**
     * Unbounded class and method variables.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class Unbounded<T> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <U> The generic type
         * @param first The input value
         * @param second The input value
         * @return The fixture result
         * @since 2.3
         */
        public <U> Map<T, U> value(T first, U second) {
            return null;
        }
    }

    /**
     * Name-only bounded class references and arrays.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class ClassBound<T extends Number> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T[][] array(T[][] input) {
            return null;
        }
    }

    /**
     * Bounded method references.
     *
     * @since 2.3
     */
    public static class MethodBound {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <U> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <U extends Number> U value(U input) {
            return null;
        }
    }

    /**
     * Method variables shadow their class namesakes.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    @SuppressWarnings("TypeParameterShadowing")
    public static class Shadowed<T extends CharSequence> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <T extends Number> T value(T input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T other(T input) {
            return null;
        }
    }

    /**
     * Method bounds reference class variables.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class CrossScope<T extends Number> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <U> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <U extends T> U value(U input) {
            return null;
        }
    }

    /**
     * Method variables reference other method variables.
     *
     * @since 2.3
     */
    public static class MethodChain {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param <U> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <T extends Number, U extends T> U[][] value(U[][] input) {
            return null;
        }
    }

    /**
     * A bound can refer to a later declaration.
     *
     * @param <A> The generic type
     * @param <B> The generic type
     * @since 2.3
     */
    public static class ForwardBounds<A extends B, B extends Number> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public A value(A input) {
            return null;
        }
    }

    /**
     * A method variable need not appear in its parameters or result.
     *
     * @since 2.3
     */
    public static class UnusedVariable {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @return The fixture result
         * @since 2.3
         */
        public <T extends Number> String value() {
            return null;
        }
    }

    /**
     * A constructor variable need not appear in its parameters.
     *
     * @since 2.3
     */
    public static class UnusedConstructorVariable {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @since 2.3
         */
        public <T extends Number> UnusedConstructorVariable() {
        }
    }

    /**
     * Generic constructors have method-scoped bounds.
     *
     * @since 2.3
     */
    public static class GenericConstructor {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param input The input value
         * @since 2.3
         */
        public <T extends Number> GenericConstructor(T input) {
        }
    }

    /**
     * Method-scoped generic exception.
     *
     * @since 2.3
     */
    public static class GenericThrows {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <E> The generic type
         * @param input The input value
         * @throws E The declared exception
         * @since 2.3
         */
        public <E extends Exception> void value(E input) throws E {
        }
    }

    /**
     * Class-scoped generic exception used only in throws.
     *
     * @param <E> The generic type
     * @since 2.3
     */
    public static class ThrowsOnly<E extends Exception> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @throws E The declared exception
         * @since 2.3
         */
        public void value() throws E {
        }
    }

    /**
     * Ordinary and generic exceptions coexist in a method signature.
     *
     * @since 2.3
     */
    public static class MixedThrows {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <E> The generic type
         * @param input The input value
         * @return The fixture result
         * @throws IOException The declared exception
         * @throws E The declared exception
         * @since 2.3
         */
        public <E extends Exception> String value(List<String> input) throws IOException, E {
            return null;
        }
    }

    /**
     * Intersection with a class as its first bound.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class Intersection<T extends Number & Runnable> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * An explicit Object bound determines erasure.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    @SuppressWarnings("ExtendsObject")
    public static class ObjectIntersection<T extends Object & Runnable> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * Interface-only intersection bounds.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class InterfaceIntersection<T extends CharSequence & Serializable> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * A recursively bounded variable with a lower wildcard.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class Recursive<T extends Comparable<? super T>> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * Mutually recursive bounds terminate at parameterized erasure.
     *
     * @param <A> The generic type
     * @param <B> The generic type
     * @since 2.3
     */
    public static class Mutual<A extends Comparable<B>, B extends Comparable<A>> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public A value(B input) {
            return null;
        }
    }

    /**
     * Nested wildcards, member types and generic arrays.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class Wildcards<T> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param first The input value
         * @param second The input value
         * @return The fixture result
         * @since 2.3
         */
        public Map.Entry<String, List<? super T[]>> value(List<? extends T> first, List<?> second) {
            return null;
        }
    }

    /**
     * Array rank distinguishes generic overloads.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class ArrayOverloads<T> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T[] value(T[] input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T[][] value(T[][] input) {
            return null;
        }
    }

    /**
     * Different bounds distinguish class-variable and method-variable overloads.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class GenericOverloads<T extends CharSequence> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <U> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <U extends Number> U value(U input) {
            return null;
        }
    }

    /**
     * Parameterized superclass and superinterface signatures.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class Inheritance<T extends Number> extends ArrayList<T> implements Supplier<List<? extends T>> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @return The fixture result
         * @since 2.3
         */
        public List<? extends T> get() {
            return null;
        }
    }

    /**
     * Self references and covariant bridge metadata.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class SelfType<T> implements Comparable<SelfType<T>> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public int compareTo(SelfType<T> input) {
            return 0;
        }
    }

    /**
     * A bridge has an erased signature and no method variables.
     *
     * @since 2.3
     */
    public static class SpecializedSupplier implements Supplier<String> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @return The fixture result
         * @since 2.3
         */
        public String get() {
            return null;
        }
    }

    /**
     * Wrong erasure must not collapse two legal overloads into one descriptor.
     *
     * @since 2.3
     */
    public static class DescriptorCollision {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public Object value(Object input) {
            return null;
        }

        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <T extends Number> Object value(T input) {
            return null;
        }
    }

    /**
     * Recursive method bounds erase to their raw interface.
     *
     * @since 2.3
     */
    public static class RecursiveMethod {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public static <T extends Comparable<? super T>> T[] value(T[] input) {
            return null;
        }
    }

    /**
     * Annotated interface bounds keep their distinct bound indexes.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class AnnotatedInterfaces<T extends @Marker CharSequence & @Marker Serializable> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * Annotation on a class variable's bound.
     *
     * @param <T> The generic type
     * @since 2.3
     */
    public static class AnnotatedClassBound<T extends @Marker Number> {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public T value(T input) {
            return null;
        }
    }

    /**
     * Annotation on a method variable's bound.
     *
     * @since 2.3
     */
    public static class AnnotatedMethodBound {
        /**
         * Supplies the javac declaration used by the signature comparison.
         *
         * @param <T> The generic type
         * @param input The input value
         * @return The fixture result
         * @since 2.3
         */
        public <T extends @Marker Number> T value(T input) {
            return null;
        }
    }

    /** Class bounds retain their declaration scope when a method shadows a bound's name.
     * @param <A> The dependent variable
     * @param <B> The numeric bound
     * @since 2.3
     */
    public static class ShadowedClassBoundChain<A extends B, B extends Number> {
        /**
         * @param <B> The unrelated method variable
         * @param value The class variable
         * @return The input
         */
        public <B extends CharSequence> A value(A value) {
            return value;
        }
    }

    /** Exception erasure retains the class declaration scope.
     * @param <E> The exception bound
     * @param <T> The dependent exception
     * @since 2.3
     */
    public static class ShadowedExceptionBound<E extends Exception, T extends E> {
        /**
         * @param <E> The unrelated method variable
         * @throws T The class exception
         */
        public <E extends RuntimeException> void value() throws T {
        }
    }

    /** A method variable may use a class variable as its bound.
     * @param <A> The numeric class variable
     * @since 2.3
     */
    public static class MethodBoundToClass<A extends Number> {
        /**
         * @param <B> The method variable
         * @param value The input
         * @return The input
         */
        public <B extends A> B value(B value) {
            return value;
        }
    }

    /** Contract whose bridge must retain the erased method exception.
     * @param <T> The result
     * @since 2.3
     */
    public interface ThrowingContract<T> {
        /** @param <E> The thrown type
         * @return The result
         * @throws E The declared exception
         */
        <E extends Exception> T value() throws E;
    }

    /** A covariant generic method with a method exception variable.
     * @since 2.3
     */
    public static class GenericBridgeThrows implements ThrowingContract<String> {
        @Override
        public <E extends Exception> String value() throws E {
            return null;
        }
    }

    /** Explicit Object remains the leftmost erasure.
     * @param <T> The runnable
     * @since 2.3
     */
    public interface ObjectFirstContract<T extends Object & Runnable> {
        T value(T input);
    }

    /** Bridge descriptors retain the explicit Object bound.
     * @since 2.3
     */
    public static class ObjectFirstBridge implements ObjectFirstContract<Runnable> {
        @Override
        public Runnable value(Runnable input) {
            return input;
        }
    }

    /** Method variables belong to the inherited method.
     * @param <R> The result
     * @since 2.3
     */
    public interface MethodArrayContract<R> {
        <T extends Number> R value(T[] input);
    }

    /** Generic array bridge parameters.
     * @since 2.3
     */
    public static class GenericArrayBridge implements MethodArrayContract<String> {
        @Override
        public <T extends Number> String value(T[] input) {
            return null;
        }
    }

    /** Scalar method variables belong to the inherited method.
     * @param <R> The result
     * @since 2.3
     */
    public interface MethodBoundContract<R> {
        <T extends Number> R value(T input);
    }

    /** Generic scalar bridge parameters.
     * @since 2.3
     */
    public static class GenericBoundBridge implements MethodBoundContract<String> {
        @Override
        public <T extends Number> String value(T input) {
            return null;
        }
    }

    /** Long class bound chains.
     * @param <A> The dependent bound
     * @param <B> The dependent bound
     * @param <C> The dependent bound
     * @param <D> The dependent bound
     * @param <E> The dependent bound
     * @param <F> The dependent bound
     * @param <G> The dependent bound
     * @param <H> The dependent bound
     * @param <I> The dependent bound
     * @param <J> The dependent bound
     * @param <K> The dependent bound
     * @param <L> The dependent bound
     * @since 2.3
     */
    public static class LongClassChain<A extends B, B extends C, C extends D, D extends E, E extends F, F extends G,
        G extends H, H extends I, I extends J, J extends K, K extends L, L extends Number> {
        public A[][] value(A[][] input) {
            return input;
        }
    }

    /** Long method bound chains.
     * @since 2.3
     */
    public static class LongMethodChain {
        public <A extends B, B extends C, C extends D, D extends E, E extends F, F extends G,
            G extends H, H extends I, I extends J, J extends K, K extends L, L extends Number> A[][] value(A[][] input) {
                return input;
            }
    }

    /** Deeply nested wildcards and arrays.
     * @param <T> The bound
     * @since 2.3
     */
    public static class NestedWildcards<T extends Number> {
        public <U extends T> Map<? super U, List<? extends U[][]>> value(List<? super Map<String, ? extends U[]>> input) {
            return null;
        }
    }

    /** Intersection bounds of a generic method.
     * @since 2.3
     */
    public static class MethodIntersection {
        public <T extends Object & Runnable & Serializable, U extends T> U[] value(U[] input) {
            return input;
        }
    }

    /** An interface with dependent variables and generic exceptions.
     * @param <T> The value
     * @since 2.3
     */
    public interface GenericInterface<T extends Number & Serializable> {
        <U extends T, E extends Exception> List<? super U[]> value(U[][] values) throws E;
    }

    /** A covariant redeclaration requires a concrete bridge.
     * @since 2.3
     */
    public interface SpecializedInterface extends Supplier<String> {
        @Override
        String get();
    }

    /** An interface method can shadow a class bound's name.
     * @param <A> The dependent bound
     * @param <B> The numeric bound
     * @since 2.3
     */
    public interface InterfaceMethodShadow<A extends B, B extends Number> {
        <B extends CharSequence> A value(A input);
    }

    /**
     * Annotated generic superclass.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedSuperclass<T> extends ArrayList<@Marker T> {
    }

    /**
     * Annotated generic superinterface.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedSuperinterface<T> implements @Marker Supplier<@Marker T> {
        @Override
        public T get() {
            return null;
        }
    }

    /**
     * Annotated generic and concrete throws.
     * @since 2.3
     */
    public static class AnnotatedThrows {
        public <E extends Exception> void value() throws @Marker E, @Marker IOException { }
    }

    /**
     * Annotations on both wildcard directions.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedWildcardTypes<T> {
        public List<@Marker ? extends @Marker T> value(List<@Marker ? super @Marker T> value) {
            return null;
        }
    }

    /**
     * Nested type argument in a bound.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedNestedBounds<T extends Comparable<List<? extends @Marker T>>> {
        public <U extends Comparable<List<? super @Marker U>>> U value(U value) {
            return value;
        }
    }

    /**
     * Annotations at multiple array levels.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedGenericArrays<T extends Number> {
        public @Marker T @Marker [] @Marker [] value(List<@Marker T @Marker []> @Marker [] value) {
            return null;
        }
    }

    /**
     * Annotations on class and method variable uses.
     * @param <T> Element
     * @since 2.3
     */
    public static class AnnotatedVariableUses<T> {
        public <U extends T> @Marker U value(@Marker T first, @Marker U second) {
            return second;
        }
    }

    /**
     * Annotated type-variable bound occupies the class-bound slot.
     * @param <T> First
     * @param <U> Bound
     * @since 2.3
     */
    public static class AnnotatedDependentBound<T extends @Marker U, U extends Number> {
        public <A extends @Marker B, B extends T> A value(A value) {
            return value;
        }
    }

    /**
     * Record with dependent bounded components.
     * @param <T> Number
     * @param <U> Subtype
     * @param value Value
     * @param values Array
     * @since 2.3
     */
    public record BoundedRecord<T extends Number, U extends T>(T value, U[] values) { }

    /**
     * Record with recursive bounds and nested arrays.
     * @param <T> Element
     * @param values List
     * @param matrix Matrix
     * @since 2.3
     */
    public record NestedRecord<T extends Comparable<? super T>>(List<? extends T[]> values, @Marker T @Marker [] @Marker [] matrix) { }

    /** Explicit Object wildcard bounds retain their type annotations.
     * @since 2.3
     */
    public static class AnnotatedObjectWildcard {
        public List<? extends @Marker Object> value(List<? extends @Marker Object> input) {
            return input;
        }
    }

    /** Explicit annotated Object intersection bounds remain class bounds.
     * @param <T> Intersection variable
     * @since 2.3
     */
    public static class AnnotatedObjectIntersection<T extends @Marker Object & @Marker Runnable> {
        public T value(T input) {
            return input;
        }
    }

    /** An annotated generic exception without other generic uses.
     * @since 2.3
     */
    public static class AnnotatedMethodThrowsOnly {
        public <E extends @Marker Exception> void value() throws @Marker E { }
    }

    /** Generic throwing contract for inherited bridge checks.
     * @param <T> Result
     * @since 2.3
     */
    public interface InheritedThrowingContract<T> {
        T value() throws IOException;
    }

    /** Inherited concrete implementation with a checked exception.
     * @since 2.3
     */
    public static class InheritedThrowingParent {
        public String value() throws IOException {
            return "value";
        }
    }

    /** An inherited bridge retains checked exception metadata.
     * @since 2.3
     */
    public static class InheritedThrowsBridge extends InheritedThrowingParent implements InheritedThrowingContract<String> { }

    /** Generic throwing contract for inherited method-scoped exception checks.
     * @param <T> Result
     * @since 2.3
     */
    public interface InheritedGenericThrowingContract<T> {
        <E extends Exception> T value() throws E;
    }

    /** Inherited method-scoped checked exception.
     * @since 2.3
     */
    public static class InheritedGenericThrowingParent {
        public <E extends Exception> String value() throws E {
            return "value";
        }
    }

    /** A bridge inherited from a method declaring its own exception variable.
     * @since 2.3
     */
    public static class InheritedGenericThrowsBridge extends InheritedGenericThrowingParent implements InheritedGenericThrowingContract<String> { }

    /**
     * Combines dependent class bounds, nested wildcard arrays, method bounds and a generic exception.
     *
     * @param <A> The numeric class variable
     * @param <B> The dependent class variable
     * @since 2.3
     */
    public static class SignatureStress<A extends Number & Serializable, B extends A>
        extends java.util.HashMap<List<? super A[]>, Map<String, B>> {
        /**
         * @param <C> The dependent method variable
         * @param <D> The recursively constrained method variable
         * @param <E> The thrown method variable
         * @param input The input values
         * @return The generic result
         * @throws E The generic exception
         * @since 2.3
         */
        public <C extends B, D extends Comparable<? super C> & Serializable, E extends Exception>
        Map.Entry<A, D[]> value(List<? super C[]> input) throws E {
            return null;
        }
    }

    /** javac's synchronized method.
     * @since 2.3
     */
    public static class SynchronizedMethod {
        public synchronized String get() {
            return "v";
        }
    }

    /** javac's volatile and transient fields, public for reflection to read them.
     * @since 2.3
     */
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static class VolatileFields {
        public volatile boolean flag;
        public transient Object cache;
    }
}
