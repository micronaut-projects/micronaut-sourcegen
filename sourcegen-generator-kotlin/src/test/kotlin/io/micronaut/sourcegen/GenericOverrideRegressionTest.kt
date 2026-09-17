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

import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Compilation regressions for generic overrides and the expressions that use their normalized signatures.
 */
class GenericOverrideRegressionTest {

    @Test
    fun capturedResultKeepsTheBoundOfAnotherCapturedVariable() {
        val a = TypeDef.variable("A", TypeDef.of(CharSequence::class.java))
        val t = TypeDef.variable("T", a)
        val get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build { _, _ -> ExpressionDef.constant("text").returning() }
        val target = ClassDef.builder("test.DependentTarget").addTypeVariable(a).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), t))
            .addMethod(get)
            .build()
        val receiver = TypeDef.parameterized(
            target.asTypeDef(),
            TypeDef.wildcardSupertypeOf(TypeDef.STRING),
            TypeDef.wildcardSupertypeOf(TypeDef.STRING)
        )
        val chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("selected").returning() }
        val chooseSequence = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", CharSequence::class.java).returns(TypeDef.Primitive.INT)
            .build { _, _ -> ExpressionDef.constant(2).returning() }
        val caller = ClassDef.builder("test.DependentCaller")
            .addMethod(chooseObject)
            .addMethod(chooseSequence)
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).returns(String::class.java)
                .build { _, parameters ->
                    ClassTypeDef.of("test.DependentCaller")
                        .invokeStatic(chooseObject, parameters[0].invoke(get)).returning()
                })
            .build()

        // Choosing the CharSequence overload instead of the modeled Object overload cannot return a String.
        assertCompiles(writeSource(target), writeSource(caller))
    }

    @Test
    fun normalizedReturnConvertsAValueOfAnotherVariable() {
        val t = TypeDef.variable("T")
        val v = TypeDef.variable("V")
        val classDef = ClassDef.builder("test.VariableValue").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function::class.java), v, t))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", v).returns(TypeDef.OBJECT)
                .build { _, parameters -> parameters[0].returning() })
            .build()

        assertCompiles(writeSource(classDef))
    }

    @Test
    fun hierarchySubstitutesTypeArgumentsOnlyOncePerEdge() {
        val t = TypeDef.variable("T")
        val parent = ClassDef.builder("test.GenericParent").addModifiers(Modifier.ABSTRACT).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), t))
            .build()
        val child = ClassDef.builder("test.GenericChild").addTypeVariable(t)
            .superclass(TypeDef.parameterized(
                parent.asTypeDef(), TypeDef.parameterized(ClassTypeDef.of(List::class.java), t)
            ))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.nullValue().returning() })
            .build()

        // Supplier<T> becomes Supplier<List<T>>, not Supplier<List<List<T>>>.
        assertCompiles(writeSource(parent), writeSource(child))
    }

    private fun writeSource(objectDef: ObjectDef): String {
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(objectDef, writer)
            return writer.toString()
        }
    }
}
