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
import io.micronaut.sourcegen.generator.OverloadRules
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.EnumDef.EnumConstantDef
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import java.io.IOException
import java.io.Writer
import java.util.function.Consumer
import javax.lang.model.element.Modifier

/*
 * The Kotlin types of the definitions: a class, a record, an interface, an enum and an annotation, with
 * their inner types, and the companion object of their static members.
 */

/**
 * Writes the file of the definition being written.
 *
 * @param writer The writer
 */
@Throws(IOException::class)
internal fun KotlinWriteContext.writeDefinition(writer: Writer) {
    FileSpec.builder(writtenDefinition.packageName, writtenDefinition.simpleName + ".kt")
        .addType(typeBuilderOf(writtenDefinition).build())
        .build()
        .writeTo(writer)
}

/** The builder of the Kotlin type of a definition. */
private fun KotlinWriteContext.typeBuilderOf(objectDef: ObjectDef): TypeSpec.Builder = when (objectDef) {
    is ClassDef -> getClassBuilder(objectDef)
    is RecordDef -> getRecordBuilder(objectDef)
    is InterfaceDef -> getInterfaceBuilder(objectDef)
    is EnumDef -> getEnumBuilder(objectDef)
    is AnnotationObjectDef -> getAnnotationObjectBuilder(objectDef)
}

private fun KotlinWriteContext.getAnnotationObjectBuilder(def: AnnotationObjectDef): TypeSpec.Builder {
    val builder = TypeSpec.annotationBuilder(def.simpleName)
    builder.addModifiers(asKModifiers(stripStatic(def.modifiers)))
    def.javadoc.forEach(Consumer { format: String -> builder.addKdoc(format) })
    for (annotation in def.annotations) {
        builder.addAnnotation(asAnnotationSpec(annotation))
    }
    // A Kotlin annotation member is a constructor property, its default is the parameter default
    if (def.members.isNotEmpty()) {
        val constructor = FunSpec.constructorBuilder()
        for (member in def.members) {
            val memberType = asType(member.type, def)
            val parameter = ParameterSpec.builder(member.name, memberType)
            for (annotation in member.annotations) {
                parameter.addAnnotation(asAnnotationSpec(annotation))
            }
            renderAnnotationMemberDefault(def, member)?.let(parameter::defaultValue)
            constructor.addParameter(parameter.build())

            val property = PropertySpec.builder(member.name, memberType)
                .initializer(member.name)
            member.javadoc.forEach(Consumer { format: String -> property.addKdoc(format) })
            builder.addProperty(property.build())
        }
        builder.primaryConstructor(constructor.build())
    }
    val companion = CompanionMembers()
    for (field in def.fields) {
        companion.members.addProperty(buildProperty(field, stripStatic(field.modifiers), field.javadoc, def))
    }
    companion.addTo(builder)
    addInnerTypes(def.innerTypes, builder)
    return builder
}

private fun KotlinWriteContext.renderAnnotationMemberDefault(
    def: AnnotationObjectDef,
    member: AnnotationObjectDef.AnnotationMemberDef
): CodeBlock? {
    member.annotationDefaultValue?.let {
        // A nested annotation default is a constructor call, not an annotation use
        return CodeBlock.of("%L", asAnnotationSpec(it).toString().substring(1))
    }
    val defaultValue = member.defaultValue ?: return null
    val init = MethodDef.builder(member.name).returns(member.type).build()
    return renderAssigned(def, init, KotlinRenderScope.root(init), defaultValue, member.type)
}

private fun KotlinWriteContext.getInterfaceBuilder(interfaceDef: InterfaceDef): TypeSpec.Builder {
    val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceDef.simpleName)
    if (interfaceDef.annotations.any { it.type.name.equals(FunctionalInterface::class.qualifiedName) } || hasSingleAbstractMethod(interfaceDef)) {
        // Java implements an interface of a single abstract method with a lambda, Kotlin a fun interface only
        interfaceBuilder.addModifiers(KModifier.FUN)
    }
    interfaceBuilder.addModifiers(asKModifiers(stripStatic(interfaceDef.modifiers)))
    interfaceDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> asTypeVariable(tv, interfaceDef) }
        .forEach { typeVariable: TypeVariableName -> interfaceBuilder.addTypeVariable(typeVariable) }
    interfaceDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(typeDef, interfaceDef) }
        .forEach { it: TypeName ->
            interfaceBuilder.addSuperinterface(
                it
            )
        }
    interfaceDef.javadoc.forEach(Consumer { format: String -> interfaceBuilder.addKdoc(format) })
    interfaceDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
        .forEach { annotationSpec: AnnotationSpec -> interfaceBuilder.addAnnotation(annotationSpec) }

    for (property in interfaceDef.properties) {
        val propertySpec = if (property.type.isNullable) {
            buildProperty(
                property.name,
                property.type.makeNullable(),
                property.modifiers,
                property.annotations,
                property.javadoc,
                null,
                interfaceDef
            )
        } else {
            buildConstructorProperty(
                property.name,
                property.type,
                property.modifiers,
                property.annotations,
                property.javadoc,
                interfaceDef
            )
        }
        interfaceBuilder.addProperty(
            propertySpec
        )
    }
    val companion = CompanionMembers()
    addMethods(interfaceDef, interfaceBuilder, companion)
    companion.addTo(interfaceBuilder)
    addInnerTypes(interfaceDef.innerTypes, interfaceBuilder, isInterface = true)
    return interfaceBuilder
}

/**
 * Whether an interface declares a single abstract method and nothing else abstract, which Kotlin implements with
 * a lambda where it is a fun interface. One extending another interface is not known to.
 */
private fun hasSingleAbstractMethod(interfaceDef: InterfaceDef): Boolean {
    if (interfaceDef.properties.isNotEmpty() || interfaceDef.superinterfaces.isNotEmpty()) {
        return false
    }
    val abstract = interfaceDef.methods.filter { method ->
        !method.modifiers.contains(Modifier.STATIC) && !method.modifiers.contains(Modifier.DEFAULT)
            && !method.modifiers.contains(Modifier.PRIVATE) && method.statements.isEmpty()
    }
    return abstract.size == 1 && abstract[0].typeVariables.isEmpty()
}

private fun KotlinWriteContext.getClassBuilder(classDef: ClassDef): TypeSpec.Builder {
    val classBuilder = TypeSpec.classBuilder(classDef.simpleName)
    classBuilder.addModifiers(asKModifiers(stripStatic(classDef.modifiers)))
    if (isExtendable(classDef) && !classDef.modifiers.contains(Modifier.ABSTRACT)) {
        // Java lets any class that is not final be extended, Kotlin only an open one
        classBuilder.addModifiers(KModifier.OPEN)
    }
    classDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> asTypeVariable(tv, classDef) }
        .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
    classDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(withNullableArguments(typeDef, classDef), classDef) }
        .forEach { it: TypeName ->
            classBuilder.addSuperinterface(
                it
            )
        }
    classDef.javadoc.forEach(Consumer { format: String -> classBuilder.addKdoc(format) })
    if (classDef.superclass != null) {
        val superclass = classDef.superclass!!
        // A subclass of Number overrides its Java methods, `intValue()`, which kotlin.Number names `toInt()`
        classBuilder.superclass(if (TypeHierarchy.erasedName(superclass) == "java.lang.Number") ClassName("java.lang", "Number")
            else asType(withNullableArguments(superclass, classDef), classDef))
    }
    classDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
        .forEach { annotationSpec: AnnotationSpec -> classBuilder.addAnnotation(annotationSpec) }

    val companion = CompanionMembers()
    buildProperties(classDef, classBuilder)
    buildFields(classDef, classDef.fields, classBuilder, companion)

    classDef.staticInitializer?.let { staticInitializerDef ->
        val clinit = MethodDef.builder("<clinit>").build()
        companion.members.addInitializerBlock(
            renderStatementCodeBlock(classDef, clinit, KotlinRenderScope.root(clinit), staticInitializerDef)
        )
    }

    addMethods(classDef, classBuilder, companion) { method ->
        val modifiers = method.modifiers
        // Through generated superclasses too: a generated parent can extend the `AbstractList` that maps `size`
        val inheritedProperty = if (method.isOverride) KotlinJavaMappings.inheritedPropertyOf(generationScope, classDef, method, HashSet()) else null
        if (inheritedProperty != null) {
            // Kotlin sees the inherited Java method as a property - the `size` of a list - which a function of
            // that name does not override
            val (name, declaring) = inheritedProperty
            val function = buildFunction(classDef, method, modifiers)
            // A Java class's collection is a mutable one to Kotlin: the `entries` of an AbstractMap
            val type = KotlinJavaMappings.mappedPropertyType(function.returnType, !declaring.isInterface)
            classBuilder.addProperty(PropertySpec.builder(name, type)
                .addModifiers(function.modifiers)
                .getter(FunSpec.getterBuilder().addCode(function.body).build())
                .build())
        } else if (method.isConstructor) {
            val superCallStatement = method.statements.firstOrNull {
                it is InvokeInstanceMethod && it.instance is VariableDef.Super && it.method.isConstructor
            } as? InvokeInstanceMethod
            val superCallStatement2 = method.statements.firstOrNull {
                it is InvokeSuperConstructor
            } as? InvokeSuperConstructor
            // Only a lone constructor that does nothing but call super is the primary one: another constructor,
            // or a body, needs secondary constructors that delegate
            val primary = classDef.methods.count { it.isConstructor } == 1 && method.statements.size == 1
            val superMethod = superCallStatement2?.method ?: superCallStatement?.method
            val superValues = superCallStatement2?.values ?: superCallStatement?.values
            if (primary && superMethod != null && superValues != null) {
                addPrimaryConstructor(classBuilder, classDef, method, modifiers, renderArguments(classDef, method,
                    KotlinRenderScope.root(method), classDef.superclass, MethodDef.CONSTRUCTOR, superMethod,
                    superMethod.parameters.map { it.type }, superValues))
            } else {
                classBuilder.addFunction(
                    buildFunction(classDef, method, modifiers)
                )
            }
        } else {
            classBuilder.addFunction(
                buildFunction(classDef, method, modifiers)
            )
        }
    }
    companion.addTo(classBuilder)
    addInnerTypes(classDef.innerTypes, classBuilder)
    return classBuilder
}

private fun KotlinWriteContext.addPrimaryConstructor(
    classBuilder: TypeSpec.Builder,
    classDef: ClassDef,
    method: MethodDef,
    modifiers: Set<Modifier>,
    superArguments: CodeBlock
) {
    val constructorFunSpecBuilder = FunSpec.constructorBuilder()
        .addModifiers(asKModifiers(method, modifiers))
        .addParameters(
            method.parameters.stream()
                .map { param: ParameterDef ->
                    ParameterSpec.builder(
                        param.name,
                        asType(if (isBoxed(param.type)) param.type.makeNullable() else param.type, classDef)
                    ).build()
                }.toList()
        )
    classBuilder.superclassConstructorParameters.add(superArguments)
    classBuilder.primaryConstructor(constructorFunSpecBuilder.build())
}

private fun KotlinWriteContext.renderEnumConstantArguments(objectDef: ObjectDef?, method: MethodDef, arguments: List<ExpressionDef>): CodeBlock {
    val builder = CodeBlock.builder()
    for ((index, arg) in arguments.withIndex()) {
        builder.add(renderExpressionCode(objectDef, method, KotlinRenderScope.root(method), arg))
        if (index < arguments.size - 1) {
            builder.add(", ")
        }
    }
    return builder.build()
}

private fun KotlinWriteContext.getRecordBuilder(recordDef: RecordDef): TypeSpec.Builder {
    val canonical = canonicalConstructorOf(recordDef)
    val initializer = canonical?.let { initializerOf(recordDef, it) }
    if (canonical != null && initializer == null) {
        // A canonical constructor that computes a component, which the primary constructor of a data class takes
        // as it is: the record is a class of its components, with the constructor it declares
        return getClassBuilder(asClass(recordDef))
    }
    val classBuilder = TypeSpec.classBuilder(recordDef.simpleName)
    if (recordDef.properties.isNotEmpty()) {
        // A data class needs a component
        classBuilder.addModifiers(KModifier.DATA)
    }
    classBuilder.addModifiers(asKModifiers(stripStatic(recordDef.modifiers)))
    recordDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> asTypeVariable(tv, recordDef) }
        .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
    recordDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(withNullableArguments(typeDef, recordDef), recordDef) }
        .forEach { it: TypeName ->
            classBuilder.addSuperinterface(
                it,
            )
        }
    recordDef.javadoc.forEach(Consumer { format: String -> classBuilder.addKdoc(format) })
    recordDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
        .forEach { annotationSpec: AnnotationSpec -> classBuilder.addAnnotation(annotationSpec) }

    val constructorProperties: MutableList<PropertyDef> = ArrayList()
    for (property in recordDef.properties) {
        constructorProperties.add(property)
        classBuilder.addProperty(
            buildConstructorProperty(
                property.name,
                property.type,
                extendModifiers(property.modifiers, Modifier.FINAL),
                property.annotations,
                property.javadoc,
                recordDef
            )
        )
    }
    if (constructorProperties.isNotEmpty()) {
        classBuilder.primaryConstructor(
            FunSpec.constructorBuilder().addModifiers(KModifier.PUBLIC).addParameters(
                constructorProperties.stream()
                    .map { prop: PropertyDef ->
                        ParameterSpec.builder(
                            prop.name,
                            asType(prop.type, recordDef)
                        ).build()
                    }.toList()
            ).build()
        )
    }

    val companion = CompanionMembers()
    addMethods(recordDef, classBuilder, companion) { method ->
        if (canonical != null && method.isConstructor && sameParameters(recordDef, method, canonical)) {
            // The primary constructor is the canonical one: what else its body does is the init block, whose
            // parameters are the components
            if (initializer!!.isNotEmpty()) {
                val scope = KotlinRenderScope.root(canonical)
                canonical.parameters.forEachIndexed { index, parameter -> scope.rename(parameter.name, recordDef.properties[index].name) }
                val block = CodeBlock.builder()
                for (statement in initializer) {
                    block.add(renderStatementCodeBlock(recordDef, canonical, scope, statement))
                    if (Completion.KOTLIN.cannotCompleteNormally(statement)) {
                        break
                    }
                }
                classBuilder.addInitializerBlock(block.build())
            }
        } else {
            classBuilder.addFunction(buildFunction(recordDef, method, method.modifiers))
        }
    }
    companion.addTo(classBuilder)
    addInnerTypes(recordDef.innerTypes, classBuilder)
    return classBuilder
}

private fun KotlinWriteContext.getEnumBuilder(enumDef: EnumDef): TypeSpec.Builder {
    val enumBuilder = TypeSpec.enumBuilder(enumDef.simpleName)
    enumBuilder.addModifiers(asKModifiers(stripStatic(enumDef.modifiers)))
    enumDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(withNullableArguments(typeDef, enumDef), enumDef) }
        .forEach { it: TypeName -> enumBuilder.addSuperinterface(it) }
    enumDef.javadoc.forEach(Consumer { format: String -> enumBuilder.addKdoc(format) })
    enumDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
        .forEach { annotationSpec: AnnotationSpec -> enumBuilder.addAnnotation(annotationSpec) }

    enumDef.enumConstants.forEach { enumConstant: EnumConstantDef ->
        if (!enumConstant.constructorArgs.isNullOrEmpty()) {
            val constantInit = MethodDef.builder("").returns(TypeDef.VOID).build()
            // The arguments are converted to the parameters of the constructor they are passed to
            val constructor = enumDef.methods.singleOrNull { it.isConstructor && it.parameters.size == enumConstant.constructorArgs.size }
            val arguments = enumConstant.constructorArgs.mapIndexed { index, argument ->
                if (constructor == null) argument else KotlinConversions.coerce(argument, constructor.parameters[index].type)
            }
            enumBuilder.addEnumConstant(
                enumConstant.name,
                TypeSpec.companionObjectBuilder()
                    .addSuperclassConstructorParameter(renderEnumConstantArguments(null, constantInit, arguments))
                    .build()
            )
        } else {
            enumBuilder.addEnumConstant(enumConstant.name)
        }
    }

    val companion = CompanionMembers()
    buildProperties(enumDef, enumBuilder)
    buildFields(enumDef, enumDef.fields, enumBuilder, companion)
    addMethods(enumDef, enumBuilder, companion)
    companion.addTo(enumBuilder)
    addInnerTypes(enumDef.innerTypes, enumBuilder)
    return enumBuilder
}

/**
 * The companion object of a type, which declares the static members of its definition: created with the first of
 * them, and added to the type where there is one.
 */
internal class CompanionMembers {
    private var builder: TypeSpec.Builder? = null

    /** The builder of the companion object, created on first use. */
    val members: TypeSpec.Builder
        get() = builder ?: TypeSpec.companionObjectBuilder().also { builder = it }

    /**
     * Adds the companion object to the type, where a static member was added to it.
     *
     * @param type The type
     */
    fun addTo(type: TypeSpec.Builder) {
        builder?.let { type.addType(it.build()) }
    }
}

/**
 * Adds the methods a definition writes to its type: a static one as a function of the companion object, another
 * as a function of the type, or as the action given writes it.
 */
private fun KotlinWriteContext.addMethods(
    definition: ObjectDef,
    typeBuilder: TypeSpec.Builder,
    companion: CompanionMembers,
    addInstanceMethod: (MethodDef) -> Unit = { method -> typeBuilder.addFunction(buildFunction(definition, method, method.modifiers)) }
) {
    for (method in OverloadRules.writtenMethods(definition, generationScope, true)) {
        if (method.modifiers.contains(Modifier.STATIC)) {
            companion.members.addFunction(buildFunction(definition, method, stripStatic(method.modifiers)))
        } else {
            addInstanceMethod(method)
        }
    }
}

private fun KotlinWriteContext.addInnerTypes(objectDefs: List<ObjectDef>, classBuilder: TypeSpec.Builder, isInterface: Boolean = false) {
    for (objectDef in objectDefs) {
        val innerBuilder = typeBuilderOf(objectDef)
        if (isInterface) {
            innerBuilder.addModifiers(KModifier.PUBLIC)
        }
        classBuilder.addType(innerBuilder.build())
    }
}

/** Whether Java lets a subclass extend the class, which it does unless the class is final or sealed. */
internal fun isExtendable(classDef: ClassDef): Boolean =
    !classDef.modifiers.contains(Modifier.FINAL) && !classDef.modifiers.contains(Modifier.SEALED)
