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
package io.micronaut.sourcegen.generator.bytecode.jdk

import io.micronaut.inject.ast.Element
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.inject.writer.GeneratedFile
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import spock.lang.Specification

import javax.lang.model.element.Modifier
import java.lang.classfile.ClassFile
import java.nio.file.Files

class ByteCodeGeneratorSpec extends Specification {

    /** GeneratedFile#write is a default method writing through openWriter, so it needs a real one. */
    static class InMemoryGeneratedFile implements GeneratedFile {
        final StringWriter writer = new StringWriter()

        URI toURI() { URI.create("mem:///generated") }

        String getName() { "generated" }

        InputStream openInputStream() { throw new UnsupportedOperationException() }

        OutputStream openOutputStream() { throw new UnsupportedOperationException() }

        Reader openReader() { throw new UnsupportedOperationException() }

        CharSequence getTextContent() { writer.toString() }

        Writer openWriter() { writer }
    }

    private static ClassDef simple(String name) {
        ClassDef.builder(name)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("value")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(TypeDef.STRING)
                        .build({ aThis, parameters -> ExpressionDef.constant("hi").returning() }))
                .build()
    }

    /** A definition the direct writer declines, and whose source javac cannot compile either. */
    private static ClassDef broken(String name) {
        ClassDef.builder(name)
                .addModifiers(Modifier.PUBLIC)
                // A char selector is not lowered directly, so this goes to the source fallback
                .addMethod(MethodDef.builder("describe")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addParameter("index", TypeDef.Primitive.CHAR)
                        .returns(TypeDef.STRING)
                        .build({ aThis, parameters ->
                            parameters.get(0).asStatementSwitch(TypeDef.STRING,
                                    [(ExpressionDef.constant(1)): ExpressionDef.constant("one").returning()],
                                    ExpressionDef.constant("other").returning())
                        }))
                // ... where it names a type that exists nowhere, so the compilation fails
                .addMethod(MethodDef.builder("broken")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(TypeDef.OBJECT)
                        .build({ aThis, parameters ->
                            ClassTypeDef.of("example.definitely.Missing").instantiate().returning()
                        }))
                .build()
    }

    void "the JDK backend generates Java"() {
        expect:
            new ByteCodeGenerator().language == VisitorContext.Language.JAVA
    }

    void "a definition is written as a verifiable class file"() {
        given:
            def generator = new ByteCodeGenerator()
            def output = new ByteArrayOutputStream()
            def context = Stub(VisitorContext) {
                getOptions() >> [:]
                getProjectDir() >> Optional.empty()
                getClassesOutputPath() >> Optional.empty()
                getClasspathResources(_) >> []
                visitClass("example.Written", _) >> output
            }

        when:
            def traverseMembers = generator.writeClass(simple("example.Written"), null, context, new Element[0])

        then:
            traverseMembers
            ClassFile.of().verify(output.toByteArray()).isEmpty()
    }

    void "the source path and class path come from the processing options"() {
        given:
            def generator = new ByteCodeGenerator()
            def sources = Files.createTempDirectory("sourcegen-sources")
            def classes = Files.createTempDirectory("sourcegen-classes")
            def output = new ByteArrayOutputStream()
            def context = Stub(VisitorContext) {
                getOptions() >> [
                        (ByteCodeGenerator.SOURCE_PATH_OPTION): sources.toString(),
                        (ByteCodeGenerator.CLASS_PATH_OPTION) : classes.toString() + File.pathSeparator + " "
                ]
                getProjectDir() >> Optional.of(sources)
                getClassesOutputPath() >> Optional.of(classes)
                getClasspathResources(_) >> []
                visitClass(_, _) >> output
            }

        when:
            generator.writeClass(simple("example.WithPaths"), null, context, new Element[0])

        then:
            ClassFile.of().verify(output.toByteArray()).isEmpty()

        cleanup:
            Files.deleteIfExists(sources)
            Files.deleteIfExists(classes)
    }

    void "a definition that cannot be emitted is written as Java source with a warning"() {
        given:
            def generator = new ByteCodeGenerator()
            def warnings = []
            def generatedFile = new InMemoryGeneratedFile()
            def context = Stub(VisitorContext) {
                getOptions() >> [:]
                getProjectDir() >> Optional.empty()
                getClassesOutputPath() >> Optional.empty()
                getClasspathResources(_) >> []
                visitGeneratedSourceFile(_, _, _) >> Optional.of(generatedFile)
                warn(_, _) >> { String message, Element element -> warnings << message }
            }

        when:
            def traverseMembers = generator.writeClass(broken("example.Fallen"), null, context, new Element[0])

        then:
            !traverseMembers
            generatedFile.textContent.contains("class Fallen")
            warnings.size() == 1
            warnings[0].contains("example.Fallen")
    }

    void "a member type that cannot be emitted is reported, since its enclosing class is already written"() {
        given:
            def generator = new ByteCodeGenerator()
            def context = Stub(VisitorContext) {
                getOptions() >> [:]
                getProjectDir() >> Optional.empty()
                getClassesOutputPath() >> Optional.empty()
                getClasspathResources(_) >> []
            }

        when:
            generator.writeClass(broken('example.Outer$Fallen'), ClassTypeDef.of('example.Outer'),
                    context, new Element[0])

        then:
            def e = thrown(ProcessingException)
            e.message.contains("example.Outer\$Fallen")
    }

    void "a definition that cannot be emitted and has nowhere to write source is reported"() {
        given:
            def generator = new ByteCodeGenerator()
            def context = Stub(VisitorContext) {
                getOptions() >> [:]
                getProjectDir() >> Optional.empty()
                getClassesOutputPath() >> Optional.empty()
                getClasspathResources(_) >> []
                visitGeneratedSourceFile(_, _, _) >> Optional.empty()
            }

        when:
            generator.writeClass(broken("example.Nowhere"), null, context, new Element[0])

        then:
            def e = thrown(ProcessingException)
            e.message.contains("example.Nowhere")
    }
}
