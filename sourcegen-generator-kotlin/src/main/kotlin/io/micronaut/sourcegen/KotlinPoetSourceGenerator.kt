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

import io.micronaut.core.annotation.Internal
import io.micronaut.inject.ast.Element
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.generator.SourceGenerator
import io.micronaut.sourcegen.model.ObjectDef
import java.io.IOException
import java.io.Writer

/**
 * Kotlin source code generator.
 *
 * Each write renders the definition with a [KotlinWriteContext] of its own, which holds what the rendering of one
 * file looks up and caches.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
class KotlinPoetSourceGenerator : SourceGenerator {
    override fun getLanguage(): VisitorContext.Language {
        return VisitorContext.Language.KOTLIN
    }

    override fun write(objectDef: ObjectDef, context: VisitorContext, vararg originatingElements: Element) {
        // The context looks up the supertypes of an override only known by name
        context.visitGeneratedSourceFile(objectDef.packageName, objectDef.simpleName, *originatingElements)
            .ifPresent { generatedFile ->
                try {
                    generatedFile.write { writer -> write(objectDef, writer, context) }
                } catch (e: Exception) {
                    val element = originatingElements.firstOrNull()
                    throw ProcessingException(element, "Failed to generate '" + objectDef.name + "': " + e.message, e)
                }
            }
    }

    @Throws(IOException::class)
    override fun write(objectDef: ObjectDef, writer: Writer) {
        write(objectDef, writer, null)
    }

    private fun write(objectDef: ObjectDef, writer: Writer, visitorContext: VisitorContext?) {
        KotlinWriteContext(visitorContext, objectDef).writeDefinition(writer)
    }
}
