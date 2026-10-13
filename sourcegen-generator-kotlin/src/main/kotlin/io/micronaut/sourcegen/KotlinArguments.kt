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
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.micronaut.sourcegen.generator.CalleeBounds
import io.micronaut.sourcegen.generator.InvokedSignature
import io.micronaut.sourcegen.generator.OverloadRules
import io.micronaut.sourcegen.generator.OverrideResolver
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*

/*
 * The arguments of a call, cast where Kotlin would pick another overload or infer another type than the
 * bytecode does.
 */

/**
 * Renders call arguments, casting an `Object` value passed to a narrower parameter: the
 * verifier accepts it, the Kotlin compiler does not. Each argument is written by the first of the
 * [ARGUMENT_RULES] that applies to it.
 */
internal fun KotlinWriteContext.renderArguments(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    owner: ClassTypeDef?,
    methodName: String?,
    callMethod: MethodDef?,
    parameterTypes: List<TypeDef>?,
    values: List<ExpressionDef>
): CodeBlock {
    val call = callOf(objectDef, methodDef, scope, owner, methodName, callMethod, parameterTypes, values)
    val builder = CodeBlock.builder()
    for ((index, value) in values.withIndex()) {
        if (index > 0) {
            builder.add(", ")
        }
        val argument = Argument(call, index, value, sourceTypeOf(value, methodDef, objectDef))
        builder.add(ARGUMENT_RULES.firstNotNullOf { rule -> rule(this, argument) })
    }
    return builder.build()
}

/**
 * A call whose arguments are rendered, with what the rules of its arguments look up once for all of them.
 *
 * @property emittedTypes      The parameters of a method of this class that override resolution narrowed, which it
 *                             is written with and the values passed to it are converted to
 * @property sameArityTypes    The parameters, where there is one for each value
 * @property signature         The signature of the invoked method, which says whether it takes varargs
 * @property varargs           Whether the method takes varargs
 * @property callee            The variables the invoked method declares, with the receiver's type arguments for its
 *                             class's
 * @property generated         Whether the owner is a generated definition
 * @property receiverArguments The type arguments of the receiver, by the variables of its class
 * @property overloaded        Whether another overload would take the values: as the source types them, or - a
 *                             parameter or a local, which Kotlin smart casts after a check or a cast of the model - as
 *                             any reference
 */
private class Call(
    val objectDef: ObjectDef?,
    val methodDef: MethodDef,
    val scope: KotlinRenderScope,
    val owner: ClassTypeDef?,
    val methodName: String?,
    val callMethod: MethodDef?,
    val values: List<ExpressionDef>,
    val emittedTypes: List<TypeDef>?,
    val sameArityTypes: List<TypeDef>?,
    val signature: InvokedSignature?,
    val varargs: Boolean,
    val callee: CalleeBounds,
    val generated: Boolean,
    val receiverArguments: Map<String, TypeDef>,
    val overloaded: Boolean
)

private fun KotlinWriteContext.callOf(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    owner: ClassTypeDef?,
    methodName: String?,
    callMethod: MethodDef?,
    parameterTypes: List<TypeDef>?,
    values: List<ExpressionDef>
): Call {
    val emittedTypes = if (callMethod != null) {
        OverrideResolver.emittedSignature(owner, objectDef, methodDef, callMethod, generationScope, true)
            ?.parameterTypes()
    } else {
        null
    }
    val sameArityTypes = (emittedTypes ?: parameterTypes)?.takeIf { it.size == values.size }
    // Only a method whose signature says so takes varargs
    val signature = if (methodName != null && sameArityTypes != null) {
        InvokedSignature.resolve(owner, methodName, sameArityTypes, generationScope)
    } else {
        null
    }
    // A generated owner, which cannot be loaded, takes varargs where its method overrides one that does
    val varargs = signature?.varargs == true || sameArityTypes != null && callMethod != null
        && generationScope.definitionOf(owner, objectDef)?.let { definition ->
            definition.methods.firstOrNull { declared -> declared.name == callMethod.name
                && declared.parameters.map { TypeHierarchy.erasedName(it.type, definition) } ==
                callMethod.parameters.map { TypeHierarchy.erasedName(it.type, definition) } }
                ?.let { overridesVarargs(definition, it) }
        } == true
    val receiverArguments = callMethod?.let { method ->
        OverrideResolver.receiverArguments(owner, objectDef, method, generationScope) - method.typeVariables.map { it.name }.toSet()
    }.orEmpty()
    val callee = CalleeBounds(callMethod?.typeVariables.orEmpty(), receiverArguments, false)
    val generated = generationScope.definitionOf(owner, objectDef) != null
    val overloaded = methodName != null && sameArityTypes != null && OverloadRules.hasApplicableOverload(
        owner, generationScope.definitionOf(owner, objectDef), methodName, sameArityTypes,
        values.map { value ->
            if (isNullLiteral(value) || smartCastKey(value)?.let { scope.isSmartCast(it) } == true) null
            else sourceTypeOf(value, methodDef, objectDef)
        }, generationScope)
    return Call(objectDef, methodDef, scope, owner, methodName, callMethod, values, emittedTypes, sameArityTypes,
        signature, varargs, callee, generated, receiverArguments, overloaded)
}

/**
 * An argument of a call, with what the rules look at.
 *
 * @property call       The call
 * @property index      The index of the argument
 * @property value      The value
 * @property sourceType The type the source gives the value
 */
private class Argument(val call: Call, val index: Int, val value: ExpressionDef, val sourceType: TypeDef) {
    val objectDef get() = call.objectDef
    val methodDef get() = call.methodDef
    val scope get() = call.scope

    /** The parameter: a variable of the receiver's class is the type argument it is bound with, the `E` of a `List<String>`. */
    val parameterType: TypeDef? = call.sameArityTypes?.get(index)?.let { type ->
        if (call.emittedTypes != null) type else OverloadRules.receiverBound(type,
            call.signature?.parameterTypes()?.takeIf { it.size == call.values.size }?.get(index),
            call.callMethod?.typeVariables.orEmpty(), call.receiverArguments)
    }

    /** The types cast to: a variable the invoked method declares names the one of the caller, whose bounds they are. */
    val castTypes: List<TypeDef>? = parameterType?.let { if (call.callee.names(it)) call.callee.of(it) else listOf(it) }

    val castType: TypeDef? = castTypes?.first()

    /** What the parameter is, beneath the annotations of its type. */
    val parameterKind: TypeDef? = parameterType?.let { TypeHierarchy.unwrap(it) }

    /** Whether the value is the array passed as the varargs, or one of them. */
    val vararg: Boolean = call.varargs && index == call.values.size - 1 && parameterType is TypeDef.Array

    val valueType: TypeDef = value.type()

    /** The name of the value, where it is a parameter or a local an earlier cast smart cast. */
    val smartCast: String? = stableName(value)?.takeIf { scope.isSmartCast(it) }

    /** Whether the parameter is a variable of the class, which is fixed: one the invoked method declares is inferred. */
    val fixedVariable: Boolean = parameterKind is TypeDef.TypeVariable
        && call.callMethod?.typeVariables?.none { it.name == parameterKind.name } != false

    /** Whether the value is cast to the parameter, which Kotlin does not take it as. */
    val needsCast: Boolean = parameterType != null && (requiresImplicitCast(parameterType, valueType)
        || !vararg && valueType == TypeDef.OBJECT
        && (parameterKind is TypeDef.Array || fixedVariable)
        // A value of a variable, or an array of another component, where an override narrowed the
        // parameter
        || !vararg && valueType is TypeDef.TypeVariable
        && parameterType is ClassTypeDef && parameterType != TypeDef.OBJECT
        // A value of another variable, a class, an array or a primitive, where an override narrowed
        // the parameter to a variable of the class, which is fixed - not one the invoked method
        // declares, which is inferred
        || !vararg && fixedVariable && parameterType != valueType
        && (valueType is TypeDef.TypeVariable || valueType is ClassTypeDef
        || valueType is TypeDef.Array || valueType is TypeDef.Primitive)
        || !vararg && valueType is TypeDef.Array && parameterType is TypeDef.Array
        && valueType != parameterType)

    /** The value passed: cast to the parameter where it needs to be. */
    val passed: ExpressionDef by lazy {
        if (!needsCast || isNullLiteral(value)) {
            // `null` needs no cast, and one to a type that is not nullable throws
            value
        } else {
            // A compiled method takes `null` where the bytecode passes it on: a cast to a type that is not
            // nullable would throw
            val parameterType = parameterType!!
            value.cast(if (call.generated || parameterType.isPrimitive
                || isKotlinClass(call.owner) && !declaresNullableParameter(call.owner, call.methodName, call.values.size, index)) parameterType
                else parameterType.makeNullable())
        }
    }
}

/** The rules of an argument, in order: the first that writes it applies, and the last writes any. */
private val ARGUMENT_RULES: List<(KotlinWriteContext, Argument) -> CodeBlock?> = listOf(
    KotlinWriteContext::narrowedParameterArgument,
    KotlinWriteContext::intersectionArrayArgument,
    KotlinWriteContext::smartCastToBoundsArgument,
    KotlinWriteContext::smartCastSpreadArgument,
    KotlinWriteContext::smartCastArgument,
    KotlinWriteContext::smartCastComposedArgument,
    KotlinWriteContext::inferredVariableArgument,
    KotlinWriteContext::boundCastArgument,
    KotlinWriteContext::invariantArgument,
    KotlinWriteContext::spreadArgument,
    KotlinWriteContext::pinnedOverloadArgument,
    KotlinWriteContext::convertedArgument
)

/**
 * An override narrowed the parameter the value names - `Any` to `String` - which would select another overload
 * than the one the model calls: its type is kept. Written out, since in the model the cast is to the type the value
 * already has, which is dropped.
 */
private fun KotlinWriteContext.narrowedParameterArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterType == null || vararg || sourceType == value.type() || parameterType == sourceType) {
        return null
    }
    CodeBlock.builder()
        .add("(")
        .add(renderExpressionCode(objectDef, methodDef, scope, value))
        .add(" as %T)", asType(castType, objectDef, methodDef))
        .build()
}

/**
 * An array of a variable of several bounds, which no array type expresses: a generic helper's variable is inferred
 * as them.
 */
private fun KotlinWriteContext.intersectionArrayArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterKind !is TypeDef.Array || vararg) {
        return null
    }
    val component = TypeHierarchy.unwrap(parameterKind.componentType)
    val bounds = if (component is TypeDef.TypeVariable && call.callee.names(component)) call.callee.of(component) else null
    if (bounds == null || bounds.size <= 1) {
        return null
    }
    renderIntersectionArray(objectDef, methodDef, scope, value, bounds, parameterKind.dimensions, parameterType!!.isNullable)
}

/** Every bound is cast to, whatever an earlier cast made of the value. */
private fun KotlinWriteContext.smartCastToBoundsArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterType == null || castTypes == null || smartCast == null || castTypes.count { it != TypeDef.OBJECT } <= 1) {
        return null
    }
    val name = renderExpressionCode(objectDef, methodDef, scope, value)
    val builder = CodeBlock.builder().add("run { ")
    castTypes.filter { it != TypeDef.OBJECT }.forEach { builder.add("%L as %T; ", name, asStarProjected(it, objectDef)) }
    builder.add("%L }", name).build()
}

/** An array passed as the varargs is still spread, as the array type the model gives it. */
private fun KotlinWriteContext.smartCastSpreadArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterType == null || castType == null || smartCast == null || !vararg
        || TypeHierarchy.unwrap(sourceType) !is TypeDef.Array) {
        return null
    }
    CodeBlock.of("*(%L as %T)", renderExpressionCode(objectDef, methodDef, scope, value), asType(castType, objectDef, methodDef))
}

/**
 * A value an earlier bound cast smart cast is passed as the type the model gives it, which keeps the overload the
 * model calls - an element of varargs as their component, and a variable of the invoked method as its bound.
 */
private fun KotlinWriteContext.smartCastArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterType == null || castType == null || smartCast == null) {
        return null
    }
    val passedType = if (vararg) (TypeHierarchy.unwrap(castType) as? TypeDef.Array)?.let { array ->
        if (array.dimensions == 1) array.componentType else TypeDef.array(array.componentType, array.dimensions - 1)
    } ?: castType else castType
    CodeBlock.of("%L as %T", renderExpressionCode(objectDef, methodDef, scope, value), asKotlinComparable(asType(passedType, objectDef, methodDef)))
}

/**
 * A conditional or a `when` of values an earlier cast smart cast is of the type they were cast to: the cast to the
 * parameter keeps the overload the model calls.
 */
private fun KotlinWriteContext.smartCastComposedArgument(argument: Argument): CodeBlock? = with(argument) {
    if (parameterType == null || vararg || value !is IfElse && value !is Switch
        || composedOf(value).none { result -> stableName(result)?.let { scope.isSmartCast(it) } == true }) {
        return null
    }
    CodeBlock.of("(%L as %T)", renderExpressionCode(objectDef, methodDef, scope, value), asType(parameterType, objectDef, methodDef))
}

/** A value inferred as a variable of the invoked method has to satisfy every bound. */
private fun KotlinWriteContext.inferredVariableArgument(argument: Argument): CodeBlock? = with(argument) {
    if (castTypes == null || vararg || parameterKind !is TypeDef.TypeVariable || fixedVariable) {
        return null
    }
    if (castTypes.any { it != TypeDef.OBJECT && !satisfiesBound(it, valueType, objectDef, methodDef) }) {
        renderCalleeCast(objectDef, methodDef, scope, value, castTypes)
    } else {
        renderExpressionCode(objectDef, methodDef, scope, value)
    }
}

/** A value cast to a variable the invoked method declares is cast to its bounds. */
private fun KotlinWriteContext.boundCastArgument(argument: Argument): CodeBlock? = with(argument) {
    if (!needsCast || castTypes == null || castType == parameterType) {
        return null
    }
    renderCalleeCast(objectDef, methodDef, scope, value, castTypes)
}

/** Type arguments are invariant: a `Supplier<Int>` is no `Supplier<Number>` without a cast. */
private fun KotlinWriteContext.invariantArgument(argument: Argument): CodeBlock? = with(argument) {
    if (needsCast || parameterType !is ClassTypeDef.Parameterized || valueType !is ClassTypeDef.Parameterized
        || parameterType.rawType.name != valueType.rawType.name || parameterType.typeArguments == valueType.typeArguments
        || parameterType.typeArguments.any { TypeHierarchy.unwrap(it) is TypeDef.Wildcard || TypeHierarchy.unwrap(it) is TypeDef.TypeVariable }
        || valueType.typeArguments.any { TypeHierarchy.unwrap(it) is TypeDef.Wildcard || TypeHierarchy.unwrap(it) is TypeDef.TypeVariable }) {
        return null
    }
    CodeBlock.of("(%L as %T)", renderExpressionCode(objectDef, methodDef, scope, value), asType(parameterType, objectDef, methodDef))
}

/** An array passed as the varargs is spread, or it would be their single element. */
private fun KotlinWriteContext.spreadArgument(argument: Argument): CodeBlock? = with(argument) {
    if (!vararg || valueType !is TypeDef.Array) {
        return null
    }
    val rendered = renderExpressionCode(objectDef, methodDef, scope, passed)
    CodeBlock.builder().add("*").add(if (passed is Cast) addParentheses(rendered) else rendered).build()
}

/**
 * The cast names the overload of the model where another would take the value - a primitive through its numeric
 * conversion, which a cast of the boxed value is not: `abs(value.toLong())`.
 */
private fun KotlinWriteContext.pinnedOverloadArgument(argument: Argument): CodeBlock? = with(argument) {
    if (passed !== value || !call.overloaded || parameterType == null || vararg
        || !OverloadRules.pinsOverload(parameterType, if (isNullLiteral(value)
            || smartCastKey(value)?.let { scope.isSmartCast(it) } == true) null
            // A variable named alone selects by the bound declared where the call is written
            else OverloadRules.lexicalErasure(sourceType, objectDef, methodDef),
            call.callMethod?.typeVariables.orEmpty())) {
        return null
    }
    val conversion = if (isNullLiteral(value)) null else primitiveConversion(parameterType, sourceType)
    if (conversion != null) {
        var operand = renderExpressionCode(objectDef, methodDef, scope, value)
        if (requiresConversionTargetParentheses(unwrapCasts(value))) {
            operand = addParentheses(operand)
        }
        return operand.toBuilder().add(conversion).build()
    }
    CodeBlock.of("(%L as %T)", if (isNullLiteral(value)) CodeBlock.of("null") else renderExpressionCode(objectDef, methodDef, scope, value),
        asType(if (isNullLiteral(value)) parameterType.makeNullable() else parameterType, objectDef))
}

/** A primitive is converted to the parameter, which Kotlin does not widen it to. */
private fun KotlinWriteContext.convertedArgument(argument: Argument): CodeBlock = with(argument) {
    val converted = if (parameterType != null && !vararg) KotlinConversions.coerce(passed, parameterType) else passed
    if (converted is Constant && converted.value is Number && isByteOrShort(converted.type) && parameterType != null
        && isByteOrShort(parameterType)) {
        CodeBlock.of("%L", converted.value)
    } else {
        renderExpressionCode(objectDef, methodDef, scope, converted,
            acceptsNull = !vararg && call.sameArityTypes != null
                && takesNull(call.owner, objectDef, call.methodName, call.sameArityTypes, index))
    }
}

/**
 * A value cast to the bounds of a variable the invoked method declares: an unbounded wildcard of one is
 * `*`, and a value of several bounds is smart cast to each of them, where it is a parameter or a local.
 */
private fun KotlinWriteContext.renderCalleeCast(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    value: ExpressionDef,
    castTypes: List<TypeDef>
): CodeBlock {
    var operand = renderExpressionCode(objectDef, methodDef, scope, value)
    val bounds = castTypes.filter { it != TypeDef.OBJECT }.ifEmpty { castTypes }
    val stable = stableName(value)
    if (bounds.size > 1) {
        // Written within a statement, whose continuation lines are indented twice: the body is indented
        // once from the statement, and the closing brace aligned with it. A parameter or a local is smart
        // cast to each bound; another value is read once, into the argument of `let`
        val block = CodeBlock.builder()
        val name = if (stable != null) {
            block.add("run {\n")
            operand
        } else {
            if (requiresMethodCallTargetParentheses(value)) {
                operand = addParentheses(operand)
            }
            block.add("%L.let { arg ->\n", operand)
            CodeBlock.of("arg")
        }
        block.unindent()
        bounds.forEach { block.add("%L as %T\n", name, asStarProjected(it, objectDef)) }
        return block.add("%L\n", name).unindent().add("}").indent().indent().build()
    }
    if (stable != null) {
        // The cast smart casts the value for the statements after it
        scope.markSmartCast(stable)
    }
    if (requiresCastOperandParentheses(unwrapCasts(value))) {
        operand = addParentheses(operand)
    }
    return CodeBlock.of("%L as %T", operand, asStarProjected(bounds.first(), objectDef))
}

/**
 * A value converted to an array of a variable of several bounds, by a generic helper of an anonymous object,
 * whose variable the invocation infers as the intersection no array type expresses.
 */
private fun KotlinWriteContext.renderIntersectionArray(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    value: ExpressionDef,
    bounds: List<TypeDef>,
    dimensions: Int,
    nullable: Boolean = false
): CodeBlock {
    // The bounds can name a variable of the class or of the function, which the helper's own must not shadow
    var name = "T"
    var suffix = 1
    while (isVariablePartOfTheDefinition(name, objectDef, methodDef, false)) {
        name = "T" + suffix++
    }
    var array: TypeName = TypeVariableName(name)
    repeat(dimensions) { array = ARRAY.parameterizedBy(array) }
    // `null` stays `null` where the parameter takes it: a cast to an array that is not nullable throws
    array = array.copy(nullable = nullable)
    val constraints = bounds.map { CodeBlock.of("%L : %T", name, asStarProjected(it, objectDef)) }.joinToCode(", ")
    return CodeBlock.of(
        "object { @Suppress(%S) fun <%L> cast(value: Any?): %T where %L = value as %T }.cast(%L)",
        "UNCHECKED_CAST", name, array, constraints, array, renderExpressionCode(objectDef, methodDef, scope, value)
    )
}

private fun composedOf(value: ExpressionDef): List<ExpressionDef> = when (value) {
    is IfElse -> composedOf(value.ifExpression) + composedOf(value.elseExpression)
    is Switch -> value.cases.values.flatMap { composedOf(it) } + listOfNotNull(value.defaultCase).flatMap { composedOf(it) }
    else -> listOf(value)
}

/**
 * The name Kotlin smart casts a value by: a parameter, a local, or a property of `this` or of a type, which
 * a cast of a `val` smart casts for the statements that follow.
 */
internal fun smartCastKey(value: ExpressionDef): String? = stableName(value) ?: when (value) {
    is VariableDef.Field -> if (value.instance is VariableDef.This) "this.${value.name}" else null
    is VariableDef.StaticField -> "${value.ownerType.name}.${value.name}"
    else -> null
}

/**
 * The name of a parameter or a local, which Kotlin smart casts, or `null` for another value.
 */
private fun stableName(value: ExpressionDef): String? = when (value) {
    is VariableDef.MethodParameter -> value.name
    is VariableDef.Local -> value.name
    else -> null
}

/**
 * Whether a value is known to satisfy a bound of a variable the invoked method declares, which Kotlin then
 * infers the variable from: a subclass, the parameterization of a class, or a value of a variable of the caller.
 */
private fun KotlinWriteContext.satisfiesBound(bound: TypeDef, value: TypeDef, objectDef: ObjectDef?, methodDef: MethodDef?): Boolean {
    if (value.isNullable && !bound.isNullable) {
        // A nullable value does not satisfy a bound that is not
        return false
    }
    val boxed = if (value is TypeDef.Primitive) value.wrapperType() else value
    if (bound == TypeDef.OBJECT || bound == boxed) {
        return true
    }
    if (bound is TypeDef.TypeVariable || boxed == TypeDef.OBJECT) {
        return false
    }
    if (boxed is TypeDef.TypeVariable) {
        // A value of a variable of the caller satisfies what one of its own bounds does
        return OverrideResolver.upperBounds(boxed, objectDef, methodDef)
            .any { satisfiesBound(bound, it, objectDef, methodDef) }
    }
    if (bound is ClassTypeDef.Parameterized) {
        val inherited = OverrideResolver.inheritedAs(boxed, bound, generationScope) as? ClassTypeDef.Parameterized
            ?: return false
        return bound.typeArguments.size == inherited.typeArguments.size
            && bound.typeArguments.zip(inherited.typeArguments).all { (expected, actual) ->
                expected == actual || expected is TypeDef.Wildcard && expected.lowerBounds.isEmpty()
                    && (expected.upperBounds.isEmpty() || expected.upperBounds[0] == TypeDef.OBJECT)
            }
    }
    if (bound is ClassTypeDef && boxed is ClassTypeDef) {
        val boundClass = generationScope.typeLookup().loadClass(bound.name)
        val valueClass = generationScope.typeLookup().loadClass(boxed.name)
        if (boundClass != null && valueClass != null) {
            return boundClass.isAssignableFrom(valueClass)
        }
        return TypeHierarchy.inherits(boxed, bound.name, generationScope.elementLookup())
    }
    return true
}
