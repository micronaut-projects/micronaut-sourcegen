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
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The annotation to be used with {@link Builder} or {@link SuperBuilder} on a property to create
 * a single element method for filling a collection. The final collection is immutable by default.
 * The name of the singular method is extracted from the property name converting plural name to a singular,
 * in a case it's not possible to recognize the singular name it's required to provide it in the value attribute.
 * The only supported collections are:
 * - {@link java.lang.Iterable}
 * - {@link java.util.Collection}
 * - {@link java.util.List}
 * - {@link java.util.Set}
 * - {@link java.util.SortedSet}
 * - {@link java.util.Map}
 * - {@link java.util.SortedMap}
 *
 * @author Denis Stepanov
 * @since 1.2
 */
@Documented
@Retention(RUNTIME)
@Target({FIELD, PARAMETER})
public @interface Singular {

    /**
     * @return The name of the singular method. If the collection doesn't end with "s" the name is required.
     */
    String value() default "";

}
