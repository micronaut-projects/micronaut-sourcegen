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
package io.micronaut.sourcegen.annotations;

import io.micronaut.core.annotation.Introspected;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The staged builder annotation on a bean should create a builder that initializes the required
 * properties in stages.
 *
 * <p>Every required property gets its own stage interface declaring the single method that assigns it
 * and returns the next stage, so the build method is only reachable once all of them are assigned. A
 * property is required unless the builder can leave it unassigned: it is nullable, it declares a
 * default value with {@link io.micronaut.core.bind.annotation.Bindable#defaultValue()} or it
 * accumulates values with {@link Singular}. Those are assigned in any order on the final stage.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface StagedBuilder {

    /**
     * Define what annotations should be added to the generated builder. By default,
     * the builder will have {@link io.micronaut.core.annotation.Introspected} annotation
     * so that introspection can be created for it.
     *
     * @return Array of annotations to apply on the builder
     */
    Class<? extends Annotation>[] annotatedWith() default Introspected.class;

}
