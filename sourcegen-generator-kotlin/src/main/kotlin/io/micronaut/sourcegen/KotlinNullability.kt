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

import io.micronaut.sourcegen.KotlinFieldRules.isNullified
import io.micronaut.sourcegen.generator.OverrideResolver
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import java.util.function.Consumer
import org.jspecify.annotations.Nullable

/*
 * The values Kotlin types as nullable where the model does not: the JVM default of a field, a wrapper, `null`
 * and the platform types of Java, and the types the source gives the values.
 */

/**
 * The type arguments of the supertypes that are nullable: the resolved types of the overrides the model declares
 * nullable, or that can return `null`. `get(): String?` overrides the `get` of a `Supplier<String?>` only.
 */
internal fun KotlinWriteContext.nullableArguments(objectDef: ObjectDef?): Set<TypeDef> {
    if (objectDef == null) {
        return emptySet()
    }
    // Asked for by each supertype and each method of the definition
    return nullableArgumentsCache.getOrPut(objectDef) { computeNullableArguments(objectDef) }
}

private fun KotlinWriteContext.computeNullableArguments(objectDef: ObjectDef): Set<TypeDef> {
    val result = LinkedHashSet<TypeDef>()
    for (declared in objectDef.methods) {
        val resolved = OverrideResolver.resolve(objectDef, declared, generationScope, true) ?: continue
        if (!resolved.returnType().isPrimitive && resolved.returnType() != TypeDef.VOID && (declared.returnType.isNullable
                || resolved.returnType() != declared.returnType && returnsPlatformValue(declared))) {
            result.add(resolved.returnType().makeNullable())
        }
        declared.parameters.forEachIndexed { index, parameter ->
            if (parameter.type.isNullable && !resolved.parameterTypes()[index].isPrimitive) {
                result.add(resolved.parameterTypes()[index].makeNullable())
            }
        }
    }
    return result
}

internal fun KotlinWriteContext.withNullableArguments(type: TypeDef, objectDef: ObjectDef): TypeDef {
    val nullable = nullableArguments(objectDef)
    if (nullable.isEmpty() || type !is ClassTypeDef.Parameterized) {
        return type
    }
    return TypeDef.parameterized(type.rawType, *type.typeArguments
        .map { if (nullable.contains(it.makeNullable())) it.makeNullable() else it }.toTypedArray())
}

/**
 * The resolved signature with the nullability the model declares, which the type arguments of the supertype do
 * not carry: `accept(@Nullable Object)` of a `Consumer<String>` takes a `String?`. A narrowed result is nullable
 * too where the body can return `null` - the bytecode's cast lets it through, `as String` throws.
 */
internal fun keepingNullability(method: MethodDef, declared: MethodDef, nullable: Set<TypeDef>): MethodDef {
    if (method === declared) {
        return method
    }
    // A type argument that is nullable is so wherever the supertype names its variable
    val nullableResult = nullable.contains(method.returnType.makeNullable())
    val nullableParameters = method.parameters.map { nullable.contains(it.type.makeNullable()) }
    if (!nullableResult && nullableParameters.none { it }) {
        return method
    }
    val builder = MethodDef.builder(method.name).addModifiers(method.modifiers).addAnnotations(method.annotations)
        .addJavadoc(method.javadoc).synthetic(method.isSynthetic).addThrows(method.throwTypes)
        .returns(if (nullableResult) method.returnType.makeNullable() else method.returnType)
        .addStatements(method.statements).overrides()
    method.typeVariables.forEach { builder.addTypeVariable(it) }
    method.parameters.forEachIndexed { index, parameter ->
        builder.addParameter(ParameterDef.builder(parameter.name,
            if (nullableParameters[index]) parameter.type.makeNullable() else parameter.type)
            .addModifiers(parameter.modifiers).addAnnotations(parameter.annotations).build())
    }
    return builder.build()
}

/** Whether a method returns a value Kotlin cannot tell is not `null`: the result of a call, a field, an element. */
private fun returnsPlatformValue(method: MethodDef): Boolean {
    fun platform(expression: ExpressionDef?): Boolean = when (val value = expression?.let { unwrapCasts(it) }) {
        null -> false
        is Constant -> value.value == null
        // The erased result of a compiled Java method, which the narrowed return casts
        is InvokeInstanceMethod -> !value.method.isConstructor && value.type() == TypeDef.OBJECT
            && value.instance !is VariableDef.This && value.instance !is VariableDef.Super
            && loadedClass(value.instance.type())?.let { !it.isAnnotationPresent(Metadata::class.java) } == true
        is IfElse -> platform(value.ifExpression) || platform(value.elseExpression)
        else -> false
    }
    fun returns(statement: StatementDef?): Boolean = when (statement) {
        null -> false
        is Return -> platform(statement.expression)
        is StatementDef.Multi -> statement.statements.any { returns(it) }
        is StatementDef.If -> returns(statement.statement)
        is StatementDef.IfElse -> returns(statement.statement) || returns(statement.elseStatement)
        is StatementDef.While -> returns(statement.statement)
        is StatementDef.Synchronized -> returns(statement.statement)
        is StatementDef.Switch -> statement.cases.values.any { returns(it) } || returns(statement.defaultCase)
        is StatementDef.Try -> returns(statement.statement) || returns(statement.finallyStatement)
            || statement.catches.any { returns(it.statement) }
        else -> false
    }
    return method.statements.any { returns(it) }
}

/**
 * Whether a type is a wrapper of a primitive the model types as not null. Kotlin types a wrapper as its
 * primitive, whose JVM type is the primitive's; the parameter, the result and the local of one are written
 * nullable, which is the wrapper in the JVM and holds `null` as Java's does.
 */
internal fun isBoxed(type: TypeDef): Boolean = !type.isNullable && KotlinConversions.unboxed(type) != null

/**
 * Whether the parameters and the result of a function are written as the model declares them, a wrapper
 * nullable: an override takes the types of the method it overrides.
 */
internal fun declaresOwnSignature(method: MethodDef): Boolean = !method.isOverride

/**
 * Whether a function returns a value Kotlin types as nullable where the model types its result as not null:
 * `null`, a field holding the JVM default, a local assigned `null`, a nullable result of a Java method such as
 * the message of an exception. Its result is nullable, as the one of the JVM is.
 */
internal fun KotlinWriteContext.returnsNullable(objectDef: ObjectDef?, method: MethodDef): Boolean {
    if (method.isConstructor || method.isOverride || method.returnType.isPrimitive || method.returnType.isNullable
        || method.returnType == TypeDef.VOID) {
        return false
    }
    nullableReturnsCache[method]?.let { return it }
    // A method returning its own result is not nullable for that
    nullableReturnsCache[method] = false
    var nullable = isBoxed(method.returnType)
    if (!nullable) {
        ownReturns(Multi(method.statements)) { returned ->
            val expression = returned.expression
            if (!nullable && expression != null && isMaybeNull(objectDef, method, expression)) {
                nullable = true
            }
        }
    }
    nullableReturnsCache[method] = nullable
    return nullable
}

/**
 * The locals of a function written nullable: one the model assigns `null`, and one of a wrapper.
 */
internal fun KotlinWriteContext.nullableLocals(method: MethodDef): Set<String> {
    fun compute(): Set<String> {
        val result = LinkedHashSet<String>()
        method.statements.forEach { body ->
            forEachStatement(body) { statement ->
                when (statement) {
                    is DefineAndAssign -> if (!statement.variable.type.isNullable && !statement.variable.type.isPrimitive
                        && (isNullLiteral(statement.expression) || isBoxed(statement.variable.type))) {
                        result.add(statement.variable.name)
                    }
                    is Assign -> if (isNullLiteral(statement.expression)) {
                        result.add(statement.variable.name)
                    }
                    else -> Unit
                }
            }
        }
        return result
    }
    return nullableLocalsCache.getOrPut(method) { compute() }
}

/**
 * Whether a value is one Kotlin types as nullable where the model types it as not null: rendered with `!!`
 * where Kotlin needs a value, as it is where it takes `null`.
 */
internal fun KotlinWriteContext.isMaybeNull(objectDef: ObjectDef?, method: MethodDef?, expression: ExpressionDef, scope: KotlinRenderScope? = null): Boolean {
    var value = expression
    // A cast the source does not write keeps the value as it is
    while (value is Cast && (value.type == value.expressionDef.type() || value.type == TypeDef.OBJECT)) {
        value = value.expressionDef
    }
    return when (value) {
        is Constant -> value.value == null
        is VariableDef.Field -> {
            val owner = if (value.declaringType == TypeDef.THIS) objectDef
                else generationScope.definitionOf(TypeHierarchy.unwrap(value.declaringType) as? ClassTypeDef, objectDef)
            owner != null && value.name in nullifiedFields(owner)
        }
        is VariableDef.StaticField -> generationScope.definitionOf(value.ownerType, objectDef)
            ?.let { value.name in nullifiedFields(it) } == true
        is VariableDef.Local -> scope?.isNullableLocal(value.name) ?: (method != null && value.name in nullableLocals(method))
        is VariableDef.MethodParameter -> isBoxedParameter(method, value.name, scope)
        is InvokeInstanceMethod -> !value.method.isConstructor && (isNullableJavaResult(value, objectDef, method)
            || generatedReturnsNullable(ownerOf(objectDef, value.instance.type()), objectDef, value.method))
        is InvokeStaticMethod -> generatedReturnsNullable(value.classDef, objectDef, value.method)
        is IfElse -> isMaybeNull(objectDef, method, value.ifExpression, scope) || isMaybeNull(objectDef, method, value.elseExpression, scope)
        else -> false
    }
}

/** Whether a parameter is one of a wrapper that its function declares nullable. */
internal fun KotlinWriteContext.isBoxedParameter(method: MethodDef?, name: String, scope: KotlinRenderScope?): Boolean {
    val owner = scope?.parameterOwner(name) ?: method ?: return false
    // A lambda's parameter is the one of the functional interface
    if (scope != null && !owner.isConstructor && enclosingFunctions.none { it === owner }) {
        return false
    }
    val parameter = owner.parameters.firstOrNull { it.name == name } ?: return false
    return declaresOwnSignature(owner) && isBoxed(parameter.type)
}

/** Whether a generated method returns a value Kotlin types as nullable where the model does not. */
internal fun KotlinWriteContext.generatedReturnsNullable(owner: ClassTypeDef?, objectDef: ObjectDef?, callMethod: MethodDef): Boolean {
    val definition = generationScope.definitionOf(owner, objectDef) ?: return false
    val declared = declaredCallee(definition, callMethod.name, callMethod.parameters.map { it.type }) ?: return false
    return returnsNullable(definition, declared)
}

/** The method of a generated definition a call invokes, `<init>` for a constructor. */
private fun declaredCallee(definition: ObjectDef, name: String, parameterTypes: List<TypeDef>): MethodDef? =
    definition.methods.firstOrNull { declared ->
        (if (name == MethodDef.CONSTRUCTOR) declared.isConstructor else declared.name == name)
            && declared.parameters.size == parameterTypes.size
            && declared.parameters.map { TypeHierarchy.erasedName(it.type, definition) } ==
            parameterTypes.map { TypeHierarchy.erasedName(it, definition) }
    }

/**
 * Whether the parameter an argument is passed to takes `null` in Kotlin: one declared nullable, one of a
 * compiled Java method, whose type is a platform one, and one of a wrapper a generated function declares.
 */
internal fun KotlinWriteContext.takesNull(owner: ClassTypeDef?, objectDef: ObjectDef?, name: String?, parameterTypes: List<TypeDef>, index: Int): Boolean {
    val parameterType = parameterTypes[index]
    if (parameterType.isPrimitive) {
        return false
    }
    if (parameterType.isNullable) {
        return true
    }
    val definition = generationScope.definitionOf(owner, objectDef)
    if (definition != null) {
        val declared = name?.let { declaredCallee(definition, it, parameterTypes) } ?: return false
        return declaresOwnSignature(declared) && isBoxed(declared.parameters[index].type)
    }
    return owner != null && loadedClass(owner, generationScope.typeLookup()) != null && !isKotlinClass(owner, generationScope.typeLookup())
}

/** Whether a call is of a Java method whose result Kotlin types as nullable: the message of an exception. */
internal fun KotlinWriteContext.isNullableJavaResult(call: InvokeInstanceMethod, objectDef: ObjectDef?, method: MethodDef?): Boolean {
    if (!KotlinJavaMappings.mayBeNullableProperty(call.method)) {
        return false
    }
    val receiver = if (method != null) sourceTypeOf(call.instance, method, objectDef) else call.instance.type()
    return KotlinJavaMappings.isNullableProperty(loadedClass(receiver, generationScope.typeLookup()), call.method)
}

internal fun KotlinWriteContext.nullifiedFields(objectDef: ObjectDef): Set<String> {
    fun compute(): Set<String> {
        val fields = when (objectDef) {
            is ClassDef -> objectDef.fields
            is EnumDef -> objectDef.fields
            else -> emptyList()
        }
        // The properties of a definition declaring its constructors are written as its fields are
        val properties = if (objectDef !is RecordDef && objectDef.methods.any { it.isConstructor }) {
            objectDef.properties.map { FieldDef.builder(it.name).ofType(it.type).addModifiers(it.modifiers).build() }
        } else {
            emptyList()
        }
        return (fields + properties).filter { isNullified(objectDef, it) }.map { it.name }.toSet()
    }
    return nullifiedFieldsCache.getOrPut(objectDef) { compute() }
}

/** Whether a compiled class is a Kotlin one, whose parameters are not the platform types a Java one has. */
internal fun isKotlinClass(owner: ClassTypeDef?, lookup: TypeLookup = TypeLookup.reflective()): Boolean {
    val name = owner?.name ?: return false
    return try {
        lookup.loadClass(name)?.isAnnotationPresent(Metadata::class.java) == true
    } catch (e: LinkageError) {
        false
    }
}

/**
 * Whether a parameter of a compiled Kotlin function is declared nullable. The metadata is read by kotlin-reflect
 * where the processor has it, which is looked up by name: it is no dependency of the generator.
 */
@Suppress("NO_REFLECTION_IN_CLASS_PATH")
internal fun declaresNullableParameter(owner: ClassTypeDef?, methodName: String?, arity: Int, index: Int,
                                       lookup: TypeLookup = TypeLookup.reflective()): Boolean {
    val name = owner?.name ?: return false
    val type = lookup.loadClass(name) ?: return false
    return try {
        val candidates = (type.declaredMethods.toList() + type.methods.toList())
            .filter { it.name == methodName && it.parameterCount == arity }
        candidates.isNotEmpty() && candidates.all { method ->
            // kotlin-reflect reads the metadata, where it is there to: only its interfaces are in the stdlib
            val function = Class.forName("kotlin.reflect.jvm.ReflectJvmMapping")
                .getMethod("getKotlinFunction", java.lang.reflect.Method::class.java)
                .invoke(null, method) as? kotlin.reflect.KFunction<*>
            val parameters = function?.parameters?.filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
            parameters != null && parameters.size == arity && parameters[index].type.isMarkedNullable
        }
    } catch (e: Throwable) {
        false
    }
}

/**
 * The type a value has in the source: that of the parameter it names, which an override can have narrowed
 * from the type the model built the value with. A cast to the type the value already has is not written.
 */
internal fun KotlinWriteContext.sourceTypeOf(value: ExpressionDef, methodDef: MethodDef, objectDef: ObjectDef?): TypeDef {
    if (value is Cast) {
        return if (value.type == value.expressionDef.type()) {
            sourceTypeOf(value.expressionDef, methodDef, objectDef)
        } else {
            value.type
        }
    }
    if (value is InvokeInstanceMethod && !value.method.isConstructor) {
        // The result of a generated method that override resolution narrowed has the narrowed type
        OverrideResolver.emittedSignature(
            ownerOf(objectDef, value.instance.type()), objectDef, methodDef, value.method, generationScope, true
        )?.let { return it.returnType }
    }
    if (value is IfElse) {
        return branchesType(listOf(value.ifExpression, value.elseExpression), value.type(), methodDef, objectDef)
    }
    if (value is Switch) {
        return branchesType(value.cases.values + listOfNotNull(value.defaultCase), value.type(), methodDef, objectDef)
    }
    if (value is ArrayElement) {
        // An element of an array an override narrowed has the narrowed component type
        val arrayType = sourceTypeOf(value.expression, methodDef, objectDef)
        if (arrayType != value.expression.type() && arrayType is TypeDef.Array) {
            return if (arrayType.dimensions == 1) {
                arrayType.componentType
            } else {
                TypeDef.array(arrayType.componentType, arrayType.dimensions - 1)
            }
        }
    }
    if (value is VariableDef.MethodParameter) {
        methodDef.parameters.firstOrNull { it.name == value.name }?.let { return it.type }
        // A lambda captures the parameter of the function it is written in, as that function is written
        enclosingFunctions.forEach { outer ->
            outer.parameters.firstOrNull { it.name == value.name }?.let { return it.type }
        }
    }
    return value.type()
}

/**
 * The type of a conditional or a switch expression: the type its results have, where they agree. Where they
 * do not, Kotlin types the expression by what they have in common, which need not be the type of the model;
 * one that differs from it is returned, which says that the source type differs. One with a `null` result
 * keeps its type: `null` cannot be cast to a non-null type.
 */
private fun KotlinWriteContext.branchesType(
    results: Collection<ExpressionDef>,
    modelType: TypeDef,
    methodDef: MethodDef,
    objectDef: ObjectDef?
): TypeDef {
    if (results.any { it is Constant && it.value == null }) {
        return modelType
    }
    val types = results.map { sourceTypeOf(it, methodDef, objectDef) }.distinct()
    return types.singleOrNull() ?: types.firstOrNull { it != modelType } ?: modelType
}

/**
 * The type declaring an invoked method: the class being written or its superclass for `this` and `super`,
 * which the model names by placeholders.
 */
internal fun ownerOf(objectDef: ObjectDef?, type: TypeDef): ClassTypeDef? {
    val resolved = if (objectDef != null
        && (type == TypeDef.THIS || type == TypeDef.SUPER && objectDef !is InterfaceDef)) {
        objectDef.getContextualType(type)
    } else {
        type
    }
    return (resolved as? ClassTypeDef)?.takeIf { it != TypeDef.THIS && it != TypeDef.SUPER }
}
