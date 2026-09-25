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

import io.micronaut.sourcegen.KotlinCompileAssertions.compileMatchingSnapshots
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import java.io.Serializable
import javax.lang.model.element.Modifier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Executes DSL programs at conversion and scope boundaries. */
class ConversionProgramRegressionTest {

    private val snapshots = "/conversion-programs/kotlin"

    @ParameterizedTest(name = "the array helper keeps the caller variable {0}")
    @ValueSource(strings = ["T", "R"])
    fun arrayHelperPreservesCallerVariables(name: String) {
        val callerVariable = TypeDef.variable(name)
        val x = TypeDef.variable("X")
        val u = TypeDef.variable("U", TypeDef.parameterized(ClassTypeDef.of(List::class.java), x), TypeDef.of(Serializable::class.java))
        val arrays = MethodDef.builder("arrays").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u.array()).returns(TypeDef.OBJECT).build()
        val def = ClassDef.builder("test.ArrayScope$name").addTypeVariable(callerVariable)
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(ClassTypeDef.of(IntersectionTarget::class.java), callerVariable))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(arrays, p[1]).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val value = arrayOf(arrayListOf("text"))
            assertSame(value, cls.getMethod("call", IntersectionTarget::class.java, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), IntersectionTarget<String>(), value))
        }
    }

    @ParameterizedTest(name = "an inherited {0} call matches the full generic signature")
    @ValueSource(strings = ["overload", "array"])
    fun inheritedCallsMatchTheFullGenericSignature(kind: String) {
        val x = TypeDef.variable("X", TypeDef.of(CharSequence::class.java))
        val u = TypeDef.variable("U", x)
        val parameter = if (kind == "array") TypeDef.variable("U").array() else u
        val method = MethodDef.builder(if (kind == "array") "arrays" else "identity").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(u).addParameter("value", parameter).returns(TypeDef.OBJECT).build()
        val receiver = if (kind == "array") ArrayChild::class.java else GenericOverloadedChild::class.java
        val def = ClassDef.builder("test.GenericSignature$kind")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(method, p[1]).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val value: Any = if (kind == "array") arrayOf("text") else "text"
            assertSame(value, cls.getMethod("call", receiver, Any::class.java)
                .invoke(cls.getConstructor().newInstance(), receiver.getConstructor().newInstance(), value))
        }
    }

    @Test
    fun nullableIntersectionArrayPreservesNull() {
        val u = TypeDef.variable("U", TypeDef.of(CharSequence::class.java), TypeDef.of(Serializable::class.java))
        val arrays = MethodDef.builder("nullableArrays").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(u)
            .addParameter("value", u.array().makeNullable()).returns(TypeDef.OBJECT.makeNullable()).build()
        val def = ClassDef.builder("test.NullableIntersectionArray")
            .addMethod(MethodDef.builder("call").returns(TypeDef.OBJECT.makeNullable())
                .build { _, _ -> ClassTypeDef.of(Calls::class.java).invokeStatic(arrays, ExpressionDef.nullValue()).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertNull(cls.getMethod("call").invoke(cls.getConstructor().newInstance()))
        }
    }

    @ParameterizedTest(name = "successive casts of a {0} value keep every bound")
    @ValueSource(strings = ["scalar", "array", "recursive"])
    fun successiveCastsKeepEveryBound(kind: String) {
        val v = TypeDef.variable("V", TypeDef.of(Serializable::class.java))
        val first = MethodDef.builder("serializable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(v)
            .addParameter("value", v).returns(TypeDef.OBJECT).build()
        val u = if (kind == "recursive") TypeDef.variable("U", TypeDef.parameterized(ClassTypeDef.of(Comparable::class.java), TypeDef.variable("U")))
            else TypeDef.variable("U", TypeDef.of(CharSequence::class.java), TypeDef.of(Serializable::class.java))
        val parameter = if (kind == "array") u.array() else u
        val second = MethodDef.builder(when (kind) { "array" -> "arrays"; "recursive" -> "comparable"; else -> "intersection" })
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(u).addParameter("value", parameter).returns(TypeDef.OBJECT).build()
        val def = ClassDef.builder("test.SuccessiveCasts$kind")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { _, p -> StatementDef.multi(ClassTypeDef.of(Calls::class.java).invokeStatic(first, p[0]),
                    ClassTypeDef.of(Calls::class.java).invokeStatic(second, p[0]).returning()) }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val value: Any = if (kind == "array") arrayOf("text") else "text"
            assertSame(value, cls.getMethod("call", Any::class.java).invoke(cls.getConstructor().newInstance(), value))
        }
    }

    @ParameterizedTest(name = "smart casts composed through a {0} keep the overload the model selects")
    @ValueSource(strings = ["parameter", "conditional", "switch"])
    fun composedSmartCastsPreserveOverloadSelection(kind: String) {
        val v = TypeDef.variable("V", TypeDef.of(Serializable::class.java))
        val first = MethodDef.builder("serializable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(v)
            .addParameter("value", v).returns(TypeDef.OBJECT).build()
        val chooseAny = MethodDef.builder("choose").addParameter("value", TypeDef.OBJECT).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("object").returning() }
        val chooseSerializable = MethodDef.builder("choose").addParameter("value", Serializable::class.java).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("serializable").returning() }
        val def = ClassDef.builder("test.ComposedSmartCast$kind").addMethod(chooseAny).addMethod(chooseSerializable)
            .addMethod(MethodDef.builder("call").addParameter("flag", TypeDef.Primitive.BOOLEAN).addParameter("value", TypeDef.OBJECT)
                .returns(String::class.java).build { self, p ->
                    val argument = when (kind) {
                        "conditional" -> p[0].ifTrue(p[1], p[1])
                        "switch" -> ExpressionDef.constant(1).asExpressionSwitch(TypeDef.OBJECT,
                            mapOf(ExpressionDef.constant(1) to p[1]), p[1])
                        else -> p[1]
                    }
                    StatementDef.multi(ClassTypeDef.of(Calls::class.java).invokeStatic(first, p[1]),
                        self.invoke(chooseAny, argument).returning())
                }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            for (flag in listOf(true, false)) {
                assertEquals("object", cls.getMethod("call", Boolean::class.javaPrimitiveType, Any::class.java)
                    .invoke(cls.getConstructor().newInstance(), flag, "text"))
            }
        }
    }

    class IntersectionTarget<X> {
        fun <U> arrays(value: Array<U>): Any where U : List<X>, U : Serializable = value
    }
    class GenericOverloadedChild : CalleeBoundsRegressionTest.Parent<String>() {
        fun <U : Number> identity(value: U): U = value
    }
    open class ArrayParent<X : CharSequence> {
        fun <U : X> arrays(value: Array<U>): Any = value
    }
    class ArrayChild : ArrayParent<String>()
    class Calls {
        companion object {
            @JvmStatic fun <U> nullableArrays(value: Array<U>?): Any? where U : CharSequence, U : Serializable = value
            @JvmStatic fun <U> arrays(value: Array<U>): Any where U : CharSequence, U : Serializable = value
            @JvmStatic fun <U> intersection(value: U): Any where U : CharSequence, U : Serializable = value
            @JvmStatic fun <U : Comparable<U>> comparable(value: U): Any = value
            @JvmStatic fun <V : Serializable> serializable(value: V): Any = value
        }
    }
}
