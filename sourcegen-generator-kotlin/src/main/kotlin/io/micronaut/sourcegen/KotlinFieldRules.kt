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
import javax.lang.model.element.Modifier

/**
 * How a field of the model is declared as a Kotlin property: lateinit, nullable where it holds the JVM
 * default, initialized with the default of its primitive, a constant, and how often the model assigns it.
 */
internal object KotlinFieldRules {

    /**
     * How a field is declared as a Kotlin property.
     *
     * @property static         Whether the field is static, a property of the companion object
     * @property byConstructors Whether every constructor assigns it, which lets it be declared without an initializer
     * @property nullified      Whether it is a nullable property holding the JVM default, `null`, until it is assigned
     * @property lateInit       Whether it is a lateinit var
     * @property reassigned     Whether the model assigns it where a val cannot be: a var
     * @property constant       Whether it is a constant of the compiler
     * @property jvmField       Whether it is a @JvmField, whose accessors would clash with a method
     */
    class Declaration(
        val static: Boolean,
        val byConstructors: Boolean,
        val nullified: Boolean,
        val lateInit: Boolean,
        val reassigned: Boolean,
        val constant: Boolean,
        val jvmField: Boolean
    )

    /**
     * @param objectDef The definition declaring the field
     * @param field     The field
     * @param modifiers The modifiers the property is declared with
     * @return How the field is declared as a Kotlin property
     */
    fun declarationOf(objectDef: ObjectDef?, field: FieldDef, modifiers: Set<Modifier>): Declaration {
        val static = field.modifiers.contains(Modifier.STATIC)
        val byConstructors = !static && isAssignedByEveryConstructor(objectDef, field)
        val nullified = isNullified(objectDef, field)
        // A Kotlin property must be initialized where it is declared, or by every constructor; one a constructor
        // assigns more than once, as in a try and its catch, is a lateinit var. A field no constructor assigns holds
        // the JVM default: null, or the default of its primitive
        val lateInit = !nullified && field.initializer.isEmpty && !field.type.isNullable
            && byConstructors && assignments(objectDef, field) > constructorsOf(objectDef)
            && field.type !is TypeDef.Primitive && field.type !is TypeDef.TypeVariable
            && !isKotlinPrimitive(field.type)
        // A val is assigned where it is declared, or once by each constructor: anything else the model assigns is a var
        val reassigned = nullified || field.initializer.isEmpty && (!byConstructors && assignments(objectDef, field) > 0
            || byConstructors && assignments(objectDef, field) > constructorsOf(objectDef))
        // A constant of a primitive or a String is one of the compiler, which an annotation can name
        val constant = static && Modifier.FINAL in modifiers && !reassigned && field.initializer.orElse(null)
            .let { it is Constant && it.value != null && (field.type is TypeDef.Primitive || field.type == TypeDef.STRING)
                && (it.type is TypeDef.Primitive || it.type == TypeDef.STRING) }
        // A field Java reads directly, where a method of the class is named as its getter or setter, whose name the
        // property's accessor would take
        val jvmField = !lateInit && !constant && Modifier.PRIVATE !in modifiers && clashesWithAnAccessor(objectDef, field)
        return Declaration(static, byConstructors, nullified, lateInit, reassigned, constant, jvmField)
    }

    /** Whether the definition declares a method named as the getter or the setter a property of the field has. */
    private fun clashesWithAnAccessor(objectDef: ObjectDef?, field: FieldDef): Boolean {
        val capitalized = field.name.replaceFirstChar { it.uppercaseChar() }
        return objectDef?.methods.orEmpty().any { method ->
            !method.isConstructor && (method.parameters.isEmpty() && (method.name == "get$capitalized" || method.name == "is$capitalized")
                || method.parameters.size == 1 && method.name == "set$capitalized")
        }
    }

    private fun constructorsOf(objectDef: ObjectDef?): Int = objectDef?.methods?.count { it.isConstructor } ?: 0

    /** How many statements of the definition assign the field. */
    private fun assignments(objectDef: ObjectDef?, field: FieldDef): Int {
        if (objectDef == null) {
            return 0
        }
        val bodies = objectDef.methods.flatMap { it.statements } + listOfNotNull((objectDef as? ClassDef)?.staticInitializer)
        return bodies.sumOf { countAssignments(it, field) }
    }

    private fun countAssignments(statement: StatementDef?, field: FieldDef): Int = when (statement) {
        null -> 0
        is StatementDef.PutField -> if (statement.field.name == field.name) 1 else 0
        is StatementDef.PutStaticField -> if (statement.field.name == field.name) 1 else 0
        is StatementDef.Multi -> statement.statements.sumOf { countAssignments(it, field) }
        is StatementDef.If -> countAssignments(statement.statement, field)
        // The branches exclude each other: a path assigns the field in one of them
        is StatementDef.IfElse -> maxOf(countAssignments(statement.statement, field), countAssignments(statement.elseStatement, field))
        is StatementDef.While -> countAssignments(statement.statement, field)
        is StatementDef.Synchronized -> countAssignments(statement.statement, field)
        is StatementDef.Switch -> maxOf(statement.cases.values.maxOfOrNull { countAssignments(it, field) } ?: 0,
            countAssignments(statement.defaultCase, field))
        is StatementDef.Try -> countAssignments(statement.statement, field) + countAssignments(statement.finallyStatement, field) +
            (statement.catches.maxOfOrNull { countAssignments(it.statement, field) } ?: 0)
        else -> 0
    }

    /**
     * A field of a reference type that no constructor assigns holds `null`, the JVM default, until it is assigned:
     * the lazily initialized one, `if (cache == null) cache = ..`, one a getter returns before its setter is
     * called, and a boxed one. It is a nullable property holding `null`, read with `!!` where Kotlin needs a
     * value. So is a field the model assigns `null`.
     */
    fun isNullified(objectDef: ObjectDef?, field: FieldDef): Boolean {
        if (field.type.isNullable || field.type is TypeDef.Primitive) {
            return false
        }
        val byConstructors = !field.modifiers.contains(Modifier.STATIC) && isAssignedByEveryConstructor(objectDef, field)
        return field.initializer.isEmpty && !byConstructors || isAssignedNull(objectDef, field)
    }

    /** Whether a statement of the definition assigns `null` to the field. */
    private fun isAssignedNull(objectDef: ObjectDef?, field: FieldDef): Boolean {
        if (objectDef == null) {
            return false
        }
        var assigned = false
        val bodies = objectDef.methods.flatMap { it.statements } + listOfNotNull((objectDef as? ClassDef)?.staticInitializer)
        bodies.forEach { body ->
            forEachStatement(body) { statement ->
                if (statement is PutField && statement.field.name == field.name && isNullLiteral(statement.expression)
                    || statement is PutStaticField && statement.field.name == field.name && isNullLiteral(statement.expression)) {
                    assigned = true
                }
            }
        }
        return assigned
    }

    private val BOXED_PRIMITIVES = mapOf(
        "java.lang.Byte" to TypeDef.Primitive.BYTE,
        "java.lang.Short" to TypeDef.Primitive.SHORT,
        "java.lang.Character" to TypeDef.Primitive.CHAR,
        "java.lang.Integer" to TypeDef.Primitive.INT,
        "java.lang.Long" to TypeDef.Primitive.LONG,
        "java.lang.Float" to TypeDef.Primitive.FLOAT,
        "java.lang.Double" to TypeDef.Primitive.DOUBLE,
        "java.lang.Boolean" to TypeDef.Primitive.BOOLEAN
    )

    /**
     * Whether the type is one Kotlin maps to a primitive, which cannot be a lateinit property.
     */
    private fun isKotlinPrimitive(typeDef: TypeDef): Boolean {
        return typeDef is ClassTypeDef && BOXED_PRIMITIVES.containsKey(typeDef.name)
    }

    /**
     * The value a property of a primitive type is declared with, where the model assigns the field later.
     */
    fun defaultOf(field: FieldDef, objectDef: ObjectDef? = null): ExpressionDef? {
        // An instance property is assigned by the constructor the model writes; a static one has none
        if (!field.initializer.isEmpty || field.type.isNullable
            || !field.modifiers.contains(Modifier.STATIC) && isAssignedByEveryConstructor(objectDef, field)) {
            return null
        }
        val primitive = (field.type as? ClassTypeDef)?.let { BOXED_PRIMITIVES[it.name] }
            ?: field.type as? TypeDef.Primitive
            ?: return null
        return TypeDef.Primitive.defaultValue(primitive.name())
    }

    /**
     * Whether every constructor of the definition assigns the field, which lets it be declared without an
     * initializer - and without lateinit, which an annotation such as @JvmField does not allow.
     */
    private fun isAssignedByEveryConstructor(objectDef: ObjectDef?, field: FieldDef): Boolean {
        val constructors = objectDef?.methods?.filter { it.isConstructor } ?: return false
        return constructors.isNotEmpty() && constructors.all { constructor ->
            val assignment = assignmentOf(StatementDef.multi(constructor.statements), field.name, false)
            assignment.assigned && !assignment.returnsUnassigned
        }
    }

    /**
     * How a statement assigns the field of this instance - not one of another object that shares its name.
     *
     * @property assigned          Whether the field is assigned on every path that completes the statement
     *                             normally - vacuously where none does
     * @property returnsUnassigned Whether a path returns from the constructor before assigning it
     * @property completes         Whether some path completes the statement normally
     */
    private data class Assignment(val assigned: Boolean, val returnsUnassigned: Boolean, val completes: Boolean)

    /**
     * @param assigned Whether the field is assigned by the time the statement is reached
     */
    private fun assignmentOf(statement: StatementDef?, name: String, assigned: Boolean): Assignment = when (statement) {
        null -> Assignment(assigned, false, true)
        is StatementDef.PutField -> Assignment(
            assigned || statement.field.name == name && statement.field.instance is VariableDef.This, false, true
        )
        // Nothing completes past either; a return ends the constructor with the field as it is
        is Return -> Assignment(true, !assigned, false)
        is Throw -> Assignment(true, false, false)
        is Multi -> {
            var current = assigned
            var returnsUnassigned = false
            var completes = true
            for (child in statement.statements) {
                val assignment = assignmentOf(child, name, current)
                current = assignment.assigned
                returnsUnassigned = returnsUnassigned || assignment.returnsUnassigned
                if (!assignment.completes || Completion.KOTLIN.cannotCompleteNormally(child)) {
                    // The statements after it are not rendered
                    completes = false
                    break
                }
            }
            Assignment(current, returnsUnassigned, completes)
        }
        is StatementDef.If -> {
            val then = assignmentOf(statement.statement, name, assigned)
            Assignment(assigned, then.returnsUnassigned, true)
        }
        is StatementDef.IfElse -> {
            val then = assignmentOf(statement.statement, name, assigned)
            val otherwise = assignmentOf(statement.elseStatement, name, assigned)
            Assignment(
                then.assigned && otherwise.assigned,
                then.returnsUnassigned || otherwise.returnsUnassigned,
                then.completes || otherwise.completes
            )
        }
        is StatementDef.Switch -> {
            val cases = statement.cases.values.map { assignmentOf(it, name, assigned) }
            val default = statement.defaultCase?.let { assignmentOf(it, name, assigned) }
            // Without a default, the path no case matches continues with the state it came in with
            Assignment(
                (default?.assigned ?: assigned) && cases.all { it.assigned },
                cases.any { it.returnsUnassigned } || default?.returnsUnassigned == true,
                default == null || default.completes || cases.any { it.completes }
            )
        }
        is StatementDef.While ->
            Assignment(assigned, assignmentOf(statement.statement, name, assigned).returnsUnassigned, true)
        is StatementDef.Synchronized -> assignmentOf(statement.statement(), name, assigned)
        is StatementDef.Try -> {
            val body = assignmentOf(statement.statement(), name, assigned)
            val catches = statement.catches().map { assignmentOf(it.statement(), name, assigned) }
            val completed = body.assigned && catches.all { it.assigned }
            // The finally follows the paths of the try and its catches that complete, with what they assigned;
            // where none does - a body that only throws - it starts with the state before the try
            val completing = (listOf(body) + catches).filter { it.completes }
            val entering = if (completing.isEmpty()) assigned else completing.all { it.assigned }
            val finallyAssignment = assignmentOf(statement.finallyStatement(), name, entering)
            // A return in the try or a catch runs the finally before it leaves
            val returnsInside = body.returnsUnassigned || catches.any { it.returnsUnassigned }
            val finallyAssigns = statement.finallyStatement() != null
                && assignmentOf(statement.finallyStatement(), name, false).assigned
            Assignment(
                completed || finallyAssignment.assigned,
                returnsInside && !finallyAssigns || finallyAssignment.returnsUnassigned,
                completing.isNotEmpty() && finallyAssignment.completes
            )
        }
        else -> Assignment(assigned, false, true)
    }
}
