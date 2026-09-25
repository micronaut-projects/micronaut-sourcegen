package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.EnumDef.EnumDefBuilder
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.InterfaceDef.InterfaceDefBuilder
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.RecordDef.RecordDefBuilder
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
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
            // A record without components is no data class, which needs one
            "record" -> className = "class"
            "enum" -> className = "enum class"
            // A class that is not final can be extended
            "class" -> className = "open class"
            else -> className = classType
        }
        val CLASS_REGEX = Pattern.compile(
            "package test[\\s\\S]+" +
                    "public " + className + " " + classDef.simpleName + " \\{\\s+" +
                    "([\\s\\S]+)\\s+}\\s+"
        )
        val matcher = CLASS_REGEX.matcher(result)
        if (!matcher.matches()) {
            throw RuntimeException("Expected class to match regex: \n$CLASS_REGEX\nbut is: \n$result")
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

              public open class Inner
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

            public open class StatusClass {
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

            public open class ExampleRecordClass {
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

            public open class InnerClass {
              public open class Inner
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

            public open class InnerClass {
              private open class Inner {
                public var name: String? = null

                public constructor(name: String) {
                  this.name = name
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

            public open class InterfaceClass {
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

            public class StatusRecord {
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

            public class ExampleRecord {
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

            public class InnerRecord {
              public open class Inner
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

            public class InterfaceRecord {
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
              public open class Inner
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

    @Test
    fun enclosingClassCallsAPrivateMethodOfItsNestedClass() {
        val secret = MethodDef.builder("secret").addModifiers(Modifier.PRIVATE).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("nested").returning() }
        val nested = ClassDef.builder("NestedHolder").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addMethod(secret).build()
        val nestedType = ClassTypeDef.of("test.NestOuter\$NestedHolder", true)
        val outer = ClassDef.builder("test.NestOuter").addModifiers(Modifier.PUBLIC).addInnerType(nested)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { _, _ -> nestedType.instantiate().invoke(secret).returning() })
            .build()
        compile(outer).use { loader ->
            val cls = loader.loadClass(outer.name)
            assertEquals("nested", cls.getMethod("call").invoke(cls.getConstructor().newInstance()))
        }
    }

    @Test
    fun nestedTypeOfAClassWhoseNameHasADollar() {
        val holder = ClassDef.builder("Holder").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build()
        val holderType = ClassTypeDef.of("test.\$Outer\$Holder", true)
        val def = ClassDef.builder("test.\$Outer").addModifiers(Modifier.PUBLIC).addInnerType(holder)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build { _, _ -> holderType.instantiate().returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("test.\$Outer\$Holder", cls.getMethod("create").invoke(cls.getConstructor().newInstance()).javaClass.name)
        }
    }

    /**
     * A static nested type calls the narrowed `accept(String)` of its outer type, which it can only name. The Java
     * generator finds the outer definition in the file being written; the Kotlin one did not record that file, so it
     * passed the `Any` value unconverted and kotlinc rejected the call.
     */
    @Test
    fun nestedTypeCallsANarrowedMethodOfItsOuterType() {
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Any::class.java).returns(Void.TYPE)
            .build { _, p -> p[0].invoke("toString", TypeDef.STRING) }
        val nested = ClassDef.builder("Nested").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("target", ClassTypeDef.of("test.NarrowedOuter")).addParameter("value", Any::class.java)
                .returns(Void.TYPE)
                .build { _, p -> p[0].invoke(accept, p[1]) })
            .build()
        val outer = ClassDef.builder("test.NarrowedOuter").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Consumer::class.java, String::class.java))
            .addMethod(accept).addInnerType(nested)
            .build()
        KotlinCompileAssertions.compileAndLoad(render(outer)).use { loader ->
            val instance = loader.loadClass(outer.name).getConstructor().newInstance()
            // A static method is a function of the companion object
            val companion = loader.loadClass("test.NarrowedOuter\$Nested").getField("Companion").get(null)
            companion.javaClass.getMethod("run", instance.javaClass, Any::class.java).invoke(companion, instance, "v")
        }
    }

}
