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

import io.micronaut.sourcegen.bytecode.tck.EnclosingTypeFixtures.Calls;
import io.micronaut.sourcegen.bytecode.tck.EnclosingTypeFixtures.Outer;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.Modifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Member types: references to a member type by its binary name, enclosing type arguments of inner classes,
 * and the signatures and overloads of their members.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({"unchecked", "rawtypes", "varargs", "TypeParameterUnusedInFormals"})
public abstract class MemberTypeTck extends AbstractByteCodeWriterTck {

    @Test
    public void writesReferencesToAMemberTypeByItsBinaryName() throws Exception {
        // A member type is named when it is built, before it knows what it will be declared in, and the
        // model is immutable - so the definition the caller holds on to keeps the simple name while the
        // copy `addInnerType` stores carries the qualified one. A reference taken from the caller's
        // definition therefore reads as `Inner`, which Java source resolves through scoping and a class
        // file cannot: every name it carries is a binary name.
        ClassDef inner = ClassDef.builder("Inner")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("name")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> ExpressionDef.constant("inner").returning()))
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassTypeDef.of("Inner"))
                .build((ignored, parameters) -> ExpressionDef.nullValue().returning()))
            .build();
        ClassDef outer = ClassDef.builder("example.TckMemberTypeOuter")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(inner)
            .addMethod(MethodDef.builder("make")
                .addModifiers(Modifier.PUBLIC)
                .returns(inner.asTypeDef())
                .build((ignored, parameters) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("all")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.parameterized(ClassTypeDef.of(List.class), inner.asTypeDef()))
                .build((ignored, parameters) -> ExpressionDef.nullValue().returning()))
            .build();

        ObjectDef member = outer.getInnerTypes().get(0);
        assertEquals("example.TckMemberTypeOuter$Inner", member.getName());

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
            outer.getName(), write(outer),
            member.getName(), write(member)
        ));
        Class<?> outerClass = loader.loadClass(outer.getName());
        Class<?> memberClass = loader.loadClass(member.getName());

        assertSame(memberClass, outerClass.getMethod("make").getReturnType());
        assertEquals("java.util.List<example.TckMemberTypeOuter$Inner>",
            outerClass.getMethod("all").getGenericReturnType().toString());
        // The member type resolves a reference to itself the same way, without seeing its enclosing type
        assertSame(memberClass, memberClass.getMethod("self").getReturnType());
        assertEquals("inner", memberClass.getMethod("name").invoke(memberClass.getConstructor().newInstance()));
        assertNull(outerClass.getMethod("make").invoke(outerClass.getConstructor().newInstance()));
    }

    /**
     * Reflection-to-model conversion must retain the generic enclosing type of a member class, for a
     * parameterized member, a plain member and an array of members.
     *
     * @param name The fixture method whose return type is modelled
     * @throws Exception If the generated program cannot be loaded
     */
    @ParameterizedTest(name = "the return type of {0}() keeps its owner's type arguments")
    @ValueSource(strings = {"member", "plainMember", "memberArray"})
    public void memberClassMethodSignaturesRetainOwnerTypeArguments(String name) throws Exception {
        var expected = MemberSignatures.class.getMethod(name).getGenericReturnType();
        var method = MethodDef.builder(name).addModifiers(Modifier.PUBLIC)
            .returns(TypeHierarchy.typeDefOf(expected)).build((self, p) -> ExpressionDef.nullValue().returning());
        var generated = define(ClassDef.builder("test.completeness.MemberSignatures").addModifiers(Modifier.PUBLIC).addMethod(method).build());
        assertEquals(expected.getTypeName(), generated.getMethod(name).getGenericReturnType().getTypeName());
    }

    @Test
    public void enclosingTypeArgumentConstrainsMemberOverload() throws Exception {
        Outer<Integer>.Inner<String> receiver = new Outer<Integer>().new Inner<>();
        assertEquals("object", receiver.choose(Double.valueOf(1)));
        assertCall(receiver.choose(Double.valueOf(1)),
            List.of(fixtureType("integerOwner"), TypeDef.of(Double.class)), List.of(receiver, 1.0),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void enclosingTypeArgumentConstrainsMethodVariableBound() throws Exception {
        Outer<Integer>.Inner<String> receiver = new Outer<Integer>().new Inner<>();
        assertEquals("object", receiver.bounded(Double.valueOf(1)));
        assertCall(receiver.bounded(Double.valueOf(1)),
            List.of(fixtureType("integerOwner"), TypeDef.of(Double.class)), List.of(receiver, 1.0),
            p -> p.getFirst().invoke("bounded", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void enclosingTypeArgumentsRemainInvariant() throws Exception {
        Outer<Long>.Inner<String> value = new Outer<Long>().new Inner<>();
        assertEquals("object", Calls.owner(value));
        assertCall(Calls.owner(value), List.of(fixtureType("longOwner")), List.of(value),
            p -> ClassTypeDef.of(Calls.class).invokeStatic("owner", TypeDef.STRING, p));
    }

    @Test
    public void compatibleEnclosingTypeArgumentsSelectMemberOverload() throws Exception {
        Outer<Integer>.Inner<String> value = new Outer<Integer>().new Inner<>();
        assertCall(Calls.owner(value), List.of(fixtureType("integerOwner")), List.of(value),
            p -> ClassTypeDef.of(Calls.class).invokeStatic("owner", TypeDef.STRING, p));
    }

    @Test
    public void enclosingTypeArgumentConstrainsNonGenericMember() throws Exception {
        Outer<Integer>.Plain receiver = new Outer<Integer>().new Plain();
        assertEquals("object", receiver.choose(Double.valueOf(1)));
        assertCall(receiver.choose(Double.valueOf(1)),
            List.of(fixtureType("plainOwner"), TypeDef.of(Double.class)), List.of(receiver, 1.0),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void enclosingTypeArgumentIsSubstitutedInInheritedMethod() throws Exception {
        Outer<Integer>.Inherited receiver = new Outer<Integer>().new Inherited();
        assertEquals("object", receiver.choose(Double.valueOf(1)));
        assertCall(receiver.choose(Double.valueOf(1)),
            List.of(fixtureType("inheritedOwner"), TypeDef.of(Double.class)), List.of(receiver, 1.0),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void innerDeclarationShadowsOuterTypeVariable() throws Exception {
        Outer<Integer>.Shadow<String> receiver = new Outer<Integer>().new Shadow<>();
        assertCall(receiver.choose("hello"), List.of(fixtureType("shadowOwner"), TypeDef.STRING),
            List.of(receiver, "hello"), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    /**
     * The generic type of a member of a parameterized owner reads back as javac writes it, as a field type and as
     * a method's parameter and return type.
     *
     * @param name The fixture field whose type is modelled
     * @param kind Where the type is used: a field or a method
     * @throws Exception If the generated program cannot be loaded
     */
    @ParameterizedTest(name = "{0} as a {1} type matches javac")
    @MethodSource("memberSignatureCases")
    public void memberSignaturesMatchJavac(String name, String kind) throws Exception {
        var reference = Signatures.class.getField(name);
        TypeDef type = TypeHierarchy.typeDefOf(reference.getGenericType());
        var builder = ClassDef.builder("test.owner.Signature").addModifiers(Modifier.PUBLIC);
        if (kind.equals("field")) {
            builder.addField(FieldDef.builder("value", type).addModifiers(Modifier.PUBLIC).build());
            assertEquals(reference.getGenericType().getTypeName(),
                define(builder.build()).getField("value").getGenericType().getTypeName());
        } else {
            builder.addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC)
                .addParameter("value", type).returns(type).build((self, p) -> p.getFirst().returning()));
            Method actual = Arrays.stream(define(builder.build()).getDeclaredMethods()).filter(m -> m.getName().equals("value")).findFirst().orElseThrow();
            assertEquals(reference.getGenericType().getTypeName(), actual.getGenericReturnType().getTypeName());
            assertEquals(reference.getGenericType().getTypeName(), actual.getGenericParameterTypes()[0].getTypeName());
        }
    }

    /**
     * The type annotations of a member type and of its owner's type arguments keep their type paths, as a field
     * type, a return type and a parameter type.
     *
     * @param name The fixture field whose annotated type is modelled
     * @param kind Where the type is used: a field, a return type or a parameter
     * @throws Exception If the generated program cannot be loaded
     */
    @ParameterizedTest(name = "the annotations of {0} as a {1} type match javac")
    @MethodSource("memberTypeAnnotationCases")
    public void memberTypeAnnotationPathsMatchJavac(String name, String kind) throws Exception {
        AnnotatedType reference = Signatures.class.getField(name).getAnnotatedType();
        TypeDef type = annotatedModel(reference);
        var builder = ClassDef.builder("test.owner.Annotations").addModifiers(Modifier.PUBLIC);
        AnnotatedType actual;
        if (kind.equals("field")) {
            builder.addField(FieldDef.builder("value", type).addModifiers(Modifier.PUBLIC).build());
            actual = define(builder.build()).getField("value").getAnnotatedType();
        } else {
            builder.addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC)
                .addParameter("value", type).returns(type).build((self, p) -> p.getFirst().returning()));
            Method method = Arrays.stream(define(builder.build()).getDeclaredMethods()).filter(m -> m.getName().equals("value")).findFirst().orElseThrow();
            actual = kind.equals("return") ? method.getAnnotatedReturnType() : method.getAnnotatedParameterTypes()[0];
        }
        assertEquals(annotationTree(reference), annotationTree(actual));
    }

    @Test
    public void memberSuperclassRequiresOuterVariableBridge() throws Exception {
        var parent = (ClassTypeDef) fixtureType("baseOwner");
        var definition = ClassDef.builder("test.owner.OuterBridge").addModifiers(Modifier.PUBLIC)
            .superclass(parent)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("owner", TypeDef.of(Outer.class))
                .build((self, p) -> self.superRef().invokeSuperConstructor(List.of(TypeDef.of(Outer.class)), p)))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.of(Integer.class))
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("child").returning())).build();
        Class<?> generated = define(definition);
        assertTrue(Arrays.stream(OuterBridge.class.getDeclaredMethods()).anyMatch(Method::isBridge));
        assertTrue(Arrays.stream(generated.getDeclaredMethods()).anyMatch(Method::isBridge), "javac emits accept(Number) as a bridge");
        Outer<Integer>.Base instance = (Outer<Integer>.Base) generated.getConstructor(Outer.class).newInstance(new Outer<Integer>());
        assertEquals("child", instance.accept(1));
    }

    /**
     * A method modelled from a member of a generic owner keeps the owner's and its own type variables, its
     * generic signature and its erasure.
     *
     * @param reference The fixture method
     * @throws Exception If the generated program cannot be loaded
     */
    @ParameterizedTest(name = "{0} keeps the class and method variables javac writes")
    @MethodSource("typedMemberMethods")
    public void ownerSignaturesKeepClassAndMethodVariables(Method reference) throws Exception {
        var builder = ClassDef.builder("test.owner.TypedMembers").addModifiers(Modifier.PUBLIC);
        for (TypeVariable<?> variable : TypedMembers.class.getTypeParameters()) {
            builder.addTypeVariable(declaration(variable));
        }
        var method = MethodDef.builder(reference.getName()).addModifiers(Modifier.PUBLIC)
            .returns(TypeHierarchy.typeDefOf(reference.getGenericReturnType()));
        for (TypeVariable<?> variable : reference.getTypeParameters()) {
            method.addTypeVariable(declaration(variable));
        }
        Arrays.stream(reference.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).forEach(method::addParameter);
        method.addThrows(Arrays.stream(reference.getGenericExceptionTypes()).map(TypeHierarchy::typeDefOf).toList());
        builder.addMethod(method.build((self, p) -> ExpressionDef.nullValue().returning()));
        Class<?> generated = define(builder.build());
        Method actual = generated.getMethod(reference.getName(), reference.getParameterTypes());
        assertEquals(variableBounds(TypedMembers.class.getTypeParameters()), variableBounds(generated.getTypeParameters()));
        assertEquals(variableBounds(reference.getTypeParameters()), variableBounds(actual.getTypeParameters()));
        assertEquals(reference.getGenericReturnType().getTypeName(), actual.getGenericReturnType().getTypeName());
        assertEquals(Arrays.stream(reference.getGenericParameterTypes()).map(java.lang.reflect.Type::getTypeName).toList(),
            Arrays.stream(actual.getGenericParameterTypes()).map(java.lang.reflect.Type::getTypeName).toList());
        assertEquals(Arrays.stream(reference.getGenericExceptionTypes()).map(java.lang.reflect.Type::getTypeName).toList(),
            Arrays.stream(actual.getGenericExceptionTypes()).map(java.lang.reflect.Type::getTypeName).toList());
        assertEquals(reference.getReturnType(), actual.getReturnType());
        assertEquals(List.of(reference.getExceptionTypes()), List.of(actual.getExceptionTypes()));
    }

    static Stream<Arguments> memberSignatureCases() {
        return Stream.of("integerOwner", "plainOwner", "deepOwner", "wildcardOwner", "arrayOwner", "dollarOwner")
            .flatMap(name -> Stream.of("field", "method").map(kind -> Arguments.of(name, kind)));
    }

    static Stream<Arguments> memberTypeAnnotationCases() {
        return Stream.of("annotatedMember", "annotatedOuterArgument", "annotatedInnerArgument", "annotatedDeep")
            .flatMap(name -> Stream.of("field", "return", "parameter").map(kind -> Arguments.of(name, kind)));
    }

    static Stream<Arguments> typedMemberMethods() {
        return Arrays.stream(TypedMembers.class.getDeclaredMethods()).map(method -> Arguments.of(Named.of(method.getName(), method)));
    }

    private static TypeDef.TypeVariable declaration(TypeVariable<?> variable) {
        return TypeDef.variable(variable.getName(), Arrays.stream(variable.getBounds()).map(TypeHierarchy::typeDefOf).toList());
    }

    private static List<String> variableBounds(TypeVariable<?>[] variables) {
        return Arrays.stream(variables).map(variable -> variable.getName() + ":"
            + Arrays.stream(variable.getBounds()).map(java.lang.reflect.Type::getTypeName).toList()).toList();
    }

    private static TypeDef fixtureType(String name) throws Exception {
        return TypeHierarchy.typeDefOf(Signatures.class.getField(name).getGenericType());
    }

    private static TypeDef annotatedModel(AnnotatedType annotated) {
        TypeDef type;
        if (annotated instanceof AnnotatedArrayType array) {
            type = annotatedModel(array.getAnnotatedGenericComponentType()).array();
        } else if (annotated instanceof AnnotatedParameterizedType parameterized) {
            ParameterizedType reflectionType = (ParameterizedType) annotated.getType();
            ClassTypeDef raw = ClassTypeDef.of((Class<?>) reflectionType.getRawType());
            if (parameterized.getAnnotatedOwnerType() != null && reflectionType.getOwnerType() instanceof ParameterizedType) {
                raw = TypeHierarchy.memberType((ClassTypeDef) annotatedModel(parameterized.getAnnotatedOwnerType()), raw);
            }
            List<TypeDef> arguments = Arrays.stream(parameterized.getAnnotatedActualTypeArguments()).map(MemberTypeTck::annotatedModel).toList();
            type = arguments.isEmpty() ? raw : TypeDef.parameterized(raw, arguments);
        } else if (annotated instanceof AnnotatedWildcardType wildcard) {
            type = wildcard.getAnnotatedLowerBounds().length == 0
                ? TypeDef.wildcardSubtypeOf(annotatedModel(wildcard.getAnnotatedUpperBounds()[0]))
                : TypeDef.wildcardSupertypeOf(annotatedModel(wildcard.getAnnotatedLowerBounds()[0]));
        } else {
            type = TypeHierarchy.typeDefOf(annotated.getType());
        }
        List<AnnotationDef> annotations = Arrays.stream(annotated.getAnnotations())
            .map(annotation -> AnnotationDef.builder(annotation.annotationType()).build()).toList();
        return annotations.isEmpty() ? type : type.annotated(annotations);
    }

    private static String annotationTree(AnnotatedType type) {
        if (type == null) {
            return "";
        }
        String result = Arrays.stream(type.getAnnotations()).map(a -> a.annotationType().getSimpleName()).toList()
            + " " + type.getType().getTypeName() + " owner(" + annotationTree(type.getAnnotatedOwnerType()) + ")";
        if (type instanceof AnnotatedParameterizedType parameterized) {
            result += Arrays.stream(parameterized.getAnnotatedActualTypeArguments()).map(MemberTypeTck::annotationTree).toList();
        } else if (type instanceof AnnotatedArrayType array) {
            result += " array(" + annotationTree(array.getAnnotatedGenericComponentType()) + ")";
        } else if (type instanceof AnnotatedWildcardType wildcard) {
            result += " upper" + Arrays.stream(wildcard.getAnnotatedUpperBounds()).map(MemberTypeTck::annotationTree).toList()
                + " lower" + Arrays.stream(wildcard.getAnnotatedLowerBounds()).map(MemberTypeTck::annotationTree).toList();
        }
        return result;
    }

    /** Enclosing generic type for member signatures.
     * @param <T> Enclosing variable
     * @since 2.3
     */
    public static class Owner<T> {
        /** Parameterized member type.
         * @param <U> Member variable
         * @since 2.3
         */
        public class Member<U> { }
        /** Non-generic member of a generic enclosing type.
         * @since 2.3
         */
        public class PlainMember { }
    }

    /** javac member-type signature oracle.
     * @since 2.3
     */
    public static class MemberSignatures {
        public Owner<String>.Member<Integer> member() {
            return null;
        }

        public Owner<String>.PlainMember plainMember() {
            return null;
        }

        public Owner<String>.Member<Integer>[] memberArray() {
            return null;
        }
    }

    /** Runtime type annotation used as an independent path oracle.
     * @since 2.3
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Mark { }

    /** javac bridge oracle.
     * @since 2.3
     */
    public static class OuterBridge extends Outer<Integer>.Base {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @param outer The fixture input
         *
         * @since 2.3
         */
        public OuterBridge(Outer<Integer> outer) {
            outer.super();
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @since 2.3
         */
        @Override
        public String accept(Integer value) {
            return "child";
        }
    }

    /** Member signatures compiled independently by javac.
     * @since 2.3
     */
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static class Signatures {
        public Outer<Integer>.Inner<String> integerOwner;
        public Outer<Long>.Inner<String> longOwner;
        public Outer<Integer>.Plain plainOwner;
        public Outer<Integer>.Inherited inheritedOwner;
        public Outer<Integer>.Shadow<String> shadowOwner;
        public Outer<Integer>.Base baseOwner;
        public Outer<Integer>.Inner<String>.Deep<Double> deepOwner;
        public Outer<? extends Number>.Inner<? super String> wildcardOwner;
        public Outer<Integer>.Inner<String>[][] arrayOwner;
        public Outer<Integer>.Dollar$Member<String> dollarOwner;
        public Outer<Integer>.@Mark Inner<String> annotatedMember;
        public Outer<@Mark Integer>.Inner<String> annotatedOuterArgument;
        public Outer<Integer>.Inner<@Mark String> annotatedInnerArgument;
        public Outer<Integer>.Inner<String>.Deep<@Mark Double> annotatedDeep;
    }

    /** Combined owner, class, method, exception and array signatures compiled by javac.
     * @param <T> Fixture type
     * @param <U> Fixture type
     * @since 2.3
     */
    public static class TypedMembers<T extends Number, U extends T> {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <V> The fixture input
         * @param <E> The fixture input
         * @param input The fixture input
         *
         * @since 2.3
         */
        public <V extends U, E extends Exception> Outer<V>.Inner<List<? super U[]>> dependent(
            Outer<T>.Inner<? extends V[]> input) throws E {
            return null;
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param input The fixture input
         *
         * @since 2.3
         */
        public <T extends Integer> Outer<T>.Plain shadowed(Outer<T>.Plain input) {
            return input;
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <V> The fixture input
         * @param value The fixture input
         *
         * @since 2.3
         */
        public <V extends Outer<Integer>.Inner<String>> V memberBound(V value) {
            return value;
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public Outer<T>.Inner<U>.Deep<List<? extends U[][]>> arrays(Outer<U>.Inner<? super T[]> value) {
            return null;
        }
    }
}
