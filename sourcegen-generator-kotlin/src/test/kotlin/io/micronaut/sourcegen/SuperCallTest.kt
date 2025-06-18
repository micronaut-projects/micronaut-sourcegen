package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.*
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.io.StringWriter
import java.util.regex.Pattern
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

            public class SuperTypeReferenceClass {
              public fun simpleSuperCall(): String {
                return super.toString()
                    .toUpperCase()
              }
            }
        """.trimIndent()
        val stringType = ClassTypeDef.of(String::class.java)
        val method: MethodDef = MethodDef.builder("simpleSuperCall")
            .returns(stringType)
            .addModifiers(Modifier.PUBLIC)
            .build(MethodDef.MethodBodyBuilder { aThis: VariableDef.This?, methodParameters: MutableList<VariableDef.MethodParameter?>? ->
                aThis!!.superRef()
                    .invoke("toString", stringType)
                    .invoke("toUpperCase", stringType)
                    .returning()
            })
        val classBuilder = ClassDef.builder("test." + "SuperTypeReferenceClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSpecificSuperTypeReference() {
        val expectedString = """
            package test

            public class SpecificSuperTypeReferenceClass : ParentClass() {
              public fun specificSuperCall() {
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
        Assert.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSuperConstructorWithNoParam() {
        val expectedString = """
            package test

            public class NoParamChildClass public constructor() : NoParamParent()
        """.trimIndent()
        val parentType = ClassTypeDef.of("test." + "NoParamParent")
        val constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .build { aThis, methodParameters ->
                aThis.superRef().invokeConstructor()
            }
        val classBuilder = ClassDef.builder("test." + "NoParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(constructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSuperConstructorWithParam() {
        val expectedString = """
            package test

            import kotlin.Int
            import kotlin.Long

            public class MultiParamChildClass public constructor(
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
                aThis.superRef().invokeConstructor(
                    methodParameters[0],
                    methodParameters[1]
                )
            }
        val classBuilder = ClassDef.builder("test." + "MultiParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(childConstructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }
}
