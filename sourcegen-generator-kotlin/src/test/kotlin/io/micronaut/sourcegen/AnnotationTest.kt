package io.micronaut.sourcegen

import io.micronaut.core.annotation.Introspected
import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringWriter

class AnnotationTest {
    private val PATTERN_ANN: String = "jakarta.validation.constraints.Pattern"
    private val JSON_SUB_TYPES_ANN: String = "com.fasterxml.jackson.annotation.JsonSubTypes"
    private val JSON_SUB_TYPES_TYPE_ANN: String = "$JSON_SUB_TYPES_ANN.Type"

    @Test
    @Throws(IOException::class)
    fun writeSimpleAnnotation() {
        val classDef = ClassDef.builder("SimpleClass").addAnnotation(Introspected::class.java).build()
        val result = writeClass(classDef)

        val expected = """
        @Introspected
        public open class SimpleClass
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeAnnotationWithVariable() {
        val annDef = AnnotationDef.builder(ClassTypeDef.of(PATTERN_ANN))
            .addMember("regex", "hii")
            .build()
        val classDef = ClassDef.builder("SimpleClass")
            .addAnnotation(Introspected::class.java)
            .addField(FieldDef.builder("str").ofType(TypeDef.STRING).addAnnotation(annDef).build())
            .build()
        val result = writeClass(classDef)

        val expected = """
        @Introspected
        public open class SimpleClass {
          @Pattern(regex = "hii")
          public var str: String? = null
        }
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeAnnotationWithListVariable() {
        val simpleAnn = getSimpleAnn()
        val classDef = ClassDef.builder("SimpleClass")
            .addAnnotation(simpleAnn)
            .build()
        val result = writeClass(classDef)

        val expected = """
        @Simple(value = [1,
        2,
        3])
        public open class SimpleClass
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    @Throws(IOException::class)
    fun writeAnnotationWithAnnListVariable() {
        val annDef = getJsonSubTypesAnn()
        val classDef = ClassDef.builder("SimpleClass")
            .addAnnotation(annDef)
            .build()
        val result = writeClass(classDef)

        val expected = """
        @JsonSubTypes(value = [com.fasterxml.jackson.`annotation`.JsonSubTypes.Type(value = String::class, name = "Cat"),
        com.fasterxml.jackson.`annotation`.JsonSubTypes.Type(value = String::class, name = "Dog"),
        com.fasterxml.jackson.`annotation`.JsonSubTypes.Type(value = String::class, name = "Fish")])
        public open class SimpleClass
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Test
    fun staticFinalConstantIsAnAnnotationArgument() {
        val nameField = FieldDef.builder("NAME", String::class.java).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(ExpressionDef.constant("configured")).build()
        val names = ClassDef.builder("test.Names").addModifiers(Modifier.PUBLIC).addField(nameField).build()
        val def = ClassDef.builder("test.AnnotatedWithConstant").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID)
                .addAnnotation(io.micronaut.sourcegen.model.AnnotationDef.builder(io.micronaut.context.annotation.Value::class.java)
                    .addMember("value", names.asTypeDef().getStaticField(nameField)).build())
                .build { _, _ -> StatementDef.multi() })
            .build()
        compile(names, def).use { loader ->
            val method = loader.loadClass(def.name).getMethod("call")
            assertEquals("configured", method.getAnnotation(io.micronaut.context.annotation.Value::class.java).value)
        }
    }

    @Test
    fun arrayAnnotationMemberKeepsAnotherMemberWhoseValueNamesIt() {
        val requires = io.micronaut.sourcegen.model.AnnotationDef.builder(io.micronaut.context.annotation.Requires::class.java)
            .addMember("property", "env")
            .addMember("env", listOf<Any>("a", "b"))
            .build()
        val def = ClassDef.builder("test.RequiresBoth").addModifiers(Modifier.PUBLIC).addAnnotation(requires).build()
        compile(def).use { loader ->
            val annotation = loader.loadClass(def.name).getAnnotation(io.micronaut.context.annotation.Requires::class.java)
            assertEquals("env", annotation.property)
            assertArrayEquals(arrayOf("a", "b"), annotation.env)
        }
    }

    private fun getSimpleAnn(): AnnotationDef {
        val numbers = listOf(1,2,3)
        return AnnotationDef.builder(ClassTypeDef.of("Simple"))
            .addMember("value", numbers)
            .build()
    }

    private fun getJsonSubTypesAnn(): AnnotationDef {
        val mapping = mapOf("Cat" to TypeDef.STRING, "Dog" to TypeDef.STRING, "Fish" to TypeDef.STRING)
        val subTypeList = mapping.entries
            .map { entry: Map.Entry<String, Any> ->
                AnnotationDef
                    .builder(ClassTypeDef.of(JSON_SUB_TYPES_TYPE_ANN))
                    .addMember("value", entry.value)
                    .addMember("name", entry.key)
                    .build()
            }
            .toList()
        return AnnotationDef.builder(ClassTypeDef.of(JSON_SUB_TYPES_ANN))
            .addMember("value", subTypeList)
            .build()
    }

    @Throws(IOException::class)
    private fun writeClass(classDef: ClassDef): String {
        val generator: KotlinPoetSourceGenerator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            result = writer.toString()
        }

        return result.substring(result.indexOf("@"), result.length)
    }
}
