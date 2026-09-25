package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.KotlinCompileAssertions.outcomeOf
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import java.util.function.Function
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
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
            writeIndentedBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
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
            writeIndentedBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
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
            writeIndentedBody(TypeDef.Primitive.INT) { _, _ ->
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
            writeIndentedBody(TypeDef.VOID) { _, _ ->
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
            writeIndentedBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
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
            writeIndentedBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
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
        val body = writeIndentedBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
            params[0].asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("many")).returning()
        }
        Assertions.assertTrue(body.contains("var held:kotlin.String = \"one\""), "was: $body")
        // The value of the branch is its last expression: a `return` would leave the function, not the `when`
        Assertions.assertTrue(Regex("\\n\\s+held\\n").containsMatchIn(body), "was: $body")
    }

    @Test
    fun writeLambdaWithParameters() {
        val fnType = ClassTypeDef.of(java.util.function.Function::class.java)
        Assertions.assertEquals(
            """
            return Function {arg0: Any -> arg0}
            """.trimIndent(),
            writeIndentedBody(fnType) { _, _ ->
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
            return (`value` as java.lang.String).substring(1, 2)
            """.trimIndent(),
            writeIndentedBody(TypeDef.STRING, TypeDef.STRING) { _, params ->
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
            writeIndentedBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
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
            writeIndentedBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
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
            writeIndentedBody(TypeDef.STRING) { _, _ ->
                ExpressionDef.StringConcatenation(
                    ExpressionDef.constant(1),
                    ExpressionDef.constant(2)
                ).returning()
            }
        )
    }

    // A yielding case of a switch expression is written with `return`, which in Kotlin returns from the method instead of giving the `when` its value: "one" instead of "one!". The Java generator yields.
    @Test
    fun switchExpressionYieldAssignedToLocal() {
        val def = ClassDef.builder("test.C22")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.STRING)
                .build { _, p -> p[0].asExpressionSwitch(TypeDef.STRING, mapOf(
                    ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(TypeDef.STRING, ExpressionDef.constant("one").returning())
                ), ExpressionDef.constant("other")).newLocal("text") { text -> text.stringConcat(ExpressionDef.constant("!")).returning() } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("one!", o.javaClass.getMethod("call", Int::class.javaPrimitiveType).invoke(o, 1))
        }
    }

    @Test
    fun switchOnAByteComparesByteKeys() {
        val def = ClassDef.builder("test.ByteSwitch").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.BYTE).returns(String::class.java)
                .build { _, p -> p[0].asStatementSwitch(TypeDef.STRING,
                    mapOf(TypeDef.Primitive.BYTE.constant(1.toByte()) to ExpressionDef.constant("one").returning()),
                    ExpressionDef.constant("other").returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("one", cls.getMethod("call", Byte::class.javaPrimitiveType).invoke(cls.getConstructor().newInstance(), 1.toByte()))
        }
    }

    @Test
    fun yieldCaseWithAPercentSign() {
        val def = ClassDef.builder("test.PercentYield").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(String::class.java)
                .build { _, p -> p[0].asExpressionSwitch(TypeDef.STRING, mapOf(
                    ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(TypeDef.STRING, StatementDef.multi(
                        ExpressionDef.constant("100%").newLocal("label"),
                        ExpressionDef.constant("all").returning()))),
                    ExpressionDef.constant("none")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("all", cls.getMethod("call", Int::class.javaPrimitiveType).invoke(cls.getConstructor().newInstance(), 1))
        }
    }

    @Test
    fun yieldCaseReturningFromBothBranches() {
        val def = ClassDef.builder("test.BranchYield").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN).returns(String::class.java)
                .build { _, p -> ExpressionDef.constant("result:").stringConcat(p[0].asExpressionSwitch(TypeDef.STRING, mapOf(
                    ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(TypeDef.STRING,
                        p[1].isTrue().doIfElse(ExpressionDef.constant("yes").returning(), ExpressionDef.constant("no").returning()))),
                    ExpressionDef.constant("none"))).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("result:yes", cls.getMethod("call", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(cls.getConstructor().newInstance(), 1, true))
        }
    }

    @Test
    fun yieldCaseWithAnEarlyReturnYieldsIt() {
        val def = ClassDef.builder("test.EarlyYield").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT)
                .addParameter("text", TypeDef.STRING.makeNullable()).returns(String::class.java)
                .build { _, p -> ExpressionDef.constant("result:").stringConcat(p[0].asExpressionSwitch(TypeDef.STRING, mapOf(
                    ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(TypeDef.STRING, StatementDef.multi(
                        p[1].isNull().doIf(ExpressionDef.constant("missing").returning()),
                        ExpressionDef.constant("present").returning()))),
                    ExpressionDef.constant("none"))).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("result:missing", cls.getMethod("call", Int::class.javaPrimitiveType, String::class.java)
                .invoke(cls.getConstructor().newInstance(), 1, null))
        }
    }

    @Test
    fun switchOnAnEnumWithoutADefaultCase() {
        val unit = ClassTypeDef.of(java.util.concurrent.TimeUnit::class.java)
        val def = ClassDef.builder("test.EnumSwitch").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("unit", unit).returns(String::class.java)
                .build { _, p -> StatementDef.multi(
                    p[0].asStatementSwitch(TypeDef.STRING, mapOf(
                        ExpressionDef.Constant(unit, java.util.concurrent.TimeUnit.SECONDS) to ExpressionDef.constant("s").returning())),
                    ExpressionDef.constant("other").returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("s", cls.getMethod("call", java.util.concurrent.TimeUnit::class.java).invoke(instance, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("other", cls.getMethod("call", java.util.concurrent.TimeUnit::class.java).invoke(instance, java.util.concurrent.TimeUnit.DAYS))
        }
    }

    @Test
    fun synchronizedAndInfiniteLoopBodiesThatOnlyReturn() {
        val def = ClassDef.builder("test.TerminalBlocks").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("locked").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { self, _ -> StatementDef.Synchronized(self, ExpressionDef.constant(1).returning()) })
            .addMethod(MethodDef.builder("looped").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { _, _ -> ExpressionDef.trueValue().whileLoop(ExpressionDef.constant(2).returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(1, cls.getMethod("locked").invoke(instance))
            assertEquals(2, cls.getMethod("looped").invoke(instance))
        }
    }

    @Test
    fun voidMethodReturnsEarly() {
        val counter = ClassTypeDef.of(java.util.concurrent.atomic.AtomicInteger::class.java)
        val increment = java.util.concurrent.atomic.AtomicInteger::class.java.getMethod("incrementAndGet")
        val def = ClassDef.builder("test.EarlyVoid").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("counter", counter)
                .addParameter("skip", TypeDef.Primitive.BOOLEAN).returns(TypeDef.VOID)
                .build { _, p -> StatementDef.multi(
                    p[1].isTrue().doIf(StatementDef.Return(null)),
                    p[0].invoke(increment)) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val count = java.util.concurrent.atomic.AtomicInteger()
            cls.getMethod("call", count.javaClass, Boolean::class.javaPrimitiveType).invoke(instance, count, true)
            cls.getMethod("call", count.javaClass, Boolean::class.javaPrimitiveType).invoke(instance, count, false)
            assertEquals(1, count.get())
        }
    }

    private fun writeIndentedBody(
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

    /**
     * Each branch of a conditional is converted to the conditional's type, as Java converts it: a byte, a short or a
     * char to the int of an `Integer`, and a reference cast, which throws for another type.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("conditionalBranches")
    fun conditionalBranchIsConvertedToItsType(type: TypeDef, first: TypeDef, second: TypeDef, flag: Boolean, firstValue: Any?,
                                              secondValue: Any?, expected: Any?) {
        assertEquals(expected, outcomeOf {
            runMethod("test.ConditionalBranch", type, listOf(TypeDef.Primitive.BOOLEAN, first, second), flag, firstValue, secondValue) { _, p ->
                ExpressionDef.IfElse(p[0].isTrue, p[1], p[2], type).returning()
            }
        })
    }

    companion object {
        private val INTEGER = TypeDef.of(Integer::class.java)
        private val LONG_BOX = TypeDef.of(java.lang.Long::class.java)
        private val NUMBER = TypeDef.of(Number::class.java)

        @JvmStatic
        fun conditionalBranches(): List<Arguments> = listOf(
            arguments(named("an int or a String as an Integer, the int", INTEGER), TypeDef.Primitive.INT, TypeDef.STRING, true, 5, "x", 5),
            arguments(named("an int or a String as an Integer, the String", INTEGER), TypeDef.Primitive.INT, TypeDef.STRING, false, 5, "x",
                ClassCastException::class.java),
            arguments(named("a char or a String as an Integer, the char's code", INTEGER), TypeDef.Primitive.CHAR, TypeDef.STRING, true,
                'a', "x", 97),
            arguments(named("a byte or an Object as an Integer, the byte's value", INTEGER), TypeDef.Primitive.BYTE, TypeDef.OBJECT, true,
                7.toByte(), 1, 7),
            arguments(named("an Integer or an Integer as a String, a null", TypeDef.STRING), INTEGER, INTEGER, true, null, 6, null),
            arguments(named("an Integer or an Integer as a String, a number", TypeDef.STRING), INTEGER, INTEGER, true, 5, 6,
                ClassCastException::class.java),
            arguments(named("a Long or a Long as an Integer, converted", INTEGER), LONG_BOX, LONG_BOX, true, 5L, 6L, 5),
            arguments(named("an Object or a double as a Number, the double", NUMBER), TypeDef.OBJECT, TypeDef.Primitive.DOUBLE, false,
                "x", 2.5, 2.5),
            arguments(named("a Character or an Integer as a Number, the Integer", NUMBER), TypeDef.of(Character::class.java), INTEGER, false,
                'a', 3, 3),
            arguments(named("a String or an int as a Number, the int", NUMBER), TypeDef.STRING, TypeDef.Primitive.INT, false, "x", 4, 4)
        )
    }
}
