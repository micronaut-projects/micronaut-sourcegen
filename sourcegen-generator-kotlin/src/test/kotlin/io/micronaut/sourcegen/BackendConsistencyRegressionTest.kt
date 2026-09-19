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
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/** Regression coverage for inferred generic argument conversions. */
class BackendConsistencyRegressionTest {

    @Test
    fun primitiveArgumentMustSatisfyInferredParameterizedBound() {
        val t = TypeDef.variable("T", TypeDef.parameterized(Comparable::class.java, String::class.java))
        val identity = MethodDef.builder("comparable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.PrimitiveBoundCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.BackendConsistencyRegressionTest
            |import kotlin.Any
            |import kotlin.Comparable
            |import kotlin.Int
            |import kotlin.String
            |
            |public open class PrimitiveBoundCall {
            |  public open fun call(`value`: Int): Any {
            |    return BackendConsistencyRegressionTest.Calls.comparable(`value` as Comparable<String>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun parameterizedArgumentMustSatisfyInferredBound() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, String::class.java))
        val identity = MethodDef.builder("list").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.ParameterizedBoundCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.parameterized(List::class.java, Int::class.javaObjectType)).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.BackendConsistencyRegressionTest
            |import kotlin.Any
            |import kotlin.Int
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class ParameterizedBoundCall {
            |  public open fun call(`value`: List<Int?>): Any {
            |    return BackendConsistencyRegressionTest.Calls.list(`value` as List<String>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun interfaceArgumentMustSatisfyInferredBound() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val identity = MethodDef.builder("text").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.InterfaceBoundCall")
            .addMethod(MethodDef.builder("call").addParameter("value", Runnable::class.java).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.BackendConsistencyRegressionTest
            |import java.lang.Runnable
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public open class InterfaceBoundCall {
            |  public open fun call(`value`: Runnable): Any {
            |    return BackendConsistencyRegressionTest.Calls.text(`value` as CharSequence)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun nullableParameterizedBoundAcceptsNull() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, String::class.java).makeNullable())
        val identity = MethodDef.builder("nullableList").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.NullableListBound")
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT.makeNullable())
                .build { _, _ -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, ExpressionDef.nullValue()).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.BackendConsistencyRegressionTest
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class NullableListBound {
            |  public open fun call(): Any? {
            |    return BackendConsistencyRegressionTest.Calls.nullableList(null as List<String>?)
            |  }
            |}
            |""".trimMargin()))
    }

    private fun assertSource(objectDef: ObjectDef, expected: String): String {
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(objectDef, writer)
            val source = writer.toString()
            assertEquals(expected, source)
            return source
        }
    }

    class Calls {
        companion object {
            @JvmStatic
            fun <T : Comparable<String>> comparable(value: T): T = value

            @JvmStatic
            fun <T : List<String>> list(value: T): T = value

            @JvmStatic
            fun <T : CharSequence> text(value: T): T = value

            @JvmStatic
            fun <T : List<String>?> nullableList(value: T): T = value
        }
    }
}
