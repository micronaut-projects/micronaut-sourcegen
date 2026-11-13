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

import com.squareup.kotlinpoet.*
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import javax.lang.model.element.Modifier

/*
 * A record as the data class Kotlin writes it with, whose primary constructor is the canonical one.
 */

/** The constructor of a record taking its components, which the primary constructor of the data class is. */
internal fun canonicalConstructorOf(recordDef: RecordDef): MethodDef? = recordDef.methods.firstOrNull { method ->
    method.isConstructor && recordDef.properties.isNotEmpty() && method.parameters.size == recordDef.properties.size
        && method.parameters.map { TypeHierarchy.erasedName(it.type, recordDef) } ==
        recordDef.properties.map { TypeHierarchy.erasedName(it.type, recordDef) }
}

internal fun sameParameters(objectDef: ObjectDef, method: MethodDef, other: MethodDef): Boolean =
    method.parameters.map { TypeHierarchy.erasedName(it.type, objectDef) } == other.parameters.map { TypeHierarchy.erasedName(it.type, objectDef) }

/**
 * The statements of a canonical constructor other than the assignments of its parameters to the components,
 * which the data class makes: `null` where it assigns a component another value, which it cannot.
 */
internal fun initializerOf(recordDef: RecordDef, canonical: MethodDef): List<StatementDef>? {
    val components = recordDef.properties.map { it.name }
    val result = ArrayList<StatementDef>()
    for (statement in StatementDef.multi(canonical.statements).flatten()) {
        val assigned = (statement as? PutField)?.field?.takeIf { it.instance is VariableDef.This && it.name in components }
        if (assigned == null) {
            var assignsComponent = false
            forEachStatement(statement) { nested ->
                if (nested is PutField && nested.field.instance is VariableDef.This && nested.field.name in components) {
                    assignsComponent = true
                }
            }
            if (assignsComponent) {
                return null
            }
            result.add(statement)
            continue
        }
        val value = unwrapCasts(statement.expression)
        val index = components.indexOf(assigned.name)
        if (value !is VariableDef.MethodParameter || value.name != canonical.parameters[index].name) {
            return null
        }
    }
    return result
}

/** A record as a final class of its components, which keeps the constructors it declares. */
internal fun asClass(recordDef: RecordDef): ClassDef {
    val builder = ClassDef.builder(recordDef.simpleName)
        .addModifiers(recordDef.modifiers + Modifier.FINAL)
        .addAnnotations(recordDef.annotations)
        .addJavadoc(recordDef.javadoc)
        .addSuperinterfaces(recordDef.superinterfaces)
        .addMethods(recordDef.methods)
    recordDef.typeVariables.forEach { builder.addTypeVariable(it) }
    recordDef.properties.forEach { property ->
        builder.addProperty(PropertyDef.builder(property.name).ofType(property.type)
            .addModifiers(property.modifiers + Modifier.FINAL).addAnnotations(property.annotations).addJavadoc(property.javadoc).build())
    }
    recordDef.innerTypes.forEach { builder.addInnerType(it) }
    return builder.build().withClassName(ClassTypeDef.ClassName(recordDef.asTypeDef().name, recordDef.asTypeDef().isInner))
}
