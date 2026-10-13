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
import io.micronaut.sourcegen.KotlinCompileAssertions.outcomeOf
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Values the model allows to be null where Kotlin types are not nullable: null constants and platform values passed,
 * returned or received as parameters, and nullable receivers. The generated classes let the null through as the
 * bytecode does. Every program is compiled and run.
 */
class NullabilityWriteTest {

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
                .build { _, p -> ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(describe, p[0].invoke(get, ExpressionDef.constant("missing"))).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("null", o.javaClass.getMethod("call", java.util.Map::class.java).invoke(o, HashMap<String, Any>()))
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

    // `ExpressionDef.nullValue()` passed to a String parameter is written `null as String`: always an NPE, where bytecode passes null.
    @Test
    fun nullConstantPassedToATypedParameter() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.D16")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { _, _ -> ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(describe, ExpressionDef.nullValue()).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("null", o.javaClass.getMethod("call").invoke(o))
        }
    }

    @Test
    fun nullWrittenWhereTheModelTypeIsNotNullable() {
        val cache = FieldDef.builder("cache", String::class.java).addModifiers(Modifier.PRIVATE).build()
        val text = VariableDef.Local("text", TypeDef.STRING)
        val def = ClassDef.builder("test.NullAssignments").addModifiers(Modifier.PUBLIC).addField(cache)
            .addMethod(MethodDef.builder("returned").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { _, _ -> ExpressionDef.nullValue().returning() })
            .addMethod(MethodDef.builder("local").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { _, _ -> StatementDef.multi(StatementDef.DefineAndAssign(text, ExpressionDef.nullValue()), text.returning()) })
            .addMethod(MethodDef.builder("reset").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID)
                .build { self, _ -> self.field(cache).put(ExpressionDef.nullValue()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(null, cls.getMethod("returned").invoke(instance))
            assertEquals(null, cls.getMethod("local").invoke(instance))
            cls.getMethod("reset").invoke(instance)
        }
    }

    /** A field no constructor assigns holds null, which Java reads as it is where it is written as an `Object`. */
    @ParameterizedTest(name = "an unassigned {0} field read as an Object is null")
    @MethodSource("unassignedFieldTypes")
    fun unassignedFieldReadAsAnObject(type: TypeDef) {
        val field = FieldDef.builder("f", type).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.UnassignedField").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build { self, _ -> self.field(field).cast(TypeDef.OBJECT).returning() })
            .build()
        assertNull(invokeRun(def))
    }

    /** A conditional typed as a primitive with a null branch unboxes the null, which throws. */
    @ParameterizedTest(name = "the null branch of a conditional typed as an int, taken: {0}")
    @CsvSource("true, java.lang.NullPointerException", "false, 5")
    fun nullBranchOfAPrimitiveConditional(flag: Boolean, expected: String) {
        val outcome = outcomeOf {
            runMethod("test.NullBranch", TypeDef.Primitive.INT, listOf(TypeDef.Primitive.BOOLEAN, TypeDef.of(Integer::class.java)), flag, 5) { _, p ->
                ExpressionDef.IfElse(p[0].isTrue, ExpressionDef.nullValue(), p[1], TypeDef.Primitive.INT).returning()
            }
        }
        assertEquals(expected, if (outcome is Class<*>) outcome.name else outcome.toString())
    }

    /** A receiver of a method reference. */
    class Greeter {
        fun greet(name: String): String = "hi $name"
    }

    companion object {
        @JvmStatic
        fun unassignedFieldTypes(): List<Arguments> = listOf(
            arguments(named("Integer", TypeDef.of(Integer::class.java))),
            arguments(named("String", TypeDef.STRING)),
            arguments(named("int[]", TypeDef.Primitive.INT.array()))
        )
    }
}
