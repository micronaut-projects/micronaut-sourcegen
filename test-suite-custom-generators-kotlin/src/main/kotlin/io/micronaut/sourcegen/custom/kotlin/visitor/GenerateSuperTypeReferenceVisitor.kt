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
package io.micronaut.sourcegen.custom.kotlin.visitor

import org.jspecify.annotations.NonNull
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.inject.visitor.TypeElementVisitor.VisitorKind
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.custom.example.GenerateSuperTypeReference
import io.micronaut.sourcegen.generator.SourceGenerators
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.VariableDef
import javax.lang.model.element.Modifier

open class GenerateSuperTypeReferenceVisitor : TypeElementVisitor<GenerateSuperTypeReference, Any> {

    override fun getVisitorKind(): @NonNull VisitorKind {
        return VisitorKind.ISOLATING
    }

    override fun visitClass(element: ClassElement, context: VisitorContext) {

        val stringType = ClassTypeDef.of(String::class.java)
        val method: MethodDef = MethodDef.builder("simpleSuperCall")
            .returns(stringType)
            .addModifiers(Modifier.PUBLIC)
            .build { aThis: VariableDef.This, methodParameters: MutableList<VariableDef.MethodParameter> ->
                aThis.superRef()
                    .invoke("toString", stringType)
                    .invoke("uppercase", stringType)
                    .returning()
            }
        val classBuilder = ClassDef.builder("SuperTypeReferenceClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)

        val sourceGenerator =
            SourceGenerators.findByLanguage(context.language).orElse(null)
        if (sourceGenerator == null) {
            return
        }
        sourceGenerator.write(classBuilder.build(), context, element)
    }
}
