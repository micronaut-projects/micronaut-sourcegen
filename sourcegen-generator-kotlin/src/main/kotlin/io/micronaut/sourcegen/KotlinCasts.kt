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

import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*

/*
 * The casts of the model: which the source writes, and which a numeric conversion is.
 */

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

internal fun isByteOrShort(type: TypeDef): Boolean {
    val primitive = TypeHierarchy.unwrap(type) as? TypeDef.Primitive ?: KotlinConversions.unboxed(type)
    return primitive == TypeDef.Primitive.BYTE || primitive == TypeDef.Primitive.SHORT
}

/** The value of a cast to the type, which the model adds to an operand: the right one of a math operation. */
internal fun uncast(expression: ExpressionDef, type: TypeDef): ExpressionDef =
    if (expression is Cast && expression.type == type) expression.expressionDef else expression

/**
 * The operands of an equality, the one converted to the primitive type of the other as the bytecode compares
 * them: Kotlin does not compare an Int with a Long, or a Char with an Int.
 */
internal fun comparedAsPrimitives(left: ExpressionDef, right: ExpressionDef): Pair<ExpressionDef, ExpressionDef> {
    val leftType = TypeHierarchy.unwrap(left.type())
    val rightType = TypeHierarchy.unwrap(right.type())
    val leftPrimitive = leftType as? TypeDef.Primitive ?: KotlinConversions.unboxed(leftType)
    val rightPrimitive = rightType as? TypeDef.Primitive ?: KotlinConversions.unboxed(rightType)
    if (leftType is TypeDef.Primitive && rightPrimitive == null && !isNullLiteral(right)) {
        // A primitive and a reference that boxes none, a Number or an Object: the reference is cast to the primitive
        return left to right.cast(leftType)
    }
    if (rightType is TypeDef.Primitive && leftPrimitive == null && !isNullLiteral(left)) {
        return left.cast(rightType) to right
    }
    if (leftPrimitive == null || rightPrimitive == null || leftPrimitive == rightPrimitive
        || leftType !is TypeDef.Primitive && rightType !is TypeDef.Primitive) {
        return left to right
    }
    return if (leftType is TypeDef.Primitive) {
        left to KotlinConversions.coerce(right, leftType)
    } else {
        KotlinConversions.coerce(left, rightType) to right
    }
}

/**
 * The promoted type Java compares two primitives as: an int where both are narrower, and otherwise the wider one.
 *
 * @return The type, or `null` where one is a boolean and the other is not
 */
internal fun promotedType(left: TypeDef.Primitive, right: TypeDef.Primitive): TypeDef.Primitive? {
    val bool = TypeDef.Primitive.BOOLEAN
    if (left == bool || right == bool) {
        return if (left == right) bool else null
    }
    for (wide in listOf(TypeDef.Primitive.DOUBLE, TypeDef.Primitive.FLOAT, TypeDef.Primitive.LONG)) {
        if (left == wide || right == wide) {
            return wide
        }
    }
    return TypeDef.Primitive.INT
}

/** Whether Kotlin types a value of the type as one of its value types, a primitive or the box of one. */
internal fun isKotlinValueType(type: TypeDef): Boolean {
    val unwrapped = TypeHierarchy.unwrap(type)
    return unwrapped is TypeDef.Primitive && unwrapped != TypeDef.VOID || KotlinConversions.unboxed(unwrapped) != null
}

/**
 * The class of a type, where it can be loaded: the box of a primitive, which is what Kotlin checks a value of its
 * against another type.
 */
internal fun javaClassOf(type: TypeDef): Class<*>? = when (val unwrapped = TypeHierarchy.unwrap(type)) {
    is TypeDef.Primitive -> if (unwrapped == TypeDef.VOID) null else loadedClass(unwrapped.wrapperType())
    is TypeDef.Array -> {
        val component = TypeHierarchy.unwrap(unwrapped.componentType)
        var arrayClass = if (component is TypeDef.Primitive) primitiveClassOf(component) else javaClassOf(component)
        repeat(unwrapped.dimensions) { arrayClass = arrayClass?.arrayType() }
        arrayClass
    }
    else -> loadedClass(unwrapped)
}

internal fun primitiveClassOf(type: TypeDef.Primitive): Class<*>? =
    (loadedClass(type.wrapperType())?.getField("TYPE")?.get(null) as? Class<*>)

/**
 * Whether no value is of both types, which Kotlin rejects comparing or checking one against the other: only known
 * for the types that can be loaded.
 */
internal fun unrelatedTypes(first: TypeDef, second: TypeDef): Boolean {
    val firstClass = javaClassOf(first) ?: return false
    val secondClass = javaClassOf(second) ?: return false
    return !firstClass.isAssignableFrom(secondClass) && !secondClass.isAssignableFrom(firstClass)
}

/** Whether a reference cast can fail: to a type the value's is not known to be a subtype of. */
internal fun castCanFail(cast: Cast): Boolean {
    val target = TypeHierarchy.unwrap(cast.type)
    val source = TypeHierarchy.unwrap(cast.expressionDef.type())
    if (target is TypeDef.Primitive || isNullLiteral(cast.expressionDef)
        || boxedNumericConversion(target, collapseNestedCasts(cast.expressionDef)) != null) {
        return false
    }
    // A primitive is checked as its box: an int cast to a String throws
    val targetClass = javaClassOf(target) ?: return false
    val sourceClass = javaClassOf(source) ?: return false
    return !targetClass.isAssignableFrom(sourceClass)
}

/** The type of the value written for an operand read as it is, whose casts are left out. */
internal fun referencedType(operand: ExpressionDef): TypeDef =
    if (operand is Cast) collapseNestedCasts(operand.expressionDef).type() else operand.type()

internal fun unwrapCasts(expressionDef: ExpressionDef): ExpressionDef {
    var expression = expressionDef
    while (expression is Cast) {
        expression = expression.expressionDef()
    }
    return expression
}

internal fun collapseNestedCasts(expressionDef: ExpressionDef): ExpressionDef {
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
internal fun primitiveConversion(castType: TypeDef, sourceType: TypeDef): String? {
    if (castType !is TypeDef.Primitive || !isNumberLike(sourceType)) {
        return null
    }
    val source = sourceType as? TypeDef.Primitive ?: KotlinConversions.unboxed(sourceType)
    if (source == null || source == castType && sourceType !is TypeDef.Primitive) {
        // A Number, or the wrapper of the primitive, which its conversion unboxes
        return when (castType.name()) {
            "boolean" -> null
            // A Number is checked as a Character, as the bytecode unboxes a char
            "char" -> null
            else -> numberConversion(castType.name())
        }
    }
    return KotlinConversions.conversion(castType, source)
}

/**
 * The conversion of a primitive, or the box of a number or a char, to the box of another number or char: the value
 * unboxed with its own wrapper and converted as a primitive, which the target box then holds.
 *
 * @return The primitive the value is read as and the conversion to append, or null where the cast is no such one
 */
internal fun boxedNumericConversion(castType: TypeDef, value: ExpressionDef): Pair<TypeDef.Primitive, String>? {
    if (isNullLiteral(value)) {
        return null
    }
    val target = KotlinConversions.unboxed(castType) ?: return null
    val sourceType = TypeHierarchy.unwrap(value.type())
    val source = sourceType as? TypeDef.Primitive ?: KotlinConversions.unboxed(sourceType) ?: return null
    val conversion = KotlinConversions.conversion(target, source) ?: return null
    return source to conversion
}

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

internal fun isNullLiteral(value: ExpressionDef): Boolean {
    val operand = unwrapCasts(value)
    return operand is Constant && operand.value == null
}

internal fun requiresImplicitCast(targetType: TypeDef, valueType: TypeDef): Boolean =
    valueType == TypeDef.OBJECT && targetType != TypeDef.OBJECT
        && (targetType is ClassTypeDef || targetType is TypeDef.Primitive && targetType != TypeDef.VOID)

/**
 * Whether a returned value needs a cast to the return type. Unlike an argument - where an array parameter
 * may be a vararg, which takes the value as an element - an array return type is cast to as well, as is a
 * type variable an override is resolved to.
 */
internal fun requiresImplicitReturnCast(returnType: TypeDef, valueType: TypeDef): Boolean {
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
        return valueType != returnType
            && (valueType is ClassTypeDef || valueType is TypeDef.Array || valueType is TypeDef.TypeVariable
            || valueType is TypeDef.Primitive && valueType != TypeDef.VOID)
    }
    return returnType is ClassTypeDef.JavaClass && valueType is ClassTypeDef.JavaClass
        && !returnType.type.isAssignableFrom(valueType.type)
}
