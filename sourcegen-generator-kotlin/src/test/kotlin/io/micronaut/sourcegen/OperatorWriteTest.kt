package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * The operator and comparison expressions. Kotlin agrees with Java on most of the spelling, so what
 * is pinned here is where the operands need parentheses and where an operation is a function call
 * rather than an operator.
 */
class OperatorWriteTest {

    @Test
    fun writeConditionalExpression() {
        Assertions.assertEquals(
            """
            return if (`value` == null) "empty" else "present"
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
                params[0].isNull()
                    .doIfElse(ExpressionDef.constant("empty"), ExpressionDef.constant("present"))
                    .returning()
            }
        )
    }

    @Test
    fun writeComparisons() {
        for ((op, symbol) in listOf(
            ComparisonOperation.OpType.EQUAL_TO to "==",
            ComparisonOperation.OpType.NOT_EQUAL_TO to "!=",
            ComparisonOperation.OpType.GREATER_THAN to ">",
            ComparisonOperation.OpType.LESS_THAN to "<",
            ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL to ">=",
            ComparisonOperation.OpType.LESS_THAN_OR_EQUAL to "<="
        )) {
            Assertions.assertEquals(
                "return `value` $symbol 1",
                writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT) { _, params ->
                    params[0].compare(op, ExpressionDef.constant(1)).returning()
                }
            )
        }
    }

    @Test
    fun writeNegation() {
        Assertions.assertEquals(
            """
            return -(`value` + 1)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT, TypeDef.Primitive.INT) { _, params ->
                params[0].math(OpType.ADDITION, ExpressionDef.constant(1))
                    .math(MathUnaryOperation.OpType.NEGATE)
                    .returning()
            }
        )
    }

    @Test
    fun writeNegatedCondition() {
        Assertions.assertEquals(
            """
            return !(`value` == null || `value` != null)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.OBJECT) { _, params ->
                params[0].isNull().or(params[0].isNonNull()).isFalse().returning()
            }
        )
    }

    @Test
    fun writeNegatedValue() {
        Assertions.assertEquals(
            """
            return !`value`
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.BOOLEAN) { _, params ->
                params[0].isFalse().returning()
            }
        )
    }

    @Test
    fun writeGetClassAndHashCode() {
        Assertions.assertEquals(
            """
            return `value`.javaClass
            """.trimIndent(),
            writeBody(ClassTypeDef.of(Class::class.java), TypeDef.OBJECT) { _, params ->
                params[0].invokeGetClass().returning()
            }
        )
        Assertions.assertEquals(
            """
            return `value`.hashCode()
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT, TypeDef.OBJECT) { _, params ->
                params[0].invokeHashCode().returning()
            }
        )
    }

    @Test
    fun writeHashCodeOfAnArray() {
        Assertions.assertEquals(
            """
            return `value`.contentHashCode()
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT, TypeDef.STRING.array()) { _, params ->
                params[0].invokeHashCode().returning()
            }
        )
        Assertions.assertEquals(
            """
            return `value`.contentDeepHashCode()
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT, TypeDef.STRING.array(2)) { _, params ->
                params[0].invokeHashCode().returning()
            }
        )
    }

    @Test
    fun writeStructuralEquality() {
        Assertions.assertEquals(
            """
            return `value` == "other"
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING) { _, params ->
                params[0].equalsStructurally(ExpressionDef.constant("other")).returning()
            }
        )
        Assertions.assertEquals(
            """
            return `value` != "other"
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING) { _, params ->
                params[0].notEqualsStructurally(ExpressionDef.constant("other")).returning()
            }
        )
    }

    @Test
    fun writeStructuralEqualityOfArrays() {
        val other = TypeDef.STRING.array().instantiate(ExpressionDef.constant("a"))
        Assertions.assertEquals(
            """
            return `value`.contentEquals(arrayOf<String>("a"))
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING.array()) { _, params ->
                params[0].equalsStructurally(other).returning()
            }
        )
        Assertions.assertEquals(
            """
            return !`value`.contentEquals(arrayOf<String>("a"))
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING.array()) { _, params ->
                params[0].notEqualsStructurally(other).returning()
            }
        )
    }

    @Test
    fun writeStructuralEqualityOfNestedArrays() {
        val other = TypeDef.STRING.array(2).instantiate(0)
        Assertions.assertEquals(
            """
            return `value`.contentDeepEquals(arrayOfNulls<Array<String>>(0))
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING.array(2)) { _, params ->
                params[0].equalsStructurally(other).returning()
            }
        )
        Assertions.assertEquals(
            """
            return !`value`.contentDeepEquals(arrayOfNulls<Array<String>>(0))
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING.array(2)) { _, params ->
                params[0].notEqualsStructurally(other).returning()
            }
        )
    }

    @Test
    fun writeReferentialEquality() {
        Assertions.assertEquals(
            """
            return `value` === "other"
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING) { _, params ->
                params[0].equalsReferentially(ExpressionDef.constant("other")).returning()
            }
        )
        Assertions.assertEquals(
            """
            return `value` !== "other"
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING) { _, params ->
                params[0].notEqualsReferentially(ExpressionDef.constant("other")).returning()
            }
        )
    }

    @Test
    fun writeNestedConcatenation() {
        Assertions.assertEquals(
            """
            return "a" + ("b" + "c")
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                ExpressionDef.StringConcatenation(
                    ExpressionDef.constant("a"),
                    ExpressionDef.StringConcatenation(ExpressionDef.constant("b"), ExpressionDef.constant("c"))
                ).returning()
            }
        )
    }

    @Test
    fun writeCallOnAConcatenationTarget() {
        Assertions.assertEquals(
            """
            return ("a" + "b").trim()
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                ExpressionDef.StringConcatenation(ExpressionDef.constant("a"), ExpressionDef.constant("b"))
                    .invoke("trim", TypeDef.STRING)
                    .returning()
            }
        )
    }

    @Test
    fun writeShiftsAndXorAsInfixFunctions() {
        for ((op, name) in listOf(
            OpType.BITWISE_XOR to "xor",
            OpType.BITWISE_LEFT_SHIFT to "shl",
            OpType.BITWISE_RIGHT_SHIFT to "shr",
            OpType.BITWISE_UNSIGNED_RIGHT_SHIFT to "ushr"
        )) {
            Assertions.assertEquals(
                "return `value` $name 2",
                writeBody(TypeDef.Primitive.INT, TypeDef.Primitive.INT) { _, params ->
                    params[0].math(op, ExpressionDef.constant(2)).returning()
                }
            )
        }
    }

    @Test
    fun writeSubtractionOfASubtraction() {
        // `-` is left-associative, so only the right operand needs the parentheses back
        Assertions.assertEquals(
            """
            return 1 - (2 - 3)
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                ExpressionDef.constant(1)
                    .math(
                        OpType.SUBTRACTION,
                        ExpressionDef.constant(2).math(OpType.SUBTRACTION, ExpressionDef.constant(3))
                    )
                    .returning()
            }
        )
        Assertions.assertEquals(
            """
            return 1 - 2 - 3
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                ExpressionDef.constant(1)
                    .math(OpType.SUBTRACTION, ExpressionDef.constant(2))
                    .math(OpType.SUBTRACTION, ExpressionDef.constant(3))
                    .returning()
            }
        )
    }

    @Test
    fun writeModulusInsideMultiplication() {
        Assertions.assertEquals(
            """
            return 2 * 3 % 4
            """.trimIndent(),
            writeBody(TypeDef.Primitive.INT) { _, _ ->
                ExpressionDef.constant(2)
                    .math(OpType.MULTIPLICATION, ExpressionDef.constant(3))
                    .math(OpType.MODULUS, ExpressionDef.constant(4))
                    .returning()
            }
        )
    }

    @Test
    fun writeDivisionAsACallTarget() {
        Assertions.assertEquals(
            """
            return (100 / `value`).toString()
            """.trimIndent(),
            writeBody(TypeDef.STRING, TypeDef.Primitive.INT) { _, params ->
                ExpressionDef.constant(100)
                    .math(OpType.DIVISION, params[0])
                    .invoke("toString", TypeDef.STRING)
                    .returning()
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
            return writer.toString().lines()
                .dropWhile { !it.contains("fun run") }
                .drop(1)
                .takeWhile { !it.trim().startsWith("}") }
                .joinToString("\n") { it.trim() }
        }
    }
}
