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
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import org.junit.jupiter.api.Test
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import javax.lang.model.element.Modifier

/**
 * Regression coverage for bound conversion at generic invocation sites.
 */
class GenericInferenceRegressionTest {

    @Test
    fun chainedCalleeBoundIsResolvedBeforeCasting() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val u = TypeDef.variable("U", t)
        val identity = MethodDef.builder("chained").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addTypeVariable(u).addParameter("value", u).returns(u).build()
        val def = ClassDef.builder("test.ChainedCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.GenericInferenceRegressionTest
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public open class ChainedCall {
            |  public open fun call(`value`: Any): Any {
            |    return GenericInferenceRegressionTest.Calls.chained(`value` as CharSequence)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun recursiveCalleeBoundKeepsItsRawBound() {
        val t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable::class.java), TypeDef.variable("T")))
        val identity = MethodDef.builder("recursive").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.RecursiveCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.GenericInferenceRegressionTest
            |import kotlin.Any
            |import kotlin.Comparable
            |
            |public open class RecursiveCall {
            |  public open fun call(`value`: Any): Any {
            |    return GenericInferenceRegressionTest.Calls.recursive(`value` as Comparable<Any>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun inferredVariableSatisfiesEveryIntersectionBound() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java), TypeDef.of(Runnable::class.java))
        val identity = MethodDef.builder("intersection").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.IntersectionCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.GenericInferenceRegressionTest
            |import java.lang.Runnable
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public open class IntersectionCall {
            |  public open fun call(`value`: Any): Any {
            |    return GenericInferenceRegressionTest.Calls.intersection(run {
            |      `value` as CharSequence
            |      `value` as Runnable
            |      `value`
            |    })
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun inferredBoundCastsTheWholeConditionalArgument() {
        val task = TypeDef.parameterized(ClassTypeDef.of(ForkJoinTask::class.java), TypeDef.wildcard())
        val t = TypeDef.variable("T", task)
        val submit = MethodDef.builder("submit").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("task", t).returns(t).build()
        val def = ClassDef.builder("test.ConditionalBoundCall")
            .addMethod(MethodDef.builder("call").addParameter("pool", ForkJoinPool::class.java)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN).addParameter("left", TypeDef.OBJECT).addParameter("right", TypeDef.OBJECT)
                .returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(submit, p[1].isTrue().doIfElse(p[2], p[3])).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.concurrent.ForkJoinPool
            |import java.util.concurrent.ForkJoinTask
            |import kotlin.Any
            |import kotlin.Boolean
            |
            |public open class ConditionalBoundCall {
            |  public open fun call(
            |    pool: ForkJoinPool,
            |    flag: Boolean,
            |    left: Any,
            |    right: Any,
            |  ): Any {
            |    return pool.submit((if (flag) left else right) as ForkJoinTask<*>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun inferredBoundUsesReceiverArguments() {
        val x = TypeDef.variable("X", TypeDef.of(CharSequence::class.java))
        val u = TypeDef.variable("U", x)
        val identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u).returns(u).build()
        val receiver = TypeDef.parameterized(ClassTypeDef.of(Receiver::class.java), TypeDef.STRING)
        val def = ClassDef.builder("test.ReceiverBoundCall")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(identity, p[1]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.GenericInferenceRegressionTest
            |import kotlin.Any
            |import kotlin.String
            |
            |public open class ReceiverBoundCall {
            |  public open fun call(target: GenericInferenceRegressionTest.Receiver<String>, `value`: Any): Any {
            |    return target.identity(`value` as String)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun recursiveBoundInsideACollectionIsPreserved() {
        val t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable::class.java), TypeDef.variable("T")))
        val first = MethodDef.builder("recursiveList").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("values", TypeDef.parameterized(ClassTypeDef.of(List::class.java), t)).returns(t).build()
        val def = ClassDef.builder("test.RecursiveCollectionCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(first, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.GenericInferenceRegressionTest
            |import kotlin.Any
            |import kotlin.Comparable
            |import kotlin.collections.List
            |
            |public open class RecursiveCollectionCall {
            |  public open fun call(`value`: Any): Any {
            |    return GenericInferenceRegressionTest.Calls.recursiveList(`value` as List<Comparable<Any>>)
            |  }
            |}
            |""".trimMargin()))
    }

    class Calls {
        companion object {
            @JvmStatic
            fun <T : CharSequence, U : T> chained(value: U): U = value

            @JvmStatic
            fun <T : Comparable<T>> recursive(value: T): T = value

            @JvmStatic
            fun <T : Comparable<T>> recursiveList(values: List<T>): T = values.first()

            @JvmStatic
            fun <T> intersection(value: T): T where T : CharSequence, T : Runnable = value
        }
    }

    class Receiver<X : CharSequence> {
        fun <U : X> identity(value: U): U = value
    }
}
