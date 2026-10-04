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

import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Function
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Test

/**
 * Names that are valid in bytecode and have to be escaped in Kotlin: fields, lambda parameters and referenced methods
 * named like a Kotlin keyword. Every program is compiled and run.
 */
class IdentifierNameWriteTest {

    // A lambda parameter named with a Kotlin keyword is declared unescaped (`{object: String -> `object`}`), while its uses are escaped.
    @Test
    fun lambdaParameterNamedWithKeyword() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.L01")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(function)
                .build { _, _ -> function.getLambda().implement(listOf("object")) { _, p -> p[0].returning() }.returning() })
            .build()
        compile(def).use { }
    }

    // A static field named with a keyword is declared escaped but read as `L02.object` (renderVariable uses %L).
    @Test
    fun staticFieldNamedWithKeyword() {
        val type = ClassTypeDef.of("test.L02")
        val field = FieldDef.builder("object", TypeDef.STRING).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(ExpressionDef.constant("a")).build()
        val def = ClassDef.builder(type.name).addField(field)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { _, _ -> type.getStaticField(field).returning() })
            .build()
        compile(def).use { }
    }

    // A reference to a method named with a keyword is written `CompiledFixtures::when` (unescaped %L).
    @Test
    fun referenceToAMethodNamedWithKeyword() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val method = MethodDef.builder("when").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("s", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.M02")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(function)
                .build { _, _ -> function.staticMethodReference(ClassTypeDef.of(CompiledFixtures::class.java), method).returning() })
            .build()
        compile(def).use { }
    }
}
