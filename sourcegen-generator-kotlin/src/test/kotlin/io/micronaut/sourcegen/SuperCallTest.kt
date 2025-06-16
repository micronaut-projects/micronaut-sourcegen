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
        val generator: KotlinPoetSourceGenerator = KotlinPoetSourceGenerator()
        var result: String
        StringWriter().use { writer ->
            generator.write(classDef, writer)
            result = writer.toString()
        }

        val CLASS_REGEX = Pattern.compile(
            "package test[\\s\\S]+" +
                    "public " + classType + " " + classDef.simpleName + " \\{\\s+" +
                    "([\\s\\S]+)\\s+}\\s+"
        )
        val matcher = CLASS_REGEX.matcher(result)
        if (!matcher.matches()) {
            Assert.fail("Expected class to match regex: \n$CLASS_REGEX\nbut is: \n$result")
        }
        return matcher.group(0).trim { it <= ' ' }
    }

    @Test
    @Throws(IOException::class)
    fun testSuperTypeReference() {
        val expectedString = """
            package test

            import kotlin.String

            public class TestSuperTypeReferenceClass {
              public fun complexSuperCall(): String {
                return super.toString()
                    .toUpperCase()
              }
            }
        """.trimIndent()
        val stringType = ClassTypeDef.of(String::class.java)
        val method: MethodDef = MethodDef.builder("complexSuperCall")
            .returns(stringType)
            .addModifiers(Modifier.PUBLIC)
            .build(MethodDef.MethodBodyBuilder { aThis: VariableDef.This?, methodParameters: MutableList<VariableDef.MethodParameter?>? ->
                aThis!!.superRef()
                    .invoke("toString", stringType)
                    .invoke("toUpperCase", stringType)
                    .returning()
            })
        val classBuilder = ClassDef.builder("test." + "TestSuperTypeReferenceClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSpecificSuperTypeReference() {
        val expectedString = """
            package test

            public class TestSpecificSuperTypeReferenceClass {
              public fun SpecificSuperCall() {
                super<ParentClass>.specificMethod()
              }
            }
        """.trimIndent()
        val specificParentType = ClassTypeDef.of("test." + "ParentClass")
        VariableDef.Super(specificParentType)
        val superMethod = MethodDef.builder("SpecificSuperCall")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.PUBLIC)
            .build { aThis, methodParameters ->
                aThis.superRef(specificParentType).invoke("specificMethod", TypeDef.VOID)
            }

        val classBuilder = ClassDef.builder("test." + "TestSpecificSuperTypeReferenceClass")
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

            public class MultiParamChildClass {
              public constructor() {
                super()
              }
            }
        """.trimIndent()
        val parentType = ClassTypeDef.of("test." + "MultiParamParent")
        val constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .build { aThis, methodParameters ->
                aThis.superRef().invokeConstructor()
            }
        val classBuilder = ClassDef.builder("test." + "MultiParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(constructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }

    @Test
    fun testSuperConstructorWithMultipleClassLiterals() {
        val expectedString = """
            package test

            import Var1
            import Var2
            import kotlin.Int
            import kotlin.String

            public class MultiParamChildClass {
              public constructor(var1: String, var2: Int) {
                super(var1, Var1::class, var2, Var2::class)
              }
            }
        """.trimIndent()
        val parentType = ClassTypeDef.of("test." + "MultiParamParent")
        val bookType = ClassTypeDef.of("Var1")
        val authorType = ClassTypeDef.of("Var2")
        val bookClassLiteral = ExpressionDef.constant(bookType)
        val authorClassLiteral = ExpressionDef.constant(authorType)
        val param1 = ParameterDef.builder("var1", TypeDef.STRING).build()
        val param2 = ParameterDef.builder("var2", TypeDef.Primitive.INT).build()
        val constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(param1)
            .addParameter(param2)
            .build { aThis, methodParameters ->
                aThis.superRef().invokeConstructor(
                    methodParameters[0],
                    bookClassLiteral,
                    methodParameters[1],
                    authorClassLiteral
                )
            }
        val classBuilder = ClassDef.builder("test." + "MultiParamChildClass")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parentType)
            .addMethod(constructor)
        val actual = writeClass(classBuilder.build(), "class")
        Assert.assertEquals(expectedString.trim(), actual)
    }
}
