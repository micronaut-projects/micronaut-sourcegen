package io.micronaut.sourcegen

import io.micronaut.sourcegen.custom.visitor.GenerateAnnotationClassVisitor
import io.micronaut.sourcegen.model.AnnotationObjectDef
import io.micronaut.sourcegen.model.ObjectDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringWriter

class AnnotationClassWriteTest {

    @Test
    @Throws(IOException::class)
    fun writeAnnotationClass() {
        val ann: AnnotationObjectDef = GenerateAnnotationClassVisitor.createAnnotation("test.TestAnnotation")
        val result = writeObject(ann)

        // Note: the fixture's `inner` member is typed as a Java annotation (java.lang.annotation.Target)
        // with a Java-annotation default. The Kotlin generator omits such members because the Kotlin
        // compiler crashes on a Java annotation in default-value position (see KotlinPoetSourceGenerator).
        val expected = """
        package test

        import java.lang.`annotation`.ElementType
        import java.lang.`annotation`.Retention
        import java.lang.`annotation`.RetentionPolicy
        import java.lang.`annotation`.Target
        import kotlin.FloatArray
        import kotlin.Int
        import kotlin.IntArray
        import kotlin.String

        /**
         * This is my annotation
         */
        @Retention(value = RetentionPolicy.RUNTIME)
        @Target(value = [java.lang.`annotation`.ElementType.TYPE])
        public annotation class TestAnnotation(
          /**
           * This is a string value
           */
          public val string: String = "hello",
          /**
           * This is a primitive value
           */
          public val primitive: Int = 2,
          /**
           * This is a primitive array value
           */
          public val floats: FloatArray,
          /**
           * An enum value with default
           */
          public val enumValue: ElementType = ElementType.TYPE,
          public val array: IntArray = [1, 2, 3],
        )
        """.trimIndent()
        Assertions.assertEquals(expected.trim(), result.trim())
    }

    @Throws(IOException::class)
    private fun writeObject(objectDef: ObjectDef): String {
        val generator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(objectDef, writer)
            result = writer.toString()
        }
        return result
    }
}
