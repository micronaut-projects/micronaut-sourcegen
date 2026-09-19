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
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Generic variables remain distinct after override resolution.
 */
class GenericConversionScopeRegressionTest {

    @Test
    fun invocationConvertsAnotherVariableToFixedClassVariable() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, Any::class.java))
        val v = TypeDef.variable("V", TypeDef.parameterized(List::class.java, String::class.java))
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID)
            .build()
        val def = ClassDef.builder("test.VariableArgument").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", v).returns(TypeDef.VOID)
                .build { self, p -> self.invoke(accept, p[0]) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class VariableArgument<T : List<Any>, V : List<String>> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public open fun call(`value`: V) {
            |    this.accept(`value` as T)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun returnConvertsAnotherVariableToFixedClassVariable() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, Any::class.java))
        val v = TypeDef.variable("V", TypeDef.parameterized(List::class.java, String::class.java))
        val def = ClassDef.builder("test.VariableReturn").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function::class.java), v, t))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class VariableReturn<T : List<Any>, V : List<String>> : Function<V, T> {
            |  public override fun apply(`value`: V): T {
            |    return `value` as T
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun referenceConvertsAnotherVariableToFixedClassVariable() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, Any::class.java))
        val v = TypeDef.variable("V", TypeDef.parameterized(List::class.java, String::class.java))
        val consumer = TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), v)
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID)
            .build()
        val def = ClassDef.builder("test.VariableReferenceArgument").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("delegate").returns(consumer)
                .build { self, _ -> consumer.methodReference(self, accept).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class VariableReferenceArgument<T : List<Any>, V : List<String>> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public open fun `delegate`(): Consumer<V> {
            |    return Consumer<V> { arg0 -> this.accept(arg0 as T) }
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun narrowedInvocationConvertsToFixedClassVariable() {
        val strings = TypeDef.parameterized(List::class.java, String::class.java)
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, Any::class.java))
        val get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build { _, _ -> TypeDef.parameterized(java.util.ArrayList::class.java, String::class.java).instantiate().returning() }
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID)
            .build()
        val def = ClassDef.builder("test.NarrowedArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), strings))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t))
            .addMethod(get).addMethod(accept)
            .addMethod(MethodDef.builder("call").returns(TypeDef.VOID)
                .build { self, _ -> self.invoke(accept, self.invoke(get)) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.ArrayList
            |import java.util.function.Consumer
            |import java.util.function.Supplier
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class NarrowedArgument<T : List<Any>> : Supplier<List<String>>, Consumer<T> {
            |  public override fun `get`(): List<String> {
            |    return ArrayList()
            |  }
            |
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public open fun call() {
            |    this.accept((this.`get`() as T))
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
