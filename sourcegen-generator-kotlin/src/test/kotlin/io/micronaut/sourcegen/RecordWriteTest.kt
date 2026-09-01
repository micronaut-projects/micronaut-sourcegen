package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

class RecordWriteTest {

    @Test
    fun writeSimpleRecord() {
        val recordDef = RecordDef.builder("test.TestRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING).build())
            .addProperty(PropertyDef.builder("age").ofType(TypeDef.Primitive.INT).build())
            .build()

        val expected = """
        package test

        import kotlin.Int
        import kotlin.String

        public data class TestRecord public constructor(
          public final val name: String,
          public final val age: Int,
        )

        """.trimIndent()
        Assertions.assertEquals(expected.trim(), write(recordDef).trim())
    }

    @Test
    fun writeGenericRecord() {
        val recordDef = RecordDef.builder("test.TestRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("K"))
            .addTypeVariable(TypeDef.variable("V", TypeDef.of(Number::class.java)))
            .addSuperinterface(
                TypeDef.parameterized(
                    ClassTypeDef.of("java.util.function.Supplier"),
                    TypeDef.variable("V")
                )
            )
            .addProperty(PropertyDef.builder("key").ofType(TypeDef.variable("K")).build())
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.variable("V")).build())
            // TypeDef.THIS renders as the record parameterized by its own variables
            .addMethod(
                MethodDef.builder("self")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeDef.THIS)
                    .build { aThis, _ -> aThis.returning() }
            )
            .build()

        val expected = """
        package test

        import java.lang.Number
        import java.util.function.Supplier

        public data class TestRecord<K, V : Number> public constructor(
          public final val key: K,
          public final val `value`: V,
        ) : Supplier<V> {
          public fun self(): TestRecord<K, V> {
            return this
          }
        }

        """.trimIndent()
        Assertions.assertEquals(expected.trim(), write(recordDef).trim())
    }

    private fun write(recordDef: RecordDef): String {
        val generator = KotlinPoetSourceGenerator()
        StringWriter().use { writer ->
            generator.write(recordDef, writer)
            return writer.toString()
        }
    }
}
