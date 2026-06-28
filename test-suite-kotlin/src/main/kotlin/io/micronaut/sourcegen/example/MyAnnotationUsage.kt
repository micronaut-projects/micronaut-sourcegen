/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.example

// Verifies that the generated Kotlin annotation actually compiles by using it.
// Note: the shared annotation fixture also declares an `inner` member typed as a Java annotation
// (java.lang.annotation.Target) with a Java-annotation default; the Kotlin generator omits such
// members because the Kotlin compiler crashes on a Java annotation in default-value position
// (see KotlinPoetSourceGenerator).
@MyAnnotation(
    string = "this",
    primitive = 3,
    floats = [1.0f, 2.0f]
)
class MyAnnotationUsage
