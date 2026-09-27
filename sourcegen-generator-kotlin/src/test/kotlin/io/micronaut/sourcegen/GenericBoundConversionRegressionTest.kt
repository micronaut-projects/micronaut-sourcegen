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
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Captured bounds keep the substitutions of the receiver through dependent variables.
 */
class GenericBoundConversionRegressionTest {
    @Test
    fun capturedBoundKeepsRemainingReceiverArguments() {
        val a = TypeDef.variable("A")
        val b = TypeDef.variable("B", a)
        val c = TypeDef.variable("C", b)
        val get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build { _, _ -> ExpressionDef.constant("text").returning() }
        val target = ClassDef.builder("test.ChainedTarget").addTypeVariable(a).addTypeVariable(b).addTypeVariable(c)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), c)).addMethod(get)
            .build()
        val receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.of(CharSequence::class.java),
            TypeDef.wildcardSupertypeOf(TypeDef.STRING), TypeDef.wildcardSupertypeOf(TypeDef.STRING))
        val selected = MethodDef.builder("choose").addModifiers(Modifier.STATIC).addParameter("value", TypeDef.OBJECT).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("selected").returning() }
        val other = MethodDef.builder("choose").addModifiers(Modifier.STATIC).addParameter("value", CharSequence::class.java).returns(TypeDef.Primitive.INT)
            .build { _, _ -> ExpressionDef.constant(2).returning() }
        val caller = ClassDef.builder("test.ChainedCaller").addMethod(selected).addMethod(other)
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).returns(String::class.java)
                .build { _, parameters -> ClassTypeDef.of("test.ChainedCaller").invokeStatic(selected, parameters[0].invoke(get)).returning() }).build()
        assertCompiles(
            assertSource(target, """
                |package test
                |
                |import java.util.function.Supplier
                |
                |public open class ChainedTarget<A, B : A, C : B> : Supplier<C> {
                |  public override fun `get`(): C {
                |    return "text" as C
                |  }
                |}
                |""".trimMargin()),
            assertSource(caller, """
                |package test
                |
                |import kotlin.Any
                |import kotlin.CharSequence
                |import kotlin.Int
                |import kotlin.String
                |
                |public open class ChainedCaller {
                |  public open fun call(target: ChainedTarget<CharSequence, in String, in String>): String {
                |    return ChainedCaller.choose((target.`get`() as Any))
                |  }
                |
                |  public companion object {
                |    public fun choose(`value`: Any): String {
                |      return "selected"
                |    }
                |
                |    public fun choose(`value`: CharSequence): Int {
                |      return 2
                |    }
                |  }
                |}
                |""".trimMargin())
        )
    }
}
