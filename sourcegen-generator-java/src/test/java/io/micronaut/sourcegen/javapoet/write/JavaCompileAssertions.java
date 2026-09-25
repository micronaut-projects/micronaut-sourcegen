package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Renders definitions with the Java generator and compiles the sources, so that a test can assert the output is
 * not only well formed but also valid Java, and run it. The helpers the test classes share live here.
 */
final class JavaCompileAssertions {

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
        "(?m)^(?:public )?(?:final |abstract )?(?:class|interface|enum|record) (\\S+?)[\\s(<{]");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^package (\\S+);");

    private JavaCompileAssertions() {
    }

    /**
     * Compiles the given sources together and fails the test if compilation reports any error.
     *
     * @param sources The Java sources
     */
    static void assertCompiles(String... sources) {
        try (var ignored = compileAndLoad(sources)) {
            // Compilation is sufficient for callers that only verify source validity.
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compiles sources and exposes the generated classes for behavioral assertions. */
    static URLClassLoader compileAndLoad(String... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("No system Java compiler available");
        }
        List<JavaFileObject> files = new ArrayList<>(sources.length);
        for (String source : sources) {
            files.add(new StringSource(qualifiedName(source), source));
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output;
        try {
            output = Files.createTempDirectory("sourcegen-compile");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
            boolean success = compiler.getTask(null, fileManager, diagnostics, null, null, files).call();
            if (!success) {
                fail("Generated source does not compile:\n"
                    + diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"))
                    + "\n\n" + String.join("\n\n", sources));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try {
            return new URLClassLoader(new URL[]{output.toUri().toURL()}, JavaCompileAssertions.class.getClassLoader());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Renders a definition with the Java generator.
     *
     * @param definition The definition
     * @return The Java source
     */
    static String render(ObjectDef definition) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(definition, writer);
            return writer.toString();
        }
    }

    /**
     * Renders a definition and asserts the source is the expected one.
     *
     * @param definition The definition
     * @param expected The expected source
     * @return The source
     */
    static String assertSource(ObjectDef definition, String expected) throws IOException {
        String source = render(definition);
        assertEquals(expected, source);
        return source;
    }

    /**
     * Renders the definitions and compiles them together.
     *
     * @param definitions The definitions
     * @return The class loader of the compiled classes
     */
    static URLClassLoader compile(ObjectDef... definitions) throws IOException {
        var sources = new String[definitions.length];
        for (int i = 0; i < definitions.length; i++) {
            sources[i] = render(definitions[i]);
        }
        return compileAndLoad(sources);
    }

    /**
     * Renders the definitions, asserts each source is the snapshot {@code <directory>/<simple name>.txt} of the test
     * resources, and compiles them together.
     *
     * @param directory The resource directory of the snapshots, such as {@code /generated-programs/java}
     * @param definitions The definitions
     * @return The class loader of the compiled classes
     */
    static URLClassLoader compileMatchingSnapshots(String directory, ObjectDef... definitions) throws IOException {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            var source = render(definition);
            var resource = directory + "/" + definition.getSimpleName() + ".txt";
            try (InputStream expected = JavaCompileAssertions.class.getResourceAsStream(resource)) {
                assertNotNull(expected, resource + "\n" + source);
                assertEquals(new String(expected.readAllBytes(), StandardCharsets.UTF_8), source, definition.getName());
            }
            sources.add(source);
        }
        return compileAndLoad(sources.toArray(String[]::new));
    }

    /**
     * Creates an instance of a compiled class through its no-argument constructor, which may be private.
     *
     * @param loader The class loader of the compiled classes
     * @param name The class name
     * @return The instance
     */
    static Object newInstance(URLClassLoader loader, String name) throws ReflectiveOperationException {
        var constructor = loader.loadClass(name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * A public class {@code test.<name>} with the single public method {@code call}, whose parameters are named
     * {@code p0}, {@code p1}...
     *
     * @param name The simple name of the class
     * @param returns The return type of the method
     * @param parameters The parameter types of the method
     * @param body The body of the method
     * @return The class
     */
    static ClassDef single(String name, Class<?> returns, List<TypeDef> parameters, MethodDef.MethodBodyBuilder body) {
        return single(name, TypeDef.of(returns), parameters, body);
    }

    /**
     * A public class {@code test.<name>} with the single public method {@code call}, whose parameters are named
     * {@code p0}, {@code p1}...
     *
     * @param name The simple name of the class
     * @param returns The return type of the method
     * @param parameters The parameter types of the method
     * @param body The body of the method
     * @return The class
     */
    static ClassDef single(String name, TypeDef returns, List<TypeDef> parameters, MethodDef.MethodBodyBuilder body) {
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(returns);
        for (int i = 0; i < parameters.size(); i++) {
            method.addParameter("p" + i, parameters.get(i));
        }
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC).addMethod(method.build(body)).build();
    }

    /**
     * Compiles a definition and invokes its method {@code call} on a new instance.
     *
     * @param definition The definition
     * @param args The arguments of the call
     * @return The result of the call
     * @throws Exception What the call throws, unwrapped
     */
    static Object run(ObjectDef definition, Object... args) throws Exception {
        try (var loader = compile(definition)) {
            return invoke(loader, definition, args);
        }
    }

    /**
     * Invokes the method {@code call} that takes as many parameters as there are arguments, of a compiled definition
     * on a new instance.
     *
     * @param loader The class loader of the compiled classes
     * @param definition The definition
     * @param args The arguments of the call
     * @return The result of the call
     * @throws Exception What the call throws, unwrapped
     */
    static Object invoke(ClassLoader loader, ObjectDef definition, Object... args) throws Exception {
        var cls = loader.loadClass(definition.getName());
        var method = Arrays.stream(cls.getMethods())
            .filter(m -> m.getName().equals("call") && m.getParameterCount() == args.length)
            .findFirst().orElseThrow();
        try {
            return method.invoke(cls.getConstructor().newInstance(), args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * A public class with the single public method {@code int call()}.
     *
     * @param name The class name
     * @param body The body of the method
     * @return The class
     */
    static ClassDef intMethod(String name, StatementDef body) {
        return ClassDef.builder(name).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class).addStatement(body).build())
            .build();
    }

    /**
     * A public class with the single public method {@code String call()}.
     *
     * @param name The class name
     * @param body The body of the method
     * @return The class
     */
    static ClassDef stringMethod(String name, StatementDef body) {
        return ClassDef.builder(name).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class).addStatement(body).build())
            .build();
    }

    private static String qualifiedName(String source) {
        Matcher typeMatcher = TYPE_DECLARATION.matcher(source);
        if (!typeMatcher.find()) {
            throw new IllegalArgumentException("Cannot find the type declaration in:\n" + source);
        }
        String simpleName = typeMatcher.group(1);
        Matcher packageMatcher = PACKAGE_DECLARATION.matcher(source);
        return packageMatcher.find() ? packageMatcher.group(1) + "." + simpleName : simpleName;
    }

    private static final class StringSource extends SimpleJavaFileObject {

        private final String source;

        private StringSource(String qualifiedName, String source) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

}
