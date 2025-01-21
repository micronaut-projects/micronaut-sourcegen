/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.sourcegen.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * An annotation that triggers the generation of Gradle plugin and other types.
 * Only single Gradle plugin should be generated. But a plugin may contain
 * more than one task. Each task will have an corresponding extension method.
 *
 * @author Andriy Dmytruk
 * @since 1.6.x
 */
@Documented
@Retention(CLASS)
@Target({ ElementType.TYPE })
public @interface GenerateGradlePlugin {

    /**
     * The prefix to use for all names.
     * For example if the prefix is {@code Test}, task will be generated as {@code TestTask}.
     * The default is the annotated class name.
     *
     * @return The prefix
     */
    String namePrefix() default "";

    /**
     * Configure gradle tasks that will be generated.
     *
     * @return gradle task configurations
     */
    GenerateGradleTask[] tasks();

    /**
     * The types of classes to generate.
     * By default, all are generated.
     *
     * @return The plugin types to generate.
     */
    Type[] types() default {
        Type.GRADLE_TASK, Type.GRADLE_EXTENSION, Type.GRADLE_SPECIFICATION, Type.GRADLE_PLUGIN
    };

    /**
     * @return The gradle task group that will be set for all tasks
     */
    String taskGroup() default "";

    /**
     * @return Whether to extend the micronaut plugin
     */
    boolean micronautPlugin() default true;

    /**
     * The coordinate of dependency to add, like
     * {@code io.micronaut.jsonschema:micronaut-jsonschema-generator}.
     *
     * @return The dependency
     */
    String dependency() default "";

    /**
     * Enum defining the types that could be generated.
     */
    enum Type {
        GRADLE_TASK,
        GRADLE_SPECIFICATION,
        GRADLE_EXTENSION,
        GRADLE_PLUGIN
    }

    /**
     * A configuration for generating a gradle task as part of the plugin.
     */
    @interface GenerateGradleTask {

        /**
         * @return The prefix to use for task name.
         */
        String namePrefix() default "";

        /**
         * @return The task configuration class name that has {@link PluginTask} annotation
         */
        String source();

        /**
         * @return The name to use for the generated extension
         */
        String extensionMethodName() default "";

        /**
         * @return Whether the task is cacheable
         */
        boolean cacheable() default true;

    }


}
