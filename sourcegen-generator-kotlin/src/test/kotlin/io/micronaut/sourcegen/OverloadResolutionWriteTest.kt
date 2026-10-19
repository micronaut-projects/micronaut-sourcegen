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
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Function
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Calls whose overload kotlinc would choose differently from the method the model names: after a smart cast, for a
 * narrower value or a variable of a bounded type, and for a primitive argument. The bytecode writer calls the method
 * by its descriptor, the generated source has to make kotlinc bind the same one. Every program is compiled and run.
 */
class OverloadResolutionWriteTest {

    // After `value is String` Kotlin smart casts the parameter, so `choose(value)` - which the model binds to `choose(T)` - selects `choose(CharSequence)`: "text" instead of "object". Only casts the generator inserts itself are tracked (markSmartCast).
    @Test
    fun instanceOfSmartCastChangesOverload() {
        val t = TypeDef.variable("V")
        val choose = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.B13a")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { _, p -> StatementDef.multi(
                    p[0].instanceOf(ClassTypeDef.of(String::class.java)).doIf(
                        ClassTypeDef.of(InvocationProgramRegressionTest.Calls::class.java).invokeStatic(choose, p[0]).returning()),
                    ExpressionDef.constant("other").returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.getMethod("call", Any::class.java).invoke(o, "text"))
        }
    }

    // The same after a cast of the model (`var text: String = value as String`): `pick(value)` selects pick(String) instead of the pick(Object) the model calls.
    @Test
    fun modelCastSmartCastChangesOverload() {
        val choose = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.B13b")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { _, p -> p[0].cast(TypeDef.STRING).newLocal("text") { _ ->
                    ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(choose, p[0]).returning() } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.getMethod("call", Any::class.java).invoke(o, "text"))
        }
    }

    // A String-typed value passed to `pick(Object)` while `pick(String)` exists: written `pick(text)`, which selects the String overload. Bytecode calls pick(Object). (The Java generator has the same defect.)
    @Test
    fun narrowerValueKeepsTheOverloadTheModelCalls() {
        val pick = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.D05")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("text", TypeDef.STRING).returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(pick, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.getMethod("call", String::class.java).invoke(o, "a"))
        }
    }

    // sourceTypeOf looks a parameter up in the method being rendered only: inside a lambda the narrowed parameter of the enclosing override is not found, so `pick(value)` selects pick(String) instead of the pick(Object) the model calls (outside a lambda `(value as Any)` is written).
    @Test
    fun narrowedParameterCapturedByALambdaKeepsTheOverload() {
        val pick = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val inner = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.D01")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function::class.java), TypeDef.STRING, inner))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> inner.getLambda().implement(listOf("ignored")) { _, _ ->
                    ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(pick, p[0]).returning() }.returning() })
            .build()
        compile(def).use { loader ->
            @Suppress("UNCHECKED_CAST")
            val o = newInstance(loader, def) as Function<String, Function<String, String>>
            assertEquals("object", o.apply("a").apply("b"))
        }
    }

    @Test
    fun castOfAValPropertyKeepsTheOverloadOfTheModel() {
        val value = FieldDef.builder("value", TypeDef.OBJECT).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val pick = Overloads::class.java.getMethod("pick", Any::class.java)
        val def = ClassDef.builder("test.ValSmartCast").addModifiers(Modifier.PUBLIC).addField(value).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> StatementDef.multi(
                    self.field(value).cast(TypeDef.STRING).newLocal("text"),
                    ClassTypeDef.of(Overloads::class.java).invokeStatic(pick, self.field(value)).returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("object", cls.getMethod("call").invoke(cls.getConstructor(Any::class.java).newInstance("text")))
        }
    }

    /**
     * A variable of the class named alone, `TypeDef.variable("T")`, passed to a call by name. The model resolves the
     * call without the bound the class declares, as `numeric(Object)`, and so do the bytecode writers. Kotlin reads
     * `T : Number` and would choose `numeric(Number)`, so the argument is cast to the model's overload.
     */
    @Test
    fun classVariableNamedAloneKeepsTheOverloadOfTheModel() {
        val def = ClassDef.builder("test.LexicalOverload").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number::class.java)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(NumericOverloads::class.java).invokeStatic("numeric", TypeDef.STRING, p[0]).returning() })
            .build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("object", cls.getMethod("call", Number::class.java).invoke(cls.getConstructor().newInstance(), 1))
        }
    }

    @Test
    fun nameResolvedCallWidensItsArgument() {
        // Long.valueOf(long) is the only numeric overload: an int argument widens to it
        val result = runMethod("test.LongValueOfInt", TypeDef.OBJECT, listOf(TypeDef.Primitive.INT), 5) { _, p ->
            ClassTypeDef.of(java.lang.Long::class.java).invokeStatic("valueOf", TypeDef.of(java.lang.Long::class.java), p[0])
                .cast(TypeDef.OBJECT).returning()
        }
        assertEquals(5L, result)
    }

    @Test
    fun pinningPrimitiveOverloadUsesNumericConversion() {
        val abs = Math::class.java.getMethod("abs", Long::class.javaPrimitiveType)
        val def = ClassDef.builder("test.PrimitiveOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Int::class.javaPrimitiveType!!)
                .returns(Long::class.javaPrimitiveType!!)
                .build { _, p -> ClassTypeDef.of(Math::class.java).invokeStatic(abs, p[0]).returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(3L, cls.getMethod("call", Int::class.javaPrimitiveType).invoke(cls.getConstructor().newInstance(), -3))
        }
    }

    object Overloads {
        @JvmStatic
        fun pick(value: Any?): String = "object"

        @JvmStatic
        fun pick(value: String?): String = "string"
    }
}
