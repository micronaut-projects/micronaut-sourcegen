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

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.WildcardTypeName
import io.micronaut.sourcegen.generator.GenerationScope
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ExpressionDef.*
import io.micronaut.sourcegen.model.ExpressionDef.IfElse
import io.micronaut.sourcegen.model.ExpressionDef.Switch
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef.*
import io.micronaut.sourcegen.model.TypeDef

/**
 * The members of the Java types that Kotlin maps onto its own, which it names differently or hides: `charAt` is
 * `get`, `intValue()` is `toInt()`, `getClass()` is `javaClass`, and a method of `java.lang.String`, `java.lang.Object`
 * or `java.util.Collection` the Kotlin type does not declare is called on the receiver cast to the Java type. The
 * tables here are the one place these mappings are listed: the renamed members, the methods Kotlin sees as
 * properties, the mutable collections a mutator is called on and the classes Kotlin maps onto its own.
 */
internal object KotlinJavaMappings {

    /** How Kotlin writes the call of a Java method. */
    sealed interface MappedCall {

        /** A member function of another name, taking the same arguments: `get` for `charAt`. */
        data class Renamed(val name: String) : MappedCall

        /** A conversion of the receiver, which takes no arguments: `.toInt()` for `intValue()`. */
        data class Conversion(val call: String) : MappedCall

        /** The receiver itself: `booleanValue()` of a Boolean, which Kotlin types as its primitive. */
        data object Identity : MappedCall

        /**
         * The method of the Java type, which the Kotlin type hides, called on the receiver cast to the Java type.
         *
         * @property type The Java type, fully qualified: imported, it would clash with the Kotlin one of its name
         */
        data class ThroughJavaType(val type: String) : MappedCall

        /**
         * An extension property: `javaClass` for `getClass()`.
         *
         * @property boxed Whether the receiver is boxed first, as Kotlin types a wrapper as the primitive
         */
        data class Property(val name: String, val boxed: Boolean) : MappedCall
    }

    private const val JAVA_STRING = "java.lang.String"
    private const val JAVA_OBJECT = "java.lang.Object"
    private const val JAVA_COLLECTION = "java.util.Collection<*>"

    // The members kotlin.String declares itself, or maps: everything else is called on java.lang.String
    private val KOTLIN_STRING_MEMBERS = setOf("length", "charAt", "subSequence", "compareTo", "equals", "hashCode", "toString")

    private val NUMBER_CONVERSIONS = mapOf(
        "byteValue" to TypeDef.Primitive.BYTE,
        "shortValue" to TypeDef.Primitive.SHORT,
        "intValue" to TypeDef.Primitive.INT,
        "longValue" to TypeDef.Primitive.LONG,
        "floatValue" to TypeDef.Primitive.FLOAT,
        "doubleValue" to TypeDef.Primitive.DOUBLE
    )

    /**
     * A Java method Kotlin names otherwise on the type it maps the declaring one to.
     *
     * @property owner        The Java type declaring the method
     * @property method       The name of the method
     * @property parameters   The types of its parameters, `null` for any
     * @property name         The name of the Kotlin member function
     * @property receiverOnly Whether only a call on a receiver of a compiled class is renamed, not an override
     */
    private class RenamedMember(
        val owner: Class<*>,
        val method: String,
        val parameters: List<TypeDef?>,
        val name: String,
        val receiverOnly: Boolean
    )

    private val RENAMED_MEMBERS = listOf(
        RenamedMember(CharSequence::class.java, "charAt", listOf(null), "get", false),
        // The `remove` of a Kotlin list takes the element
        RenamedMember(List::class.java, "remove", listOf(TypeDef.Primitive.INT), "removeAt", true)
    )

    /**
     * A Java method without parameters Kotlin sees as a property of the type it maps the declaring one to.
     *
     * @property owner    The Java type declaring the method
     * @property method   The name of the method
     * @property property The name of the property
     * @property nullable Whether the property is nullable, as the Java method's result can be null
     */
    private class MappedProperty(val owner: Class<*>, val method: String, val property: String, val nullable: Boolean = false)

    private val MAPPED_PROPERTIES = listOf(
        MappedProperty(CharSequence::class.java, "length", "length"),
        MappedProperty(Collection::class.java, "size", "size"),
        MappedProperty(Map::class.java, "size", "size"),
        MappedProperty(Map::class.java, "keySet", "keys"),
        MappedProperty(Map::class.java, "values", "values"),
        MappedProperty(Map::class.java, "entrySet", "entries"),
        MappedProperty(Map.Entry::class.java, "getKey", "key"),
        MappedProperty(Map.Entry::class.java, "getValue", "value"),
        MappedProperty(Enum::class.java, "name", "name"),
        MappedProperty(Enum::class.java, "ordinal", "ordinal"),
        MappedProperty(Throwable::class.java, "getMessage", "message", true),
        MappedProperty(Throwable::class.java, "getCause", "cause", true)
    )

    // The Java collection types Kotlin maps to read-only ones, by the mutable type that declares their mutators
    private val MUTABLE_COLLECTIONS = mapOf(
        "java.lang.Iterable" to "MutableIterable", "java.util.Iterator" to "MutableIterator",
        "java.util.ListIterator" to "MutableListIterator", "java.util.Collection" to "MutableCollection",
        "java.util.List" to "MutableList", "java.util.Set" to "MutableSet", "java.util.Map" to "MutableMap"
    )

    // The members of the Java collection types the read-only types Kotlin maps them to declare
    private val READ_ONLY_MEMBERS = setOf(
        "size", "isEmpty", "contains", "containsAll", "iterator", "get", "indexOf", "lastIndexOf", "listIterator",
        "subList", "containsKey", "containsValue", "keySet", "values", "entrySet", "hasNext", "next", "hasPrevious",
        "previous", "nextIndex", "previousIndex", "getOrDefault", "forEach", "stream", "parallelStream", "spliterator",
        "toArray", "equals", "hashCode", "toString", "getClass"
    )

    // The Java classes Kotlin maps onto its own: only kotlin.Throwable can be caught or thrown
    private val KOTLIN_CLASSES = mapOf(
        "java.lang.Throwable" to ClassName("kotlin", "Throwable"),
        "java.lang.Number" to ClassName("kotlin", "Number")
    )

    /**
     * @param receiverClass The class of the receiver, where it is a compiled one
     * @param supertypes    The compiled classes the receiver is an instance of: its own, or those a generated one extends
     * @param receiverType  The type of the receiver
     * @param method        The invoked method
     * @return How Kotlin calls the method, or `null` where it calls it as it is
     */
    fun mappedCall(receiverClass: Class<*>?, supertypes: List<Class<*>>, receiverType: TypeDef, method: MethodDef): MappedCall? {
        val name = method.name
        val arity = method.parameters.size
        val primitive = receiverType as? TypeDef.Primitive ?: KotlinConversions.unboxed(receiverType)
        if (name == "getClass" && arity == 0) {
            // A primitive's javaClass is the primitive's class, the model's value is the box
            return MappedCall.Property("javaClass", primitive != null)
        }
        if ((name == "notify" || name == "notifyAll") && arity == 0 || name == "wait" && arity <= 2) {
            return MappedCall.ThroughJavaType(JAVA_OBJECT)
        }
        // A Java subclass of Number is one of kotlin.Number, a generated one extends the Java class
        if (arity == 0 && (primitive != null || receiverClass != null && Number::class.java.isAssignableFrom(receiverClass))) {
            NUMBER_CONVERSIONS[name]?.let { target ->
                if (primitive == target) {
                    return MappedCall.Identity
                }
                val source = primitive ?: if (receiverClass == Double::class.javaObjectType || receiverClass == Float::class.javaObjectType) {
                    TypeDef.Primitive.DOUBLE
                } else {
                    null
                }
                return MappedCall.Conversion(source?.let { KotlinConversions.conversion(target, it) } ?: KotlinConversions.numberConversion(target))
            }
            if (name == "charValue" && primitive == TypeDef.Primitive.CHAR || name == "booleanValue" && primitive == TypeDef.Primitive.BOOLEAN) {
                return MappedCall.Identity
            }
        }
        renamedMember(method, supertypes, receiverClass ?: loadedClass(receiverType))?.let { return MappedCall.Renamed(it) }
        if (receiverClass == String::class.java && name !in KOTLIN_STRING_MEMBERS
            // A Kotlin extension of String, `uppercase`, which a model for Kotlin names, is called as it is
            && String::class.java.methods.any { it.name == name && it.parameterCount == arity }) {
            return MappedCall.ThroughJavaType(JAVA_STRING)
        }
        if (name == "toArray" && arity <= 1 && supertypes.any { Collection::class.java.isAssignableFrom(it) }) {
            return MappedCall.ThroughJavaType(JAVA_COLLECTION)
        }
        return null
    }

    /**
     * The name of a Java method Kotlin overrides as another member function: `get` for the `charAt` of a
     * `CharSequence`.
     *
     * @param supertypes The compiled supertypes of the overriding definition
     * @param method     The overriding method
     * @return The name, or `null` where it is the method's
     */
    fun overrideName(supertypes: List<Class<*>>, method: MethodDef): String? = renamedMember(method, supertypes, null)

    /**
     * @param method     The Java method
     * @param supertypes The compiled classes the receiver is an instance of
     * @param receiver   The compiled class of the receiver of a call, `null` for an override
     * @return The name of the member function Kotlin names the method with, or `null` where it is the method's
     */
    private fun renamedMember(method: MethodDef, supertypes: List<Class<*>>, receiver: Class<*>?): String? =
        RENAMED_MEMBERS.firstOrNull { renamed ->
            renamed.method == method.name && renamed.parameters.size == method.parameters.size
                && renamed.parameters.indices.all { renamed.parameters[it] == null || renamed.parameters[it] == method.parameters[it].type }
                && (if (renamed.receiverOnly) receiver != null && renamed.owner.isAssignableFrom(receiver)
                    else supertypes.any { renamed.owner.isAssignableFrom(it) })
        }?.name

    private val READ_ONLY_TO_MUTABLE = mapOf(
        "kotlin.collections.Iterable" to "MutableIterable",
        "kotlin.collections.Iterator" to "MutableIterator",
        "kotlin.collections.Collection" to "MutableCollection",
        "kotlin.collections.List" to "MutableList",
        "kotlin.collections.Set" to "MutableSet",
        "kotlin.collections.Map" to "MutableMap"
    )

    /**
     * The type of a property a Java class maps: Kotlin sees a Java collection as a mutable one, whose entries are
     * mutable too - the `entries` of an `AbstractMap` are a `MutableSet<MutableMap.MutableEntry<K, V>>`. A property
     * of a Java interface the source names, read-only as Kotlin maps it, has the entries of Kotlin's `Map`.
     *
     * @param type    The type of the Java method
     * @param mutable Whether the property is one of a Java class
     * @return The type of the property
     */
    fun mappedPropertyType(type: TypeName, mutable: Boolean): TypeName {
        val nullable = type.isNullable
        return when (type) {
            is ParameterizedTypeName -> {
                val raw = type.rawType
                val mapped = if (mutable) READ_ONLY_TO_MUTABLE[raw.canonicalName]?.let { ClassName("kotlin.collections", it) } else null
                (mapped ?: entryType(raw, mutable) ?: raw)
                    .parameterizedBy(type.typeArguments.map { mappedPropertyType(it, mutable) }).copy(nullable = nullable)
            }
            is ClassName -> (entryType(type, mutable) ?: type).copy(nullable = nullable)
            is WildcardTypeName -> type
            else -> type
        }
    }

    private fun entryType(raw: ClassName, mutable: Boolean): ClassName? =
        if (raw.canonicalName == "java.util.Map.Entry") {
            if (mutable) ClassName("kotlin.collections", "MutableMap", "MutableEntry") else ClassName("kotlin.collections", "Map", "Entry")
        } else {
            null
        }

    /**
     * The mutable Kotlin collection a Java collection is cast to for a call of a mutator, which the read-only type
     * Kotlin maps it to does not declare.
     *
     * @param javaType The Java collection type
     * @param method   The name of the invoked method
     * @return The simple name of the mutable type, or `null` where the call needs no cast
     */
    fun mutableCollectionFor(javaType: String, method: String): String? =
        MUTABLE_COLLECTIONS[javaType]?.takeIf { method !in READ_ONLY_MEMBERS }

    /**
     * @param javaType A Java collection type Kotlin maps to a read-only one
     * @return The simple name of the mutable Kotlin type of it
     */
    fun mutableCollectionOf(javaType: String): String = MUTABLE_COLLECTIONS.getValue(javaType)

    /**
     * @param javaClass The name of a Java class
     * @return The Kotlin class it is written as
     */
    fun kotlinClassOf(javaClass: ClassName): ClassName = KOTLIN_CLASSES[javaClass.canonicalName] ?: javaClass

    /**
     * @param receiver The compiled class of the receiver
     * @param method   The invoked method
     * @return Whether Kotlin sees the method as a nullable property, as the message of an exception
     */
    fun isNullableProperty(receiver: Class<*>?, method: MethodDef): Boolean =
        receiver != null && method.parameters.isEmpty()
            && MAPPED_PROPERTIES.any { it.nullable && it.method == method.name && it.owner.isAssignableFrom(receiver) }

    /**
     * @param method The invoked method
     * @return Whether a compiled class maps the method to a nullable property, whatever the receiver
     */
    fun mayBeNullableProperty(method: MethodDef): Boolean =
        method.parameters.isEmpty() && MAPPED_PROPERTIES.any { it.nullable && it.method == method.name }

    /** The property a generated definition inherits for a Java method Kotlin maps to one, or `null`. */
    fun inheritedProperty(scope: GenerationScope, definition: ObjectDef, method: MethodDef, visited: MutableSet<String>): String? =
        inheritedPropertyOf(scope, definition, method, visited)?.first

    /** The property a generated definition inherits for a Java method Kotlin maps to one, with the compiled type declaring it. */
    fun inheritedPropertyOf(scope: GenerationScope, definition: ObjectDef, method: MethodDef, visited: MutableSet<String>): Pair<String, Class<*>>? {
        if (method.parameters.isNotEmpty() || !visited.add(definition.asTypeDef().name)) {
            return null
        }
        return TypeHierarchy.superTypesOf(definition).firstNotNullOfOrNull { superType ->
            val generated = scope.definitionOf(TypeHierarchy.unwrap(superType) as? ClassTypeDef)
            val loaded = if (generated == null) loadedClass(superType, scope.typeLookup()) else null
            when {
                generated != null -> inheritedPropertyOf(scope, generated, method, visited)
                // The entry is written as the Java interface, which Kotlin does not map: its getters are methods
                loaded == null || loaded == Map.Entry::class.java -> null
                else -> mappedProperty(loaded, method)?.let { it to loaded }
            }
        }
    }

    /** The compiled classes a generated definition extends or implements, through the generated ones it extends. */
    fun compiledSupertypes(scope: GenerationScope, definition: ObjectDef, visited: MutableSet<String> = HashSet()): List<Class<*>> {
        if (!visited.add(definition.asTypeDef().name)) {
            return emptyList()
        }
        return TypeHierarchy.superTypesOf(definition).flatMap { superType ->
            val generated = scope.definitionOf(TypeHierarchy.unwrap(superType) as? ClassTypeDef)
            if (generated != null) compiledSupertypes(scope, generated, visited) else listOfNotNull(loadedClass(superType, scope.typeLookup()))
        }
    }

    /** The property Kotlin maps a Java method without parameters to, or `null`. */
    fun mappedProperty(owner: Class<*>?, method: MethodDef): String? {
        if (owner == null || method.parameters.isNotEmpty()) {
            return null
        }
        return MAPPED_PROPERTIES.firstOrNull { it.owner.isAssignableFrom(owner) && it.method == method.name }?.property
    }
}
