package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter

class SwitchWriteTest {

    @Test
    fun writeStatementSwitchWithoutDefaultPreservesCallerCaseOrder() {
        val intType = TypeDef.Primitive.INT
        val result = VariableDef.Local("result", intType)
        val cases = linkedMapOf<ExpressionDef.Constant, StatementDef>()
        cases[ExpressionDef.constant(1)] = result.assign(intType.constant(10))
        cases[ExpressionDef.constant(0)] = result.assign(intType.constant(20))
        cases[ExpressionDef.constant(3)] = result.assign(intType.constant(30))
        cases[ExpressionDef.constant(2)] = result.assign(intType.constant(40))

        val classDef = ClassDef.builder("test.MyClass")
            .addMethod(
                MethodDef.builder("test")
                    .addParameter("value", intType)
                    .returns(intType)
                    .build { _, methodParameters ->
                        StatementDef.multi(
                            result.defineAndAssign(intType.constant(-1)),
                            methodParameters[0].asStatementSwitch(intType, cases),
                            result.returning()
                        )
                    }
            )
            .build()

        val data = writeClass(classDef)

        Assertions.assertEquals(
            """
            package test

            import kotlin.Int

            public class MyClass {
              public fun test(`value`: Int): Int {
                var result:Int = -1
                when (`value`) {
                  1-> {
                    result = 10
                  }
                  0-> {
                    result = 20
                  }
                  3-> {
                    result = 30
                  }
                  2-> {
                    result = 40
                  }
                }
                return result
              }
            }
            """.trimIndent(),
            data
        )
    }

    private fun writeClass(classDef: ClassDef): String {
        val generator = KotlinPoetSourceGenerator()
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            return writer.toString().trim()
        }
    }
}
