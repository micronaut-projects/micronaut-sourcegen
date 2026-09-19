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
            |public class ShadowedLibraryCall<T : Number> {
            |  public fun call(`value`: Any): List<Any> {
            |    return Collections.singletonList(`value`)
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
