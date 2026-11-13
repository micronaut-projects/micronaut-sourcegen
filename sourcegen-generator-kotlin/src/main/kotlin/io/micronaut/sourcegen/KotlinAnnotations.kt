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
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.StatementDef.*
import java.lang.reflect.Array
import java.util.function.Consumer
import kotlin.reflect.KClass

/*
 * The annotations of the model, and the values of their members.
 */

internal fun KotlinWriteContext.asAnnotationSpec(annotationDef: AnnotationDef): AnnotationSpec {
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

private fun KotlinWriteContext.addAnnotationValue(
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
        builder.addMember("$memberName = %L", renderVariable(null, null, KotlinRenderScope.root(null), value))
    }

    is AnnotationDef -> {
        val spec = asAnnotationSpec(value)
        builder.addMember("$memberName = %L", spec.toString().substring(1))
    }

    is Collection<*> -> {
        // Each element is written as a member of an annotation of its own, whose value the array takes: the
        // members of this one stay as they are, whatever their values name
        val elements = AnnotationSpec.builder(ClassName("kotlin", "Suppress"))
        value.forEach(Consumer { v: Any? -> addAnnotationValue(elements, memberName, v!!) })
        val listStr: String = elements.members.map { it.toString().substringAfter("= ") }.joinToString(separator = ",\n")
        builder.addMember("$memberName = [%L]", listStr)
    }

    else -> {
        builder.addMember("$memberName = %L", value)
    }
}
