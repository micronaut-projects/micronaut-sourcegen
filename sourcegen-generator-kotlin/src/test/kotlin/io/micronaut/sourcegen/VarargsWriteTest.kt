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

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Variable arity methods: an array passed for a `vararg` parameter is spread, and an override of a `vararg` method
 * declared with the array of the bytecode signature is written as a `vararg` override. Every program is compiled and
 * run.
 */
class VarargsWriteTest {

    // An override of a Kotlin `vararg` method declared with the array parameter of the bytecode signature is written `parts: Array<String>`: overrides nothing.
    @Test
    fun varargOverride() {
        val def = ClassDef.builder("test.B04")
            .addSuperinterface(ClassTypeDef.of(Joiner::class.java))
            .addMethod(MethodDef.builder("join").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("parts", TypeDef.STRING.array()).returns(TypeDef.STRING)
                .build { _, p -> p[0].arrayElement(0).returning() })
            .build()
        compile(def).use { loader ->
            assertEquals("a", (newInstance(loader, def) as Joiner).join("a", "b"))
        }
    }

    // An array passed to a varargs super constructor is not spread: the parent sees one element, the array itself.
    @Test
    fun superConstructorVarargs() {
        val def = ClassDef.builder("test.B08b")
            .superclass(ClassTypeDef.of(VarargsParent::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT.array())
                .build { aThis, p -> aThis.superRef().invokeSuperConstructor(listOf(TypeDef.OBJECT.array()), p[0]) })
            .build()
        compile(def).use { loader ->
            val e = loader.loadClass(def.name).getConstructor(kotlin.Array<Any>::class.java)
                .newInstance(arrayOf<Any>("a", "b") as Any) as VarargsParent
            assertEquals(2, e.size)
        }
    }

    // An `Object[]` value passed for a varargs parameter is never spread with `*`: the callee sees a single element where bytecode passes the array as the varargs.
    @Test
    fun arrayPassedAsVarargs() {
        val count = MethodDef.builder("count").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.Primitive.INT).build()
        val def = ClassDef.builder("test.C01")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.Primitive.INT)
                .build { _, p -> ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(count, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, o.javaClass.getMethod("call", kotlin.Array<Any>::class.java).invoke(o, arrayOf<Any>("a", "b") as Any))
        }
    }

    // The same through `String.format(String, Object...)`: MissingFormatArgumentException.
    @Test
    fun arrayPassedToJavaVarargs() {
        val format = String::class.java.getMethod("format", String::class.java, kotlin.Array<Any>::class.java)
        val def = ClassDef.builder("test.C01b")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(String::class.java).invokeStatic(format, ExpressionDef.constant("%s-%s"), p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a-b", o.javaClass.getMethod("call", kotlin.Array<Any>::class.java).invoke(o, arrayOf<Any>("a", "b") as Any))
        }
    }

    @Test
    fun boundedGenericVarargsAreSpread() {
        val t = TypeDef.variable("T", TypeDef.of(Number::class.java))
        val first = MethodDef.builder("first").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("values", t.array()).returns(t).build()
        val def = ClassDef.builder("test.BoundedVarargs").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.of(Number::class.java).array()).returns(Number::class.java)
                .build { _, p -> ClassTypeDef.of(VarargsCalls::class.java).invokeStatic(first, p[0]).returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(42, cls.getMethod("call", Array<Number>::class.java)
                .invoke(cls.getConstructor().newInstance(), arrayOf<Number>(42)))
        }
    }

    @Test
    fun unrelatedVarargsOverloadDoesNotChangeOverride() {
        val def = ClassDef.builder("test.ArrayOverride").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(OverloadedArrays::class.java))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", TypeDef.STRING.array()).returns(String::class.java)
                .build { _, _ -> ExpressionDef.constant("override").returning() }).build()
        KotlinCompileAssertions.assertCompiles(render(def))
    }

    @Test
    fun arrayVarargRetainsSpreadAfterEarlierCast() {
        val asList = java.util.Arrays::class.java.getMethod("asList", Array<Any>::class.java)
        val def = ClassDef.builder("test.SpreadAfterCast").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT.array())
                .returns(Any::class.java).build { _, p -> StatementDef.multi(
                    p[0].cast(TypeDef.STRING.array()).newLocal("checked"),
                    ClassTypeDef.of(java.util.Arrays::class.java).invokeStatic(asList, p[0]).returning()) }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(listOf("a", "b"), cls.getMethod("call", Array<Any>::class.java)
                .invoke(cls.getConstructor().newInstance(), arrayOf("a", "b")))
        }
    }

    @Test
    fun varargOverrideCanBeInvokedOnGeneratedReceiver() {
        val count = MethodDef.builder("count").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("values", TypeDef.STRING.array()).returns(Int::class.javaPrimitiveType!!)
            .build { _, _ -> ExpressionDef.constant(2).returning() }
        val def = ClassDef.builder("test.GeneratedVarargCall").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(VarargCounter::class.java)).addMethod(count)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.STRING.array()).returns(Int::class.javaPrimitiveType!!)
                .build { self, p -> self.invoke(count, p[0]).returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(2, cls.getMethod("call", Array<String>::class.java)
                .invoke(cls.getConstructor().newInstance(), arrayOf("a", "b")))
        }
    }

    /** Declares a vararg method. */
    interface Joiner {
        fun join(vararg parts: String): String
    }

    /** Takes varargs in its constructor. */
    open class VarargsParent(vararg values: Any) {
        val size = values.size
    }

    open class OverloadedArrays {
        open fun accept(values: Array<String>): String = "array"
        open fun accept(vararg values: Int): String = "varargs"
    }

    class VarargsCalls {
        companion object {
            @JvmStatic
            fun <T : Number> first(vararg values: T): T = values[0]
        }
    }

    interface VarargCounter {
        fun count(vararg values: String): Int
    }
}
