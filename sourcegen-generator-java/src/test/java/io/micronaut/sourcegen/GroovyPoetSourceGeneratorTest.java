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
package io.micronaut.sourcegen;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.tools.GroovyClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyPoetSourceGeneratorTest {

    private static final String COMPILE_STATIC_IMPORT = "import groovy.transform.CompileStatic;";
    private static final String COMPILE_STATIC = "@CompileStatic\n";

    private final GroovyPoetSourceGenerator groovyGenerator = new GroovyPoetSourceGenerator();
    private final JavaPoetSourceGenerator javaGenerator = new JavaPoetSourceGenerator();

    @Test
    void groovyOutputIsAnnotatedWithCompileStatic() throws IOException {
        for (ObjectDef objectDef : allDefs()) {
            String source = render(groovyGenerator, objectDef);
            assertTrue(source.contains(COMPILE_STATIC_IMPORT), () -> objectDef.getName() + ":\n" + source);
            String expectedLine = COMPILE_STATIC + "public ";
            assertTrue(source.contains(expectedLine), () -> objectDef.getName() + ":\n" + source);
        }
    }

    @Test
    void nestedGroovyTypesAreAnnotatedWithCompileStatic() throws IOException {
        ClassDef outer = ClassDef.builder("example.Outer")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .addInnerType(InterfaceDef.builder("Contract").addModifiers(Modifier.PUBLIC).build())
            .addInnerType(EnumDef.builder("Kind").addModifiers(Modifier.PUBLIC).addEnumConstant("A").build())
            .addInnerType(RecordDef.builder("Pair").addModifiers(Modifier.PUBLIC)
                .addProperty(PropertyDef.builder("left").ofType(TypeDef.STRING).build())
                .build())
            .build();
        String source = render(groovyGenerator, outer);
        assertTrue(source.contains(COMPILE_STATIC_IMPORT), source);
        // The outer class plus its four nested types
        assertEquals(5, source.split(COMPILE_STATIC, -1).length - 1, source);
    }

    @Test
    void javaOutputIsNotAnnotatedWithCompileStatic() throws IOException {
        for (ObjectDef objectDef : allDefs()) {
            String source = render(javaGenerator, objectDef);
            assertFalse(source.contains("CompileStatic"), () -> objectDef.getName() + ":\n" + source);
        }
    }

    @Test
    void groovyOutputCompilesStatically() {
        ClassShape shape = compilePersonBuilder(groovyGenerator);
        assertTrue(shape.methodNames.contains("upperName"), shape.methodNames.toString());
        // Static compilation dispatches calls with invokevirtual, dynamic Groovy uses invokedynamic call sites
        assertEquals(List.of(), shape.invokeDynamicMethods, "Dynamically dispatched call sites found");
        assertTrue(shape.annotationDescriptors.stream().noneMatch(a -> a.contains("CompileStatic")),
            "CompileStatic is a source-only transformation: " + shape.annotationDescriptors);
    }

    @Test
    void sameSourceWithoutCompileStaticCompilesDynamically() {
        // Sanity check of the assertion above: the Java generator output has no @CompileStatic
        ClassShape shape = compilePersonBuilder(javaGenerator);
        assertTrue(shape.invokeDynamicMethods.contains("upperName"), shape.invokeDynamicMethods.toString());
    }

    private static ClassShape compilePersonBuilder(JavaPoetSourceGenerator generator) {
        String source;
        try {
            source = render(generator, personBuilder());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GroovyClass personBuilder = compileGroovy("PersonBuilder.groovy", source).stream()
            .filter(c -> c.getName().equals("example.PersonBuilder"))
            .findFirst()
            .orElseThrow();
        return ClassShape.of(personBuilder.getBytes());
    }

    private static final class ClassShape extends ClassVisitor {
        private final List<String> methodNames = new ArrayList<>();
        private final List<String> invokeDynamicMethods = new ArrayList<>();
        private final List<String> annotationDescriptors = new ArrayList<>();

        private ClassShape() {
            super(Opcodes.ASM9);
        }

        static ClassShape of(byte[] bytes) {
            ClassShape shape = new ClassShape();
            new ClassReader(bytes).accept(shape, 0);
            return shape;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methodNames.add(name);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    if (!invokeDynamicMethods.contains(name)) {
                        invokeDynamicMethods.add(name);
                    }
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotationDescriptors.add(descriptor);
            return null;
        }
    }

    private static List<GroovyClass> compileGroovy(String fileName, String source) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setTargetBytecode(CompilerConfiguration.JDK17);
        CompilationUnit compilationUnit = new CompilationUnit(configuration);
        compilationUnit.addSource(fileName, source);
        compilationUnit.compile(Phases.CLASS_GENERATION);
        return compilationUnit.getClasses();
    }

    private static String render(JavaPoetSourceGenerator generator, ObjectDef objectDef) throws IOException {
        StringWriter writer = new StringWriter();
        generator.write(objectDef, writer);
        return writer.toString();
    }

    private static List<ObjectDef> allDefs() {
        return Stream.of(
            personBuilder(),
            RecordDef.builder("example.Person")
                .addModifiers(Modifier.PUBLIC)
                .addProperty(PropertyDef.builder("id").ofType(TypeDef.of(Long.class)).build())
                .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING).build())
                .build(),
            EnumDef.builder("example.Color")
                .addModifiers(Modifier.PUBLIC)
                .addEnumConstant("RED")
                .addEnumConstant("GREEN")
                .build(),
            InterfaceDef.builder("example.Greeter")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("greet")
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(TypeDef.STRING)
                    .build())
                .build()
        ).map(ObjectDef.class::cast).toList();
    }

    private static ClassDef personBuilder() {
        ClassTypeDef selfType = ClassTypeDef.of("example.PersonBuilder");
        FieldDef nameField = FieldDef.builder("name").ofType(TypeDef.STRING).addModifiers(Modifier.PRIVATE).build();
        return ClassDef.builder("example.PersonBuilder")
            .addModifiers(Modifier.PUBLIC)
            .addField(nameField)
            .addMethod(MethodDef.builder("name")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("name", TypeDef.STRING)
                .returns(selfType)
                .build((self, params) -> self.field(nameField).put(params.get(0)).after(self.returning())))
            .addMethod(MethodDef.builder("upperName")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((self, params) -> self.field(nameField).invoke("toUpperCase", TypeDef.STRING).returning()))
            .addMethod(MethodDef.builder("getName")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((self, params) -> self.field(nameField).returning()))
            .build();
    }
}
