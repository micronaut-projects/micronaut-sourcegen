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
import io.micronaut.sourcegen.KotlinFieldRules.defaultOf
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import java.util.function.Consumer
import javax.lang.model.element.Modifier

/*
 * The properties of a definition: its fields and its properties, as a Kotlin property or a parameter of the
 * primary constructor.
 */

internal fun KotlinWriteContext.buildProperties(
    objectDef: ObjectDef,
    builder: TypeSpec.Builder
) {
    val notNullProperties: MutableList<PropertyDef> = ArrayList()
    // A definition that declares its constructors assigns its properties in them, as it does its fields: a primary
    // constructor of the properties would clash with the one taking them
    val declaresConstructors = objectDef.methods.any { it.isConstructor }
    for (property in objectDef.properties) {
        var propertySpec: PropertySpec
        if (declaresConstructors) {
            val field = FieldDef.builder(property.name).ofType(property.type).addModifiers(property.modifiers)
                .addAnnotations(property.annotations).build()
            propertySpec = buildProperty(field, property.modifiers, property.javadoc, objectDef)
        } else if (property.type.isNullable) {
            propertySpec = buildProperty(
                property.name,
                property.type.makeNullable(),
                property.modifiers,
                property.annotations,
                property.javadoc,
                null,
                objectDef
            )
        } else {
            propertySpec = buildConstructorProperty(
                property.name,
                property.type,
                property.modifiers,
                property.annotations,
                property.javadoc,
                objectDef
            )
            notNullProperties.add(property)
        }
        builder.addProperty(
            propertySpec
        )
    }
    if (notNullProperties.isNotEmpty()) {
        builder.primaryConstructor(
            FunSpec.constructorBuilder().addModifiers(KModifier.PUBLIC).addParameters(
                notNullProperties.stream()
                    .map { prop: PropertyDef ->
                        ParameterSpec.builder(
                            prop.name,
                            asType(prop.type, objectDef)
                        ).build()
                    }.toList()
            ).build()
        )
    }
}

/** Adds the fields of a class or an enum as properties: a static one of the companion object. */
internal fun KotlinWriteContext.buildFields(
    objectDef: ObjectDef,
    fields: List<FieldDef>,
    builder: TypeSpec.Builder,
    companion: CompanionMembers
) {
    for (field in fields) {
        val modifiers = field.modifiers
        if (modifiers.contains(Modifier.STATIC)) {
            companion.members.addProperty(buildProperty(field, stripStatic(modifiers), field.javadoc, objectDef))
        } else {
            builder.addProperty(buildProperty(field, modifiers, field.javadoc, objectDef))
        }
    }
}

internal fun KotlinWriteContext.buildProperty(
    name: String,
    typeDef: TypeDef,
    modifiers: Set<Modifier>,
    annotations: List<AnnotationDef>,
    docs: List<String>, initializer: ExpressionDef?,
    objectDef: ObjectDef?,
    staticContext: Boolean = false,
    lateInit: Boolean = false,
    nullInitializer: Boolean = true,
    nullified: Boolean = false,
    constant: Boolean = false,
): PropertySpec {
    val propertyBuilder = PropertySpec.builder(
        name,
        asType(typeDef, objectDef, staticContext).let { if (nullified) it.copy(nullable = true) else it },
        asKModifiers(modifiers)
    )
    docs.forEach(Consumer { format: String -> propertyBuilder.addKdoc(format) })

    if (constant) {
        propertyBuilder.addModifiers(KModifier.CONST)
    } else if (!modifiers.contains(Modifier.FINAL) || lateInit) {
        propertyBuilder.mutable(true)
    }
    if (lateInit) {
        propertyBuilder.addModifiers(KModifier.LATEINIT)
    }
    modifierAnnotations(modifiers).forEach(propertyBuilder::addAnnotation)
    for (annotation in annotations) {
        propertyBuilder.addAnnotation(
            asAnnotationSpec(annotation)
        )
    }
    if (initializer != null) {
        val init = MethodDef.builder(name).returns(typeDef).build()
        propertyBuilder.initializer(
            renderAssigned(objectDef, init, KotlinRenderScope.root(init), initializer, typeDef)
        )
    } else if ((typeDef.isNullable || nullified) && nullInitializer) {
        propertyBuilder.initializer("null")
    }
    return propertyBuilder.build()
}

internal fun KotlinWriteContext.buildConstructorProperty(
    name: String,
    typeDef: TypeDef,
    modifiers: Set<Modifier>,
    annotations: List<AnnotationDef>,
    docs: List<String>,
    objectDef: ObjectDef?
): PropertySpec {
    val propertyBuilder = PropertySpec.builder(
        name,
        asType(typeDef, objectDef),
        asKModifiers(modifiers)
    )
    docs.forEach(Consumer { format: String -> propertyBuilder.addKdoc(format) })
    if (!modifiers.contains(Modifier.FINAL)) {
        propertyBuilder.mutable(true)
    }
    modifierAnnotations(modifiers).forEach(propertyBuilder::addAnnotation)
    for (annotation in annotations) {
        propertyBuilder.addAnnotation(
            asAnnotationSpec(annotation)
        )
    }
    return propertyBuilder
        .initializer(name)
        .build()
}

internal fun KotlinWriteContext.buildProperty(
    field: FieldDef,
    declaredModifiers: Set<Modifier>,
    docs: List<String>,
    objectDef: ObjectDef?
): PropertySpec {
    val modifiers = exposedToNest(objectDef, field.name, declaredModifiers)
    val declaration = KotlinFieldRules.declarationOf(objectDef, field, modifiers)
    return buildProperty(
        field.name,
        if (declaration.nullified) field.type.makeNullable() else field.type,
        (if (declaration.reassigned && !declaration.lateInit || declaration.constant) modifiers - Modifier.FINAL else modifiers),
        if (declaration.jvmField) field.annotations + AnnotationDef.builder(ClassTypeDef.of(JvmField::class.java)).build() else field.annotations,
        docs,
        field.initializer.orElse(if (declaration.nullified) null else defaultOf(field, objectDef)),
        objectDef,
        declaration.static,
        declaration.lateInit,
        // A nullable property every constructor assigns is not initialized where it is declared
        !declaration.byConstructors,
        declaration.nullified,
        declaration.constant
    )
}
