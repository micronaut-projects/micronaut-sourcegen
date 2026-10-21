package io.micronaut.sourcegen

import io.micronaut.sourcegen.custom.visitor.GenerateAnnotationClassVisitor
import io.micronaut.sourcegen.model.ObjectDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter

/**
 * An annotation class is a Kotlin `annotation class`, whose members are the properties of its
 * primary constructor and whose defaults are the parameter defaults.
 *
 * Note that the fixture below cannot be compiled by kotlinc: a member of a Java annotation type
 * (`inner` here) crashes the JVM backend, whatever writes the source. That is why the annotation
 * class is covered here rather than by a trigger in the Kotlin test suite.
 */
class AnnotationClassWriteTest {

    @Test
    fun writeAnnotationClass() {
        val annotationDef = GenerateAnnotationClassVisitor.createAnnotation("test.TestAnnotation")

        Assertions.assertEquals(
            """
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
              /**
               * An annotation value with default
               */
              public val `inner`:
                  Target = java.lang.`annotation`.Target(value = [java.lang.`annotation`.ElementType.TYPE]),
              public val array: IntArray = intArrayOf(1, 2, 3),
            )
            """.trimIndent(),
            write(annotationDef)
        )
    }

    private fun write(objectDef: ObjectDef): String {
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(objectDef, writer)
            return writer.toString().trim()
        }
    }
}
