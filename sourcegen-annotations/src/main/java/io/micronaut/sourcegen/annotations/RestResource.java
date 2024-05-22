/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.data.repository.CrudRepository;
import io.micronaut.data.repository.GenericRepository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The annotation to generate an interface implementing `with` copy method for records - `MyRecord withMyProperty(MyProperty)`.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface RestResource {
    /**
     *
     * @return The CRUD Repository for the resource.
     */
    Class<? extends CrudRepository> repository();

    /**
     *
     * @return The values supplied will be used to secure the resource controller.
     */
    String[] rolesAllowed() default "isAuthenticated()";

    /**
     *
     * @return The resource name. E.g. {@literal Book}
     */
    String name() default "";

    /**
     *
     * @return The base uri for the resource. e.g. {@literal books}
     */
    String uri() default "";


    /**
     *
     * @return Whether a resource /uri DELETE endpoint should be generated.
     */
    boolean delete() default true;

    /**
     *
     * @return Whether a resource /uri GET endpoint returning a JSON Array should be generated
     */
    boolean list() default true;

    /**
     *
     * @return Whether a resource /uri/{id} GET endpoint returning a JSON object should be generated
     */
    boolean show() default true;

    /**
     *
     * @return Whether a resource /uri PUT endpoint returning 200 OK should be generated
     */
    boolean update() default true;

    /**
     *
     * @return Whether a resource /uri POST endpoint returning 201 OK should be generated
     */
    boolean save() default true;
}
