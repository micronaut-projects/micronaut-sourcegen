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
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import java.io.StringWriter
import java.io.Serializable
import java.net.URLClassLoader
import java.util.function.Function
import javax.lang.model.element.Modifier

/** Exercises invocation behavior through DSL-generated programs. */
class InvocationProgramRegressionTest {
    @Test
    fun inheritedGenericCallIgnoresUnrelatedSubclassOverload() {
        val def = receiver("OverloadedInheritedCall", ClassTypeDef.of(OverloadedChild::class.java))
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("text", cls.getMethod("call", OverloadedChild::class.java, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), OverloadedChild(), "text"))
        }
    }

    @Test
    fun generatedSubclassBindsCompiledParent() {
        val child = ClassDef.builder("test.GeneratedChild")
            .superclass(TypeDef.parameterized(CalleeBoundsRegressionTest.Parent::class.java, String::class.java)).build()
        val def = receiver("MixedHierarchyCall", child.asTypeDef())
        compile(child, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val target = loader.loadClass(child.name)
            assertEquals("text", cls.getMethod("call", target, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), target.getConstructor().newInstance(), "text"))
        }
    }

    @Test
    fun inheritedGenericCallFollowsDeepCompiledHierarchy() {
        val def = receiver("DeepCompiledCall", ClassTypeDef.of(DeepChild9::class.java))
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("text", cls.getMethod("call", DeepChild9::class.java, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), DeepChild9(), "text"))
        }
    }

    @Test
    fun adaptedReferenceAcceptsNullableReceiverType() {
        val apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT).build { _, p -> p[0].returning() }
        val target = ClassDef.builder("test.ReferenceTarget")
            .addSuperinterface(TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)).addMethod(apply).build()
        val function = TypeDef.parameterized(Function::class.java, Any::class.java, Any::class.java)
        val def = ClassDef.builder("test.NullableReferenceCall")
            .addMethod(MethodDef.builder("reference").addParameter("target", target.asTypeDef().makeNullable()).returns(function)
                .build { _, p -> function.methodReference(p[0], apply).returning() }).build()
        compile(target, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val targetClass = loader.loadClass(target.name)
            @Suppress("UNCHECKED_CAST")
            val reference = cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(), targetClass.getConstructor().newInstance()) as Function<Any, Any>
            assertEquals("text", reference.apply("text"))
        }
    }

    @Test
    fun parameterizedArgumentBindsBothReceiverAndMethodVariables() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val x = TypeDef.variable("X")
        val parameter = TypeDef.parameterized(ClassTypeDef.of(Map::class.java), x, t)
        val method = MethodDef.builder("map").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("value", parameter).returns(parameter).build()
        val def = ClassDef.builder("test.MixedMapCall")
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(MapTarget::class.java, String::class.java))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(method, p[1]).returning() }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val value = mapOf("key" to "text")
            assertSame(value, cls.getMethod("call", MapTarget::class.java, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), MapTarget<String>(), value))
        }
    }

    @Test
    fun nullableGenericParameterPreservesNull() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val method = MethodDef.builder("nullable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t.makeNullable()).returns(t.makeNullable()).build()
        val def = ClassDef.builder("test.NullableVariableCall")
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT.makeNullable())
                .build { _, _ -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, ExpressionDef.nullValue()).returning() }).build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertNull(cls.getMethod("call").invoke(cls.getConstructor().newInstance()))
        }
    }

    @Test
    fun genericArrayArgumentsSatisfyEveryComponentBound() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java), TypeDef.of(Serializable::class.java))
        val method = MethodDef.builder("intersectionArray").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t.array()).returns(TypeDef.OBJECT).build()
        val def = ClassDef.builder("test.IntersectionArrayCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]).returning() }).build()
        // Require a compilable program without prescribing how intersection-array adaptation is lowered.
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            val value = arrayOf("text")
            assertSame(value, cls.getMethod("call", Any::class.java).invoke(cls.getConstructor().newInstance(), value))
        }
    }

    @TestFactory
    fun insertedCastsPreserveLaterOverloadSelection() = listOf("intersection", "generic", "varargs").map { kind ->
        dynamicTest(kind) {
            val bounds = if (kind == "intersection") arrayOf(TypeDef.of(CharSequence::class.java), TypeDef.of(Runnable::class.java))
                else arrayOf(TypeDef.of(CharSequence::class.java))
            val t = TypeDef.variable("T", *bounds)
            val first = MethodDef.builder(if (kind == "intersection") "intersection" else "text")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
            val second = MethodDef.builder(if (kind == "varargs") "varargs" else "choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            val parameter = if (kind == "generic") TypeDef.variable("V").also { second.addTypeVariable(it) }
                else if (kind == "varargs") TypeDef.OBJECT.array() else TypeDef.OBJECT
            val call = second.addParameter("value", parameter).returns(String::class.java).build()
            val def = ClassDef.builder("test.AfterCast$kind")
                .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(String::class.java)
                    .build { _, p -> StatementDef.multi(
                        ClassTypeDef.of(CalleeBoundsRegressionTest.Calls::class.java).invokeStatic(first, p[0]),
                        ClassTypeDef.of(Calls::class.java).invokeStatic(call, p[0]).returning()) }).build()
            compile(def).use { loader ->
                val cls = loader.loadClass(def.name)
                assertEquals("object", cls.getMethod("call", Any::class.java)
                    .invoke(cls.getConstructor().newInstance(), CalleeBoundsRegressionTest.RunningText("text")))
            }
        }
    }

    private fun receiver(name: String, receiver: ClassTypeDef): ClassDef {
        val x = TypeDef.variable("X", TypeDef.of(CharSequence::class.java))
        val u = TypeDef.variable("U", x)
        val method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u).returns(u).build()
        return ClassDef.builder("test.$name")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(method, p[1]).returning() }).build()
    }

    private fun compile(vararg definitions: ObjectDef): URLClassLoader {
        val sources = definitions.map { definition ->
            val source = render(definition)
            val resource = "/invocation-programs/kotlin/${definition.simpleName}.txt"
            val expected = javaClass.getResourceAsStream(resource)
            assertNotNull(expected, resource)
            val snapshot = expected!!.bufferedReader().use { it.readText() }
            assertEquals(snapshot, source, definition.name)
            source
        }
        return KotlinCompileAssertions.compileAndLoad(*sources.toTypedArray())
    }

    private fun render(definition: ObjectDef): String {
        val writer = StringWriter()
        KotlinPoetSourceGenerator().write(definition, writer)
        return writer.toString()
    }

    class OverloadedChild : CalleeBoundsRegressionTest.Parent<String>() {
        fun identity(value: Int): Int = value
    }

    open class DeepChild0 : CalleeBoundsRegressionTest.Parent<String>()
    open class DeepChild1 : DeepChild0()
    open class DeepChild2 : DeepChild1()
    open class DeepChild3 : DeepChild2()
    open class DeepChild4 : DeepChild3()
    open class DeepChild5 : DeepChild4()
    open class DeepChild6 : DeepChild5()
    open class DeepChild7 : DeepChild6()
    open class DeepChild8 : DeepChild7()
    class DeepChild9 : DeepChild8()

    class MapTarget<X> {
        fun <T : CharSequence> map(value: Map<X, T>): Map<X, T> = value
    }

    class Calls {
        companion object {
            @JvmStatic fun <T> intersectionArray(value: Array<T>): Any where T : CharSequence, T : Serializable = value
            @JvmStatic fun <T : CharSequence> nullable(value: T?): T? = value
            @JvmStatic fun <T> choose(value: T): String = "object"
            @JvmStatic fun choose(value: CharSequence): String = "text"
            @JvmStatic fun varargs(vararg value: Any): String = "object"
            @JvmStatic fun varargs(value: CharSequence): String = "text"
        }
    }
}
