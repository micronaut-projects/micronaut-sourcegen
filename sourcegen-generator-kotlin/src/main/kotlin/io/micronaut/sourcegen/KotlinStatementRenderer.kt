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
import org.jspecify.annotations.Nullable

/*
 * The statements of a body.
 */

internal const val EXCEPTION_NAME = "e"

/** The names of the locals a statement declares, in any of its blocks. */
internal fun declaredLocals(statement: StatementDef?): List<String> = when (statement) {
    null -> emptyList()
    is DefineAndAssign -> listOf(statement.variable.name)
    is StatementDef.Multi -> statement.statements.flatMap { declaredLocals(it) }
    is StatementDef.If -> declaredLocals(statement.statement)
    is StatementDef.IfElse -> declaredLocals(statement.statement) + declaredLocals(statement.elseStatement)
    is StatementDef.Switch -> declaredLocals(statement.defaultCase) + statement.cases.values.flatMap { declaredLocals(it) }
    is StatementDef.While -> declaredLocals(statement.statement)
    is StatementDef.Synchronized -> declaredLocals(statement.statement)
    is StatementDef.Try -> declaredLocals(statement.statement) + declaredLocals(statement.finallyStatement) +
        statement.catches.flatMap { declaredLocals(it.statement) }
    else -> emptyList()
}

/** Whether a statement returns from its function, other than from its lambdas and its switch expressions. */
internal fun containsOwnReturn(statement: StatementDef): Boolean {
    var found = false
    ownReturns(statement) { found = true }
    return found
}

/**
 * The returns of a statement that return from its function, not those of its lambdas or its switch expressions,
 * nor those after a statement that cannot complete, which are not written.
 */
internal fun ownReturns(statement: StatementDef?, action: (Return) -> Unit) {
    when (statement) {
        is Return -> action(statement)
        is Multi -> for (nested in statement.statements) {
            ownReturns(nested, action)
            if (Completion.KOTLIN.cannotCompleteNormally(nested)) {
                break
            }
        }
        is StatementDef.If -> ownReturns(statement.statement, action)
        is StatementDef.IfElse -> {
            ownReturns(statement.statement, action)
            ownReturns(statement.elseStatement, action)
        }
        is StatementDef.While -> ownReturns(statement.statement, action)
        is StatementDef.Synchronized -> ownReturns(statement.statement, action)
        is StatementDef.Switch -> {
            statement.cases.values.forEach { ownReturns(it, action) }
            ownReturns(statement.defaultCase, action)
        }
        is StatementDef.Try -> {
            ownReturns(statement.statement, action)
            statement.catches.forEach { ownReturns(it.statement, action) }
            ownReturns(statement.finallyStatement, action)
        }
        else -> Unit
    }
}

internal fun KotlinWriteContext.renderStatementCodeBlock(
    objectDef: @Nullable ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    statementDef: StatementDef?,
    // Whether nothing follows the statement in the body, so that returning is what falling out of it does
    tailPosition: Boolean = false
): CodeBlock {
    if (statementDef is Multi) {
        val builder: CodeBlock.Builder =
            CodeBlock.builder()
        for ((index, statement) in statementDef.statements.withIndex()) {
            builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statement,
                tailPosition && index == statementDef.statements.size - 1))
            if (Completion.KOTLIN.cannotCompleteNormally(statement)) {
                // The model may append a fallback after an exhaustive statement, such as a return null
                break
            }
        }
        return builder.build()
    }
    val returnedVoid = (statementDef as? Return)?.expression?.takeIf { it.type() == TypeDef.VOID }
    if (returnedVoid != null) {
        // A void invocation cannot be returned in source. Where the statement is not in tail position - a
        // branch of a conditional, say - the call is followed by the return it stands for, which execution
        // would otherwise fall through
        val builder = CodeBlock.builder()
            .addStatement("%L", renderExpressionCode(objectDef, methodDef, scope, returnedVoid))
        if (!tailPosition) {
            builder.addStatement(if (scope.returnLabel == null) "return" else "return@${scope.returnLabel}")
        }
        return builder.build()
    }
    if (statementDef is StatementDef.Try) {
        return renderTry(objectDef, methodDef, scope, statementDef, tailPosition)
    }
    if (statementDef is StatementDef.Synchronized) {
        val builder: CodeBlock.Builder = CodeBlock.builder()
        builder.add("synchronized(")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.monitor(), true))
        builder.add(") {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement(), tailPosition))
        builder.unindent()
        builder.add("}\n")
        return builder.build()
    }
    if (statementDef is StatementDef.If) {
        val builder: CodeBlock.Builder =
            CodeBlock.builder()
        builder.add("if (")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.condition))
        builder.add(") {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement, tailPosition))
        builder.unindent()
        builder.add("}\n")
        return builder.build()
    }
    if (statementDef is StatementDef.IfElse) {
        val builder: CodeBlock.Builder = CodeBlock.builder()
        builder.add("if (")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.condition))
        builder.add(") {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement, tailPosition))
        builder.unindent()
        builder.add("} else {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.elseStatement, tailPosition))
        builder.unindent()
        builder.add("}\n")
        return builder.build()
    }
    if (statementDef is StatementDef.Switch) {
        return renderSwitchStatement(objectDef, methodDef, scope, statementDef, tailPosition)
    }
    if (statementDef is While) {
        val builder: CodeBlock.Builder =
            CodeBlock.builder()
        builder.add("while (")
        builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
        builder.add(") {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement))
        builder.unindent()
        builder.add("}\n")
        return builder.build()
    }
    if (statementDef != null && containsBlockBodyLambda(statementDef)) {
        return CodeBlock.builder().add(renderStatement(objectDef, methodDef, scope, statementDef)).add("\n").build()
    }
    return CodeBlock.builder()
        .addStatement("%L", renderStatement(objectDef, methodDef, scope, statementDef))
        .build()
}

internal fun isBlockBody(lambda: Lambda): Boolean =
    !(lambda.implementation.statements.size == 1 && lambda.implementation.statements[0] is Return
        && (lambda.implementation.statements[0] as Return).expression != null)

private fun containsBlockBodyLambda(statementDef: StatementDef): Boolean =
    statementDef.nestedExpressionsStream().anyMatch { containsBlockBodyLambda(it) }

private fun containsBlockBodyLambda(expressionDef: ExpressionDef): Boolean {
    if (expressionDef is Lambda) {
        return isBlockBody(expressionDef)
            || expressionDef.implementation.statements.any { containsBlockBodyLambda(it) }
    }
    return expressionDef.nestedExpressionsStream().anyMatch { containsBlockBodyLambda(it) }
}

private fun KotlinWriteContext.renderTry(
    objectDef: @Nullable ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    statementDef: StatementDef.Try,
    tailPosition: Boolean = false
): CodeBlock {
    val builder: CodeBlock.Builder = CodeBlock.builder()
    builder.add("try {\n")
    builder.indent()
    builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.statement(), tailPosition))
    builder.unindent()
    for (aCatch in statementDef.catches()) {
        // Kotlin warns about shadowing, so a nested catch gets a name of its own
        val exceptionLocal = scope.allocate(EXCEPTION_NAME)
        builder.add("} catch (%L: %T) {\n", exceptionLocal, asType(aCatch.exception(), objectDef))
        builder.indent()
        val catchScope = scope.nested(null)
        catchScope.rename(EXCEPTION_NAME, exceptionLocal)
        builder.add(renderStatementCodeBlock(objectDef, methodDef, catchScope, aCatch.statement(), tailPosition))
        builder.unindent()
    }
    val finallyStatement = statementDef.finallyStatement()
    if (finallyStatement != null) {
        builder.add("} finally {\n")
        builder.indent()
        // Never the tail: a return here discards an exception or a return of the try, which falling out of
        // the block does not
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, finallyStatement, false))
        builder.unindent()
    }
    builder.add("}\n")
    return builder.build()
}

private fun KotlinWriteContext.renderSwitchStatement(
    objectDef: @Nullable ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    statementDef: StatementDef.Switch,
    tailPosition: Boolean = false
): CodeBlock {
    val builder: CodeBlock.Builder = CodeBlock.builder()
    builder.add("when (")
    builder.add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
    builder.add(") {\n")
    builder.indent()
    for ((key, statement) in statementDef.cases) {
        // A key is one of the type of the subject: `1.toByte()` for a byte, which `1` is not
        builder.add(renderConstantExpression(
            KotlinConversions.coerce(key, statementDef.expression.type()) as? Constant ?: key, methodDef, scope))
        builder.add("-> {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statement, tailPosition))
        builder.unindent()
        builder.add("}\n")
    }
    if (statementDef.defaultCase != null) {
        builder.add("else -> {\n")
        builder.indent()
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statementDef.defaultCase, tailPosition))
        builder.unindent()
        builder.add("}\n")
    } else if (isEnumOrBoolean(statementDef.expression.type(), statementDef.cases.keys)) {
        // A `when` on an enum or a boolean has to be exhaustive, a switch without a default matches nothing else
        builder.add("else -> {}\n")
    }
    builder.unindent()
    builder.add("}\n")
    return builder.build()
}

private fun isEnumOrBoolean(type: TypeDef, keys: Collection<Constant>): Boolean {
    val unwrapped = TypeHierarchy.unwrap(type)
    return unwrapped == TypeDef.Primitive.BOOLEAN || (unwrapped as? ClassTypeDef)?.name == "java.lang.Boolean"
        || (unwrapped as? ClassTypeDef)?.isEnum == true || loadedClass(unwrapped)?.isEnum == true
        || keys.any { key -> (key.type as? ClassTypeDef)?.isEnum == true || key.value is Enum<*> }
}

private fun KotlinWriteContext.renderStatement(
    objectDef: ObjectDef?,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    statementDef: StatementDef?
): CodeBlock {
    // A constructor calls `super(..)` in its header, see renderBody: Kotlin has no such statement
    if (statementDef is Throw) {
        return CodeBlock.builder()
            .add("throw ")
            .add(renderExpressionCode(objectDef, methodDef, scope, statementDef.expression))
            .build()
    }
    if (statementDef is Return) {
        var returned: ExpressionDef? = statementDef.expression
        if (returned == null) {
            // The early return of a void method, or of a lambda that returns nothing
            return CodeBlock.of(if (scope.returnLabel == null) "return" else "return@${scope.returnLabel}")
        }
        if (returned.type() == TypeDef.VOID) {
            // Returning a void invocation is a plain call in source
            return renderExpressionCode(objectDef, methodDef, scope, returned)
        }
        // The value of a case of a switch expression is of the type it yields
        val returnType = scope.returnType ?: methodDef.returnType
        if (returnType != TypeDef.VOID && requiresImplicitReturnCast(returnType, returned.type())) {
            returned = returned.cast(returnType)
        }
        return CodeBlock.builder()
            .add(if (scope.returnLabel == null) "return " else "return@${scope.returnLabel} ")
            .add(renderAssigned(objectDef, methodDef, scope, returned, returnType, scope.returnType == null
                && enclosingFunctions.any { it === methodDef } && returnsNullable(objectDef, methodDef)))
            .build()
    }
    if (statementDef is PutField || statementDef is PutStaticField) {
        val field = if (statementDef is PutField) statementDef.field else (statementDef as PutStaticField).field
        val value = if (statementDef is PutField) statementDef.expression else (statementDef as PutStaticField).expression
        return renderVariable(objectDef, methodDef, scope, field, true).toBuilder()
            .add(" = ")
            // A field holding the JVM default is a nullable property
            .add(renderAssigned(objectDef, methodDef, scope, value, field.type(), isMaybeNull(objectDef, methodDef, field, scope)))
            .build()
    }
    if (statementDef is Assign) {
        return renderVariable(objectDef, methodDef, scope, statementDef.variable, true).toBuilder()
            .add(" = ")
            .add(renderAssigned(objectDef, methodDef, scope, statementDef.expression, statementDef.variable.type(),
                isMaybeNull(objectDef, methodDef, statementDef.variable, scope)))
            .build()
    }
    if (statementDef is DefineAndAssign) {
        // A local the model assigns `null`, or of a wrapper, is nullable
        val nullable = statementDef.variable.name in nullableLocals(methodDef)
        val definition = CodeBlock.builder()
            .add("var %N:%T", statementDef.variable.name, asType(statementDef.variable.type, objectDef, methodDef)
                .let { if (nullable) it.copy(nullable = true) else it })
            .add(" = ")
            .add(renderAssigned(objectDef, methodDef, scope, statementDef.expression, statementDef.variable.type, nullable))
            .build()
        if (nullable) {
            scope.markNullableLocal(statementDef.variable.name)
        }
        // Declared only after the initializer is rendered - a lambda in it cannot see the variable
        scope.declare(statementDef.variable.name)
        return definition
    }
    if (statementDef is ExpressionDef) {
        return renderExpressionCode(objectDef, methodDef, scope, statementDef)
    }

    throw IllegalStateException("Unrecognized statement: $statementDef")
}

internal fun KotlinWriteContext.renderYield(
    builder: CodeBlock.Builder,
    methodDef: MethodDef,
    scope: KotlinRenderScope,
    statementDef: StatementDef,
    objectDef: ObjectDef?,
    type: TypeDef
) {
    if (statementDef is StatementDef.Return) {
        // The value of the branch is its last expression: a `return` would leave the function
        builder.addStatement(
            "%L",
            renderExpressionCode(objectDef, methodDef, scope, statementDef.expression?.let { KotlinConversions.coerce(it, type) })
        )
    } else {
        throw java.lang.IllegalStateException("The last statement of SwitchYieldCase should be a return. Found: $statementDef")
    }
}
