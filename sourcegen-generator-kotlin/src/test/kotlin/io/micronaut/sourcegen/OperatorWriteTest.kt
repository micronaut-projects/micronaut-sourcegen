package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.outcomeOf
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.KotlinCompileAssertions.writeBody
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

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

    @ParameterizedTest(name = "the comparison {0} is written {1}")
    @CsvSource(
        "EQUAL_TO, ==",
        "NOT_EQUAL_TO, !=",
        "GREATER_THAN, >",
        "LESS_THAN, <",
        "GREATER_THAN_OR_EQUAL, >=",
        "LESS_THAN_OR_EQUAL, <="
    )
    fun writeComparisons(op: ComparisonOperation.OpType, symbol: String) {
        Assertions.assertEquals(
            "return `value` $symbol 1",
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT) { _, params ->
                params[0].compare(op, ExpressionDef.constant(1)).returning()
            }
        )
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
            return `value`.contentDeepEquals((arrayOfNulls<Array<String>>(0) as Array<Array<String>>))
            """.trimIndent(),
            writeBody(TypeDef.Primitive.BOOLEAN, TypeDef.STRING.array(2)) { _, params ->
                params[0].equalsStructurally(other).returning()
            }
        )
        Assertions.assertEquals(
            """
            return !`value`.contentDeepEquals((arrayOfNulls<Array<String>>(0) as Array<Array<String>>))
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
            return (("a" + "b") as java.lang.String).trim()
            """.trimIndent(),
            writeBody(TypeDef.STRING) { _, _ ->
                ExpressionDef.StringConcatenation(ExpressionDef.constant("a"), ExpressionDef.constant("b"))
                    .invoke("trim", TypeDef.STRING)
                    .returning()
            }
        )
    }

    @ParameterizedTest(name = "{0} is written as the infix function {1}")
    @CsvSource(
        "BITWISE_XOR, xor",
        "BITWISE_LEFT_SHIFT, shl",
        "BITWISE_RIGHT_SHIFT, shr",
        "BITWISE_UNSIGNED_RIGHT_SHIFT, ushr"
    )
    fun writeShiftsAndXorAsInfixFunctions(op: OpType, name: String) {
        Assertions.assertEquals(
            "return `value` $name 2",
            writeBody(TypeDef.Primitive.INT, TypeDef.Primitive.INT) { _, params ->
                params[0].math(op, ExpressionDef.constant(2)).returning()
            }
        )
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

    @Test
    fun equalToOfObjectsComparesReferences() {
        val def = ClassDef.builder("test.ReferenceComparison").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("same").addModifiers(Modifier.PUBLIC)
                .addParameter("a", String::class.java).addParameter("b", String::class.java).returns(TypeDef.Primitive.BOOLEAN)
                .build { _, p -> p[0].compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val first = String(charArrayOf('a'))
            val second = String(charArrayOf('a'))
            assertEquals(false, cls.getMethod("same", String::class.java, String::class.java)
                .invoke(cls.getConstructor().newInstance(), first, second))
        }
    }

    @Test
    fun structuralEqualityOfDifferentPrimitiveTypes() {
        val def = ClassDef.builder("test.MixedEquality").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("sameNumber").addModifiers(Modifier.PUBLIC).addParameter("a", TypeDef.Primitive.INT).addParameter("b", TypeDef.Primitive.LONG)
                .returns(TypeDef.Primitive.BOOLEAN).build { _, p -> p[0].equalsStructurally(p[1]).returning() })
            .addMethod(MethodDef.builder("isA").addModifiers(Modifier.PUBLIC).addParameter("c", TypeDef.Primitive.CHAR)
                .returns(TypeDef.Primitive.BOOLEAN).build { _, p -> p[0].equalsStructurally(ExpressionDef.constant(65)).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(true, cls.getMethod("sameNumber", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(instance, 3, 3L))
            assertEquals(true, cls.getMethod("isA", Char::class.javaPrimitiveType).invoke(instance, 'A'))
        }
    }

    @Test
    fun concatenationWithANonStringLeftOperand() {
        val def = ClassDef.builder("test.LeftConcat").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("number").addModifiers(Modifier.PUBLIC).addParameter("count", TypeDef.Primitive.INT).addParameter("unit", String::class.java)
                .returns(String::class.java).build { _, p -> p[0].stringConcat(p[1]).returning() })
            .addMethod(MethodDef.builder("any").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).addParameter("unit", String::class.java)
                .returns(String::class.java).build { _, p -> p[0].stringConcat(p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("3kg", cls.getMethod("number", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 3, "kg"))
            assertEquals("3kg", cls.getMethod("any", Any::class.java, String::class.java).invoke(instance, 3, "kg"))
        }
    }

    @Test
    fun equalToOfTwoBoxesComparesIdentity() {
        // The Java source and both bytecode writers compare the references, as `==` does in Java
        val result = runMethod("test.BoxIdentity", TypeDef.Primitive.BOOLEAN, listOf(TypeDef.Primitive.INT, TypeDef.Primitive.INT), 1000, 1000) { _, p ->
            p[0].cast(TypeDef.of(Integer::class.java)).compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                p[1].cast(TypeDef.of(Integer::class.java))).returning()
        }
        assertEquals(false, result)
    }

    @Test
    fun concatenationWithANonStringLeftOperandCompiles() {
        val result = runMethod("test.IntPlusString", TypeDef.STRING, listOf(TypeDef.Primitive.INT, TypeDef.STRING), 1, "a") { _, p ->
            p[0].stringConcat(p[1]).returning()
        }
        assertEquals("1a", result)
    }

    @Test
    fun conditionalOperandOfIsIsParenthesized() {
        // (if (flag) a else b) is Int, not if (flag) a else (b is Int)
        val result = runMethod("test.ConditionalIs", TypeDef.Primitive.BOOLEAN,
            listOf(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.LONG, TypeDef.Primitive.LONG), true, 1L, 2L) { _, p ->
            ExpressionDef.IfElse(p[0].isTrue, p[1], p[2], TypeDef.Primitive.LONG).cast(TypeDef.OBJECT)
                .instanceOf(ClassTypeDef.of(java.lang.Long::class.java)).returning()
        }
        assertEquals(true, result)
    }

    /**
     * A conditional as the operand of `||` is written without parentheses: `if (a) b else c || a` is
     * `if (a) b else (c || a)`, not the `(a ? b : c) || a` of the model.
     */
    @Test
    fun conditionalOperandKeepsItsGrouping() {
        val bool: Class<*> = Boolean::class.javaPrimitiveType!!
        val def = ClassDef.builder("test.ConditionalOperand").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", bool).addParameter("b", bool).addParameter("c", bool).returns(bool)
                .build { _, p -> p[0].isTrue.doIfElse(p[1], p[2]).isTrue.or(p[0].isTrue).returning() }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(true, cls.getMethod("call", bool, bool, bool).invoke(cls.getConstructor().newInstance(), true, false, false))
        }
    }

    /**
     * The infix `and`, `or`, `xor`, `shl` of Kotlin share one precedence and group to the left, unlike the operators
     * of Java the parentheses are decided by: `a | b & c` is written `a or b and c`, which is `(a or b) and c`.
     */
    @Test
    fun bitwiseOperandsKeepTheirGrouping() {
        val int: Class<*> = Int::class.javaPrimitiveType!!
        val def = ClassDef.builder("test.BitwiseGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", int).addParameter("b", int).addParameter("c", int).returns(IntArray::class.java)
                .build { _, p ->
                    val a: ExpressionDef = p[0]
                    val b: ExpressionDef = p[1]
                    val c: ExpressionDef = p[2]
                    TypeDef.Primitive.INT.array().instantiate(
                        a.math(OpType.BITWISE_OR, b.math(OpType.BITWISE_AND, c)),
                        a.math(OpType.BITWISE_AND, b.math(OpType.BITWISE_LEFT_SHIFT, c)),
                        a.math(OpType.BITWISE_XOR, b.math(OpType.BITWISE_AND, c))
                    ).returning()
                }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val a = 12
            val b = 10
            val c = 1
            assertArrayEquals(intArrayOf(a or (b and c), a and (b shl c), a xor (b and c)),
                cls.getMethod("call", int, int, int).invoke(cls.getConstructor().newInstance(), a, b, c) as IntArray)
        }
    }

    /**
     * A referential comparison is Java's `==`: two primitives are compared as values of their promoted type, and
     * otherwise the references are, a primitive boxed - which Kotlin's `===` does not take for primitives or unrelated
     * types.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("referentialComparisons")
    fun referentialComparisonIsJavas(left: TypeDef, right: TypeDef, leftValue: Any?, rightValue: Any?, negated: Boolean, expected: Boolean) {
        assertEquals(expected, runMethod("test.ReferentialComparison", TypeDef.Primitive.BOOLEAN, listOf(left, right), leftValue, rightValue) { _, p ->
            (if (negated) p[0].notEqualsReferentially(p[1]) else p[0].equalsReferentially(p[1])).returning()
        })
    }

    /**
     * A structural comparison is Java's `Objects.equals` of two references, and `==` of a primitive and the other value
     * converted to it - which Kotlin's `==` does not take for unrelated types.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralComparisons")
    fun structuralComparisonIsJavas(left: TypeDef, right: TypeDef, leftValue: Any?, rightValue: Any?, negated: Boolean, expected: Boolean) {
        assertEquals(expected, runMethod("test.StructuralComparison", TypeDef.Primitive.BOOLEAN, listOf(left, right), leftValue, rightValue) { _, p ->
            (if (negated) p[0].notEqualsStructurally(p[1]) else p[0].equalsStructurally(p[1])).returning()
        })
    }

    /** `==` of two references casts the right one to the type of the left, which throws for another type. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("castComparisons")
    fun equalToCastsTheRightOperand(left: TypeDef, right: TypeDef, leftValue: Any?, rightValue: Any?, expected: Any?) {
        assertEquals(expected, outcomeOf {
            runMethod("test.CastComparison", TypeDef.Primitive.BOOLEAN, listOf(left, right), leftValue, rightValue) { _, p ->
                p[0].compare(ComparisonOperation.OpType.EQUAL_TO, p[1]).returning()
            }
        })
    }

    @Test
    fun newStringIsAnotherReference() {
        assertEquals(false, runMethod("test.NewString", TypeDef.Primitive.BOOLEAN, listOf(TypeDef.STRING), "abc") { _, p ->
            ClassTypeDef.of(String::class.java).instantiate(p[0]).compare(ComparisonOperation.OpType.EQUAL_TO, p[0]).returning()
        })
    }

    /** The negation of a char is a char, which Kotlin has no unary minus for: `(char) -c`. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("charNegations")
    fun negationOfACharIsAChar(returns: TypeDef, parameter: TypeDef, argument: Any, expected: Any) {
        assertEquals(expected, runMethod("test.CharNegation", returns, listOf(parameter), argument) { _, p ->
            val negated = p[0].math(MathUnaryOperation.OpType.NEGATE)
            when (returns) {
                TypeDef.STRING -> ExpressionDef.constant("").stringConcat(negated).returning()
                TypeDef.OBJECT -> negated.cast(TypeDef.OBJECT).returning()
                else -> negated.returning()
            }
        })
    }

    @Test
    fun negationOfACharConstantIsAChar() {
        assertEquals('ﾟ', runMethod("test.CharConstantNegation", TypeDef.Primitive.CHAR, listOf()) { _, _ ->
            ExpressionDef.constant('a').math(MathUnaryOperation.OpType.NEGATE).returning()
        })
    }

    /** Java concatenates the `toString` of a char array, and `null` as the text "null". */
    @Test
    fun concatenationOfACharArrayIsItsToString() {
        val result = runMethod("test.CharArrayConcat", TypeDef.STRING, listOf(TypeDef.Primitive.CHAR.array(), TypeDef.STRING), charArrayOf('h', 'i'), "x") { _, p ->
            p[0].stringConcat(p[1]).returning()
        } as String
        assertEquals(true, result.startsWith("[C@") && result.endsWith("x"), result)
    }

    @Test
    fun concatenationOfNulls() {
        assertEquals("nullnull", runMethod("test.NullConcat", TypeDef.STRING, listOf()) { _, _ ->
            ExpressionDef.nullValue().stringConcat(ExpressionDef.nullValue()).returning()
        })
    }

    /** `(flag ? "a" : null) == null`, not `flag ? "a" : (null == null)`. */
    @ParameterizedTest(name = "the conditional of {0} checked against null is {1}")
    @CsvSource("true, false", "false, true")
    fun conditionalCheckedAgainstNull(flag: Boolean, expected: Boolean) {
        assertEquals(expected, runMethod("test.ConditionalIsNull", TypeDef.Primitive.BOOLEAN, listOf(TypeDef.Primitive.BOOLEAN), flag) { _, p ->
            ExpressionDef.IfElse(p[0].isTrue, ExpressionDef.constant("a"), ExpressionDef.nullValue(), TypeDef.STRING).isNull().returning()
        })
    }

    companion object {
        private val INT = TypeDef.Primitive.INT
        private val LONG = TypeDef.Primitive.LONG
        private val CHAR = TypeDef.Primitive.CHAR
        private val BYTE = TypeDef.Primitive.BYTE
        private val FLOAT = TypeDef.Primitive.FLOAT
        private val DOUBLE = TypeDef.Primitive.DOUBLE
        private val INTEGER = TypeDef.of(Integer::class.java)
        private val LONG_BOX = TypeDef.of(java.lang.Long::class.java)
        private val DOUBLE_BOX = TypeDef.of(java.lang.Double::class.java)
        private val CHARACTER = TypeDef.of(Character::class.java)
        private val BOOLEAN_BOX = TypeDef.of(java.lang.Boolean::class.java)
        private val NUMBER = TypeDef.of(Number::class.java)
        private val INT_ARRAY = TypeDef.Primitive.INT.array()

        @JvmStatic
        fun referentialComparisons(): List<Arguments> = listOf(
            arguments(named("int == char compares the values", INT), CHAR, 97, 'a', false, true),
            arguments(named("double == int compares the values as doubles", DOUBLE), INT, 2.0, 2, false, true),
            arguments(named("long != float compares the values as floats", LONG), FLOAT, 16777217L, 16777216f, true, false),
            arguments(named("char == byte compares the values", CHAR), BYTE, 'A', 65.toByte(), false, true),
            arguments(named("float != byte compares the values", FLOAT), BYTE, 1.5f, 1.toByte(), true, true),
            arguments(named("int == Integer compares the box of the int", INT), INTEGER, 1000, 1000, false, false),
            arguments(named("Character == int compares the box of the int", CHARACTER), INT, 'a', 97, false, false),
            arguments(named("Integer == Long compares the references", INTEGER), LONG_BOX, 5, 5L, false, false),
            arguments(named("String != Integer compares the references", TypeDef.STRING), INTEGER, "5", 5, true, true),
            arguments(named("int[] == char compares the references", INT_ARRAY), CHAR, intArrayOf(1), 'a', false, false),
            arguments(named("Boolean == Character compares the references", BOOLEAN_BOX), CHARACTER, true, 'a', false, false)
        )

        @JvmStatic
        fun structuralComparisons(): List<Arguments> = listOf(
            arguments(named("Integer equals Character", INTEGER), CHARACTER, 97, 'a', false, false),
            arguments(named("Integer equals Double", INTEGER), DOUBLE_BOX, 1, 1.0, false, false),
            arguments(named("Long not equals Integer", LONG_BOX), INTEGER, 1L, 1, true, true),
            arguments(named("String equals Number", TypeDef.STRING), NUMBER, "1", 1, false, false),
            arguments(named("Boolean equals String", BOOLEAN_BOX), TypeDef.STRING, true, "true", false, false),
            arguments(named("Integer equals int[]", INTEGER), INT_ARRAY, 1, intArrayOf(1), false, false),
            arguments(named("Number not equals float converts the Number to a float", NUMBER), FLOAT, 2.0, 2f, true, false)
        )

        @JvmStatic
        fun castComparisons(): List<Arguments> = listOf(
            arguments(named("Number == String throws", NUMBER), TypeDef.STRING, 5, "5", ClassCastException::class.java),
            arguments(named("String == Object holding a number throws", TypeDef.STRING), TypeDef.OBJECT, "a", 5, ClassCastException::class.java),
            arguments(named("String == Object holding a String compares", TypeDef.STRING), TypeDef.OBJECT, "a", "b", false),
            arguments(named("String == Integer throws", TypeDef.STRING), INTEGER, "a", 5, ClassCastException::class.java)
        )

        @JvmStatic
        fun charNegations(): List<Arguments> = listOf(
            arguments(named("the negation of a char", CHAR), CHAR, 'a', 'ﾟ'),
            arguments(named("the negation of a char as an Object is a Character", TypeDef.OBJECT), CHAR, 'a', 'ﾟ'),
            arguments(named("the negation of a char concatenated is the char", TypeDef.STRING), CHAR, 'a', "ﾟ"),
            arguments(named("the negation of a Character as an Object is a Character", TypeDef.OBJECT), CHARACTER, 'a', 'ﾟ')
        )
    }
}
