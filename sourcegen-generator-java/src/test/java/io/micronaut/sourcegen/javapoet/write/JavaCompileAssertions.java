package io.micronaut.sourcegen.javapoet.write;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles generated sources so that a test can assert the output is not only well formed but also
 * valid Java.
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
