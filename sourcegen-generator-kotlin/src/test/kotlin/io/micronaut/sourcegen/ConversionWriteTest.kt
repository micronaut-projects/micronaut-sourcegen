package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * Kotlin has no primitive casts and no numeric widening, so every primitive `Cast` is a conversion
 * function, and each primitive has an array type and an array factory of its own.
 */
class ConversionWriteTest {

    @Test
    fun writeNumericConversions() {
        for ((type, call) in listOf(
            TypeDef.Primitive.BYTE to ".toByte()",
            TypeDef.Primitive.SHORT to ".toShort()",
            TypeDef.Primitive.INT to ".toInt()",
            TypeDef.Primitive.FLOAT to ".toFloat()",
            TypeDef.Primitive.DOUBLE to ".toDouble()"
        )) {
            Assertions.assertEquals(
                "return `value`$call",
                writeBody(type, TypeDef.Primitive.LONG) { _, params -> params[0].cast(type).returning() }
            )
        }
        Assertions.assertEquals(
            "return `value`.toLong()",
            writeBody(TypeDef.Primitive.LONG, TypeDef.Primitive.INT) { _, params ->
                params[0].cast(TypeDef.Primitive.LONG).returning()
            }
        )
    }

    @Test
    fun writeConversionsFromChar() {
        // Char has no numeric conversions of its own, so its code is taken first
        Assertions.assertEquals(
            "return `value`.code",
            writeBody(TypeDef.Primitive.INT, TypeDef.Primitive.CHAR) { _, params ->
                params[0].cast(TypeDef.Primitive.INT).returning()
            }
        )
        Assertions.assertEquals(
            "return `value`.code.toLong()",
            writeBody(TypeDef.Primitive.LONG, TypeDef.Primitive.CHAR) { _, params ->
                params[0].cast(TypeDef.Primitive.LONG).returning()
            }
        )
    }

    @Test
    fun writeConversionsToChar() {
        // Only Int declares toChar, so the others go through it
        Assertions.assertEquals(
            "return `value`.toChar()",
            writeBody(TypeDef.Primitive.CHAR, TypeDef.Primitive.INT) { _, params ->
                params[0].cast(TypeDef.Primitive.CHAR).returning()
            }
        )
        Assertions.assertEquals(
            "return `value`.toInt().toChar()",
            writeBody(TypeDef.Primitive.CHAR, TypeDef.Primitive.LONG) { _, params ->
                params[0].cast(TypeDef.Primitive.CHAR).returning()
            }
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

    @Test
    fun writePrimitiveArrayTypes() {
        for ((type, name) in listOf(
            TypeDef.Primitive.BYTE to "ByteArray",
            TypeDef.Primitive.SHORT to "ShortArray",
            TypeDef.Primitive.CHAR to "CharArray",
            TypeDef.Primitive.INT to "IntArray",
            TypeDef.Primitive.LONG to "LongArray",
            TypeDef.Primitive.FLOAT to "FloatArray",
            TypeDef.Primitive.DOUBLE to "DoubleArray",
            TypeDef.Primitive.BOOLEAN to "BooleanArray"
        )) {
            Assertions.assertEquals(
                "return $name(2)",
                writeBody(type.array()) { _, _ -> type.array().instantiate(2).returning() }
            )
        }
    }

    @Test
    fun writePrimitiveArrayFactories() {
        for ((type, factory) in listOf(
            TypeDef.Primitive.BYTE to "byteArrayOf",
            TypeDef.Primitive.SHORT to "shortArrayOf",
            TypeDef.Primitive.CHAR to "charArrayOf",
            TypeDef.Primitive.INT to "intArrayOf",
            TypeDef.Primitive.LONG to "longArrayOf",
            TypeDef.Primitive.FLOAT to "floatArrayOf",
            TypeDef.Primitive.DOUBLE to "doubleArrayOf",
            TypeDef.Primitive.BOOLEAN to "booleanArrayOf"
        )) {
            val value: Any = if (type == TypeDef.Primitive.BOOLEAN) true else 1
            val body = writeBody(type.array()) { _, _ ->
                type.array().instantiate(ExpressionDef.Constant(type, value)).returning()
            }
            Assertions.assertTrue(body.startsWith("return $factory("), "was: $body")
        }
    }

    @Test
    fun writeNestedArrayCreation() {
        // `componentType` is always the innermost type, so a second dimension has to be put back
        Assertions.assertEquals(
            "return arrayOfNulls<Array<String>>(2)",
            writeBody(TypeDef.STRING.array(2)) { _, _ -> TypeDef.STRING.array(2).instantiate(2).returning() }
        )
        Assertions.assertEquals(
            "return arrayOfNulls<IntArray>(2)",
            writeBody(TypeDef.Primitive.INT.array(2)) { _, _ ->
                TypeDef.Primitive.INT.array(2).instantiate(2).returning()
            }
        )
    }

    @Test
    fun writeBoxedConstants() {
        for ((type, expected) in listOf(
            ClassTypeDef.of(java.lang.Long::class.java) to "5L",
            ClassTypeDef.of(java.lang.Double::class.java) to "5.0",
            ClassTypeDef.of(java.lang.Float::class.java) to "5.0f",
            ClassTypeDef.of(java.lang.Byte::class.java) to "5",
            ClassTypeDef.of(java.lang.Short::class.java) to "5"
        )) {
            Assertions.assertEquals(
                "return $expected",
                writeBody(type) { _, _ -> ExpressionDef.Constant(type, 5).returning() }
            )
        }
        Assertions.assertEquals(
            "return 'c'",
            writeBody(ClassTypeDef.of(Character::class.java)) { _, _ ->
                ExpressionDef.Constant(ClassTypeDef.of(Character::class.java), 'c').returning()
            }
        )
    }

    @Test
    fun writeEscapedCharacterConstants() {
        for ((value, expected) in listOf(
            '\n' to "\\n",
            '\t' to "\\t",
            '\r' to "\\r",
            '\b' to "\\b",
            '\u000C' to "\\f",
            '\'' to "\\'",
            '\\' to "\\\\",
            '\u0001' to "\\u0001"
        )) {
            Assertions.assertEquals(
                "return '$expected'",
                writeBody(TypeDef.Primitive.CHAR) { _, _ -> ExpressionDef.constant(value).returning() }
            )
        }
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
