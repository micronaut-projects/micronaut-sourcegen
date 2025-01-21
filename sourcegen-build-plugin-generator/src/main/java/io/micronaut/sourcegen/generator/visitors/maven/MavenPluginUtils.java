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
package io.micronaut.sourcegen.generator.visitors.maven;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.GenerateMavenMojo;
import io.micronaut.sourcegen.generator.visitors.JavadocUtils;
import io.micronaut.sourcegen.generator.visitors.JavadocUtils.TypeJavadoc;
import io.micronaut.sourcegen.generator.visitors.ModelUtils;
import io.micronaut.sourcegen.generator.visitors.ModelUtils.GeneratedModel;
import io.micronaut.sourcegen.generator.visitors.PluginUtils;
import io.micronaut.sourcegen.generator.visitors.PluginUtils.ParameterConfig;
import io.micronaut.sourcegen.model.TypeDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Utils class for Maven plugin generation.
 */
@Internal
public final class MavenPluginUtils {

    /**
     * Get task configurations configured for a given element
     * with {@link GenerateMavenMojo} annotations.
     *
     * @param element The element
     * @param context The visitor context
     * @return The maven task config
     */
    public static @NonNull List<MavenTaskConfig> getTaskConfigs(
        @NonNull ClassElement element, @NonNull VisitorContext context
    ) {
        List<AnnotationValue<GenerateMavenMojo>> annotations =
            element.getAnnotationValuesByType(GenerateMavenMojo.class);
        return annotations.stream().map(a -> getTaskConfig(element, a, context)).toList();
    }

    /**
     * Convert to dot-separated string.
     *
     * @param camelCase Camel case name
     * @return Dot separated name
     */
    public static String toDotSeparated(String camelCase) {
        StringBuilder result = new StringBuilder();
        boolean isStartOfWord = true;
        for (int i = 0; i < camelCase.length(); i++) {
            if (Character.isUpperCase(camelCase.charAt(i))) {
                if (!isStartOfWord) {
                    result.append(".");
                }
                result.append(Character.toLowerCase(camelCase.charAt(i)));
                isStartOfWord = true;
            } else {
                result.append(camelCase.charAt(i));
                isStartOfWord = camelCase.charAt(i) == '.';
            }
        }
        return result.toString();
    }

    private static @NonNull MavenTaskConfig getTaskConfig(
            @NonNull ClassElement element, @NonNull AnnotationValue<GenerateMavenMojo> annotation, @NonNull VisitorContext context
    ) {
        ClassElement source = annotation.stringValue("source")
            .flatMap(context::getClassElement).orElse(null);
        if (source == null) {
            throw new ProcessingException(element, "Could not load source type defined in @GenerateMavenMojo: "
                + annotation.stringValue("source"));
        }

        List<GeneratedModel> generatedModels = new ArrayList<>();
        TypeJavadoc javadoc = JavadocUtils.getTaskJavadoc(context, source);
        List<ParameterConfig> parameters = new ArrayList<>();
        for (PropertyElement property: source.getBeanProperties()) {
            TypeDef type = ModelUtils.getType(context, element.getPackageName() + ".model",
                property.getType(), generatedModels);
            parameters.add(PluginUtils.getParameterConfig(javadoc, property, type));
        }

        String namePrefix = annotation.stringValue("namePrefix").orElse(element.getSimpleName());
        String methodName = PluginUtils.getTaskExecutable(source).getName();
        String methodJavadoc = javadoc.elements().get(methodName + "()");
        if (methodJavadoc == null) {
            methodJavadoc = "Main execution of " + namePrefix + " Mojo.";
        }
        return new MavenTaskConfig(
            source,
            parameters,
            methodName,
            element.getPackageName(),
            namePrefix,
            annotation.booleanValue("micronautPlugin").orElse(true),
            annotation.stringValue("mavenPropertyPrefix").orElse(toDotSeparated(namePrefix)),
            javadoc.javadoc().orElse(namePrefix + " Maven Mojo."),
            methodJavadoc,
            generatedModels
        );
    }

    /**
     * Configuration for a gradle task type.
     *
     * @param source The configuration source
     * @param parameters The parameters
     * @param methodName The run method name
     * @param packageName The package name
     * @param namePrefix The type name prefix
     * @param micronautPlugin Whether to extend micronaut plugin
     * @param mavenPropertyPrefix The prefix for maven properties
     * @param taskJavadoc The javadoc for the whole task
     * @param methodJavadoc The javadoc for the executable method
     * @param generatedModels Additional generated models
     */
    public record MavenTaskConfig(
        ClassElement source,
        List<ParameterConfig> parameters,
        @NonNull String methodName,
        @NonNull String packageName,
        @NonNull String namePrefix,
        boolean micronautPlugin,
        @Nullable String mavenPropertyPrefix,
        @NonNull String taskJavadoc,
        @NonNull String methodJavadoc,
        @NonNull List<GeneratedModel> generatedModels
    ) {
    }

}
