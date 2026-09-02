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
package io.micronaut.sourcegen.generator.bytecode.jdk;

import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.bytecode.jdk.ByteCodeWriter;
import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.generator.AbstractByteCodeGenerator;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sourcegen generator entry point for the JDK backend.
 *
 * <p>The writer uses the annotation-processing context to reconstruct the source and class paths
 * visible to the current compilation. It emits normalized classfiles directly when all referenced
 * types are resolvable and preserves annotation-processing round semantics with a Java source
 * fallback when a peer generated type is not yet available.</p>
 *
 * @since 2.2
 */
public final class ByteCodeGenerator extends AbstractByteCodeGenerator {

    /**
     * Annotation-processor option holding additional source roots, separated by {@link File#pathSeparator},
     * which javac may read when a generated class references a type that is still being compiled.
     * Builds whose sources live outside {@code src/main/java} and {@code src/test/java} of the project
     * directory, for example shared source sets, should pass those roots through this option.
     */
    public static final String SOURCE_PATH_OPTION = "micronaut.sourcegen.bytecode.jdk.sourcepath";

    /**
     * Annotation-processor option holding the compilation's class path, separated by {@link File#pathSeparator}.
     * An annotation processor cannot see the class path javac was started with, only its own processor path,
     * so builds whose generated classes reference compile-scope dependencies should pass that class path here
     * for the source fallback to resolve them.
     */
    public static final String CLASS_PATH_OPTION = "micronaut.sourcegen.bytecode.jdk.classpath";

    private static final JavaPoetSourceGenerator SOURCE_GENERATOR = new JavaPoetSourceGenerator();

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    protected boolean writeClass(ObjectDef objectDef,
                                 @Nullable ClassTypeDef outerType,
                                 VisitorContext context,
                                 Element[] originatingElements) throws Exception {
        Map<String, byte[]> produced;
        try {
            ByteCodeWriter byteCodeWriter = new ByteCodeWriter(sourcePath(context), classPath(context));
            produced = byteCodeWriter.writeAll(objectDef, outerType);
            if (!produced.containsKey(objectDef.getName())) {
                throw new IllegalStateException("The JDK compiler did not produce '" + objectDef.getName() + "'");
            }
        } catch (Exception e) {
            if (outerType != null) {
                // The enclosing class has already been emitted, so its member can no longer be folded
                // back into a source file. Report the failure instead of writing an unusable source.
                throw new ProcessingException(
                    originatingElements.length > 0 ? originatingElements[0] : null,
                    "Failed to generate '" + objectDef.getName() + "': " + e.getMessage(),
                    e
                );
            }
            Element element = originatingElements.length > 0 ? originatingElements[0] : null;
            context.warn("The JDK bytecode backend could not emit '" + objectDef.getName()
                + "' as a class file and generated Java source instead: " + e.getMessage(), element);
            writeSource(objectDef, context, originatingElements, e);
            return false;
        }
        writeClass(objectDef.getName(), produced.get(objectDef.getName()), context, originatingElements);
        // The source fallback compiles member types together with their enclosing type. Emit
        // those classfiles now and stop the traversal from generating them a second time, where
        // sibling members would no longer be resolvable.
        String memberPrefix = objectDef.getName() + "$";
        boolean membersWritten = false;
        for (Map.Entry<String, byte[]> entry : produced.entrySet()) {
            if (entry.getKey().startsWith(memberPrefix)) {
                writeClass(entry.getKey(), entry.getValue(), context, originatingElements);
                membersWritten = true;
            }
        }
        return !membersWritten;
    }

    private static void writeSource(ObjectDef objectDef,
                                    VisitorContext context,
                                    Element[] originatingElements,
                                    Exception bytecodeFailure) {
        var generatedFile = context.visitGeneratedSourceFile(
            objectDef.getPackageName(), objectDef.getSimpleName(), originatingElements
        );
        if (generatedFile.isEmpty()) {
            throw new ProcessingException(
                originatingElements.length > 0 ? originatingElements[0] : null,
                "Failed to generate '" + objectDef.getName() + "': " + bytecodeFailure.getMessage(),
                bytecodeFailure
            );
        }
        try {
            generatedFile.get().write(writer -> SOURCE_GENERATOR.write(objectDef, writer));
        } catch (Exception sourceFailure) {
            Element element = originatingElements.length > 0 ? originatingElements[0] : null;
            throw new ProcessingException(element,
                "Failed to generate '" + objectDef.getName() + "': " + bytecodeFailure.getMessage(),
                sourceFailure);
        }
    }

    private static List<Path> sourcePath(VisitorContext context) {
        Set<Path> paths = new LinkedHashSet<>();
        context.getProjectDir().ifPresent(projectDir -> {
            paths.add(projectDir);
            paths.add(projectDir.resolve("src/main/java"));
            paths.add(projectDir.resolve("src/test/java"));
        });
        addConfigured(context, SOURCE_PATH_OPTION, paths);
        return existing(paths);
    }

    private static void addConfigured(VisitorContext context, String option, Set<Path> paths) {
        String configured = context.getOptions().get(option);
        if (configured != null && !configured.isBlank()) {
            for (String entry : configured.split(Pattern.quote(File.pathSeparator), -1)) {
                if (!entry.isBlank()) {
                    paths.add(Path.of(entry.trim()).toAbsolutePath().normalize());
                }
            }
        }
    }

    private static List<Path> classPath(VisitorContext context) {
        Set<Path> paths = new LinkedHashSet<>();
        addConfigured(context, CLASS_PATH_OPTION, paths);
        context.getClassesOutputPath().ifPresent(paths::add);
        for (String resource : List.of(
            "META-INF/MANIFEST.MF",
            "io/micronaut/core/annotation/Introspected.class",
            "io/micronaut/inject/ast/ClassElement.class",
            "io/micronaut/sourcegen/model/ObjectDef.class",
            "org/jspecify/annotations/Nullable.class"
        )) {
            for (URL url : context.getClasspathResources(resource)) {
                toClasspathRoot(url, resource).ifPresent(paths::add);
            }
        }
        return existing(paths);
    }

    private static List<Path> existing(Set<Path> paths) {
        return paths.stream().filter(path -> path.toFile().exists()).toList();
    }

    private static java.util.Optional<Path> toClasspathRoot(URL url, String resource) {
        try {
            if (url.openConnection() instanceof JarURLConnection jar) {
                return java.util.Optional.of(Path.of(jar.getJarFileURL().toURI()));
            }
            if ("file".equals(url.getProtocol())) {
                Path path = Path.of(url.toURI());
                int segments = resource.split("/").length;
                for (int i = 0; i < segments; i++) {
                    Path parent = path.getParent();
                    if (parent == null) {
                        return java.util.Optional.empty();
                    }
                    path = parent;
                }
                return java.util.Optional.of(path);
            }
        } catch (Exception ignored) {
            // A non-file classpath resource cannot be supplied to javac.
        }
        return java.util.Optional.empty();
    }

    private static void writeClass(String name,
                                   byte[] bytes,
                                   VisitorContext context,
                                   Element[] originatingElements) throws Exception {
        try (OutputStream output = context.visitClass(name, originatingElements)) {
            output.write(bytes);
        }
    }
}
