package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.custom.visitor.GenerateAnnotationClassVisitor;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

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

class AnnotationByteCodeWriterTest {

    @Test
    void writeAnnotationClass() throws Exception {
        AnnotationObjectDef def = GenerateAnnotationClassVisitor.createAnnotation("test.TestAnnotation");

        byte[] bytes = generate(def);
        Class<?> clazz = new ByteArrayClassLoader(getClass().getClassLoader())
            .defineClass("test.TestAnnotation", bytes);

        assertTrue(clazz.isAnnotation(), "Generated class should be an annotation");
        assertTrue(Annotation.class.isAssignableFrom(clazz));

        // Type-level annotations must be readable at runtime (proves @Retention(RUNTIME) was emitted)
        Retention retention = clazz.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        // @Target is emitted as a type-level annotation. The shared model fixture supplies a scalar
        // ElementType for Target#value() (declared as ElementType[]); the writer wraps such scalars in
        // a single-element array (as source compilers do), so the meta-annotation is well-formed.
        Target target = clazz.getAnnotation(Target.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, target.value());

        // string: String default "hello"
        Method string = clazz.getDeclaredMethod("string");
        assertEquals(String.class, string.getReturnType());
        assertEquals("hello", string.getDefaultValue());

        // primitive: int default 2
        Method primitive = clazz.getDeclaredMethod("primitive");
        assertEquals(int.class, primitive.getReturnType());
        assertEquals(2, primitive.getDefaultValue());

        // floats: float[] with no default
        Method floats = clazz.getDeclaredMethod("floats");
        assertEquals(float[].class, floats.getReturnType());
        assertNull(floats.getDefaultValue());

        // enumValue: ElementType default ElementType.TYPE
        Method enumValue = clazz.getDeclaredMethod("enumValue");
        assertEquals(ElementType.class, enumValue.getReturnType());
        assertEquals(ElementType.TYPE, enumValue.getDefaultValue());

        // inner: Target default @Target(ElementType.TYPE)
        Method inner = clazz.getDeclaredMethod("inner");
        assertEquals(Target.class, inner.getReturnType());
        // The default is a @Target annotation whose value() (ElementType[]) is supplied as a scalar in
        // the model and wrapped into a single-element array by the writer (see type-level @Target above).
        assertTrue(inner.getDefaultValue() instanceof Target);
        assertArrayEquals(new ElementType[]{ElementType.TYPE}, ((Target) inner.getDefaultValue()).value());

        // array: int[] default {1, 2, 3}
        Method array = clazz.getDeclaredMethod("array");
        assertEquals(int[].class, array.getReturnType());
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) array.getDefaultValue());
    }

    private byte[] generate(AnnotationObjectDef def) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter checkClassAdapter = new CheckClassAdapter(classWriter);
        new ByteCodeWriter(false, false).writeObject(checkClassAdapter, def);
        checkClassAdapter.visitEnd();
        return classWriter.toByteArray();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineClass(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
