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

import io.micronaut.sourcegen.bytecode.tck.GeneratedClassLoader;
import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.KotlinPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * The backends of the differential test: writes the models with each of the four backends, loads
 * the written classes and runs them on the inputs of each model.
 */
final class DiffBackends {

    enum Backend { JAVA, KOTLIN, ASM, JDK }

    /** One generated program: a public class with a public instance method {@code run}. */
    record Model(String simpleName, ClassDef def, Class<?>[] parameterTypes, List<Object[]> inputs, String description) {
        String name() {
            return def.getName();
        }
    }

    /** What one backend made of one model. */
    static final class Result {
        String failure; // null when the class loaded; otherwise "COMPILE: ...", "VERIFY: ...", "CRASH: ...", "DECLINED"
        List<String> outcomes = new ArrayList<>();
        String source;
        String signature; // non-null when the run method has another descriptor than the model's

        boolean declined() {
            return "DECLINED".equals(failure);
        }

        String summary() {
            return failure != null ? failure.split("\n", 2)[0] : String.join(" | ", outcomes);
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "diff-runner");
        t.setDaemon(true);
        return t;
    });

    private DiffBackends() {
    }

    static Map<Backend, Map<String, Result>> runAll(List<Model> models) throws IOException {
        Path workDir = Files.createTempDirectory("differential");
        try {
            Map<Backend, Map<String, Result>> all = new LinkedHashMap<>();
            all.put(Backend.JAVA, runJava(models, workDir));
            all.put(Backend.KOTLIN, runKotlin(models, workDir));
            all.put(Backend.ASM, runBytecode(models, false));
            all.put(Backend.JDK, runBytecode(models, true));
            return all;
        } finally {
            try (Stream<Path> paths = Files.walk(workDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Java

    static Map<String, Result> runJava(List<Model> models, Path workDir) {
        Map<String, Result> results = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (Model model : models) {
            Result result = new Result();
            results.put(model.name(), result);
            try {
                StringWriter writer = new StringWriter();
                new JavaPoetSourceGenerator().write(model.def(), writer);
                result.source = writer.toString();
                if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(result.source)) {
                    result.failure = "COMPILE: source is not encodable as UTF-8 (unpaired surrogate)";
                    continue;
                }
                sources.put(model.name(), result.source);
            } catch (Throwable e) {
                result.failure = "CRASH: " + e;
            }
        }
        Map<String, List<String>> errors = new HashMap<>();
        Path output = compileJavaUntilClean(sources, errors, workDir);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, DiffBackends.class.getClassLoader())) {
            for (Model model : models) {
                Result result = results.get(model.name());
                if (result.failure != null) {
                    continue;
                }
                if (errors.containsKey(model.name())) {
                    result.failure = "COMPILE: " + String.join(" ;; ", errors.get(model.name()));
                    continue;
                }
                execute(model, result, () -> loader.loadClass(model.name()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return results;
    }

    private static Path compileJavaUntilClean(Map<String, String> sources, Map<String, List<String>> errors, Path workDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        for (int attempt = 0; attempt < 30; attempt++) {
            Path output;
            try {
                output = Files.createTempDirectory(workDir, "java");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (remaining.isEmpty()) {
                return output;
            }
            List<JavaFileObject> files = new ArrayList<>();
            for (var entry : remaining.entrySet()) {
                files.add(new StringSource(entry.getKey(), entry.getValue()));
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
                boolean ok = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none", "-Xlint:none", "-nowarn", "-Xmaxerrs", "100000"), null, files).call();
                if (ok) {
                    return output;
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            boolean attributed = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) {
                    continue;
                }
                String name = ((StringSource) d.getSource()).qualifiedName;
                errors.computeIfAbsent(name, k -> new ArrayList<>()).add("line " + d.getLineNumber() + ": " + d.getMessage(null).replace('\n', ' '));
                remaining.remove(name);
                attributed = true;
            }
            if (!attributed) {
                throw new IllegalStateException("javac failed without attributable errors: " + diagnostics.getDiagnostics());
            }
        }
        throw new IllegalStateException("javac did not converge");
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String qualifiedName;
        private final String source;

        private StringSource(String qualifiedName, String source) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.qualifiedName = qualifiedName;
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    // ------------------------------------------------------------------ Kotlin

    static Map<String, Result> runKotlin(List<Model> models, Path workDir) {
        Map<String, Result> results = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (Model model : models) {
            Result result = new Result();
            results.put(model.name(), result);
            try {
                StringWriter writer = new StringWriter();
                new KotlinPoetSourceGenerator().write(model.def(), writer);
                result.source = writer.toString();
                if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(result.source)) {
                    result.failure = "COMPILE: source is not encodable as UTF-8 (unpaired surrogate)";
                    continue;
                }
                sources.put(model.name(), result.source);
            } catch (Throwable e) {
                result.failure = "CRASH: " + e;
            }
        }
        Map<String, List<String>> errors = new HashMap<>();
        Path output = compileKotlinUntilClean(sources, errors, workDir);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, DiffBackends.class.getClassLoader())) {
            for (Model model : models) {
                Result result = results.get(model.name());
                if (result.failure != null) {
                    continue;
                }
                if (errors.containsKey(model.name())) {
                    result.failure = "COMPILE: " + String.join(" ;; ", errors.get(model.name()));
                    continue;
                }
                execute(model, result, () -> loader.loadClass(model.name()));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return results;
    }

    private static Path compileKotlinUntilClean(Map<String, String> sources, Map<String, List<String>> errors, Path workDir) {
        Map<String, String> remaining = new LinkedHashMap<>(sources);
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                Path sourceDir = Files.createTempDirectory(workDir, "kotlin-src");
                Path outputDir = Files.createTempDirectory(workDir, "kotlin-out");
                if (remaining.isEmpty()) {
                    return outputDir;
                }
                Map<String, String> fileToModel = new HashMap<>();
                for (var entry : remaining.entrySet()) {
                    Path file = sourceDir.resolve(entry.getKey().replace('.', '_') + ".kt");
                    Files.writeString(file, entry.getValue());
                    fileToModel.put(file.toString(), entry.getKey());
                    fileToModel.put(file.toRealPath().toString(), entry.getKey());
                }
                List<String> unattributed = new ArrayList<>();
                Map<String, List<String>> found = new HashMap<>();
                MessageCollector collector = new MessageCollector() {
                    @Override
                    public void clear() {
                    }

                    @Override
                    public boolean hasErrors() {
                        return !found.isEmpty() || !unattributed.isEmpty();
                    }

                    @Override
                    public void report(CompilerMessageSeverity severity, String message, CompilerMessageSourceLocation location) {
                        if (!severity.isError()) {
                            return;
                        }
                        String model = location == null ? null : fileToModel.get(location.getPath());
                        if (model == null && location != null) {
                            try {
                                model = fileToModel.get(Path.of(location.getPath()).toRealPath().toString());
                            } catch (IOException ignored) {
                                // keep null
                            }
                        }
                        if (model == null) {
                            unattributed.add(message);
                        } else {
                            found.computeIfAbsent(model, k -> new ArrayList<>()).add("line " + location.getLine() + ": " + message.replace('\n', ' '));
                        }
                    }
                };
                K2JVMCompilerArguments arguments = new K2JVMCompilerArguments();
                arguments.setFreeArgs(List.of(sourceDir.toString()));
                arguments.setDestination(outputDir.toString());
                arguments.setClasspath(System.getProperty("java.class.path"));
                arguments.setNoStdlib(true);
                arguments.setNoReflect(true);
                arguments.setJvmTarget("17");
                arguments.setSuppressWarnings(true);
                ExitCode exitCode = new K2JVMCompiler().exec(collector, Services.EMPTY, arguments);
                if (exitCode == ExitCode.OK && found.isEmpty()) {
                    return outputDir;
                }
                if (found.isEmpty()) {
                    throw new IllegalStateException("kotlinc failed without attributable errors: " + unattributed);
                }
                for (var entry : found.entrySet()) {
                    errors.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                    remaining.remove(entry.getKey());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("kotlinc did not converge");
    }

    // ------------------------------------------------------------------ bytecode

    static Map<String, Result> runBytecode(List<Model> models, boolean jdk) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (Model model : models) {
            Result result = new Result();
            results.put(model.name(), result);
            byte[] bytes;
            try {
                if (jdk) {
                    Optional<byte[]> direct = new JdkClassFileWriter(false).write(model.def(), null);
                    if (direct.isEmpty()) {
                        result.failure = "DECLINED";
                        continue;
                    }
                    bytes = direct.get();
                } else {
                    bytes = new io.micronaut.sourcegen.bytecode.ByteCodeWriter(false, true).write(model.def());
                }
            } catch (Throwable e) {
                result.failure = "CRASH: " + e;
                continue;
            }
            try {
                List<VerifyError> verifyErrors = ClassFile.of().verify(bytes);
                if (!verifyErrors.isEmpty()) {
                    result.failure = "VERIFY: " + verifyErrors.getFirst().getMessage();
                    continue;
                }
            } catch (Throwable e) {
                result.failure = "VERIFY: " + e;
                continue;
            }
            GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(model.name(), bytes));
            execute(model, result, () -> loader.loadClass(model.name()));
        }
        return results;
    }

    // ------------------------------------------------------------------ execution

    interface ClassSupplier {
        Class<?> load() throws Exception;
    }

    private static void execute(Model model, Result result, ClassSupplier supplier) {
        Class<?> cls;
        Object instance;
        Method method;
        try {
            cls = supplier.load();
            instance = cls.getConstructor().newInstance();
            Method found;
            try {
                found = cls.getMethod("run", model.parameterTypes());
            } catch (NoSuchMethodException e) {
                // A backend that writes another descriptor (Kotlin Int for Integer): still compare behaviour
                found = Arrays.stream(cls.getMethods()).filter(mm -> mm.getName().equals("run")
                    && mm.getParameterCount() == model.parameterTypes().length).findFirst().orElseThrow(() -> e);
                result.signature = Arrays.toString(found.getParameterTypes()) + found.getReturnType().getSimpleName();
            }
            method = found;
        } catch (Throwable e) {
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            result.failure = "LOAD: " + cause;
            return;
        }
        for (Object[] input : model.inputs()) {
            Future<String> future = EXECUTOR.submit(() -> {
                try {
                    return "=" + describe(method.invoke(instance, input.clone()));
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    return "!" + cause.getClass().getName() + (cause instanceof VerifyError || cause instanceof LinkageError ? ": " + cause.getMessage() : "");
                } catch (Throwable e) {
                    return "!!" + e;
                }
            });
            String outcome;
            try {
                outcome = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                outcome = "TIMEOUT";
            } catch (Exception e) {
                outcome = "!!" + e;
            }
            result.outcomes.add(outcome);
        }
    }

    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return type.getSimpleName() + Arrays.deepToString(new Object[]{value});
        }
        if (value instanceof java.util.function.Function<?, ?> || value instanceof java.util.function.Supplier<?>) {
            return "lambda";
        }
        return type.getSimpleName() + ":" + value;
    }
}
