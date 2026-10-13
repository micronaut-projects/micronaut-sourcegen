/*
 * Copyright 2017-2026 original authors
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

import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.TypeHierarchy

/**
 * The conversions the bytecode writer applies to a value written to a parameter, a return, a local or a field, which
 * Kotlin spells out: it has no implicit widening of a primitive, no primitive casts and no covariant arrays.
 */
internal object KotlinConversions {

    private val BYTE = TypeDef.Primitive.BYTE
    private val SHORT = TypeDef.Primitive.SHORT
    private val CHAR = TypeDef.Primitive.CHAR
    private val INT = TypeDef.Primitive.INT
    private val LONG = TypeDef.Primitive.LONG
    private val FLOAT = TypeDef.Primitive.FLOAT
    private val DOUBLE = TypeDef.Primitive.DOUBLE
    private val BOOLEAN = TypeDef.Primitive.BOOLEAN

    private val WRAPPERS: Map<String, TypeDef.Primitive> = listOf(BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, BOOLEAN)
        .associateBy { it.wrapperType().name }

    /**
     * @param type A type
     * @return The primitive the type boxes, or `null` where it is no wrapper
     */
    fun unboxed(type: TypeDef): TypeDef.Primitive? = (TypeHierarchy.unwrap(type) as? ClassTypeDef)?.let { WRAPPERS[it.name] }

    /**
     * The member call converting a primitive value to another primitive type, as the JVM converts it.
     *
     * @param target The type converted to
     * @param source The type of the value
     * @return The call to append, or `null` where the value needs none
     */
    fun conversion(target: TypeDef.Primitive, source: TypeDef.Primitive): String? {
        if (target == source || target == BOOLEAN || source == BOOLEAN || target == TypeDef.VOID || source == TypeDef.VOID) {
            return null
        }
        if (source == CHAR) {
            // A Char has no numeric conversions of its own, its code is an Int
            return if (target == INT) ".code" else ".code" + numberConversion(target)
        }
        if (target == CHAR) {
            // Only Int declares toChar, the others are deprecated
            return if (source == INT) ".toChar()" else ".toInt().toChar()"
        }
        if ((source == DOUBLE || source == FLOAT) && (target == BYTE || target == SHORT)) {
            // Kotlin deprecates narrowing a floating point number straight to a byte or a short, the JVM goes through
            // an int as well
            return ".toInt()" + numberConversion(target)
        }
        return numberConversion(target)
    }

    /**
     * @param target A numeric type
     * @return The member call converting a number to it
     */
    fun numberConversion(target: TypeDef.Primitive): String = when (target) {
        BYTE -> ".toByte()"
        SHORT -> ".toShort()"
        INT -> ".toInt()"
        LONG -> ".toLong()"
        FLOAT -> ".toFloat()"
        DOUBLE -> ".toDouble()"
        else -> error("Not a numeric type: $target")
    }

    /**
     * A constant written as one of another primitive type, with the value the JVM converts it to: `16` passed as a
     * `double` is `16.0`.
     *
     * @param constant The constant
     * @param target   The primitive type
     * @return The constant of the type, or `null` where it is no numeric or character constant
     */
    fun convertedConstant(constant: ExpressionDef.Constant, target: TypeDef.Primitive): ExpressionDef.Constant? {
        val number: Number = when (val value = constant.value) {
            is Char -> value.code
            is Number -> value
            else -> return null
        }
        val floating = number is Double || number is Float
        val converted: Any = when (target) {
            BYTE -> if (floating) number.toInt().toByte() else number.toLong().toByte()
            SHORT -> if (floating) number.toInt().toShort() else number.toLong().toShort()
            CHAR -> (if (floating) number.toInt() else number.toLong().toInt()).toChar()
            INT -> if (floating) number.toDouble().toInt() else number.toLong().toInt()
            LONG -> if (floating) number.toDouble().toLong() else number.toLong()
            FLOAT -> number.toFloat()
            DOUBLE -> number.toDouble()
            else -> return null
        }
        return ExpressionDef.Constant(target, converted)
    }

    /**
     * A value converted to the type it is written to, as the bytecode writer converts it: a primitive to the primitive
     * written, a numeric constant written as one of that type, and an array of references cast to the array type
     * written, which Kotlin's arrays - invariant - are not otherwise.
     *
     * @param value  The value
     * @param target The type of the parameter, the return, the local or the field
     * @return The value to write
     */
    fun coerce(value: ExpressionDef, target: TypeDef): ExpressionDef {
        if (value is ExpressionDef.Constant && value.value == null) {
            return value
        }
        val targetType = TypeHierarchy.unwrap(target)
        val valueType = TypeHierarchy.unwrap(value.type())
        if (targetType is TypeDef.Primitive) {
            if (targetType == valueType || targetType == TypeDef.VOID || targetType == BOOLEAN) {
                return value
            }
            val source = valueType as? TypeDef.Primitive ?: unboxed(valueType) ?: return value
            if (source == BOOLEAN || source == TypeDef.VOID || source == targetType && value !is ExpressionDef.Constant) {
                // Kotlin types the wrapper of the primitive as the primitive
                return value
            }
            if (value is ExpressionDef.Constant) {
                convertedConstant(value, targetType)?.let { return it }
            }
            return value.cast(targetType)
        }
        if (targetType is TypeDef.Array && valueType is TypeDef.Array && targetType != valueType
            && targetType.componentType !is TypeDef.Primitive && valueType.componentType !is TypeDef.Primitive
            && TypeHierarchy.unwrap(targetType.componentType) !is TypeDef.TypeVariable
            && TypeHierarchy.unwrap(valueType.componentType) !is TypeDef.TypeVariable) {
            return value.cast(target)
        }
        return value
    }
}
