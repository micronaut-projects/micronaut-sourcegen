/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.EnumDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.net.URLClassLoader
import javax.lang.model.element.Modifier

/**
 * The Kotlin side of the DSL-generated programs for shapes no other test of the generators reaches: rendered,
 * compared with their snapshot, compiled and run.
 */
class UncoveredShapeProgramTest {

    private val INT: Class<*> = Int::class.javaPrimitiveType!!

    @Test
    fun annotationMembersOfEveryKindAreReadBack() {
        val nested = AnnotationDef.builder(ClassTypeDef.of(Nested::class.java)).addMember("value", "inner").build()
        val annotation = AnnotationDef.builder(ClassTypeDef.of(Members::class.java))
            .addMember("texts", arrayOf("a", "\$b"))
            .addMember("numbers", intArrayOf(1, 2))
            .addMember("none", arrayOf<String>())
            .addMember("types", arrayOf<Class<*>>(String::class.java, INT))
            .addMember("policies", listOf(AnnotationRetention.RUNTIME, AnnotationRetention.SOURCE))
            .addMember("letter", '\'')
            .addMember("ratio", 1.5f)
            .addMember("nested", nested)
            .build()
        val def = ClassDef.builder("test.AnnotatedMembers").addModifiers(Modifier.PUBLIC).addAnnotation(annotation).build()
        compile(def).use { loader ->
            val members = loader.loadClass(def.name).getAnnotation(Members::class.java)
            assertArrayEquals(arrayOf("a", "\$b"), members.texts)
            assertArrayEquals(intArrayOf(1, 2), members.numbers)
            assertEquals(0, members.none.size)
            assertEquals(listOf(String::class, Int::class), members.types.toList())
            assertEquals(listOf(AnnotationRetention.RUNTIME, AnnotationRetention.SOURCE), members.policies.toList())
            assertEquals('\'', members.letter)
            assertEquals(1.5f, members.ratio)
            assertEquals("inner", members.nested.value)
        }
    }

    @Test
    fun nestedMathKeepsTheGroupingOfTheModel() {
        val def = ClassDef.builder("test.MathGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", INT).addParameter("b", INT)
                .addParameter("c", INT).returns(IntArray::class.java)
                .build { _, p ->
                    val a: ExpressionDef = p[0]
                    val b: ExpressionDef = p[1]
                    val c: ExpressionDef = p[2]
                    TypeDef.Primitive.INT.array().instantiate(
                        a.math(OpType.ADDITION, b).math(OpType.MULTIPLICATION, c),
                        a.math(OpType.SUBTRACTION, b.math(OpType.SUBTRACTION, c)),
                        a.math(OpType.SUBTRACTION, b).math(OpType.SUBTRACTION, c),
                        a.math(OpType.DIVISION, b.math(OpType.MULTIPLICATION, c)),
                        a.math(OpType.BITWISE_OR, b).math(OpType.BITWISE_AND, c),
                        a.math(OpType.BITWISE_LEFT_SHIFT, b.math(OpType.ADDITION, c)),
                        a.math(OpType.BITWISE_XOR, b.math(OpType.BITWISE_OR, c)),
                        a.math(OpType.ADDITION, b).math(ExpressionDef.MathUnaryOperation.OpType.NEGATE),
                        a.math(OpType.MODULUS, b.math(OpType.MODULUS, c))
                    ).returning()
                }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val a = 40
            val b = 7
            val c = 3
            assertArrayEquals(
                intArrayOf((a + b) * c, a - (b - c), a - b - c, a / (b * c), (a or b) and c, a shl (b + c), a xor (b or c), -(a + b), a % (b % c)),
                cls.getMethod("call", INT, INT, INT)
                    .invoke(cls.getConstructor().newInstance(), a, b, c) as IntArray
            )
        }
    }

    @Test
    fun conditionsKeepTheirGroupingAndNegation() {
        val bool = Boolean::class.javaPrimitiveType!!
        val def = ClassDef.builder("test.ConditionGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", bool).addParameter("b", bool).addParameter("c", bool)
                .addParameter("x", INT).addParameter("text", TypeDef.OBJECT.makeNullable())
                .returns(BooleanArray::class.java)
                .build { _, p ->
                    val a = p[0].isTrue
                    val b = p[1].isTrue
                    val c = p[2].isTrue
                    TypeDef.Primitive.BOOLEAN.array().instantiate(
                        a.or(b).and(c),
                        a.and(b.or(c)),
                        a.or(b.and(c)),
                        a.or(b).isFalse,
                        a.and(b).isFalse.or(c),
                        p[3].equalsReferentially(ExpressionDef.constant(1)).isFalse,
                        p[3].notEqualsStructurally(ExpressionDef.constant(1)),
                        p[4].notEqualsStructurally(ExpressionDef.constant("text")),
                        p[4].isNull.isFalse.and(p[4].instanceOf(ClassTypeDef.of(String::class.java)))
                    ).returning()
                }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val method = cls.getMethod("call", bool, bool, bool, INT, Any::class.java)
            val instance = cls.getConstructor().newInstance()
            for (bits in 0 until 8) {
                val a = bits and 1 != 0
                val b = bits and 2 != 0
                val c = bits and 4 != 0
                val x = bits % 2
                val text: Any? = if (a) "text" else if (b) "other" else null
                assertArrayEquals(
                    booleanArrayOf((a || b) && c, a && (b || c), a || (b && c), !(a || b), !(a && b) || c, !(x == 1), x != 1,
                        "text" != text, !(text == null) && text is String),
                    method.invoke(instance, a, b, c, x, text) as BooleanArray, "bits $bits"
                )
            }
        }
    }

    @Test
    fun elementOfNarrowedArrayParameterIsReturned() {
        val def = ClassDef.builder("test.NarrowedElement").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(First::class.java, String::class.java))
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", Array<Any>::class.java).returns(Any::class.java)
                .build { _, p -> p[0].arrayElement(0).returning() })
            .build()
        compile(def).use { loader ->
            @Suppress("UNCHECKED_CAST") val first = loader.loadClass(def.name).getConstructor().newInstance() as First<String>
            assertEquals("text", first.first(arrayOf("text")))
        }
    }

    @Test
    fun interfaceStaticMethodAndImplementationAreCalled() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", INT).returns(String::class.java).build()
        val twice = MethodDef.builder("twice").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", INT).returns(INT)
            .build { _, p -> p[0].math(OpType.MULTIPLICATION, ExpressionDef.constant(2)).returning() }
        val describer = InterfaceDef.builder("test.Describer").addModifiers(Modifier.PUBLIC).addMethod(describe).addMethod(twice).build()
        val def = ClassDef.builder("test.TwiceDescriber").addModifiers(Modifier.PUBLIC).addSuperinterface(describer.asTypeDef())
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", INT).returns(String::class.java)
                .build { _, p -> ExpressionDef.constant("twice ").stringConcat(describer.asTypeDef().invokeStatic(twice, p[0])).returning() })
            .build()
        compile(describer, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("twice 42", cls.getMethod("describe", INT).invoke(cls.getConstructor().newInstance(), 21))
        }
    }

    @Test
    fun enumWithConstructorStaticMethodAndItsConstants() {
        val weight = FieldDef.builder("weight", INT).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val type = ClassTypeDef.of("test.Weighted")
        val weightOf = MethodDef.builder("weight").addModifiers(Modifier.PUBLIC).returns(INT)
            .build { self, _ -> self.field(weight).returning() }
        val heavier = MethodDef.builder("heavier").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("left", type).addParameter("right", type).returns(type)
            .build { _, p -> p[0].invoke(weightOf).compare(ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, p[1].invoke(weightOf))
                .doIfElse(p[0], p[1]).returning() }
        val def = EnumDef.builder(type.name).addModifiers(Modifier.PUBLIC)
            .addEnumConstant("LIGHT", ExpressionDef.constant(1)).addEnumConstant("HEAVY", ExpressionDef.constant(10))
            .addField(weight).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(heavier)
            .addMethod(weightOf)
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val light = cls.getField("LIGHT").get(null)
            val heavy = cls.getField("HEAVY").get(null)
            assertEquals(10, cls.getMethod("weight").invoke(heavy))
            val companion = cls.getField("Companion").get(null)
            assertEquals(heavy, companion.javaClass.getMethod("heavier", cls, cls).invoke(companion, light, heavy))
        }
    }

    @Test
    fun declaredCheckedExceptionIsThrown() {
        val def = ClassDef.builder("test.CheckedThrower").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addThrows(TypeDef.of(java.io.IOException::class.java)).returns(Void.TYPE)
                .build { _, _ -> ClassTypeDef.of(java.io.IOException::class.java).instantiate(ExpressionDef.constant("checked")).doThrow() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val method = cls.getMethod("call")
            assertArrayEquals(arrayOf<Class<*>>(java.io.IOException::class.java), method.exceptionTypes)
            val error = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(cls.getConstructor().newInstance())
            }
            assertEquals("checked", error.cause!!.message)
        }
    }

    @Test
    fun superConstructorTakesItsArgument() {
        val def = ClassDef.builder("test.SuperArgument").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("label", String::class.java)
                .build { self, p -> self.superRef().invokeSuperConstructor(listOf<TypeDef>(TypeDef.STRING), p[0]) })
            .addMethod(MethodDef.builder("typeName").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> self.invokeGetClass().invoke("getSimpleName", TypeDef.STRING).returning() })
            .addMethod(MethodDef.builder("stableHash").addModifiers(Modifier.PUBLIC).returns(Boolean::class.javaPrimitiveType!!)
                .build { self, _ -> self.invokeHashCode().compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, self.invokeHashCode()).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor(String::class.java).newInstance("text")
            assertEquals("text", (instance as Labelled).label)
            assertEquals("SuperArgument", cls.getMethod("typeName").invoke(instance))
            assertEquals(true, cls.getMethod("stableHash").invoke(instance))
        }
    }

    private fun compile(vararg definitions: ObjectDef): URLClassLoader {
        val sources = definitions.map { definition ->
            val writer = StringWriter()
            KotlinPoetSourceGenerator().write(definition, writer)
            val source = writer.toString()
            val resource = "/uncovered-programs/kotlin/${definition.simpleName}.txt"
            javaClass.getResourceAsStream(resource).use { expected ->
                assertNotNull(expected, "$resource\n$source")
                assertEquals(expected!!.bufferedReader(Charsets.UTF_8).readText(), source, definition.name)
            }
            source
        }
        return KotlinCompileAssertions.compileAndLoad(*sources.toTypedArray())
    }

    open class Labelled(@JvmField val label: String)

    interface First<T> {
        fun first(values: Array<T>): T
    }

    @Retention(AnnotationRetention.RUNTIME)
    annotation class Nested(val value: String)

    @Retention(AnnotationRetention.RUNTIME)
    annotation class Members(
        val texts: Array<String>,
        val numbers: IntArray,
        val none: Array<String>,
        val types: Array<kotlin.reflect.KClass<*>>,
        val policies: Array<AnnotationRetention>,
        val letter: Char,
        val ratio: Float,
        val nested: Nested
    )
}
