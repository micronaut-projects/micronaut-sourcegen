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
import io.micronaut.sourcegen.KotlinCompileAssertions.assertSource
import io.micronaut.sourcegen.KotlinCompileAssertions.compile
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
        assertCompiles(
            assertSource(target, """
                |package test
                |
                |import java.util.function.Supplier
                |import kotlin.CharSequence
                |
                |public open class DependentTarget<A : CharSequence, T : A> : Supplier<T> {
                |  public override fun `get`(): T {
                |    return "text" as T
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
                |public open class DependentCaller {
                |  public open fun call(target: DependentTarget<in String, in String>): String {
                |    return DependentCaller.choose((target.`get`() as Any))
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

        assertCompiles(assertSource(classDef, """
            |package test
            |
            |import java.util.function.Function
            |
            |public open class VariableValue<T, V> : Function<V, T> {
            |  public override fun apply(`value`: V): T {
            |    return `value` as T
            |  }
            |}
            |""".trimMargin()))
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
        assertCompiles(
            assertSource(parent, """
                |package test
                |
                |import java.util.function.Supplier
                |
                |public abstract class GenericParent<T> : Supplier<T>
                |""".trimMargin()),
            assertSource(child, """
                |package test
                |
                |import kotlin.collections.List
                |
                |public open class GenericChild<T> : GenericParent<List<T>?>() {
                |  public override fun `get`(): List<T>? {
                |    return null as List<T>?
                |  }
                |}
                |""".trimMargin())
        )
    }

    // An override whose parameters are `Class<?>` / `List<?>` is written `Class<out Any>` / `List<out Any>`, which does not override `Class<*>` / `List<*>`. Expected star projections.
    @Test
    fun wildcardParameterOverride() {
        val def = ClassDef.builder("test.B17")
            .addSuperinterface(ClassTypeDef.of(TypeAcceptor::class.java))
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("type", TypeDef.parameterized(ClassTypeDef.of(Class::class.java), TypeDef.wildcard()))
                .addParameter("list", TypeDef.parameterized(ClassTypeDef.of(java.util.List::class.java), TypeDef.wildcard()))
                .returns(TypeDef.STRING)
                .build { _, p -> p[0].invoke("getName", TypeDef.STRING).returning() })
            .build()
        compile(def).use { loader ->
            assertEquals("java.lang.String", (newInstance(loader, def) as TypeAcceptor).accept(String::class.java, listOf<Any>()))
        }
    }

    // A generic method never declares its type variables (`fun id(value: T): T`): buildFunction does not call addTypeVariable(s).
    @Test
    fun genericMethodDeclaresItsVariable() {
        val t = TypeDef.variable("T")
        val def = ClassDef.builder("test.C20")
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
                .addParameter("value", t).returns(t)
                .build { _, p -> p[0].returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("id", Any::class.java).invoke(o, "a"))
        }
    }

    @Test
    fun explicitBridgeDoesNotDuplicateNarrowedMethod() {
        val erased = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Any::class.java).returns(Any::class.java)
            .build { _, p -> p[0].returning() }
        val specialized = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", String::class.java).returns(String::class.java)
            .build { _, p -> p[0].returning() }
        val def = ClassDef.builder("test.BridgedFunction").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function::class.java, String::class.java, String::class.java))
            .addMethod(erased).addMethod(specialized).build()
        KotlinCompileAssertions.assertCompiles(render(def))
    }

    @Test
    fun returnCastKeepsMethodTypeVariableInScope() {
        val t = TypeDef.variable("T")
        val def = ClassDef.builder("test.GenericReturnCast").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
                .addParameter("value", Any::class.java).returns(t)
                .build { _, p -> p[0].returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("value", cls.getMethod("call", Any::class.java).invoke(cls.getConstructor().newInstance(), "value"))
        }
    }

    /** Declares star projected parameters. */
    interface TypeAcceptor {
        fun accept(type: Class<*>, list: List<*>): String
    }
}
