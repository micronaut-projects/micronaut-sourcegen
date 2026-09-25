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
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import org.junit.jupiter.api.Test
import java.util.function.Consumer
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Inferred method variables and fixed class variables need different argument conversions.
 */
class GenericInvocationRegressionTest {

    @Test
    fun inferredLibraryMethodDoesNotCastToUnscopedVariable() {
        val t = TypeDef.variable("T")
        val singletonList = MethodDef.builder("singletonList").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(TypeDef.parameterized(ClassTypeDef.of(List::class.java), t)).build()
        val def = ClassDef.builder("test.InferredLibraryCall")
            .addMethod(MethodDef.builder("call").returns(TypeDef.parameterized(List::class.java, String::class.java))
                .build { _, _ -> ClassTypeDef.of(java.util.Collections::class.java)
                    .invokeStatic(singletonList, ExpressionDef.constant("text")).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.Collections
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public open class InferredLibraryCall {
            |  public open fun call(): List<String> {
            |    return Collections.singletonList("text")
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun arrayArgumentConvertsToFixedClassVariable() {
        val t = TypeDef.variable("T")
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID).build()
        val def = ClassDef.builder("test.ArrayVariableArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.STRING.array()).returns(TypeDef.VOID)
                .build { self, p -> self.invoke(accept, p[0]) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.Array
            |import kotlin.String
            |
            |public open class ArrayVariableArgument<T> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public open fun call(`value`: Array<String>) {
            |    this.accept(`value` as T)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun primitiveArgumentConvertsToFixedClassVariable() {
        val t = TypeDef.variable("T", TypeDef.of(Number::class.java))
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID).build()
        val def = ClassDef.builder("test.PrimitiveVariableArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.VOID)
                .build { self, p -> self.invoke(accept, p[0]) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.Int
            |import kotlin.Number
            |
            |public open class PrimitiveVariableArgument<T : Number> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public open fun call(`value`: Int) {
            |    this.accept(`value` as T)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun primitiveReturnConvertsToFixedClassVariable() {
        val t = TypeDef.variable("T", TypeDef.of(Number::class.java))
        val def = ClassDef.builder("test.PrimitiveVariableReturn").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.constant(1).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Supplier
            |import kotlin.Number
            |
            |public open class PrimitiveVariableReturn<T : Number> : Supplier<T> {
            |  public override fun `get`(): T {
            |    return 1 as T
            |  }
            |}
            |""".trimMargin()))
    }
}
