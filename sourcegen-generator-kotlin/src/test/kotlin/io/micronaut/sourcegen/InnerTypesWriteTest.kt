package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder
import io.micronaut.sourcegen.model.EnumDef.EnumDefBuilder
import io.micronaut.sourcegen.model.InterfaceDef.InterfaceDefBuilder
import io.micronaut.sourcegen.model.RecordDef.RecordDefBuilder
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringWriter
import java.util.regex.Pattern
import javax.lang.model.element.Modifier

class InnerTypesWriteTest {
    /**
     * Writes a class and returns all the contents of the class.
     */
    @Throws(IOException::class)
    fun writeClass(classDef: ObjectDef, classType: String): String {
        val generator: KotlinPoetSourceGenerator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            result = writer.toString()
        }

        val className: String
        when (classType) {
            "record" -> className = "data class"
            "enum" -> className = "enum class"
            else -> className = classType
        }
        val CLASS_REGEX = Pattern.compile(
            "package test[\\s\\S]+" +
                    "public " + className + " " + classDef.simpleName + " \\{\\s+" +
                    "([\\s\\S]+)\\s+}\\s+"
        )
        val matcher = CLASS_REGEX.matcher(result)
        if (!matcher.matches()) {
            Assert.fail("Expected class to match regex: \n$CLASS_REGEX\nbut is: \n$result")
        }
        return matcher.group(0).trim { it <= ' ' }
    }

    private fun getClassDefBuilderWith(objectDef: ObjectDef): ClassDefBuilder {
        val classBuilder = ClassDef.builder("test." + objectDef.simpleName + "Class")
            .addModifiers(Modifier.PUBLIC)
        classBuilder.addInnerType(objectDef)
        return classBuilder
    }

    private fun getRecordDefBuilderWith(objectDef: ObjectDef): RecordDefBuilder {
        val classBuilder = RecordDef.builder("test." + objectDef.simpleName + "Record")
            .addModifiers(Modifier.PUBLIC)
        classBuilder.addInnerType(objectDef)
        return classBuilder
    }

    private fun getInterfaceDefBuilderWith(objectDef: ObjectDef): InterfaceDefBuilder {
        val classBuilder = InterfaceDef.builder("test." + objectDef.simpleName + "Interface")
            .addModifiers(Modifier.PUBLIC)
        classBuilder.addInnerType(objectDef)
        return classBuilder
    }

    private fun getEnumDefBuilderWith(objectDef: ObjectDef): EnumDefBuilder {
        val classBuilder = EnumDef.builder("test." + objectDef.simpleName + "Enum")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("HI").addEnumConstant("HELLO")
        classBuilder.addInnerType(objectDef)
        return classBuilder
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE AN ENUM
     * -----------------------------------------------------------
     */
    @Test
    @Throws(IOException::class)
    fun enumInEnum() {
        val expectedString = """
            package test

            public enum class StatusEnum {
              HI,
              HELLO,
              ;

              public enum class Status {
                SINGLE,
                MARRIED,
              }
            }
            """.trimIndent()
        val enumBuilder = EnumDef.builder("Status")
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED")
        val enumDef = enumBuilder.build()

        val classBuilder: EnumDefBuilder = getEnumDefBuilderWith(enumDef)
        val actual = writeClass(classBuilder.build(), "enum")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun recordInEnum() {
        val expectedString = """
            package test

            import kotlin.Int

            public enum class ExampleRecordEnum {
              HI,
              HELLO,
              ;

              public data class ExampleRecord public constructor(
                public final val id: Int,
              )
            }
            """.trimIndent()
        val recordBuilder = RecordDef.builder("ExampleRecord")
        val propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT)
        recordBuilder.addProperty(propertyBuilder.build())

        val classBuilder: EnumDefBuilder = getEnumDefBuilderWith(recordBuilder.build())
        val actual = writeClass(classBuilder.build(), "enum")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun classInEnum() {
        val expectedString = """
            package test

            public enum class InnerEnum {
              HI,
              HELLO,
              ;

              public class Inner
            }
            """.trimIndent()
        val innerClassBuilder = ClassDef.builder("Inner")

        val classBuilder: EnumDefBuilder = getEnumDefBuilderWith(innerClassBuilder.build())
        val actual = writeClass(classBuilder.build(), "enum")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun interfaceInEnum() {
        val expectedString = """
            package test

            public enum class InterfaceEnum {
              HI,
              HELLO,
              ;

              public interface Interface
            }
            """.trimIndent()
        val interfaceBuilder = InterfaceDef.builder("Interface")

        val classBuilder: EnumDefBuilder = getEnumDefBuilderWith(interfaceBuilder.build())
        val actual = writeClass(classBuilder.build(), "enum")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE A CLASS
     * -----------------------------------------------------------
     */
    @Test
    @Throws(IOException::class)
    fun enumInClass() {
        val expectedString = """
            package test

            public class StatusClass {
              public enum class Status {
                SINGLE,
                MARRIED,
              }
            }""".trimIndent()
        val enumBuilder = EnumDef.builder("Status")
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED")
        val enumDef = enumBuilder.build()

        val classBuilder: ClassDefBuilder = getClassDefBuilderWith(enumDef)
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun recordInClass() {
        val expectedString = """
            package test

            import kotlin.Int

            public class ExampleRecordClass {
              public data class ExampleRecord public constructor(
                public final val id: Int,
              )
            }
            """.trimIndent()
        val recordBuilder = RecordDef.builder("ExampleRecord")
        val propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT)
        recordBuilder.addProperty(propertyBuilder.build())

        val classBuilder: ClassDefBuilder = getClassDefBuilderWith(recordBuilder.build())
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun classInClass() {
        val expectedString = """
            package test

            public class InnerClass {
              public class Inner
            }
            """.trimIndent()
        val innerClassBuilder = ClassDef.builder("Inner")

        val classBuilder: ClassDefBuilder = getClassDefBuilderWith(innerClassBuilder.build())
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun class2InClass() {
        val expectedString = """
            package test

            import kotlin.String

            public class InnerClass {
              private class Inner {
                public var name: String

                public constructor(name: String) {
                  this. name = name
                }

                public constructor()
              }
            }
            """.trimIndent()
        val innerClassBuilder = ClassDef.builder("Inner")
            .addModifiers(Modifier.PRIVATE)
            .addField(FieldDef.builder("name").ofType(TypeDef.STRING).build())
            .addAllFieldsConstructor()
            .addNoFieldsConstructor()

        val classBuilder: ClassDefBuilder = getClassDefBuilderWith(innerClassBuilder.build())
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun interfaceInClass() {
        val expectedString = """
            package test

            public class InterfaceClass {
              public interface Interface
            }
            """.trimIndent()
        val interfaceBuilder = InterfaceDef.builder("Interface")

        val classBuilder: ClassDefBuilder = getClassDefBuilderWith(interfaceBuilder.build())
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE A RECORD
     * -----------------------------------------------------------
     */
    @Test
    @Throws(IOException::class)
    fun enumInRecord() {
        val expectedString = """
            package test

            public data class StatusRecord {
              public enum class Status {
                SINGLE,
                MARRIED,
              }
            }
            """.trimIndent()
        val enumBuilder = EnumDef.builder("Status")
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED")
        val enumDef = enumBuilder.build()

        val classBuilder: RecordDefBuilder = getRecordDefBuilderWith(enumDef)
        val actual = writeClass(classBuilder.build(), "record")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun recordInRecord() {
        val expectedString = """
            package test

            import kotlin.Int

            public data class ExampleRecord {
              public data class Example public constructor(
                public final val id: Int,
              )
            }
            """.trimIndent()
        val recordBuilder = RecordDef.builder("Example")
        val propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT)
        recordBuilder.addProperty(propertyBuilder.build())

        val classBuilder: RecordDefBuilder = getRecordDefBuilderWith(recordBuilder.build())
        val actual = writeClass(classBuilder.build(), "record")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun classInRecord() {
        val expectedString = """
            package test

            public data class InnerRecord {
              public class Inner
            }
            """.trimIndent()
        val innerClassBuilder = ClassDef.builder("Inner")

        val classBuilder: RecordDefBuilder = getRecordDefBuilderWith(innerClassBuilder.build())
        val actual = writeClass(classBuilder.build(), "record")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun interfaceInRecord() {
        val expectedString = """
            package test

            public data class InterfaceRecord {
              public interface Interface
            }
            """.trimIndent()
        val interfaceBuilder = InterfaceDef.builder("Interface")

        val classBuilder: RecordDefBuilder = getRecordDefBuilderWith(interfaceBuilder.build())
        val actual = writeClass(classBuilder.build(), "record")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE AN INTERFACE
     * -----------------------------------------------------------
     */
    @Test
    @Throws(IOException::class)
    fun enumInInterface() {
        val expectedString = """
            package test

            public interface StatusInterface {
              public enum class Status {
                SINGLE,
                MARRIED,
              }
            }
            """.trimIndent()
        val enumBuilder = EnumDef.builder("Status")
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED")
        val enumDef = enumBuilder.build()

        val classBuilder: InterfaceDefBuilder = getInterfaceDefBuilderWith(enumDef)
        val actual = writeClass(classBuilder.build(), "interface")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun recordInInterface() {
        val expectedString = """
            package test

            import kotlin.Int

            public interface RecordInterface {
              public data class Record public constructor(
                public final val id: Int,
              )
            }
            """.trimIndent()
        val recordBuilder = RecordDef.builder("Record")
        val propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT)
        recordBuilder.addProperty(propertyBuilder.build())

        val classBuilder: InterfaceDefBuilder = getInterfaceDefBuilderWith(recordBuilder.build())
        val actual = writeClass(classBuilder.build(), "interface")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun classInInterface() {
        val expectedString = """
            package test

            public interface InnerInterface {
              public class Inner
            }
            """.trimIndent()
        val innerClassBuilder = ClassDef.builder("Inner")

        val classBuilder: InterfaceDefBuilder = getInterfaceDefBuilderWith(innerClassBuilder.build())
        val actual = writeClass(classBuilder.build(), "interface")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    @Throws(IOException::class)
    fun interfaceInInterface() {
        val expectedString = """
            package test

            public interface InnerInterface {
              public interface Inner
            }
            """.trimIndent()
        val interfaceBuilder = InterfaceDef.builder("Inner")

        val classBuilder: InterfaceDefBuilder = getInterfaceDefBuilderWith(interfaceBuilder.build())
        val actual = writeClass(classBuilder.build(), "interface")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

}
