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

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.EnumDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.TypeHierarchy
import io.micronaut.sourcegen.model.VariableDef

/**
 * The private members of the nested types of a file that code outside them uses. Java lets the types of a nest use
 * each other's private members; Kotlin only lets a nested type use those of the types enclosing it, so such a member
 * is written without `private`.
 *
 * @param accessed The members, as `binary name of the owner#member name`, a constructor named `<init>`
 */
internal class KotlinNestAccess private constructor(private val accessed: Set<String>) {

    /**
     * @param owner  The definition declaring the member
     * @param member The member name, `<init>` for a constructor
     * @return Whether code of the file outside the owner, and outside the types it encloses, uses the member
     */
    fun isUsedOutside(owner: ObjectDef, member: String): Boolean = "${owner.asTypeDef().name}#$member" in accessed

    companion object {

        private val NONE = KotlinNestAccess(emptySet())

        /**
         * @param outermost The top level definition of the file
         * @return The members its nested types have to expose to the rest of the file
         */
        fun of(outermost: ObjectDef): KotlinNestAccess {
            val definitions = LinkedHashMap<String, ObjectDef>()
            collect(outermost, definitions)
            if (definitions.size < 2) {
                return NONE
            }
            val accessed = HashSet<String>()
            for ((name, definition) in definitions) {
                val record = { owner: TypeDef?, member: String ->
                    val ownerName = ((TypeHierarchy.unwrap(owner ?: TypeDef.OBJECT) as? ClassTypeDef)
                        ?.let { (it as? ClassTypeDef.Parameterized)?.rawType ?: it })?.name
                    // A type may use the private members of the types enclosing it, and its own
                    if (ownerName != null && ownerName in definitions && ownerName != name && !name.startsWith("$ownerName$")) {
                        accessed.add("$ownerName#$member")
                    }
                }
                forEachExpression(definition) { expression ->
                    when (expression) {
                        is ExpressionDef.InvokeInstanceMethod -> record(
                            if (expression.instance is VariableDef.This) definition.asTypeDef() else expression.instance.type(),
                            if (expression.method.isConstructor) "<init>" else expression.method.name
                        )
                        is ExpressionDef.InvokeStaticMethod -> record(expression.classDef, expression.method.name)
                        is ExpressionDef.NewInstance -> record(expression.type, "<init>")
                        is VariableDef.Field -> record(
                            if (expression.declaringType == TypeDef.THIS) definition.asTypeDef() else expression.declaringType,
                            expression.name
                        )
                        is VariableDef.StaticField -> record(expression.ownerType, expression.name)
                        else -> Unit
                    }
                }
            }
            return KotlinNestAccess(accessed)
        }

        private fun collect(definition: ObjectDef, definitions: MutableMap<String, ObjectDef>) {
            definitions[definition.asTypeDef().name] = definition
            definition.innerTypes.forEach { collect(it, definitions) }
        }
    }
}

/**
 * Visits every expression a definition's own code holds - its methods, its static initializer, the initializers of
 * its fields and the arguments of its enum constants - not those of its nested types.
 *
 * @param definition The definition
 * @param action     The visitor
 */
internal fun forEachExpression(definition: ObjectDef, action: (ExpressionDef) -> Unit) {
    definition.methods.forEach { method -> method.statements.forEach { forEachExpression(it, action) } }
    when (definition) {
        is ClassDef -> {
            definition.staticInitializer?.let { forEachExpression(it, action) }
            definition.fields.forEach { field -> field.initializer.ifPresent { visit(it, action) } }
        }
        is EnumDef -> {
            definition.fields.forEach { field -> field.initializer.ifPresent { visit(it, action) } }
            definition.enumConstants.forEach { constant -> constant.constructorArgs.forEach { visit(it, action) } }
        }
        else -> Unit
    }
}

/**
 * Visits every expression of a statement, the bodies of its lambdas and of the cases of its switch expressions too.
 *
 * @param statement The statement
 * @param action    The visitor
 */
internal fun forEachExpression(statement: StatementDef, action: (ExpressionDef) -> Unit) {
    if (statement is ExpressionDef) {
        visit(statement, action)
    } else {
        statement.nestedExpressionsStream().forEach { visit(it, action) }
    }
}

/**
 * Visits a statement and every statement nested in it: in its blocks, and in the bodies of the lambdas and of the
 * switch expression cases of its expressions.
 *
 * @param statement The statement
 * @param action    The visitor
 */
internal fun forEachStatement(statement: StatementDef?, action: (StatementDef) -> Unit) {
    if (statement == null) {
        return
    }
    action(statement)
    when (statement) {
        is StatementDef.Multi -> statement.statements.forEach { forEachStatement(it, action) }
        is StatementDef.If -> forEachStatement(statement.statement, action)
        is StatementDef.IfElse -> {
            forEachStatement(statement.statement, action)
            forEachStatement(statement.elseStatement, action)
        }
        is StatementDef.While -> forEachStatement(statement.statement, action)
        is StatementDef.Synchronized -> forEachStatement(statement.statement, action)
        is StatementDef.Switch -> {
            statement.cases.values.forEach { forEachStatement(it, action) }
            forEachStatement(statement.defaultCase, action)
        }
        is StatementDef.Try -> {
            forEachStatement(statement.statement, action)
            statement.catches.forEach { forEachStatement(it.statement, action) }
            forEachStatement(statement.finallyStatement, action)
        }
        else -> Unit
    }
    // The statements of the lambdas and of the switch expressions of the statement's own expressions
    val own = if (statement is ExpressionDef) listOf(statement) else when (statement) {
        is StatementDef.Multi, is StatementDef.If, is StatementDef.IfElse, is StatementDef.While, is StatementDef.Synchronized,
        is StatementDef.Switch, is StatementDef.Try -> emptyList()
        else -> statement.nestedExpressionsStream().toList()
    }
    own.forEach { expression ->
        visit(expression) { nested ->
            when (nested) {
                is ExpressionDef.Lambda -> nested.implementation.statements.forEach { forEachStatement(it, action) }
                is ExpressionDef.SwitchYieldCase -> forEachStatement(nested.statement, action)
                else -> Unit
            }
        }
    }
}

private fun visit(expression: ExpressionDef, action: (ExpressionDef) -> Unit) {
    action(expression)
    expression.nestedExpressionsStream().forEach { visit(it, action) }
    when (expression) {
        is ExpressionDef.Lambda -> expression.implementation.statements.forEach { forEachExpression(it, action) }
        is ExpressionDef.SwitchYieldCase -> forEachExpression(expression.statement, action)
        else -> Unit
    }
}
