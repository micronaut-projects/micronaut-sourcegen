package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.KotlinCompileAssertions.writeBody
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Kotlin has no primitive casts and no numeric widening, so every primitive `Cast` is a conversion
 * function, and each primitive has an array type and an array factory of its own.
 */
class ConversionWriteTest {

    /**
     * A primitive cast is written as the conversion function of the target type. Char has no numeric conversions of
     * its own, so its code is taken first, and only Int declares toChar, so the others go through it.
     */
    @ParameterizedTest(name = "a {0} cast to {1} is written as value{2}")
    @CsvSource(
        "long, byte, .toByte()",
        "long, short, .toShort()",
        "long, int, .toInt()",
        "long, float, .toFloat()",
        "long, double, .toDouble()",
        "int, long, .toLong()",
        "char, int, .code",
        "char, long, .code.toLong()",
        "int, char, .toChar()",
        "long, char, .toInt().toChar()"
    )
    fun writePrimitiveCastsAsConversionFunctions(from: String, to: String, conversion: String) {
        val target = TypeDef.primitive(to)
        Assertions.assertEquals(
            "return `value`$conversion",
            writeBody(target, TypeDef.primitive(from)) { _, params -> params[0].cast(target).returning() }
        )
    }

    @Test
    fun writeNestedCastsKeepOnlyTheLastReferenceCast() {
        Assertions.assertEquals(
            "return `value` as String",
            writeBody(TypeDef.STRING, TypeDef.OBJECT) { _, params ->
                params[0].cast(TypeDef.OBJECT).cast(TypeDef.STRING).returning()
            }
        )
    }

    @Test
    fun writeNestedCastsKeepAPrimitiveNarrowing() {
        // Narrowing to Int and widening back is not the same as converting straight to Long,
        // so the inner conversion has to survive
        Assertions.assertEquals(
            "return `value`.toInt().toLong()",
            writeBody(TypeDef.Primitive.LONG, TypeDef.Primitive.DOUBLE) { _, params ->
                params[0].cast(TypeDef.Primitive.INT).cast(TypeDef.Primitive.LONG).returning()
            }
        )
    }

    @Test
    fun writeCastOfANegativeConstant() {
        Assertions.assertEquals(
            "return (-1).toLong()",
            writeBody(TypeDef.Primitive.LONG) { _, _ ->
                ExpressionDef.constant(-1).cast(TypeDef.Primitive.LONG).returning()
            }
        )
    }

    /** Each primitive has an array type of its own, which a sized array is created as. */
    @ParameterizedTest(name = "a sized array of {0} is created as {1}(2)")
    @CsvSource(
        "byte, ByteArray",
        "short, ShortArray",
        "char, CharArray",
        "int, IntArray",
        "long, LongArray",
        "float, FloatArray",
        "double, DoubleArray",
        "boolean, BooleanArray"
    )
    fun writePrimitiveArrayTypes(primitive: String, arrayType: String) {
        val type = TypeDef.primitive(primitive)
        Assertions.assertEquals(
            "return $arrayType(2)",
            writeBody(type.array()) { _, _ -> type.array().instantiate(2).returning() }
        )
    }

    /** Each primitive has an array factory of its own, which an initialized array is created with. */
    @ParameterizedTest(name = "an initialized array of {0} is created with {1}")
    @CsvSource(
        "byte, byteArrayOf",
        "short, shortArrayOf",
        "char, charArrayOf",
        "int, intArrayOf",
        "long, longArrayOf",
        "float, floatArrayOf",
        "double, doubleArrayOf",
        "boolean, booleanArrayOf"
    )
    fun writePrimitiveArrayFactories(primitive: String, factory: String) {
        val type = TypeDef.primitive(primitive)
        val value: Any = if (type == TypeDef.Primitive.BOOLEAN) true else 1
        val body = writeBody(type.array()) { _, _ ->
            type.array().instantiate(ExpressionDef.Constant(type, value)).returning()
        }
        Assertions.assertTrue(body.startsWith("return $factory("), "was: $body")
    }

    @Test
    fun writeNestedArrayCreation() {
        // `componentType` is always the innermost type, so a second dimension has to be put back
        Assertions.assertEquals(
            "return (arrayOfNulls<Array<String>>(2) as Array<Array<String>>)",
            writeBody(TypeDef.STRING.array(2)) { _, _ -> TypeDef.STRING.array(2).instantiate(2).returning() }
        )
        Assertions.assertEquals(
            "return (arrayOfNulls<IntArray>(2) as Array<IntArray>)",
            writeBody(TypeDef.Primitive.INT.array(2)) { _, _ ->
                TypeDef.Primitive.INT.array(2).instantiate(2).returning()
            }
        )
    }

    /** A constant of a boxed type is written as the literal of its primitive. */
    @ParameterizedTest(name = "the {0} constant {1} is written {2}")
    @MethodSource("boxedConstants")
    fun writeBoxedConstants(type: Class<*>, value: Any, literal: String) {
        val boxed = ClassTypeDef.of(type)
        Assertions.assertEquals(
            "return $literal",
            writeBody(boxed) { _, _ -> ExpressionDef.Constant(boxed, value).returning() }
        )
    }

    /** A char constant that has an escape sequence is written with it. */
    @ParameterizedTest(name = "{0} is written as the char literal ''{1}''")
    @MethodSource("escapedCharacters")
    fun writeEscapedCharacterConstants(value: Char, escaped: String) {
        Assertions.assertEquals(
            "return '$escaped'",
            writeBody(TypeDef.Primitive.CHAR) { _, _ -> ExpressionDef.constant(value).returning() }
        )
    }

    /**
     * A number or a char cast to the box of another is unboxed, converted and boxed, as the bytecode converts it,
     * and is not checked as the box: `value.toLong()`.
     */
    @ParameterizedTest(name = "a {0} cast to the box of another number is converted")
    @MethodSource("numericCastsToAnotherBox")
    fun numberCastToTheBoxOfAnotherIsConverted(source: TypeDef, target: TypeDef, argument: Any, expected: Any) {
        assertEquals(expected, runMethod("test.NumericBoxCast", target, listOf(source), argument) { _, p ->
            p[0].cast(target).returning()
        })
    }

    /** A reference branch of a conditional typed as a primitive is converted to it: through Number, as the bytecode. */
    @ParameterizedTest(name = "a {0} branch of a conditional typed double is converted")
    @MethodSource("referenceBranchesOfADoubleConditional")
    fun referenceBranchOfAPrimitiveConditionalIsConverted(branch: TypeDef, value: Any, expected: Any) {
        assertEquals(expected, runMethod("test.ReferenceBranch", TypeDef.Primitive.DOUBLE,
            listOf(TypeDef.Primitive.BOOLEAN, branch, TypeDef.Primitive.INT), true, value, 3) { _, p ->
            ExpressionDef.IfElse(p[0].isTrue, p[1], p[2], TypeDef.Primitive.DOUBLE).returning()
        })
    }

    @Test
    fun numberCastToCharIsCheckedAsACharacter() {
        val outcome = KotlinCompileAssertions.outcomeOf {
            runMethod("test.NumberToChar", TypeDef.Primitive.CHAR, listOf(ClassTypeDef.of(Number::class.java)), 1000) { _, p ->
                p[0].cast(TypeDef.Primitive.CHAR).returning()
            }
        }
        assertEquals(ClassCastException::class.java, outcome)
    }

    @Test
    fun primitiveArgumentWidensToTheParameterType() {
        val toHex = java.lang.Long::class.java.getMethod("toHexString", Long::class.javaPrimitiveType)
        val sqrt = Math::class.java.getMethod("sqrt", Double::class.javaPrimitiveType)
        val toBinary = Integer::class.java.getMethod("toBinaryString", Int::class.javaPrimitiveType)
        val def = ClassDef.builder("test.WideningArguments").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("hex").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(String::class.java)
                .build { _, p -> ClassTypeDef.of(java.lang.Long::class.java).invokeStatic(toHex, p[0]).returning() })
            .addMethod(MethodDef.builder("root").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.Primitive.DOUBLE)
                .build { _, p -> ClassTypeDef.of(Math::class.java).invokeStatic(sqrt, p[0]).returning() })
            .addMethod(MethodDef.builder("rootOfSixteen").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.DOUBLE)
                .build { _, _ -> ClassTypeDef.of(Math::class.java).invokeStatic(sqrt, ExpressionDef.constant(16)).returning() })
            .addMethod(MethodDef.builder("binary").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.CHAR).returns(String::class.java)
                .build { _, p -> ClassTypeDef.of(Integer::class.java).invokeStatic(toBinary, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("ff", cls.getMethod("hex", Int::class.javaPrimitiveType).invoke(instance, 255))
            assertEquals(4.0, cls.getMethod("root", Int::class.javaPrimitiveType).invoke(instance, 16))
            assertEquals(4.0, cls.getMethod("rootOfSixteen").invoke(instance))
            assertEquals("1000001", cls.getMethod("binary", Char::class.javaPrimitiveType).invoke(instance, 'A'))
        }
    }

    @Test
    fun primitiveReturnLocalAndFieldWidenToTheirType() {
        val total = FieldDef.builder("total", TypeDef.Primitive.LONG).addModifiers(Modifier.PRIVATE).build()
        val local = VariableDef.Local("widened", TypeDef.Primitive.LONG)
        val def = ClassDef.builder("test.WideningAssignments").addModifiers(Modifier.PUBLIC).addField(total)
            .addMethod(MethodDef.builder("returned").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.Primitive.LONG)
                .build { _, p -> p[0].returning() })
            .addMethod(MethodDef.builder("local").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.Primitive.LONG)
                .build { _, p -> StatementDef.multi(StatementDef.DefineAndAssign(local, p[0]), local.returning()) })
            .addMethod(MethodDef.builder("field").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.Primitive.LONG)
                .build { self, p -> StatementDef.multi(self.field(total).put(p[0]), self.field(total).returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            for (name in listOf("returned", "local", "field")) {
                assertEquals(7L, cls.getMethod(name, Int::class.javaPrimitiveType).invoke(instance, 7), name)
            }
        }
    }

    @Test
    fun arrayAssignedToAnArrayOfItsSupertype() {
        val values = FieldDef.builder("values", TypeDef.OBJECT.array()).addModifiers(Modifier.PRIVATE).build()
        val local = VariableDef.Local("objects", TypeDef.OBJECT.array())
        val def = ClassDef.builder("test.CovariantArrays").addModifiers(Modifier.PUBLIC).addField(values)
            .addMethod(MethodDef.builder("local").addModifiers(Modifier.PUBLIC).addParameter("texts", TypeDef.STRING.array()).returns(TypeDef.Primitive.INT)
                .build { _, p -> StatementDef.multi(StatementDef.DefineAndAssign(local, p[0]), local.arrayElement(0).invokeHashCode().returning()) })
            .addMethod(MethodDef.builder("field").addModifiers(Modifier.PUBLIC).addParameter("texts", TypeDef.STRING.array()).returns(TypeDef.VOID)
                .build { self, p -> self.field(values).put(p[0]) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("a".hashCode(), cls.getMethod("local", Array<String>::class.java).invoke(instance, arrayOf("a")))
            cls.getMethod("field", Array<String>::class.java).invoke(instance, arrayOf("a"))
        }
    }

    @Test
    fun byteAndShortArithmeticKeepsTheModelType() {
        val def = ClassDef.builder("test.SmallArithmetic").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("add").addModifiers(Modifier.PUBLIC).addParameter("a", TypeDef.Primitive.BYTE).addParameter("b", TypeDef.Primitive.BYTE).returns(TypeDef.Primitive.BYTE)
                .build { _, p -> p[0].math(OpType.ADDITION, p[1]).returning() })
            .addMethod(MethodDef.builder("mask").addModifiers(Modifier.PUBLIC).addParameter("a", TypeDef.Primitive.BYTE).addParameter("b", TypeDef.Primitive.BYTE).returns(TypeDef.Primitive.BYTE)
                .build { _, p -> p[0].math(OpType.BITWISE_AND, p[1]).returning() })
            .addMethod(MethodDef.builder("negate").addModifiers(Modifier.PUBLIC).addParameter("a", TypeDef.Primitive.SHORT).returns(TypeDef.Primitive.SHORT)
                .build { _, p -> p[0].math(ExpressionDef.MathUnaryOperation.OpType.NEGATE).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val b = Byte::class.javaPrimitiveType
            assertEquals(7.toByte(), cls.getMethod("add", b, b).invoke(instance, 3.toByte(), 4.toByte()))
            assertEquals(2.toByte(), cls.getMethod("mask", b, b).invoke(instance, 6.toByte(), 3.toByte()))
            assertEquals((-5).toShort(), cls.getMethod("negate", Short::class.javaPrimitiveType).invoke(instance, 5.toShort()))
        }
    }

    @Test
    fun conditionalAndEnumConstantArgumentsWidenToTheirType() {
        val weight = FieldDef.builder("weight", TypeDef.Primitive.DOUBLE).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val enumDef = io.micronaut.sourcegen.model.EnumDef.builder("test.Weights").addModifiers(Modifier.PUBLIC)
            .addEnumConstant("LIGHT", ExpressionDef.constant(1))
            .addField(weight).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(MethodDef.builder("weight").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.DOUBLE)
                .build { self, _ -> self.field(weight).returning() })
            .build()
        val def = ClassDef.builder("test.WideningConditional").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .addParameter("small", TypeDef.Primitive.INT).addParameter("large", TypeDef.Primitive.LONG).returns(TypeDef.Primitive.LONG)
                .build { _, p -> ExpressionDef.IfElse(p[0].isTrue(), p[1], p[2], TypeDef.Primitive.LONG).returning() })
            .build()
        compile(enumDef, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(3L, cls.getMethod("pick", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .invoke(cls.getConstructor().newInstance(), true, 3, 4L))
            val light = loader.loadClass(enumDef.name).getField("LIGHT").get(null)
            assertEquals(1.0, light.javaClass.getMethod("weight").invoke(light))
        }
    }

    @Test
    fun castOfObjectToIntConvertsAnyNumber() {
        // The Java source writes ((Number) value).intValue(), the bytecode writers unbox through Number
        val result = runMethod("test.ObjectToInt", TypeDef.Primitive.INT, listOf(TypeDef.OBJECT), 2.5) { _, p ->
            p[0].cast(TypeDef.Primitive.INT).returning()
        }
        assertEquals(2, result)
    }

    @Test
    fun doubleToByteCastCompiles() {
        val result = runMethod("test.DoubleToByte", TypeDef.Primitive.BYTE, listOf(TypeDef.Primitive.DOUBLE), 2.5) { _, p ->
            p[0].cast(TypeDef.Primitive.BYTE).returning()
        }
        assertEquals(2.toByte(), result)
    }

    @Test
    fun byteArithmeticIsNarrowedToByte() {
        val result = runMethod("test.ByteSum", TypeDef.Primitive.BYTE, listOf(TypeDef.Primitive.BYTE, TypeDef.Primitive.BYTE), 100.toByte(), 100.toByte()) { _, p ->
            p[0].math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, p[1]).returning()
        }
        assertEquals((-56).toByte(), result)
    }

    @Test
    fun longShiftCompiles() {
        val result = runMethod("test.LongShift", TypeDef.Primitive.LONG, listOf(TypeDef.Primitive.LONG, TypeDef.Primitive.INT), 1L, 40) { _, p ->
            p[0].math(ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT, p[1]).returning()
        }
        assertEquals(1L shl 40, result)
    }

    @Test
    fun conditionalOfAWiderDeclaredTypeCompiles() {
        val result = runMethod("test.WidenedConditional", TypeDef.Primitive.LONG,
            listOf(TypeDef.Primitive.BOOLEAN, TypeDef.Primitive.INT, TypeDef.Primitive.INT), true, 5, 6) { _, p ->
            ExpressionDef.IfElse(p[0].isTrue, p[1], p[2], TypeDef.Primitive.LONG).returning()
        }
        assertEquals(5L, result)
    }

    /** A static method called for another primitive result than its own: Java converts the result it returns. */
    @Test
    fun resultOfAStaticCallIsConvertedToTheRequestedType() {
        val result = runMethod("test.MaxAsLong", TypeDef.Primitive.LONG, listOf(TypeDef.Primitive.INT, TypeDef.Primitive.INT), 3, 7) { _, p ->
            ClassTypeDef.of(Math::class.java).invokeStatic("max", TypeDef.Primitive.LONG, p[0], p[1]).returning()
        }
        assertEquals(7L, result)
    }

    @Test
    fun castOfTheSmallestIntegerConstantToAChar() {
        val result = runMethod("test.SmallestIntegerAsChar", TypeDef.Primitive.CHAR, listOf()) { _, _ ->
            ExpressionDef.constant(Integer.MIN_VALUE as Any).cast(TypeDef.Primitive.CHAR).returning()
        }
        assertEquals('\u0000', result)
    }

    /**
     * The arithmetic of a byte or a short is of the type the model declares for it, whatever the value is written to:
     * returned as an `Object` it is a `Short`, concatenated, compared or widened it is the narrowed value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("narrowArithmetic")
    fun narrowArithmeticKeepsItsType(returns: TypeDef, operand: TypeDef, op: OpType, left: Any, right: Any, expected: Any) {
        assertEquals(expected, runMethod("test.NarrowArithmetic", returns, listOf(operand, operand), left, right) { _, p ->
            val operation = p[0].math(op, p[1])
            when (returns) {
                TypeDef.STRING -> ExpressionDef.constant("r=").stringConcat(operation).returning()
                TypeDef.Primitive.BOOLEAN -> operation.compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, ExpressionDef.constant(0)).returning()
                else -> operation.cast(returns).returning()
            }
        })
    }

    companion object {
        @JvmStatic
        fun narrowArithmetic(): List<Arguments> = listOf(
            arguments(named("short - short as an Object is a Short", TypeDef.OBJECT), TypeDef.Primitive.SHORT, OpType.SUBTRACTION,
                Short.MIN_VALUE, 1.toShort(), Short.MAX_VALUE),
            arguments(named("short & short as an Object is a Short", TypeDef.OBJECT), TypeDef.Primitive.SHORT, OpType.BITWISE_AND,
                300.toShort(), 300.toShort(), 300.toShort()),
            arguments(named("byte >>> byte as an Object is a Byte", TypeDef.OBJECT), TypeDef.Primitive.BYTE, OpType.BITWISE_UNSIGNED_RIGHT_SHIFT,
                (-128).toByte(), 4.toByte(), (-8).toByte()),
            arguments(named("short << short as a long is the short", TypeDef.Primitive.LONG), TypeDef.Primitive.SHORT, OpType.BITWISE_LEFT_SHIFT,
                300.toShort(), 12.toShort(), -16384L),
            arguments(named("byte << byte concatenated is the byte", TypeDef.STRING), TypeDef.Primitive.BYTE, OpType.BITWISE_LEFT_SHIFT,
                100.toByte(), 4.toByte(), "r=64"),
            arguments(named("byte + byte compared to 0 is the byte", TypeDef.Primitive.BOOLEAN), TypeDef.Primitive.BYTE, OpType.ADDITION,
                100.toByte(), 100.toByte(), false)
        )

        @JvmStatic
        fun boxedConstants(): List<Arguments> = listOf(
            arguments(named("Long", java.lang.Long::class.java), 5, "5L"),
            arguments(named("Double", java.lang.Double::class.java), 5, "5.0"),
            arguments(named("Float", java.lang.Float::class.java), 5, "5.0f"),
            arguments(named("Byte", java.lang.Byte::class.java), 5, "5"),
            arguments(named("Short", java.lang.Short::class.java), 5, "5"),
            arguments(named("Character", Character::class.java), 'c', "'c'")
        )

        @JvmStatic
        fun escapedCharacters(): List<Arguments> = listOf(
            arguments(named("a line feed", '\n'), "\\n"),
            arguments(named("a tab", '\t'), "\\t"),
            arguments(named("a carriage return", '\r'), "\\r"),
            arguments(named("a backspace", '\b'), "\\b"),
            arguments(named("a form feed", '\u000C'), "\\f"),
            arguments(named("a single quote", '\''), "\\'"),
            arguments(named("a backslash", '\\'), "\\\\"),
            arguments(named("a control character", '\u0001'), "\\u0001"),
            arguments(named("an unpaired high surrogate", '\ud83d'), "\\ud83d"),
            arguments(named("an unpaired low surrogate", '\ude00'), "\\ude00")
        )

        @JvmStatic
        fun referenceBranchesOfADoubleConditional(): List<Arguments> = listOf(
            arguments(named("Object", TypeDef.OBJECT), 5L, 5.0),
            arguments(named("Number", ClassTypeDef.of(Number::class.java)), 2.5f, 2.5),
            arguments(named("Integer", ClassTypeDef.of(Integer::class.java)), 7, 7.0)
        )

        @JvmStatic
        fun numericCastsToAnotherBox(): List<Arguments> = listOf(
            arguments(named("Byte to Long", ClassTypeDef.of(java.lang.Byte::class.java)), ClassTypeDef.of(java.lang.Long::class.java),
                (-5).toByte(), -5L),
            arguments(named("Integer to Character", ClassTypeDef.of(Integer::class.java)), ClassTypeDef.of(Character::class.java),
                1000, 1000.toChar()),
            arguments(named("Character to Integer", ClassTypeDef.of(Character::class.java)), ClassTypeDef.of(Integer::class.java),
                'a', 97),
            arguments(named("Double to Integer", ClassTypeDef.of(java.lang.Double::class.java)), ClassTypeDef.of(Integer::class.java),
                2.9, 2),
            arguments(named("Long to Short", ClassTypeDef.of(java.lang.Long::class.java)), ClassTypeDef.of(java.lang.Short::class.java),
                70000L, 4464.toShort()),
            arguments(named("long to Float", TypeDef.Primitive.LONG), ClassTypeDef.of(java.lang.Float::class.java),
                1099511627776L, 1.09951163E12f),
            arguments(named("int to Character", TypeDef.Primitive.INT), ClassTypeDef.of(Character::class.java), 65, 'A'),
            arguments(named("char to Double", TypeDef.Primitive.CHAR), ClassTypeDef.of(java.lang.Double::class.java), 'a', 97.0)
        )
    }
}
