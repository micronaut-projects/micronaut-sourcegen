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
import io.micronaut.sourcegen.KotlinCompileAssertions.invokeRun
import io.micronaut.sourcegen.KotlinCompileAssertions.newInstance
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Fields without an initializer, blank final fields and lazily initialized fields: Kotlin requires a property to be
 * initialized and a `val` to be assigned once, the generated classes behave as the bytecode does - a field holds the
 * JVM default until it is assigned. Every program is compiled and run.
 */
class FieldInitializationWriteTest {

    // A non-final field of a class type variable, assigned by a setter: written `var value: T` without an initializer (lateinit is excluded for a variable), which kotlinc rejects. Expected: compiles (for instance a nullable backing property) and behaves like the field.
    @Test
    fun typeVariableField() {
        val t = TypeDef.variable("T")
        val field = FieldDef.builder("value", t).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.P02").addTypeVariable(t).addField(field)
            .addMethod(MethodDef.builder("set").addModifiers(Modifier.PUBLIC).addParameter("v", t).returns(TypeDef.VOID)
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(t)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            o.javaClass.getMethod("set", Any::class.java).invoke(o, "a")
            assertEquals("a", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // `int count;` that no constructor assigns is written `var count: Int` without an initializer: defaultOf() only gives static fields their default. Expected: `= 0` as the JVM gives the field.
    @Test
    fun primitiveInstanceFieldWithoutInitializer() {
        val field = FieldDef.builder("count", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.P03").addField(field)
            .addMethod(MethodDef.builder("inc").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { aThis, _ -> StatementDef.multi(
                    aThis.field(field).put(aThis.field(field).math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))),
                    aThis.field(field).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(1, o.javaClass.getMethod("inc").invoke(o))
        }
    }

    // A blank `static final int` assigned in the static initializer is written `val VALUE: Int = 0` and then reassigned in `init`: 'val cannot be reassigned'. The String counterpart becomes a lateinit var; the primitive one gets a default but stays a val.
    @Test
    fun staticFinalPrimitiveAssignedInStaticInitializer() {
        val type = ClassTypeDef.of("test.P06")
        val field = FieldDef.builder("VALUE", TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build()
        val def = ClassDef.builder(type.name).addField(field)
            .addStaticInitializer(type.getStaticField(field).put(ExpressionDef.constant(5)))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { _, _ -> type.getStaticField(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(5, o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A blank final field of a nullable type assigned by the constructor is written `val value: String? = null` and then assigned: 'val cannot be reassigned'.
    @Test
    fun nullableFinalFieldAssignedInConstructor() {
        val field = FieldDef.builder("value", TypeDef.STRING.makeNullable()).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val def = ClassDef.builder("test.P09").addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING.makeNullable())
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING.makeNullable())
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java).newInstance("x")
            assertEquals("x", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // The static counterpart: `val VALUE: String? = null` reassigned in the companion's `init`.
    @Test
    fun nullableStaticFinalAssignedInStaticInitializer() {
        val type = ClassTypeDef.of("test.P17")
        val field = FieldDef.builder("VALUE", TypeDef.STRING.makeNullable()).addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).build()
        val def = ClassDef.builder(type.name).addField(field)
            .addStaticInitializer(type.getStaticField(field).put(ExpressionDef.constant("a")))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING.makeNullable())
                .build { _, _ -> type.getStaticField(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A primitive instance field that only one of two constructors assigns has no initializer: 'Property must be initialized'. Expected: the default value, as the reference field beside it gets lateinit.
    @Test
    fun fieldAssignedInOnlyOneOfTwoConstructors() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val other = FieldDef.builder("size", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.P19").addField(field).addField(other)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING)
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.Primitive.INT)
                .build { aThis, p -> aThis.field(other).put(p[0]) })
            .addMethod(MethodDef.builder("size").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { aThis, _ -> aThis.field(other).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java).newInstance("x")
            assertEquals(0, o.javaClass.getMethod("size").invoke(o))
        }
    }

    // A blank final instance field assigned in a try and in its catch stays a `val`: 'val cannot be reassigned'. The static counterpart is relaxed to a lateinit var.
    @Test
    fun finalFieldAssignedInTryAndCatch() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val def = ClassDef.builder("test.D09a").addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING)
                .build { aThis, p -> StatementDef.doTry(aThis.field(field).put(p[0].invoke("trim", TypeDef.STRING)))
                    .doCatch(RuntimeException::class.java) { _ -> aThis.field(field).put(ExpressionDef.constant("failed")) } })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java).newInstance(" x ")
            assertEquals("x", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A static field without initializer read before it is assigned (`if (CACHE == null) CACHE = ...`) is a lateinit var: the null check throws UninitializedPropertyAccessException where bytecode reads null.
    @Test
    fun lazilyInitializedStaticField() {
        val type = ClassTypeDef.of("test.D11")
        val cache = FieldDef.builder("CACHE", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.STATIC).build()
        val def = ClassDef.builder(type.name).addField(cache)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { _, _ -> StatementDef.multi(
                    type.getStaticField(cache).isNull.doIf(type.getStaticField(cache).put(ExpressionDef.constant("computed"))),
                    type.getStaticField(cache).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("computed", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // The instance counterpart of the lazily initialized field.
    @Test
    fun lazilyInitializedInstanceField() {
        val cache = FieldDef.builder("cache", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.D11b").addField(cache)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> StatementDef.multi(
                    aThis.field(cache).isNull.doIf(aThis.field(cache).put(ExpressionDef.constant("computed"))),
                    aThis.field(cache).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("computed", o.javaClass.getMethod("get").invoke(o))
        }
    }

    @Test
    fun fieldReadBeforeItIsAssignedHoldsTheJvmDefault() {
        val name = FieldDef.builder("name", String::class.java).addModifiers(Modifier.PRIVATE).build()
        val count = FieldDef.builder("count", Integer::class.java).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.UnassignedBean").addModifiers(Modifier.PUBLIC).addField(name).addField(count)
            .addMethod(MethodDef.builder("getName").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> self.field(name).returning() })
            .addMethod(MethodDef.builder("setName").addModifiers(Modifier.PUBLIC).addParameter("name", String::class.java).returns(TypeDef.VOID)
                .build { self, p -> self.field(name).put(p[0]) })
            .addMethod(MethodDef.builder("getCount").addModifiers(Modifier.PUBLIC).returns(Integer::class.java)
                .build { self, _ -> self.field(count).returning() })
            .addMethod(MethodDef.builder("setCount").addModifiers(Modifier.PUBLIC).addParameter("count", Integer::class.java).returns(TypeDef.VOID)
                .build { self, p -> self.field(count).put(p[0]) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            org.junit.jupiter.api.Assertions.assertAll(
                { assertEquals(null, cls.getMethod("getCount").invoke(instance)) },
                { assertEquals(null, cls.getMethod("getName").invoke(instance)) }
            )
        }
    }

    @Test
    fun lazilyInitializedFieldReadByAClassWrittenBeforeItsOwner() {
        val cache = FieldDef.builder("cache", String::class.java).addModifiers(Modifier.PUBLIC).build()
        val holder = ClassDef.builder("test.LazyHolder").addModifiers(Modifier.PUBLIC).addField(cache)
            .addMethod(MethodDef.builder("load").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> StatementDef.multi(
                    self.field(cache).isNull().doIf(self.field(cache).put(ExpressionDef.constant("loaded"))),
                    self.field(cache).returning()) })
            .build()
        val reader = ClassDef.builder("test.LazyReader").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).addParameter("holder", holder.asTypeDef()).returns(String::class.java)
                .build { _, p -> p[0].field(cache).returning() })
            .build()
        // The same definitions, only written in the other order, compile: whether the reader asserts the property is
        // not null depends on which of them the thread wrote first
        compile(reader, holder).use { loader ->
            val holderClass = loader.loadClass(holder.name)
            val instance = holderClass.getConstructor().newInstance()
            holderClass.getMethod("load").invoke(instance)
            val cls = loader.loadClass(reader.name)
            assertEquals("loaded", cls.getMethod("read", holderClass).invoke(cls.getConstructor().newInstance(), instance))
        }
    }

    /**
     * A class reads the lazily initialized field of another generated class, a nullable property read with `!!`.
     * Which fields are nullable was recorded while their class was written, so the reader only compiled when its
     * target had been written first on the same thread.
     */
    @Test
    fun lazilyInitializedFieldReadByNameByAClassWrittenBeforeItsOwner() {
        val cache = FieldDef.builder("cache", String::class.java).addModifiers(Modifier.PUBLIC).build()
        val holder = ClassDef.builder("test.LazyHolder").addModifiers(Modifier.PUBLIC).addField(cache)
            .addMethod(MethodDef.builder("load").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> StatementDef.multi(
                    self.field(cache).isNull.doIf(self.field(cache).put(ExpressionDef.constant("loaded"))),
                    self.field(cache).returning()) })
            .build()
        val reader = ClassDef.builder("test.LazyReader").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).addParameter("holder", holder.asTypeDef())
                .returns(TypeDef.Primitive.INT)
                .build { _, p -> p[0].field("cache", TypeDef.STRING).invoke("length", TypeDef.Primitive.INT).returning() })
            .build()
        val readerSource = render(reader)
        val holderSource = render(holder)
        KotlinCompileAssertions.compileAndLoad(readerSource, holderSource).use { loader ->
            val instance = loader.loadClass(holder.name).getConstructor().newInstance()
            instance.javaClass.getMethod("load").invoke(instance)
            val readerClass = loader.loadClass(reader.name)
            assertEquals(6, readerClass.getMethod("read", instance.javaClass).invoke(readerClass.getConstructor().newInstance(), instance))
        }
    }

    @Test
    fun uninitializedDoubleFieldCompiles() {
        val field = FieldDef.builder("value", TypeDef.Primitive.DOUBLE).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.DoubleFieldDefault").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.DOUBLE)
                .build { self, _ -> self.field(field).returning() }).build()
        assertEquals(0.0, invokeRun(def))
    }

    @Test
    fun nullCheckedBoxedFieldKeepsJvmDefaultNull() {
        val field = FieldDef.builder("cache", Int::class.javaObjectType).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.BoxedCache").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("empty").addModifiers(Modifier.PUBLIC).returns(Boolean::class.javaPrimitiveType!!)
                .build { self, _ -> self.field(field).isNull.returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(true, cls.getMethod("empty").invoke(cls.getConstructor().newInstance()))
        }
    }

    @Test
    fun nullCheckedFieldThroughLambdaStartsNull() {
        val field = FieldDef.builder("cache", String::class.java).addModifiers(Modifier.PRIVATE).build()
        val supplier = TypeDef.parameterized(Supplier::class.java, Boolean::class.javaObjectType)
        val def = ClassDef.builder("test.LambdaNullCheck").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(supplier)
                .build { self, _ -> supplier.getLambda().implement { _, _ -> self.field(field).isNull.returning() }.returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            val fn = cls.getMethod("call").invoke(cls.getConstructor().newInstance()) as Supplier<*>
            assertEquals(true, fn.get())
        }
    }
}
