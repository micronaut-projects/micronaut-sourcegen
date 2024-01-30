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

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.javapoet.KotlinPoetJavaPoetPreview
import com.squareup.kotlinpoet.javapoet.toKClassName
import com.squareup.kotlinpoet.javapoet.toKTypeName
import io.micronaut.core.annotation.Internal
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.generator.SourceGenerator
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.EnumDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.CallInstanceMethod
import io.micronaut.sourcegen.model.ExpressionDef.CallStaticMethod
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.ParameterDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.StatementDef.Assign
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import java.io.IOException
import java.io.Writer
import java.util.function.Consumer
import javax.lang.model.element.Modifier

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

            else -> {
                throw IllegalStateException("Unknown object definition: $objectDef")
            }
        }
    }

    @Throws(IOException::class)
    private fun writeInterface(writer: Writer, interfaceDef: InterfaceDef) {
        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceDef.simpleName)
        interfaceBuilder.addModifiers(asKModifiers(interfaceDef.modifiers))
        interfaceDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> this.asTypeVariable(tv) }
            .forEach { typeVariable: TypeVariableName -> interfaceBuilder.addTypeVariable(typeVariable) }
        interfaceDef.superinterfaces.stream().map { typeDef: TypeDef -> this.asType(typeDef) }
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
                buildNullableProperty(
                    property.name,
                    property.type.makeNullable(),
                    property.modifiers,
                    property.annotations,
                    property.javadoc,
                    null
                )
            } else {
                buildConstructorProperty(
                    property.name,
                    property.type,
                    property.modifiers,
                    property.annotations,
                    property.javadoc
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
                    buildFunction(null, method, modifiers)
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
        FileSpec.builder(interfaceDef.packageName, interfaceDef.simpleName + ".kt")
            .addType(interfaceBuilder.build())
            .build()
            .writeTo(writer)
    }

    @Throws(IOException::class)
    private fun writeClass(writer: Writer, classDef: ClassDef) {
        val classBuilder = TypeSpec.classBuilder(classDef.simpleName)
        classBuilder.addModifiers(asKModifiers(classDef.modifiers))
        classDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> this.asTypeVariable(tv) }
            .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
        classDef.superinterfaces.stream().map { typeDef: TypeDef -> this.asType(typeDef) }
            .forEach { it: TypeName ->
                classBuilder.addSuperinterface(
                    it
                )
            }
        classDef.javadoc.forEach(Consumer { format: String -> classBuilder.addKdoc(format) })
        classDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
            .forEach { annotationSpec: AnnotationSpec -> classBuilder.addAnnotation(annotationSpec) }

        var companionBuilder: TypeSpec.Builder? = null
        val notNullProperties: MutableList<PropertyDef> = ArrayList()
        for (property in classDef.properties) {
            var propertySpec: PropertySpec
            if (property.type.isNullable) {
                propertySpec = buildNullableProperty(
                    property.name,
                    property.type.makeNullable(),
                    property.modifiers,
                    property.annotations,
                    property.javadoc,
                    null
                )
            } else {
                propertySpec = buildConstructorProperty(
                    property.name,
                    property.type,
                    property.modifiers,
                    property.annotations,
                    property.javadoc
                )
                notNullProperties.add(property)
            }
            classBuilder.addProperty(
                propertySpec
            )
        }
        if (notNullProperties.isNotEmpty()) {
            classBuilder.primaryConstructor(
                FunSpec.constructorBuilder().addModifiers(KModifier.PUBLIC).addParameters(
                    notNullProperties.stream()
                        .map { prop: PropertyDef ->
                            ParameterSpec.builder(
                                prop.name,
                                asType(prop.type)
                            ).build()
                        }.toList()
                ).build()
            )
        }
        for (field in classDef.fields) {
            val modifiers = field.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                companionBuilder.addProperty(
                    buildNullableProperty(field, stripStatic(modifiers), field.javadoc)
                )
            } else {
                classBuilder.addProperty(
                    buildNullableProperty(field, modifiers, field.javadoc)
                )
            }
        }

        for (method in classDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                modifiers = stripStatic(modifiers)
                companionBuilder.addFunction(
                    buildFunction(null, method, modifiers)
                )
            } else {
                classBuilder.addFunction(
                    buildFunction(classDef, method, modifiers)
                )
            }
        }
        if (companionBuilder != null) {
            classBuilder.addType(companionBuilder.build())
        }
        FileSpec.builder(classDef.packageName, classDef.simpleName + ".kt")
            .addType(classBuilder.build())
            .build()
            .writeTo(writer)
    }

    @Throws(IOException::class)
    private fun writeRecordDef(writer: Writer, recordDef: RecordDef) {
        val classBuilder = TypeSpec.classBuilder(recordDef.simpleName)
        classBuilder.addModifiers(KModifier.DATA)
        classBuilder.addModifiers(asKModifiers(recordDef.modifiers))
        recordDef.typeVariables.stream().map { tv: TypeDef.TypeVariable -> this.asTypeVariable(tv) }
            .forEach { typeVariable: TypeVariableName -> classBuilder.addTypeVariable(typeVariable) }
        recordDef.superinterfaces.stream().map { typeDef: TypeDef -> this.asType(typeDef) }
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
                    property.javadoc
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
                                asType(prop.type)
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
                    buildFunction(null, method, modifiers)
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
        FileSpec.builder(recordDef.packageName, recordDef.simpleName + ".kt")
            .addType(classBuilder.build())
            .build()
            .writeTo(writer)
    }

    @Throws(IOException::class)
    private fun writeEnumDef(writer: Writer, enumDef: EnumDef) {
        val enumBuilder = TypeSpec.enumBuilder(enumDef.simpleName)
        enumBuilder.addModifiers(asKModifiers(enumDef.modifiers))
        enumDef.superinterfaces.stream().map { typeDef: TypeDef -> this.asType(typeDef) }
            .forEach { it: TypeName -> enumBuilder.addSuperinterface(it) }
        enumDef.javadoc.forEach(Consumer { format: String -> enumBuilder.addKdoc(format) })
        enumDef.annotations.stream().map { annotationDef: AnnotationDef -> asAnnotationSpec(annotationDef) }
            .forEach { annotationSpec: AnnotationSpec -> enumBuilder.addAnnotation(annotationSpec) }

        for (enumConstant in enumDef.enumConstants) {
            enumBuilder.addEnumConstant(enumConstant)
        }

        var companionBuilder: TypeSpec.Builder? = null
        for (method in enumDef.methods) {
            var modifiers = method.modifiers
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder()
                }
                modifiers = stripStatic(modifiers)
                companionBuilder.addFunction(
                    buildFunction(null, method, modifiers)
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
        FileSpec.builder(enumDef.packageName, enumDef.simpleName + ".kt")
            .addType(enumBuilder.build())
            .build()
            .writeTo(writer)
    }

    private fun buildNullableProperty(
        name: String,
        typeDef: TypeDef,
        modifiers: Set<Modifier>,
        annotations: List<AnnotationDef>,
        docs: List<String>, initializer: ExpressionDef?
    ): PropertySpec {
        val propertyBuilder = PropertySpec.builder(
            name,
            asType(typeDef),
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
        if (initializer != null) {
            if (initializer is ExpressionDef.Constant) {
                propertyBuilder.initializer(
                    CodeBlock.of(
                        "%L", initializer.value
                    )
                )
            }
        }
        return propertyBuilder
            .initializer("null").build()
    }

    private fun buildConstructorProperty(
        name: String,
        typeDef: TypeDef,
        modifiers: Set<Modifier>,
        annotations: List<AnnotationDef>,
        docs: List<String>
    ): PropertySpec {
        val propertyBuilder = PropertySpec.builder(
            name,
            asType(typeDef),
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

    private fun buildNullableProperty(field: FieldDef, modifiers: Set<Modifier>, docs: List<String>): PropertySpec {
        return buildNullableProperty(
            field.name,
            field.type,
            modifiers,
            field.annotations,
            docs,
            field.initializer.orElse(null)
        )
    }

    private fun buildFunction(objectDef: ObjectDef?, method: MethodDef, modifiers: Set<Modifier>): FunSpec {
        var funBuilder = if (method.name == "<init>") {
            FunSpec.constructorBuilder()
        } else {
            FunSpec.builder(method.name).returns(asType(method.returnType))
        }
        funBuilder = funBuilder
            .addModifiers(asKModifiers(modifiers))
            .addParameters(
                method.parameters.stream()
                    .map { param: ParameterDef ->
                        ParameterSpec.builder(
                            param.name,
                            asType(param.type)
                        ).build()
                    }
                    .toList()
            )
        for (annotation in method.annotations) {
            funBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            )
        }
        method.statements.stream()
            .map { st: StatementDef -> renderStatement(objectDef, method, st) }
            .forEach { statement -> funBuilder.addStatement(statement.toString()) }
        method.javadoc.forEach(Consumer { format: String -> funBuilder.addKdoc(format) })
        return funBuilder.build()
    }

    @OptIn(KotlinPoetJavaPoetPreview::class)
    private fun asType(typeDef: TypeDef): TypeName {
        val result: TypeName = if (typeDef is ClassTypeDef.Parameterized) {
            asClassName(typeDef.rawType).parameterizedBy(
                typeDef.typeArguments.map { v: TypeDef -> this.asType(v) }
            )
        } else if (typeDef is TypeDef.Primitive) {
            when (typeDef.name) {
                "void" -> UNIT
                "byte" -> com.squareup.javapoet.TypeName.BYTE.toKTypeName()
                "short" -> com.squareup.javapoet.TypeName.SHORT.toKTypeName()
                "char" -> com.squareup.javapoet.TypeName.CHAR.toKTypeName()
                "int" -> com.squareup.javapoet.TypeName.INT.toKTypeName()
                "long" -> com.squareup.javapoet.TypeName.LONG.toKTypeName()
                "float" -> com.squareup.javapoet.TypeName.FLOAT.toKTypeName()
                "double" -> com.squareup.javapoet.TypeName.DOUBLE.toKTypeName()
                "boolean" -> com.squareup.javapoet.TypeName.BOOLEAN.toKTypeName()
                else -> throw IllegalStateException("Unrecognized primitive name: " + typeDef.name)
            }
        } else if (typeDef is ClassTypeDef) {
            asClassName(typeDef)
        } else if (typeDef is TypeDef.Wildcard) {
            if (typeDef.lowerBounds.isNotEmpty()) {
                WildcardTypeName.consumerOf(
                    asType(
                        typeDef.lowerBounds[0]
                    )
                )
            } else {
                WildcardTypeName.producerOf(
                    asType(
                        typeDef.upperBounds[0]
                    )
                )
            }
        } else if (typeDef is TypeDef.TypeVariable) {
            return asTypeVariable(typeDef)
        } else {
            throw IllegalStateException("Unrecognized type definition $typeDef")
        }
        if (typeDef.isNullable) {
            return asNullable(result)
        }
        return result
    }

    private fun asTypeVariable(tv: TypeDef.TypeVariable): TypeVariableName {
        return TypeVariableName(
            tv.name,
            tv.bounds.stream().map { v: TypeDef -> this.asType(v) }.toList()
        )
    }

    @JvmRecord
    private data class ExpResult(val rendered: CodeBlock, val type: TypeDef)
    companion object {
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

        private fun asClassName(classType: ClassTypeDef): ClassName {
            val packageName = classType.packageName
            val simpleName = classType.simpleName
            val result: ClassName = com.squareup.javapoet.ClassName.get(packageName, simpleName).toKClassName()
            if (result.isNullable) {
                return asNullable(result) as ClassName
            }
            return result
        }

        private fun asNullable(kClassName: TypeName): TypeName {
            return kClassName.copy(true, kClassName.annotations, kClassName.tags)
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

        private fun renderStatement(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            statementDef: StatementDef
        ): CodeBlock {
            if (statementDef is StatementDef.Return) {
                val expResult = renderExpression(objectDef, methodDef, statementDef.expression)
                return CodeBlock.of("return ")
                    .toBuilder()
                    .add(renderWithNotNullAssertion(expResult, methodDef.returnType))
                    .build()
            }
            if (statementDef is Assign) {
                val variableExp = renderVariable(objectDef, methodDef, statementDef.variable)
                val valueExp = renderExpression(objectDef, methodDef, statementDef.expression)
                val codeBuilder = variableExp.rendered.toBuilder()
                codeBuilder.add(" = ")
                codeBuilder.add(renderWithNotNullAssertion(valueExp, variableExp.type))
                return codeBuilder.build()
            }
            throw IllegalStateException("Unrecognized statement: $statementDef")
        }

        private fun renderExpression(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            expressionDef: ExpressionDef
        ): ExpResult {
            if (expressionDef is ExpressionDef.NewInstance) {
                val codeBuilder = CodeBlock.builder()
                codeBuilder.add("%T(", asClassName(expressionDef.type))
                for ((index, parameter) in expressionDef.values.withIndex()) {
                    val expResult = renderExpression(objectDef, methodDef, parameter)
                    codeBuilder.add(renderWithNotNullAssertion(expResult, parameter.type()))
                    if (index != expressionDef.values.size - 1) {
                        codeBuilder.add(", ")
                    }
                }
                codeBuilder.add(")")
                return ExpResult(
                    codeBuilder.build(),
                    expressionDef.type
                )
            }
            if (expressionDef is CallInstanceMethod) {
                val expResult = renderVariable(objectDef, methodDef, expressionDef.instance)
                val codeBuilder = expResult.rendered.toBuilder()
                codeBuilder.add(".%N(", expressionDef.name)
                for ((index, parameter) in expressionDef.parameters.withIndex()) {
                    codeBuilder.add(renderExpression(objectDef, methodDef, parameter).rendered)
                    if (index != expressionDef.parameters.size - 1) {
                        codeBuilder.add(", ")
                    }
                }
                codeBuilder.add(")")
                return ExpResult(
                    codeBuilder.build(),
                    expressionDef.type()
                )
            }
            if (expressionDef is CallStaticMethod) {
                val codeBuilder = CodeBlock.builder()
                codeBuilder.add("%T.%N(", asClassName(expressionDef.classDef), expressionDef.name)
                for ((index, parameter) in expressionDef.parameters.withIndex()) {
                    codeBuilder.add(renderExpression(objectDef, methodDef, parameter).rendered)
                    if (index != expressionDef.parameters.size - 1) {
                        codeBuilder.add(", ")
                    }
                }
                codeBuilder.add(")")
                return ExpResult(
                    codeBuilder.build(),
                    expressionDef.type()
                )
            }
            if (expressionDef is ExpressionDef.Convert) {
                val expResult = renderVariable(objectDef, methodDef, expressionDef.variable)
                val resultType = expressionDef.type
                return ExpResult(
                    renderWithNotNullAssertion(expResult, resultType),
                    resultType
                )
            }
            if (expressionDef is VariableDef) {
                return renderVariable(objectDef, methodDef, expressionDef)
            }
            throw IllegalStateException("Unrecognized expression: $expressionDef")
        }

        private fun renderVariable(
            objectDef: ObjectDef?,
            methodDef: MethodDef,
            variableDef: VariableDef
        ): ExpResult {
            if (variableDef is VariableDef.MethodParameter) {
                methodDef.getParameter(variableDef.name) // Check if exists
                return ExpResult(CodeBlock.of("%N", variableDef.name), variableDef.type)
            }
            if (variableDef is VariableDef.Field) {
                checkNotNull(objectDef) { "Field 'this' is not available" }
                if (objectDef is ClassDef) {
                    objectDef.getField(variableDef.name) // Check if exists
                } else {
                    throw IllegalStateException("Field access no supported on the object definition: $objectDef")
                }
                val expResult = renderVariable(objectDef, methodDef, variableDef.instanceVariable)
                val builder = expResult.rendered.toBuilder()
                if (expResult.type.isNullable) {
                    builder.add("!!")
                }
                builder.add(". %N", variableDef.name)
                return ExpResult(
                    builder.build(),
                    variableDef.type
                )
            }
            if (variableDef is VariableDef.This) {
                checkNotNull(objectDef) { "Accessing 'this' is not available" }
                return ExpResult(CodeBlock.of("this"), variableDef.type)
            }
            throw IllegalStateException("Unrecognized variable: $variableDef")
        }

        private fun renderWithNotNullAssertion(expResult: ExpResult, result: TypeDef): CodeBlock {
            val builder = expResult.rendered.toBuilder()
            if (!result.isNullable && expResult.type.isNullable) {
                builder.add("!!")
            }
            return builder.build()
        }

        private fun asAnnotationSpec(annotationDef: AnnotationDef): AnnotationSpec {
            var builder = AnnotationSpec.builder(ClassName.bestGuess(annotationDef.type.name))
            for ((memberName, value) in annotationDef.values) {
                builder = when (value) {
                    is Class<*> -> {
                        builder.addMember("$memberName = %T::class", value)
                    }

                    is Enum<*> -> {
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

                    else -> {
                        builder.addMember("$memberName = %L", value)
                    }
                }
            }
            return builder.build()
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
}
