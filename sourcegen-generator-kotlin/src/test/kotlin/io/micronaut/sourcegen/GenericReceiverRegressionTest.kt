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

import io.micronaut.sourcegen.KotlinCompileAssertions.assertSource
import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.lang.model.element.Modifier

/**
 * Inferred library calls preserve the type supplied by the caller.
 */
class GenericReceiverRegressionTest {

    @Test
    fun objectArgumentDoesNotUseShadowingClassVariable() {
        val t = TypeDef.variable("T")
        val singletonList = MethodDef.builder("singletonList").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(TypeDef.parameterized(ClassTypeDef.of(List::class.java), t)).build()
        val def = ClassDef.builder("test.ShadowedLibraryCall").addTypeVariable(TypeDef.variable("T", TypeDef.of(Number::class.java)))
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.parameterized(List::class.java, Any::class.java))
                .build { _, p -> ClassTypeDef.of(java.util.Collections::class.java).invokeStatic(singletonList, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.Collections
            |import kotlin.Any
            |import kotlin.Number
            |import kotlin.collections.List
            |
            |public open class ShadowedLibraryCall<T : Number> {
            |  public open fun call(`value`: Any): List<Any> {
            |    return Collections.singletonList(`value`)
            |  }
            |}
            |""".trimMargin()))
    }

    // A reflective `accept(Object)` of a compiled `GenericParent<T>` called on super of a `GenericParent<String>` subclass with an Object value: the parameter is not narrowed to String, no cast is written. (Java generator: same.)
    @Test
    fun superCallWithObjectArgument() {
        val accept = MethodDef.of(GenericParent::class.java.getMethod("accept", Any::class.java))
        val def = ClassDef.builder("test.C04")
            .superclass(TypeDef.parameterized(GenericParent::class.java, String::class.java))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { aThis, p -> aThis.superRef().invoke(accept, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("call", Any::class.java).invoke(o, "a"))
        }
    }

    // The same through a `GenericParent<String>` parameter; only hand-built methods naming the variable are converted, not MethodDef.of(Method), whose parameters are erased.
    @Test
    fun parameterizedReceiverCall() {
        val accept = MethodDef.of(GenericParent::class.java.getMethod("accept", Any::class.java))
        val def = ClassDef.builder("test.C04b")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(GenericParent::class.java, String::class.java))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { _, p -> p[0].invoke(accept, p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("call", GenericParent::class.java, Any::class.java).invoke(o, GenericParent<String>(), "a"))
        }
    }

    /** A generic parent. */
    open class GenericParent<T> {
        open fun accept(value: T): String = value.toString()
    }
}
