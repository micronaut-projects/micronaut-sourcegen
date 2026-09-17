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
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.util.function.Consumer
import javax.lang.model.element.Modifier

/**
 * Arguments of fixed class variables need conversions from their bounds.
 */
class GenericConversionEdgeRegressionTest {

    @Test
    fun invocationConvertsClassBoundToFixedVariable() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID).build()
        val def = ClassDef.builder("test.ClassBoundArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", CharSequence::class.java).returns(TypeDef.VOID)
                .build { self, p -> self.invoke(accept, p[0]) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.CharSequence
            |
            |public class ClassBoundArgument<T : CharSequence> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public fun call(`value`: CharSequence) {
            |    this.accept(`value` as T)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun invocationConvertsParameterizedBoundToFixedVariable() {
        val t = TypeDef.variable("T", TypeDef.parameterized(List::class.java, Any::class.java))
        val accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.VOID).build()
        val def = ClassDef.builder("test.ParameterizedBoundArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.parameterized(List::class.java, String::class.java)).returns(TypeDef.VOID)
                .build { self, p -> self.invoke(accept, p[0]) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import java.util.function.Consumer
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public class ParameterizedBoundArgument<T : List<Any>> : Consumer<T> {
            |  public override fun accept(`value`: T) {
            |  }
            |
            |  public fun call(`value`: List<String>) {
            |    this.accept(`value` as T)
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
