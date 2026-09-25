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

import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef

/**
 * The names visible at a point of the rendering. Kotlin allows a lambda parameter or a catch
 * variable to shadow an enclosing name, but the shadow is a warning and it hides the outer
 * value, so a colliding name is emitted under an allocated one and its references remapped.
 *
 * @param parent The enclosing scope
 * @param owner  The method the scope belongs to
 */
internal class KotlinRenderScope private constructor(
    private val parent: KotlinRenderScope?,
    private val owner: MethodDef?
) {
    private val renames = LinkedHashMap<String, String>()
    private val taken = LinkedHashSet<String>()
    private val smartCasts = LinkedHashSet<String>()
    private val nullableLocals = LinkedHashSet<String>()

    /**
     * Records a local declared nullable where the model types it as not null.
     *
     * @param name The name
     */
    fun markNullableLocal(name: String) {
        nullableLocals.add(name)
    }

    /**
     * @param name The name of a local
     * @return True if this scope or an enclosing one declared it nullable
     */
    fun isNullableLocal(name: String): Boolean {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            if (scope.nullableLocals.contains(name)) {
                return true
            }
            scope = scope.parent
        }
        return false
    }

    /**
     * @param name The name of a parameter
     * @return The method of the innermost scope that declares it, or null
     */
    fun parameterOwner(name: String): MethodDef? {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            if (scope.owner?.findParameter(name) != null) {
                return scope.owner
            }
            scope = scope.parent
        }
        return null
    }

    private var ownReturnLabel: String? = null

    /**
     * The label a `return` in a lambda body is written with: a bare one returns from the enclosing function. A
     * scope nested in the body, such as a catch block, returns from the same lambda.
     */
    var returnLabel: String?
        get() = ownReturnLabel ?: parent?.returnLabel
        set(value) {
            ownReturnLabel = value
        }

    private var ownReturnType: TypeDef? = null

    /**
     * The type a `return` of the scope returns, where it is not the one of the method: the type a case of a switch
     * expression yields, whose returns are its value.
     */
    var returnType: TypeDef?
        get() = ownReturnType ?: parent?.returnType
        set(value) {
            ownReturnType = value
        }

    /**
     * Records that a statement of the scope cast a parameter or a local, which Kotlin smart casts after it.
     *
     * @param name The name
     */
    fun markSmartCast(name: String) {
        smartCasts.add(name)
    }

    /**
     * @param name The name of a parameter or a local
     * @return True if a statement of this scope or an enclosing one cast it
     */
    fun isSmartCast(name: String): Boolean {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            if (scope.smartCasts.contains(name)) {
                return true
            }
            scope = scope.parent
        }
        return false
    }

    init {
        owner?.parameters?.forEach { taken.add(it.name) }
    }

    companion object {
        /**
         * @param owner The method the scope belongs to
         * @return A root scope
         */
        fun root(owner: MethodDef?) = KotlinRenderScope(null, owner)
    }

    /**
     * @param owner The method the nested scope belongs to
     * @return A scope nested in this one
     */
    fun nested(owner: MethodDef?) = KotlinRenderScope(this, owner)

    /**
     * Records a name as declared in this scope, so that a nested lambda does not reuse it.
     *
     * @param name The name
     */
    fun declare(name: String) {
        taken.add(name)
    }

    /**
     * Records that a name of the owning method is emitted under a different name.
     *
     * @param name        The name in the model
     * @param emittedName The name to emit
     */
    fun rename(name: String, emittedName: String) {
        renames[name] = emittedName
        taken.add(emittedName)
    }

    /**
     * @param name The name
     * @return True if the name is already used by this scope or any enclosing one
     */
    fun isTaken(name: String): Boolean {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            if (scope.taken.contains(name)) {
                return true
            }
            scope = scope.parent
        }
        return false
    }

    /**
     * Allocates a name that is not used by this scope or any enclosing one.
     *
     * @param name The preferred name
     * @return The preferred name, or a name derived from it
     */
    fun allocate(name: String): String {
        if (!isTaken(name)) {
            return name
        }
        var i = 1
        var candidate = name + i
        while (isTaken(candidate)) {
            candidate = name + ++i
        }
        return candidate
    }

    /**
     * Resolves the name a method parameter is emitted under, looking in the innermost scope that
     * declares it and walking outwards so that a lambda body can capture a parameter of the
     * enclosing method.
     *
     * @param name The parameter name
     * @return The name to emit, or null if no scope declares the parameter
     */
    fun resolveParameter(name: String): String? {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            if (scope.owner?.findParameter(name) != null) {
                return scope.renames.getOrDefault(name, name)
            }
            scope = scope.parent
        }
        return null
    }

    /**
     * Resolves a name recorded by [rename], walking outwards.
     *
     * @param name The name in the model
     * @return The name to emit, or null if no scope renamed it
     */
    fun resolveRename(name: String): String? {
        var scope: KotlinRenderScope? = this
        while (scope != null) {
            val emittedName = scope.renames[name]
            if (emittedName != null) {
                return emittedName
            }
            scope = scope.parent
        }
        return null
    }
}
