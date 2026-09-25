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
import java.util.function.Consumer
import javax.lang.model.element.Modifier

/*
 * The functions of a definition, and the body of each.
 */

internal fun KotlinWriteContext.buildFunction(objectDef: ObjectDef?, declaredMethod: MethodDef, declaredModifiers: Set<Modifier>): FunSpec {
    val modifiers = exposedToNest(objectDef, if (declaredMethod.isConstructor) "<init>" else declaredMethod.name, declaredModifiers)
    // A model written for the bytecode writer overrides a generic method with its erased signature, which the
    // verifier accepts; Kotlin only overrides with the exact signature with the type arguments of the supertype.
    // The body is rendered against the resolved signature, so a returned value is cast to its type
    val method = (OverrideResolver.resolve(objectDef, declaredMethod, generationScope, true)
        ?.apply(declaredMethod) ?: declaredMethod).let { keepingNullability(it, declaredMethod, nullableArguments(objectDef)) }
    var funBuilder = if (method.isConstructor) {
        FunSpec.constructorBuilder()
    } else {
        // A Java method Kotlin maps to a member of another name is overridden as that one: `get` for `charAt`
        val name = (if (method.isOverride && objectDef != null) KotlinJavaMappings.overrideName(KotlinJavaMappings.compiledSupertypes(generationScope, objectDef), method) else null)
            ?: method.name
        FunSpec.builder(name).returns(asType(method.returnType, objectDef, method).let { type ->
            // The iterator of a Java `Iterable` is the mutable one, which the read-only type does not override
            val raw = ((method.returnType as? ClassTypeDef.Parameterized)?.rawType ?: method.returnType as? ClassTypeDef)?.name
            if (method.isOverride && (raw == "java.util.Iterator" || raw == "java.util.ListIterator")) {
                val mutable = ClassName("kotlin.collections", KotlinJavaMappings.mutableCollectionOf(raw))
                ((type as? ParameterizedTypeName)?.let { mutable.parameterizedBy(it.typeArguments) }
                    ?: mutable.parameterizedBy(STAR)).copy(nullable = type.isNullable)
            } else if (returnsNullable(objectDef, method)) {
                // A result that can be null, as the JVM's is, where the model does not say so
                type.copy(nullable = true)
            } else {
                type
            }
        })
    }
    // `equals` of `Any` takes a nullable value, which the erased `Object` of the model does not say
    val overridesEquals = method.name == "equals" && method.isOverride && method.parameters.size == 1
        && method.parameters[0].type == TypeDef.OBJECT
    funBuilder = funBuilder
        .addModifiers(asKModifiers(method, modifiers))
        .addTypeVariables(method.typeVariables.map { asTypeVariable(it, objectDef, method) })
        .addParameters(
            method.parameters.mapIndexed { index, param ->
                val array = param.type as? TypeDef.Array
                if (array != null && index == method.parameters.size - 1 && overridesVarargs(objectDef, method)) {
                    // `vararg parts: String` is the array the body reads
                    ParameterSpec.builder(param.name, asType(if (array.dimensions == 1) array.componentType
                        else TypeDef.array(array.componentType, array.dimensions - 1), objectDef, method), KModifier.VARARG).build()
                } else {
                    // A wrapper is nullable, as the JVM type Java takes, where the function declares its own signature
                    val nullable = overridesEquals || declaresOwnSignature(method) && isBoxed(param.type)
                    ParameterSpec.builder(
                        param.name,
                        asType(if (nullable) param.type.makeNullable() else param.type, objectDef, method)
                    ).build()
                }
            }
        )
    if (method.isOverride) {
        funBuilder.modifiers += KModifier.OVERRIDE
    }
    if (isOverridable(objectDef, method, modifiers)) {
        // A method Java lets a subclass override, which Kotlin only does where it is open
        funBuilder.modifiers += KModifier.OPEN
    }
    modifierAnnotations(modifiers).forEach(funBuilder::addAnnotation)
    for (annotation in method.annotations) {
        funBuilder.addAnnotation(
            asAnnotationSpec(annotation)
        )
    }
    if (method.throwTypes.isNotEmpty()) {
        funBuilder.addAnnotation(
            AnnotationSpec.builder(Throws::class)
                .addMember(
                    method.throwTypes.joinToString { "%T::class" },
                    *method.throwTypes.map { asType(it, objectDef, method) }.toTypedArray()
                )
                .build(),
        )
    }
    val scope = KotlinRenderScope.root(method)
    val renderingObjectDef = if (method.modifiers.contains(Modifier.STATIC)) null else objectDef
    inFunction(method) {
        renderBody(funBuilder, objectDef, renderingObjectDef, method, scope)
    }
    method.javadoc.forEach(Consumer { format: String -> funBuilder.addKdoc(format) })
    return funBuilder.build()
}

/** Whether Java lets a subclass override the method, which Kotlin needs to be told with `open`. */
private fun isOverridable(objectDef: ObjectDef?, method: MethodDef, modifiers: Set<Modifier>): Boolean =
    objectDef is ClassDef && isExtendable(objectDef) && !method.isConstructor && !method.isOverride
        && !method.modifiers.contains(Modifier.STATIC) && Modifier.PRIVATE !in modifiers
        && Modifier.FINAL !in modifiers && Modifier.ABSTRACT !in modifiers

private fun KotlinWriteContext.renderBody(funBuilder: FunSpec.Builder, objectDef: ObjectDef?, renderingObjectDef: ObjectDef?, method: MethodDef, scope: KotlinRenderScope) {
    // A constructor delegates in its header, `constructor() : this("d")`, not by a statement of its body
    val delegation = if (method.isConstructor) method.statements.firstOrNull() else null
    var delegated = false
    if (delegation is InvokeSuperConstructor) {
        funBuilder.callSuperConstructor(renderArguments(objectDef, method, scope, (objectDef as? ClassDef)?.superclass,
            MethodDef.CONSTRUCTOR, delegation.method, delegation.method.parameters.map { it.type }, delegation.values))
        delegated = true
    } else if (delegation is InvokeInstanceMethod && delegation.method.isConstructor
        && (delegation.instance is VariableDef.Super || delegation.instance is VariableDef.This)) {
        val arguments = renderArguments(objectDef, method, scope,
            if (delegation.instance is VariableDef.Super) (objectDef as? ClassDef)?.superclass else objectDef?.asTypeDef(),
            MethodDef.CONSTRUCTOR, delegation.method, delegation.method.parameters.map { it.type }, delegation.values)
        if (delegation.instance is VariableDef.Super) funBuilder.callSuperConstructor(arguments) else funBuilder.callThisConstructor(arguments)
        delegated = true
    }
    for ((index, statement) in method.statements.withIndex()) {
        if (delegated && index == 0) {
            continue
        }
        funBuilder.addCode(renderStatementCodeBlock(renderingObjectDef, method, scope, statement,
            index == method.statements.size - 1))
        if (Completion.KOTLIN.cannotCompleteNormally(statement)) {
            break
        }
    }
}

/** Whether the method overrides one that takes varargs, which an array parameter does not override in Kotlin. */
internal fun overridesVarargs(objectDef: ObjectDef?, method: MethodDef): Boolean {
    if (objectDef == null || !method.isOverride || method.parameters.lastOrNull()?.type !is TypeDef.Array) {
        return false
    }
    // The overridden method, not another overload of the name: each parameter has the erasure of the model's, or
    // is generic, which the resolution substitutes
    return TypeHierarchy.superTypesOf(objectDef).mapNotNull { loadedClass(it) }.any { type ->
        type.methods.any { candidate ->
            candidate.name == method.name && candidate.parameterCount == method.parameters.size && candidate.isVarArgs
                && candidate.genericParameterTypes.indices.all { i ->
                    val generic = candidate.genericParameterTypes[i]
                    generic is java.lang.reflect.TypeVariable<*> || generic is java.lang.reflect.GenericArrayType
                        || candidate.parameterTypes[i].typeName == TypeHierarchy.erasedName(method.parameters[i].type, objectDef)
                }
        }
    }
}
