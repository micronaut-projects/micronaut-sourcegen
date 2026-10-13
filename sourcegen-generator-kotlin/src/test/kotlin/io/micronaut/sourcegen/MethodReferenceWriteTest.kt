package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.MethodReferenceExpression
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Function
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * Tests every kind of method reference as it is rendered into Kotlin source. A callable reference is
 * not a functional interface on its own in Kotlin, so each one is wrapped in a SAM constructor, and a
 * constructor reference is spelled `::ClassName` rather than `ClassName::new`.
 */
class MethodReferenceWriteTest {

    private val owner = ClassTypeDef.of("test.Owner")

    private fun stringFunction(): ClassTypeDef =
        InterfaceDef.builder("test.StringFunction")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(
                MethodDef.builder("apply")
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addParameter("context", TypeDef.STRING)
                    .returns(TypeDef.STRING)
                    .build()
            )
            .build()
            .asTypeDef()

    /** An instance `String trim(String)` - contrived, so the one-argument interface is filled. */
    private fun trimTaking(args: Int) = MethodDef.builder("trim")
        .addModifiers(Modifier.PUBLIC)
        .also { b -> repeat(args) { b.addParameter("arg$it", TypeDef.STRING) } }
        .returns(TypeDef.STRING)
        .build()

    private fun renderReference(reference: MethodReferenceExpression): String {
        val classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(
                MethodDef.builder("evaluate")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassTypeDef.of("test.StringFunction"))
                    .build { _, _ -> reference.returning() }
            )
            .build()
        val generator = KotlinPoetSourceGenerator()
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            return writer.toString().trim()
        }
    }

    @Test
    fun staticMethodReference() {
        val shout = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build()

        Assertions.assertEquals(
            """
            package test

            public open class MyClass {
              public open fun evaluate(): StringFunction {
                return StringFunction(Owner::shout)
              }
            }
            """.trimIndent(),
            renderReference(stringFunction().staticMethodReference(owner, shout))
        )
    }

    @Test
    fun namedReferenceBoundToAReceiver() {
        Assertions.assertEquals(
            """
            package test

            public open class MyClass {
              public open fun evaluate(): StringFunction {
                return StringFunction("prefix_"::trim)
              }
            }
            """.trimIndent(),
            renderReference(
                stringFunction()
                    .methodReference(ExpressionDef.constant("prefix_"), trimTaking(1))
            )
        )
    }

    @Test
    fun boundInstanceMethodReference() {
        // Named rather than resolved by reflection: plus is a Kotlin operator, not a java.lang.String method
        val plus = MethodDef.builder("plus")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("other", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build()

        Assertions.assertEquals(
            """
            package test

            public open class MyClass {
              public open fun evaluate(): StringFunction {
                return StringFunction("prefix_"::plus)
              }
            }
            """.trimIndent(),
            renderReference(stringFunction().methodReference(ExpressionDef.constant("prefix_"), plus))
        )
    }

    @Test
    fun constructorReference() {
        val constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .build()

        Assertions.assertEquals(
            """
            package test

            public open class MyClass {
              public open fun evaluate(): StringFunction {
                return StringFunction(::Owner)
              }
            }
            """.trimIndent(),
            renderReference(stringFunction().constructorReference(owner, constructor))
        )
    }

    @Test
    fun adaptedReferencePreservesNullableArgument() {
        val apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", TypeDef.OBJECT.makeNullable()).returns(Any::class.java)
            .build { _, _ -> ExpressionDef.constant("accepted").returning() }
        val function = TypeDef.parameterized(ClassTypeDef.of(Function::class.java),
            TypeDef.OBJECT.makeNullable(), TypeDef.OBJECT.makeNullable())
        val def = ClassDef.builder("test.NullableAdaptedReference").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).returns(function)
                .build { self, _ -> function.methodReference(self, apply).returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            @Suppress("UNCHECKED_CAST")
            val function = cls.getMethod("reference").invoke(cls.getConstructor().newInstance()) as Function<Any?, Any?>
            assertEquals("accepted", function.apply(null))
        }
    }
}
