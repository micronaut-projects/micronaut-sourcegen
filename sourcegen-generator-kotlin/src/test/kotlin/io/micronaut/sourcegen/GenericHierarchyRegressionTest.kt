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
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Inferred method arguments preserve their declaration scopes and bounds.
 */
class GenericHierarchyRegressionTest {

    @Test
    fun narrowedArgumentDoesNotUseTheCallersClassVariable() {
        val t = TypeDef.variable("T")
        val singletonList = MethodDef.builder("singletonList").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(TypeDef.parameterized(ClassTypeDef.of(List::class.java), t)).build()
        val get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object::class.java)
            .build { _, _ -> ExpressionDef.constant("text").returning() }
        val def = ClassDef.builder("test.NarrowedLibraryCall").addTypeVariable(TypeDef.variable("T", TypeDef.of(Number::class.java)))
            .addSuperinterface(TypeDef.parameterized(Supplier::class.java, String::class.java)).addMethod(get)
            .addMethod(MethodDef.builder("call").returns(TypeDef.parameterized(List::class.java, Any::class.java))
                .build { self, _ -> ClassTypeDef.of(Collections::class.java).invokeStatic(singletonList, self.invoke(get)).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.Collections
            |import java.util.function.Supplier
            |import kotlin.Any
            |import kotlin.Number
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public class NarrowedLibraryCall<T : Number> : Supplier<String> {
            |  public override fun `get`(): String {
            |    return "text"
            |  }
            |
            |  public fun call(): List<Any> {
            |    return Collections.singletonList((this.`get`() as Any))
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun inferredLibraryVariableUsesItsBoundForObjectArguments() {
        val task = TypeDef.parameterized(ClassTypeDef.of(ForkJoinTask::class.java), TypeDef.wildcard())
        val t = TypeDef.variable("T", task)
        val submit = MethodDef.builder("submit").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("task", t).returns(t).build()
        val def = ClassDef.builder("test.BoundedLibraryCall")
            .addMethod(MethodDef.builder("call").addParameter("pool", ForkJoinPool::class.java)
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(submit, p[1]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.concurrent.ForkJoinPool
            |import java.util.concurrent.ForkJoinTask
            |import kotlin.Any
            |
            |public class BoundedLibraryCall {
            |  public fun call(pool: ForkJoinPool, `value`: Any): Any {
            |    return pool.submit(`value` as ForkJoinTask<*>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun inferredArrayComponentDoesNotUseTheCallersClassVariable() {
        val t = TypeDef.variable("T")
        val copyOf = MethodDef.builder("copyOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("original", t.array()).addParameter("length", TypeDef.Primitive.INT).returns(t.array()).build()
        val def = ClassDef.builder("test.InferredArrayCall").addTypeVariable(TypeDef.variable("T", TypeDef.of(Number::class.java)))
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).addParameter("length", TypeDef.Primitive.INT)
                .returns(TypeDef.OBJECT.array())
                .build { _, p -> ClassTypeDef.of(Arrays::class.java).invokeStatic(copyOf, p[0], p[1]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.Arrays
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.Int
            |import kotlin.Number
            |
            |public class InferredArrayCall<T : Number> {
            |  public fun call(`value`: Any, length: Int): Array<Any> {
            |    return Arrays.copyOf(`value` as Array<Any>, length) as Array<Any>
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
}
