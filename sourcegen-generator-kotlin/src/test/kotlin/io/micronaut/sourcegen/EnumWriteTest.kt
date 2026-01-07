package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringWriter
import java.util.regex.Pattern
import javax.lang.model.element.Modifier

class EnumWriteTest {
    @Test
    @Throws(IOException::class)
    fun writeSimpleEnum() {
        val enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .build()
        val result = writeEnum(enumDef)

        val expected = """
        package test

        public enum class Status {
          ACTIVE,
          IN_PROGRESS,
          DELETED,
        }

        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    fun testExceptions() {
        Assertions.assertThrows(
            IllegalArgumentException::class.java
        ) { EnumDef.builder("test.Status").addEnumConstant("active").build() }
        Assertions.assertThrows(
            IllegalArgumentException::class.java
        ) { EnumDef.builder("test.Status").addEnumConstant("9in progress", ExpressionDef.constant(1)).build() }
    }

    @Test
    @Throws(IOException::class)
    fun writeComplexEnumConstant() {
        val enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE", ExpressionDef.constant(2))
            .addEnumConstant("IN_PROGRESS", ExpressionDef.constant(1))
            .addEnumConstant("DELETED", ExpressionDef.constant(0))
            .addField(FieldDef.builder("intValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build()
        val result = writeEnum(enumDef)

        val expected = """
        package test

        import kotlin.Int

        public enum class Status {
          ACTIVE(2),
          IN_PROGRESS(1),
          DELETED(0),
          ;

          public var intValue: Int

          private constructor(intValue: Int) {
            this. intValue = intValue
          }
        }
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeComplexEnumConstant2() {
        val enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE", ExpressionDef.constant(2), ExpressionDef.trueValue())
            .addEnumConstant("IN_PROGRESS", ExpressionDef.constant(1), ExpressionDef.trueValue())
            .addEnumConstant("DELETED", ExpressionDef.constant(0), ExpressionDef.falseValue())
            .addField(FieldDef.builder("intValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addField(FieldDef.builder("boolValue").ofType(TypeDef.Primitive.BOOLEAN).addModifiers(Modifier.PUBLIC).build())
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build()
        val result = writeEnum(enumDef)

        val expected = """
        package test

        import kotlin.Boolean
        import kotlin.Int

        public enum class Status {
          ACTIVE(2, true),
          IN_PROGRESS(1, true),
          DELETED(0, false),
          ;

          public var intValue: Int

          public var boolValue: Boolean

          private constructor(intValue: Int, boolValue: Boolean) {
            this. intValue = intValue
            this. boolValue = boolValue
          }
        }
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeComplexEnumWithProperty() {
        val enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .addProperty(PropertyDef.builder("strValue").ofType(TypeDef.STRING).build())
            .build()
        val generator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(enumDef, writer)
            result = writer.toString()
        }

        val expected = """
        package test

        import kotlin.String

        public enum class Status public constructor(
          public var strValue: String,
        ) {
          ACTIVE,
          IN_PROGRESS,
          DELETED,
          ;
        }
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeComplexEnumWithFieldMethod() {
        val enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .addField(FieldDef.builder("strValue").ofType(TypeDef.STRING).build())
            .addMethod(
                MethodDef.builder("getValue")
                    .returns(TypeDef.STRING)
                    .addModifiers(Modifier.PUBLIC)
                    .addStatement(StatementDef.Return(ExpressionDef.constant("value")))
                    .build()
            )
            .build()
        val result = writeEnum(enumDef)

        val expected = """
        package test

        import kotlin.String

        public enum class Status {
          ACTIVE,
          IN_PROGRESS,
          DELETED,
          ;

          public var strValue: String

          public fun getValue(): String {
            return "value"
          }
        }
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }


    @Throws(IOException::class)
    private fun writeEnum(enumDef: EnumDef): String {
        val generator: KotlinPoetSourceGenerator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(enumDef, writer)
            result = writer.toString()
        }
        // The regex will skip the imports and make sure it is a record
        val ENUM_REGEX = Pattern.compile(
            "package test([\\s\\S]+)\\s+" +
                    "public enum class " + enumDef.simpleName + " \\{\\s+" +
                    "([\\s\\S]+)\\s+}\\s+"
        )
        val matcher = ENUM_REGEX.matcher(result)
        if (!matcher.matches()) {
            Assert.fail("Expected enum to match regex: \n$ENUM_REGEX\nbut is: \n$result")
        }
        return matcher.group(0)
    }
}
