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

import java.lang.annotation.*;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The EqualsAndHashCode annotation on a bean should generate an equals and a hashCode methods.
 * The equals method will be created in [BeanName]Utils class as a static method:
 *      public static boolean BeanNameUtils.equals(BeanName this, Object other)
 *
 * The hashCode method will be created in [BeanName]Utils class as a static method:
 *      public static int BeanNameUtils.hashCode(BeanName object)
 *
 * @author Elif Kurtay
 * @since 1.3
 */

@Retention(RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface EqualsAndHashCode {
    /**
     * The annotation to be used with {@link EqualsAndHashCode} on a property to hide the value from being processed.
     *
     * @author Elif Kurtay
     * @since 1.3
     */
    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER})
    public @interface Exclude {
    }
}
