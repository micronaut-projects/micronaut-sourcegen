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
import javax.lang.model.element.Modifier

/*
 * The Kotlin modifiers of the Java ones, and the annotations the JVM ones are spelled with.
 */

/**
 * The modifiers of a member of a nested type that the rest of the file uses, which Kotlin does not let it do
 * where the member is private.
 */
internal fun KotlinWriteContext.exposedToNest(objectDef: ObjectDef?, member: String, modifiers: Set<Modifier>): Set<Modifier> =
    if (objectDef != null && Modifier.PRIVATE in modifiers && nestAccess.isUsedOutside(objectDef, member)) {
        modifiers - Modifier.PRIVATE
    } else {
        modifiers
    }

internal fun stripStatic(modifiers: MutableSet<Modifier>): MutableSet<Modifier> {
    val mutable = HashSet(modifiers)
    mutable.remove(Modifier.STATIC)
    return mutable
}

internal fun extendModifiers(modifiers: MutableSet<Modifier>, modifier: Modifier): Set<Modifier> {
    if (modifiers.contains(modifier)) {
        return modifiers
    }
    val mutable = HashSet(modifiers)
    mutable.add(modifier)
    return mutable
}

internal fun asKModifiers(methodDef: MethodDef, modifier: Collection<Modifier>): List<KModifier> {
    val modifiers = asKModifiers(modifier).toMutableList()
    // An override and an abstract method keep the protected visibility a subclass declares them with
    if (modifier.contains(Modifier.PROTECTED) && (methodDef.isOverride || modifier.contains(Modifier.ABSTRACT))) {
        modifiers.remove(KModifier.PUBLIC)
        modifiers.add(KModifier.PROTECTED)
    }
    if (methodDef.isOverride) {
        modifiers.add(KModifier.OVERRIDE)
    }
    return modifiers
}

internal fun asKModifiers(modifier: Collection<Modifier>): List<KModifier> {
    return modifier.stream().map { m: Modifier ->
        when (m) {
            Modifier.PUBLIC -> KModifier.PUBLIC
            // A protected member of Java is visible in its package too, which Kotlin's protected is not: a
            // caller of the package is only compiled against a public one
            Modifier.PROTECTED -> KModifier.PUBLIC
            Modifier.PRIVATE -> KModifier.PRIVATE
            Modifier.ABSTRACT -> KModifier.ABSTRACT
            Modifier.SEALED -> KModifier.SEALED
            Modifier.FINAL -> KModifier.FINAL
            Modifier.NATIVE -> KModifier.EXTERNAL
            // A method of an interface with a body is a default one
            Modifier.DEFAULT -> null
            // Kotlin spells these as annotations, see modifierAnnotations
            Modifier.VOLATILE, Modifier.TRANSIENT, Modifier.SYNCHRONIZED, Modifier.STRICTFP -> null
            else -> throw IllegalStateException("Not supported modifier: $m")
        }
    }.toList().filterNotNull().distinct()
}

/**
 * The Java modifiers Kotlin spells as annotations of the JVM: `@Volatile`, `@Transient`, `@Synchronized` and
 * `@Strictfp`.
 */
internal fun modifierAnnotations(modifiers: Collection<Modifier>): List<AnnotationSpec> = modifiers.mapNotNull { modifier ->
    when (modifier) {
        Modifier.VOLATILE -> ClassName("kotlin.jvm", "Volatile")
        Modifier.TRANSIENT -> ClassName("kotlin.jvm", "Transient")
        Modifier.SYNCHRONIZED -> ClassName("kotlin.jvm", "Synchronized")
        Modifier.STRICTFP -> ClassName("kotlin.jvm", "Strictfp")
        else -> null
    }?.let { AnnotationSpec.builder(it).build() }
}
