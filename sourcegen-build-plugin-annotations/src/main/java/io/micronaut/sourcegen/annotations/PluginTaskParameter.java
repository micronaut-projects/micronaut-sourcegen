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
 * An annotation that configures a parameter for plugin task.
 * Should be inside a type annotated with {@link PluginTask}.
 *
 * <p>The annotation is used during generation of particular plugin implementations, like Maven
 * Mojos or Gradle Tasks.</p>
 *
 * <p>Java primitives, strings, lists, maps, enums and simple records/POJOs are supported.</p>
 *
 * @author Andriy Dmytruk
 * @since 1.6.x
 */
@Documented
@Retention(CLASS)
@Target({ ElementType.FIELD })
public @interface PluginTaskParameter {

    /**
     * Whether the parameter is required.
     * By default, parameters are not required to ensure a simple plugin API.
     * Parameters that have a default value should not be required.
     *
     * @return Whether it is required
     */
    boolean required() default false;

    /**
     * The default value.
     * Is allowed only for Java primitives or enums.
     *
     * @return The default value
     */
    String defaultValue() default "";

    /**
     * Whether the parameter is plugin-internal.
     * This means that the parameter won't get exposed as part of plugin API.
     * Specific logic will be written by developer in plugin to set the value for this parameter.
     * This is useful for parameters that depend on plugin-specific logic, like getting the
     * build directory.
     *
     * @return Whether it is internal
     */
    boolean internal() default false;

    /**
     * The global property name.
     * For maven Mojo it will correspond to {@code @Parameter(property='')} value.
     * It has no current effect for Gradle.
     *
     * @return The property name
     */
    String globalProperty() default "";

    /**
     * Whether the file is a directory.
     * Will only work for parameters of type {@link java.io.File}.
     *
     * @return Whether it is a directory.
     */
    boolean directory() default false;

    /**
     * Whether the parameter is output of the task.
     * Most likely, the parameter is a file or directory.
     *
     * @return Whether it is output
     */
    boolean output() default false;

    /**
     * @return Path sensitivity to use for file parameters. This would reflect on how
     * task executions are cached. If the path is considered equal, task won't be executed again.
     * This has no effect for Maven.
     */
    PathSensitivity pathSensitivity() default PathSensitivity.ABSOLUTE;

    /**
     * Path sensitivity options.
     * The specified part of the file path is used when detecting if the property has changed.
     */
    enum PathSensitivity {
        /** The parameter is ignored for caching. **/
        NONE,
        /** Only name of the file is compared. **/
        NAME_ONLY,
        /** The relative path is compared. **/
        RELATIVE,
        /** The absolute path is compared. **/
        ABSOLUTE
    }

}
