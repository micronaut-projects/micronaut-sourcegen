package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * The expressions whose Kotlin form differs from the Java one - an array of a primitive has a type
 * of its own, a primitive cast is a conversion function and the operators bind differently.
 */
class ExpressionWriteTest {

    @Test
    fun writeArrayElement() {
        Assertions.assertEquals(
            """
            return arrayOf<String>("a", "b")[1]
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                TypeDef.STRING.array()
                    .instantiate(ExpressionDef.constant("a"), ExpressionDef.constant("b"))
                    .arrayElement(1)
                    .returning()
            }
        )
    }

    @Test
    fun writePrimitiveArrayElement() {
        Assertions.assertEquals(
            """
            return intArrayOf(10, 20)[1]
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                TypeDef.Primitive.INT.array()
                    .instantiate(ExpressionDef.constant(10), ExpressionDef.constant(20))
                    .arrayElement(1)
                    .returning()
            }
        )
    }

    @Test
    fun writeSizedPrimitiveArray() {
        Assertions.assertEquals(
            """
            return LongArray(3)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.LONG.array()) { _, _ ->
                TypeDef.Primitive.LONG.array().instantiate(3).returning()
            }
        )
    }

    @Test
    fun writeInstanceOf() {
        Assertions.assertEquals(
            """
            return `value` is String
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.OBJECT) { _, params ->
                params[0].instanceOf(ClassTypeDef.of(String::class.java)).returning()
            }
        )
    }

    @Test
    fun writeMathPrecedence() {
        Assertions.assertEquals(
            """
            return 2 * (3 + 4)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                ExpressionDef.constant(2)
                    .math(OpType.MULTIPLICATION, ExpressionDef.constant(3).math(OpType.ADDITION, ExpressionDef.constant(4)))
                    .returning()
            }
        )
    }

    @Test
    fun writeBitwiseOperationsAsInfixFunctions() {
        Assertions.assertEquals(
            """
            return 1 or (2 and 3)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                ExpressionDef.constant(1)
                    .math(OpType.BITWISE_OR, ExpressionDef.constant(2).math(OpType.BITWISE_AND, ExpressionDef.constant(3)))
                    .returning()
            }
        )
    }

    @Test
    fun writeOrNestedInAnd() {
        Assertions.assertEquals(
            """
            return (`value` == null || `value` != null) && `value` == null
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.OBJECT) { _, params ->
                params[0].isNull()
                    .or(params[0].isNonNull())
                    .and(params[0].isNull())
                    .returning()
            }
        )
    }

    @Test
    fun writePrimitiveCastAsConversion() {
        Assertions.assertEquals(
            """
            return (`value` + 1).toLong()
            """.trimIndent(),
            writeBody(TypeDef.Primitive.LONG, TypeDef.Primitive.INT) { _, params ->
                params[0].math(OpType.ADDITION, ExpressionDef.constant(1))
                    .cast(TypeDef.Primitive.LONG)
                    .returning()
            }
        )
    }

    @Test
    fun writeReferenceCastAsAs() {
        Assertions.assertEquals(
            """
            return `value` as String
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
                params[0].cast(TypeDef.STRING).returning()
            }
        )
    }

    @Test
    fun writeStaticCallOnAMappedType() {
        Assertions.assertEquals(
            """
            return LangString.valueOf(1)
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                ClassTypeDef.of(String::class.java)
                    .invokeStatic("valueOf", listOf(TypeDef.Primitive.INT), TypeDef.STRING, ExpressionDef.constant(1))
                    .returning()
            }
        )
    }

    @Test
    fun writeLongAndDoubleConstants() {
        Assertions.assertEquals(
            """
            return 5L
            """.trimIndent(),
            writeBody(TypeDef.Primitive.LONG) { _, _ -> ExpressionDef.constant(5L).returning() }
        )
        Assertions.assertEquals(
            """
            return 5.0
            """.trimIndent(),
            writeBody(TypeDef.Primitive.DOUBLE) { _, _ -> ExpressionDef.constant(5.0).returning() }
        )
        Assertions.assertEquals(
            """
            return 'c'
            """.trimIndent(),
            writeBody(TypeDef.Primitive.CHAR) { _, _ -> ExpressionDef.constant('c').returning() }
        )
    }

    @Test
    fun writeNonFiniteConstants() {
        Assertions.assertEquals(
            """
            return Double.NaN
            """.trimIndent(),
            writeBody(TypeDef.Primitive.DOUBLE) { _, _ -> ExpressionDef.constant(Double.NaN).returning() }
        )
        Assertions.assertEquals(
            """
            return Float.POSITIVE_INFINITY
            """.trimIndent(),
            writeBody(TypeDef.Primitive.FLOAT) { _, _ ->
                ExpressionDef.constant(Float.POSITIVE_INFINITY).returning()
            }
        )
    }

    @Test
    fun writeClassConstant() {
        Assertions.assertEquals(
            """
            return String::class.java
            """.trimIndent(),
            writeBody(ClassTypeDef.of(Class::class.java)) { _, _ ->
                ExpressionDef.constant(TypeDef.STRING).returning()
            }
        )
    }

    @Test
    fun writeLambdaCapturingAnEnclosingParameter() {
        val fnType = ClassTypeDef.of(java.util.function.Supplier::class.java)
        Assertions.assertEquals(
            """
            return Supplier {() -> `value`}
            """.trimIndent(),
            writeBody(fnType, TypeDef.STRING) { _, params ->
                fnType.getLambda().implement { _, _ -> params[0].returning() }.returning()
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
        val classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method.build(body))
            .build()
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(classDef, writer)
            // Only the body of the single method is of interest here
            return writer.toString().lines()
                .dropWhile { !it.contains("fun run") }
                .drop(1)
                .takeWhile { !it.trim().startsWith("}") }
                .joinToString("\n") { it.trim() }
        }
    }
}
