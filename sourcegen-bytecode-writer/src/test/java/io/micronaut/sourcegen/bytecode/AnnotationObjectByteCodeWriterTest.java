package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.custom.visitor.GenerateAnnotationClassVisitor;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationMemberDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An annotation type written as bytecode has to be readable by the reflection that reads a compiled one:
 * the flags of an annotation type, its meta-annotations, and the default of every member.
 */
class AnnotationObjectByteCodeWriterTest {

    @Test
    void writesAnAnnotationTypeThatReflectsLikeACompiledOne() throws Exception {
        Class<?> annotationClass = define(GenerateAnnotationClassVisitor.createAnnotation("example.MyAnnotation"));

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
    void everyMemberOfAnAnnotationTypeIsPublicAndAbstract() throws Exception {
        Class<?> annotationClass = define(GenerateAnnotationClassVisitor.createAnnotation("example.MyAnnotation"));

        for (Method member : annotationClass.getDeclaredMethods()) {
            assertTrue(java.lang.reflect.Modifier.isPublic(member.getModifiers()), member.getName());
            assertTrue(java.lang.reflect.Modifier.isAbstract(member.getModifiers()), member.getName());
            assertEquals(0, member.getParameterCount(), member.getName());
        }
    }

    @Test
    void writesTheConstantsAnAnnotationTypeDeclares() throws Exception {
        AnnotationObjectDef annotationDef = AnnotationObjectDef.builder("example.WithConstant")
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

        Class<?> annotationClass = define(annotationDef);

        assertTrue(annotationClass.isAnnotation());
        assertEquals("none", annotationClass.getDeclaredField("DEFAULT_NAME").get(null));
        assertEquals("none", annotationClass.getDeclaredMethod("name").getDefaultValue());
    }

    @Test
    void writesAClassValuedMemberWithItsDefault() throws Exception {
        AnnotationObjectDef annotationDef = AnnotationObjectDef.builder("example.WithClass")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Retention.class)
                .addMember("value", RetentionPolicy.RUNTIME)
                .build())
            .addMember(AnnotationMemberDef.builder("type", TypeDef.CLASS)
                .withDefault(ExpressionDef.constant(ClassTypeDef.of(String.class)))
                .build())
            .build();

        Class<?> annotationClass = define(annotationDef);

        assertEquals(String.class, annotationClass.getDeclaredMethod("type").getDefaultValue());
    }

    private Class<?> define(ObjectDef objectDef) {
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        new ByteCodeWriter(false, true).writeObject(new CheckClassAdapter(classWriter), objectDef, null);
        byte[] bytes = classWriter.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(objectDef.getName(), bytes, 0, bytes.length);
            }
        }.define();
    }
}
