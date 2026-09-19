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
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import java.io.StringWriter
import javax.lang.model.element.Modifier
import org.jspecify.annotations.NonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Regression coverage for inferred argument bounds in generated Kotlin. */
class CalleeBoundsRegressionTest {

    @Test
    fun inheritedCompiledReceiverBindsDeclaringVariable() {
        val x = TypeDef.variable("X", TypeDef.of(CharSequence::class.java))
        val u = TypeDef.variable("U", x)
        val method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("value", u).returns(u).build()
        val def = ClassDef.builder("test.InheritedReceiverCall")
            .addMethod(MethodDef.builder("call").addParameter("target", Child::class.java).addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(method, p[1]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.String
            |
            |public class InheritedReceiverCall {
            |  public fun call(target: CalleeBoundsRegressionTest.Child, `value`: Any): Any {
            |    return target.identity(`value` as String)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun mixedReceiverAndRecursiveVariablesAreSubstituted() {
        val x = TypeDef.variable("X")
        val t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Link::class.java), x, TypeDef.variable("T")))
        val method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.MixedReceiverCall")
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(Mixed::class.java, String::class.java))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(method, p[1]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.String
            |
            |public class MixedReceiverCall {
            |  public fun call(target: CalleeBoundsRegressionTest.Mixed<String>, `value`: Any): Any {
            |    return target.identity(`value` as CalleeBoundsRegressionTest.Link<String, Any>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun intersectionCastEvaluatesCallOnce() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java), TypeDef.of(Runnable::class.java))
        val method = MethodDef.builder("intersection").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.IntersectionResultCall")
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT).build { _, _ ->
                ClassTypeDef.of(Calls::class.java).invokeStatic(method, ClassTypeDef.of(Calls::class.java).invokeStatic("next", TypeDef.OBJECT)).returning()
            }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import java.lang.Runnable
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public class IntersectionResultCall {
            |  public fun call(): Any {
            |    return CalleeBoundsRegressionTest.Calls.intersection(CalleeBoundsRegressionTest.Calls.next().let { arg ->
            |      arg as CharSequence
            |      arg as Runnable
            |      arg
            |    })
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun intersectionCastUsesStableFieldSnapshot() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java), TypeDef.of(Runnable::class.java))
        val method = MethodDef.builder("intersection").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val field = FieldDef.builder("value").ofType(TypeDef.OBJECT).initializer(ClassTypeDef.of(Calls::class.java).invokeStatic("next", TypeDef.OBJECT)).build()
        val def = ClassDef.builder("test.IntersectionFieldCall").addField(field)
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT).build { self, _ ->
                ClassTypeDef.of(Calls::class.java).invokeStatic(method, self.field(field)).returning()
            }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import java.lang.Runnable
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public class IntersectionFieldCall {
            |  public var `value`: Any = CalleeBoundsRegressionTest.Calls.next()
            |
            |  public fun call(): Any {
            |    return CalleeBoundsRegressionTest.Calls.intersection(this. `value`.let { arg ->
            |      arg as CharSequence
            |      arg as Runnable
            |      arg
            |    })
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun nullableGenericArrayKeepsNullability() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val method = MethodDef.builder("nullableArray").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t.array().makeNullable()).returns(t.array().makeNullable()).build()
        val def = ClassDef.builder("test.NullableArrayCall")
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT.makeNullable())
                .build { _, _ -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, ExpressionDef.nullValue()).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.CharSequence
            |
            |public class NullableArrayCall {
            |  public fun call(): Any? {
            |    return CalleeBoundsRegressionTest.Calls.nullableArray(null as Array<CharSequence>?)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun callerVariableMustSatisfyCalleeBound() {
        val v = TypeDef.variable("V")
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val method = MethodDef.builder("text").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.VariableBoundCall").addTypeVariable(v)
            .addMethod(MethodDef.builder("call").addParameter("value", v).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public class VariableBoundCall<V> {
            |  public fun call(`value`: V): Any {
            |    return CalleeBoundsRegressionTest.Calls.text(`value` as CharSequence)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun nullableSubtypeMustSatisfyNonNullBound() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val method = MethodDef.builder("text").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.NullableSubtypeCall")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.STRING.makeNullable()).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.CharSequence
            |import kotlin.String
            |
            |public class NullableSubtypeCall {
            |  public fun call(`value`: String?): Any {
            |    return CalleeBoundsRegressionTest.Calls.text(`value` as CharSequence)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun nullableParameterizedSubtypeMustSatisfyNonNullBound() {
        val list = TypeDef.parameterized(List::class.java, String::class.java)
        val t = TypeDef.variable("T", list)
        val method = MethodDef.builder("list").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t).addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.NullableListArgumentCall")
            .addMethod(MethodDef.builder("call").addParameter("value", list.makeNullable()).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]).returning() }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public class NullableListArgumentCall {
            |  public fun call(`value`: List<String>?): Any {
            |    return CalleeBoundsRegressionTest.Calls.list(`value` as List<String>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun annotatedCalleeVariableUsesBound() {
        assertCompiles(assertSource(argument("AnnotatedVariableCall", false), """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public class AnnotatedVariableCall {
            |  public fun call(`value`: Any): Any {
            |    return CalleeBoundsRegressionTest.Calls.text(`value` as CharSequence)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun annotatedCalleeArrayUsesBound() {
        assertCompiles(assertSource(argument("AnnotatedArrayCall", true), """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.CharSequence
            |
            |public class AnnotatedArrayCall {
            |  public fun call(`value`: Any): Any {
            |    return CalleeBoundsRegressionTest.Calls.array(`value` as Array<CharSequence>)
            |  }
            |}
            |""".trimMargin()))
    }

    @Test
    fun singleBoundCastDoesNotChangeLaterOverload() {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val method = MethodDef.builder("text").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val choose = MethodDef.builder("choose").addParameter("value", TypeDef.OBJECT).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("object").returning() }
        val narrow = MethodDef.builder("choose").addParameter("value", CharSequence::class.java).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("text").returning() }
        val def = ClassDef.builder("test.SingleBoundOverloadCall").addMethod(choose).addMethod(narrow)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(String::class.java)
                .build { self, p -> StatementDef.multi(ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]), self.invoke(choose, p[0]).returning()) }).build()
        assertCompiles(assertSource(def, """
            |package test
            |
            |import io.micronaut.sourcegen.CalleeBoundsRegressionTest
            |import kotlin.Any
            |import kotlin.CharSequence
            |import kotlin.String
            |
            |public class SingleBoundOverloadCall {
            |  public fun choose(`value`: Any): String {
            |    return "object"
            |  }
            |
            |  public fun choose(`value`: CharSequence): String {
            |    return "text"
            |  }
            |
            |  public fun call(`value`: Any): String {
            |    CalleeBoundsRegressionTest.Calls.text(`value` as CharSequence)
            |    return this.choose(`value` as Any)
            |  }
            |}
            |""".trimMargin()))
    }

    private fun argument(name: String, array: Boolean): ClassDef {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val type = (if (array) t.array() else t).annotated(AnnotationDef.builder(NonNull::class.java).build())
        val method = MethodDef.builder(if (array) "array" else "text").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", type).returns(if (array) t.array() else t).build()
        return ClassDef.builder("test.$name")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(method, p[0]).returning() }).build()
    }

    private fun assertSource(objectDef: ObjectDef, expected: String): String {
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(objectDef, writer)
            val source = writer.toString()
            assertEquals(expected, source)
            return source
        }
    }

    open class Parent<X : CharSequence> {
        fun <U : X> identity(value: U): U = value
    }

    class Child : Parent<String>()

    interface Link<X, in T>

    class Mixed<X> {
        fun <T : Link<X, T>> identity(value: T): T = value
    }

    class RunningText(private val text: String) : CharSequence by text, Runnable {
        override fun run() {}
    }

    class Calls {
        companion object {
            @JvmStatic
            fun next(): Any = RunningText("text")

            @JvmStatic
            fun <T> intersection(value: T): T where T : CharSequence, T : Runnable = value

            @JvmStatic
            fun <T : CharSequence> text(value: T): T = value

            @JvmStatic
            fun <T : List<String>> list(value: T): T = value

            @JvmStatic
            fun <T : CharSequence> array(value: Array<T>): Array<T> = value

            @JvmStatic
            fun <T : CharSequence> nullableArray(value: Array<T>?): Array<T>? = value
        }
    }
}
