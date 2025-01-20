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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * An annotation that triggers the generation of Maven Mojo.
 * A plugin can include multiple Mojos.
 *
 * @author Andriy Dmytruk
 * @since 1.6.x
 */
@Documented
@Retention(CLASS)
@Target({ ElementType.TYPE })
@Repeatable(GenerateMavenMojo.List.class)
public @interface GenerateMavenMojo {

    /**
     * The prefix to use for the mojo name.
     * For example if the prefix is {@code Test}, mojo will be generated as {@code TestMojo}.
     * The default is the annotated class name.
     *
     * @return The prefix
     */
    String namePrefix() default "";

    /**
     * @return The task configuration class name that has {@link PluginTask} annotation
     */
    String source();

    /**
     * @return Whether to extend abstract micronaut mojo.
     */
    boolean micronautPlugin() default true;

    /**
     * The property prefix to use for parameters generated in Maven Mojo.
     *
     * @see PluginTaskParameter#globalProperty()
     * @return The property prefix
     */
    String mavenPropertyPrefix() default "";

    /**
     * A container for repeated MavenMojo.
     */
    @Documented
    @Retention(CLASS)
    @Target({ ElementType.TYPE })
    @interface List {

        /**
         * @return Repeated annotations
         */
        GenerateMavenMojo[] value();
    }

}
