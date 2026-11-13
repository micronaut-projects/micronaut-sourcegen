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
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Lambdas written as Kotlin lambdas: block bodies with several returns, branches, try and catch, lambdas without
 * parameters, of a generated functional interface, and lambda parameters named like a local of the body. Every program
 * is compiled and run.
 */
class LambdaWriteTest {

    // A lambda whose body is more than one return statement throws IllegalStateException from KotlinPoet (addStatement nested in a statement). The Java generator writes block lambdas (blockBodyLambda* tests).
    @Test
    fun blockLambdaReturnsValue() {
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        val def = ClassDef.builder("test.S01")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build { _, _ -> supplier.getLambda().implement { _, _ ->
                    ExpressionDef.constant("a").newLocal("x") { x -> x.returning() } }.returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", (o.javaClass.getMethod("get").invoke(o) as Supplier<*>).get())
        }
    }

    // Same with a conditional return in the lambda body.
    @Test
    fun blockLambdaWithBranches() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.S01b")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(function)
                .build { _, _ -> function.getLambda().implement { _, p ->
                    StatementDef.multi(
                        p[0].invoke("isEmpty", TypeDef.Primitive.BOOLEAN).isTrue.doIf(ExpressionDef.constant("empty").returning()),
                        p[0].returning()) }.returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            @Suppress("UNCHECKED_CAST")
            val fn = o.javaClass.getMethod("get").invoke(o) as Function<String, String>
            assertEquals("empty", fn.apply(""))
            assertEquals("b", fn.apply("b"))
        }
    }

    // A Runnable lambda returning a void call from a branch: the same IllegalStateException; the `return` it would write is also not allowed in a Kotlin lambda.
    @Test
    fun runnableLambdaReturningVoidCall() {
        val runnable = ClassTypeDef.of(Runnable::class.java)
        val list = TypeDef.parameterized(java.util.List::class.java, String::class.java)
        val clear = java.util.List::class.java.getMethod("clear")
        val def = ClassDef.builder("test.S05")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).addParameter("list", list).returns(runnable)
                .build { _, mp -> runnable.getLambda().implement { _, _ ->
                    StatementDef.multi(
                        mp[0].invoke("isEmpty", TypeDef.Primitive.BOOLEAN).isTrue.doIf(mp[0].invoke(clear).returning()),
                        mp[0].invoke(clear).returning()) }.returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val l = arrayListOf("a")
            (o.javaClass.getMethod("get", java.util.List::class.java).invoke(o, l) as Runnable).run()
            assertEquals(0, l.size)
        }
    }

    // A lambda without parameters is written `Supplier<String> {() -> "a"}`, which is not Kotlin. Expected: `Supplier<String> { "a" }`.
    @Test
    fun lambdaWithoutParameters() {
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        val def = ClassDef.builder("test.S00")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build { _, _ -> supplier.getLambda().implement { _, _ -> ExpressionDef.constant("a").returning() }.returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", (o.javaClass.getMethod("get").invoke(o) as Supplier<*>).get())
        }
    }

    @Test
    fun lambdaOfAGeneratedInterfaceWithoutFunctionalInterfaceAnnotation() {
        val call = MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", String::class.java)
            .returns(String::class.java).build()
        val callback = InterfaceDef.builder("test.Callback").addModifiers(Modifier.PUBLIC).addMethod(call).build()
        val callbackType = ClassTypeDef.of(callback)
        val def = ClassDef.builder("test.CallbackFactory").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).returns(callbackType)
                .build { _, _ -> callbackType.getLambda().implement { _, p -> p[0].returning() }.returning() })
            .build()
        compile(callback, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val created = cls.getMethod("create").invoke(cls.getConstructor().newInstance())
            assertEquals("text", loader.loadClass(callback.name).getMethod("call", String::class.java).invoke(created, "text"))
        }
    }

    @Test
    fun parameterlessBlockLambdaReturnsEarly() {
        val runnable = ClassTypeDef.of(Runnable::class.java)
        val counter = ClassTypeDef.of(java.util.concurrent.atomic.AtomicInteger::class.java)
        val increment = java.util.concurrent.atomic.AtomicInteger::class.java.getMethod("incrementAndGet")
        val def = ClassDef.builder("test.EarlyRunnable").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).addParameter("counter", counter)
                .addParameter("skip", TypeDef.Primitive.BOOLEAN).returns(runnable)
                .build { _, p -> runnable.getLambda().implement { _, _ -> StatementDef.multi(
                    p[1].isTrue().doIf(StatementDef.Return(null)),
                    p[0].invoke(increment)) }.returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val count = java.util.concurrent.atomic.AtomicInteger()
            (cls.getMethod("create", count.javaClass, Boolean::class.javaPrimitiveType).invoke(instance, count, true) as Runnable).run()
            (cls.getMethod("create", count.javaClass, Boolean::class.javaPrimitiveType).invoke(instance, count, false) as Runnable).run()
            assertEquals(1, count.get())
        }
    }

    @Test
    fun catchInsideBlockLambdaKeepsLocalReturn() {
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        val def = ClassDef.builder("test.CatchingLambda").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build { _, _ -> supplier.getLambda().implement { _, _ ->
                    StatementDef.doTry(ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow())
                        .doCatch(IllegalStateException::class.java) { ExpressionDef.constant("caught").returning() }
                }.returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            val result = cls.getMethod("call").invoke(cls.getConstructor().newInstance()) as Supplier<*>
            assertEquals("caught", result.get())
        }
    }

    @Test
    fun renamedLambdaParameterDoesNotCollideWithBodyLocal() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.LambdaLocalCollision").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("value", String::class.java)
                .returns(function).build { _, _ -> function.getLambda().implement(listOf("value")) { _, p ->
                    ExpressionDef.constant("local").newLocal("value1") { p[0].returning() }
                }.returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            @Suppress("UNCHECKED_CAST")
            val function = cls.getMethod("reference", String::class.java)
                .invoke(cls.getConstructor().newInstance(), "outer") as Function<String, String>
            assertEquals("input", function.apply("input"))
        }
    }

    @Test
    fun expressionLambdaConvertsErasedResult() {
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        val def = ClassDef.builder("test.ErasedLambdaReturn").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Any::class.java)
                .returns(supplier).build { _, p -> supplier.getLambda().implement { _, _ -> p[0].returning() }.returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            val fn = cls.getMethod("call", Any::class.java).invoke(cls.getConstructor().newInstance(), "hello") as Supplier<*>
            assertEquals("hello", fn.get())
        }
    }
}
