/*
 * Copyright 2017-2026 original authors
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

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

/**
 * Marks a source-generated type for tools that inspect compiled bytecode.
 *
 * <p>The marker is retained in class files so coverage tools can identify generated code.
 * Unlike {@link io.micronaut.core.annotation.Generated}, it does not prevent Micronaut
 * from processing the annotated type. Applying this annotation does not itself generate
 * any source code.</p>
 *
 * @author Issam Liqali
 * @since 2.2.4
 */
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface GeneratedSource {
}
