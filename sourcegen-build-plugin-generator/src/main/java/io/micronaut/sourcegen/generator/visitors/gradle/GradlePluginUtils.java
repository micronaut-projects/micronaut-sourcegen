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
package io.micronaut.sourcegen.generator.visitors.gradle;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin.GenerateGradleTask;
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
 * Utility class for Gradle plugin generation.
 */
@Internal
public final class GradlePluginUtils {

    /**
     * Get task configurations configured for a given element
     * with {@link GenerateGradlePlugin} annotation.
     *
     * @param element The element
     * @param context The visitor context
     * @return The maven task config
     */
    public static @NonNull GradlePluginConfig getPluginConfig(
            @NonNull ClassElement element,
            @NonNull VisitorContext context
    ) {
        AnnotationValue<GenerateGradlePlugin> annotation = element.getAnnotation(GenerateGradlePlugin.class);

        List<GradleTaskConfig> taskConfigs = new ArrayList<>();
        for (AnnotationValue<GenerateGradleTask> taskAnn:
            annotation.getAnnotations("tasks", GenerateGradleTask.class)
        ) {
            taskConfigs.add(getTaskConfig(element, taskAnn, context));
        }

        return new GradlePluginConfig(
            taskConfigs,
            element.getPackageName(),
            annotation.stringValue("namePrefix").orElse(element.getSimpleName()),
            annotation.stringValue("taskGroup").orElse(null),
            annotation.booleanValue("micronautPlugin").orElse(true),
            annotation.stringValue("dependency").orElse(null),
            annotation.getRequiredValue("types", GenerateGradlePlugin.Type[].class)
        );
    }

    private static @NonNull GradleTaskConfig getTaskConfig(
            @NonNull ClassElement element,
            @NonNull AnnotationValue<GenerateGradleTask> annotation,
            @NonNull VisitorContext context
    ) {
        ClassElement source = annotation.stringValue("source")
            .flatMap(context::getClassElement).orElse(null);
        if (source == null) {
            throw new ProcessingException(element, "Could not load source type defined in @GenerateGradleTask: "
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

        String namePrefix = annotation.stringValue("namePrefix").orElse(source.getSimpleName());
        String methodName = PluginUtils.getTaskExecutable(source).getName();
        String methodJavadoc = javadoc.elements().get(methodName + "()");
        if (methodJavadoc == null) {
            methodJavadoc = "Main execution of " + namePrefix + " task.";
        }
        return new GradleTaskConfig(
            source,
            parameters,
            methodName,
            namePrefix,
            annotation.stringValue("extensionMethodName").orElse(methodName),
            javadoc.javadoc().orElse(namePrefix + " Gradle task."),
            methodJavadoc,
            generatedModels,
            annotation.booleanValue("cacheable").orElse(true)
        );
    }

    /**
     * Configuration for a gradle plugin.
     *
     * @param tasks The task configuration
     * @param packageName The package name
     * @param namePrefix The type name prefix
     * @param taskGroup The gradle group to use
     * @param micronautPlugin Whether to extend micronaut plugin
     * @param dependency The dependency
     * @param types The types to generate
     */
    public record GradlePluginConfig(
        List<GradleTaskConfig> tasks,
        String packageName,
        String namePrefix,
        String taskGroup,
        boolean micronautPlugin,
        String dependency,
        GenerateGradlePlugin.Type[] types
    ) {
    }

    /**
     * Configuration for a gradle task.
     *
     * @param source The source element
     * @param parameters The parameters
     * @param methodName The run method name
     * @param namePrefix The prefix to use for classnames
     * @param extensionMethodName The method name for gradle extension
     * @param methodJavadoc The javadoc for executable method
     * @param taskJavadoc The javadoc for the whole task
     * @param generatedModels Additional generated models
     * @param cacheable Whether the task should be cacheable
     */
    public record GradleTaskConfig (
        @NonNull ClassElement source,
        @NonNull List<ParameterConfig> parameters,
        @NonNull String methodName,
        @NonNull String namePrefix,
        @NonNull String extensionMethodName,
        @NonNull String taskJavadoc,
        @NonNull String methodJavadoc,
        @NonNull List<GeneratedModel> generatedModels,
        boolean cacheable
    ) {
    }

}
