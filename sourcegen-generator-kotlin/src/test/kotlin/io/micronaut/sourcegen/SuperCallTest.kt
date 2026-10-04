package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.model.*
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringWriter
import javax.lang.model.element.Modifier


class SuperCallTest {

    /**
     * Writes a class and returns all the contents of the class.
     */
    @Throws(IOException::class)
    fun writeClass(classDef: ObjectDef, classType: String): String {
        val generator = KotlinPoetSourceGenerator()
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            return writer.toString().trim()
        }
    }

    @Test
    @Throws(IOException::class)
    fun testSuperTypeReference() {
        val expectedString = """
            package test

            import kotlin.String

            public open class SuperTypeReferenceClass {
              public open fun simpleSuperCall(): String {
                return (super.toString() as java.lang.String)
                    .toUpperCase()
              }
            }
        """.trimIndent()
        val stringType = ClassTypeDef.of(String::class.java)
        val method: MethodDef = MethodDef.builder("simpleSuperCall")
            .returns(stringType)
            .addModifiers(Modifier.PUBLIC)
            .build { aThis: VariableDef.This, _: MutableList<VariableDef.MethodParameter> ->
                aThis.superRef()
                    .invoke("toString", stringType)
                    .invoke("toUpperCase", stringType)
                    .returning()
            }
        val classBuilder = ClassDef.builder("test." + "SuperTypeReferenceClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSpecificSuperTypeReference() {
        val expectedString = """
            package test

            public open class SpecificSuperTypeReferenceClass : ParentClass() {
              public open fun specificSuperCall() {
                super<ParentClass>.specificMethod()
              }
            }
        """.trimIndent()
        val specificParentType = ClassTypeDef.of("test." + "ParentClass")
        VariableDef.Super(specificParentType)
        val superMethod = MethodDef.builder("specificSuperCall")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.PUBLIC)
            .build { aThis, methodParameters ->
                aThis.superRef(specificParentType).invoke("specificMethod", TypeDef.VOID)
            }

        val classBuilder = ClassDef.builder("test." + "SpecificSuperTypeReferenceClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(specificParentType)
            .addMethod(superMethod)
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSuperConstructorWithNoParam() {
        val expectedString = """
            package test

            public open class NoParamChildClass public constructor() : NoParamParent()
        """.trimIndent()
        val parentType = ClassTypeDef.of("test." + "NoParamParent")
        val constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .build { aThis, methodParameters ->
                aThis.superRef().invokeSuperConstructor()
            }
        val classBuilder = ClassDef.builder("test." + "NoParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(constructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSuperConstructorWithParam() {
        val expectedString = """
            package test

            import kotlin.Int
            import kotlin.Long

            public open class MultiParamChildClass public constructor(
              childParam1: Int,
              childParam2: Long,
            ) : MultiParamParent(childParam1, childParam2)
        """.trimIndent()
        val parentType = ClassTypeDef.of("test." + "MultiParamParent")
        val childParam1 = ParameterDef.builder("childParam1", TypeDef.Primitive.INT).build()
        val childParam2 = ParameterDef.builder("childParam2", TypeDef.Primitive.LONG).build()
        val childConstructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(childParam1)
            .addParameter(childParam2)
            .build { aThis, methodParameters ->
                aThis.superRef().invokeSuperConstructor(
                    methodParameters[0],
                    methodParameters[1]
                )
            }
        val classBuilder = ClassDef.builder("test." + "MultiParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(childConstructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assertions.assertEquals(expectedString.trim(), actual)
    }

    // Arguments of a super constructor call are rendered without renderArguments: an Object value for `Exception(String)` is not cast (Java: superConstructorArgumentIsCast).
    @Test
    fun superConstructorArgumentIsCast() {
        val def = ClassDef.builder("test.B08")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .build { aThis, p -> aThis.superRef(ClassTypeDef.of(Exception::class.java))
                    .invokeSuperConstructor(listOf(TypeDef.STRING), p[0]) })
            .build()
        compile(def).use { loader ->
            val e = loader.loadClass(def.name).getConstructor(Any::class.java).newInstance("m") as Exception
            assertEquals("m", e.message)
        }
    }

    // `this(...)` in a constructor is written as the statement `this("d")` in the body; Kotlin delegates with `constructor() : this("d")`. The final field is then also reported unassigned.
    @Test
    fun constructorDelegation() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val primary = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING)
            .build { aThis, p -> aThis.field(field).put(p[0]) }
        val def = ClassDef.builder("test.B09").addField(field)
            .addMethod(primary)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ -> aThis.invokeConstructor(primary, ExpressionDef.constant("d")) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("d", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A constructor calling super becomes the primary constructor and the rest of its body is dropped: the field assignment after `super(message)` is lost.
    @Test
    fun constructorBodyAfterSuperCall() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val def = ClassDef.builder("test.C16")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("message", TypeDef.STRING).addParameter("v", TypeDef.STRING)
                .build { aThis, p -> StatementDef.multi(
                    aThis.superRef(ClassTypeDef.of(Exception::class.java)).invokeSuperConstructor(listOf(TypeDef.STRING), p[0]),
                    aThis.field(field).put(p[1])) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java, String::class.java).newInstance("m", "v")
            assertEquals("v", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // Two constructors that both call super: each sets the primary constructor and appends its super arguments, giving `constructor() : Exception(message, "default")`.
    @Test
    fun twoConstructorsCallingSuper() {
        val def = ClassDef.builder("test.C17")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("message", TypeDef.STRING)
                .build { aThis, p -> aThis.superRef(ClassTypeDef.of(Exception::class.java)).invokeSuperConstructor(listOf(TypeDef.STRING), p[0]) })
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ -> aThis.superRef(ClassTypeDef.of(Exception::class.java))
                    .invokeSuperConstructor(listOf(TypeDef.STRING), ExpressionDef.constant("default")) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("m", (cls.getConstructor(String::class.java).newInstance("m") as Exception).message)
            assertEquals("default", (cls.getConstructor().newInstance() as Exception).message)
        }
    }

    /**
     * A default method of a generated interface is rejected: "Not supported modifier: default".
     */
    @Test
    fun generatedInterfaceDefaultMethodIsCalledThroughItsSuper() {
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("hello").returning() }
        val greeter = InterfaceDef.builder("test.DefaultGreeter").addModifiers(Modifier.PUBLIC).addMethod(greet).build()
        val def = ClassDef.builder("test.LoudGreeter").addModifiers(Modifier.PUBLIC).addSuperinterface(greeter.asTypeDef())
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).overrides().returns(String::class.java)
                .build { self, _ -> self.superRef(greeter.asTypeDef()).invoke(greet).stringConcat(ExpressionDef.constant("!")).returning() })
            .build()
        compile(greeter, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("hello!", cls.getMethod("greet").invoke(cls.getConstructor().newInstance()))
        }
    }
}
