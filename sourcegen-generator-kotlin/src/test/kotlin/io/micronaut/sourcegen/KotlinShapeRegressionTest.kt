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

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.net.URLClassLoader
import java.util.function.Consumer
import javax.lang.model.element.Modifier

/**
 * Shapes the Java generator writes and runs - see its UncoveredShapeProgramTest - which the Kotlin generator
 * rejects, writes as source kotlinc rejects, or writes with another meaning. The programs are compiled and run; the
 * source they are written as is not prescribed.
 */
class KotlinShapeRegressionTest {

    /**
     * A conditional as the operand of `||` is written without parentheses: `if (a) b else c || a` is
     * `if (a) b else (c || a)`, not the `(a ? b : c) || a` of the model.
     */
    @Test
    fun conditionalOperandKeepsItsGrouping() {
        val bool: Class<*> = Boolean::class.javaPrimitiveType!!
        val def = ClassDef.builder("test.ConditionalOperand").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", bool).addParameter("b", bool).addParameter("c", bool).returns(bool)
                .build { _, p -> p[0].isTrue.doIfElse(p[1], p[2]).isTrue.or(p[0].isTrue).returning() }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(true, cls.getMethod("call", bool, bool, bool).invoke(cls.getConstructor().newInstance(), true, false, false))
        }
    }

    /**
     * The infix `and`, `or`, `xor`, `shl` of Kotlin share one precedence and group to the left, unlike the operators
     * of Java the parentheses are decided by: `a | b & c` is written `a or b and c`, which is `(a or b) and c`.
     */
    @Test
    fun bitwiseOperandsKeepTheirGrouping() {
        val int: Class<*> = Int::class.javaPrimitiveType!!
        val def = ClassDef.builder("test.BitwiseGrouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("a", int).addParameter("b", int).addParameter("c", int).returns(IntArray::class.java)
                .build { _, p ->
                    val a: ExpressionDef = p[0]
                    val b: ExpressionDef = p[1]
                    val c: ExpressionDef = p[2]
                    TypeDef.Primitive.INT.array().instantiate(
                        a.math(OpType.BITWISE_OR, b.math(OpType.BITWISE_AND, c)),
                        a.math(OpType.BITWISE_AND, b.math(OpType.BITWISE_LEFT_SHIFT, c)),
                        a.math(OpType.BITWISE_XOR, b.math(OpType.BITWISE_AND, c))
                    ).returning()
                }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val a = 12
            val b = 10
            val c = 1
            assertArrayEquals(intArrayOf(a or (b and c), a and (b shl c), a xor (b and c)),
                cls.getMethod("call", int, int, int).invoke(cls.getConstructor().newInstance(), a, b, c) as IntArray)
        }
    }

    /**
     * `TypeDef.SUPER` as a type is written as the model's own marker class, `io.micronaut.sourcegen.model.SuperType`.
     */
    @Test
    fun superTypeIsWrittenAsTheSuperclass() {
        val consumer = TypeDef.parameterized(Consumer::class.java, TypeDef.wildcardSupertypeOf(TypeDef.STRING))
        val accept = Consumer::class.java.getMethod("accept", Any::class.java)
        val def = ClassDef.builder("test.TypeKinds").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Holder::class.java))
            .addMethod(MethodDef.builder("feed").addModifiers(Modifier.PUBLIC).addParameter("consumer", consumer)
                .addParameter("value", TypeDef.STRING).returns(TypeDef.SUPER)
                .build { self, p -> StatementDef.multi(p[0].invoke(accept, p[1]), self.returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val consumed = ArrayList<Any>()
            assertEquals(instance, cls.getMethod("feed", Consumer::class.java, String::class.java).invoke(instance, Consumer<Any> { consumed.add(it) }, "text"))
            assertEquals(listOf<Any>("text"), consumed)
        }
    }

    /**
     * A field declared by another type than the instance has is read without the cast to its declaring type.
     */
    @Test
    fun fieldOfAnotherTypeIsReadThroughItsDeclaringType() {
        val def = ClassDef.builder("test.ForeignField").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("label").addModifiers(Modifier.PUBLIC).addParameter("other", Any::class.java).returns(String::class.java)
                .build { _, p -> VariableDef.Field(p[0], ClassTypeDef.of(Holder::class.java), "label", TypeDef.STRING).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("held", cls.getMethod("label", Any::class.java).invoke(cls.getConstructor().newInstance(), Holder()))
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

    /**
     * An `Object` passed to the typed constructor of the superclass is converted by the Java generator -
     * `super((String) label)` - and written as it is here, which kotlinc rejects.
     */
    @Test
    fun superConstructorTakesAnErasedArgument() {
        val def = ClassDef.builder("test.ErasedSuperArgument").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("label", Any::class.java)
                .build { self, p -> self.superRef().invokeSuperConstructor(listOf<TypeDef>(TypeDef.STRING), p[0]) })
            .build()
        compile(def).use { loader ->
            val instance = loader.loadClass(def.name).getConstructor(Any::class.java).newInstance("text")
            assertEquals("text", (instance as Labelled).label)
        }
    }

    /**
     * A static method is rendered without its definition, so reading a field of an instance it is given fails
     * with "Field 'this' is not available".
     */
    @Test
    fun staticMethodReadsFieldOfAnInstance() {
        val int: Class<*> = Int::class.javaPrimitiveType!!
        val weight = FieldDef.builder("weight", int).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val type = ClassTypeDef.of("test.StaticFieldReader")
        val def = ClassDef.builder(type.name).addModifiers(Modifier.PUBLIC).addField(weight).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("weightOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", type).returns(int)
                .build { _, p -> p[0].field(weight).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val companion = cls.getField("Companion").get(null)
            assertEquals(7, companion.javaClass.getMethod("weightOf", cls).invoke(companion, cls.getConstructor(int).newInstance(7)))
        }
    }

    private fun compile(vararg definitions: ObjectDef): URLClassLoader =
        KotlinCompileAssertions.compileAndLoad(*definitions.map { definition ->
            StringWriter().also { KotlinPoetSourceGenerator().write(definition, it) }.toString()
        }.toTypedArray())

    open class Labelled(@JvmField val label: String)

    open class Holder {
        @JvmField
        var label: String = "held"
    }
}
