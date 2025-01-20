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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.sourcegen.annotations.PluginTaskExecutable;
import io.micronaut.sourcegen.annotations.PluginTaskParameter;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef.Local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Common utility methods for plugin generation.
 */
@Internal
public class PluginUtils {

    /**
     * Validate and get the method name of the task executable.
     *
     * @param source The source element annotated with {@link io.micronaut.sourcegen.annotations.PluginTask}.
     * @return The method name
     */
    public static MethodElement getTaskExecutable(ClassElement source) {
        List<MethodElement> executables = source.getMethods().stream()
            .filter(m -> m.hasAnnotation(PluginTaskExecutable.class))
            .toList();

        if (executables.size() != 1) {
            throw new ProcessingException(source, "Expected exactly one method annotated with @PluginTaskExecutable but found " + executables.size());
        }
        if (executables.get(0).getParameters().length != 0) {
            throw new ProcessingException(source, "Expected @PluginTaskExecutable method to have no parameters");
        }
        if (!executables.get(0).getReturnType().isVoid()) {
            throw new ProcessingException(source, "Expected @PluginTaskExecutable to have void return type");
        }
        return executables.get(0);
    }

    /**
     * Get configuration for a plugin parameter.
     *
     * @param sourceJavadoc The javadoc for the task type
     * @param property The property representing the parameter
     * @param type The type to use for parameter
     * @return THe configuration
     */
    public static @NonNull ParameterConfig getParameterConfig(
            @NonNull JavadocUtils.TypeJavadoc sourceJavadoc, @NonNull PropertyElement property, @Nullable TypeDef type
    ) {
        AnnotationValue<PluginTaskParameter> annotation = property.getAnnotation(PluginTaskParameter.class);
        String javadoc = sourceJavadoc.elements().get(property.getName());
        if (javadoc == null) {
            javadoc = "Configurable " + property.getName() + " parameter.";
        }
        if (type == null) {
            type = TypeDef.of(property.getType());
        }
        if (annotation == null) {
            return new ParameterConfig(property, false, null, false, false, false, null, javadoc, type);
        }
        return new ParameterConfig(
            property,
            annotation.booleanValue("required").orElse(false),
            annotation.stringValue("defaultValue").orElse(null),
            annotation.booleanValue("internal").orElse(false),
            annotation.booleanValue("directory").orElse(false),
            annotation.booleanValue("output").orElse(false),
            annotation.stringValue("globalProperty").orElse(null),
            javadoc,
            type
        );
    }

    /**
     * A common method for executing the main task executable.
     *
     * @param source The source annotated with {@link io.micronaut.sourcegen.annotations.PluginTask}
     * @param methodName The name of the method annotated with {@link PluginTaskExecutable}
     * @param parameters The parameters of the task
     * @param arguments The prepared arguments for the task
     * @return The statements to execute the task method
     */
    public static StatementDef executeTaskMethod(
            ClassElement source, String methodName, List<ParameterConfig> parameters, List<ExpressionDef> arguments
    ) {
        List<StatementDef> statements = new ArrayList<>();
        ClassTypeDef taskType = ClassTypeDef.of(source);
        Local task = new Local("task", taskType);

        MethodElement constructor = source.getPrimaryConstructor().orElse(null);
        if (constructor == null) {
            throw new ProcessingException(source, "No constructor found for " + source.getName());
        }
        if (source.isRecord()) {
            statements.add(new StatementDef.DefineAndAssign(task,
                taskType.instantiate(constructor, arguments)
            ));
        } else {
            Set<String> fulfilledArgs = new HashSet<>();
            Map<String, ExpressionDef> argsByName = new HashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                argsByName.put(parameters.get(i).source().getName(), arguments.get(i));
            }

            constructor.getParameters();
            List<ExpressionDef> constructorArgs = new ArrayList<>();
            for (ParameterElement param : constructor.getParameters()) {
                fulfilledArgs.add(param.getName());
                constructorArgs.add(argsByName.containsValue(param.getName())
                    ? argsByName.get(param.getName()) : ExpressionDef.constant(null));
            }
            statements.add(task.defineAndAssign(
                taskType.instantiate(constructor, constructorArgs)));

            for (PropertyElement property : source.getBeanProperties()) {
                if (fulfilledArgs.contains(property.getName())
                    || !argsByName.containsKey(property.getName())
                ) {
                    continue;
                }
                Optional<MethodElement> writeMethod = property.getWriteMethod();
                if (writeMethod.isPresent()) {
                    statements.add(task.invoke(writeMethod.get(), argsByName.get(property.getName())));
                    fulfilledArgs.add(property.getName());
                } else if (property.getField().isPresent()
                    && (property.isPublic() || property.isPackagePrivate())
                ) {
                    statements.add(task.field(property.getField().get()).assign(argsByName.get(property.getName())));
                    fulfilledArgs.add(property.getName());
                }
            }
        }

        statements.add(task.invoke(methodName, ClassTypeDef.VOID));
        return StatementDef.multi(statements);
    }

    /**
     * Configuration for a plugin parameter.
     *
     * @param source The source parameter
     * @param required Whether it is required
     * @param defaultValue The default value
     * @param internal Whether it is internal
     * @param directory Whether it is a directory
     * @param output Whether it is an output
     * @param globalProperty A global property
     * @param javadoc The javadoc for property
     * @param type The type to use for generated property
     */
    public record ParameterConfig(
        @NonNull PropertyElement source,
        boolean required,
        @Nullable String defaultValue,
        boolean internal,
        boolean directory,
        boolean output,
        @Nullable String globalProperty,
        @NonNull String javadoc,
        @NonNull TypeDef type
    ) {
    }

}
