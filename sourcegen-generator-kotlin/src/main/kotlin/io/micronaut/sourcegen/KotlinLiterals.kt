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
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import java.lang.reflect.Array

/*
 * The literals of the constants of the model.
 */

private val FLOAT = ClassName("kotlin", "Float")

private val DOUBLE = ClassName("kotlin", "Double")

internal fun KotlinWriteContext.renderConstantExpression(
    constant: Constant,
    methodDef: MethodDef,
    scope: KotlinRenderScope
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
            return renderArrayConstant(type, value, methodDef, scope)
        }
    } else if (type is ClassTypeDef) {
        if (value is TypeDef) {
            // A class literal names the class, without type arguments
            val literalType = TypeHierarchy.unwrap(value).let { (it as? ClassTypeDef.Parameterized)?.rawType ?: it }
            return CodeBlock.of("%T::class.java",
                if (literalType is ClassTypeDef && literalType !is ClassTypeDef.Parameterized && TypeHierarchy.enclosingOf(literalType) == null) asClassName(literalType)
                else asType(value, null))
        }
        val name = type.name
        return if (ClassUtils.isJavaLangType(name)) {
            when (name) {
                "java.lang.Byte" -> renderPrimitiveConstant("byte", value)
                "java.lang.Short" -> renderPrimitiveConstant("short", value)
                "java.lang.Character" -> renderPrimitiveConstant("char", value)
                "java.lang.Integer" -> renderPrimitiveConstant("int", value)
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

private fun KotlinWriteContext.renderArrayConstant(
    type: TypeDef.Array,
    value: Any,
    methodDef: MethodDef,
    scope: KotlinRenderScope
): CodeBlock {
    val builder = CodeBlock.builder()
    val length = Array.getLength(value)
    val componentType = type.componentType
    for (i in 0 until length) {
        val element = Array.get(value, i)
        builder.add(
            if (isByteOrShort(componentType) && element is Number) {
                // The factory takes the literal as the byte or the short it is an element of
                CodeBlock.of("%L", element)
            } else {
                renderConstantExpression(Constant(componentType, element), methodDef, scope)
            }
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

private fun renderPrimitiveConstant(name: String, value: Any): CodeBlock {
    val number = value as? Number
    return when (name) {
        // The literal of the smallest value is out of range: it is the negation of a literal one too large
        "long" -> if (number?.toLong() == Long.MIN_VALUE) CodeBlock.of("%T.MIN_VALUE", ClassName("kotlin", "Long"))
            // Kotlin only accepts an upper case long suffix
            else CodeBlock.of("%LL", value)
        "int" -> if (number != null && number.toLong() == Int.MIN_VALUE.toLong()) CodeBlock.of("%T.MIN_VALUE", ClassName("kotlin", "Int"))
            else CodeBlock.of("%L", value)
        // An integer literal is an Int, where no byte or short is expected: boxed, compared or a key
        "byte", "short" -> CodeBlock.of(if (value.toString().startsWith("-")) "(%L).%L()" else "%L.%L()",
            value, if (name == "byte") "toByte" else "toShort")
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

// Copy from com.squareup.javapoet.Util
internal fun characterLiteralWithoutSingleQuotes(c: Char): String {
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
        // A surrogate is escaped: unpaired, it has no encoding a source file can hold
        else -> if (Character.isISOControl(c) || Character.isSurrogate(c)) String.format("\\u%04x", c.code) else c.toString()
    }
}
