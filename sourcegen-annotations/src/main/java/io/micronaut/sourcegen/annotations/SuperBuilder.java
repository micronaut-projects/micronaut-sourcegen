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

import io.micronaut.core.annotation.Introspected;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The builder annotation on a bean should create a builder with a support of inheritance.
 * The super type should be also annotated with @SuperBuilder.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface SuperBuilder {

    /**
     * Define what annotations should be added to the generated builder. By default,
     * the builder will have {@link io.micronaut.core.annotation.Introspected} annotation
     * so that introspection can be created for it.
     *
     * @return Array of annotations to apply on the builder
     */
    Class<? extends Annotation>[] annotatedWith() default Introspected.class;

}
