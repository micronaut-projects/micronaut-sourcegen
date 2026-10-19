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
import io.micronaut.sourcegen.generator.OverrideResolver
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import org.jspecify.annotations.Nullable

/*
 * The expressions of a body.
 */

private val NUMBER = ClassName("kotlin", "Number")

/**
 * A value written to a parameter, a return, a local or a field of the type, converted to it as the bytecode
 * writer converts it. An integer constant written to a byte or a short is a literal Kotlin types as one.
 */
internal fun KotlinWriteContext.renderAssigned(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    value: ExpressionDef,
    target: TypeDef,
    // Whether the Kotlin type written to is nullable, where the model's need not be
    acceptsNull: Boolean = false
): CodeBlock {
    val converted = KotlinConversions.coerce(value, target)
    if (converted is Constant && converted.value is Number && isByteOrShort(converted.type) && isByteOrShort(target)) {
        return CodeBlock.of("%L", converted.value)
    }
    if (converted is NewInstance && TypeHierarchy.unwrap(target) !is ClassTypeDef.Parameterized) {
        // A raw generic class instantiated as an Object, which infers no type argument: Java's raw type
        rawInstantiationArguments(converted.type)?.let { arguments ->
            return renderNewInstance(objectDef, methodDef, scope, converted, arguments)
        }
    }
    return renderExpressionCode(objectDef, methodDef, scope, converted, target, acceptsNull)
}

private fun KotlinWriteContext.renderNewInstance(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: NewInstance,
    typeArguments: List<TypeName>?
): CodeBlock {
    val codeBuilder = CodeBlock.builder()
    if (expressionDef.type.name == TypeDef.STRING.name && expressionDef.parameterTypes.size == 1
        && (TypeHierarchy.unwrap(expressionDef.parameterTypes[0]) as? ClassTypeDef)?.name == TypeDef.STRING.name) {
        // Kotlin's String has no copy constructor, the Java class has
        return CodeBlock.of("(%T(%L) as %T)", ClassName("java.lang", "String"),
            renderAssigned(objectDef, methodDef, scope, expressionDef.values[0], TypeDef.STRING), STRING)
    }
    val name = asClassName(expressionDef.type)
    codeBuilder.add("%T(", if (typeArguments == null) name else name.parameterizedBy(typeArguments))
    codeBuilder.add(renderArguments(
        objectDef, methodDef, scope, expressionDef.type, "<init>", null,
        expressionDef.parameterTypes, expressionDef.values
    ))
    codeBuilder.add(")")
    return codeBuilder.build()
}

/**
 * An expression written where a value of the type is expected: asserted with `!!` where the model types the value
 * nullable and the type is not.
 */
internal fun KotlinWriteContext.renderExpressionCode(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ExpressionDef?,
    expectedType: TypeDef,
    acceptsNull: Boolean = false
): CodeBlock {
    val codeBlock = renderExpressionCode(objectDef, methodDef, scope, expressionDef, acceptsNull = acceptsNull)
    val builder = codeBlock.toBuilder()
    if (!acceptsNull && !expectedType.isNullable && expressionDef?.type()?.isNullable == true) {
        builder.add("!!")
    }
    return builder.build()
}

/**
 * An expression, by its kind.
 *
 * @param isRef       Whether the value is read as it is, a cast of it to the type it has left out
 * @param acceptsNull Whether the value is written where Kotlin takes `null`: a value it types as nullable, where the
 *                    model does not, is written as it is, not asserted with `!!`
 */
internal fun KotlinWriteContext.renderExpressionCode(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ExpressionDef?,
    isRef: Boolean = false,
    acceptsNull: Boolean = false
): CodeBlock = when (expressionDef) {
    is NewInstance -> renderNewInstance(objectDef, methodDef, scope, expressionDef, null)
    is InvokeInstanceMethod -> renderInvokeInstanceMethod(objectDef, methodDef, scope, expressionDef, acceptsNull)
    is GetPropertyValue -> renderGetPropertyValue(objectDef, methodDef, scope, expressionDef)
    is InvokeStaticMethod -> renderInvokeStaticMethod(objectDef, methodDef, scope, expressionDef, acceptsNull)
    is ArrayElement -> renderArrayElement(objectDef, methodDef, scope, expressionDef)
    is Cast -> renderCast(objectDef, methodDef, scope, expressionDef, isRef, acceptsNull)
    is VariableDef -> renderVariable(objectDef, methodDef, scope, expressionDef, acceptsNull)
    is Constant -> renderConstantExpression(expressionDef, methodDef, scope)
    is And -> renderAnd(objectDef, methodDef, scope, expressionDef)
    is Or -> renderOr(objectDef, methodDef, scope, expressionDef)
    is IfElse -> renderIfElse(objectDef, methodDef, scope, expressionDef, acceptsNull)
    is Switch -> renderSwitchExpression(objectDef, methodDef, scope, expressionDef)
    is SwitchYieldCase -> renderSwitchYieldCase(objectDef, methodDef, scope, expressionDef)
    is IsNull -> renderNullCheck(objectDef, methodDef, scope, expressionDef.expression, true)
    is IsNotNull -> renderNullCheck(objectDef, methodDef, scope, expressionDef.expression, false)
    is IsTrue -> renderIsTrue(objectDef, methodDef, scope, expressionDef)
    is IsFalse -> renderIsFalse(objectDef, methodDef, scope, expressionDef)
    is InstanceOf -> renderInstanceOf(objectDef, methodDef, scope, expressionDef)
    is MathBinaryOperation -> renderMathBinaryOperation(objectDef, methodDef, scope, expressionDef)
    is MathUnaryOperation -> renderMathUnaryOperation(objectDef, methodDef, scope, expressionDef)
    is ComparisonOperation -> renderComparison(objectDef, methodDef, scope, expressionDef)
    is NewArrayOfSize -> renderNewArrayOfSize(objectDef, methodDef, scope, expressionDef)
    is NewArrayInitialized -> renderNewArrayInitialized(objectDef, methodDef, scope, expressionDef)
    is InvokeGetClassMethod -> renderInvokeGetClassMethod(objectDef, methodDef, scope, expressionDef)
    is InvokeHashCodeMethod -> renderInvokeHashCodeMethod(objectDef, methodDef, scope, expressionDef)
    is EqualsStructurally -> renderStructuralEquality(objectDef, methodDef, scope, expressionDef.instance, expressionDef.other, false)
    is NotEqualsStructurally -> renderStructuralEquality(objectDef, methodDef, scope, expressionDef.instance, expressionDef.other, true)
    is EqualsReferentially -> renderReferentialEquality(objectDef, methodDef, scope, expressionDef.instance, expressionDef.other, false)
    is NotEqualsReferentially -> renderReferentialEquality(objectDef, methodDef, scope, expressionDef.instance, expressionDef.other, true)
    is Lambda -> renderLambda(objectDef, methodDef, scope, expressionDef)
    is MethodReferenceExpression -> renderMethodReference(objectDef, methodDef, scope, expressionDef)
    is StringConcatenation -> renderStringConcatenation(objectDef, methodDef, scope, expressionDef)
    else -> throw IllegalStateException("Unrecognized expression: $expressionDef")
}

/** A call of an instance method or a constructor: a Java method Kotlin maps is written as it maps it. */
private fun KotlinWriteContext.renderInvokeInstanceMethod(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: InvokeInstanceMethod,
    acceptsNull: Boolean
): CodeBlock {
    var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
    val codeBuilder = CodeBlock.builder()
    if (expressionDef.method.isConstructor) {
        codeBuilder.add(instanceExp)
        codeBuilder.add("(")
    } else {
        if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
            instanceExp = addParentheses(instanceExp)
        }
        val receiverType = sourceTypeOf(expressionDef.instance, methodDef, objectDef)
        val receiverClass = if (expressionDef.instance is VariableDef.This || expressionDef.instance is VariableDef.Super) null
            else loadedClass(receiverType, generationScope.typeLookup())
        // A generated receiver, `this` and `super` inherit the property from the types they extend
        val receiverDefinition = when (expressionDef.instance) {
            is VariableDef.This, is VariableDef.Super -> objectDef
            else -> generationScope.definitionOf(receiverType as? ClassTypeDef, objectDef)
        }
        val property = KotlinJavaMappings.mappedProperty(receiverClass, expressionDef.method)
            ?: receiverDefinition?.let { KotlinJavaMappings.inheritedProperty(generationScope, it, expressionDef.method, HashSet()) }
        if (property != null) {
            // Kotlin sees the method as a property of the type it maps the Java one to: `text.length`, a
            // nullable one where Java's result can be null: `e.message`
            return CodeBlock.of(if (!acceptsNull && isNullableJavaResult(expressionDef, objectDef, methodDef)) "%L.%N!!" else "%L.%N",
                instanceExp, property)
        }
        if (receiverDefinition is RecordDef && expressionDef.method.parameters.isEmpty()
            && receiverDefinition.properties.any { it.name == expressionDef.method.name }) {
            // The accessor of a record component is the property of the data class
            return CodeBlock.of("%L.%N", instanceExp, expressionDef.method.name)
        }
        var methodName = expressionDef.method.name
        val supertypes = if (receiverClass != null) listOf(receiverClass) else receiverDefinition?.let { KotlinJavaMappings.compiledSupertypes(generationScope, it) }.orEmpty()
        when (val mapped = KotlinJavaMappings.mappedCall(receiverClass, supertypes, receiverType, expressionDef.method)) {
            null -> Unit
            is KotlinJavaMappings.MappedCall.Renamed -> methodName = mapped.name
            // A super call is of the member itself
            else -> if (expressionDef.instance !is VariableDef.Super) when (mapped) {
                is KotlinJavaMappings.MappedCall.Conversion -> return CodeBlock.of("%L%L", instanceExp, mapped.call)
                is KotlinJavaMappings.MappedCall.Identity -> return instanceExp
                is KotlinJavaMappings.MappedCall.Property -> return if (mapped.boxed) {
                    CodeBlock.of("(%L as %T).%N", instanceExp, ANY, mapped.name)
                } else {
                    CodeBlock.of("%L.%N", instanceExp, mapped.name)
                }
                is KotlinJavaMappings.MappedCall.ThroughJavaType -> instanceExp = CodeBlock.of("(%L as %L)", instanceExp, mapped.type)
            }
        }
        val mutable = ((receiverType as? ClassTypeDef.Parameterized)?.rawType ?: receiverType as? ClassTypeDef)
            ?.let { KotlinJavaMappings.mutableCollectionFor(it.name, expressionDef.method.name) }
        if (mutable != null) {
            // The read-only type Kotlin maps the Java one to does not declare its mutators
            val arguments = (receiverType as? ClassTypeDef.Parameterized)?.typeArguments.orEmpty()
            val mutableType = ClassName("kotlin.collections", mutable).let { raw ->
                if (arguments.isEmpty()) raw else raw.parameterizedBy(arguments.map { asType(it, objectDef, methodDef) })
            }
            instanceExp = CodeBlock.of("(%L as %T)", instanceExp, mutableType)
        }
        codeBuilder.add(instanceExp)
        if (expressionDef.instance is InvokeInstanceMethod) {
            codeBuilder.add("\n")
        }
        codeBuilder.add(".%N(", methodName)
    }
    codeBuilder.add(renderArguments(
        objectDef, methodDef, scope, ownerOf(objectDef, expressionDef.instance.type()),
        expressionDef.method.name, expressionDef.method,
        expressionDef.method.parameters.map { it.type }, expressionDef.values
    ))
    codeBuilder.add(")")
    if (!acceptsNull && !expressionDef.method.isConstructor
        && generatedReturnsNullable(ownerOf(objectDef, expressionDef.instance.type()), objectDef, expressionDef.method)) {
        // A generated method whose result can be null, where Kotlin needs a value
        codeBuilder.add("!!")
    }
    return codeBuilder.build()
}

private fun KotlinWriteContext.renderGetPropertyValue(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: GetPropertyValue
): CodeBlock {
    var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
    if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
        instanceExp = addParentheses(instanceExp)
    }
    val codeBuilder = instanceExp.toBuilder()
    codeBuilder.add(".%L", expressionDef.propertyElement.name)
    return codeBuilder.build()
}

private fun KotlinWriteContext.renderInvokeStaticMethod(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: InvokeStaticMethod,
    acceptsNull: Boolean
): CodeBlock {
    val codeBuilder = CodeBlock.builder()
    codeBuilder.add("%T.%N(", asStaticOwnerName(expressionDef.classDef), expressionDef.method.name)
    codeBuilder.add(renderArguments(
        objectDef, methodDef, scope, expressionDef.classDef, expressionDef.method.name, expressionDef.method,
        expressionDef.method.parameters.map { it.type }, expressionDef.values
    ))
    codeBuilder.add(")")
    if (!acceptsNull && generatedReturnsNullable(expressionDef.classDef, objectDef, expressionDef.method)) {
        codeBuilder.add("!!")
    }
    compiledReturnConversion(expressionDef.classDef, expressionDef.method)?.let { codeBuilder.add(it) }
    return codeBuilder.build()
}

/**
 * The conversion of the primitive a compiled static method returns to the other primitive the model calls it for:
 * `Math.max(int, int)` called for a long, whose result Java widens.
 */
private fun compiledReturnConversion(owner: ClassTypeDef, method: MethodDef): String? {
    val requested = TypeHierarchy.unwrap(method.returnType) as? TypeDef.Primitive ?: return null
    val parameterClasses = method.parameters.map { parameter ->
        val type = TypeHierarchy.unwrap(parameter.type)
        (if (type is TypeDef.Primitive) primitiveClassOf(type) else javaClassOf(type)) ?: return null
    }
    val compiled = try {
        loadedClass(owner)?.getMethod(method.name, *parameterClasses.toTypedArray())
    } catch (e: NoSuchMethodException) {
        null
    } ?: return null
    if (!java.lang.reflect.Modifier.isStatic(compiled.modifiers)) {
        return null
    }
    val returned = compiled.returnType.takeIf { it.isPrimitive } ?: return null
    return KotlinConversions.conversion(requested, TypeDef.primitive(returned.name))
}

private fun KotlinWriteContext.renderArrayElement(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ArrayElement
): CodeBlock {
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

/** A cast: a numeric conversion where Kotlin has no primitive cast, and none where the value already has the type. */
private fun KotlinWriteContext.renderCast(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: Cast,
    isRef: Boolean,
    acceptsNull: Boolean
): CodeBlock {
    val exp: ExpressionDef = collapseNestedCasts(expressionDef.expressionDef)
    val boxConversion = boxedNumericConversion(expressionDef.type, exp)
    if (boxConversion != null) {
        // A number or a char converted to the box of another is unboxed, converted and boxed: `p0.toFloat()`
        var operand = renderExpressionCode(objectDef, methodDef, scope, exp, boxConversion.first)
        if (requiresConversionTargetParentheses(unwrapCasts(exp))) {
            operand = addParentheses(operand)
        }
        return operand.toBuilder().add(boxConversion.second).build()
    }
    if (expressionDef.type == exp.type() || isRef) {
        return renderExpressionCode(objectDef, methodDef, scope, exp, acceptsNull = acceptsNull)
    }
    val castType = expressionDef.type
    if (acceptsNull && !castType.isPrimitive && unwrapCasts(exp) is VariableDef && !exp.type().isPrimitive) {
        // A variable Kotlin holds null in, where the model types it not null, cast where null is taken: the null
        // passes the cast, as it does in Java
        val raw = renderExpressionCode(objectDef, methodDef, scope, exp, acceptsNull = true)
        if (raw.toString() != renderExpressionCode(objectDef, methodDef, scope, exp, castType).toString()) {
            val operand = if (requiresCastOperandParentheses(unwrapCasts(exp))) addParentheses(raw) else raw
            smartCastKey(exp)?.let { scope.markSmartCast(it) }
            return CodeBlock.of("%L as %T", operand, asType(castType, objectDef, methodDef).copy(nullable = true))
        }
    }
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
    if (isNullLiteral(exp) && (TypeHierarchy.unwrap(castType) is ClassTypeDef || TypeHierarchy.unwrap(castType) is TypeDef.Array)) {
        // `null as String` always throws: the cast only names the type, as for an overload
        return CodeBlock.of("null as %T", asType(castType, objectDef, methodDef).copy(nullable = true))
    }
    // Kotlin smart casts a parameter, a local or a property the model casts, for the statements that follow
    smartCastKey(exp)?.let { scope.markSmartCast(it) }
    if (castType is TypeDef.Primitive && castType.isNumber && !exp.type().isPrimitive && !isNullLiteral(exp)) {
        // The bytecode unboxes a reference through Number, which converts any number: `as Int` only takes
        // an Integer
        val operand = if (requiresCastOperandParentheses(unwrapCasts(exp))) addParentheses(rendered) else rendered
        return CodeBlock.of("(%L as %T)%L", operand, NUMBER, KotlinConversions.numberConversion(castType))
    }
    val codeBuilder = CodeBlock.builder()
    if (requiresCastOperandParentheses(unwrapCasts(exp))) {
        codeBuilder.add(addParentheses(rendered))
    } else {
        codeBuilder.add(rendered)
    }
    codeBuilder.add(" as %T", asType(castType, objectDef, methodDef))
    return codeBuilder.build()
}

private fun KotlinWriteContext.renderAnd(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: And
): CodeBlock {
    return CodeBlock.builder()
        .add(renderAndConditionOperand(objectDef, methodDef, scope, expressionDef.left))
        .add(" && ")
        .add(renderAndConditionOperand(objectDef, methodDef, scope, expressionDef.right))
        .build()
}

private fun KotlinWriteContext.renderOr(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: Or
): CodeBlock {
    return CodeBlock.builder()
        .add(renderCondition(objectDef, methodDef, scope, expressionDef.left))
        .add(" || ")
        .add(renderCondition(objectDef, methodDef, scope, expressionDef.right))
        .build()
}

private fun KotlinWriteContext.renderIfElse(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: IfElse,
    acceptsNull: Boolean
): CodeBlock {
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
        .add(renderConditionalBranch(objectDef, methodDef, scope, expressionDef.ifExpression, expressionDef.elseExpression,
            expressionDef.type(), acceptsNull))
        .add(" else ")
        .add(renderConditionalBranch(objectDef, methodDef, scope, expressionDef.elseExpression, expressionDef.ifExpression,
            expressionDef.type(), acceptsNull))
        .build()
}

/**
 * A branch of a conditional, converted to the conditional's type as Java converts it, which Kotlin does not: a
 * primitive widened, a byte, a short or a char to the int of an `Integer` where the other branch is no such primitive
 * (Java's numeric promotion), a reference cast to a type it may not have, and a `null` of a primitive conditional
 * unboxed, which throws.
 */
private fun KotlinWriteContext.renderConditionalBranch(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    branch: ExpressionDef,
    otherBranch: ExpressionDef,
    type: TypeDef,
    acceptsNull: Boolean
): CodeBlock {
    val targetType = TypeHierarchy.unwrap(type)
    val branchType = TypeHierarchy.unwrap(branch.type())
    if (isNullLiteral(branch) && targetType is TypeDef.Primitive) {
        return CodeBlock.of("null!!")
    }
    if (targetType !is TypeDef.Primitive && boxedNumericConversion(targetType, branch) != null) {
        // A number or a char of another type is converted to the box of the conditional's
        return renderExpressionCode(objectDef, methodDef, scope, branch.cast(type))
    }
    if (targetType is TypeDef.Primitive && targetType != TypeDef.VOID && branchType !is TypeDef.Primitive
        && !isNullLiteral(branch) && KotlinConversions.unboxed(branchType) != targetType) {
        // A reference of another type is converted to the conditional's primitive as the bytecode converts it:
        // through Number, or checked as the box of a char or a boolean
        return renderExpressionCode(objectDef, methodDef, scope, branch.cast(type))
    }
    if (branchType is TypeDef.Primitive && KotlinConversions.unboxed(targetType) == TypeDef.Primitive.INT
        && (branchType == TypeDef.Primitive.BYTE || branchType == TypeDef.Primitive.SHORT || branchType == TypeDef.Primitive.CHAR)
        && TypeHierarchy.unwrap(otherBranch.type()) != branchType) {
        return renderExpressionCode(objectDef, methodDef, scope, KotlinConversions.coerce(branch, TypeDef.Primitive.INT))
    }
    if (targetType !is TypeDef.Primitive && !isNullLiteral(branch)
        && (unrelatedTypes(targetType, branchType) || castCanFail(branch.cast(targetType)))) {
        // A value Kotlin types as nullable stays nullable: the null passes the cast, as it does in Java
        val nullable = branchType !is TypeDef.Primitive && (branchType.isNullable || KotlinConversions.unboxed(branchType) != null)
        val rendered = renderExpressionCode(objectDef, methodDef, scope, branch, acceptsNull = nullable)
        val operand = if (requiresCastOperandParentheses(unwrapCasts(branch))) addParentheses(rendered) else rendered
        return CodeBlock.of("%L as %T", operand, asType(type, objectDef, methodDef).let { if (nullable) it.copy(nullable = true) else it })
    }
    return renderExpressionCode(objectDef, methodDef, scope, KotlinConversions.coerce(branch, type), type, acceptsNull)
}

private fun KotlinWriteContext.renderSwitchExpression(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: Switch
): CodeBlock {
    val builder: CodeBlock.Builder = CodeBlock.builder()
    builder.add("when (")
    builder.add(renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression))
    builder.add(") {\n")
    builder.indent()
    for ((key, value) in expressionDef.cases) {
        builder.add(renderExpressionCode(objectDef, methodDef, scope,
            KotlinConversions.coerce(key, expressionDef.expression.type())))
        builder.add(" -> ")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, KotlinConversions.coerce(value, expressionDef.type)))
        if (value is SwitchYieldCase) {
            builder.add("\n")
        } else {
            builder.add(";\n")
        }
    }
    expressionDef.defaultCase?.let { defaultCase ->
        builder.add("else -> ")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, KotlinConversions.coerce(defaultCase, expressionDef.type)))
    }
    builder.unindent()
    builder.add("}")
    return builder.build()
}

/** A case of a switch expression that yields from statements: a block, or a lambda whose returns are its value. */
private fun KotlinWriteContext.renderSwitchYieldCase(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: SwitchYieldCase
): CodeBlock {
    val builder: CodeBlock.Builder = CodeBlock.builder()
    val statement = expressionDef.statement
    val flatten = statement.flatten()
    check(!flatten.isEmpty()) { "SwitchYieldCase did not return any statements" }
    val last = flatten[flatten.size - 1]
    val rest: List<StatementDef> = flatten.subList(0, flatten.size - 1)
    val caseScope = scope.nested(null)
    caseScope.returnType = expressionDef.type
    if (last is Return && rest.none { containsOwnReturn(it) }) {
        builder.add("{\n")
        builder.indent()
        for (statementDef in rest) {
            builder.add(renderStatementCodeBlock(objectDef, methodDef, caseScope, statementDef))
        }
        renderYield(builder, methodDef, caseScope, last, objectDef, expressionDef.type)
        builder.unindent()
        builder.add("}")
    } else {
        // A case yielding from a branch, or before its end, is a lambda whose returns are the case's value
        val label = caseScope.allocate("yield")
        caseScope.declare(label)
        caseScope.returnLabel = label
        builder.add("run %L@{\n", label)
        builder.indent()
        for ((index, statementDef) in flatten.withIndex()) {
            builder.add(renderStatementCodeBlock(objectDef, methodDef, caseScope, statementDef, index == flatten.size - 1))
            if (Completion.KOTLIN.cannotCompleteNormally(statementDef)) {
                break
            }
        }
        builder.unindent()
        builder.add("}")
    }
    // Rendered as text, so that its statements are not nested in the enclosing one - and taken as it is,
    // whatever `%` it has
    return CodeBlock.of("%L", builder.build().toString())
}

private fun KotlinWriteContext.renderIsTrue(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: IsTrue
): CodeBlock {
    val expression = unwrapCasts(expressionDef.expression)
    if (expression is ConditionExpressionDef) {
        return renderExpressionCode(objectDef, methodDef, scope, expression)
    }
    val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression)
    // `if (a) b else c || d` reads `if (a) b else (c || d)`: an `if` or a `when` takes all that follows it
    return if (expression is IfElse || expression is Switch) addParentheses(rendered) else rendered
}

private fun KotlinWriteContext.renderIsFalse(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: IsFalse
): CodeBlock {
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

private fun KotlinWriteContext.renderInstanceOf(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: InstanceOf
): CodeBlock {
    smartCastKey(expressionDef.expression)?.let { scope.markSmartCast(it) }
    var operand = renderExpressionCode(objectDef, methodDef, scope, expressionDef.expression, true, acceptsNull = true)
    if (requiresCastOperandParentheses(unwrapCasts(expressionDef.expression))) {
        // `if (a) b else c is T` checks `c` alone
        operand = addParentheses(operand)
    }
    if (unrelatedTypes(referencedType(expressionDef.expression), expressionDef.instanceType)) {
        // Kotlin rejects a check that is always false, which Java makes of the value as an Object
        operand = CodeBlock.of("(%L as %T)", operand, ANY.copy(nullable = true))
    }
    return CodeBlock.builder()
        .add(operand)
        .add(" is %T", asTypeCheckType(expressionDef.instanceType, objectDef))
        .build()
}

private fun KotlinWriteContext.renderMathBinaryOperation(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: MathBinaryOperation
): CodeBlock {
    val type = TypeHierarchy.unwrap(expressionDef.type())
    val shift = expressionDef.opType == MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT
        || expressionDef.opType == MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT
        || expressionDef.opType == MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT
    val bitwise = shift || expressionDef.opType == MathBinaryOperation.OpType.BITWISE_AND
        || expressionDef.opType == MathBinaryOperation.OpType.BITWISE_OR
        || expressionDef.opType == MathBinaryOperation.OpType.BITWISE_XOR
    // The arithmetic of a byte or a short is an Int's, which the model narrows back to its type
    val small = type == TypeDef.Primitive.BYTE || type == TypeDef.Primitive.SHORT
    var left = expressionDef.left
    var right = expressionDef.right
    if ((shift || small && bitwise) && TypeHierarchy.unwrap(right.type()) != TypeDef.Primitive.INT) {
        // Kotlin shifts by an Int distance, and a byte or a short has no bitwise operations of its own
        right = KotlinConversions.coerce(uncast(right, left.type()), TypeDef.Primitive.INT)
    }
    if (small && bitwise) {
        left = KotlinConversions.coerce(left, TypeDef.Primitive.INT)
    }
    val rendered = CodeBlock.builder()
        .add(renderMathOperand(objectDef, methodDef, scope, expressionDef, left, false))
        .add("%L", getMathOp(expressionDef.opType))
        .add(renderMathOperand(objectDef, methodDef, scope, expressionDef, right, true))
        .build()
    return if (small) {
        CodeBlock.of("(%L)%L", rendered, KotlinConversions.numberConversion(type as TypeDef.Primitive))
    } else {
        rendered
    }
}

private fun KotlinWriteContext.renderMathUnaryOperation(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: MathUnaryOperation
): CodeBlock {
    val operand = expressionDef.expression
    val operandType = TypeHierarchy.unwrap(operand.type())
    if (operandType == TypeDef.Primitive.CHAR || KotlinConversions.unboxed(operandType) == TypeDef.Primitive.CHAR) {
        // Kotlin has no negation of a Char: the negation of its code, narrowed back to the char the model types it
        return CodeBlock.of("(%L%L).toChar()", getMathOp(expressionDef.opType),
            renderExpressionWithParentheses(objectDef, methodDef, scope, KotlinConversions.coerce(operand, TypeDef.Primitive.INT)))
    }
    val rendered = CodeBlock.builder()
        .add("%L", getMathOp(expressionDef.opType))
        // `--1` is a decrement
        .add(if (isNegativeNumericConstant(unwrapCasts(operand))) addParentheses(renderExpressionCode(objectDef, methodDef, scope, operand))
            else renderExpressionWithParentheses(objectDef, methodDef, scope, operand))
        .build()
    val type = TypeHierarchy.unwrap(expressionDef.type())
    return if (type == TypeDef.Primitive.BYTE || type == TypeDef.Primitive.SHORT) {
        // The negation of a byte or a short is an Int
        CodeBlock.of("(%L)%L", rendered, KotlinConversions.numberConversion(type as TypeDef.Primitive))
    } else {
        rendered
    }
}

/** A comparison: of references where the model compares them, as Java's `==` does. */
private fun KotlinWriteContext.renderComparison(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ComparisonOperation
): CodeBlock {
    if (!expressionDef.left.type().isPrimitive
        && (expressionDef.opType == ComparisonOperation.OpType.EQUAL_TO || expressionDef.opType == ComparisonOperation.OpType.NOT_EQUAL_TO)) {
        // The bytecode compares references, as Java's `==` does: `==` of Kotlin calls equals
        return renderReferenceIdentity(objectDef, methodDef, scope, expressionDef.left, expressionDef.right,
            expressionDef.opType == ComparisonOperation.OpType.NOT_EQUAL_TO)
    }
    return CodeBlock.builder()
        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.left))
        .add("%L", getOpType(expressionDef.opType))
        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef.right))
        .build()
}

private fun KotlinWriteContext.renderNewArrayOfSize(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: NewArrayOfSize
): CodeBlock {
    val componentType = arrayElementType(expressionDef.type)
    val primitiveArray = primitiveArrayType(componentType)
    if (primitiveArray != null) {
        // A primitive array is sized rather than filled with nulls
        return CodeBlock.of("%T(%L)", asType(ClassTypeDef.of(primitiveArray), objectDef), expressionDef.size)
    }
    // An array of nulls is one of a nullable component to Kotlin, which the model does not type it as
    return CodeBlock.of(
        "(arrayOfNulls<%T>(%L) as %T)",
        asType(componentType, objectDef, methodDef),
        expressionDef.size,
        asType(expressionDef.type, objectDef, methodDef)
    )
}

private fun KotlinWriteContext.renderNewArrayInitialized(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: NewArrayInitialized
): CodeBlock {
    val componentType = arrayElementType(expressionDef.type)
    val builder: CodeBlock.Builder = CodeBlock.builder()
    if (componentType is TypeDef.Primitive) {
        builder.add("%L(", arrayOfFunction(componentType))
    } else {
        builder.add("arrayOf<%T>(", asType(componentType, objectDef, methodDef))
    }
    val iterator: Iterator<ExpressionDef> = expressionDef.expressions.iterator()
    while (iterator.hasNext()) {
        val expression = iterator.next()
        builder.add(renderAssigned(objectDef, methodDef, scope, expression, componentType))
        if (iterator.hasNext()) {
            builder.add(", ")
        }
    }
    builder.add(")")
    return builder.build()
}

private fun KotlinWriteContext.renderInvokeGetClassMethod(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: InvokeGetClassMethod
): CodeBlock {
    var instanceExp = renderExpressionCode(objectDef, methodDef, scope, expressionDef.instance)
    if (requiresMethodCallTargetParentheses(expressionDef.instance)) {
        instanceExp = addParentheses(instanceExp)
    }
    val type = TypeHierarchy.unwrap(expressionDef.instance.type())
    if (type is TypeDef.Primitive || KotlinConversions.unboxed(type) != null) {
        // The class of Kotlin's Int is the primitive's, the model's value is the box
        return CodeBlock.of("(%L as %T).javaClass", instanceExp, ANY)
    }
    return instanceExp.toBuilder().add(".javaClass").build()
}

private fun KotlinWriteContext.renderInvokeHashCodeMethod(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: InvokeHashCodeMethod
): CodeBlock {
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

/** A lambda implementing a functional interface, an expression body or the statements of a block one. */
private fun KotlinWriteContext.renderLambda(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: Lambda
): CodeBlock {
    val implementation = expressionDef.implementation
    val lambdaScope = scope.nested(implementation)
    lambdaScope.returnType = implementation.returnType
    // A renamed parameter does not take the name of a local the body declares
    implementation.statements.forEach { declaredLocals(it).forEach(lambdaScope::declare) }
    val builder = CodeBlock.builder()
        .add("%T ", asSamType(expressionDef.type, objectDef, methodDef))
        .add("{")
    val parameter: Iterator<ParameterDef> = implementation.parameters.iterator()
    val parameterless = !parameter.hasNext()
    while (parameter.hasNext()) {
        val param = parameter.next()
        val emittedName = if (scope.isTaken(param.name)) lambdaScope.allocate(param.name) else param.name
        lambdaScope.rename(param.name, emittedName)
        builder.add("%N: %T", emittedName, asType(param.type, objectDef, methodDef))
        if (parameter.hasNext()) {
            builder.add(", ")
        }
    }
    // A lambda without parameters has no arrow: `{ () -> value }` is not Kotlin
    builder.add(if (parameterless) " " else " -> ")
    val statements: List<StatementDef> = implementation.statements
    if (!isBlockBody(expressionDef)) {
        val returnStatement = statements[0] as Return
        // The result is converted to what the lambda returns, as a return statement's is
        var returned = returnStatement.expression
        if (returned != null && implementation.returnType != TypeDef.VOID && returned.type() != TypeDef.VOID) {
            returned = if (requiresImplicitReturnCast(implementation.returnType, returned.type())) {
                returned.cast(implementation.returnType)
            } else {
                KotlinConversions.coerce(returned, implementation.returnType)
            }
        }
        builder.add(
            renderExpressionCode(
                objectDef,
                implementation,
                lambdaScope,
                returned
            )
        )
    } else {
        // The statements of the body, whose returns are those of the lambda
        lambdaScope.returnLabel = ((expressionDef.type as? ClassTypeDef.Parameterized)?.rawType
            ?: expressionDef.type).simpleName.substringAfterLast('$')
        builder.add("\n").indent()
        for ((index, statement) in statements.withIndex()) {
            builder.add(renderStatementCodeBlock(objectDef, implementation, lambdaScope, statement,
                index == statements.size - 1))
            if (Completion.KOTLIN.cannotCompleteNormally(statement)) {
                break
            }
        }
        builder.unindent()
    }
    return builder.add("}").build()
}

/** A method reference, wrapped in the SAM constructor of the interface it implements. */
private fun KotlinWriteContext.renderMethodReference(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: MethodReferenceExpression
): CodeBlock {
    val instance = expressionDef.instance()
    if (instance != null && !expressionDef.isConstructor) {
        renderAdaptedReference(objectDef, methodDef, scope, expressionDef, instance)?.let { return it }
    }
    // A callable reference is not a functional interface on its own, so it is wrapped
    // in the SAM constructor of the interface being implemented
    val builder = CodeBlock.builder().add("%T(", asSamType(expressionDef.type(), objectDef, null))
    when {
        // Kotlin spells a constructor reference ::ClassName
        expressionDef.isConstructor ->
            builder.add("::%T", asType(expressionDef.owner(), objectDef))

        instance != null -> builder
            .add(renderExpressionWithParentheses(objectDef, methodDef, scope, instance, true))
            // A bound reference needs a receiver that is not null, as the bytecode's does
            .add(if (instance.type().isNullable) "!!::%N" else "::%N", expressionDef.method().name)

        else ->
            // A static method is one of the Java class, which a mapped Kotlin type does not have
            builder.add("%T::%N", asStaticOwnerName(expressionDef.owner()), expressionDef.method().name)
    }
    return builder.add(")").build()
}

/** A concatenation, of a String as the left operand. */
private fun KotlinWriteContext.renderStringConcatenation(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: StringConcatenation
): CodeBlock {
    var left: ExpressionDef = expressionDef.left()
    if ((TypeHierarchy.unwrap(left.type()) as? ClassTypeDef)?.name != TypeDef.STRING.name) {
        // Only a String has the `+` that concatenates: the left operand is converted as Java converts it - a char
        // array and `null` as an Object, not as the characters `valueOf(char[])` takes
        val leftType = TypeHierarchy.unwrap(left.type())
        if (isNullLiteral(left) || leftType is TypeDef.Array && leftType.dimensions == 1 && leftType.componentType == TypeDef.Primitive.CHAR) {
            left = left.cast(TypeDef.OBJECT)
        }
        left = TypeDef.STRING.invokeStatic("valueOf", TypeDef.STRING, left)
    }
    return CodeBlock.builder()
        .add(renderConcatenationOperand(objectDef, methodDef, scope, left, false))
        .add(" + ")
        .add(renderConcatenationOperand(objectDef, methodDef, scope, expressionDef.right()))
        .build()
}

/**
 * A check of a value against `null`. A variable is checked itself, as it can hold `null` where the model reads it as
 * not null.
 */
private fun KotlinWriteContext.renderNullCheck(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expression: ExpressionDef,
    isNull: Boolean
): CodeBlock {
    val operand = unwrapCasts(expression)
    val checked = if (operand is VariableDef.Field || operand is VariableDef.StaticField || operand is VariableDef.Local
        || operand is VariableDef.MethodParameter) {
        renderVariable(objectDef, methodDef, scope, operand as VariableDef, true)
    } else {
        val rendered = renderExpressionCode(objectDef, methodDef, scope, expression, true, acceptsNull = true)
        // `if (a) b else c == null` checks `c` alone
        if (operand is IfElse || operand is Switch) addParentheses(rendered) else rendered
    }
    return CodeBlock.builder().add(checked).add(if (isNull) " == null" else " != null").build()
}

/**
 * The structural equality of two values, `==`: of the contents of arrays, and of primitives of one type.
 */
private fun KotlinWriteContext.renderStructuralEquality(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    instance: ExpressionDef,
    other: ExpressionDef,
    negated: Boolean
): CodeBlock {
    val type = instance.type()
    // An array compared with a primitive is converted to it, as a reference is
    if (type.isArray && !TypeHierarchy.unwrap(other.type()).isPrimitive) {
        val method = if (type is TypeDef.Array && type.dimensions > 1) {
            ".contentDeepEquals("
        } else {
            ".contentEquals("
        }
        val builder = CodeBlock.builder()
        if (negated) {
            builder.add("!")
        }
        return builder
            .add(renderExpressionWithParentheses(objectDef, methodDef, scope, instance))
            .add(method)
            .add(renderExpressionCode(objectDef, methodDef, scope, other))
            .add(")")
            .build()
    }
    val (left, right) = comparedAsPrimitives(instance, other)
    if (!isNullLiteral(left) && !isNullLiteral(right) && !left.type().isPrimitive && !right.type().isPrimitive
        && unrelatedTypes(referencedType(left), referencedType(right))) {
        // `Objects.equals` of references of unrelated types, which Kotlin's `==` does not take: compared as `Any?`
        return CodeBlock.builder()
            .add(renderIdentityOperand(objectDef, methodDef, scope, left, true))
            .add(if (negated) " != " else " == ")
            .add(renderIdentityOperand(objectDef, methodDef, scope, right, true))
            .build()
    }
    return CodeBlock.builder()
        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, left, acceptsNull = true))
        .add(if (negated) " != " else " == ")
        .add(renderExpressionWithParentheses(objectDef, methodDef, scope, right, acceptsNull = true))
        .build()
}

/**
 * The referential equality of two values, Java's `==`: two primitives are compared as values of their promoted type,
 * and otherwise the references, a primitive boxed.
 */
private fun KotlinWriteContext.renderReferentialEquality(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    instance: ExpressionDef,
    other: ExpressionDef,
    negated: Boolean
): CodeBlock {
    // The model compares the operands as Objects, which boxes neither of two primitives
    val left = if (instance is Cast && instance.type == TypeDef.OBJECT) instance.expressionDef else instance
    val right = if (other is Cast && other.type == TypeDef.OBJECT) other.expressionDef else other
    val leftType = TypeHierarchy.unwrap(left.type())
    val rightType = TypeHierarchy.unwrap(right.type())
    if (leftType is TypeDef.Primitive && rightType is TypeDef.Primitive) {
        promotedType(leftType, rightType)?.let { promoted ->
            // Kotlin has no identity of primitives, nor compares a Char with an Int
            return CodeBlock.builder()
                .add(renderExpressionWithParentheses(objectDef, methodDef, scope, KotlinConversions.coerce(left, promoted)))
                .add(if (negated) " != " else " == ")
                .add(renderExpressionWithParentheses(objectDef, methodDef, scope, KotlinConversions.coerce(right, promoted)))
                .build()
        }
    }
    return renderReferenceIdentity(objectDef, methodDef, scope, instance, other, negated)
}

/**
 * The identity of two references, `===` or `!==`: a value Kotlin types as a primitive, and one of a type unrelated to
 * the other's, is compared as an `Any?`, which Kotlin does not otherwise take.
 */
private fun KotlinWriteContext.renderReferenceIdentity(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    left: ExpressionDef,
    right: ExpressionDef,
    negated: Boolean
): CodeBlock {
    val nullCompared = isNullLiteral(left) || isNullLiteral(right)
    val leftType = identityType(left)
    val rightType = identityType(right)
    val unrelated = !nullCompared && unrelatedTypes(leftType, rightType)
    return CodeBlock.builder()
        .add(renderIdentityOperand(objectDef, methodDef, scope, left, unrelated || isKotlinValueType(leftType)))
        .add(if (negated) " !== " else " === ")
        .add(renderIdentityOperand(objectDef, methodDef, scope, right, unrelated || isKotlinValueType(rightType)))
        .build()
}

/** The type of an operand compared by reference, as it is written: with a cast that can fail, and without others. */
private fun identityType(operand: ExpressionDef): TypeDef =
    if (operand is Cast && (castCanFail(operand) || isBoxedNumericCast(operand))) operand.type else referencedType(operand)

/** A cast that converts a number or a char to the box of another, which is compared as that box. */
private fun isBoxedNumericCast(cast: Cast): Boolean =
    boxedNumericConversion(cast.type, collapseNestedCasts(cast.expressionDef)) != null

/**
 * An operand compared by reference. A reference cast that can fail is kept, as Java checks it; a value Kotlin types as
 * a primitive is compared as the box the model has, as an `Any?` - identity of a primitive type is not Kotlin.
 *
 * @param asAny Whether the operand is written as an `Any?`
 */
private fun KotlinWriteContext.renderIdentityOperand(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    operand: ExpressionDef,
    asAny: Boolean
): CodeBlock {
    if (isNullLiteral(operand)) {
        return renderExpressionWithParentheses(objectDef, methodDef, scope, operand, true, true)
    }
    if (operand is Cast && isBoxedNumericCast(operand)) {
        val converted = addParentheses(renderExpressionCode(objectDef, methodDef, scope, operand))
        return if (asAny) CodeBlock.of("(%L as %T)", converted, ANY.copy(nullable = true)) else converted
    }
    if (operand is Cast && castCanFail(operand)) {
        // The null a reference holds passes the cast, as it does in Java
        val cast = CodeBlock.of("(%L as %T)", renderCastOperand(objectDef, methodDef, scope, operand.expressionDef),
            asType(operand.type, objectDef, methodDef).copy(nullable = true))
        return if (asAny) CodeBlock.of("(%L as %T)", cast, ANY.copy(nullable = true)) else cast
    }
    if (!asAny) {
        return renderExpressionWithParentheses(objectDef, methodDef, scope, operand, true, true)
    }
    // The cast that boxes a primitive is the one to Any
    val boxed = if (operand is Cast && operand.expressionDef.type().isPrimitive || operand is Cast && !operand.type.isPrimitive) {
        collapseNestedCasts(operand)
    } else {
        operand
    }
    var rendered = renderExpressionCode(objectDef, methodDef, scope, boxed, acceptsNull = true)
    if (requiresCastOperandParentheses(unwrapCasts(boxed)) || boxed is Cast) {
        rendered = addParentheses(rendered)
    }
    return CodeBlock.of("(%L as %T)", rendered, ANY.copy(nullable = true))
}

/** The operand of a cast, read as it is: a `null` it holds is the cast's. */
private fun KotlinWriteContext.renderCastOperand(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    operand: ExpressionDef
): CodeBlock {
    val rendered = renderExpressionCode(objectDef, methodDef, scope, operand, true, acceptsNull = true)
    return if (requiresCastOperandParentheses(unwrapCasts(operand))) addParentheses(rendered) else rendered
}

private fun KotlinWriteContext.renderConcatenationOperand(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    operand: ExpressionDef,
    rightOperand: Boolean = true
): CodeBlock {
    // Concatenated, `null` is the text "null"
    val rendered = renderExpressionCode(objectDef, methodDef, scope, operand, acceptsNull = true)
    val unwrapped = unwrapCasts(operand)
    if (operand !is Cast && (unwrapped is IfElse || unwrapped is Switch || unwrapped is ConditionExpressionDef
            || unwrapped is MathBinaryOperation || rightOperand && unwrapped is StringConcatenation)
        || operand is Cast && rightOperand && unwrapped is StringConcatenation) {
        return addParentheses(rendered)
    }
    return rendered
}

private fun KotlinWriteContext.renderAndConditionOperand(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
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

private fun KotlinWriteContext.renderMathOperand(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
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

private fun KotlinWriteContext.renderExpressionWithParentheses(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ExpressionDef,
    isRef: Boolean = false,
    acceptsNull: Boolean = false
): CodeBlock {
    val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef, isRef, acceptsNull)
    if (!requiresParentheses(expressionDef)) {
        return rendered
    }
    return addParentheses(rendered)
}

private fun KotlinWriteContext.renderCondition(
    objectDef: @Nullable ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    expressionDef: ExpressionDef
): CodeBlock {
    val needsParentheses = expressionDef is And
    val rendered = renderExpressionCode(objectDef, methodDef, scope, expressionDef)
    if (needsParentheses) {
        return addParentheses(rendered)
    }
    return rendered
}

internal fun KotlinWriteContext.renderVariable(
    objectDef: ObjectDef?,
    methodDef: MethodDef?,
    scope: KotlinRenderScope,
    variableDef: VariableDef,
    raw: Boolean = false
): CodeBlock {
    if (!raw && (variableDef is VariableDef.Field || variableDef is VariableDef.StaticField)) {
        // The definition declaring the field, which tells whether it holds `null`, whichever is written first
        val owner = when (variableDef) {
            is VariableDef.Field -> if (variableDef.declaringType == TypeDef.THIS) objectDef
                else generationScope.definitionOf(TypeHierarchy.unwrap(variableDef.declaringType) as? ClassTypeDef, objectDef)
            is VariableDef.StaticField -> generationScope.definitionOf(variableDef.ownerType, objectDef)
        }
        val name = if (variableDef is VariableDef.Field) variableDef.name else (variableDef as VariableDef.StaticField).name
        if (owner != null && name in nullifiedFields(owner)) {
            // A property holding `null` until it is assigned, which the model reads as not null
            return CodeBlock.of("%L!!", renderVariable(objectDef, methodDef, scope, variableDef, true))
        }
    }
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
        // A wrapper parameter is nullable, as the one of the JVM: asserted where Kotlin needs a value
        return CodeBlock.of(if (!raw && isBoxedParameter(methodDef, variableDef.name, scope)) "%N!!" else "%N", name)
    }
    if (variableDef is VariableDef.Field) {
        // Only a field declared by the type being written can be checked against its definition - which a
        // static method is rendered without
        if (objectDef != null && (variableDef.declaringType as? ClassTypeDef)?.name == objectDef.asTypeDef().name) {
            if (objectDef is ClassDef) {
                objectDef.getField(variableDef.name) // Check if exists
            } else if (objectDef is EnumDef) {
                objectDef.getField(variableDef.name) // Check if exists
            } else {
                throw IllegalStateException("Field access not supported on the object definition: $objectDef")
            }
        }
        checkNotNull(methodDef) { "Accessing field is not available" }
        val declaring = variableDef.declaringType
        val instance = if (variableDef.instance.type() != declaring && variableDef.instance !is VariableDef.This
            && variableDef.instance !is VariableDef.Super && declaring is ClassTypeDef
            && declaring != TypeDef.THIS && declaring != TypeDef.SUPER) {
            variableDef.instance.cast(declaring)
        } else {
            variableDef.instance
        }
        var codeBlock = renderExpressionCode(objectDef, methodDef, scope, instance)
        if (requiresMethodCallTargetParentheses(instance)) {
            codeBlock = addParentheses(codeBlock)
        }
        val builder = codeBlock.toBuilder()
        if (variableDef.instance.type().isNullable) {
            builder.add("!!")
        }
        builder.add(".%N", variableDef.name)
        val declaringClass = (TypeHierarchy.unwrap(declaring) as? ClassTypeDef)?.takeIf { it != TypeDef.THIS && it != TypeDef.SUPER }
        if (!raw && variableDef.instance !is VariableDef.This && variableDef.instance !is VariableDef.Super && declaringClass != null
            && variableDef.type.isPrimitive.not() && !variableDef.type.isNullable
            && generationScope.definitionOf(declaringClass, objectDef) == null && loadedClass(declaringClass, generationScope.typeLookup()) == null) {
            // A field of a type generated elsewhere, known by name only, holds null until it is assigned where no
            // constructor assigns it
            builder.add("!!")
        }
        return builder.build()
    }
    if (variableDef is VariableDef.StaticField) {
        val owner = variableDef.ownerType
        return CodeBlock.of(
            "%T.%N",
            // A static field of a Java type Kotlin maps, `Boolean.TRUE`, is one of the Java type
            if (owner is ClassTypeDef.Parameterized || owner.isInner || TypeHierarchy.enclosingOf(owner) != null) asType(owner, objectDef)
            else asStaticOwnerName(owner),
            variableDef.name
        )
    }
    if (variableDef is VariableDef.This) {
        checkNotNull(objectDef) { "Accessing 'this' is not available" }
        return CodeBlock.of("this")
    }
    if (variableDef is VariableDef.Local) {
        // A local declared nullable where the model's type is not: asserted where Kotlin needs a value
        return CodeBlock.of(if (!raw && scope.isNullableLocal(variableDef.name)) "%N!!" else "%N", variableDef.name)
    }
    if (variableDef is VariableDef.Super) {
        checkNotNull(objectDef) { "Accessing 'super' is not available" }
        // The bytecode model names Object to pick the invokespecial owner, but Any is never an
        // immediate supertype that `super<Any>` could name
        if (variableDef.type() !== TypeDef.SUPER && variableDef.type != TypeDef.OBJECT) {
            return CodeBlock.of("super<%T>", asSamType(variableDef.type, objectDef, null))
        }
        return CodeBlock.of("super");
    }
    throw IllegalStateException("Unrecognized variable: $variableDef")
}

/**
 * A reference to a generated method that override resolution narrowed, as a lambda converting its
 * arguments and result. A receiver other than `this` or a parameter, which the model never assigns, is
 * read once where the reference is created, as the reference would.
 */
private fun KotlinWriteContext.renderAdaptedReference(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    reference: MethodReferenceExpression,
    instance: ExpressionDef
): CodeBlock? {
    val adaptation = OverrideResolver.adaptReference(
        ownerOf(objectDef, instance.type()), objectDef, methodDef, reference, generationScope, true
    ) ?: return null
    val lambdaScope = scope.nested(null)
    // A parameter is read as the reference would - unless it is nullable, which is checked where the reference
    // is created
    val nullableReceiver = instance.type().isNullable
    val captured = instance !is VariableDef.This && instance !is VariableDef.Super
        && (instance !is VariableDef.MethodParameter || nullableReceiver)
    val receiver = if (captured) lambdaScope.allocate("target").also { lambdaScope.declare(it) } else null
    val names = adaptation.argumentTypes().indices.map { index ->
        generateSequence(0) { it + 1 }.map { "arg$index" + if (it == 0) "" else "_$it" }
            .first { !lambdaScope.isTaken(it) }
            .also { lambdaScope.declare(it) }
    }
    val arguments = adaptation.argumentTypes().mapIndexed { index, type ->
        if (type == null) {
            CodeBlock.of("%N", names[index])
        } else {
            // A parameter the model declares nullable is written nullable, as the method is, and takes `null`
            val nullable = reference.method().parameters.getOrNull(index)?.type?.isNullable == true
            CodeBlock.of("%N as %T", names[index], asType(if (nullable) type.makeNullable() else type, objectDef, methodDef))
        }
    }
    var call = CodeBlock.of(
        "%L.%N(%L)",
        if (receiver != null) CodeBlock.of("%N", receiver) else renderExpressionCode(objectDef, methodDef, scope, instance),
        reference.method().name,
        arguments.joinToCode(", ")
    )
    adaptation.resultType()?.let { call = CodeBlock.of("(%L as %T)", call, asType(it, objectDef, methodDef)) }
    val lambda = CodeBlock.of(
        "%T { %L -> %L }", asSamType(reference.type(), objectDef, null), names.joinToString(", "), call
    )
    if (receiver == null) {
        return lambda
    }
    return CodeBlock.of(
        if (nullableReceiver) "%L!!.let { %N -> %L }" else "%L.let { %N -> %L }",
        renderExpressionWithParentheses(objectDef, methodDef, scope, instance, true),
        receiver,
        lambda
    )
}
