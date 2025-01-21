/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.sourcegen.generator.visitors;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.JavadocBlockTag.Type;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A utility class for javadoc.
 * Since processed task might be in a dependency, it helps with writing it javadoc info
 * in a META-INF file and reading it from the file.
 */
@Internal
public class JavadocUtils {

    public static final String META_INF_FOLDER = "micronaut-plugin-gen/";
    public static final String META_INF_EXTENSION = ".javadoc.txt";

    /**
     * Get the javadoc for a task. Task class element may be in a dependency.
     * It will read the {@code .javadoc.txt} file written by plugin task visitor.
     *
     * @param context The visitor context
     * @param element The element annotated with {@link io.micronaut.sourcegen.annotations.PluginTask}.
     * @return The javadoc
     */
    public static @NonNull TypeJavadoc getTaskJavadoc(VisitorContext context, ClassElement element) {
        String javadocMetaPath = "META-INF/" + META_INF_FOLDER + element.getName() + META_INF_EXTENSION;
        ClassLoader classLoader = JavadocUtils.class.getClassLoader();

        String javadoc = null;
        Map<String, String> elements = new LinkedHashMap<>();
        try (InputStream inputStream = classLoader.getResourceAsStream(javadocMetaPath)) {
            if (inputStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    String line = reader.readLine();
                    if (line  != null) {
                        javadoc = parseJavadocInfo(line);
                    }
                    while ((line = reader.readLine()) != null) {
                        int i = line.indexOf(' ');
                        if (i > 0) {
                            elements.put(line.substring(0, i), line.substring(i + 1));
                        }
                    }
                }
            } else {
                context.warn("Could not find javadoc META-INF file " + javadocMetaPath + " for type", element);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (javadoc == null && elements.isEmpty()) {
            return getSourceJavadoc(element);
        }
        if (javadoc != null && javadoc.isEmpty()) {
            javadoc = null;
        }
        return new TypeJavadoc(
            Optional.ofNullable(javadoc),
            elements
        );
    }

    /**
     * Write javadoc meta info that could be parsed from a file.
     *
     * @param element The source element.
     * @return The info
     */
    public static String writeJavadocInfo(ClassElement element) {
        StringBuilder result = new StringBuilder();

        TypeJavadoc javadoc = getSourceJavadoc(element);
        result.append(formatJavadocInfo(javadoc.javadoc.orElse("")))
            .append('\n');
        for (Entry<String, String> entry: javadoc.elements.entrySet()) {
            result.append(entry.getKey())
                .append(' ')
                .append(formatJavadocInfo(entry.getValue()))
                .append('\n');
        }
        return result.toString();
    }

    private static @NonNull TypeJavadoc getSourceJavadoc(ClassElement element) {
        Javadoc parsed = StaticJavaParser.parseJavadoc(element.getDocumentation().orElse(""));
        String javadoc = parsed.getDescription().toText();
        Map<String, String> elements = new LinkedHashMap<>();

        for (JavadocBlockTag tag: parsed.getBlockTags()) {
            if (tag.getType() == Type.PARAM) {
                elements.put(tag.getName().orElse(null), tag.getContent().toText() + ".");
            }
        }
        for (PropertyElement property: element.getBeanProperties()) {
            if (property.getDocumentation().isPresent()) {
                elements.put(property.getName(), property.getDocumentation().get());
            } else if (property.getField().flatMap(Element::getDocumentation).isPresent()) {
                elements.put(property.getName(), property.getField().get().getDocumentation().get());
            }
        }
        for (MethodElement method: element.getMethods()) {
            if (method.getDocumentation().isPresent()) {
                String key = method.getName() + Arrays.stream(method.getParameters()).map(p -> p.getType().getName())
                    .collect(Collectors.joining(",")) + "()";
                elements.put(key, method.getDocumentation().get());
            }
        }
        if (javadoc.isEmpty()) {
            javadoc = null;
        }
        return new TypeJavadoc(
            Optional.ofNullable(javadoc),
            elements
        );
    }

    private static String formatJavadocInfo(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replaceAll("\n\\s+", "\n")
            .replace("\\", "\\\\")
            .replace("\n", "\\n");
    }

    private static String parseJavadocInfo(String line) {
        return line.replaceAll("(?<!\\\\\\\\)\\\\n", "\n")
            .replace("\\\\", "\\");
    }

    /**
     * A holder of javadoc for a type.
     *
     * @param javadoc The type javadoc
     * @param elements The javadoc of elements, like properties and methods
     */
    public record TypeJavadoc(
        Optional<String> javadoc,
        Map<String, String> elements
    ) {
    }

}
