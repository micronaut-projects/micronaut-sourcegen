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

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.classfile.ClassFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the JDK backend does beyond the shared contract in
 * {@link io.micronaut.sourcegen.bytecode.tck.ByteCodeWriterTck}: which constructs it lowers
 * directly, and where it falls back to javac instead.
 */
class JdkByteCodeWriterParityTest {

    @Test
    void publicWriterCoversFallbackEnumsInterfacesAndMemberTypes() throws Exception {
        ByteCodeWriter writer = new ByteCodeWriter();
        EnumDef enumDef = EnumDef.builder("example.JdkFallbackEnum")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("ONE")
            .addEnumConstant("TWO")
            .build();
        byte[] enumBytes = writer.write(enumDef);
        assertVerified(enumBytes);
        Class<?> enumClass = new MapClassLoader(Map.of(enumDef.getName(), enumBytes)).loadClass(enumDef.getName());
        assertEquals(List.of("ONE", "TWO"), Arrays.stream(enumClass.getEnumConstants()).map(Object::toString).toList());

        InterfaceDef interfaceDef = InterfaceDef.builder("example.JdkFallbackInterface")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.STRING)
                .build())
            .build();
        byte[] interfaceBytes = writer.write(interfaceDef);
        assertVerified(interfaceBytes);
        Class<?> interfaceClass = new MapClassLoader(Map.of(interfaceDef.getName(), interfaceBytes))
            .loadClass(interfaceDef.getName());
        assertTrue(interfaceClass.isInterface());

        ClassDef outer = ClassDef.builder("example.JdkFallbackOuter")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .build())
            .build();
        Map<String, byte[]> classes = writer.writeAll(outer);
        assertTrue(classes.containsKey(outer.getName()));
        assertTrue(classes.containsKey(outer.getName() + "$Inner"));
        classes.values().forEach(JdkByteCodeWriterParityTest::assertVerified);
        MapClassLoader loader = new MapClassLoader(classes);
        Class<?> outerClass = loader.loadClass(outer.getName());
        Class<?> innerClass = loader.loadClass(outer.getName() + "$Inner");
        assertSame(outerClass, innerClass.getDeclaringClass());
    }

    @Test
    void directlyWritesClassesReferencingTypesThatAreNotResolvableYet() {
        // Types generated earlier in the same compilation round have no class file to read. The
        // writer must still emit the class rather than abandoning it, because the JVM checks the
        // real hierarchy when the class is finally loaded.
        ClassTypeDef first = ClassTypeDef.of("example.NotOnTheClassPathYetOne");
        ClassTypeDef second = ClassTypeDef.of("example.NotOnTheClassPathYetTwo");
        ClassDef definition = ClassDef.builder("example.JdkUnresolvedTypeParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> parameters.get(0).isTrue()
                    .doIfElse(first.instantiate().returning(), second.instantiate().returning())))
            .build();

        assertVerified(writeDirect(definition));

        // The same holds when a compilation type lookup is supplied but knows nothing about them
        var withLookup = new JdkClassFileWriter(true, name -> java.util.Optional.empty())
            .write(definition, null);
        assertTrue(withLookup.isPresent());
        assertVerified(withLookup.orElseThrow());
    }

    @Test
    void publicWriterFallsBackToJavacForConstructsTheDirectWriterDeclines() throws Exception {
        // A char selector is not lowered directly
        ClassDef definition = ClassDef.builder("example.JdkJavacFallback")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("describe")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.CHAR)
                .returns(TypeDef.STRING)
                .build((ignored, parameters) -> parameters.get(0).asStatementSwitch(TypeDef.STRING,
                    Map.of(ExpressionDef.constant(1), ExpressionDef.constant("one").returning()),
                    ExpressionDef.constant("other").returning())))
            .build();
        assertTrue(new JdkClassFileWriter(true).write(definition, null).isEmpty());

        ByteCodeWriter writer = new ByteCodeWriter();
        // Twice, so the second compilation goes through the shared file manager
        byte[] first = writer.write(definition);
        byte[] second = writer.write(definition);
        assertArrayEquals(first, second);
        assertVerified(first);
        Class<?> generated = new MapClassLoader(Map.of(definition.getName(), first)).loadClass(definition.getName());
        assertEquals("one", generated.getMethod("describe", char.class).invoke(null, (char) 1));
        assertEquals("other", generated.getMethod("describe", char.class).invoke(null, (char) 9));
    }

    @Test
    void directlyWritesNestedTypesAsNestmates() throws Exception {
        ClassTypeDef outerType = ClassTypeDef.of("example.JdkNestParity");
        FieldDef secret = FieldDef.builder("secret", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .initializer(ExpressionDef.constant(7))
            .build();
        // A member type is declared by its simple name; adding it to the outer type gives it its binary name
        ClassDef outer = ClassDef.builder(outerType.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(secret)
            .addInnerType(ClassDef.builder("Inner")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addMethod(MethodDef.builder("peek")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(TypeDef.Primitive.INT)
                    .build((ignored, parameters) -> outerType.getStaticField(secret).returning()))
                .build())
            .build();
        ObjectDef inner = outer.getInnerTypes().get(0);
        assertEquals("example.JdkNestParity$Inner", inner.getName());

        var innerResult = new JdkClassFileWriter(true).write(inner, outer.asTypeDef());
        assertTrue(innerResult.isPresent(), "Expected direct ClassFile lowering for the member type");
        assertVerified(innerResult.orElseThrow());
        MapClassLoader loader = new MapClassLoader(Map.of(
            outer.getName(), writeDirect(outer),
            inner.getName(), innerResult.orElseThrow()
        ));
        Class<?> outerClass = loader.loadClass(outer.getName());
        Class<?> innerClass = loader.loadClass(inner.getName());

        assertEquals(outerClass, innerClass.getDeclaringClass());
        assertEquals(outerClass, innerClass.getNestHost());
        assertArrayEquals(new Class<?>[] {innerClass}, outerClass.getDeclaredClasses());
        assertEquals("Inner", innerClass.getSimpleName());
        assertTrue(java.lang.reflect.Modifier.isStatic(innerClass.getModifiers()));
        // Reading the outer type's private field only links when the two really are nestmates
        assertEquals(7, innerClass.getMethod("peek").invoke(null));
    }

    @Test
    void directlyWritesJoinsOfModelOnlyTypesThroughTheHierarchyResolver() throws Exception {
        ClassDef first = ClassDef.builder("example.JdkMergeFirst").addModifiers(Modifier.PUBLIC).build();
        ClassDef second = ClassDef.builder("example.JdkMergeSecond").addModifiers(Modifier.PUBLIC).build();
        ClassDef definition = ClassDef.builder("example.JdkMergeParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> parameters.get(0).isTrue()
                    .doIfElse(first.asTypeDef().instantiate(), second.asTypeDef().instantiate())
                    .returning()))
            .build();

        // The stack map at the join needs the hierarchy of two classes that exist only as models
        MapClassLoader loader = new MapClassLoader(Map.of(
            first.getName(), writeDirect(first),
            second.getName(), writeDirect(second),
            definition.getName(), writeDirect(definition)
        ));
        Class<?> generated = loader.loadClass(definition.getName());

        assertInstanceOf(loader.loadClass(first.getName()), generated.getMethod("choose", boolean.class).invoke(null, true));
        assertInstanceOf(loader.loadClass(second.getName()), generated.getMethod("choose", boolean.class).invoke(null, false));
    }

    private static byte[] writeDirect(ObjectDef definition) {
        var result = new JdkClassFileWriter(true).write(definition, null);
        assertTrue(result.isPresent(), () -> "Expected direct ClassFile lowering for " + definition.getName());
        byte[] bytes = result.orElseThrow();
        assertVerified(bytes);
        return bytes;
    }

    private static void assertVerified(byte[] bytes) {
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
    }

    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private MapClassLoader(Map<String, byte[]> classes) {
            super(JdkByteCodeWriterParityTest.class.getClassLoader());
            this.classes = new LinkedHashMap<>(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

