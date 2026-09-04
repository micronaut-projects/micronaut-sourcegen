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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationMemberDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.ClassFile;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation type written by the JDK backend has to be readable by the reflection that reads a
 * compiled one: the flags of an annotation type, its meta-annotations, and the default of every member.
 */
class JdkAnnotationObjectWriterTest {

    @Test
    void writesAnAnnotationTypeThatReflectsLikeACompiledOne() throws Exception {
        Class<?> annotationClass = define(annotationDef("example.MyAnnotation"));

        assertTrue(annotationClass.isAnnotation());
        assertTrue(annotationClass.isInterface());
        assertTrue(Annotation.class.isAssignableFrom(annotationClass));

        assertEquals(RetentionPolicy.RUNTIME, annotationClass.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, annotationClass.getAnnotation(Target.class).value());

        assertEquals(String.class, annotationClass.getDeclaredMethod("string").getReturnType());
        assertEquals("hello", annotationClass.getDeclaredMethod("string").getDefaultValue());
        assertEquals(int.class, annotationClass.getDeclaredMethod("primitive").getReturnType());
        assertEquals(2, annotationClass.getDeclaredMethod("primitive").getDefaultValue());
        assertEquals(ElementType.TYPE, annotationClass.getDeclaredMethod("enumValue").getDefaultValue());
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) annotationClass.getDeclaredMethod("array").getDefaultValue());

        Method floats = annotationClass.getDeclaredMethod("floats");
        assertEquals(float[].class, floats.getReturnType());
        assertNull(floats.getDefaultValue());

        Target inner = (Target) annotationClass.getDeclaredMethod("inner").getDefaultValue();
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, inner.value());
    }

    @Test
    void everyMemberOfAnAnnotationTypeIsPublicAndAbstract() {
        Class<?> annotationClass = define(annotationDef("example.MyAnnotation"));

        for (Method member : annotationClass.getDeclaredMethods()) {
            assertTrue(java.lang.reflect.Modifier.isPublic(member.getModifiers()), member.getName());
            assertTrue(java.lang.reflect.Modifier.isAbstract(member.getModifiers()), member.getName());
            assertEquals(0, member.getParameterCount(), member.getName());
        }
    }

    @Test
    void writesTheConstantsAnAnnotationTypeDeclares() throws Exception {
        AnnotationObjectDef definition = AnnotationObjectDef.builder("example.WithConstant")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Retention.class)
                .addMember("value", RetentionPolicy.RUNTIME)
                .build())
            .addField(FieldDef.builder("DEFAULT_NAME", TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(ExpressionDef.constant("none"))
                .build())
            .addMember(AnnotationMemberDef.builder("name", TypeDef.STRING)
                .withDefault(ExpressionDef.constant("none"))
                .build())
            .build();

        Class<?> annotationClass = define(definition);

        assertTrue(annotationClass.isAnnotation());
        assertEquals("none", annotationClass.getDeclaredField("DEFAULT_NAME").get(null));
        assertEquals("none", annotationClass.getDeclaredMethod("name").getDefaultValue());
    }

    @Test
    void writesAClassValuedMemberWithItsDefault() throws Exception {
        AnnotationObjectDef definition = AnnotationObjectDef.builder("example.WithClass")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Retention.class)
                .addMember("value", RetentionPolicy.RUNTIME)
                .build())
            .addMember(AnnotationMemberDef.builder("type", TypeDef.CLASS)
                .withDefault(ExpressionDef.constant(ClassTypeDef.of(String.class)))
                .build())
            .build();

        assertEquals(String.class, define(definition).getDeclaredMethod("type").getDefaultValue());
    }

    /**
     * The same definition the Java, Kotlin and ASM backends are covered with.
     */
    private static AnnotationObjectDef annotationDef(String className) {
        return AnnotationObjectDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Retention.class)
                .addMember("value", RetentionPolicy.RUNTIME)
                .build())
            .addAnnotation(AnnotationDef.builder(Target.class)
                .addMember("value", ElementType.TYPE)
                .build())
            .addJavadoc("This is my annotation")
            .addMember(AnnotationMemberDef.builder("string", TypeDef.STRING)
                .withDefault(ExpressionDef.constant("hello"))
                .build())
            .addMember(AnnotationMemberDef.builder("primitive", TypeDef.Primitive.INT)
                .withDefault(ExpressionDef.constant(2))
                .build())
            .addMember(AnnotationMemberDef.builder("floats", TypeDef.Primitive.FLOAT.array())
                .build())
            .addMember(AnnotationMemberDef.builder("enumValue", ClassTypeDef.of(ElementType.class))
                .withDefault(ClassTypeDef.of(ElementType.class)
                    .getStaticField("TYPE", ClassTypeDef.of(ElementType.class)))
                .build())
            .addMember(AnnotationMemberDef.builder("inner", ClassTypeDef.of(Target.class))
                .withDefault(AnnotationDef.builder(Target.class)
                    .addMember("value", ElementType.TYPE)
                    .build())
                .build())
            .addMember(AnnotationMemberDef.builder("array", TypeDef.Primitive.INT.array())
                .withDefault(ExpressionDef.constant(new int[]{1, 2, 3}))
                .build())
            .build();
    }

    private Class<?> define(ObjectDef definition) {
        byte[] bytes = new JdkClassFileWriter(true).write(definition, null)
            .orElseThrow(() -> new AssertionError("Expected direct ClassFile lowering for " + definition.getName()));
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
    }
}
