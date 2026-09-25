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
package io.micronaut.sourcegen

import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.generator.GenerationScope
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.IdentityHashMap

/**
 * What the rendering of one file looks up and caches, created for each definition written. The rendering functions
 * are its extensions, so that the file is rendered against it and what a file reads does not depend on what was
 * written before it.
 *
 * @property visitorContext    The context of the file being written, which looks up the supertypes of an override
 *                             only known by name: `null` where the definition is written without one
 * @property writtenDefinition The top level definition of the file being written
 */
internal class KotlinWriteContext(
    val visitorContext: VisitorContext?,
    val writtenDefinition: ObjectDef
) {
    /** The definitions of the file, whose inner types name it and each other, and the context it is written with. */
    val generationScope: GenerationScope = GenerationScope.of(writtenDefinition, visitorContext)

    /** The private members of the nested types that the rest of the file uses. */
    val nestAccess: KotlinNestAccess = KotlinNestAccess.of(writtenDefinition)

    /** The functions being written, the innermost first. */
    val enclosingFunctions = ArrayDeque<MethodDef>()

    /**
     * The fields of the definitions the file reads that are nullable properties the model types as not null, by
     * definition.
     */
    val nullifiedFieldsCache = IdentityHashMap<ObjectDef, Set<String>>()

    /** Whether a method of the file returns a value Kotlin types as nullable, where the model does not, by method. */
    val nullableReturnsCache = IdentityHashMap<MethodDef, Boolean>()

    /** The locals a method of the file declares nullable, by method. */
    val nullableLocalsCache = IdentityHashMap<MethodDef, Set<String>>()

    /** The type arguments of the supertypes of a definition that are nullable, by definition. */
    val nullableArgumentsCache = IdentityHashMap<ObjectDef, Set<TypeDef>>()

    /**
     * Runs an action with a function as the innermost one being written.
     *
     * @param function The function
     * @param action   The action
     * @return The result of the action
     */
    fun <T> inFunction(function: MethodDef, action: () -> T): T {
        enclosingFunctions.addFirst(function)
        try {
            return action()
        } finally {
            enclosingFunctions.removeFirst()
        }
    }
}
