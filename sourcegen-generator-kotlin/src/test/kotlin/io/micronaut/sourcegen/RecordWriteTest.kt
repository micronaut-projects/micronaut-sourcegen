package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
        Assertions.assertEquals(expected.trim(), render(recordDef).trim())
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

        import java.util.function.Supplier
        import kotlin.Number

        public data class TestRecord<K, V : Number> public constructor(
          public final val key: K,
          public final val `value`: V,
        ) : Supplier<V> {
          public fun self(): TestRecord<K, V> {
            return this
          }
        }

        """.trimIndent()
        Assertions.assertEquals(expected.trim(), render(recordDef).trim())
    }

    @Test
    fun staticSelfTypeDoesNotUseRecordTypeVariables() {
        val recordDef = RecordDef.builder("test.StaticSelfRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(
                MethodDef.builder("create")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(TypeDef.THIS)
                    .build { _, _ ->
                        ClassTypeDef.of(UnsupportedOperationException::class.java)
                            .instantiate()
                            .doThrow()
                    }
            )
            .build()

        val source = render(recordDef)

        Assertions.assertTrue(source.contains("fun create(): StaticSelfRecord<Any>"), source)
    }

    @Test
    fun writeWildcardsAndOutOfScopeVariables() {
        val recordDef = RecordDef.builder("test.WildcardRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(
                PropertyDef.builder("producers").ofType(
                    TypeDef.parameterized(
                        ClassTypeDef.of(java.util.List::class.java),
                        TypeDef.wildcardSubtypeOf(TypeDef.of(Number::class.java))
                    )
                ).build()
            )
            .addProperty(
                PropertyDef.builder("consumers").ofType(
                    TypeDef.parameterized(
                        ClassTypeDef.of(java.util.List::class.java),
                        TypeDef.wildcardSupertypeOf(TypeDef.of(Number::class.java))
                    )
                ).build()
            )
            // A variable neither the record nor the method declares falls back to its bound
            .addProperty(
                PropertyDef.builder("bound").ofType(TypeDef.variable("U", TypeDef.of(CharSequence::class.java))).build()
            )
            .addProperty(PropertyDef.builder("unbound").ofType(TypeDef.variable("W")).build())
            .build()

        val source = render(recordDef)

        Assertions.assertTrue(source.contains("val producers: List<out Number>"), source)
        Assertions.assertTrue(source.contains("val consumers: List<in Number>"), source)
        Assertions.assertTrue(source.contains("val bound: CharSequence"), source)
        Assertions.assertTrue(source.contains("val unbound: Any"), source)
    }

    @Test
    fun recordWithoutComponentsCompiles() {
        val def = RecordDef.builder("test.EmptyRecord").addModifiers(Modifier.PUBLIC).build()
        compile(def).use { loader ->
            loader.loadClass(def.name).getConstructor().newInstance()
        }
    }

    @Test
    fun recordWithADeclaredCanonicalConstructor() {
        val def = RecordDef.builder("test.CheckedRecord").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String::class.java).build())
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("name", String::class.java)
                .build { self, p -> self.field("name", TypeDef.STRING).put(p[0]) })
            .build()
        compile(def).use { loader ->
            loader.loadClass(def.name).getConstructor(String::class.java).newInstance("text")
        }
    }

    @Test
    fun recordAccessorIsCalledByAnotherClass() {
        val record = RecordDef.builder("test.NamedRecord").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String::class.java).build()).build()
        val caller = ClassDef.builder("test.NamedRecordReader").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).addParameter("record", record.asTypeDef()).returns(String::class.java)
                .build { _, p -> p[0].invoke("name", TypeDef.STRING).returning() })
            .build()
        compile(record, caller).use { loader ->
            val recordClass = loader.loadClass(record.name)
            val cls = loader.loadClass(caller.name)
            assertEquals("text", cls.getMethod("read", recordClass)
                .invoke(cls.getConstructor().newInstance(), recordClass.getConstructor(String::class.java).newInstance("text")))
        }
    }
}
