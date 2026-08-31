package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * The control flow statements and the expressions that carry a body, all of which Kotlin writes as
 * an expression rather than a statement.
 */
class ControlFlowWriteTest {

    @Test
    fun writeIf() {
        Assertions.assertEquals(
            """
            if (`value` == null) {
              return "empty"
            }
            return `value`
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
                StatementDef.multi(
                    params[0].isNull().doIf(ExpressionDef.constant("empty").returning()),
                    params[0].returning()
                )
            }
        )
    }

    @Test
    fun writeIfElse() {
        Assertions.assertEquals(
            """
            if (`value` == null) {
              return "empty"
            } else {
              return `value`
            }
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
                params[0].isNull().doIfElse(
                    ExpressionDef.constant("empty").returning(),
                    params[0].returning()
                )
            }
        )
    }

    @Test
    fun writeWhile() {
        val counter = VariableDef.Local("counter", TypeDef.Primitive.INT)
        Assertions.assertEquals(
            """
            var counter:Int = 0
            while (counter < 3) {
              counter = counter + 1
            }
            return counter
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                StatementDef.multi(
                    counter.defineAndAssign(ExpressionDef.constant(0)),
                    StatementDef.While(
                        counter.compare(
                            ExpressionDef.ComparisonOperation.OpType.LESS_THAN,
                            ExpressionDef.constant(3)
                        ),
                        counter.assign(
                            counter.math(
                                ExpressionDef.MathBinaryOperation.OpType.ADDITION,
                                ExpressionDef.constant(1)
                            )
                        )
                    ),
                    counter.returning()
                )
            }
        )
    }

    @Test
    fun writeThrow() {
        Assertions.assertEquals(
            """
            throw IllegalStateException("broken")
            """.trimIndent(),
            writeBody(TypeDef.VOID) { _, _ ->
                ClassTypeDef.of(IllegalStateException::class.java)
                    .instantiate(ExpressionDef.constant("broken"))
                    .doThrow()
            }
        )
    }

    @Test
    fun writeStatementSwitchWithDefault() {
        val cases = linkedMapOf<ExpressionDef.Constant, StatementDef>(
            ExpressionDef.constant(1) to ExpressionDef.constant("one").returning(),
            ExpressionDef.constant(2) to ExpressionDef.constant("two").returning()
        )
        Assertions.assertEquals(
            """
            when (`value`) {
              1-> {
                return "one"
              }
              2-> {
                return "two"
              }
              else -> {
                return "many"
              }
            }
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
                params[0].asStatementSwitch(
                    TypeDef.STRING,
                    cases,
                    ExpressionDef.constant("many").returning()
                )
            }
        )
    }

    @Test
    fun writeExpressionSwitch() {
        val cases = linkedMapOf<ExpressionDef.Constant, ExpressionDef>(
            ExpressionDef.constant(1) to ExpressionDef.constant("one"),
            ExpressionDef.constant(2) to ExpressionDef.constant("two")
        )
        Assertions.assertEquals(
            """
            return when (`value`) {
                  1 -> "one";
                  2 -> "two";
                  else -> "many"}
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
                params[0].asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("many"))
                    .returning()
            }
        )
    }

    @Test
    fun writeExpressionSwitchWithAYieldingCase() {
        val cases = linkedMapOf<ExpressionDef.Constant, ExpressionDef>(
            ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(
                TypeDef.STRING,
                StatementDef.multi(
                    VariableDef.Local("held", TypeDef.STRING).defineAndAssign(ExpressionDef.constant("one")),
                    VariableDef.Local("held", TypeDef.STRING).returning()
                )
            )
        )
        val body = writeBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
            params[0].asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("many")).returning()
        }
        Assertions.assertTrue(body.contains("var held:kotlin.String = \"one\""), "was: $body")
        Assertions.assertTrue(body.contains("return held"), "was: $body")
    }

    @Test
    fun writeLambdaWithParameters() {
        val fnType = ClassTypeDef.of(java.util.function.Function::class.java)
        Assertions.assertEquals(
            """
            return Function {arg0: Any -> arg0}
            """.trimIndent(),
            writeBody(fnType) { _, _ ->
                fnType.getLambda().implement { _, params -> params[0].returning() }.returning()
            }
        )
    }

    @Test
    fun writeLambdaParameterShadowingAnEnclosingOne() {
        val fnType = ClassTypeDef.of(java.util.function.Function::class.java)
        // The enclosing parameter is already named `arg0`, so the lambda's own is renamed
        val method = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("arg0", TypeDef.STRING)
            .returns(fnType)
            .build { _, params ->
                fnType.getLambda().implement { _, _ -> params[0].returning() }.returning()
            }
        val body = writeMethod(method)
        Assertions.assertEquals(
            """
            return Function {arg01: Any -> arg01}
            """.trimIndent(),
            body
        )
    }

    @Test
    fun writeCallWithSeveralArguments() {
        Assertions.assertEquals(
            """
            return `value`.substring(1, 2)
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
                params[0].invoke(
                    "substring",
                    listOf(TypeDef.Primitive.INT, TypeDef.Primitive.INT),
                    TypeDef.STRING,
                    listOf(ExpressionDef.constant(1), ExpressionDef.constant(2))
                ).returning()
            }
        )
    }

    @Test
    fun writeStaticCallWithSeveralArguments() {
        Assertions.assertEquals(
            """
            return LangString.format("%s", `value`)
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
                ClassTypeDef.of(String::class.java).invokeStatic(
                    "format",
                    listOf(TypeDef.STRING, TypeDef.OBJECT.array()),
                    TypeDef.STRING,
                    listOf(ExpressionDef.constant("%s"), params[0])
                ).returning()
            }
        )
    }

    @Test
    fun writeArrayElementOfAConditional() {
        Assertions.assertEquals(
            """
            return (if (`value` == null) arrayOf<String>("a") else arrayOf<String>("b"))[0]
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
                params[0].isNull()
                    .doIfElse(
                        TypeDef.STRING.array().instantiate(ExpressionDef.constant("a")),
                        TypeDef.STRING.array().instantiate(ExpressionDef.constant("b"))
                    )
                    .arrayElement(0)
                    .returning()
            }
        )
    }

    @Test
    fun writeConcatenationOfTwoNonStrings() {
        // Neither side is a String, so the left one is turned into one first
        Assertions.assertEquals(
            """
            return LangString.valueOf(1) + 2
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                ExpressionDef.StringConcatenation(
                    ExpressionDef.constant(1),
                    ExpressionDef.constant(2)
                ).returning()
            }
        )
    }

    private fun writeBody(
        returns: TypeDef,
        vararg parameters: TypeDef,
        body: (ExpressionDef, List<ExpressionDef>) -> StatementDef
    ): String {
        val method = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(returns)
        parameters.forEach { method.addParameter("value", it) }
        return writeMethod(method.build(body))
    }

    private fun writeMethod(method: MethodDef): String {
        val classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
            .build()
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(classDef, writer)
            return writer.toString().lines()
                .dropWhile { !it.contains("fun run") }
                .drop(1)
                .takeWhile { it != "  }" }
                .joinToString("\n") { it.removePrefix("    ") }
        }
    }
}
