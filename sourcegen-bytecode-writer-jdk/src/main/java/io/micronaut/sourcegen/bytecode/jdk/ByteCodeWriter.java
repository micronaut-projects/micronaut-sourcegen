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

import io.micronaut.core.annotation.Experimental;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.JarURLConnection;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;

/**
 * Writes Sourcegen definitions using the JDK 25 classfile toolchain.
 *
 * <p>The JDK backend lowers supported definitions directly with the JDK 25 ClassFile API, including
 * stack maps and invokedynamic call sites. Definitions that require a source-level compatibility
 * lowering use an in-memory Java 17 compiler fallback. This is deliberately kept in this JDK-only
 * artifact and introduces no ASM into its runtime graph.</p>
 *
 * <p>The resulting class is normalized through the JDK ClassFile API, pinned to Java 17, and verified
 * before it is returned.</p>
 *
 * @author Graeme Rocher
 * @since 2.2
 */
@Experimental
public final class ByteCodeWriter {

    private final List<Path> sourcePath;
    private final List<Path> classPath;
    private final boolean verify;
    @Nullable
    private final CompilationTypes compilationTypes;

    /**
     * Creates a writer using the current runtime class path.
     */
    public ByteCodeWriter() {
        this(List.of(), List.of(), true);
    }

    /**
     * Creates a writer with the paths visible to an annotation-processing invocation.
     *
     * @param sourcePath Source roots containing types still being compiled
     * @param classPath Classpath entries containing already compiled types
     */
    @io.micronaut.core.annotation.Internal
    public ByteCodeWriter(List<Path> sourcePath, List<Path> classPath) {
        this(sourcePath, classPath, true);
    }

    /**
     * Creates a writer with explicit paths and verification policy.
     *
     * @param sourcePath Source roots containing types still being compiled
     * @param classPath Classpath entries containing already compiled types
     * @param verify Whether to run ClassFile verification
     */
    @io.micronaut.core.annotation.Internal
    public ByteCodeWriter(List<Path> sourcePath, List<Path> classPath, boolean verify) {
        this(sourcePath, classPath, verify, null);
    }

    /**
     * Creates a writer that can resolve types of the compilation it is running in.
     *
     * <p>A generated class often references types that are still being compiled and so have no
     * class file to read. Supplying the current compilation's type lookup, such as
     * {@code visitorContext::getClassElement}, lets the writer resolve their hierarchy when it
     * computes stack maps.</p>
     *
     * @param sourcePath Source roots containing types still being compiled
     * @param classPath Classpath entries containing already compiled types
     * @param verify Whether to run ClassFile verification
     * @param compilationTypes Looks up a type of the current compilation by binary name
     */
    @io.micronaut.core.annotation.Internal
    public ByteCodeWriter(List<Path> sourcePath,
                          List<Path> classPath,
                          boolean verify,
                          @Nullable CompilationTypes compilationTypes) {
        this.sourcePath = List.copyOf(sourcePath);
        this.classPath = List.copyOf(classPath);
        this.verify = verify;
        this.compilationTypes = compilationTypes;
    }

    /**
     * Writes a top-level definition.
     *
     * @param objectDef The object definition
     * @return Java 17 classfile bytes
     */
    public byte[] write(ObjectDef objectDef) {
        return write(objectDef, null);
    }

    /**
     * Writes a definition which is a member of the supplied outer type.
     *
     * @param objectDef  The object definition
     * @param outerType  The outer type, or {@code null} for a top-level definition
     * @return Java 17 classfile bytes
     */
    public byte[] write(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        Map<String, byte[]> all = writeAll(objectDef, outerType);
        byte[] bytes = all.get(objectDef.getName());
        if (bytes == null) {
            throw new IllegalStateException("The JDK compiler did not produce '" + objectDef.getName() + "'");
        }
        return bytes;
    }

    /**
     * Writes a definition and all member classes produced for it.
     *
     * @param objectDef The object definition
     * @return classfile bytes keyed by binary class name
     * @since 2.2
     */
    @io.micronaut.core.annotation.Internal
    public Map<String, byte[]> writeAll(ObjectDef objectDef) {
        return writeAll(objectDef, null);
    }

    /**
     * Writes a definition and all member classes produced for it.
     *
     * @param objectDef The object definition
     * @param outerType The outer type, or {@code null} for a top-level definition
     * @return classfile bytes keyed by binary class name
     * @since 2.2
     */
    @io.micronaut.core.annotation.Internal
    public Map<String, byte[]> writeAll(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        java.util.Optional<byte[]> direct = new JdkClassFileWriter(verify, compilationTypes).write(objectDef, outerType);
        if (direct.isPresent()) {
            // Member types are separate class files; each one picks its own direct or fallback path
            Map<String, byte[]> result = new LinkedHashMap<>();
            result.put(objectDef.getName(), direct.get());
            for (ObjectDef inner : objectDef.getInnerTypes()) {
                result.putAll(writeAll(inner, objectDef.asTypeDef()));
            }
            return result;
        }
        Compilation compilation = compileFallback(objectDef, outerType);
        ObjectDef sourceRoot = compilation.sourceRoot();
        Map<String, byte[]> compiled = compilation.classes();
        Map<String, ObjectDef> definitions = new LinkedHashMap<>();
        collectDefinitions(sourceRoot, definitions);
        Set<ClassElement> classElements = new LinkedHashSet<>();
        definitions.values().forEach(definition -> classElements.addAll(
            SourcegenClassHierarchyResolver.classElements(definition)
        ));
        // Only the requested definition and its own members are returned; the enclosing type or
        // sibling members compiled alongside it are emitted by their own traversal step.
        String memberPrefix = objectDef.getName() + "$";
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            String name = entry.getKey();
            if (!name.equals(objectDef.getName()) && !name.startsWith(memberPrefix)) {
                continue;
            }
            result.put(name, normalizeAndVerify(
                entry.getValue(), compiled, definitions.get(name), classElements, definitions.values(), verify,
                compilationTypes
            ));
        }
        return result;
    }

    private Compilation compileFallback(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        if (outerType == null) {
            return new Compilation(objectDef, compile(renderSources(objectDef)));
        }
        // A member is compiled inside its real enclosing definition when the outer type carries
        // one, so that references to the outer type's members resolve. A bare outer name only
        // allows an empty stub, which is kept as the last resort.
        ObjectDef enclosing = enclosingDefinition(objectDef, outerType);
        if (enclosing != null) {
            try {
                return new Compilation(enclosing, compile(renderSources(enclosing)));
            } catch (IllegalStateException e) {
                // Fall through to the stub
            }
        }
        ObjectDef stub = wrapInnerType(objectDef, outerType);
        return new Compilation(stub, compile(renderSources(stub)));
    }

    @Nullable
    private static ObjectDef enclosingDefinition(ObjectDef objectDef, ClassTypeDef outerType) {
        if (outerType instanceof ClassTypeDef.ClassDefType outerDef
            && outerDef.objectDef().getInnerTypes().stream()
                .anyMatch(inner -> inner.getName().equals(objectDef.getName()))) {
            return outerDef.objectDef();
        }
        return null;
    }

    private static void collectDefinitions(ObjectDef definition, Map<String, ObjectDef> definitions) {
        definitions.put(definition.getName(), definition);
        definition.getInnerTypes().forEach(inner -> collectDefinitions(inner, definitions));
    }

    private static ObjectDef wrapInnerType(ObjectDef objectDef, ClassTypeDef outerType) {
        String outerName = outerType.getName();
        if (outerType.isInterface()) {
            return InterfaceDef.builder(outerName).addInnerType(objectDef).build();
        }
        return ClassDef.builder(outerName).addInnerType(objectDef).build();
    }

    private static Map<String, String> renderSources(ObjectDef sourceRoot) {
        LinkedHashMap<String, ObjectDef> definitions = new LinkedHashMap<>();
        Set<String> embedded = new LinkedHashSet<>();
        collectEmbedded(sourceRoot, embedded);
        collectDefinition(sourceRoot, definitions, embedded);
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (ObjectDef definition : definitions.values()) {
            StringWriter writer = new StringWriter();
            try {
                SourceGeneratorHolder.INSTANCE.write(definition, writer);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to render '" + definition.getName() + "'", e);
            }
            sources.put(definition.getName(), writer.toString());
        }
        return sources;
    }

    private static void collectDefinition(ObjectDef definition,
                                          Map<String, ObjectDef> definitions,
                                          Set<String> embedded) {
        if (!embedded.contains(definition.getName())) {
            definitions.putIfAbsent(definition.getName(), definition);
            collectReferencedTypes(definition, definitions, embedded);
        } else {
            collectReferencedTypes(definition, definitions, embedded);
        }
    }

    private static void collectEmbedded(ObjectDef definition, Set<String> embedded) {
        for (ObjectDef innerType : definition.getInnerTypes()) {
            embedded.add(innerType.getName());
            collectEmbedded(innerType, embedded);
        }
    }

    private static void collectReferencedTypes(ObjectDef definition,
                                               Map<String, ObjectDef> definitions,
                                               Set<String> embedded) {
        for (TypeDef type : definition.getSuperinterfaces()) {
            collectType(type, definitions, embedded);
        }
        if (definition instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            collectType(classDef.getSuperclass(), definitions, embedded);
        }
        if (definition instanceof ClassDef classDef) {
            classDef.getFields().forEach(field -> collectType(field.getType(), definitions, embedded));
            classDef.getFields().forEach(field -> field.getInitializer()
                .ifPresent(expression -> collectExpressions(expression, definitions, embedded)));
        }
        definition.getProperties().forEach(property -> collectType(property.getType(), definitions, embedded));
        if (definition instanceof EnumDef enumDef) {
            enumDef.getFields().forEach(field -> collectType(field.getType(), definitions, embedded));
        }
        for (MethodDef method : definition.getMethods()) {
            collectType(method.getReturnType(), definitions, embedded);
            method.getParameters().forEach(parameter -> collectType(parameter.getType(), definitions, embedded));
            method.getThrowTypes().forEach(type -> collectType(type, definitions, embedded));
            method.getTypeVariables().forEach(variable -> variable.bounds().forEach(type -> collectType(type, definitions, embedded)));
            method.getStatements().forEach(statement -> collectStatements(statement, definitions, embedded));
        }
        if (definition instanceof ClassDef classDef && classDef.getStaticInitializer() != null) {
            collectStatements(classDef.getStaticInitializer(), definitions, embedded);
        }
        for (ObjectDef innerType : definition.getInnerTypes()) {
            collectReferencedTypes(innerType, definitions, embedded);
        }
    }

    private static void collectStatements(StatementDef statement,
                                          Map<String, ObjectDef> definitions,
                                          Set<String> embedded) {
        statement.nestedExpressionsStream().forEach(expression -> {
            collectExpressions(expression, definitions, embedded);
            collectType(expression.type(), definitions, embedded);
        });
    }

    private static void collectExpressions(io.micronaut.sourcegen.model.ExpressionDef expression,
                                           Map<String, ObjectDef> definitions,
                                           Set<String> embedded) {
        collectType(expression.type(), definitions, embedded);
        expression.nestedExpressionsStream().forEach(child -> collectExpressions(child, definitions, embedded));
    }

    private static void collectType(TypeDef type,
                                    Map<String, ObjectDef> definitions,
                                    Set<String> embedded) {
        switch (type) {
            case ClassTypeDef.ClassDefType classDefType -> collectDefinition(classDefType.objectDef(), definitions, embedded);
            case ClassTypeDef.Parameterized parameterized -> {
                collectType(parameterized.rawType(), definitions, embedded);
                parameterized.typeArguments().forEach(argument -> collectType(argument, definitions, embedded));
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> collectType(annotated.typeDef(), definitions, embedded);
            case TypeDef.AnnotatedTypeDef annotated -> collectType(annotated.typeDef(), definitions, embedded);
            case TypeDef.Array array -> collectType(array.componentType(), definitions, embedded);
            case TypeDef.TypeVariable variable -> variable.bounds().forEach(bound -> collectType(bound, definitions, embedded));
            case TypeDef.Wildcard wildcard -> {
                wildcard.upperBounds().forEach(bound -> collectType(bound, definitions, embedded));
                wildcard.lowerBounds().forEach(bound -> collectType(bound, definitions, embedded));
            }
            case ClassTypeDef _, TypeDef.Primitive _ -> {
            }
        }
    }

    private Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = Tooling.COMPILER;
        if (compiler == null) {
            throw new IllegalStateException("The JDK backend requires a full JDK 25, including javac");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = compilerOptions();
        List<JavaFileObject> sourceFiles = sources.entrySet().stream()
            .map(entry -> new SourceFile(entry.getKey(), entry.getValue()))
            .map(JavaFileObject.class::cast)
            .toList();
        // The standard file manager holds the opened class-path archives; reusing it across the
        // classes of one compilation is what keeps a per-class javac task affordable.
        StandardJavaFileManager standard = Tooling.fileManager(compiler, options);
        synchronized (standard) {
            MemoryFileManager fileManager = new MemoryFileManager(standard);
            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, sourceFiles).call();
            if (!Boolean.TRUE.equals(success)) {
                throw new IllegalStateException(formatDiagnostics(diagnostics));
            }
            return fileManager.bytes();
        }
    }

    private List<String> compilerOptions() {
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add("17");
        options.add("-proc:none");
        // Explicitly supplied entries (the compilation's own class path) take precedence over
        // whatever the processor's class loader and the JVM's class path happen to expose.
        String runtimeClassPath = System.getProperty("java.class.path", "");
        String combinedClassPath = Stream.concat(
                Stream.concat(classPath.stream(), Tooling.DISCOVERED_CLASS_PATH.stream()).map(Path::toString),
                Stream.of(runtimeClassPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            )
            .filter(entry -> !entry.isEmpty())
            .distinct()
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        options.add("-classpath");
        options.add(combinedClassPath);
        if (!sourcePath.isEmpty()) {
            options.add("-sourcepath");
            options.add(sourcePath.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
        }
        return options;
    }

    private static List<Path> discoveredClassPath() {
        Set<Path> result = new LinkedHashSet<>();
        ClassLoader loader = ByteCodeWriter.class.getClassLoader();
        try {
            var resources = loader.getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (url.openConnection() instanceof JarURLConnection jar) {
                    result.add(Path.of(jar.getJarFileURL().toURI()));
                } else if ("file".equals(url.getProtocol())) {
                    Path manifest = Path.of(url.toURI());
                    Path classes = manifest.getParent();
                    if (classes != null && classes.getParent() != null) {
                        result.add(classes.getParent());
                    }
                }
            }
        } catch (Exception ignored) {
            // The runtime class path remains usable when a classloader does not expose manifests.
        }
        return List.copyOf(result);
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder message = new StringBuilder("JDK compilation of generated source failed:");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            message.append('\n').append(diagnostic.getKind()).append(" at ")
                .append(diagnostic.getLineNumber()).append(':').append(diagnostic.getColumnNumber())
                .append(" - ").append(diagnostic.getMessage(null));
        }
        return message.toString();
    }

    private static byte[] normalizeAndVerify(byte[] bytes,
                                             Map<String, byte[]> compiled,
                                             @Nullable ObjectDef objectDef,
                                             Set<ClassElement> classElements,
                                             Collection<ObjectDef> objectDefs,
                                             boolean verify,
                                             @Nullable CompilationTypes compilationTypes) {
        ClassFile classFile = ClassFile.of(
            ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
            ClassFile.ClassHierarchyResolverOption.of(
                new SourcegenClassHierarchyResolver(
                    compiled, classElements, objectDefs, ByteCodeWriter.class.getClassLoader(), compilationTypes
                )
            )
        );
        byte[] normalized = bytes;
        if (objectDef != null && objectDef.getMethods().stream().anyMatch(ByteCodeWriter::hasParameterAnnotations)) {
            normalized = classFile.transformClass(
                classFile.parse(normalized),
                parameterAnnotationsTransform(objectDef)
            );
        }
        if (verify) {
            List<VerifyError> errors = classFile.verify(normalized);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("JDK verification failed: " + errors);
            }
        }
        return normalized;
    }

    private static boolean hasParameterAnnotations(MethodDef method) {
        return method.getParameters().stream().anyMatch(parameter -> !parameter.getAnnotations().isEmpty());
    }

    /**
     * Restores the parameter annotations of the model onto a class the source fallback compiled.
     * Each method is looked up by its own name and descriptor as it is transformed, so nothing is
     * carried between the decision to transform a method and the transformation itself.
     */
    private static ClassTransform parameterAnnotationsTransform(ObjectDef objectDef) {
        Map<String, List<List<Annotation>>> annotations = new LinkedHashMap<>();
        for (MethodDef method : objectDef.getMethods()) {
            List<List<Annotation>> parameters = method.getParameters().stream()
                .map(parameter -> parameter.getAnnotations().stream().map(ByteCodeWriter::toAnnotation).toList())
                .toList();
            if (parameters.stream().anyMatch(parameter -> !parameter.isEmpty())) {
                annotations.put(method.getName() + descriptor(objectDef, method), parameters);
            }
        }
        return (builder, element) -> {
            List<List<Annotation>> methodAnnotations = element instanceof MethodModel method
                ? annotations.get(method.methodName().stringValue() + method.methodType().stringValue())
                : null;
            if (methodAnnotations == null
                || ((MethodModel) element).attributes().stream()
                    .anyMatch(RuntimeVisibleParameterAnnotationsAttribute.class::isInstance)) {
                builder.with(element);
                return;
            }
            builder.transformMethod((MethodModel) element, MethodTransform.endHandler(method ->
                method.with(RuntimeVisibleParameterAnnotationsAttribute.of(methodAnnotations))));
        };
    }

    private static String descriptor(ObjectDef objectDef, MethodDef method) {
        return io.micronaut.sourcegen.bytecode.core.TypeUtils.getMethodDescriptor(objectDef, method);
    }

    static Annotation toAnnotation(io.micronaut.sourcegen.model.AnnotationDef annotation) {
        List<AnnotationElement> elements = annotation.getValues().entrySet().stream()
            .map(entry -> AnnotationElement.of(entry.getKey(), toAnnotationValue(entry.getValue())))
            .toList();
        return Annotation.of(ClassDesc.of(annotation.getType().getName()), elements);
    }

    static AnnotationValue toAnnotationValue(Object value) {
        if (value instanceof io.micronaut.sourcegen.model.ExpressionDef.Constant constant) {
            Object constantValue = constant.value();
            if (constantValue == null) {
                throw new IllegalArgumentException("Annotation value cannot be null");
            }
            return toAnnotationValue(constantValue);
        }
        if (value instanceof VariableDef.StaticField field) {
            return AnnotationValue.ofEnum(ClassDesc.of(field.ownerType().getName()), field.name());
        }
        if (value instanceof io.micronaut.sourcegen.model.AnnotationDef annotation) {
            return AnnotationValue.ofAnnotation(toAnnotation(annotation));
        }
        if (value instanceof ClassTypeDef type) {
            return AnnotationValue.ofClass(ClassDesc.ofDescriptor(
                io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(type, null)
            ));
        }
        if (value instanceof TypeDef type) {
            return AnnotationValue.ofClass(ClassDesc.ofDescriptor(
                io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(type, null)
            ));
        }
        if (value instanceof Class<?> type) {
            return AnnotationValue.ofClass(ClassDesc.ofDescriptor(type.descriptorString().replace('.', '/')));
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<AnnotationValue> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(toAnnotationValue(java.lang.reflect.Array.get(value, i)));
            }
            return AnnotationValue.ofArray(values);
        }
        if (value instanceof Enum<?> anEnum) {
            return AnnotationValue.ofEnum(ClassDesc.of(anEnum.getDeclaringClass().getName()), anEnum.name());
        }
        if (value instanceof java.util.Collection<?> values) {
            return AnnotationValue.ofArray(values.stream().map(ByteCodeWriter::toAnnotationValue).toList());
        }
        return AnnotationValue.of(value);
    }

    /**
     * The source generator is only needed when a definition falls back to compiling Java source.
     * Holding it here keeps that dependency off the class-initialization path, so a caller whose
     * definitions all lower directly never has to have the source generator available.
     */
    private static final class SourceGeneratorHolder {
        private static final JavaPoetSourceGenerator INSTANCE = create();

        private static JavaPoetSourceGenerator create() {
            try {
                return new JavaPoetSourceGenerator();
            } catch (LinkageError e) {
                throw new IllegalStateException("This definition cannot be lowered directly and needs the Java "
                    + "source fallback, which requires 'micronaut-sourcegen-generator-java' on the class path", e);
            }
        }
    }

    private record Compilation(ObjectDef sourceRoot, Map<String, byte[]> classes) {
    }

    /**
     * Compiler and class-path discovery are shared by every writer instance: both are stable for the
     * lifetime of the JVM and scanning the manifests on each generated class is measurable.
     */
    private static final class Tooling {
        @Nullable
        static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
        static final List<Path> DISCOVERED_CLASS_PATH = discoveredClassPath();
        private static final Map<String, StandardJavaFileManager> FILE_MANAGERS = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * One standard file manager per distinct set of compiler options. The options carry the
         * class and source paths, so a manager is only ever asked about one configuration, and
         * javac's archive cache inside it is shared by every class compiled with that configuration.
         */
        static StandardJavaFileManager fileManager(JavaCompiler compiler, List<String> options) {
            return FILE_MANAGERS.computeIfAbsent(String.join("\n", options),
                key -> compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8));
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        private SourceFile(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> output = new LinkedHashMap<>();

        private MemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            output.put(className, stream);
            return new SimpleJavaFileObject(
                URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind
            ) {
                @Override
                public ByteArrayOutputStream openOutputStream() {
                    return stream;
                }
            };
        }

        private Map<String, byte[]> bytes() {
            Map<String, byte[]> result = new LinkedHashMap<>();
            output.forEach((name, bytes) -> result.put(name, bytes.toByteArray()));
            return result;
        }
    }
}
