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
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import javax.lang.model.element.Modifier

/*
 * The Kotlin names of the types of the model: mapped, star projected where they are raw, nested in a
 * parameterized type, and of arrays.
 */

internal fun loadedClass(type: TypeDef?, lookup: TypeLookup = TypeLookup.reflective()): Class<*>? {
    val name = ((type as? ClassTypeDef.Parameterized)?.rawType ?: type as? ClassTypeDef)?.name ?: return null
    return lookup.loadClass(name)
}

@OptIn(KotlinPoetJavaPoetPreview::class)
internal fun KotlinWriteContext.asClassName(classType: ClassTypeDef): ClassName {
    val result = if (classType.isInner) {
        // Build ClassName deterministically from the binary name split on '$',
        // avoiding heuristics in ClassName.bestGuess() that rely on capitalisation.
        val binaryName = classType.name
        val names = nestedSimpleNames(binaryName)
        if (names != null) {
            ClassName(names.first, names.second)
        } else {
            com.squareup.javapoet.ClassName.get(classType.packageName, classType.simpleName).toKClassName()
        }
    } else {
        com.squareup.javapoet.ClassName.get(classType.packageName, classType.simpleName).toKClassName()
    }.let { KotlinJavaMappings.kotlinClassOf(it) }
    if (result.isNullable) {
        return asNullable(result) as ClassName
    }
    return result
}

/**
 * The package and the simple names, the outermost first, of a nested type known by its binary name. A `$` that
 * starts a simple name belongs to it: `test.$Outer$Holder` is `Holder` in `$Outer`. The definitions being
 * written tell where one of theirs ends.
 */
private fun KotlinWriteContext.nestedSimpleNames(binaryName: String): Pair<String, List<String>>? {
    val dotIndex = binaryName.lastIndexOf('.')
    val packageName = if (dotIndex == -1) "" else binaryName.substring(0, dotIndex)
    // The definition of the file being written is named as it is declared, whatever `$` its name has
    val outermost = writtenDefinition.asTypeDef().name
    if (binaryName.startsWith("$outermost$") && outermost.lastIndexOf('.') == dotIndex) {
        return packageName to (listOf(outermost.substring(dotIndex + 1)) + splitBinaryNames(binaryName.substring(outermost.length + 1)))
    }
    val names = splitBinaryNames(binaryName.substring(dotIndex + 1))
    return if (names.size < 2) null else packageName to names
}

/** The simple names a `$` separates, where a `$` that starts a name belongs to it. */
private fun splitBinaryNames(names: String): List<String> {
    val result = ArrayList<String>()
    var current = StringBuilder()
    for (segment in names.split('$')) {
        current.append(segment)
        if (segment.isEmpty()) {
            current.append('$')
        } else {
            result.add(current.toString())
            current = StringBuilder()
        }
    }
    if (current.isNotEmpty()) {
        // Trailing `$` characters stay with the last name
        val trailing = current.toString().dropLast(1)
        if (result.isEmpty()) result.add(current.toString()) else result[result.size - 1] = result.last() + "$" + trailing
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
internal fun KotlinWriteContext.asStaticOwnerName(classType: ClassTypeDef): ClassName {
    val mapped = asClassName(classType)
    if (classType.isInner || mapped.canonicalName == classType.canonicalName) {
        return mapped
    }
    return ClassName(classType.packageName, classType.simpleName)
}

private fun asNullable(kClassName: TypeName): TypeName {
    return kClassName.copy(true, kClassName.annotations, kClassName.tags)
}

@OptIn(KotlinPoetJavaPoetPreview::class)
internal fun KotlinWriteContext.asType(typeDef: TypeDef?, objectDef: ObjectDef?): TypeName {
    return asType(typeDef, objectDef, null, false)
}

@OptIn(KotlinPoetJavaPoetPreview::class)
internal fun KotlinWriteContext.asType(typeDef: TypeDef?, objectDef: ObjectDef?, methodDef: MethodDef?): TypeName {
    return asType(
        typeDef,
        objectDef,
        methodDef,
        methodDef != null && methodDef.modifiers.contains(Modifier.STATIC),
    )
}

@OptIn(KotlinPoetJavaPoetPreview::class)
internal fun KotlinWriteContext.asType(typeDef: TypeDef?, objectDef: ObjectDef?, staticContext: Boolean): TypeName {
    return asType(typeDef, objectDef, null, staticContext)
}

@OptIn(KotlinPoetJavaPoetPreview::class)
internal fun KotlinWriteContext.asType(
    typeDef: TypeDef?,
    objectDef: ObjectDef?,
    methodDef: MethodDef?,
    staticContext: Boolean,
): TypeName {
    val result: TypeName = when {
        typeDef == TypeDef.THIS -> asSelfType(objectDef, methodDef, staticContext)
        typeDef == TypeDef.SUPER -> asType(
            (objectDef as? ClassDef)?.superclass ?: if (objectDef is EnumDef) ClassTypeDef.of(Enum::class.java) else TypeDef.OBJECT,
            objectDef, methodDef, staticContext)
        typeDef is TypeDef.Array -> asArray(typeDef, objectDef, methodDef, staticContext)
        typeDef is ClassTypeDef.Parameterized -> typeDef.typeArguments.map { v: TypeDef -> this.asType(v, objectDef, methodDef, staticContext) }
            .let { arguments ->
                val rawType = typeDef.rawType
                val enclosing = TypeHierarchy.enclosingOf(rawType)
                if (enclosing != null) asMemberType(enclosing, rawType, arguments, objectDef, methodDef, staticContext)
                else asClassName(rawType).parameterizedBy(arguments)
            }
        typeDef is ClassTypeDef && TypeHierarchy.enclosingOf(typeDef) != null ->
            asMemberType(TypeHierarchy.enclosingOf(typeDef)!!, typeDef, emptyList(), objectDef, methodDef, staticContext)
        typeDef is TypeDef.Primitive -> asPrimitive(typeDef)
        // A raw generic type, which Kotlin does not have, is star projected: `List<*>`
        typeDef is ClassTypeDef -> asClassName(typeDef).let { name ->
            val arity = rawTypeArity(typeDef)
            if (arity > 0) name.parameterizedBy(List(arity) { STAR }) else name
        }
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
 * A member of a parameterized enclosing type, `Outer<String>.Member<Int>`.
 */
private fun KotlinWriteContext.asMemberType(
    enclosingType: ClassTypeDef,
    memberType: ClassTypeDef,
    arguments: List<TypeName>,
    objectDef: ObjectDef?,
    methodDef: MethodDef?,
    staticContext: Boolean,
): TypeName {
    val member = asClassName(TypeHierarchy.memberClass(memberType))
    val enclosing = asType(enclosingType, objectDef, methodDef, staticContext)
    if (enclosing is ParameterizedTypeName) {
        return (enclosing.copy(nullable = false) as ParameterizedTypeName).nestedClass(member.simpleName, arguments)
    }
    return if (arguments.isEmpty()) member else member.parameterizedBy(arguments)
}

/**
 * The self type in the scope it is written in. In a static context the enclosing definition is
 * dropped, so its type variables are not treated as being in scope.
 */
@OptIn(KotlinPoetJavaPoetPreview::class)
private fun KotlinWriteContext.asSelfType(objectDef: ObjectDef?, methodDef: MethodDef?, staticContext: Boolean): TypeName {
    if (objectDef == null) {
        throw java.lang.IllegalStateException("This type is used outside of the instance scope!")
    }
    // The scope is kept: the self type of a generic definition carries the variables it declares
    return asType(objectDef.asTypeDef(), if (staticContext) null else objectDef, methodDef, staticContext)
}

@OptIn(KotlinPoetJavaPoetPreview::class)
private fun KotlinWriteContext.asAnnotated(
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
private fun KotlinWriteContext.asWildcard(
    typeDef: TypeDef.Wildcard,
    objectDef: ObjectDef?,
    methodDef: MethodDef?,
    staticContext: Boolean,
): TypeName = if (typeDef.lowerBounds.isNotEmpty()) {
    WildcardTypeName.consumerOf(asType(typeDef.lowerBounds[0], objectDef, methodDef, staticContext))
} else if (methodDef?.isOverride == true && (typeDef.upperBounds.isEmpty() || typeDef.upperBounds.all { it == TypeDef.OBJECT })) {
    // `?` in the signature of an override is `*`: `out Any` does not override a `Class<*>`
    STAR
} else {
    WildcardTypeName.producerOf(asType(typeDef.upperBounds[0], objectDef, methodDef, staticContext))
}

@OptIn(KotlinPoetJavaPoetPreview::class)
private fun KotlinWriteContext.asTypeVariableType(
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

internal fun isVariablePartOfTheDefinition(
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

internal fun KotlinWriteContext.asTypeVariable(tv: TypeDef.TypeVariable, objectDef: ObjectDef?, methodDef: MethodDef? = null): TypeVariableName {
    return TypeVariableName(
        tv.name,
        tv.bounds.stream().map { v: TypeDef -> asType(v, objectDef, methodDef) }.toList()
    )
}

private fun KotlinWriteContext.asArray(
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
internal fun arrayElementType(type: TypeDef.Array): TypeDef =
    if (type.dimensions > 1) {
        TypeDef.Array(type.componentType, type.dimensions - 1, false)
    } else {
        type.componentType
    }

/**
 * @param componentType The component of an array
 * @return The Kotlin type of an array of that component, or null if it is not a primitive
 */
internal fun primitiveArrayType(componentType: TypeDef): String? {
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
internal fun arrayOfFunction(componentType: TypeDef): String {
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

/**
 * @param name The primitive name that no lookup recognized
 * @return Never, the name is not a primitive
 */
internal fun unrecognizedPrimitive(name: String): Nothing =
    error("Unrecognized primitive name: $name")

internal fun KotlinWriteContext.asStarProjected(type: TypeDef, objectDef: ObjectDef?): TypeName =
    asKotlinComparable(asStarProjectedType(type, objectDef))

/**
 * A cast to a bound names `kotlin.Comparable`, which inference matches with a Kotlin type's supertypes.
 */
internal fun asKotlinComparable(type: TypeName): TypeName {
    if (type !is ParameterizedTypeName) {
        return type
    }
    val raw = if (type.rawType.canonicalName == "java.lang.Comparable") ClassName("kotlin", "Comparable") else type.rawType
    return raw.parameterizedBy(type.typeArguments.map { asKotlinComparable(it) }).copy(nullable = type.isNullable)
}

private fun KotlinWriteContext.asStarProjectedType(type: TypeDef, objectDef: ObjectDef?): TypeName {
    if (type !is ClassTypeDef.Parameterized) {
        return asType(type, objectDef)
    }
    // Rendered as a whole, so that a Java type is named as Kotlin's - `kotlin.Comparable`
    val rendered = asType(type, objectDef) as? ParameterizedTypeName ?: return asType(type, objectDef)
    return rendered.rawType.parameterizedBy(type.typeArguments.mapIndexed { index, argument ->
        if (argument is TypeDef.Wildcard && argument.lowerBounds.isEmpty()
            && (argument.upperBounds.isEmpty() || argument.upperBounds[0] == TypeDef.OBJECT)) {
            STAR
        } else {
            rendered.typeArguments[index]
        }
    }).copy(nullable = type.isNullable)
}

/**
 * The type of an `is` check: Kotlin names every type argument, so a raw generic type is
 * checked with star projections.
 */
internal fun KotlinWriteContext.asTypeCheckType(typeDef: TypeDef, objectDef: ObjectDef?): TypeName {
    // The type arguments are erased at runtime, which Kotlin does not check: each is a star
    val unwrapped = TypeHierarchy.unwrap(typeDef)
    val raw = (unwrapped as? ClassTypeDef.Parameterized)?.rawType ?: unwrapped as? ClassTypeDef
    if (raw != null && TypeHierarchy.enclosingOf(raw) == null) {
        val arity = (unwrapped as? ClassTypeDef.Parameterized)?.typeArguments?.size ?: rawTypeArity(raw)
        if (arity > 0) {
            return asClassName(raw).parameterizedBy(List(arity) { STAR })
        }
    }
    return asType(typeDef, objectDef)
}

/** The number of type parameters of a generic type written raw, which Kotlin names with as many arguments. */
private fun KotlinWriteContext.rawTypeArity(type: ClassTypeDef): Int {
    if (type is ClassTypeDef.Parameterized || type == TypeDef.THIS || type == TypeDef.SUPER) {
        return 0
    }
    val definition = (type as? ClassTypeDef.ClassDefType)?.objectDef() ?: generationScope.definitionOf(type)
    if (definition != null) {
        return when (definition) {
            is ClassDef -> definition.typeVariables.size
            is InterfaceDef -> definition.typeVariables.size
            is RecordDef -> definition.typeVariables.size
            else -> 0
        }
    }
    val loaded = (type as? ClassTypeDef.JavaClass)?.type ?: if (type is ClassTypeDef.ClassElementType) null else loadedClass(type, generationScope.typeLookup())
    return if (loaded == null || loaded.isArray) 0 else loaded.typeParameters.size
}

/**
 * The name of a functional interface a lambda or a reference implements, and of a supertype `super` names: a
 * raw one without arguments, which Kotlin infers, where a star projection is no type to implement.
 */
internal fun KotlinWriteContext.asSamType(type: TypeDef, objectDef: ObjectDef?, methodDef: MethodDef?): TypeName {
    val unwrapped = TypeHierarchy.unwrap(type)
    if (unwrapped is ClassTypeDef && unwrapped !is ClassTypeDef.Parameterized && TypeHierarchy.enclosingOf(unwrapped) == null
        && rawTypeArity(unwrapped) > 0) {
        return asClassName(unwrapped)
    }
    return asType(type, objectDef, methodDef)
}

/**
 * The type arguments of a raw generic class instantiated where nothing infers them: those of Java's raw type,
 * `Any?`. A bounded parameter has none that fits all its uses, and is left to inference.
 */
internal fun KotlinWriteContext.rawInstantiationArguments(type: ClassTypeDef): List<TypeName>? {
    if (rawTypeArity(type) == 0) {
        return null
    }
    val loaded = (type as? ClassTypeDef.JavaClass)?.type ?: loadedClass(type, generationScope.typeLookup())
    if (loaded != null) {
        return if (loaded.typeParameters.all { parameter -> parameter.bounds.all { it == Any::class.java } }) {
            loaded.typeParameters.map { ANY.copy(nullable = true) }
        } else {
            null
        }
    }
    val definition = (type as? ClassTypeDef.ClassDefType)?.objectDef() ?: generationScope.definitionOf(type)
    val variables = when (definition) {
        is ClassDef -> definition.typeVariables
        is InterfaceDef -> definition.typeVariables
        is RecordDef -> definition.typeVariables
        else -> return null
    }
    return if (variables.all { it.bounds.isEmpty() || it.bounds.all { bound -> bound == TypeDef.OBJECT } }) {
        variables.map { ANY.copy(nullable = true) }
    } else {
        null
    }
}
