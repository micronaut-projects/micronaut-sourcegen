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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyPoetSourceGeneratorTest {

    private static final Set<String> NESTED_CLASS_NAMES = Set.of(
        "example.PersonBuilder$Helper",
        "example.PersonBuilder$Pair",
        "example.PersonBuilder$Kind",
        "example.PersonBuilder$Named"
    );
    private static final Map<String, List<String>> DECLARED_METHODS = Map.of(
        "example.PersonBuilder", List.of("name", "upperName", "getName"),
        "example.PersonBuilder$Helper", List.of("upper"),
        "example.PersonBuilder$Pair", List.of("upper"),
        "example.PersonBuilder$Kind", List.of("upper"),
        "example.PersonBuilder$Named", List.of("name")
    );
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
        Map<String, ClassShape> shapes = compileGroovy(groovyGenerator, personBuilder());
        assertEquals(NESTED_CLASS_NAMES, nestedClassNames(shapes));
        DECLARED_METHODS.forEach((className, methods) -> {
            ClassShape shape = shapes.get(className);
            assertTrue(shape.methodNames.containsAll(methods), () -> className + " " + shape.methodNames);
            for (String method : methods) {
                // A statically compiled method calls through invokevirtual, not through Groovy's indy bootstrap
                assertEquals(List.of(), shape.groovyCallSites(method), () -> "Groovy call sites in " + className + "." + method);
            }
        });
        shapes.forEach((className, shape) -> {
            // Groovy still emits cast call sites in its own helpers (methodMissing, enum values/valueOf),
            // but a statically compiled class has no dynamic method, property or constructor dispatch
            assertEquals(List.of(), shape.groovyDynamicDispatch(), () -> "Dynamic dispatch in " + className);
            assertTrue(shape.annotationDescriptors.stream().noneMatch(d -> d.contains("CompileStatic")),
                () -> "CompileStatic is a source-only transformation: " + className + " " + shape.annotationDescriptors);
        });
    }

    @Test
    void sameSourceWithoutCompileStaticCompilesDynamically() {
        // Sanity check of the assertions above: the Java generator output has no @CompileStatic.
        // Groovy records are always compiled statically, so the record is not part of this check.
        Map<String, ClassShape> shapes = compileGroovy(javaGenerator, personBuilder());
        assertEquals(NESTED_CLASS_NAMES, nestedClassNames(shapes));
        Map<String, String> dynamicMethods = Map.of(
            "example.PersonBuilder", "upperName",
            "example.PersonBuilder$Helper", "upper",
            "example.PersonBuilder$Kind", "upper"
        );
        dynamicMethods.forEach((className, method) -> {
            ClassShape shape = shapes.get(className);
            assertTrue(shape.groovyCallSites(method).contains("invoke"), () -> className + "." + method + " " + shape.groovyCallSites(method));
            assertFalse(shape.groovyDynamicDispatch().isEmpty(), className);
        });
    }

    private static Set<String> nestedClassNames(Map<String, ClassShape> shapes) {
        return shapes.keySet().stream().filter(n -> n.startsWith("example.PersonBuilder$")).collect(Collectors.toSet());
    }

    private static Map<String, ClassShape> compileGroovy(JavaPoetSourceGenerator generator, ObjectDef objectDef) {
        String source;
        try {
            source = render(generator, objectDef);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, ClassShape> shapes = new TreeMap<>();
        for (GroovyClass groovyClass : compileGroovy(objectDef.getSimpleName() + ".groovy", source)) {
            shapes.put(groovyClass.getName(), ClassShape.of(groovyClass.getBytes()));
        }
        return shapes;
    }

    private static final class ClassShape extends ClassVisitor {
        private static final String GROOVY_INDY_BOOTSTRAP = "org/codehaus/groovy/vmplugin/v8/IndyInterface";

        private final List<String> methodNames = new ArrayList<>();
        private final List<String> annotationDescriptors = new ArrayList<>();
        // Pairs of declaring method name and Groovy call site name (invoke, getProperty, init, cast, ...)
        private final List<Map.Entry<String, String>> groovyCallSites = new ArrayList<>();

        private ClassShape() {
            super(Opcodes.ASM9);
        }

        static ClassShape of(byte[] bytes) {
            ClassShape shape = new ClassShape();
            new ClassReader(bytes).accept(shape, 0);
            return shape;
        }

        List<String> groovyCallSites(String method) {
            return groovyCallSites.stream().filter(e -> e.getKey().equals(method)).map(Map.Entry::getValue).toList();
        }

        List<String> groovyDynamicDispatch() {
            return groovyCallSites.stream()
                .filter(e -> !e.getValue().equals("cast"))
                .map(e -> e.getKey() + ":" + e.getValue())
                .toList();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methodNames.add(name);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    if (bootstrapMethodHandle.getOwner().equals(GROOVY_INDY_BOOTSTRAP)) {
                        groovyCallSites.add(Map.entry(name, indyName));
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
            .addInnerType(ClassDef.builder("Helper")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addMethod(upperMethod())
                .build())
            .addInnerType(RecordDef.builder("Pair")
                .addModifiers(Modifier.PUBLIC)
                .addProperty(PropertyDef.builder("left").ofType(TypeDef.STRING).build())
                .addMethod(upperMethod())
                .build())
            .addInnerType(EnumDef.builder("Kind")
                .addModifiers(Modifier.PUBLIC)
                .addEnumConstant("A")
                .addMethod(upperMethod())
                .build())
            .addInnerType(InterfaceDef.builder("Named")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("name")
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(TypeDef.STRING)
                    .build())
                .build())
            .build();
    }

    private static MethodDef upperMethod() {
        return MethodDef.builder("upper")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((self, params) -> params.get(0).invoke("toUpperCase", TypeDef.STRING).returning());
    }
}
