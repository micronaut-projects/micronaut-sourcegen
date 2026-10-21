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
 * The statements that Kotlin spells differently to Java - a catch variable has to be named, a
 * synchronized block is a function taking the body and a static field lives on a companion object.
 */
class StatementWriteTest {

    @Test
    fun writeTryCatchFinally() {
        val body = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build { _, _ ->
                ExpressionDef.constant("value").returning()
                    .doTry()
                    .doCatch(IllegalStateException::class.java) { e -> e.invoke("toString", TypeDef.STRING).returning() }
                    .doFinally(ExpressionDef.constant("cleanup").invoke("trim", TypeDef.STRING))
            }

        Assertions.assertEquals(
            """
            package test

            import java.lang.IllegalStateException
            import kotlin.String

            public class MyClass {
              public fun run(): String {
                try {
                  return "value"
                } catch (e: IllegalStateException) {
                  return e.toString()
                } finally {
                  "cleanup".trim()
                }
              }
            }
            """.trimIndent(),
            writeClass(body)
        )
    }

    @Test
    fun writeNestedCatchesUnderDistinctNames() {
        val body = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build { _, _ ->
                ExpressionDef.constant("value").returning()
                    .doTry()
                    .doCatch(IllegalStateException::class.java) { outer ->
                        outer.invoke("toString", TypeDef.STRING).returning()
                            .doTry()
                            .doCatch(RuntimeException::class.java) { inner ->
                                inner.invoke("toString", TypeDef.STRING).returning()
                            }
                    }
            }

        Assertions.assertEquals(
            """
            package test

            import java.lang.IllegalStateException
            import java.lang.RuntimeException
            import kotlin.String

            public class MyClass {
              public fun run(): String {
                try {
                  return "value"
                } catch (e: IllegalStateException) {
                  try {
                    return e.toString()
                  } catch (e1: RuntimeException) {
                    return e1.toString()
                  }
                }
              }
            }
            """.trimIndent(),
            writeClass(body)
        )
    }

    @Test
    fun writeSynchronized() {
        val result = VariableDef.Local("result", TypeDef.STRING)
        val body = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build { aThis, _ ->
                StatementDef.multi(
                    result.defineAndAssign(ExpressionDef.constant("before")),
                    StatementDef.Synchronized(aThis, result.assign(ExpressionDef.constant("locked"))),
                    result.returning()
                )
            }

        Assertions.assertEquals(
            """
            package test

            import kotlin.String

            public class MyClass {
              public fun run(): String {
                var result:String = "before"
                synchronized(this) {
                  result = "locked"
                }
                return result
              }
            }
            """.trimIndent(),
            writeClass(body)
        )
    }

    @Test
    fun writePutStaticField() {
        val owner = ClassTypeDef.of("test.Owner")
        val body = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.VOID)
            .build { _, _ ->
                owner.getStaticField("NAME", TypeDef.STRING).put(ExpressionDef.constant("value"))
            }

        Assertions.assertEquals(
            """
            package test

            public class MyClass {
              public fun run() {
                Owner.NAME = "value"
              }
            }
            """.trimIndent(),
            writeClass(body)
        )
    }

    private fun writeClass(method: MethodDef): String {
        val classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
            .build()
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(classDef, writer)
            return writer.toString().trim()
        }
    }
}
