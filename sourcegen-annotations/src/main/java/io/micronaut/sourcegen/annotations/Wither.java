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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The annotation to generate an interface implementing `with` copy method for records - `MyRecord withMyProperty(MyProperty)`.
 *
 * <p>When placed on a record type a `with` method is generated for every record component.
 * Alternatively the annotation can be placed on individual record components, in which case
 * a `with` method is only generated for the annotated components. Component-level annotations
 * take precedence, so combining a type-level `@Wither` with component-level ones restricts the
 * generation to the annotated components.</p>
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
public @interface Wither {
}
