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
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.net.URLClassLoader
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Models valid for the bytecode writer that the Kotlin generator rejects, writes as source kotlinc rejects, or writes
 * with another meaning. The programs are compiled and run; the source they are written as is not prescribed.
 */
class KotlinGeneratorRegressionTest {

    private fun render(definition: ObjectDef): String {
        val writer = StringWriter()
        KotlinPoetSourceGenerator().write(definition, writer)
        return writer.toString()
    }

    private fun compile(vararg definitions: ObjectDef): URLClassLoader =
        KotlinCompileAssertions.compileAndLoad(*definitions.map { render(it) }.toTypedArray())

    private fun newInstance(loader: ClassLoader, def: ObjectDef): Any =
        loader.loadClass(def.name).getDeclaredConstructor().newInstance()


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


    // An erased `Object get()` of Supplier<String>, declared to return a nullable Object and returning null, is resolved to a non-null `String` and written `return null as String`: NPE where bytecode returns null. Expected: the nullability of the declared result is kept (`String?`).
    @Test
    fun erasedSupplierReturningNull() {
        val def = ClassDef.builder("test.O01")
            .addSuperinterface(TypeDef.parameterized(Supplier::class.java, String::class.java))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT.makeNullable())
                .build { _, _ -> ExpressionDef.nullValue().returning() })
            .build()
        compile(def).use { loader ->
            assertNull((newInstance(loader, def) as Supplier<*>).get())
        }
    }

    // An erased `apply` returning `map.get(key)` - a platform value that is null for a missing key - is written `... as String`, which throws NPE where bytecode's checkcast lets null through.
    @Test
    fun erasedFunctionReturningMapLookup() {
        // Function<String, String> whose erased apply returns map.get(key): null for a missing key
        val mapType = TypeDef.parameterized(java.util.Map::class.java, String::class.java, String::class.java)
        val field = FieldDef.builder("map", mapType).addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(ClassTypeDef.of(java.util.HashMap::class.java).instantiate()).build()
        val get = java.util.Map::class.java.getMethod("get", Any::class.java)
        val def = ClassDef.builder("test.O02")
            .addSuperinterface(TypeDef.parameterized(Function::class.java, String::class.java, String::class.java))
            .addField(field)
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("key", TypeDef.OBJECT).returns(TypeDef.OBJECT)
                .build { aThis, p -> aThis.field(field).invoke(get, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            @Suppress("UNCHECKED_CAST")
            assertNull((newInstance(loader, def) as Function<String, String?>).apply("missing"))
        }
    }

    // An Object-typed platform value (`map.get`) passed to a `String` parameter is cast with `as String`, which throws for null; bytecode passes the null on. Expected `as String?` (or an unchecked platform conversion) where the parameter accepts null.
    @Test
    fun platformNullPassedToTypedParameter() {
        // describe(String) called with the Object result of map.get: bytecode passes null through
        val get = java.util.Map::class.java.getMethod("get", Any::class.java)
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.N02")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("map", TypeDef.parameterized(java.util.Map::class.java, String::class.java, Any::class.java))
                .returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(Fixtures::class.java).invokeStatic(describe, p[0].invoke(get, ExpressionDef.constant("missing"))).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("null", o.javaClass.getMethod("call", java.util.Map::class.java).invoke(o, HashMap<String, Any>()))
        }
    }


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

    // A static method reference whose owner Kotlin maps (`Integer::valueOf`) is written `Int::valueOf`; calls use asStaticOwnerName (java.lang.Integer), references do not.
    @Test
    fun staticReferenceToAMappedType() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, Integer::class.java)
        val parse = MethodDef.builder("valueOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("s", TypeDef.STRING).returns(TypeDef.of(Integer::class.java)).build()
        val def = ClassDef.builder("test.M01")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(function)
                .build { _, _ -> function.staticMethodReference(ClassTypeDef.of(Integer::class.java), parse).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            @Suppress("UNCHECKED_CAST")
            assertEquals(12, (o.javaClass.getMethod("get").invoke(o) as Function<String, Int>).apply("12"))
        }
    }

    // A reference to a method named with a keyword is written `Fixtures::when` (unescaped %L).
    @Test
    fun referenceToAMethodNamedWithKeyword() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val method = MethodDef.builder("when").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("s", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.M02")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(function)
                .build { _, _ -> function.staticMethodReference(ClassTypeDef.of(Fixtures::class.java), method).returning() })
            .build()
        compile(def).use { }
    }


    // An override of a Kotlin `vararg` method declared with the array parameter of the bytecode signature is written `parts: Array<String>`: overrides nothing.
    @Test
    fun varargOverride() {
        val def = ClassDef.builder("test.B04")
            .addSuperinterface(ClassTypeDef.of(Joiner::class.java))
            .addMethod(MethodDef.builder("join").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("parts", TypeDef.STRING.array()).returns(TypeDef.STRING)
                .build { _, p -> p[0].arrayElement(0).returning() })
            .build()
        compile(def).use { loader ->
            assertEquals("a", (newInstance(loader, def) as Joiner).join("a", "b"))
        }
    }

    // Arguments of a super constructor call are rendered without renderArguments: an Object value for `Exception(String)` is not cast (Java: superConstructorArgumentIsCast).
    @Test
    fun superConstructorArgumentIsCast() {
        val def = ClassDef.builder("test.B08")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .build { aThis, p -> aThis.superRef(ClassTypeDef.of(Exception::class.java))
                    .invokeSuperConstructor(listOf(TypeDef.STRING), p[0]) })
            .build()
        compile(def).use { loader ->
            val e = loader.loadClass(def.name).getConstructor(Any::class.java).newInstance("m") as Exception
            assertEquals("m", e.message)
        }
    }

    // An array passed to a varargs super constructor is not spread: the parent sees one element, the array itself.
    @Test
    fun superConstructorVarargs() {
        val def = ClassDef.builder("test.B08b")
            .superclass(ClassTypeDef.of(VarargsParent::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT.array())
                .build { aThis, p -> aThis.superRef().invokeSuperConstructor(listOf(TypeDef.OBJECT.array()), p[0]) })
            .build()
        compile(def).use { loader ->
            val e = loader.loadClass(def.name).getConstructor(kotlin.Array<Any>::class.java)
                .newInstance(arrayOf<Any>("a", "b") as Any) as VarargsParent
            assertEquals(2, e.size)
        }
    }

    // `this(...)` in a constructor is written as the statement `this("d")` in the body; Kotlin delegates with `constructor() : this("d")`. The final field is then also reported unassigned.
    @Test
    fun constructorDelegation() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val primary = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING)
            .build { aThis, p -> aThis.field(field).put(p[0]) }
        val def = ClassDef.builder("test.B09").addField(field)
            .addMethod(primary)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ -> aThis.invokeConstructor(primary, ExpressionDef.constant("d")) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("d", o.javaClass.getMethod("get").invoke(o))
        }
    }

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
                    ClassTypeDef.of(Fixtures::class.java).invokeStatic(choose, p[0]).returning() } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.getMethod("call", Any::class.java).invoke(o, "text"))
        }
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

    // A `Supplier<Integer>` passed where the model's method takes `Supplier<Number>`: not converted, kotlinc rejects it. The Java generator converts through the raw type (typeArgumentsAreInvariant).
    @Test
    fun invariantTypeArguments() {
        val total = MethodDef.builder("total").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.parameterized(Supplier::class.java, Number::class.java)).returns(TypeDef.Primitive.INT).build()
        val def = ClassDef.builder("test.B25")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.parameterized(Supplier::class.java, Integer::class.java)).returns(TypeDef.Primitive.INT)
                .build { _, p -> ClassTypeDef.of(Fixtures::class.java).invokeStatic(total, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(4, o.javaClass.getMethod("call", Supplier::class.java).invoke(o, Supplier { 4 }))
        }
    }


    // An `Object[]` value passed for a varargs parameter is never spread with `*`: the callee sees a single element where bytecode passes the array as the varargs.
    @Test
    fun arrayPassedAsVarargs() {
        val count = MethodDef.builder("count").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.Primitive.INT).build()
        val def = ClassDef.builder("test.C01")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.Primitive.INT)
                .build { _, p -> ClassTypeDef.of(Fixtures::class.java).invokeStatic(count, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, o.javaClass.getMethod("call", kotlin.Array<Any>::class.java).invoke(o, arrayOf<Any>("a", "b") as Any))
        }
    }

    // The same through `String.format(String, Object...)`: MissingFormatArgumentException.
    @Test
    fun arrayPassedToJavaVarargs() {
        val format = String::class.java.getMethod("format", String::class.java, kotlin.Array<Any>::class.java)
        val def = ClassDef.builder("test.C01b")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array()).returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(String::class.java).invokeStatic(format, ExpressionDef.constant("%s-%s"), p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a-b", o.javaClass.getMethod("call", kotlin.Array<Any>::class.java).invoke(o, arrayOf<Any>("a", "b") as Any))
        }
    }

    // A reflective `accept(Object)` of a compiled `GenericParent<T>` called on super of a `GenericParent<String>` subclass with an Object value: the parameter is not narrowed to String, no cast is written. (Java generator: same.)
    @Test
    fun superCallWithObjectArgument() {
        val accept = MethodDef.of(GenericParent::class.java.getMethod("accept", Any::class.java))
        val def = ClassDef.builder("test.C04")
            .superclass(TypeDef.parameterized(GenericParent::class.java, String::class.java))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { aThis, p -> aThis.superRef().invoke(accept, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("call", Any::class.java).invoke(o, "a"))
        }
    }

    // An erased `accept(@Nullable Object)` of Consumer<String> is narrowed to a non-null `String`: Kotlin's parameter check throws NPE for the null the model declared it accepts.
    @Test
    fun erasedOverrideWithNullableParameter() {
        val field = FieldDef.builder("seen", TypeDef.STRING.makeNullable()).addModifiers(Modifier.PUBLIC).build()
        val def = ClassDef.builder("test.C11")
            .addSuperinterface(TypeDef.parameterized(java.util.function.Consumer::class.java, String::class.java))
            .addField(field)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", TypeDef.OBJECT.makeNullable()).returns(TypeDef.VOID)
                .build { aThis, p -> aThis.field(field).put(p[0].isNull.doIfElse(ExpressionDef.constant("null"), ExpressionDef.constant("value"))) })
            .build()
        compile(def).use { loader ->
            @Suppress("UNCHECKED_CAST")
            val o = newInstance(loader, def) as java.util.function.Consumer<String?>
            o.accept(null)
        }
    }

    // A constructor calling super becomes the primary constructor and the rest of its body is dropped: the field assignment after `super(message)` is lost.
    @Test
    fun constructorBodyAfterSuperCall() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val def = ClassDef.builder("test.C16")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("message", TypeDef.STRING).addParameter("v", TypeDef.STRING)
                .build { aThis, p -> StatementDef.multi(
                    aThis.superRef(ClassTypeDef.of(Exception::class.java)).invokeSuperConstructor(listOf(TypeDef.STRING), p[0]),
                    aThis.field(field).put(p[1])) })
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java, String::class.java).newInstance("m", "v")
            assertEquals("v", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // Two constructors that both call super: each sets the primary constructor and appends its super arguments, giving `constructor() : Exception(message, "default")`.
    @Test
    fun twoConstructorsCallingSuper() {
        val def = ClassDef.builder("test.C17")
            .superclass(ClassTypeDef.of(Exception::class.java))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("message", TypeDef.STRING)
                .build { aThis, p -> aThis.superRef(ClassTypeDef.of(Exception::class.java)).invokeSuperConstructor(listOf(TypeDef.STRING), p[0]) })
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ -> aThis.superRef(ClassTypeDef.of(Exception::class.java))
                    .invokeSuperConstructor(listOf(TypeDef.STRING), ExpressionDef.constant("default")) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("m", (cls.getConstructor(String::class.java).newInstance("m") as Exception).message)
            assertEquals("default", (cls.getConstructor().newInstance() as Exception).message)
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

    // A yielding case of a switch expression is written with `return`, which in Kotlin returns from the method instead of giving the `when` its value: "one" instead of "one!". The Java generator yields.
    @Test
    fun switchExpressionYieldAssignedToLocal() {
        val def = ClassDef.builder("test.C22")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.STRING)
                .build { _, p -> p[0].asExpressionSwitch(TypeDef.STRING, mapOf(
                    ExpressionDef.constant(1) to ExpressionDef.SwitchYieldCase(TypeDef.STRING, ExpressionDef.constant("one").returning())
                ), ExpressionDef.constant("other")).newLocal("text") { text -> text.stringConcat(ExpressionDef.constant("!")).returning() } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("one!", o.javaClass.getMethod("call", Int::class.javaPrimitiveType).invoke(o, 1))
        }
    }


    // The same through a `GenericParent<String>` parameter; only hand-built methods naming the variable are converted, not MethodDef.of(Method), whose parameters are erased.
    @Test
    fun parameterizedReceiverCall() {
        val accept = MethodDef.of(GenericParent::class.java.getMethod("accept", Any::class.java))
        val def = ClassDef.builder("test.C04b")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(GenericParent::class.java, String::class.java))
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { _, p -> p[0].invoke(accept, p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("a", o.javaClass.getMethod("call", GenericParent::class.java, Any::class.java).invoke(o, GenericParent<String>(), "a"))
        }
    }

    // A `java.util.List` parameter is written as the read-only `kotlin.collections.List`, so `list.add(..)` of the model does not resolve.
    @Test
    fun mutatingCallOnAJavaList() {
        val list = TypeDef.parameterized(java.util.List::class.java, String::class.java)
        val add = java.util.List::class.java.getMethod("add", Any::class.java)
        val def = ClassDef.builder("test.C21")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("list", list).returns(TypeDef.VOID)
                .build { _, p -> p[0].invoke(add, ExpressionDef.constant("a")) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val l = arrayListOf<String>()
            o.javaClass.getMethod("call", java.util.List::class.java).invoke(o, l)
            assertEquals(1, l.size)
        }
    }

    // A call of a Java method Kotlin maps to a property (`String.length()`, likewise `List.size()`, `Enum.name()`) is written as a call: 'Expression length of type Int cannot be invoked as a function'.
    @Test
    fun javaMethodsKotlinMapsToProperties() {
        val def = ClassDef.builder("test.C23")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("text", TypeDef.STRING).returns(TypeDef.Primitive.INT)
                .build { _, p -> p[0].invoke(String::class.java.getMethod("length")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(3, o.javaClass.getMethod("call", String::class.java).invoke(o, "abc"))
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
                    ClassTypeDef.of(Fixtures::class.java).invokeStatic(pick, p[0]).returning() }.returning() })
            .build()
        compile(def).use { loader ->
            @Suppress("UNCHECKED_CAST")
            val o = newInstance(loader, def) as Function<String, Function<String, String>>
            assertEquals("object", o.apply("a").apply("b"))
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

    // `Iterator iterator()` of Iterable<String> returning `list.iterator()`: the result is resolved to the read-only kotlin.collections.Iterator, which neither overrides the (Mutable)Iterator of the Java supertype nor accepts the MutableIterator returned.
    @Test
    fun iterableWithRawIterator() {
        val listType = TypeDef.parameterized(java.util.ArrayList::class.java, String::class.java)
        val field = FieldDef.builder("list", listType).addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(ClassTypeDef.of(java.util.ArrayList::class.java).instantiate()).build()
        val def = ClassDef.builder("test.E05")
            .addSuperinterface(TypeDef.parameterized(java.lang.Iterable::class.java, String::class.java))
            .addField(field)
            .addMethod(MethodDef.builder("iterator").addModifiers(Modifier.PUBLIC).overrides()
                .returns(TypeDef.of(java.util.Iterator::class.java))
                .build { aThis, _ -> aThis.field(field).invoke(java.util.ArrayList::class.java.getMethod("iterator")).returning() })
            .build()
        compile(def).use { loader ->
            assertEquals(false, (newInstance(loader, def) as Iterable<*>).iterator().hasNext())
        }
    }

    // `boolean equals(Object)` is written `override fun equals(other: Any)`: overrides nothing, the parameter of Any.equals is `Any?`.
    @Test
    fun equalsOverride() {
        val def = ClassDef.builder("test.E06")
            .addMethod(MethodDef.builder("equals").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("other", TypeDef.OBJECT).returns(TypeDef.Primitive.BOOLEAN)
                .build { aThis, p -> aThis.equalsReferentially(p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(false, o.equals(null))
        }
    }

    // `new String[2]` is written `arrayOfNulls<String>(2)`, an `Array<String?>`, where the model's type is `Array<String>`: type mismatch on return/assignment.
    @Test
    fun newObjectArray() {
        val def = ClassDef.builder("test.E13")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING.array())
                .build { _, _ -> TypeDef.STRING.array().instantiate(2).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, (o.javaClass.getMethod("call").invoke(o) as kotlin.Array<*>).size)
        }
    }

    // A reference through a nullable parameter to a method that needs no adaptation is written `greeter::greet`: 'Bound callable reference cannot be created on nullable receiver'. The adapted path handles it with `!!.let`.
    @Test
    fun referenceThroughANullableReceiver() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC)
            .addParameter("name", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.E25")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC)
                .addParameter("greeter", ClassTypeDef.of(Greeter::class.java).makeNullable()).returns(function)
                .build { _, p -> function.methodReference(p[0], greet).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            @Suppress("UNCHECKED_CAST")
            val fn = o.javaClass.getMethod("get", Greeter::class.java).invoke(o, Greeter()) as Function<String, String>
            assertEquals("hi b", fn.apply("b"))
        }
    }

    // `int size()` of a java.util.AbstractList subclass is written `override fun size(): Int`; Kotlin maps it to the property `size`, so nothing is overridden and the class stays abstract.
    @Test
    fun abstractListOverride() {
        val def = ClassDef.builder("test.F04")
            .superclass(TypeDef.parameterized(java.util.AbstractList::class.java, String::class.java))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("index", TypeDef.Primitive.INT).returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.constant("a").returning() })
            .addMethod(MethodDef.builder("size").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.Primitive.INT)
                .build { _, _ -> ExpressionDef.constant(1).returning() })
            .build()
        compile(def).use { loader ->
            assertEquals(listOf("a"), newInstance(loader, def))
        }
    }

    // A String-typed value passed to `pick(Object)` while `pick(String)` exists: written `pick(text)`, which selects the String overload. Bytecode calls pick(Object). (The Java generator has the same defect.)
    @Test
    fun narrowerValueKeepsTheOverloadTheModelCalls() {
        val pick = MethodDef.builder("pick").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.D05")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("text", TypeDef.STRING).returns(TypeDef.STRING)
                .build { _, p -> ClassTypeDef.of(Fixtures::class.java).invokeStatic(pick, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.getMethod("call", String::class.java).invoke(o, "a"))
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

    // `ExpressionDef.nullValue()` passed to a String parameter is written `null as String`: always an NPE, where bytecode passes null.
    @Test
    fun nullConstantPassedToATypedParameter() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.D16")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { _, _ -> ClassTypeDef.of(Fixtures::class.java).invokeStatic(describe, ExpressionDef.nullValue()).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("null", o.javaClass.getMethod("call").invoke(o))
        }
    }

    /** A generic parent. */
    open class GenericParent<T> {
        open fun accept(value: T): String = value.toString()
    }

    /** Compiled fixtures. */
    class Fixtures {
        companion object {
            @JvmStatic fun count(vararg values: Any): Int = values.size
            @JvmStatic fun describe(value: String?): String = value ?: "null"
            @JvmStatic fun `when`(value: String): String = value
            @JvmStatic fun pick(value: Any): String = "object"
            @JvmStatic fun pick(value: String): String = "text"
            @JvmStatic fun total(value: Supplier<Number>): Int = value.get().toInt()
        }
    }

    /** A receiver of a method reference. */
    class Greeter {
        fun greet(name: String): String = "hi $name"
    }

    /** Declares a vararg method. */
    interface Joiner {
        fun join(vararg parts: String): String
    }

    /** Declares star projected parameters. */
    interface TypeAcceptor {
        fun accept(type: Class<*>, list: List<*>): String
    }

    /** Takes varargs in its constructor. */
    open class VarargsParent(vararg values: Any) {
        val size = values.size
    }
}
