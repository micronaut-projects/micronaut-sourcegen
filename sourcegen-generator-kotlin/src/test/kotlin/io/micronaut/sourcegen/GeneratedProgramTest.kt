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
import io.micronaut.sourcegen.model.EnumDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Executes DSL-generated programs, including evaluation order and generic conversions. */
class GeneratedProgramTest {

    private val snapshots = "/generated-programs/kotlin"

    @ParameterizedTest(name = "a generic array of rank {0} keeps its values through the call")
    @ValueSource(ints = [1, 2, 3])
    fun genericArrayCallsPreserveValuesAcrossRanks(rank: Int) {
        val t = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val identity = MethodDef.builder("array$rank").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t.array(rank)).returns(t.array(rank)).build()
        val def = ClassDef.builder("test.Rank$rank")
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.OBJECT.array(rank)).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val value = ReflectArray.newInstance(CharSequence::class.java, *IntArray(rank))
            val parameter = ReflectArray.newInstance(Any::class.java, *IntArray(rank)).javaClass
            assertSame(value, cls.getMethod("call", parameter).invoke(cls.getConstructor().newInstance(), value))
        }
    }

    @ParameterizedTest(name = "a compatible {0} argument keeps its value")
    @ValueSource(strings = ["number", "text", "list"])
    fun compatibleArgumentsKeepTheirValues(kind: String) {
        val bound = when (kind) {
            "number" -> TypeDef.of(Number::class.java)
            "text" -> TypeDef.of(CharSequence::class.java)
            else -> TypeDef.parameterized(List::class.java, String::class.java)
        }
        val input = when (kind) {
            "number" -> TypeDef.Primitive.INT
            "text" -> TypeDef.STRING
            else -> bound
        }
        val t = TypeDef.variable("T", bound)
        val identity = MethodDef.builder(kind).addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("value", t).returns(t).build()
        val def = ClassDef.builder("test.Compatible$kind")
            .addMethod(MethodDef.builder("call").addParameter("value", input).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of(Calls::class.java).invokeStatic(identity, p[0]).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val parameter = when (kind) { "number" -> Int::class.javaPrimitiveType!!; "text" -> String::class.java; else -> List::class.java }
            val value: Any = when (kind) { "number" -> 7; "text" -> "text"; else -> listOf("text") }
            assertEquals(value, cls.getMethod("call", parameter).invoke(cls.getConstructor().newInstance(), value))
        }
    }

    @ParameterizedTest(name = "a reference through a {0} captures its receiver before it changes")
    @ValueSource(strings = ["field", "local", "result"])
    fun referencesCaptureTheirReceiverBeforeItChanges(kind: String) {
        val label = FieldDef.builder("label", String::class.java).addModifiers(Modifier.PRIVATE).build()
        val apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT)
            .build { self, _ -> self.field(label).returning() }
        val target = ClassDef.builder("test.CapturedTarget").addField(label).addAllFieldsConstructor(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)).addMethod(apply).build()
        val receiver = FieldDef.builder("receiver", target.asTypeDef()).addModifiers(Modifier.PRIVATE)
            .initializer(target.asTypeDef().instantiate(ExpressionDef.constant("first"))).build()
        val count = FieldDef.builder("count", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE).initializer(ExpressionDef.constant(0)).build()
        val next = MethodDef.builder("next").returns(target.asTypeDef()).build { self, _ -> StatementDef.multi(
            self.field(count).put(self.field(count).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
            self.field(receiver).returning()) }
        val function = TypeDef.parameterized(Function::class.java, Any::class.java, Any::class.java)
        val caller = ClassDef.builder("test.Capture$kind").addField(receiver).addField(count).addMethod(next)
            .addMethod(MethodDef.builder("reads").returns(TypeDef.Primitive.INT).build { self, _ -> self.field(count).returning() })
            .addMethod(MethodDef.builder("replace").returns(TypeDef.VOID)
                .build { self, _ -> self.field(receiver).put(target.asTypeDef().instantiate(ExpressionDef.constant("second"))) })
            .addMethod(MethodDef.builder("capture").returns(function).build { self, _ -> when (kind) {
                "field" -> function.methodReference(self.field(receiver), apply).returning()
                "local" -> self.field(receiver).newLocal("target") { local -> function.methodReference(local, apply).returning() }
                else -> function.methodReference(self.invoke(next), apply).returning()
            } }).build()
        compileMatchingSnapshots(snapshots, target, caller).use { loader ->
            val cls = loader.loadClass(caller.name)
            val instance = cls.getConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            val captured = cls.getMethod("capture").invoke(instance) as Function<Any, Any>
            cls.getMethod("replace").invoke(instance)
            assertEquals("first", captured.apply("one"))
            assertEquals("first", captured.apply("two"))
            assertEquals(if (kind == "result") 1 else 0, cls.getMethod("reads").invoke(instance))
        }
    }

    @Test
    fun enumOverrideIsCallableThroughItsGenericInterface() {
        val def = EnumDef.builder("test.SuppliedEnum").addEnumConstant("ONE")
            .addSuperinterface(TypeDef.parameterized(Supplier::class.java, String::class.java))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.constant("one").returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            assertEquals("one", (loader.loadClass(def.name).getField("ONE").get(null) as Supplier<*>).get())
        }
    }

    @Test
    fun typedConstructorConvertsAnErasedArgument() {
        val constructor = StringBuilder::class.java.getConstructor(CharSequence::class.java)
        val def = ClassDef.builder("test.TypedConstructor")
            .addMethod(MethodDef.builder("create").addParameter("value", TypeDef.OBJECT).returns(StringBuilder::class.java)
                .build { _, p -> ClassTypeDef.of(StringBuilder::class.java).instantiate(constructor, p[0]).returning() }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("text", cls.getMethod("create", Any::class.java).invoke(cls.getConstructor().newInstance(), "text").toString())
        }
    }

    @Test
    fun finallyRunsOnceOnBothReturnAndThrow() {
        val increment = AtomicInteger::class.java.getMethod("incrementAndGet")
        val def = ClassDef.builder("test.FinallyPaths")
            .addMethod(MethodDef.builder("call").addParameter("counter", AtomicInteger::class.java)
                .addParameter("fail", TypeDef.Primitive.BOOLEAN).returns(TypeDef.Primitive.INT)
                .build { _, p -> StatementDef.doTry(StatementDef.multi(
                    p[1].isTrue().doIf(ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow()), ExpressionDef.constant(7).returning()
                )).doFinally(p[0].invoke(increment)) }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val method = cls.getMethod("call", AtomicInteger::class.java, Boolean::class.javaPrimitiveType)
            val counter = AtomicInteger()
            assertEquals(7, method.invoke(instance, counter, false))
            val failure = assertThrows(InvocationTargetException::class.java) { method.invoke(instance, counter, true) }
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
            assertEquals(2, counter.get())
        }
    }

    @Test
    fun methodReferenceConvertsParameterizedArrayResults() {
        val values = FieldDef.builder("values", TypeDef.OBJECT).addModifiers(Modifier.PRIVATE).build()
        val get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build { self, _ -> self.field(values).returning() }
        val target = ClassDef.builder("test.ArraySupplier").addField(values).addAllFieldsConstructor(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), TypeDef.parameterized(List::class.java, String::class.java).array())).addMethod(get).build()
        val result = TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), TypeDef.parameterized(List::class.java, Any::class.java).array())
        val caller = ClassDef.builder("test.ArraySupplierCaller")
            .addMethod(MethodDef.builder("reference").addParameter("target", target.asTypeDef()).returns(result)
                .build { _, p -> result.methodReference(p[0], get).returning() }).build()
        compileMatchingSnapshots(snapshots, target, caller).use { loader ->
            val targetClass = loader.loadClass(target.name)
            val cls = loader.loadClass(caller.name)
            val value = arrayOf(listOf("text"))
            val instance = targetClass.getConstructor(Any::class.java).newInstance(value as Any)
            val reference = cls.getMethod("reference", targetClass).invoke(cls.getConstructor().newInstance(), instance) as Supplier<*>
            assertSame(value, reference.get())
        }
    }

    @Test
    fun exhaustiveConditionalDropsTheUnreachableFallback() {
        val def = ClassDef.builder("test.ExhaustiveBranches")
            .addMethod(MethodDef.builder("call").addParameter("flag", TypeDef.Primitive.BOOLEAN).returns(String::class.java)
                .build { _, p -> StatementDef.multi(p[0].isTrue().doIfElse(
                    ExpressionDef.constant("yes").returning(), ExpressionDef.constant("no").returning()), ExpressionDef.constant("unreachable").returning()) }).build()
        compileMatchingSnapshots(snapshots, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("yes", cls.getMethod("call", Boolean::class.javaPrimitiveType).invoke(instance, true))
            assertEquals("no", cls.getMethod("call", Boolean::class.javaPrimitiveType).invoke(instance, false))
        }
    }

    class Calls {
        companion object {
            @JvmStatic
            fun <T : CharSequence> array1(value: Array<T>): Array<T> = value

            @JvmStatic
            fun <T : CharSequence> array2(value: Array<Array<T>>): Array<Array<T>> = value

            @JvmStatic
            fun <T : CharSequence> array3(value: Array<Array<Array<T>>>): Array<Array<Array<T>>> = value

            @JvmStatic
            fun <T : Number> number(value: T): T = value

            @JvmStatic
            fun <T : CharSequence> text(value: T): T = value

            @JvmStatic
            fun <T : List<String>> list(value: T): T = value
        }
    }
}
