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
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeHierarchy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

/**
 * A member class of a parameterized enclosing type is written with the enclosing type's arguments.
 */
class MemberTypeWriteTest {

    @Test
    fun memberTypesKeepTheEnclosingTypeArguments() {
        val classDef = ClassDef.builder("test.MemberTypes").addModifiers(Modifier.PUBLIC)
            .addMethod(returning("member"))
            .addMethod(returning("plainMember"))
            .addMethod(returning("memberArray"))
            .addMethod(returning("nestedMember"))
            .build()

        val source = StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(classDef, writer)
            writer.toString()
        }

        assertEquals(
            """
            package test

            import io.micronaut.sourcegen.MemberTypeWriteTest
            import kotlin.Array
            import kotlin.Int
            import kotlin.Long
            import kotlin.String

            public open class MemberTypes {
              public open fun member(): MemberTypeWriteTest.Outer<String>.Member<Int>? {
                return null as MemberTypeWriteTest.Outer<String>.Member<Int>?
              }

              public open fun plainMember(): MemberTypeWriteTest.Outer<String>.PlainMember? {
                return null as MemberTypeWriteTest.Outer<String>.PlainMember?
              }

              public open fun memberArray(): Array<MemberTypeWriteTest.Outer<String>.Member<Int>>? {
                return null as Array<MemberTypeWriteTest.Outer<String>.Member<Int>>?
              }

              public open fun nestedMember(): MemberTypeWriteTest.Outer<String>.Member<Int>.Nested<Long>? {
                return null as MemberTypeWriteTest.Outer<String>.Member<Int>.Nested<Long>?
              }
            }
            """.trimIndent() + "\n",
            source,
        )
        KotlinCompileAssertions.assertCompiles(source)
    }

    private fun returning(name: String): MethodDef =
        MethodDef.builder(name).addModifiers(Modifier.PUBLIC)
            .returns(TypeHierarchy.typeDefOf(Signatures::class.java.getMethod(name).genericReturnType))
            .build { _, _ -> ExpressionDef.nullValue().returning() }

    /**
     * A generic enclosing type.
     */
    class Outer<T> {
        /**
         * A generic member.
         */
        inner class Member<U> {
            /**
             * A member of a member.
             */
            inner class Nested<V>
        }

        /**
         * A member declaring no variables.
         */
        inner class PlainMember
    }

    /**
     * The signatures kotlinc gives the member types.
     */
    interface Signatures {
        fun member(): Outer<String>.Member<Int>

        fun plainMember(): Outer<String>.PlainMember

        fun memberArray(): Array<Outer<String>.Member<Int>>

        fun nestedMember(): Outer<String>.Member<Int>.Nested<Long>
    }
}
