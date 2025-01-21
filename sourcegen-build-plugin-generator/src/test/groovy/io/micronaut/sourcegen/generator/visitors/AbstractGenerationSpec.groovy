package io.micronaut.sourcegen.generator.visitors


import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder
import org.intellij.lang.annotations.Language

import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import java.util.stream.Collectors

abstract class AbstractGenerationSpec extends AbstractTypeElementSpec {

    Map<String, JavaFileObject> generateSources(String className, @Language("java") String cls) {
        AbstractAnnotationMetadataBuilder.clearMutated()
        try (def parser = newJavaParser()) {
            var files = parser.generate(className, cls)
            Map<String, JavaFileObject> result = new HashMap<>()
            for (JavaFileObject file: files) {
                String name = (file as SimpleJavaFileObject).toUri().toString()
                if (name.startsWith("mem:///SOURCE_OUTPUT/")) {
                    name = name.substring("mem:///SOURCE_OUTPUT/".length()).replace("/", ".")
                    if (name.endsWith(".java")) {
                        name = name.substring(0, name.length() - ".java".length())
                    }
                    result.put(name, file)
                }
            }
            return result;
        }
    }

    String stripImports(CharSequence value) {
        String[] lines = value.toString().split("\n")
        int startI = 0
        while (lines[startI].isEmpty() || lines[startI].startsWith("package")
                || lines[startI].startsWith("import")) {
            ++startI
        }
        int endI = lines.length;
        while (lines[endI - 1].isEmpty()) {
            --endI
        }
        return Arrays.stream(lines, startI, endI).collect(Collectors.joining("\n"))
    }

}
