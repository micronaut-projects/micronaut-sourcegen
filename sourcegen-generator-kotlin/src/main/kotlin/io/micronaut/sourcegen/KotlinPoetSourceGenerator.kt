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
@file:OptIn(KotlinPoetJavaPoetPreview::class)

package io.micronaut.sourcegen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.javapoet.KotlinPoetJavaPoetPreview
import com.squareup.kotlinpoet.javapoet.toKClassName
import com.squareup.kotlinpoet.javapoet.toKTypeName
import io.micronaut.core.annotation.Internal
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.inject.ast.Element
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.generator.InvokedSignature
import io.micronaut.sourcegen.generator.OverrideResolver
import io.micronaut.sourcegen.generator.SourceGenerator
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.EnumDef.EnumConstantDef
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import org.jspecify.annotations.Nullable
import java.io.IOException
import java.io.Writer
import java.lang.reflect.Array
import java.util.function.Consumer
import javax.lang.model.element.Modifier
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.reflect.KClass

/**
 * Kotlin source code generator.
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
        val previous = VISITOR_CONTEXT.get()
        VISITOR_CONTEXT.set(context)
        try {
            super.write(objectDef, context, *originatingElements)
        } finally {
            if (previous == null) {
                VISITOR_CONTEXT.remove()
            } else {
                VISITOR_CONTEXT.set(previous)
            }
        }
    }

    @Throws(IOException::class)
    override fun write(objectDef: ObjectDef, writer: Writer) {
        when (objectDef) {
            is ClassDef -> {
                writeClass(writer, objectDef)
            }

            is RecordDef -> {
                writeRecordDef(writer, objectDef)
            }

            is InterfaceDef -> {
                writeInterface(writer, objectDef)
            }

            is EnumDef -> {
                writeEnumDef(writer, objectDef)
            }

            is AnnotationObjectDef -> {
                writeAnnotationObject(writer, objectDef)
            }

            else -> {
                throw IllegalStateException("Unknown object definition: $objectDef")
            }
        }
    }

    @Throws(IOException::class)
    private fun writeAnnotationObject(writer: Writer, annotationDef: AnnotationObjectDef) {
        FileSpec.builder(annotationDef.packageName, annotationDef.simpleName + ".kt")
            .addType(getAnnotationObjectBuilder(annotationDef).build())
            .build()
            .writeTo(writer)
    }

    private fun getAnnotationObjectBuilder(def: AnnotationObjectDef): TypeSpec.Builder {
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
        var companionBuilder: TypeSpec.Builder? = null
        for (field in def.fields) {
            if (companionBuilder == null) {
                companionBuilder = TypeSpec.companionObjectBuilder()
            }
            companionBuilder.addProperty(
                buildProperty(field, stripStatic(field.modifiers), field.javadoc, def)
            )
        }
        companionBuilder?.let { builder.addType(it.build()) }
        addInnerTypes(def.innerTypes, builder)
        return builder
    }

    private fun renderAnnotationMemberDefault(
        def: AnnotationObjectDef,
        member: AnnotationObjectDef.AnnotationMemberDef
    ): CodeBlock? {
        member.annotationDefaultValue?.let {
            // A nested annotation default is a constructor call, not an annotation use
            return CodeBlock.of("%L", asAnnotationSpec(it).toString().substring(1))
        }
        val defaultValue = member.defaultValue ?: return null
        val init = MethodDef.builder(member.name).returns(member.type).build()
        return renderExpressionCode(def, init, RenderScope.root(init), defaultValue)
    }

    @Throws(IOException::class)
    private fun writeInterface(writer: Writer, interfaceDef: InterfaceDef) {
        val interfaceBuilder = getInterfaceBuilder(interfaceDef)
        FileSpec.builder(interfaceDef.packageName, interfaceDef.simpleName + ".kt")
            .addType(interfaceBuilder.build())
            .build()
            .writeTo(writer)
    }

    private fun getInterfaceBuilder(interfaceDef: InterfaceDef): TypeSpec.Builder {
        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceDef.simpleName)
        if (interfaceDef.annotations.any { it.type.name.equals(FunctionalInterface::class.qualifiedName) }) {
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

        var companionBuilder: TypeSpec.Builder? = null
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
        for (method in interfaceDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                modifiers = stripStatic(modifiers)
                companionBuilder.addFunction(
                    buildFunction(interfaceDef, method, modifiers)
                )
            } else {
                interfaceBuilder.addFunction(
                    buildFunction(interfaceDef, method, modifiers)
                )
            }
        }
        if (companionBuilder != null) {
            interfaceBuilder.addType(companionBuilder.build())
        }
        addInnerTypes(interfaceDef.innerTypes, interfaceBuilder, isInterface = true)
        return interfaceBuilder
    }

    @Throws(IOException::class)
    private fun writeClass(writer: Writer, classDef: ClassDef) {
        val classBuilder = getClassBuilder(classDef)
        FileSpec.builder(classDef.packageName, classDef.simpleName + ".kt")
            .addType(classBuilder.build())
            .build()
            .writeTo(writer)
    }

    private fun getClassBuilder(classDef: ClassDef): TypeSpec.Builder {
        val classBuilder = TypeSpec.classBuilder(classDef.simpleName)
        classBuilder.addModifiers(asKModifiers(stripStatic(classDef.modifiers)))
        classDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> asTypeVariable(tv, classDef) }
            .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
        classDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(typeDef, classDef) }
            .forEach { it: TypeName ->
                classBuilder.addSuperinterface(
                    it
                )
            }
        classDef.javadoc.forEach(Consumer { format: String -> classBuilder.addKdoc(format) })
        if (classDef.superclass != null) {
            classBuilder.superclass(asType(classDef.superclass, classDef))
        }
        classDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
            .forEach { annotationSpec: AnnotationSpec -> classBuilder.addAnnotation(annotationSpec) }

        var companionBuilder: TypeSpec.Builder? = null
        buildProperties(classDef, classBuilder)
        companionBuilder = buildFields(classDef, companionBuilder, classBuilder)

        classDef.staticInitializer?.let { staticInitializerDef ->
            val clinit = MethodDef.builder("<clinit>").build()
            val currentCompanion = companionBuilder ?: TypeSpec.companionObjectBuilder().also {
                companionBuilder = it
            }
            currentCompanion.addInitializerBlock(
                renderStatementCodeBlock(classDef, clinit, RenderScope.root(clinit), staticInitializerDef)
            )
        }

        for (method in classDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                val currentCompanion = companionBuilder ?: TypeSpec.companionObjectBuilder().also {
                    companionBuilder = it
                }
                modifiers = stripStatic(modifiers)
                currentCompanion.addFunction(
                    buildFunction(classDef, method, modifiers)
                )
            } else if (method.name == "<init>") {
                val superCallStatement = method.statements.firstOrNull {
                    it is InvokeInstanceMethod && it.instance is VariableDef.Super && it.method.name == "<init>"
                } as? InvokeInstanceMethod
                val superCallStatement2 = method.statements.firstOrNull {
                    it is InvokeSuperConstructor
                } as? InvokeSuperConstructor
                if (superCallStatement2 != null) {
                    val superArgsCodeBlock = CodeBlock.builder()
                    for ((index, arg) in superCallStatement2.values.withIndex()) {
                        superArgsCodeBlock.add(renderExpressionCode(classDef, method, RenderScope.root(method), arg))
                        if (index < superCallStatement2.values.size - 1) {
                            superArgsCodeBlock.add(", ")
                        }
                    }
                    val constructorFunSpecBuilder = FunSpec.constructorBuilder()
                        .addModifiers(asKModifiers(method, modifiers))
                        .addParameters(
                            method.parameters.stream()
                                .map { param: ParameterDef ->
                                    ParameterSpec.builder(
                                        param.name,
                                        asType(param.type, classDef)
                                    ).build()
                                }.toList()
                        )
                    classBuilder.superclassConstructorParameters.add(superArgsCodeBlock.build())
                    classBuilder.primaryConstructor(constructorFunSpecBuilder.build())
                } else if (superCallStatement != null) {
                    val superArgsCodeBlock = CodeBlock.builder()
                    for ((index, arg) in superCallStatement.values.withIndex()) {
                        superArgsCodeBlock.add(renderExpressionCode(classDef, method, RenderScope.root(method), arg))
                        if (index < superCallStatement.values.size - 1) {
                            superArgsCodeBlock.add(", ")
                        }
                    }
                    val constructorFunSpecBuilder = FunSpec.constructorBuilder()
                        .addModifiers(asKModifiers(method, modifiers))
                        .addParameters(
                            method.parameters.stream()
                                .map { param: ParameterDef ->
                                    ParameterSpec.builder(
                                        param.name,
                                        asType(param.type, classDef)
                                    ).build()
                                }.toList()
                        )
                    classBuilder.superclassConstructorParameters.add(superArgsCodeBlock.build())
                    classBuilder.primaryConstructor(constructorFunSpecBuilder.build())
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
        companionBuilder?.let {
            classBuilder.addType(it.build())
        }
        addInnerTypes(classDef.innerTypes, classBuilder)
        return classBuilder
    }

    @Throws(IOException::class)
    private fun writeRecordDef(writer: Writer, recordDef: RecordDef) {
        val classBuilder = getRecordBuilder(recordDef)
        FileSpec.builder(recordDef.packageName, recordDef.simpleName + ".kt")
            .addType(classBuilder.build())
            .build()
            .writeTo(writer)
    }

    private fun getRecordBuilder(recordDef: RecordDef): TypeSpec.Builder {
        val classBuilder = TypeSpec.classBuilder(recordDef.simpleName)
        classBuilder.addModifiers(KModifier.DATA)
        classBuilder.addModifiers(asKModifiers(stripStatic(recordDef.modifiers)))
        recordDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> asTypeVariable(tv, recordDef) }
            .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
        recordDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(typeDef, recordDef) }
            .forEach { it: TypeName ->
                classBuilder.addSuperinterface(
                    it,
                )
            }
        recordDef.javadoc.forEach(Consumer { format: String -> classBuilder.addKdoc(format) })
        recordDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
            .forEach { annotationSpec: AnnotationSpec -> classBuilder.addAnnotation(annotationSpec) }

        var companionBuilder: TypeSpec.Builder? = null
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

        for (method in recordDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                modifiers = stripStatic(modifiers)
                companionBuilder.addFunction(
                    buildFunction(recordDef, method, modifiers)
                )
            } else {
                classBuilder.addFunction(
                    buildFunction(recordDef, method, modifiers)
                )
            }
        }
        if (companionBuilder != null) {
            classBuilder.addType(companionBuilder.build())
        }
        addInnerTypes(recordDef.innerTypes, classBuilder)
        return classBuilder
    }

    @Throws(IOException::class)
    private fun writeEnumDef(writer: Writer, enumDef: EnumDef) {
        val enumBuilder = getEnumBuilder(enumDef)
        FileSpec.builder(enumDef.packageName, enumDef.simpleName + ".kt")
            .addType(enumBuilder.build())
            .build()
            .writeTo(writer)
    }

    private fun getEnumBuilder(enumDef: EnumDef): TypeSpec.Builder {
        val enumBuilder = TypeSpec.enumBuilder(enumDef.simpleName)
        enumBuilder.addModifiers(asKModifiers(stripStatic(enumDef.modifiers)))
        enumDef.superinterfaces.stream().map { typeDef: TypeDef -> asType(typeDef, enumDef) }
            .forEach { it: TypeName -> enumBuilder.addSuperinterface(it) }
        enumDef.javadoc.forEach(Consumer { format: String -> enumBuilder.addKdoc(format) })
        enumDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
            .forEach { annotationSpec: AnnotationSpec -> enumBuilder.addAnnotation(annotationSpec) }

        enumDef.enumConstants.forEach { enumConstant: EnumConstantDef ->
            if (enumConstant.constructorArgs != null && enumConstant.constructorArgs.isNotEmpty()) {
                val exps = enumConstant.constructorArgs
                val expBuilder: CodeBlock.Builder = CodeBlock.builder()
                val constantInit = MethodDef.builder("").returns(TypeDef.VOID).build()
                for (i in exps.indices) {
                    expBuilder.add(
                        renderExpressionCode(
                            null,
                            constantInit,
                            RenderScope.root(constantInit),
                            exps[i]
                        )
                    )
                    if (i < exps.size - 1) {
                        expBuilder.add(", ")
                    }
                }
                enumBuilder.addEnumConstant(
                    enumConstant.name,
                    TypeSpec.companionObjectBuilder()
                        .addSuperclassConstructorParameter(expBuilder.build())
                        .build()
                )
            } else {
                enumBuilder.addEnumConstant(enumConstant.name)
            }
        }

        var companionBuilder: TypeSpec.Builder? = null
        buildProperties(enumDef, enumBuilder)
        companionBuilder = buildFields(enumDef, companionBuilder, enumBuilder)

        for (method in enumDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                modifiers = stripStatic(modifiers)
                companionBuilder.addFunction(
                    buildFunction(enumDef, method, modifiers)
                )
            } else {
                enumBuilder.addFunction(
                    buildFunction(enumDef, method, modifiers)
                )
            }
        }
        if (companionBuilder != null) {
            enumBuilder.addType(companionBuilder.build())
        }
        addInnerTypes(enumDef.innerTypes, enumBuilder)
        return enumBuilder
    }

    fun addInnerTypes(objectDefs: List<ObjectDef>, classBuilder: TypeSpec.Builder, isInterface: Boolean = false) {
        for (objectDef in objectDefs) {
            var innerBuilder: TypeSpec.Builder
            when (objectDef) {
                is ClassDef -> {
                    innerBuilder = getClassBuilder(objectDef)
                }

                is RecordDef -> {
                    innerBuilder = getRecordBuilder(objectDef)
                }

                is InterfaceDef -> {
                    innerBuilder = getInterfaceBuilder(objectDef)
                }

                is EnumDef -> {
                    innerBuilder = getEnumBuilder(objectDef)
                }

                is AnnotationObjectDef -> {
                    innerBuilder = getAnnotationObjectBuilder(objectDef)
                }

                else -> {
                    throw IllegalStateException("Unknown object definition: $objectDef")
                }
            }
            if (isInterface) {
                innerBuilder.addModifiers(KModifier.PUBLIC)
            }
            classBuilder.addType(innerBuilder.build())
        }
    }

    private fun buildProperties(
        objectDef: ObjectDef,
        builder: TypeSpec.Builder
    ) {
        val notNullProperties: MutableList<PropertyDef> = ArrayList()
        for (property in objectDef.properties) {
            var propertySpec: PropertySpec
            if (property.type.isNullable) {
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

    private fun buildFields(
        objectDef: ObjectDef,
        companionBuilder: TypeSpec.Builder?,
        builder: TypeSpec.Builder
    ): TypeSpec.Builder? {
        var companionBuilderTmp = companionBuilder
        var fields: List<FieldDef>
        if (objectDef is ClassDef)
            fields = objectDef.fields
        else if (objectDef is EnumDef)
            fields = objectDef.fields
        else return builder

        for (field in fields) {
            val modifiers = field.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilderTmp == null) {
                    companionBuilderTmp = TypeSpec.companionObjectBuilder()
                }
                companionBuilderTmp.addProperty(
                    buildProperty(field, stripStatic(modifiers), field.javadoc, objectDef)
                )
            } else {
                if (field.type.isNullable) {
                    builder.addProperty(
                        buildProperty(field, modifiers, field.javadoc, objectDef)
                    )
                } else {
                    builder.addProperty(
                        buildProperty(field, modifiers, field.javadoc, objectDef)
                    )
                }
            }
        }
        return companionBuilderTmp
    }

    private fun buildProperty(
        name: String,
        typeDef: TypeDef,
        modifiers: Set<Modifier>,
        annotations: List<AnnotationDef>,
        docs: List<String>, initializer: ExpressionDef?,
        objectDef: ObjectDef?,
        staticContext: Boolean = false,
        lateInit: Boolean = false,
    ): PropertySpec {
        val propertyBuilder = PropertySpec.builder(
            name,
            asType(typeDef, objectDef, staticContext),
            asKModifiers(modifiers)
        )
        docs.forEach(Consumer { format: String -> propertyBuilder.addKdoc(format) })

        if (!modifiers.contains(Modifier.FINAL) || lateInit) {
            propertyBuilder.mutable(true)
        }
        if (lateInit) {
            propertyBuilder.addModifiers(KModifier.LATEINIT)
        }
        for (annotation in annotations) {
            propertyBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            )
        }
        if (initializer != null) {
            val init = MethodDef.builder(name).returns(typeDef).build()
            propertyBuilder.initializer(
                renderExpressionCode(objectDef, init, RenderScope.root(init), initializer, typeDef)
            )
        } else if (typeDef.isNullable) {
            propertyBuilder.initializer("null")
        }
        return propertyBuilder.build()
    }

    private fun buildConstructorProperty(
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
        for (annotation in annotations) {
            propertyBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            )
        }
        return propertyBuilder
            .initializer(name)
            .build()
    }

    private fun buildProperty(
        field: FieldDef,
        modifiers: Set<Modifier>,
        docs: List<String>,
        objectDef: ObjectDef?
    ): PropertySpec {
        return buildProperty(
            field.name,
            field.type,
            modifiers,
            field.annotations,
            docs,
            field.initializer.orElse(defaultOf(field)),
            objectDef,
            field.modifiers.contains(Modifier.STATIC),
            // A Kotlin property must be initialized where it is declared; a field the model assigns
            // later, possibly more than once as in a try and its catch, is a lateinit var. A final instance field is
            // assigned by every constructor, which a val allows. A primitive cannot be lateinit - including a boxed
            // type, which Kotlin maps to its primitive - so it takes a default instead
            field.initializer.isEmpty && !field.type.isNullable
                && (field.modifiers.contains(Modifier.STATIC)
                    || !field.modifiers.contains(Modifier.FINAL) && !isAssignedByEveryConstructor(objectDef, field))
                && field.type !is TypeDef.Primitive && field.type !is TypeDef.TypeVariable
                && !isKotlinPrimitive(field.type),
        )
    }

    private fun buildFunction(objectDef: ObjectDef?, declaredMethod: MethodDef, modifiers: Set<Modifier>): FunSpec {
        // A model written for the bytecode writer overrides a generic method with its erased signature, which the
        // verifier accepts; Kotlin only overrides with the exact signature with the type arguments of the supertype.
        // The body is rendered against the resolved signature, so a returned value is cast to its type
        val method = OverrideResolver.resolve(objectDef, declaredMethod, VISITOR_CONTEXT.get(), true)
            ?.apply(declaredMethod) ?: declaredMethod
        var funBuilder = if (method.name == "<init>") {
            FunSpec.constructorBuilder()
        } else {
            FunSpec.builder(method.name).returns(asType(method.returnType, objectDef, method))
        }
        funBuilder = funBuilder
            .addModifiers(asKModifiers(method, modifiers))
            .addParameters(
                method.parameters.stream()
                    .map { param: ParameterDef ->
                        ParameterSpec.builder(
                            param.name,
                            asType(param.type, objectDef, method)
                        ).build()
                    }
                    .toList()
            )
        if (method.isOverride) {
            funBuilder.modifiers += KModifier.OVERRIDE
        }
        for (annotation in method.annotations) {
            funBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            )
        }
        if (method.throwTypes.isNotEmpty()) {
            funBuilder.addAnnotation(
                AnnotationSpec.builder(Throws::class)
                    .addMember(
                        method.throwTypes.joinToString { "%T::class" },
                        *method.throwTypes.map { asType(it, objectDef, method) }.toTypedArray()
                    )
                    .build(),
            )
        }
        val scope = RenderScope.root(method)
        val renderingObjectDef = if (method.modifiers.contains(Modifier.STATIC)) null else objectDef
        for ((index, statement) in method.statements.withIndex()) {
            funBuilder.addCode(renderStatementCodeBlock(renderingObjectDef, method, scope, statement,
                index == method.statements.size - 1))
            if (cannotCompleteNormally(statement)) {
                break
            }
        }
        method.javadoc.forEach(Consumer { format: String -> funBuilder.addKdoc(format) })
        return funBuilder.build()
    }

    companion object {
        private const val EXCEPTION_NAME = "e"

        // The context of the file being written, used to look up the supertypes of an override only known by name
        private val VISITOR_CONTEXT = ThreadLocal<VisitorContext>()

        private val FLOAT = ClassName("kotlin", "Float")

        private val DOUBLE = ClassName("kotlin", "Double")

        private val BOXED_PRIMITIVES = mapOf(
            "java.lang.Byte" to TypeDef.Primitive.BYTE,
            "java.lang.Short" to TypeDef.Primitive.SHORT,
            "java.lang.Character" to TypeDef.Primitive.CHAR,
            "java.lang.Integer" to TypeDef.Primitive.INT,
            "java.lang.Long" to TypeDef.Primitive.LONG,
            "java.lang.Float" to TypeDef.Primitive.FLOAT,
            "java.lang.Double" to TypeDef.Primitive.DOUBLE,
            "java.lang.Boolean" to TypeDef.Primitive.BOOLEAN
        )

        /**
         * Whether the type is one Kotlin maps to a primitive, which cannot be a lateinit property.
         */
        private fun isKotlinPrimitive(typeDef: TypeDef): Boolean {
            return typeDef is ClassTypeDef && BOXED_PRIMITIVES.containsKey(typeDef.name)
        }

        /**
         * The value a property of a primitive type is declared with, where the model assigns the field later.
         */
        private fun defaultOf(field: FieldDef): ExpressionDef? {
            // An instance property is assigned by the constructor the model writes; a static one has none
            if (!field.initializer.isEmpty || field.type.isNullable
                || !field.modifiers.contains(Modifier.STATIC)) {
                return null
            }
            val primitive = (field.type as? ClassTypeDef)?.let { BOXED_PRIMITIVES[it.name] }
                ?: field.type as? TypeDef.Primitive
                ?: return null
            return TypeDef.Primitive.defaultValue(primitive.name())
        }

        private val BOXED_NUMBERS = setOf(
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Number"
        )

        private fun stripStatic(modifiers: MutableSet<Modifier>): MutableSet<Modifier> {
            val mutable = HashSet(modifiers)
            mutable.remove(Modifier.STATIC)
            return mutable
        }

        private fun extendModifiers(modifiers: MutableSet<Modifier>, modifier: Modifier): Set<Modifier> {
            if (modifiers.contains(modifier)) {
                return modifiers
            }
            val mutable = HashSet(modifiers)
            mutable.add(modifier)
            return mutable
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asClassName(classType: ClassTypeDef): ClassName {
            val result = if (classType.isInner) {
                // Build ClassName deterministically from the binary name split on '$',
                // avoiding heuristics in ClassName.bestGuess() that rely on capitalisation.
                val binaryName = classType.name
                val dollarIndex = binaryName.indexOf('$')
                if (dollarIndex != -1) {
                    val outerFqn = binaryName.substring(0, dollarIndex)
                    val dotIndex = outerFqn.lastIndexOf('.')
                    val packageName = if (dotIndex == -1) "" else outerFqn.substring(0, dotIndex)
                    val outerSimpleName = if (dotIndex == -1) outerFqn else outerFqn.substring(dotIndex + 1)
                    val nestedNames = binaryName.substring(dollarIndex + 1).split('$').toTypedArray()
                    ClassName(packageName, outerSimpleName, *nestedNames)
                } else {
                    com.squareup.javapoet.ClassName.get(classType.packageName, classType.simpleName).toKClassName()
                }
            } else {
                com.squareup.javapoet.ClassName.get(classType.packageName, classType.simpleName).toKClassName()
            }.let {
                // Only kotlin.Throwable can be caught or thrown, java.lang.Throwable is mapped onto it
                if (it.canonicalName == "java.lang.Throwable") ClassName("kotlin", "Throwable") else it
            }
            if (result.isNullable) {
                return asNullable(result) as ClassName
            }
            return result
        }

        /**
         * The owner of a static call. A Java type that Kotlin maps onto one of its own, such as
         * `java.lang.String`, keeps its Java name here - the mapped Kotlin type does not declare the
         * static members, so `String.valueOf` has to be spelled `java.lang.String.valueOf`.
         *
         * @param classType The declaring type
         * @return The name to call the static member on
         */
        private fun asStaticOwnerName(classType: ClassTypeDef): ClassName {
            val mapped = asClassName(classType)
            if (classType.isInner || mapped.canonicalName == classType.canonicalName) {
                return mapped
            }
            return ClassName(classType.packageName, classType.simpleName)
        }

        private fun asNullable(kClassName: TypeName): TypeName {
            return kClassName.copy(true, kClassName.annotations, kClassName.tags)
        }

        private fun asKModifiers(methodDef: MethodDef, modifier: Collection<Modifier>): List<KModifier> {
            val modifiers = asKModifiers(modifier)
            if (methodDef.isOverride) {
                val mutableList = modifiers.toMutableList()
                mutableList.add(KModifier.OVERRIDE)
                return mutableList
            }
            return modifiers
        }

        private fun asKModifiers(modifier: Collection<Modifier>): List<KModifier> {
            return modifier.stream().map { m: Modifier ->
                when (m) {
                    Modifier.PUBLIC -> KModifier.PUBLIC
                    Modifier.PROTECTED -> KModifier.PROTECTED
                    Modifier.PRIVATE -> KModifier.PRIVATE
                    Modifier.ABSTRACT -> KModifier.ABSTRACT
                    Modifier.SEALED -> KModifier.SEALED
                    Modifier.FINAL -> KModifier.FINAL
                    else -> throw IllegalStateException("Not supported modifier: $m")
                }
            }.toList()
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asType(typeDef: TypeDef?, objectDef: ObjectDef?): TypeName {
            return asType(typeDef, objectDef, null, false)
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asType(typeDef: TypeDef?, objectDef: ObjectDef?, methodDef: MethodDef?): TypeName {
            return asType(
                typeDef,
                objectDef,
                methodDef,
                methodDef != null && methodDef.modifiers.contains(Modifier.STATIC),
            )
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asType(typeDef: TypeDef?, objectDef: ObjectDef?, staticContext: Boolean): TypeName {
            return asType(typeDef, objectDef, null, staticContext)
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asType(
            typeDef: TypeDef?,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): TypeName {
            val result: TypeName = when {
                typeDef == TypeDef.THIS -> asSelfType(objectDef, methodDef, staticContext)
                typeDef is TypeDef.Array -> asArray(typeDef, objectDef, methodDef, staticContext)
                typeDef is ClassTypeDef.Parameterized -> asClassName(typeDef.rawType).parameterizedBy(
                    typeDef.typeArguments.map { v: TypeDef -> this.asType(v, objectDef, methodDef, staticContext) }
                )
                typeDef is TypeDef.Primitive -> asPrimitive(typeDef)
                typeDef is ClassTypeDef -> asClassName(typeDef)
                typeDef is ClassTypeDef.AnnotatedClassTypeDef -> asAnnotated(
                    typeDef.typeDef, typeDef.annotations, objectDef, methodDef, staticContext
                )
                typeDef is TypeDef.Wildcard -> asWildcard(typeDef, objectDef, methodDef, staticContext)
                typeDef is TypeDef.TypeVariable ->
                    return asTypeVariableType(typeDef, objectDef, methodDef, staticContext)
                typeDef is TypeDef.Annotated && typeDef is TypeDef.AnnotatedTypeDef -> return asAnnotated(
                    typeDef.typeDef, typeDef.annotations, objectDef, methodDef, staticContext
                )
                else -> throw IllegalStateException("Unrecognized type definition $typeDef")
            }
            if (typeDef.isNullable) {
                return asNullable(result)
            }
            return result
        }

        /**
         * The self type in the scope it is written in. In a static context the enclosing definition is
         * dropped, so its type variables are not treated as being in scope.
         */
        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asSelfType(objectDef: ObjectDef?, methodDef: MethodDef?, staticContext: Boolean): TypeName {
            if (objectDef == null) {
                throw java.lang.IllegalStateException("This type is used outside of the instance scope!")
            }
            // The scope is kept: the self type of a generic definition carries the variables it declares
            return asType(objectDef.asTypeDef(), if (staticContext) null else objectDef, methodDef, staticContext)
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asAnnotated(
            typeDef: TypeDef,
            annotations: List<AnnotationDef>,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): TypeName = asType(typeDef, objectDef, methodDef, staticContext).copy(
            typeDef.isNullable,
            annotations.map { asAnnotationSpec(it) }
        )

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asWildcard(
            typeDef: TypeDef.Wildcard,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): TypeName = if (typeDef.lowerBounds.isNotEmpty()) {
            WildcardTypeName.consumerOf(asType(typeDef.lowerBounds[0], objectDef, methodDef, staticContext))
        } else {
            WildcardTypeName.producerOf(asType(typeDef.upperBounds[0], objectDef, methodDef, staticContext))
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asTypeVariableType(
            typeDef: TypeDef.TypeVariable,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): TypeName {
            if (isVariablePartOfTheDefinition(typeDef.name, objectDef, methodDef, staticContext)) {
                return asTypeVariable(typeDef, objectDef)
            }
            if (typeDef.bounds.isEmpty()) {
                return asType(TypeDef.OBJECT, objectDef, methodDef, staticContext)
            }
            return asType(typeDef.bounds[0], objectDef, methodDef, staticContext)
        }

        @OptIn(KotlinPoetJavaPoetPreview::class)
        private fun asPrimitive(typeDef: TypeDef.Primitive): TypeName = when (typeDef.name()) {
            "void" -> UNIT
            "byte" -> com.squareup.javapoet.TypeName.BYTE.toKTypeName()
            "short" -> com.squareup.javapoet.TypeName.SHORT.toKTypeName()
            "char" -> com.squareup.javapoet.TypeName.CHAR.toKTypeName()
            "int" -> com.squareup.javapoet.TypeName.INT.toKTypeName()
            "long" -> com.squareup.javapoet.TypeName.LONG.toKTypeName()
            "float" -> com.squareup.javapoet.TypeName.FLOAT.toKTypeName()
            "double" -> com.squareup.javapoet.TypeName.DOUBLE.toKTypeName()
            "boolean" -> com.squareup.javapoet.TypeName.BOOLEAN.toKTypeName()
            else -> unrecognizedPrimitive(typeDef.name())
        }

        private fun isVariablePartOfTheDefinition(
            variableName: String,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): Boolean {
            if (methodDef != null
                && methodDef.typeVariables.stream().anyMatch { v: TypeDef.TypeVariable -> v.name == variableName }
            ) {
                return true
            }
            if (staticContext) {
                return false
            }
            if (objectDef != null) {
                if (objectDef is ClassDef) {
                    return objectDef.typeVariables.stream()
                        .anyMatch { tv: TypeDef.TypeVariable -> tv.name == variableName }
                }
                if (objectDef is InterfaceDef) {
                    return objectDef.typeVariables.stream()
                        .anyMatch { tv: TypeDef.TypeVariable -> tv.name == variableName }
                }
                if (objectDef is RecordDef) {
                    return objectDef.typeVariables.stream()
                        .anyMatch { tv: TypeDef.TypeVariable -> tv.name == variableName }
                }
            }
            return false
        }

        private fun asTypeVariable(tv: TypeDef.TypeVariable, objectDef: ObjectDef?): TypeVariableName {
            return TypeVariableName(
                tv.name,
                tv.bounds.stream().map { v: TypeDef -> asType(v, objectDef) }.toList()
            )
        }

        private fun asArray(
            classType: TypeDef.Array,
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            staticContext: Boolean,
        ): TypeName {
            val componentType = classType.componentType
            // Kotlin has a dedicated type per primitive array, Array<Int> is an Integer[]
            val primitiveArray = primitiveArrayType(componentType)
            var newDef: TypeDef = primitiveArray?.let { ClassTypeDef.of(it) }
                ?: ClassTypeDef.Parameterized(ClassTypeDef.of("kotlin.Array"), listOf(componentType))
            for (i in 2..classType.dimensions) {
                newDef = ClassTypeDef.Parameterized(ClassTypeDef.of("kotlin.Array"), listOf(newDef))
            }
            return asType(newDef, objectDef, methodDef, staticContext)
        }

        /**
         * The type of an element of an array. `componentType` is always the innermost type, so for
         * anything past one dimension the element is itself an array.
         *
         * @param type The array type
         * @return The element type
         */
        private fun arrayElementType(type: TypeDef.Array): TypeDef =
            if (type.dimensions > 1) {
                TypeDef.Array(type.componentType, type.dimensions - 1, false)
            } else {
                type.componentType
            }

        /**
         * @param componentType The component of an array
         * @return The Kotlin type of an array of that component, or null if it is not a primitive
         */
        private fun primitiveArrayType(componentType: TypeDef): String? {
            if (componentType !is TypeDef.Primitive) {
                return null
            }
            return when (componentType.name()) {
                "byte" -> "kotlin.ByteArray"
                "short" -> "kotlin.ShortArray"
                "char" -> "kotlin.CharArray"
                "int" -> "kotlin.IntArray"
                "long" -> "kotlin.LongArray"
                "float" -> "kotlin.FloatArray"
                "double" -> "kotlin.DoubleArray"
                "boolean" -> "kotlin.BooleanArray"
                else -> unrecognizedPrimitive(componentType.name())
            }
        }

        /**
         * @param componentType The component of an array
         * @return The factory function creating an array of that component
         */
        private fun arrayOfFunction(componentType: TypeDef): String {
            if (componentType !is TypeDef.Primitive) {
                return "arrayOf"
            }
            return when (componentType.name()) {
                "byte" -> "byteArrayOf"
                "short" -> "shortArrayOf"
                "char" -> "charArrayOf"
                "int" -> "intArrayOf"
                "long" -> "longArrayOf"
                "float" -> "floatArrayOf"
                "double" -> "doubleArrayOf"
                "boolean" -> "booleanArrayOf"
                else -> unrecognizedPrimitive(componentType.name())
            }
        }

        private fun renderStatementCodeBlock(
            objectDef: @Nullable ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            statementDef: StatementDef?,
            // Whether nothing follows the statement in the body, so that returning is what falling out of it does
            tailPosition: Boolean = false
        ): CodeBlock {
            if (statementDef is Multi) {
                val builder: CodeBlock.Builder =
                    CodeBlock.builder()
                for ((index, statement) in statementDef.statements.withIndex()) {
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statement,
                        tailPosition && index == statementDef.statements.size - 1))
                    if (cannotCompleteNormally(statement)) {
                        // The model may append a fallback after an exhaustive statement, such as a return null
                        break
                    }
                }
                return builder.build()
            }
            val returnedVoid = (statementDef as? Return)?.expression?.takeIf { it.type() == TypeDef.VOID }
            if (returnedVoid != null) {
                // A void invocation cannot be returned in source. Where the statement is not in tail position - a
                // branch of a conditional, say - the call is followed by the return it stands for, which execution
                // would otherwise fall through
                val builder = CodeBlock.builder()
                    .addStatement("%L", renderExpressionCode(objectDef, methodDef, scope, returnedVoid))
                if (!tailPosition) {
                    builder.addStatement("return")
                }
                return builder.build()
            }
            if (statementDef is StatementDef.Try) {
                return renderTry(objectDef, methodDef, scope, statementDef, tailPosition)
            }
            if (statementDef is StatementDef.Synchronized) {
                val builder: CodeBlock.Builder = CodeBlock.builder()
                builder.add("synchronized(")
                builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.monitor(), true))
                builder.add(") {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement(), tailPosition))
                builder.unindent()
                builder.add("}\n")
                return builder.build()
            }
            if (statementDef is StatementDef.If) {
                val builder: CodeBlock.Builder =
                    CodeBlock.builder()
                builder.add("if (")
                builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.condition))
                builder.add(") {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement, tailPosition))
                builder.unindent()
                builder.add("}\n")
                return builder.build()
            }
            if (statementDef is StatementDef.IfElse) {
                val builder: CodeBlock.Builder = CodeBlock.builder()
                builder.add("if (")
                builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.condition))
                builder.add(") {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement, tailPosition))
                builder.unindent()
                builder.add("} else {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.elseStatement, tailPosition))
                builder.unindent()
                builder.add("}\n")
                return builder.build()
            }
            if (statementDef is StatementDef.Switch) {
                return renderSwitchStatement(objectDef, methodDef, scope, statementDef, tailPosition)
            }
            if (statementDef is While) {
                val builder: CodeBlock.Builder =
                    CodeBlock.builder()
                builder.add("while (")
                builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
                builder.add(") {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement))
                builder.unindent()
                builder.add("}\n")
                return builder.build()
            }
            return CodeBlock.builder()
                .addStatement("%L", renderStatement(objectDef, methodDef, scope, statementDef))
                .build()
        }

        private fun renderTry(
            objectDef: @Nullable ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            statementDef: StatementDef.Try,
            tailPosition: Boolean = false
        ): CodeBlock {
            val builder: CodeBlock.Builder = CodeBlock.builder()
            builder.add("try {\n")
            builder.indent()
            builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement(), tailPosition))
            builder.unindent()
            for (aCatch in statementDef.catches()) {
                // Kotlin warns about shadowing, so a nested catch gets a name of its own
                val exceptionLocal = scope.allocate(EXCEPTION_NAME)
                builder.add("} catch (%L: %T) {\n", exceptionLocal, asType(aCatch.exception(), objectDef))
                builder.indent()
                val catchScope = scope.nested(null)
                catchScope.rename(EXCEPTION_NAME, exceptionLocal)
                builder.add(renderStatementCodeBlock(objectDef, methodDef, catchScope, aCatch.statement(), tailPosition))
                builder.unindent()
            }
            val finallyStatement = statementDef.finallyStatement()
            if (finallyStatement != null) {
                builder.add("} finally {\n")
                builder.indent()
                // Never the tail: a return here discards an exception or a return of the try, which falling out of
                // the block does not
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, finallyStatement, false))
                builder.unindent()
            }
            builder.add("}\n")
            return builder.build()
        }

        private fun renderSwitchStatement(
            objectDef: @Nullable ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            statementDef: StatementDef.Switch,
            tailPosition: Boolean = false
        ): CodeBlock {
            val builder: CodeBlock.Builder = CodeBlock.builder()
            builder.add("when (")
            builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
            builder.add(") {\n")
            builder.indent()
            for ((key, statement) in statementDef.cases) {
                builder.add(renderConstantExpression(key, methodDef, scope))
                builder.add("-> {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statement, tailPosition))
                builder.unindent()
                builder.add("}\n")
            }
            if (statementDef.defaultCase != null) {
                builder.add("else -> {\n")
                builder.indent()
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.defaultCase, tailPosition))
                builder.unindent()
                builder.add("}\n")
            }
            builder.unindent()
            builder.add("}\n")
            return builder.build()
        }

        private fun renderStatement(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            statementDef: StatementDef?
        ): CodeBlock {
            if (statementDef is InvokeSuperConstructor) {
                val instanceExp = renderExpressionCode(objectDef, methodDef, scope, statementDef.superInstance())
                val codeBuilder = CodeBlock.builder()
                codeBuilder.add(instanceExp)
                codeBuilder.add("(")
                for ((index, parameter) in statementDef.values.withIndex()) {
                    codeBuilder.add(renderExpressionCode(objectDef, methodDef, scope, parameter))
                    if (index != statementDef.values.size - 1) {
                        codeBuilder.add(", ")
                    }
                }
                codeBuilder.add(")")
                return codeBuilder.build()
            }
            if (statementDef is Throw) {
                return CodeBlock.builder()
                    .add("throw ")
                    .add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
                    .build()
            }
            if (statementDef is Return) {
                var returned: ExpressionDef? = statementDef.expression
                if (returned != null && returned.type() == TypeDef.VOID) {
                    // Returning a void invocation is a plain call in source
                    return renderExpressionCode(objectDef, methodDef, scope, returned)
                }
                if (returned != null && methodDef.returnType != TypeDef.VOID
                    && requiresImplicitReturnCast(methodDef.returnType, returned.type())) {
                    returned = returned.cast(methodDef.returnType)
                }
                val codeBlock = renderExpressionWithNotNullAssertion(
                    objectDef,
                    methodDef,
                    scope,
                    returned,
                    methodDef.returnType
                )
                return CodeBlock.builder()
                    .add("return ")
                    .add(codeBlock)
                    .build()
            }
            if (statementDef is PutField) {
                val field = statementDef.field
                val variableExp = renderVariable(objectDef, methodDef, scope, field)
                val codeBuilder = variableExp.toBuilder()
                codeBuilder.add(" = ")
                codeBuilder.add(
                    renderExpressionCode(
                        objectDef,
                        methodDef,
                        scope,
                        statementDef.expression,
                        field.type()
                    )
                )
                return codeBuilder.build()
            }
            if (statementDef is PutStaticField) {
                val field = statementDef.field
                val variableExp = renderVariable(objectDef, methodDef, scope, field)
                val codeBuilder = variableExp.toBuilder()
                codeBuilder.add(" = ")
                codeBuilder.add(
                    renderExpressionCode(
                        objectDef,
                        methodDef,
                        scope,
                        statementDef.expression,
                        field.type()
                    )
                )
                return codeBuilder.build()
            }
            if (statementDef is Assign) {
                val variableExp = renderVariable(objectDef, methodDef, scope, statementDef.variable)
                val codeBuilder = variableExp.toBuilder()
                codeBuilder.add(" = ")
                codeBuilder.add(
                    renderExpressionCode(
                        objectDef,
                        methodDef,
                        scope,
                        statementDef.expression,
                        statementDef.variable.type()
                    )
                )
                return codeBuilder.build()
            }
            if (statementDef is DefineAndAssign) {
                val definition = CodeBlock.builder()
                    .add("var %N:%T", statementDef.variable.name, asType(statementDef.variable.type, objectDef))
                    .add(" = ")
                    .add(
                        renderExpressionCode(
                            objectDef,
                            methodDef,
                            scope,
                            statementDef.expression,
                            statementDef.variable.type
                        )
                    )
                    .build()
                // Declared only after the initializer is rendered - a lambda in it cannot see the variable
                scope.declare(statementDef.variable.name)
                return definition
            }
            if (statementDef is ExpressionDef) {
                return renderExpressionCode(objectDef, methodDef, scope, statementDef)
            }

            throw IllegalStateException("Unrecognized statement: $statementDef")
        }

        private fun renderYield(
            builder: CodeBlock.Builder,
            methodDef: MethodDef,
            scope: RenderScope,
            statementDef: StatementDef,
            objectDef: ObjectDef?
        ) {
            if (statementDef is StatementDef.Return) {
                builder.addStatement(
                    "%L",
                    CodeBlock.builder().add("return ")
                        .add(
                            renderExpressionCode(
                                objectDef,
                                methodDef,
                                scope,
                                statementDef.expression,
                                methodDef.returnType
                            )
                        )
                        .build()
                )
            } else {
                throw java.lang.IllegalStateException("The last statement of SwitchYieldCase should be a return. Found: $statementDef")
            }
        }

        private fun renderExpressionCode(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ExpressionDef?,
            expectedType: TypeDef
        ): CodeBlock {
            val codeBlock = renderExpressionCode(objectDef, methodDef, scope, expressionDef)
            val builder = codeBlock.toBuilder()
            if (!expectedType.isNullable && expressionDef?.type()?.isNullable == true) {
                builder.add("!!")
            }
            return builder.build()
        }

        private fun renderExpressionCode(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ExpressionDef?,
            isRef: Boolean = false
        ): CodeBlock {
            if (expressionDef is NewInstance) {
                val codeBuilder = CodeBlock.builder()
                codeBuilder.add("%T(", asClassName(expressionDef.type))
                codeBuilder.add(renderArguments(
                    objectDef, methodDef, scope, expressionDef.type, "<init>", null,
                    expressionDef.parameterTypes, expressionDef.values
                ))
                codeBuilder.add(")")
                return codeBuilder.build()
            }
            if (expressionDef is InvokeInstanceMethod) {
                var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
                val codeBuilder = CodeBlock.builder()
                if (expressionDef.method.name == "<init>") {
                    codeBuilder.add(instanceExp)
                    codeBuilder.add("(")
                } else {
                    if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
                        instanceExp = addParentheses(instanceExp)
                    }
                    codeBuilder.add(instanceExp)
                    if (expressionDef.instance is InvokeInstanceMethod) {
                        codeBuilder.add("\n")
                    }
                    codeBuilder.add(".%N(", expressionDef.method.name)
                }
                codeBuilder.add(renderArguments(
                    objectDef, methodDef, scope, ownerOf(objectDef, expressionDef.instance.type()),
                    expressionDef.method.name, expressionDef.method,
                    expressionDef.method.parameters.map { it.type }, expressionDef.values
                ))
                codeBuilder.add(")")
                return codeBuilder.build()
            }
            if (expressionDef is GetPropertyValue) {
                var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
                if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
                    instanceExp = addParentheses(instanceExp)
                }
                val codeBuilder = instanceExp.toBuilder()
                codeBuilder.add(".%L", expressionDef.propertyElement.name)
                return codeBuilder.build()
            }
            if (expressionDef is InvokeStaticMethod) {
                val codeBuilder = CodeBlock.builder()
                codeBuilder.add("%T.%N(", asStaticOwnerName(expressionDef.classDef), expressionDef.method.name)
                codeBuilder.add(renderArguments(
                    objectDef, methodDef, scope, expressionDef.classDef, expressionDef.method.name, null,
                    expressionDef.method.parameters.map { it.type }, expressionDef.values
                ))
                codeBuilder.add(")")
                return codeBuilder.build()
            }
            if (expressionDef is ArrayElement) {
                var array = renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression)
                if (requiresMethodCallTargetParentheses(expressionDef.expression)) {
                    array = addParentheses(array)
                }
                return array.toBuilder()
                    .add("[")
                    .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.indexExpression))
                    .add("]")
                    .build()
            }
            if (expressionDef is Cast) {
                val exp: ExpressionDef = collapseNestedCasts(expressionDef.expressionDef)
                if (expressionDef.type == exp.type() || isRef) {
                    return renderExpressionCode(objectDef, methodDef, scope, exp)
                }
                val castType = expressionDef.type
                val rendered = renderExpressionCode(objectDef, methodDef, scope, exp, castType)
                val conversion = primitiveConversion(castType, exp.type())
                if (conversion != null) {
                    // Kotlin has no primitive casts, a numeric conversion is a member function
                    var operand = rendered
                    if (requiresConversionTargetParentheses(unwrapCasts(exp))) {
                        operand = addParentheses(operand)
                    }
                    return operand.toBuilder().add(conversion).build()
                }
                val codeBuilder = CodeBlock.builder()
                if (requiresCastOperandParentheses(unwrapCasts(exp))) {
                    codeBuilder.add(addParentheses(rendered))
                } else {
                    codeBuilder.add(rendered)
                }
                codeBuilder.add(" as %T", asType(castType, objectDef))
                return codeBuilder.build()
            }
            if (expressionDef is VariableDef) {
                return renderVariable(objectDef, methodDef, scope, expressionDef)
            }
            if (expressionDef is Constant) {
                return renderConstantExpression(expressionDef, methodDef, scope)
            }
            if (expressionDef is And) {
                return CodeBlock.builder()
                    .add(renderAndConditionOperand(objectDef, methodDef, scope, expressionDef.left))
                    .add(" && ")
                    .add(renderAndConditionOperand(objectDef, methodDef, scope, expressionDef.right))
                    .build()
            }
            if (expressionDef is Or) {
                return CodeBlock.builder()
                    .add(renderCondition(objectDef, methodDef, scope, expressionDef.left))
                    .add(" || ")
                    .add(renderCondition(objectDef, methodDef, scope, expressionDef.right))
                    .build()
            }
            if (expressionDef is IfElse) {
                return CodeBlock.builder()
                    .add("if (")
                    .add(
                        renderExpressionCode(
                            objectDef,
                            methodDef,
                            scope,
                            expressionDef.condition,
                            TypeDef.Primitive.BOOLEAN
                        )
                    )
                    .add(") ")
                    .add(
                        renderExpressionCode(
                            objectDef,
                            methodDef,
                            scope,
                            expressionDef.ifExpression,
                            expressionDef.type()
                        )
                    )
                    .add(" else ")
                    .add(
                        renderExpressionCode(
                            objectDef,
                            methodDef,
                            scope,
                            expressionDef.elseExpression,
                            expressionDef.type()
                        )
                    )
                    .build()
            }
            if (expressionDef is Switch) {
                val builder: CodeBlock.Builder = CodeBlock.builder()
                builder.add("when (")
                builder.add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression))
                builder.add(") {\n")
                builder.indent()
                for ((key, value) in expressionDef.cases) {
                    builder.add(renderExpressionCode(objectDef, methodDef, scope, key))
                    builder.add(" -> ")
                    builder.add(renderExpressionCode(objectDef, methodDef, scope, value))
                    if (value is SwitchYieldCase) {
                        builder.add("\n")
                    } else {
                        builder.add(";\n")
                    }
                }
                if (expressionDef.defaultCase != null) {
                    builder.add("else -> ")
                    builder.add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.defaultCase))
                }
                builder.unindent()
                builder.add("}")
                return builder.build()
            }
            if (expressionDef is SwitchYieldCase) {
                val builder: CodeBlock.Builder = CodeBlock.builder()
                builder.add("{\n")
                builder.indent()
                val statement = expressionDef.statement
                val flatten = statement.flatten()
                check(!flatten.isEmpty()) { "SwitchYieldCase did not return any statements" }
                val last = flatten[flatten.size - 1]
                val rest: List<StatementDef> = flatten.subList(0, flatten.size - 1)
                val caseScope = scope.nested(null)
                for (statementDef in rest) {
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, caseScope, statementDef))
                }
                renderYield(builder, methodDef, caseScope, last, objectDef)
                builder.unindent()
                builder.add("}")
                val str: String = builder.build().toString()
                // Render the body to prevent nested statements
                return CodeBlock.of(str)
            }
            if (expressionDef is IsNull) {
                return CodeBlock.builder()
                    .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression, true))
                    .add(" == null")
                    .build()
            }
            if (expressionDef is IsNotNull) {
                return CodeBlock.builder()
                    .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression, true))
                    .add(" != null")
                    .build()
            }
            if (expressionDef is IsTrue) {
                val expression = unwrapCasts(expressionDef.expression)
                if (expression is ConditionExpressionDef) {
                    return renderExpressionCode(objectDef, methodDef, scope, expression)
                }
                return renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression)
            }
            if (expressionDef is IsFalse) {
                val expression = unwrapCasts(expressionDef.expression)
                if (expression is ConditionExpressionDef) {
                    return CodeBlock.builder()
                        .add("!")
                        .add(addParentheses(renderExpressionCode(objectDef, methodDef, scope, expression)))
                        .build()
                }
                return CodeBlock.builder()
                    .add("!")
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.expression))
                    .build()
            }
            if (expressionDef is InstanceOf) {
                return CodeBlock.builder()
                    .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression, true))
                    .add(" is %T", asTypeCheckType(expressionDef.instanceType, objectDef))
                    .build()
            }
            if (expressionDef is MathBinaryOperation) {
                return CodeBlock.builder()
                    .add(renderMathOperand(objectDef, methodDef, scope, expressionDef, expressionDef.left, false))
                    .add("%L", getMathOp(expressionDef.opType))
                    .add(renderMathOperand(objectDef, methodDef, scope, expressionDef, expressionDef.right, true))
                    .build()
            }
            if (expressionDef is MathUnaryOperation) {
                return CodeBlock.builder()
                    .add("%L", getMathOp(expressionDef.opType))
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.expression))
                    .build()
            }
            if (expressionDef is ComparisonOperation) {
                return CodeBlock.builder()
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.left))
                    .add("%L", getOpType(expressionDef.opType))
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.right))
                    .build()
            }
            if (expressionDef is NewArrayOfSize) {
                val componentType = arrayElementType(expressionDef.type)
                val primitiveArray = primitiveArrayType(componentType)
                if (primitiveArray != null) {
                    // A primitive array is sized rather than filled with nulls
                    return CodeBlock.of("%T(%L)", asType(ClassTypeDef.of(primitiveArray), objectDef), expressionDef.size)
                }
                return CodeBlock.of(
                    "arrayOfNulls<%T>(%L)",
                    asType(componentType, objectDef),
                    expressionDef.size
                )
            }
            if (expressionDef is NewArrayInitialized) {
                val componentType = arrayElementType(expressionDef.type)
                val builder: CodeBlock.Builder = CodeBlock.builder()
                if (componentType is TypeDef.Primitive) {
                    builder.add("%L(", arrayOfFunction(componentType))
                } else {
                    builder.add("arrayOf<%T>(", asType(componentType, objectDef))
                }
                val iterator: Iterator<ExpressionDef> = expressionDef.expressions.iterator()
                while (iterator.hasNext()) {
                    val expression = iterator.next()
                    builder.add(renderExpressionCode(objectDef, methodDef, scope, expression))
                    if (iterator.hasNext()) {
                        builder.add(", ")
                    }
                }
                builder.add(")")
                return builder.build()
            }
            if (expressionDef is InvokeGetClassMethod) {
                var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
                if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
                    instanceExp = addParentheses(instanceExp)
                }
                return instanceExp.toBuilder().add(".javaClass").build()
            }
            if (expressionDef is InvokeHashCodeMethod) {
                var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
                if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
                    instanceExp = addParentheses(instanceExp)
                }
                val type = expressionDef.instance.type()
                if (type.isArray) {
                    if (type is TypeDef.Array && type.dimensions > 1) {
                        return instanceExp.toBuilder().add(".contentDeepHashCode()").build()
                    }
                    return instanceExp.toBuilder().add(".contentHashCode()").build()
                }
                return instanceExp.toBuilder().add(".hashCode()").build()
            }
            if (expressionDef is EqualsStructurally) {
                val type = expressionDef.instance.type()
                if (type.isArray) {
                    val method = if (type is TypeDef.Array && type.dimensions > 1) {
                        ".contentDeepEquals("
                    } else {
                        ".contentEquals("
                    }
                    return CodeBlock.builder()
                        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance))
                        .add(method)
                        .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.other))
                        .add(")")
                        .build()
                }
                return CodeBlock.builder()
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance))
                    .add(" == ")
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.other))
                    .build()
            }
            if (expressionDef is NotEqualsStructurally) {
                val type = expressionDef.instance.type()
                if (type.isArray) {
                    val method = if (type is TypeDef.Array && type.dimensions > 1) {
                        ".contentDeepEquals("
                    } else {
                        ".contentEquals("
                    }
                    return CodeBlock.builder()
                        .add("!")
                        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance))
                        .add(method)
                        .add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.other))
                        .add(")")
                        .build()
                }
                return CodeBlock.builder()
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance))
                    .add(" != ")
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.other))
                    .build()
            }
            if (expressionDef is EqualsReferentially) {
                return CodeBlock.builder()
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance, true))
                    .add(" === ")
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.other, true))
                    .build()
            }
            if (expressionDef is NotEqualsReferentially) {
                return CodeBlock.builder()
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.instance, true))
                    .add(" !== ")
                    .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.other, true))
                    .build()
            }
            if (expressionDef is Lambda) {
                val implementation = expressionDef.implementation
                val lambdaScope = scope.nested(implementation)
                val builder = CodeBlock.builder()
                    .add("%T ", asType(expressionDef.type, objectDef))
                    .add("{")
                val parameter: Iterator<ParameterDef> = implementation.parameters.iterator()
                if (!parameter.hasNext()) {
                    builder.add("()")
                }
                while (parameter.hasNext()) {
                    val param = parameter.next()
                    val emittedName = if (scope.isTaken(param.name)) lambdaScope.allocate(param.name) else param.name
                    lambdaScope.rename(param.name, emittedName)
                    builder.add("%L: %T", emittedName, asType(param.type, objectDef))
                    if (parameter.hasNext()) {
                        builder.add(", ")
                    }
                }
                builder.add(" -> ")
                val statements: List<StatementDef> = implementation.statements
                if (statements.size == 1 && statements[0] is Return) {
                    val returnStatement = statements[0] as Return
                    builder.add(
                        renderExpressionCode(
                            objectDef,
                            implementation,
                            lambdaScope,
                            returnStatement.expression
                        )
                    )
                } else {
                    builder.add("{")
                    for (statement in statements) {
                        builder.add(renderStatementCodeBlock(objectDef, implementation, lambdaScope, statement))
                    }
                    builder.unindent()
                }
                return builder.add("}").build()
            }
            if (expressionDef is MethodReferenceExpression) {
                // A callable reference is not a functional interface on its own, so it is wrapped
                // in the SAM constructor of the interface being implemented
                val builder = CodeBlock.builder().add("%T(", asType(expressionDef.type(), objectDef))
                val instance = expressionDef.instance()
                when {
                    // Kotlin spells a constructor reference ::ClassName
                    expressionDef.isConstructor ->
                        builder.add("::%T", asType(expressionDef.owner(), objectDef))

                    instance != null -> builder
                        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, instance, true))
                        .add("::%L", expressionDef.method().name)

                    else ->
                        builder.add("%T::%L", asType(expressionDef.owner(), objectDef), expressionDef.method().name)
                }
                return builder.add(")").build()
            }
            if (expressionDef is StringConcatenation) {
                var left: ExpressionDef = expressionDef.left()
                if (left.type() != TypeDef.STRING && !(expressionDef.right().type().equals(TypeDef.STRING))) {
                    left = TypeDef.STRING.invokeStatic("valueOf", TypeDef.STRING, left)
                }
                return CodeBlock.builder()
                    .add(renderExpressionCode(objectDef, methodDef, scope, left))
                    .add(" + ")
                    .add(renderConcatenationOperand(objectDef, methodDef, scope, expressionDef.right()))
                    .build()
            }
            throw IllegalStateException("Unrecognized expression: $expressionDef")
        }

        private fun renderConcatenationOperand(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            operand: ExpressionDef
        ): CodeBlock {
            val rendered = renderExpressionCode(objectDef, methodDef, scope, operand)
            if (unwrapCasts(operand) is StringConcatenation) {
                return addParentheses(rendered)
            }
            return rendered
        }

        private fun renderAndConditionOperand(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ConditionExpressionDef
        ): CodeBlock {
            val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef)
            if (isOrCondition(expressionDef)) {
                return addParentheses(rendered)
            }
            return rendered
        }

        private fun isOrCondition(expressionDef: ConditionExpressionDef): Boolean {
            if (expressionDef is Or) {
                return true
            }
            if (expressionDef is IsTrue) {
                val expression = unwrapCasts(expressionDef.expression)
                return expression is ConditionExpressionDef && isOrCondition(expression)
            }
            return false
        }

        private fun renderMathOperand(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            parent: MathBinaryOperation,
            operand: ExpressionDef,
            rightOperand: Boolean
        ): CodeBlock {
            if (operand is MathBinaryOperation) {
                val rendered = renderExpressionCode(objectDef, methodDef, scope, operand)
                if (requiresMathParentheses(parent, operand, rightOperand)) {
                    return addParentheses(rendered)
                }
                return rendered
            }
            return renderExpressionWithParentheses(objectDef, methodDef, scope, operand)
        }

        private fun requiresMathParentheses(
            parent: MathBinaryOperation,
            child: MathBinaryOperation,
            rightOperand: Boolean
        ): Boolean {
            val parentPrecedence = mathPrecedence(parent.opType)
            val childPrecedence = mathPrecedence(child.opType)
            return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence)
        }

        /**
         * The Kotlin precedence of a binary operation. The bitwise operations are named infix functions,
         * which all share one precedence level below the arithmetic operators.
         */
        private fun mathPrecedence(opType: MathBinaryOperation.OpType): Int {
            return when (opType) {
                MathBinaryOperation.OpType.MULTIPLICATION,
                MathBinaryOperation.OpType.DIVISION,
                MathBinaryOperation.OpType.MODULUS -> 3

                MathBinaryOperation.OpType.ADDITION,
                MathBinaryOperation.OpType.SUBTRACTION -> 2

                else -> 1
            }
        }

        private fun renderExpressionWithParentheses(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ExpressionDef,
            isRef: Boolean = false
        ): CodeBlock {
            val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef, isRef)
            if (!requiresParentheses(expressionDef)) {
                return rendered
            }
            return addParentheses(rendered)
        }

        private fun requiresParentheses(expressionDef: ExpressionDef): Boolean {
            val expression = unwrapCasts(expressionDef)
            if (expression is InvokeHashCodeMethod) {
                val type = expression.instance().type()
                return !type.isPrimitive && !type.isArray
            }
            return !(expression is StatementDef
                || expression is VariableDef
                || expression is And
                || expression is Constant
                || expression is GetPropertyValue
                || expression is InvokeGetClassMethod
                || expression is ArrayElement
                || expression is NewArrayOfSize
                || expression is NewArrayInitialized
                || expression is NewInstance
                || expression is Switch)
        }

        private fun requiresMethodCallTargetParentheses(expressionDef: ExpressionDef): Boolean {
            return expressionDef is Cast
                || expressionDef is IfElse
                || expressionDef is StringConcatenation
                || expressionDef is Switch
                || expressionDef is MathBinaryOperation
                || expressionDef is MathUnaryOperation
                || expressionDef is ConditionExpressionDef
        }

        /**
         * A conversion is a member call, and both `.` and a prefix minus bind tighter than the
         * binary operators, so anything looser than a postfix expression has to be wrapped.
         */
        private fun requiresConversionTargetParentheses(expressionDef: ExpressionDef): Boolean {
            return requiresMethodCallTargetParentheses(expressionDef)
                || isNegativeNumericConstant(expressionDef)
        }

        private fun isNegativeNumericConstant(expressionDef: ExpressionDef): Boolean {
            return expressionDef is Constant
                && expressionDef.value is Number
                && expressionDef.value.toString().startsWith("-")
        }

        private fun requiresCastOperandParentheses(expressionDef: ExpressionDef): Boolean {
            return expressionDef is ConditionExpressionDef
                || expressionDef is IfElse
                || expressionDef is MathBinaryOperation
                || expressionDef is StringConcatenation
                || expressionDef is Switch
        }

        private fun unwrapCasts(expressionDef: ExpressionDef): ExpressionDef {
            var expression = expressionDef
            while (expression is Cast) {
                expression = expression.expressionDef()
            }
            return expression
        }

        private fun collapseNestedCasts(expressionDef: ExpressionDef): ExpressionDef {
            var expression = expressionDef
            while (expression is Cast) {
                if (expression.type().isPrimitive) {
                    val previousCastType = expression.expressionDef().type()
                    if (previousCastType != TypeDef.OBJECT) {
                        break
                    }
                }
                // Only keep the last cast
                expression = expression.expressionDef()
            }
            return expression
        }

        /**
         * Kotlin has no primitive casts. A conversion between two number-like types is a member
         * function, so a cast of one is emitted as a call instead of an `as` expression.
         *
         * @return The conversion to append, or null if the cast should be emitted as `as`
         */
        private fun primitiveConversion(castType: TypeDef, sourceType: TypeDef): String? {
            if (castType !is TypeDef.Primitive || !isNumberLike(sourceType)) {
                return null
            }
            if (sourceType.isPrimitive && (sourceType as TypeDef.Primitive).name() == "char") {
                // Char has no numeric conversions of its own, its code goes through Int
                return when (castType.name()) {
                    "char" -> null
                    "int" -> ".code"
                    else -> ".code" + numberConversion(castType.name())
                }
            }
            return when (castType.name()) {
                "boolean" -> null
                // Only Int declares toChar, the others are deprecated
                "char" -> if (sourceType.isPrimitive && (sourceType as TypeDef.Primitive).name() == "int") {
                    ".toChar()"
                } else {
                    ".toInt().toChar()"
                }

                else -> numberConversion(castType.name())
            }
        }

        /**
         * @param name The primitive name that no lookup recognized
         * @return Never, the name is not a primitive
         */
        private fun unrecognizedPrimitive(name: String): Nothing =
            error("Unrecognized primitive name: $name")

        private fun numberConversion(name: String): String {
            return when (name) {
                "byte" -> ".toByte()"
                "short" -> ".toShort()"
                "int" -> ".toInt()"
                "long" -> ".toLong()"
                "float" -> ".toFloat()"
                "double" -> ".toDouble()"
                else -> unrecognizedPrimitive(name)
            }
        }

        private fun isNumberLike(typeDef: TypeDef): Boolean {
            if (typeDef is TypeDef.Primitive) {
                return typeDef.name() != "boolean"
            }
            return typeDef is ClassTypeDef && BOXED_NUMBERS.contains(typeDef.name)
        }

        private fun addParentheses(rendered: CodeBlock): CodeBlock {
            return CodeBlock.builder().add("(").add(rendered).add(")").build()
        }

        private fun getMathOp(opType: MathBinaryOperation.OpType): String {
            return when (opType) {
                MathBinaryOperation.OpType.ADDITION -> " + "
                MathBinaryOperation.OpType.SUBTRACTION -> " - "
                MathBinaryOperation.OpType.MULTIPLICATION -> " * "
                MathBinaryOperation.OpType.DIVISION -> " / "
                MathBinaryOperation.OpType.MODULUS -> " % "
                // Kotlin spells the bitwise operations as infix functions
                MathBinaryOperation.OpType.BITWISE_AND -> " and "
                MathBinaryOperation.OpType.BITWISE_OR -> " or "
                MathBinaryOperation.OpType.BITWISE_XOR -> " xor "
                MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT -> " shl "
                MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT -> " shr "
                MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT -> " ushr "
            }
        }

        private fun getMathOp(opType: MathUnaryOperation.OpType): String {
            return when (opType) {
                MathUnaryOperation.OpType.NEGATE -> "-"
            }
        }

        private fun getOpType(opType: ComparisonOperation.OpType): String {
            return when (opType) {
                ComparisonOperation.OpType.EQUAL_TO -> " == "
                ComparisonOperation.OpType.NOT_EQUAL_TO -> " != "
                ComparisonOperation.OpType.GREATER_THAN -> " > "
                ComparisonOperation.OpType.LESS_THAN -> " < "
                ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL -> " >= "
                ComparisonOperation.OpType.LESS_THAN_OR_EQUAL -> " <= "
            }
        }

        private fun renderCondition(
            objectDef: @Nullable ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ExpressionDef
        ): CodeBlock {
            val needsParentheses = expressionDef is And
            val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef)
            if (needsParentheses) {
                return addParentheses(rendered)
            }
            return rendered
        }

        private fun renderConstantExpression(
            constant: Constant,
            methodDef: MethodDef,
            scope: RenderScope
        ): CodeBlock {
            val type = constant.type
            val value = constant.value ?: return CodeBlock.of("null")
            if (type is ClassTypeDef && type.isEnum) {
                return renderExpressionCode(
                    null, methodDef, scope, VariableDef.StaticField(
                        type,
                        if (value is Enum<*>) value.name else value.toString(),
                        type
                    )
                )
            }
            if (type is TypeDef.Primitive) {
                return renderPrimitiveConstant(type.name(), value)
            } else if (type is TypeDef.Array) {
                if (value.javaClass.isArray) {
                    val builder = CodeBlock.builder()
                    val length = Array.getLength(value)
                    val componentType = type.componentType
                    for (i in 0 until length) {
                        builder.add(
                            renderConstantExpression(
                                Constant(componentType, Array.get(value, i)),
                                methodDef,
                                scope
                            )
                        )
                        if (i + 1 != length) {
                            builder.add(", ")
                        }
                    }
                    val result = CodeBlock.builder()
                    if (componentType is TypeDef.Primitive) {
                        result.add("%L(", arrayOfFunction(componentType))
                    } else {
                        result.add("arrayOf<%T>(", asType(componentType, null))
                    }
                    return result.add(builder.build()).add(")").build()
                }
            } else if (type is ClassTypeDef) {
                if (value is TypeDef) {
                    return CodeBlock.of("%T::class.java", asType(value, null))
                }
                val name = type.name
                return if (ClassUtils.isJavaLangType(name)) {
                    when (name) {
                        "java.lang.Byte" -> renderPrimitiveConstant("byte", value)
                        "java.lang.Short" -> renderPrimitiveConstant("short", value)
                        "java.lang.Character" -> renderPrimitiveConstant("char", value)
                        "java.lang.Long" -> renderPrimitiveConstant("long", value)
                        "java.lang.Float" -> renderPrimitiveConstant("float", value)
                        "java.lang.Double" -> renderPrimitiveConstant("double", value)
                        "java.lang.String" -> CodeBlock.of("%S", value)
                        else -> CodeBlock.of("%L", value)
                    }
                } else {
                    CodeBlock.of("%L", value)
                }
            }
            throw IllegalStateException("Unrecognized expression: $constant")
        }

        private fun renderPrimitiveConstant(name: String, value: Any): CodeBlock {
            return when (name) {
                // Kotlin only accepts an upper case long suffix, and a byte or a short is written as
                // an integer literal - the expected type converts it
                "long" -> CodeBlock.of("%LL", value)
                "float" -> asFloatingPointLiteral(value, FLOAT)
                "double" -> asFloatingPointLiteral(value, DOUBLE)
                "char" -> CodeBlock.of("'%L'", characterLiteralWithoutSingleQuotes(asChar(value)))
                else -> CodeBlock.of("%L", value)
            }
        }

        private fun asChar(value: Any): Char {
            return when (value) {
                is Char -> value
                is Number -> value.toInt().toChar()
                else -> error("Expected a character constant; got: $value")
            }
        }

        /**
         * A floating point literal. Kotlin has no suffix for a double, needs a fraction to infer one
         * and spells the non-finite values as constants of the type.
         *
         * @param value The value
         * @param type  The Kotlin type declaring the non-finite constants
         * @return The literal
         */
        private fun asFloatingPointLiteral(value: Any, type: ClassName): CodeBlock {
            val number = value as? Number
                ?: throw IllegalStateException("Expected a floating point constant; got: $value")
            val suffix = if (type == FLOAT) "f" else ""
            val literal = when {
                number.toDouble().isNaN() -> return CodeBlock.of("%T.NaN", type)
                number.toDouble() == Double.POSITIVE_INFINITY -> return CodeBlock.of("%T.POSITIVE_INFINITY", type)
                number.toDouble() == Double.NEGATIVE_INFINITY -> return CodeBlock.of("%T.NEGATIVE_INFINITY", type)
                else -> number.toString()
            }
            if (literal.contains('.') || literal.contains('e') || literal.contains('E')) {
                return CodeBlock.of("%L%L", literal, suffix)
            }
            return CodeBlock.of("%L.0%L", literal, suffix)
        }

        private fun renderVariable(
            objectDef: ObjectDef?,
            methodDef: MethodDef?,
            scope: RenderScope,
            variableDef: VariableDef
        ): CodeBlock {
            if (variableDef is VariableDef.ExceptionVar) {
                val name = scope.resolveRename(EXCEPTION_NAME)
                checkNotNull(name) { "The exception variable is only available in a catch block" }
                return CodeBlock.of("%L", name)
            }
            if (variableDef is VariableDef.MethodParameter) {
                checkNotNull(methodDef) { "Accessing method parameters is not available" }
                // The parameter can belong to an enclosing method - a lambda body can capture one
                val name = scope.resolveParameter(variableDef.name)
                checkNotNull(name) {
                    "Method: " + methodDef.name + " doesn't have parameter: " + variableDef.name
                }
                return CodeBlock.of("%N", name)
            }
            if (variableDef is VariableDef.Field) {
                checkNotNull(objectDef) { "Field 'this' is not available" }
                // Only a field declared by the type being written can be checked against its definition
                if ((variableDef.declaringType as? ClassTypeDef)?.name == objectDef.asTypeDef().name) {
                    if (objectDef is ClassDef) {
                        objectDef.getField(variableDef.name) // Check if exists
                    } else if (objectDef is EnumDef) {
                        objectDef.getField(variableDef.name) // Check if exists
                    } else {
                        throw IllegalStateException("Field access not supported on the object definition: $objectDef")
                    }
                }
                checkNotNull(methodDef) { "Accessing field is not available" }
                var codeBlock = renderExpressionCode(objectDef, methodDef, scope, variableDef.instance)
                if (requiresMethodCallTargetParentheses(variableDef.instance)) {
                    codeBlock = addParentheses(codeBlock)
                }
                val builder = codeBlock.toBuilder()
                if (variableDef.instance.type().isNullable) {
                    builder.add("!!")
                }
                builder.add(". %N", variableDef.name)
                return builder.build()
            }
            if (variableDef is VariableDef.StaticField) {
                return CodeBlock.of(
                    "%T.%L",
                    asType(variableDef.ownerType, objectDef),
                    variableDef.name
                )
            }
            if (variableDef is VariableDef.This) {
                checkNotNull(objectDef) { "Accessing 'this' is not available" }
                return CodeBlock.of("this")
            }
            if (variableDef is VariableDef.Local) {
                return CodeBlock.of("%N", variableDef.name)
            }
            if (variableDef is VariableDef.Super) {
                checkNotNull(objectDef) { "Accessing 'super' is not available" }
                // The bytecode model names Object to pick the invokespecial owner, but Any is never an
                // immediate supertype that `super<Any>` could name
                if (variableDef.type() !== TypeDef.SUPER && variableDef.type != TypeDef.OBJECT) {
                    return CodeBlock.of("super<%T>", asType(variableDef.type, objectDef))
                }
                return CodeBlock.of("super");
            }
            throw IllegalStateException("Unrecognized variable: $variableDef")
        }

        /**
         * Renders call arguments, casting an `Object` value passed to a narrower parameter: the
         * verifier accepts it, the Kotlin compiler does not.
         */
        private fun renderArguments(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            owner: ClassTypeDef?,
            methodName: String?,
            callMethod: MethodDef?,
            parameterTypes: List<TypeDef>?,
            values: List<ExpressionDef>
        ): CodeBlock {
            val builder = CodeBlock.builder()
            // A method of this class that override resolution narrowed is written with the narrowed parameters,
            // which the values passed to it are converted to
            val target = OverrideResolver.definitionOf(owner, objectDef)
            val emittedTypes = if (callMethod != null && target != null) {
                OverrideResolver.emittedParameterTypes(target, callMethod, VISITOR_CONTEXT.get(), true)
            } else {
                null
            }
            val sameArityTypes = (emittedTypes ?: parameterTypes)?.takeIf { it.size == values.size }
            // Only a method whose signature says so takes varargs
            val signature = if (methodName != null && sameArityTypes != null) {
                InvokedSignature.resolve(owner, methodName, sameArityTypes, VISITOR_CONTEXT.get())
            } else {
                null
            }
            val varargs = signature?.varargs == true
            for ((index, value) in values.withIndex()) {
                if (index > 0) {
                    builder.add(", ")
                }
                val parameterType = sameArityTypes?.get(index)
                val sourceType = sourceTypeOf(value, methodDef, objectDef)
                val vararg = varargs && index == values.size - 1 && parameterType is TypeDef.Array
                if (parameterType != null && !vararg
                    && sourceType != value.type() && parameterType != sourceType) {
                    // An override narrowed the parameter the value names - `Any` to `String` - which would select
                    // another overload than the one the model calls: keep its type. Written out, since in the model
                    // the cast is to the type the value already has, which is dropped
                    builder.add("(")
                    builder.add(renderExpressionCode(objectDef, methodDef, scope, value))
                    builder.add(" as %T)", asType(parameterType, objectDef))
                    continue
                }
                val argument = if (parameterType != null && (requiresImplicitCast(parameterType, value.type())
                        || !vararg && parameterType is TypeDef.Array && value.type() == TypeDef.OBJECT)) {
                    value.cast(parameterType)
                } else {
                    value
                }
                builder.add(renderExpressionCode(objectDef, methodDef, scope, argument))
            }
            return builder.build()
        }

        /**
         * The type a value has in the source: that of the parameter it names, which an override can have narrowed
         * from the type the model built the value with. A cast to the type the value already has is not written.
         */
        private fun sourceTypeOf(value: ExpressionDef, methodDef: MethodDef, objectDef: ObjectDef?): TypeDef {
            if (value is Cast) {
                return if (value.type == value.expressionDef.type()) {
                    sourceTypeOf(value.expressionDef, methodDef, objectDef)
                } else {
                    value.type
                }
            }
            if (value is InvokeInstanceMethod && !value.method.isConstructor) {
                // The result of a generated method that override resolution narrowed has the narrowed type
                val target = OverrideResolver.definitionOf(ownerOf(objectDef, value.instance.type()), objectDef)
                if (target != null) {
                    OverrideResolver.emittedSignature(target, value.method, VISITOR_CONTEXT.get(), true)
                        ?.let { return it.returnType }
                }
            }
            if (value is VariableDef.MethodParameter) {
                methodDef.parameters.firstOrNull { it.name == value.name }?.let { return it.type }
            }
            return value.type()
        }

        /**
         * The type declaring an invoked method: the class being written or its superclass for `this` and `super`,
         * which the model names by placeholders.
         */
        private fun ownerOf(objectDef: ObjectDef?, type: TypeDef): ClassTypeDef? {
            val resolved = if (objectDef != null && objectDef !is InterfaceDef
                && (type == TypeDef.THIS || type == TypeDef.SUPER)) {
                objectDef.getContextualType(type)
            } else {
                type
            }
            return (resolved as? ClassTypeDef)?.takeIf { it != TypeDef.THIS && it != TypeDef.SUPER }
        }

        private fun requiresImplicitCast(targetType: TypeDef, valueType: TypeDef): Boolean =
            valueType == TypeDef.OBJECT && targetType != TypeDef.OBJECT
                && (targetType is ClassTypeDef || targetType is TypeDef.Primitive && targetType != TypeDef.VOID)

        /**
         * Whether a returned value needs a cast to the return type. Unlike an argument - where an array parameter
         * may be a vararg, which takes the value as an element - an array return type is cast to as well, as is a
         * type variable an override is resolved to.
         */
        private fun requiresImplicitReturnCast(returnType: TypeDef, valueType: TypeDef): Boolean {
            if (requiresImplicitCast(returnType, valueType)) {
                return true
            }
            if (valueType == TypeDef.OBJECT && (returnType is TypeDef.Array || returnType is TypeDef.TypeVariable)) {
                return true
            }
            if (returnType is TypeDef.Array && valueType is TypeDef.Array
                && returnType.dimensions == valueType.dimensions
                && returnType.componentType != valueType.componentType) {
                // An array of the erased bound, returned where the override narrows it: `Array<CharSequence>` as
                // `Array<String>` - Kotlin arrays are invariant
                return true
            }
            // A value typed with the bound an override's return type was erased to: `CharSequence` for the
            // `String` of a `Bounded<String>`, or for a `T : CharSequence`
            if (returnType is TypeDef.TypeVariable) {
                return valueType != returnType && (valueType is ClassTypeDef || valueType is TypeDef.Array)
            }
            return returnType is ClassTypeDef.JavaClass && valueType is ClassTypeDef.JavaClass
                && !returnType.type.isAssignableFrom(valueType.type)
        }

        /**
         * Whether every constructor of the definition assigns the field, which lets it be declared without an
         * initializer - and without lateinit, which an annotation such as @JvmField does not allow.
         */
        private fun isAssignedByEveryConstructor(objectDef: ObjectDef?, field: FieldDef): Boolean {
            val constructors = objectDef?.methods?.filter { it.isConstructor } ?: return false
            return constructors.isNotEmpty() && constructors.all { constructor ->
                val assignment = assignmentOf(StatementDef.multi(constructor.statements), field.name, false)
                assignment.assigned && !assignment.returnsUnassigned
            }
        }

        /**
         * How a statement assigns the field of this instance - not one of another object that shares its name.
         *
         * @property assigned          Whether the field is assigned on every path that completes the statement
         *                             normally - vacuously where none does
         * @property returnsUnassigned Whether a path returns from the constructor before assigning it
         * @property completes         Whether some path completes the statement normally
         */
        private data class Assignment(val assigned: Boolean, val returnsUnassigned: Boolean, val completes: Boolean)

        /**
         * @param assigned Whether the field is assigned by the time the statement is reached
         */
        private fun assignmentOf(statement: StatementDef?, name: String, assigned: Boolean): Assignment = when (statement) {
            null -> Assignment(assigned, false, true)
            is StatementDef.PutField -> Assignment(
                assigned || statement.field.name == name && statement.field.instance is VariableDef.This, false, true
            )
            // Nothing completes past either; a return ends the constructor with the field as it is
            is Return -> Assignment(true, !assigned, false)
            is Throw -> Assignment(true, false, false)
            is Multi -> {
                var current = assigned
                var returnsUnassigned = false
                var completes = true
                for (child in statement.statements) {
                    val assignment = assignmentOf(child, name, current)
                    current = assignment.assigned
                    returnsUnassigned = returnsUnassigned || assignment.returnsUnassigned
                    if (!assignment.completes || cannotCompleteNormally(child)) {
                        // The statements after it are not rendered
                        completes = false
                        break
                    }
                }
                Assignment(current, returnsUnassigned, completes)
            }
            is StatementDef.If -> {
                val then = assignmentOf(statement.statement, name, assigned)
                Assignment(assigned, then.returnsUnassigned, true)
            }
            is StatementDef.IfElse -> {
                val then = assignmentOf(statement.statement, name, assigned)
                val otherwise = assignmentOf(statement.elseStatement, name, assigned)
                Assignment(
                    then.assigned && otherwise.assigned,
                    then.returnsUnassigned || otherwise.returnsUnassigned,
                    then.completes || otherwise.completes
                )
            }
            is StatementDef.Switch -> {
                val cases = statement.cases.values.map { assignmentOf(it, name, assigned) }
                val default = statement.defaultCase?.let { assignmentOf(it, name, assigned) }
                // Without a default, the path no case matches continues with the state it came in with
                Assignment(
                    (default?.assigned ?: assigned) && cases.all { it.assigned },
                    cases.any { it.returnsUnassigned } || default?.returnsUnassigned == true,
                    default == null || default.completes || cases.any { it.completes }
                )
            }
            is StatementDef.While ->
                Assignment(assigned, assignmentOf(statement.statement, name, assigned).returnsUnassigned, true)
            is StatementDef.Synchronized -> assignmentOf(statement.statement(), name, assigned)
            is StatementDef.Try -> {
                val body = assignmentOf(statement.statement(), name, assigned)
                val catches = statement.catches().map { assignmentOf(it.statement(), name, assigned) }
                val completed = body.assigned && catches.all { it.assigned }
                // The finally follows the paths of the try and its catches that complete, with what they assigned;
                // where none does - a body that only throws - it starts with the state before the try
                val completing = (listOf(body) + catches).filter { it.completes }
                val entering = if (completing.isEmpty()) assigned else completing.all { it.assigned }
                val finallyAssignment = assignmentOf(statement.finallyStatement(), name, entering)
                // A return in the try or a catch runs the finally before it leaves
                val returnsInside = body.returnsUnassigned || catches.any { it.returnsUnassigned }
                val finallyAssigns = statement.finallyStatement() != null
                    && assignmentOf(statement.finallyStatement(), name, false).assigned
                Assignment(
                    completed || finallyAssignment.assigned,
                    returnsInside && !finallyAssigns || finallyAssignment.returnsUnassigned,
                    completing.isNotEmpty() && finallyAssignment.completes
                )
            }
            else -> Assignment(assigned, false, true)
        }

        /**
         * The type of an `is` check: Kotlin names every type argument, so a raw generic type is
         * checked with star projections.
         */
        private fun asTypeCheckType(typeDef: TypeDef, objectDef: ObjectDef?): TypeName {
            if (typeDef is ClassTypeDef.JavaClass && typeDef.type.typeParameters.isNotEmpty()) {
                return asClassName(typeDef).parameterizedBy(typeDef.type.typeParameters.map { STAR })
            }
            return asType(typeDef, objectDef)
        }

        private fun cannotCompleteNormally(statementDef: StatementDef): Boolean = when (statementDef) {
            is Return, is Throw -> true
            is Multi -> statementDef.statements.isNotEmpty() && cannotCompleteNormally(statementDef.statements.last())
            is StatementDef.IfElse -> cannotCompleteNormally(statementDef.statement)
                && cannotCompleteNormally(statementDef.elseStatement)
            is StatementDef.Switch -> statementDef.defaultCase?.let { cannotCompleteNormally(it) } == true
                && statementDef.cases.values.all { cannotCompleteNormally(it) }
            is StatementDef.Try -> statementDef.finallyStatement() == null
                && cannotCompleteNormally(statementDef.statement())
                && statementDef.catches().all { cannotCompleteNormally(it.statement()) }
            else -> false
        }

        private fun renderExpressionWithNotNullAssertion(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            scope: RenderScope,
            expressionDef: ExpressionDef?,
            result: TypeDef
        ): CodeBlock {
            val codeBlock = renderExpressionCode(objectDef, methodDef, scope, expressionDef)
            val builder = codeBlock.toBuilder()
            if (!result.isNullable && expressionDef?.type()?.isNullable == true) {
                builder.add("!!")
            }
            return builder.build()
        }

        private fun asAnnotationSpec(annotationDef: AnnotationDef): AnnotationSpec {
            var annName : String =
                if (annotationDef.type.name.contains("$")) {
                    annotationDef.type.name.replace("$", ".")
                } else {
                    annotationDef.type.name
                }
            var builder = AnnotationSpec.builder(ClassName.bestGuess(annName))
            for ((memberName, rawValue) in annotationDef.values) {
                // An array value is written like a collection, its toString() is not a member value
                val value: Any = if (rawValue.javaClass.isArray) {
                    (0 until Array.getLength(rawValue)).map { Array.get(rawValue, it) }
                } else {
                    rawValue
                }
                // Kotlin has no single value shorthand for an array member, it takes an array literal
                val memberValue = if (value !is Collection<*> && isArrayMember(annotationDef.type, memberName)) {
                    listOf(value)
                } else {
                    value
                }
                builder = addAnnotationValue(builder, memberName, memberValue)
            }
            return builder.build()
        }

        /**
         * @param type       The annotation type
         * @param memberName The member name
         * @return True if the member is declared as an array, false when that cannot be established
         */
        private fun isArrayMember(type: ClassTypeDef, memberName: String): Boolean {
            val javaClass = (type as? ClassTypeDef.JavaClass)?.type ?: return false
            return try {
                javaClass.getMethod(memberName).returnType.isArray
            } catch (e: NoSuchMethodException) {
                false
            }
        }

        private fun addAnnotationValue(
            builder: AnnotationSpec.Builder,
            memberName: String,
            value: Any
        ): AnnotationSpec.Builder = when (value) {
            // Note: Class values skip both Class<*> and KClass<*> entries
            is Class<*> -> {
                builder.addMember("$memberName = %T::class", value)
            }

            is KClass<*> -> {
                builder.addMember("$memberName = %T::class", value)
            }

            is ClassTypeDef -> {
                builder.addMember("$memberName = %L::class", value.getSimpleName())
            }

            is Enum<*> -> {
                // Enum values gets represented as a Static Variable and does not enter here
                builder.addMember("$memberName = %T.%L", value.javaClass, value.name)
            }

            is String -> {
                builder.addMember("$memberName = %S", value)
            }

            is Float -> {
                builder.addMember("$memberName = %Lf", value)
            }

            is Char -> {
                builder.addMember(
                    "$memberName = '%L'", characterLiteralWithoutSingleQuotes(
                        value
                    )
                )
            }

            is VariableDef -> {
                builder.addMember("$memberName = %L", renderVariable(null, null, RenderScope.root(null), value))
            }

            is AnnotationDef -> {
                val spec = asAnnotationSpec(value)
                builder.addMember("$memberName = %L", spec.toString().substring(1))
            }

            is Collection<*> -> {
                value.forEach(Consumer { v: Any? -> addAnnotationValue(builder, memberName, v!!) })
                val listItems = builder.members.filter { it.isNotEmpty() && it.toString().contains(memberName) }
                builder.members.removeAll(listItems)
                val listStr: String = listItems.map { it.toString().substringAfter("= ") }.joinToString(separator = ",\n")
                builder.addMember("$memberName = [%L]", listStr)
            }

            else -> {
                builder.addMember("$memberName = %L", value)
            }
        }

        // Copy from com.squareup.javapoet.Util
        private fun characterLiteralWithoutSingleQuotes(c: Char): String {
            // see https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.10.6
            return when (c) {
                '\b' -> "\\b"
                '\t' -> "\\t"
                '\n' -> "\\n"
                '\u000c' -> "\\f"
                '\r' -> "\\r"
                '\"' -> "\""
                '\'' -> "\\'"
                '\\' -> "\\\\"
                else -> if (Character.isISOControl(c)) String.format("\\u%04x", c.code) else c.toString()
            }
        }
    }

    /**
     * The names visible at a point of the rendering. Kotlin allows a lambda parameter or a catch
     * variable to shadow an enclosing name, but the shadow is a warning and it hides the outer
     * value, so a colliding name is emitted under an allocated one and its references remapped.
     *
     * @param parent The enclosing scope
     * @param owner  The method the scope belongs to
     */
    private class RenderScope private constructor(
        private val parent: RenderScope?,
        private val owner: MethodDef?
    ) {
        private val renames = LinkedHashMap<String, String>()
        private val taken = LinkedHashSet<String>()

        init {
            owner?.parameters?.forEach { taken.add(it.name) }
        }

        companion object {
            /**
             * @param owner The method the scope belongs to
             * @return A root scope
             */
            fun root(owner: MethodDef?) = RenderScope(null, owner)
        }

        /**
         * @param owner The method the nested scope belongs to
         * @return A scope nested in this one
         */
        fun nested(owner: MethodDef?) = RenderScope(this, owner)

        /**
         * Records a name as declared in this scope, so that a nested lambda does not reuse it.
         *
         * @param name The name
         */
        fun declare(name: String) {
            taken.add(name)
        }

        /**
         * Records that a name of the owning method is emitted under a different name.
         *
         * @param name        The name in the model
         * @param emittedName The name to emit
         */
        fun rename(name: String, emittedName: String) {
            renames[name] = emittedName
            taken.add(emittedName)
        }

        /**
         * @param name The name
         * @return True if the name is already used by this scope or any enclosing one
         */
        fun isTaken(name: String): Boolean {
            var scope: RenderScope? = this
            while (scope != null) {
                if (scope.taken.contains(name)) {
                    return true
                }
                scope = scope.parent
            }
            return false
        }

        /**
         * Allocates a name that is not used by this scope or any enclosing one.
         *
         * @param name The preferred name
         * @return The preferred name, or a name derived from it
         */
        fun allocate(name: String): String {
            if (!isTaken(name)) {
                return name
            }
            var i = 1
            var candidate = name + i
            while (isTaken(candidate)) {
                candidate = name + ++i
            }
            return candidate
        }

        /**
         * Resolves the name a method parameter is emitted under, looking in the innermost scope that
         * declares it and walking outwards so that a lambda body can capture a parameter of the
         * enclosing method.
         *
         * @param name The parameter name
         * @return The name to emit, or null if no scope declares the parameter
         */
        fun resolveParameter(name: String): String? {
            var scope: RenderScope? = this
            while (scope != null) {
                if (scope.owner?.findParameter(name) != null) {
                    return scope.renames.getOrDefault(name, name)
                }
                scope = scope.parent
            }
            return null
        }

        /**
         * Resolves a name recorded by [rename], walking outwards.
         *
         * @param name The name in the model
         * @return The name to emit, or null if no scope renamed it
         */
        fun resolveRename(name: String): String? {
            var scope: RenderScope? = this
            while (scope != null) {
                val emittedName = scope.renames[name]
                if (emittedName != null) {
                    return emittedName
                }
                scope = scope.parent
            }
            return null
        }
    }
}
