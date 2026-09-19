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
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType as Cmp
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.function.Function
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Review probes of the Kotlin generator for models shaped for the bytecode writer: each program is rendered,
 * compiled and run, and its behaviour compared with what the bytecode writer gives the same model.
 *
 * @since 2.2.2
 */
class ReviewKotlinTest {

    private val INT: Class<*> = Int::class.javaPrimitiveType!!
    private val LONG: Class<*> = Long::class.javaPrimitiveType!!
    private val CHAR: Class<*> = Char::class.javaPrimitiveType!!
    private val DOUBLE: Class<*> = Double::class.javaPrimitiveType!!
    private val BOOL: Class<*> = Boolean::class.javaPrimitiveType!!
    private val FIXTURES = ClassTypeDef.of(Fixtures::class.java)

    private fun render(definition: ObjectDef): String {
        val writer = StringWriter()
        KotlinPoetSourceGenerator().write(definition, writer)
        return writer.toString()
    }

    private fun compile(vararg definitions: ObjectDef): URLClassLoader =
        KotlinCompileAssertions.compileAndLoad(*definitions.map { render(it) }.toTypedArray())

    private fun newInstance(loader: ClassLoader, def: ObjectDef): Any =
        loader.loadClass(def.name).getDeclaredConstructor().newInstance()

    private fun method(name: String, vararg parameters: TypeDef): MethodDef.MethodDefBuilder {
        val builder = MethodDef.builder(name).addModifiers(Modifier.PUBLIC)
        parameters.forEachIndexed { index, type -> builder.addParameter("p$index", type) }
        return builder
    }

    // ---- nullability ----

    // `String call() { return null; }`: bytecode returns null, the source is `return null as String`, which throws.
    @Disabled("A null returned from a method the model types non-null is cast to the type, which throws; Kotlin has no non-null type that holds null")
    @Test
    fun nullReturnedFromANonNullMethod() {
        val def = ClassDef.builder("test.R01")
            .addMethod(method("call").returns(TypeDef.STRING).build { _, _ -> ExpressionDef.nullValue().returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertNull(o.javaClass.getMethod("call").invoke(o))
        }
    }

    // `null` passed to a generated `describe(String)` whose body null-checks its parameter: bytecode prints "null"; Kotlin's non-null parameter throws.
    @Disabled("A generated parameter is non-null even where its body null-checks it, so null cannot be passed")
    @Test
    fun nullPassedToANullCheckedParameterOfAGeneratedMethod() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING).returns(TypeDef.STRING)
            .build { _, p -> p[0].isNull.doIfElse(ExpressionDef.constant("null"), p[0]).returning() }
        val def = ClassDef.builder("test.R02").addMethod(describe)
            .addMethod(method("call").returns(TypeDef.STRING).build { self, _ -> self.invoke(describe, ExpressionDef.nullValue()).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("null", o.javaClass.getMethod("call").invoke(o))
        }
    }

    // A nullable field read, passed to a nullable parameter and returned: null flows through unchanged.
    @Test
    fun nullableFieldIsPassedAndReturned() {
        val field = FieldDef.builder("maybe", TypeDef.STRING.makeNullable()).addModifiers(Modifier.PRIVATE).build()
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING.makeNullable()).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.R03").addField(field)
            .addMethod(method("set", TypeDef.STRING.makeNullable()).returns(TypeDef.VOID).build { self, p -> self.field(field).put(p[0]) })
            .addMethod(method("get").returns(TypeDef.STRING.makeNullable()).build { self, _ -> self.field(field).returning() })
            .addMethod(method("describe").returns(TypeDef.STRING).build { self, _ -> FIXTURES.invokeStatic(describe, self.field(field)).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertNull(o.javaClass.getMethod("get").invoke(o))
            assertEquals("null", o.javaClass.getMethod("describe").invoke(o))
            o.javaClass.getMethod("set", String::class.java).invoke(o, "x")
            assertEquals("x", o.javaClass.getMethod("get").invoke(o))
            assertEquals("x", o.javaClass.getMethod("describe").invoke(o))
        }
    }

    // `String call(Map m) { return m.get("missing"); }`: the checkcast of the bytecode lets null through, `as String` throws.
    @Disabled("A platform value that is null returned from a method the model types non-null is cast to the type, which throws")
    @Test
    fun platformNullReturnedFromANonNullMethod() {
        val get = java.util.Map::class.java.getMethod("get", Any::class.java)
        val map = TypeDef.parameterized(java.util.Map::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.R04")
            .addMethod(method("call", map).returns(TypeDef.STRING).build { _, p -> p[0].invoke(get, ExpressionDef.constant("missing")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertNull(o.javaClass.getMethod("call", java.util.Map::class.java).invoke(o, HashMap<String, String>()))
        }
    }

    // ---- properties and fields ----

    // Blank fields of every kind - primitive, boxed, String, generic, object array, primitive array, nullable - assigned by setters.
    @Test
    fun blankFieldsOfEveryKindAreAssignedBySetters() {
        val types = linkedMapOf<String, TypeDef>(
            "count" to TypeDef.Primitive.INT,
            "boxed" to TypeDef.of(Integer::class.java),
            "text" to TypeDef.STRING,
            "list" to TypeDef.parameterized(java.util.List::class.java, String::class.java),
            "texts" to TypeDef.STRING.array(),
            "numbers" to TypeDef.Primitive.INT.array(),
            "maybe" to TypeDef.STRING.makeNullable()
        )
        val builder = ClassDef.builder("test.R05")
        types.forEach { (name, type) ->
            val field = FieldDef.builder(name, type).addModifiers(Modifier.PRIVATE).build()
            builder.addField(field)
                .addMethod(MethodDef.builder("set_$name").addModifiers(Modifier.PUBLIC).addParameter("v", type).returns(TypeDef.VOID)
                    .build { self, p -> self.field(field).put(p[0]) })
                .addMethod(MethodDef.builder("get_$name").addModifiers(Modifier.PUBLIC).returns(type)
                    .build { self, _ -> self.field(field).returning() })
        }
        val def = builder.build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val values = mapOf<String, Any>("count" to 3, "boxed" to 4, "text" to "t", "list" to listOf("l"),
                "texts" to arrayOf("a"), "numbers" to intArrayOf(1), "maybe" to "m")
            values.forEach { (name, value) ->
                val setter = o.javaClass.methods.first { it.name == "set_$name" }
                setter.invoke(o, value)
                val read = o.javaClass.getMethod("get_$name").invoke(o)
                if (value is Array<*>) assertArrayEquals(value, read as Array<*>) else if (value is IntArray) assertArrayEquals(value, read as IntArray)
                else assertEquals(value, read, name)
            }
        }
    }

    // A field assigned inside a lambda only: the assignment is counted where it is, so the property is a mutable one.
    @Test
    fun fieldAssignedInsideALambda() {
        val field = FieldDef.builder("text", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val runnable = ClassTypeDef.of(Runnable::class.java)
        val run = Runnable::class.java.getMethod("run")
        val def = ClassDef.builder("test.R06").addField(field)
            .addMethod(method("call").returns(TypeDef.STRING).build { self, _ ->
                runnable.getLambda().implement { _, _ -> self.field(field).put(ExpressionDef.constant("set")) }
                    .newLocal("r") { r -> StatementDef.multi(r.invoke(run), self.field(field).returning()) } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("set", o.javaClass.getMethod("call").invoke(o))
        }
    }

    // A static initializer reads a static field with an initializer and assigns a blank static one.
    @Test
    fun staticInitializerReadsAnInitializedStaticField() {
        val type = ClassTypeDef.of("test.R07")
        val a = FieldDef.builder("A", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer(ExpressionDef.constant("a")).build()
        val b = FieldDef.builder("B", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.STATIC).build()
        val def = ClassDef.builder(type.name).addField(a).addField(b)
            .addStaticInitializer(type.getStaticField(b).put(type.getStaticField(a).stringConcat(ExpressionDef.constant("b"))))
            .addMethod(method("get").returns(TypeDef.STRING).build { _, _ -> type.getStaticField(b).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("ab", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A field the constructor assigns and a method reassigns is a var, not a val.
    @Test
    fun fieldAssignedInTheConstructorAndInAMethod() {
        val field = FieldDef.builder("text", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.R08").addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING).build { self, p -> self.field(field).put(p[0]) })
            .addMethod(method("set", TypeDef.STRING).returns(TypeDef.VOID).build { self, p -> self.field(field).put(p[0]) })
            .addMethod(method("get").returns(TypeDef.STRING).build { self, _ -> self.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java).newInstance("a")
            assertEquals("a", o.javaClass.getMethod("get").invoke(o))
            o.javaClass.getMethod("set", String::class.java).invoke(o, "b")
            assertEquals("b", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // Fields assigned in a loop of the constructor: a primitive takes its default, a String is a lateinit var.
    @Test
    fun fieldsAssignedInALoopOfTheConstructor() {
        val total = FieldDef.builder("total", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE).build()
        val last = FieldDef.builder("last", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.R09").addField(total).addField(last)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).build { self, _ ->
                ExpressionDef.constant(0).newLocal("i") { i ->
                    i.compare(Cmp.LESS_THAN, ExpressionDef.constant(3)).whileLoop(StatementDef.multi(
                        self.field(total).put(self.field(total).math(OpType.ADDITION, i)),
                        self.field(last).put(ExpressionDef.constant("x").stringConcat(i)),
                        i.assign(i.math(OpType.ADDITION, ExpressionDef.constant(1)))
                    )) } })
            .addMethod(method("total").returns(TypeDef.Primitive.INT).build { self, _ -> self.field(total).returning() })
            .addMethod(method("last").returns(TypeDef.STRING).build { self, _ -> self.field(last).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(3, o.javaClass.getMethod("total").invoke(o))
            assertEquals("x2", o.javaClass.getMethod("last").invoke(o))
        }
    }

    // Static members of one generated class read, written and called from another generated file.
    @Test
    fun staticMembersOfAnotherGeneratedClass() {
        val owner = ClassTypeDef.of("test.R10Owner")
        val count = FieldDef.builder("COUNT", TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC, Modifier.STATIC).initializer(ExpressionDef.constant(0)).build()
        val bump = MethodDef.builder("bump").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.Primitive.INT)
            .build { _, _ -> StatementDef.multi(
                owner.getStaticField(count).put(owner.getStaticField(count).math(OpType.ADDITION, ExpressionDef.constant(1))),
                owner.getStaticField(count).returning()) }
        val ownerDef = ClassDef.builder(owner.name).addModifiers(Modifier.PUBLIC).addField(count).addMethod(bump).build()
        val def = ClassDef.builder("test.R10Caller")
            .addMethod(method("call").returns(TypeDef.Primitive.INT).build { _, _ -> StatementDef.multi(
                owner.getStaticField(count).put(ExpressionDef.constant(10)),
                owner.invokeStatic(bump),
                owner.invokeStatic(bump).math(OpType.ADDITION, owner.getStaticField(count)).returning()) })
            .build()
        compile(ownerDef, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(24, o.javaClass.getMethod("call").invoke(o))
        }
    }

    // ---- constructors ----

    // A secondary constructor delegating with an expression of its parameters: `constructor(a, b) : this(a + b)`.
    @Test
    fun secondaryConstructorDelegatesWithAnExpression() {
        val field = FieldDef.builder("value", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val primary = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("v", TypeDef.STRING)
            .build { self, p -> self.field(field).put(p[0]) }
        val def = ClassDef.builder("test.R11").addField(field).addMethod(primary)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("a", TypeDef.STRING).addParameter("b", TypeDef.STRING)
                .build { self, p -> self.invokeConstructor(primary, p[0].stringConcat(p[1])) })
            .addMethod(method("get").returns(TypeDef.STRING).build { self, _ -> self.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java, String::class.java).newInstance("a", "b")
            assertEquals("ab", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // A super constructor call whose argument is an expression of a parameter.
    @Test
    fun superConstructorArgumentIsAnExpression() {
        val exception = ClassTypeDef.of(Exception::class.java)
        val def = ClassDef.builder("test.R12").superclass(exception)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("message", TypeDef.STRING)
                .build { self, p -> self.superRef(exception).invokeSuperConstructor(listOf(TypeDef.STRING), p[0].stringConcat(ExpressionDef.constant("!"))) })
            .build()
        compile(def).use { loader ->
            val e = loader.loadClass(def.name).getConstructor(String::class.java).newInstance("m") as Exception
            assertEquals("m!", e.message)
        }
    }

    // A record with a method reading its component as a property and a static factory.
    @Test
    fun recordWithAMethodAndAStaticFactory() {
        val type = ClassTypeDef.of("test.R13")
        val x = PropertyDef.builder("x").ofType(TypeDef.Primitive.INT).build()
        val def = RecordDef.builder(type.name).addModifiers(Modifier.PUBLIC).addProperty(x)
            .addMethod(method("twice").returns(TypeDef.Primitive.INT).build { self, _ -> self.field("x", TypeDef.Primitive.INT).math(OpType.MULTIPLICATION, ExpressionDef.constant(2)).returning() })
            .addMethod(MethodDef.builder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("v", TypeDef.Primitive.INT).returns(type)
                .build { _, p -> type.instantiate(p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val companion = cls.getField("Companion").get(null)
            val record = companion.javaClass.getMethod("of", INT).invoke(companion, 21)
            assertEquals(42, cls.getMethod("twice").invoke(record))
            assertEquals(record, cls.getConstructor(INT).newInstance(21))
        }
    }

    // ---- overrides ----

    // An implementation of CharSequence: Kotlin maps `charAt` to `get` and `length()` to a property.
    @Disabled("The Java methods Kotlin renames on its mapped types (CharSequence.charAt is get) are not mapped, only the properties")
    @Test
    fun charSequenceOverride() {
        val text = FieldDef.builder("text", TypeDef.STRING).addModifiers(Modifier.PRIVATE, Modifier.FINAL).initializer(ExpressionDef.constant("abc")).build()
        val def = ClassDef.builder("test.R14").addSuperinterface(ClassTypeDef.of(CharSequence::class.java)).addField(text)
            .addMethod(MethodDef.builder("length").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.Primitive.INT)
                .build { self, _ -> self.field(text).invoke(String::class.java.getMethod("length")).returning() })
            .addMethod(MethodDef.builder("charAt").addModifiers(Modifier.PUBLIC).overrides().addParameter("index", TypeDef.Primitive.INT).returns(TypeDef.Primitive.CHAR)
                .build { self, p -> self.field(text).invoke(String::class.java.getMethod("charAt", INT), p[0]).returning() })
            .addMethod(MethodDef.builder("subSequence").addModifiers(Modifier.PUBLIC).overrides().addParameter("start", TypeDef.Primitive.INT).addParameter("end", TypeDef.Primitive.INT).returns(ClassTypeDef.of(CharSequence::class.java))
                .build { self, p -> self.field(text).invoke(String::class.java.getMethod("subSequence", INT, INT), p[0], p[1]).returning() })
            .addMethod(MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
                .build { self, _ -> self.field(text).returning() })
            .build()
        compile(def).use { loader ->
            val cs = newInstance(loader, def) as CharSequence
            assertEquals(3, cs.length)
            assertEquals('b', cs[1])
            assertEquals("bc", cs.subSequence(1, 3).toString())
        }
    }

    // An erased `compareTo(Object)` of `Comparable<Self>` is resolved to `compareTo(Self)`.
    @Test
    fun erasedComparableOverride() {
        val type = ClassTypeDef.of("test.R15")
        val weight = FieldDef.builder("weight", TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC, Modifier.FINAL).build()
        val def = ClassDef.builder(type.name).addSuperinterface(TypeDef.parameterized(Comparable::class.java, type)).addField(weight).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC).overrides().addParameter("other", TypeDef.OBJECT).returns(TypeDef.Primitive.INT)
                .build { self, p -> ClassTypeDef.of(Integer::class.java).invokeStatic(Integer::class.java.getMethod("compare", INT, INT),
                    self.field(weight), p[0].cast(type).field(weight)).returning() })
            .build()
        compile(def).use { loader ->
            val ctor = loader.loadClass(def.name).getConstructor(INT)
            @Suppress("UNCHECKED_CAST")
            val light = ctor.newInstance(1) as Comparable<Any>
            val heavy = ctor.newInstance(2)
            assertEquals(-1, light.compareTo(heavy))
        }
    }

    // A covariant return: the interface returns CharSequence, the class String.
    @Test
    fun covariantReturnOverride() {
        val source = InterfaceDef.builder("test.R16Source").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(ClassTypeDef.of(CharSequence::class.java)).build()).build()
        val def = ClassDef.builder("test.R16").addSuperinterface(source.asTypeDef())
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING).build { _, _ -> ExpressionDef.constant("text").returning() })
            .build()
        compile(source, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("text", loader.loadClass(source.name).getMethod("get").invoke(o))
            assertEquals(String::class.java, o.javaClass.getMethod("get").returnType)
        }
    }

    // A generated class extended by another generated class must be open, and the overridden method too.
    @Disabled("A generated class and its methods are final in Kotlin: another generated class cannot extend it")
    @Test
    fun generatedSuperclassIsOpen() {
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING).build { _, _ -> ExpressionDef.constant("a").returning() }
        val parent = ClassDef.builder("test.R17Parent").addModifiers(Modifier.PUBLIC).addMethod(greet).build()
        val def = ClassDef.builder("test.R17").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING)
                .build { self, _ -> self.superRef().invoke(greet).stringConcat(ExpressionDef.constant("b")).returning() })
            .build()
        compile(parent, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("ab", o.javaClass.getMethod("greet").invoke(o))
        }
    }

    // An abstract generated class with an abstract member implemented in another file.
    @Test
    fun abstractGeneratedClassImplementedInAnotherFile() {
        val name = MethodDef.builder("name").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.STRING).build()
        val parent = ClassDef.builder("test.R18Parent").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addMethod(name)
            .addMethod(method("describe").returns(TypeDef.STRING).build { self, _ -> ExpressionDef.constant("I am ").stringConcat(self.invoke(name)).returning() })
            .build()
        val def = ClassDef.builder("test.R18").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING).build { _, _ -> ExpressionDef.constant("x").returning() })
            .build()
        compile(parent, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("I am x", o.javaClass.getMethod("describe").invoke(o))
        }
    }

    // A generic interface method overridden with its own type variable, not erased.
    @Test
    fun genericMethodOverrideKeepsItsVariable() {
        val t = TypeDef.variable("T")
        val list = TypeDef.parameterized(ClassTypeDef.of(java.util.List::class.java), t)
        val wrap = MethodDef.builder("wrap").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(t).addParameter("value", t).returns(list).build()
        val wrapper = InterfaceDef.builder("test.R19Wrapper").addModifiers(Modifier.PUBLIC).addMethod(wrap).build()
        val def = ClassDef.builder("test.R19").addSuperinterface(wrapper.asTypeDef())
            .addMethod(MethodDef.builder("wrap").addModifiers(Modifier.PUBLIC).overrides().addTypeVariable(t).addParameter("value", t).returns(list)
                .build { _, p -> ClassTypeDef.of(java.util.Collections::class.java).invokeStatic(java.util.Collections::class.java.getMethod("singletonList", Any::class.java), p[0]).returning() })
            .build()
        compile(wrapper, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(listOf("a"), loader.loadClass(wrapper.name).getMethod("wrap", Any::class.java).invoke(o, "a"))
        }
    }

    // `hashCode()` and `toString()` overrides.
    @Test
    fun hashCodeAndToStringOverrides() {
        val def = ClassDef.builder("test.R20")
            .addMethod(MethodDef.builder("hashCode").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.Primitive.INT).build { _, _ -> ExpressionDef.constant(7).returning() })
            .addMethod(MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING).build { _, _ -> ExpressionDef.constant("R20").returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(7, o.hashCode())
            assertEquals("R20", o.toString())
        }
    }

    // ---- expressions ----

    // A statement switch on strings whose cases do not return: execution continues after the switch, as the bytecode's goto does.
    @Test
    fun switchStatementOnStringsWithoutReturns() {
        val field = FieldDef.builder("count", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.R21").addField(field)
            .addMethod(method("call", TypeDef.STRING).returns(TypeDef.Primitive.INT).build { self, p -> StatementDef.multi(
                p[0].asStatementSwitch(TypeDef.VOID, linkedMapOf<ExpressionDef.Constant, StatementDef>(
                    ExpressionDef.constant("a") to self.field(field).put(ExpressionDef.constant(1)),
                    ExpressionDef.constant("b") to self.field(field).put(ExpressionDef.constant(2)),
                    ExpressionDef.constant("empty") to StatementDef.multi()
                ), self.field(field).put(ExpressionDef.constant(3))),
                self.field(field).math(OpType.ADDITION, ExpressionDef.constant(10)).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val call = o.javaClass.getMethod("call", String::class.java)
            assertEquals(11, call.invoke(o, "a"))
            assertEquals(12, call.invoke(o, "b"))
            assertEquals(13, call.invoke(o, "other"))
            assertEquals(13, call.invoke(o, "empty"))
        }
    }

    // A switch expression on strings, with a yielding case, assigned to a local.
    @Test
    fun switchExpressionOnStrings() {
        val def = ClassDef.builder("test.R22")
            .addMethod(method("call", TypeDef.STRING).returns(TypeDef.STRING).build { _, p ->
                p[0].asExpressionSwitch(TypeDef.STRING, linkedMapOf<ExpressionDef.Constant, ExpressionDef>(
                    ExpressionDef.constant("a") to ExpressionDef.constant("A"),
                    ExpressionDef.constant("b") to ExpressionDef.SwitchYieldCase(TypeDef.STRING,
                        p[0].stringConcat(ExpressionDef.constant("!")).newLocal("t") { t -> t.returning() })
                ), ExpressionDef.constant("?")).newLocal("r") { r -> r.stringConcat(ExpressionDef.constant(".")).returning() } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val call = o.javaClass.getMethod("call", String::class.java)
            assertEquals("A.", call.invoke(o, "a"))
            assertEquals("b!.", call.invoke(o, "b"))
            assertEquals("?.", call.invoke(o, "c"))
        }
    }

    // Conditionals as operands: of `+`, of a call target, of a comparison, of an argument, of a concatenation.
    @Test
    fun conditionalsNestedInOperands() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", TypeDef.STRING.makeNullable()).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.R23")
            .addMethod(method("plus", TypeDef.Primitive.BOOLEAN).returns(TypeDef.Primitive.INT).build { _, p ->
                p[0].isTrue.doIfElse(ExpressionDef.constant(1), ExpressionDef.constant(2)).math(OpType.ADDITION, ExpressionDef.constant(3)).returning() })
            .addMethod(method("length", TypeDef.Primitive.BOOLEAN).returns(TypeDef.Primitive.INT).build { _, p ->
                p[0].isTrue.doIfElse(ExpressionDef.constant("x"), ExpressionDef.constant("yy")).invoke(String::class.java.getMethod("length")).returning() })
            .addMethod(method("eq", TypeDef.Primitive.BOOLEAN).returns(TypeDef.Primitive.BOOLEAN).build { _, p ->
                p[0].isTrue.doIfElse(ExpressionDef.constant(1), ExpressionDef.constant(2)).compare(Cmp.EQUAL_TO, ExpressionDef.constant(1)).returning() })
            .addMethod(method("arg", TypeDef.Primitive.BOOLEAN).returns(TypeDef.STRING).build { _, p ->
                FIXTURES.invokeStatic(describe, p[0].isTrue.doIfElse(ExpressionDef.constant("x"), ExpressionDef.constant("y"))).returning() })
            .addMethod(method("concat", TypeDef.Primitive.BOOLEAN).returns(TypeDef.STRING).build { _, p ->
                ExpressionDef.constant("<").stringConcat(p[0].isTrue.doIfElse(ExpressionDef.constant("x"), ExpressionDef.constant("y"))).stringConcat(ExpressionDef.constant(">")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(4, o.javaClass.getMethod("plus", BOOL).invoke(o, true))
            assertEquals(5, o.javaClass.getMethod("plus", BOOL).invoke(o, false))
            assertEquals(2, o.javaClass.getMethod("length", BOOL).invoke(o, false))
            assertEquals(false, o.javaClass.getMethod("eq", BOOL).invoke(o, false))
            assertEquals("y", o.javaClass.getMethod("arg", BOOL).invoke(o, false))
            assertEquals("<x>", o.javaClass.getMethod("concat", BOOL).invoke(o, true))
        }
    }

    // Primitive casts run as the bytecode converts: widening, narrowing with wrap and truncation, char codes, unboxing.
    @Test
    fun primitiveCastsRunAsTheBytecodeConverts() {
        val def = ClassDef.builder("test.R24")
            .addMethod(method("intToLong", TypeDef.Primitive.INT).returns(TypeDef.Primitive.LONG).build { _, p -> p[0].cast(TypeDef.Primitive.LONG).returning() })
            .addMethod(method("charToInt", TypeDef.Primitive.CHAR).returns(TypeDef.Primitive.INT).build { _, p -> p[0].cast(TypeDef.Primitive.INT).returning() })
            .addMethod(method("intToChar", TypeDef.Primitive.INT).returns(TypeDef.Primitive.CHAR).build { _, p -> p[0].cast(TypeDef.Primitive.CHAR).returning() })
            .addMethod(method("objectToInt", TypeDef.OBJECT).returns(TypeDef.Primitive.INT).build { _, p -> p[0].cast(TypeDef.Primitive.INT).returning() })
            .addMethod(method("numberToLong", TypeDef.of(Number::class.java)).returns(TypeDef.Primitive.LONG).build { _, p -> p[0].cast(TypeDef.Primitive.LONG).returning() })
            .addMethod(method("doubleToInt", TypeDef.Primitive.DOUBLE).returns(TypeDef.Primitive.INT).build { _, p -> p[0].cast(TypeDef.Primitive.INT).returning() })
            .addMethod(method("longToInt", TypeDef.Primitive.LONG).returns(TypeDef.Primitive.INT).build { _, p -> p[0].cast(TypeDef.Primitive.INT).returning() })
            .addMethod(method("intToByte", TypeDef.Primitive.INT).returns(TypeDef.Primitive.BYTE).build { _, p -> p[0].cast(TypeDef.Primitive.BYTE).returning() })
            .addMethod(method("anyToInts", TypeDef.OBJECT).returns(TypeDef.Primitive.INT.array()).build { _, p -> p[0].cast(TypeDef.Primitive.INT.array()).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val c = o.javaClass
            assertEquals(5L, c.getMethod("intToLong", INT).invoke(o, 5))
            assertEquals(65, c.getMethod("charToInt", CHAR).invoke(o, 'A'))
            assertEquals('B', c.getMethod("intToChar", INT).invoke(o, 66))
            assertEquals(7, c.getMethod("objectToInt", Any::class.java).invoke(o, 7))
            assertEquals(9L, c.getMethod("numberToLong", Number::class.java).invoke(o, 9.7))
            assertEquals(3, c.getMethod("doubleToInt", DOUBLE).invoke(o, 3.9))
            assertEquals(0, c.getMethod("doubleToInt", DOUBLE).invoke(o, Double.NaN))
            assertEquals(-1, c.getMethod("longToInt", LONG).invoke(o, 0xFFFFFFFFL))
            assertEquals((-56).toByte(), c.getMethod("intToByte", INT).invoke(o, 200))
            assertArrayEquals(intArrayOf(1, 2), c.getMethod("anyToInts", Any::class.java).invoke(o, intArrayOf(1, 2)) as IntArray)
        }
    }

    // String constants with `$`, newlines, backslashes, quotes, tabs, unicode, `"""` and margin characters keep their value.
    @Test
    fun stringConstantsWithSpecialCharacters() {
        val values = listOf("a\$b", "\${x}", "line1\nline2", "back\\slash", "quote\"q", "tab\there", "unicode é ☃", "triple\"\"\"quoted",
            "  indented\n  lines", "x\n|margin", "cr\r\nlf", "trailing \n", "\n", "")
        val def = ClassDef.builder("test.R25")
            .addMethod(method("call").returns(TypeDef.STRING.array()).build { _, _ ->
                TypeDef.STRING.array().instantiate(values.map { ExpressionDef.constant(it) }).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertArrayEquals(values.toTypedArray(), o.javaClass.getMethod("call").invoke(o) as Array<*>)
        }
    }

    // Character constants needing escapes, `$` and a control character.
    @Test
    fun charConstantsNeedingEscapes() {
        val values = listOf('\'', '\\', '\n', '$', '"', '\u0000', 'é', '\t', '☃')
        val def = ClassDef.builder("test.R26")
            .addMethod(method("call").returns(TypeDef.Primitive.CHAR.array()).build { _, _ ->
                TypeDef.Primitive.CHAR.array().instantiate(values.map { ExpressionDef.constant(it) }).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertArrayEquals(values.toCharArray(), o.javaClass.getMethod("call").invoke(o) as CharArray)
        }
    }

    // The extreme int, short, byte, float and double constants.
    @Test
    fun extremeIntAndFloatingPointConstants() {
        val def = ClassDef.builder("test.R27")
            .addMethod(method("minInt").returns(TypeDef.Primitive.INT).build { _, _ -> ExpressionDef.constant(Int.MIN_VALUE).returning() })
            .addMethod(method("maxInt").returns(TypeDef.Primitive.INT).build { _, _ -> ExpressionDef.constant(Int.MAX_VALUE).returning() })
            .addMethod(method("minShort").returns(TypeDef.Primitive.SHORT).build { _, _ -> ExpressionDef.primitiveConstant(Short.MIN_VALUE).returning() })
            .addMethod(method("minByte").returns(TypeDef.Primitive.BYTE).build { _, _ -> ExpressionDef.primitiveConstant(Byte.MIN_VALUE).returning() })
            .addMethod(method("negativeZero").returns(TypeDef.Primitive.DOUBLE).build { _, _ -> ExpressionDef.constant(-0.0).returning() })
            .addMethod(method("bigFloat").returns(TypeDef.Primitive.FLOAT).build { _, _ -> ExpressionDef.constant(1e10f).returning() })
            .addMethod(method("minFloat").returns(TypeDef.Primitive.FLOAT).build { _, _ -> ExpressionDef.constant(Float.MIN_VALUE).returning() })
            .addMethod(method("maxDouble").returns(TypeDef.Primitive.DOUBLE).build { _, _ -> ExpressionDef.constant(Double.MAX_VALUE).returning() })
            .addMethod(method("minDouble").returns(TypeDef.Primitive.DOUBLE).build { _, _ -> ExpressionDef.constant(Double.MIN_VALUE).returning() })
            .addMethod(method("nan").returns(TypeDef.Primitive.FLOAT).build { _, _ -> ExpressionDef.constant(Float.NaN).returning() })
            .addMethod(method("maxLong").returns(TypeDef.Primitive.LONG).build { _, _ -> ExpressionDef.constant(Long.MAX_VALUE).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val c = o.javaClass
            assertEquals(Int.MIN_VALUE, c.getMethod("minInt").invoke(o))
            assertEquals(Int.MAX_VALUE, c.getMethod("maxInt").invoke(o))
            assertEquals(Short.MIN_VALUE, c.getMethod("minShort").invoke(o))
            assertEquals(Byte.MIN_VALUE, c.getMethod("minByte").invoke(o))
            assertEquals((-0.0).toBits(), (c.getMethod("negativeZero").invoke(o) as Double).toBits())
            assertEquals(1e10f, c.getMethod("bigFloat").invoke(o))
            assertEquals(Float.MIN_VALUE, c.getMethod("minFloat").invoke(o))
            assertEquals(Double.MAX_VALUE, c.getMethod("maxDouble").invoke(o))
            assertEquals(Double.MIN_VALUE, c.getMethod("minDouble").invoke(o))
            assertEquals(true, (c.getMethod("nan").invoke(o) as Float).isNaN())
            assertEquals(Long.MAX_VALUE, c.getMethod("maxLong").invoke(o))
        }
    }

    // `Long.MIN_VALUE` is written `-9223372036854775808L`, whose literal is out of range for kotlinc.
    @Test
    fun minLongConstant() {
        val def = ClassDef.builder("test.R28")
            .addMethod(method("minLong").returns(TypeDef.Primitive.LONG).build { _, _ -> ExpressionDef.constant(Long.MIN_VALUE).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(Long.MIN_VALUE, o.javaClass.getMethod("minLong").invoke(o))
        }
    }

    // `new String[] {"a", null}` and `new Integer[] {1, null}`: an object array holds null in bytecode; `arrayOf<String>("a", null)` does not compile.
    @Disabled("An array initializer with a null element is written with a non-null component")
    @Test
    fun objectArrayWithANullElement() {
        val integer = TypeDef.of(Integer::class.java)
        val def = ClassDef.builder("test.R29")
            .addMethod(method("texts").returns(TypeDef.STRING.array()).build { _, _ ->
                TypeDef.STRING.array().instantiate(ExpressionDef.constant("a"), ExpressionDef.nullValue()).returning() })
            .addMethod(method("numbers").returns(integer.array()).build { _, _ ->
                integer.array().instantiate(ExpressionDef.constant(1), ExpressionDef.nullValue()).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertArrayEquals(arrayOf("a", null), o.javaClass.getMethod("texts").invoke(o) as Array<*>)
            assertArrayEquals(arrayOf(1, null), o.javaClass.getMethod("numbers").invoke(o) as Array<*>)
        }
    }

    // `<T> T[] wrap(T value) { return new T[] {value}; }`: the bytecode creates an Object[]; `arrayOf<T>(value)` needs a reified T.
    @Test
    fun genericArrayCreation() {
        val t = TypeDef.variable("T")
        val def = ClassDef.builder("test.R30")
            .addMethod(MethodDef.builder("wrap").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("value", t).returns(t.array())
                .build { _, p -> t.array().instantiate(p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertArrayEquals(arrayOf<Any>("a"), o.javaClass.getMethod("wrap", Any::class.java).invoke(o, "a") as Array<*>)
        }
    }

    // A sized array of a class type and a two-dimensional one.
    @Test
    fun sizedAndMultiDimensionalArrays() {
        val def = ClassDef.builder("test.R31")
            .addMethod(method("texts").returns(TypeDef.STRING.array()).build { _, _ -> TypeDef.STRING.array().instantiate(2).returning() })
            .addMethod(method("grid").returns(TypeDef.array(TypeDef.Primitive.INT, 2)).build { _, _ ->
                TypeDef.array(TypeDef.Primitive.INT, 2).instantiate(TypeDef.Primitive.INT.array().instantiate(ExpressionDef.constant(1)), TypeDef.Primitive.INT.array().instantiate(2)).returning() })
            .addMethod(method("rows").returns(TypeDef.array(TypeDef.STRING, 2)).build { _, _ -> TypeDef.array(TypeDef.STRING, 2).instantiate(3).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, (o.javaClass.getMethod("texts").invoke(o) as Array<*>).size)
            val grid = o.javaClass.getMethod("grid").invoke(o) as Array<*>
            assertArrayEquals(intArrayOf(1), grid[0] as IntArray)
            assertArrayEquals(intArrayOf(0, 0), grid[1] as IntArray)
            val rows = o.javaClass.getMethod("rows").invoke(o) as Array<*>
            assertEquals(3, rows.size)
            assertNull(rows[0])
        }
    }

    // ---- lambdas and references ----

    // `super::greet` as a Supplier: the bytecode makes the special call to the parent's method.
    @Test
    fun superMethodReference() {
        val base = ClassTypeDef.of(Base::class.java)
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING).build()
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        val def = ClassDef.builder("test.R32").superclass(base)
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.STRING).build { _, _ -> ExpressionDef.constant("child").returning() })
            .addMethod(method("parent").returns(supplier).build { self, _ -> supplier.methodReference(self.superRef(), greet).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("base", (o.javaClass.getMethod("parent").invoke(o) as Supplier<*>).get())
        }
    }

    // `this::greet` as a Function, and a reference to an overloaded Java static method.
    @Test
    fun thisAndOverloadedStaticReferences() {
        val function = TypeDef.parameterized(Function::class.java, String::class.java, String::class.java)
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).addParameter("name", TypeDef.STRING).returns(TypeDef.STRING)
            .build { _, p -> ExpressionDef.constant("hi ").stringConcat(p[0]).returning() }
        val valueOf = MethodDef.builder("valueOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val objectToString = TypeDef.parameterized(Function::class.java, Any::class.java, String::class.java)
        val def = ClassDef.builder("test.R33").addMethod(greet)
            .addMethod(method("self").returns(function).build { self, _ -> function.methodReference(self, greet).returning() })
            .addMethod(method("valueOf").returns(objectToString).build { _, _ -> objectToString.staticMethodReference(ClassTypeDef.of(String::class.java), valueOf).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            @Suppress("UNCHECKED_CAST")
            assertEquals("hi a", (o.javaClass.getMethod("self").invoke(o) as Function<String, String>).apply("a"))
            @Suppress("UNCHECKED_CAST")
            assertEquals("12", (o.javaClass.getMethod("valueOf").invoke(o) as Function<Any, String>).apply(12))
        }
    }

    // A lambda capturing a local the loop before it reassigns.
    @Test
    fun lambdaCapturesAReassignedLocal() {
        val supplier = TypeDef.parameterized(Supplier::class.java, Integer::class.java)
        val def = ClassDef.builder("test.R34")
            .addMethod(method("call").returns(supplier).build { _, _ ->
                ExpressionDef.constant(0).newLocal("count") { count -> StatementDef.multi(
                    count.compare(Cmp.LESS_THAN, ExpressionDef.constant(3)).whileLoop(count.assign(count.math(OpType.ADDITION, ExpressionDef.constant(1)))),
                    supplier.getLambda().implement { _, _ -> count.returning() }.returning()) } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(3, (o.javaClass.getMethod("call").invoke(o) as Supplier<*>).get())
        }
    }

    // A lambda with a labelled return inside a loop, and a nested lambda of the same interface returning from itself only.
    @Test
    fun lambdaReturnsFromInsideALoopAndANestedLambda() {
        val function = TypeDef.parameterized(Function::class.java, Integer::class.java, String::class.java)
        val apply = Function::class.java.getMethod("apply", Any::class.java)
        val def = ClassDef.builder("test.R35")
            .addMethod(method("loop").returns(function).build { _, _ ->
                function.getLambda().implement(listOf("start")) { _, p ->
                    p[0].cast(TypeDef.Primitive.INT).newLocal("i") { i -> StatementDef.multi(
                        ExpressionDef.trueValue().whileLoop(StatementDef.multi(
                            i.compare(Cmp.GREATER_THAN, ExpressionDef.constant(3)).doIf(ExpressionDef.constant("done:").stringConcat(i).returning()),
                            i.assign(i.math(OpType.ADDITION, ExpressionDef.constant(1))))),
                        ExpressionDef.constant("never").returning()) } }.returning() })
            .addMethod(method("nested").returns(function).build { _, _ ->
                function.getLambda().implement(listOf("outer")) { _, p ->
                    function.getLambda().implement(listOf("inner")) { _, q ->
                        StatementDef.multi(
                            q[0].cast(TypeDef.Primitive.INT).compare(Cmp.EQUAL_TO, ExpressionDef.constant(0)).doIf(ExpressionDef.constant("zero").returning()),
                            ExpressionDef.constant("inner ").stringConcat(q[0]).returning()) }
                        .newLocal("f") { f -> ExpressionDef.constant("outer ").stringConcat(f.invoke(apply, p[0])).returning() } }.returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            @Suppress("UNCHECKED_CAST")
            assertEquals("done:4", (o.javaClass.getMethod("loop").invoke(o) as Function<Int, String>).apply(1))
            @Suppress("UNCHECKED_CAST")
            val nested = o.javaClass.getMethod("nested").invoke(o) as Function<Int, String>
            assertEquals("outer zero", nested.apply(0))
            assertEquals("outer inner 2", nested.apply(2))
        }
    }

    // ---- control flow ----

    // `throw e` of a nullable expression: bytecode throws it, or NPE for null.
    @Test
    fun throwOfANullableExpression() {
        val def = ClassDef.builder("test.R36")
            .addMethod(method("call", ClassTypeDef.of(RuntimeException::class.java).makeNullable()).returns(TypeDef.VOID).build { _, p -> p[0].doThrow() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val call = o.javaClass.getMethod("call", RuntimeException::class.java)
            assertEquals("boom", assertThrows(InvocationTargetException::class.java) { call.invoke(o, IllegalStateException("boom")) }.cause!!.message)
            assertEquals(NullPointerException::class.java, assertThrows(InvocationTargetException::class.java) { call.invoke(o, null) }.cause!!.javaClass)
        }
    }

    // A synchronized block around a loop over a list by index, returning from inside the loop.
    @Test
    fun synchronizedLoopReturningFromInside() {
        val list = TypeDef.parameterized(java.util.List::class.java, String::class.java)
        val size = java.util.List::class.java.getMethod("size")
        val get = java.util.List::class.java.getMethod("get", INT)
        val def = ClassDef.builder("test.R37")
            .addMethod(method("find", list, TypeDef.STRING).returns(TypeDef.Primitive.INT).build { self, p ->
                StatementDef.Synchronized(self, ExpressionDef.constant(0).newLocal("i") { i -> StatementDef.multi(
                    i.compare(Cmp.LESS_THAN, p[0].invoke(size)).whileLoop(StatementDef.multi(
                        p[0].invoke(get, i).cast(TypeDef.STRING).equalsStructurally(p[1]).doIf(i.returning()),
                        i.assign(i.math(OpType.ADDITION, ExpressionDef.constant(1))))),
                    ExpressionDef.constant(-1).returning()) }) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val find = o.javaClass.getMethod("find", java.util.List::class.java, String::class.java)
            assertEquals(1, find.invoke(o, listOf("a", "b"), "b"))
            assertEquals(-1, find.invoke(o, listOf("a", "b"), "c"))
        }
    }

    // A try whose catch and finally both run, and a local declared in the try read after it in the finally.
    @Test
    fun tryCatchFinallyWithALocalOfTheTry() {
        val sb = ClassTypeDef.of(java.lang.StringBuilder::class.java)
        val append = java.lang.StringBuilder::class.java.getMethod("append", String::class.java)
        val def = ClassDef.builder("test.R38")
            .addMethod(method("call", TypeDef.Primitive.BOOLEAN).returns(TypeDef.STRING).build { _, p ->
                sb.instantiate().newLocal("out") { out -> StatementDef.multi(
                    StatementDef.doTry(StatementDef.multi(
                        out.invoke(append, ExpressionDef.constant("try;")),
                        p[0].isTrue.doIf(ClassTypeDef.of(IllegalStateException::class.java).instantiate(ExpressionDef.constant("x")).doThrow())))
                        .doCatch(RuntimeException::class.java) { e -> out.invoke(append, e.invoke(Throwable::class.java.getMethod("getMessage"))) }
                        .doFinally(out.invoke(append, ExpressionDef.constant(";finally"))),
                    out.invoke(Any::class.java.getMethod("toString")).returning()) } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("try;x;finally", o.javaClass.getMethod("call", BOOL).invoke(o, true))
            assertEquals("try;;finally", o.javaClass.getMethod("call", BOOL).invoke(o, false))
        }
    }

    // ---- naming ----

    // Kotlin keywords as the names of a method, its parameters, a local and a field.
    @Test
    fun keywordsAsIdentifiers() {
        val field = FieldDef.builder("in", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val names = listOf("fun", "val", "object", "is", "when", "typealias", "interface", "package", "it")
        val builder = MethodDef.builder("when").addModifiers(Modifier.PUBLIC)
        names.forEach { builder.addParameter(it, TypeDef.STRING) }
        val def = ClassDef.builder("test.R39").addField(field)
            .addMethod(builder.returns(TypeDef.STRING).build { self, p ->
                StatementDef.multi(
                    self.field(field).put(p[0]),
                    p[1].stringConcat(p[2]).newLocal("object") { local ->
                        var joined: ExpressionDef = self.field(field).stringConcat(local)
                        for (index in 3 until p.size) {
                            joined = joined.stringConcat(p[index])
                        }
                        joined.returning() }) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val method = o.javaClass.getMethod("when", *names.map { String::class.java }.toTypedArray())
            assertEquals(names.joinToString(""), method.invoke(o, *names.toTypedArray()))
        }
    }

    // A parameter named `it` of the Iterator type whose mutator is called: `(it as MutableIterator<String>).remove()`.
    @Test
    fun iteratorRemoveThroughAParameterNamedIt() {
        val iterator = TypeDef.parameterized(java.util.Iterator::class.java, String::class.java)
        val def = ClassDef.builder("test.R40")
            .addMethod(MethodDef.builder("drop").addModifiers(Modifier.PUBLIC).addParameter("it", iterator).returns(TypeDef.STRING).build { _, p ->
                p[0].invoke(java.util.Iterator::class.java.getMethod("next")).cast(TypeDef.STRING).newLocal("next") { next -> StatementDef.multi(
                    p[0].invoke(java.util.Iterator::class.java.getMethod("remove")),
                    next.returning()) } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val list = arrayListOf("a", "b")
            assertEquals("a", o.javaClass.getMethod("drop", java.util.Iterator::class.java).invoke(o, list.iterator()))
            assertEquals(listOf("b"), list)
        }
    }

    // ---- types ----

    // A method parameter and return of `java.lang.Integer` keep the boxed JVM signature the bytecode has.
    @Disabled("Kotlin maps java.lang.Integer to a non-null Int: the JVM signature takes a primitive and null cannot be passed")
    @Test
    fun boxedParameterKeepsItsJvmSignature() {
        val integer = TypeDef.of(Integer::class.java)
        val def = ClassDef.builder("test.R41")
            .addMethod(method("call", integer).returns(integer).build { _, p -> p[0].returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val call = o.javaClass.getMethod("call", Integer::class.java)
            assertEquals(Integer::class.java, call.returnType)
            assertEquals(5, call.invoke(o, 5))
        }
    }

    // A raw `java.util.List` parameter and a raw `Map` return.
    @Disabled("A raw Java type is written without type arguments, which Kotlin rejects")
    @Test
    fun rawJavaTypes() {
        val list = ClassTypeDef.of(java.util.List::class.java)
        val map = ClassTypeDef.of(java.util.Map::class.java)
        val def = ClassDef.builder("test.R42")
            .addMethod(method("size", list).returns(TypeDef.Primitive.INT).build { _, p -> p[0].invoke(java.util.List::class.java.getMethod("size")).returning() })
            .addMethod(method("map").returns(map).build { _, _ -> ClassTypeDef.of(java.util.HashMap::class.java).instantiate().returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, o.javaClass.getMethod("size", java.util.List::class.java).invoke(o, listOf(1, 2)))
            assertEquals(HashMap<Any, Any>(), o.javaClass.getMethod("map").invoke(o))
        }
    }

    // `Throwable.getMessage()` is mapped to the property `message`, which is `String?`; the model's call returns a `String` it can return.
    @Disabled("Throwable.getMessage is mapped to the nullable property message, which a non-null return type cannot hold")
    @Test
    fun throwableMessageReturnedAsAString() {
        val def = ClassDef.builder("test.R43")
            .addMethod(method("call", ClassTypeDef.of(Throwable::class.java)).returns(TypeDef.STRING).build { _, p ->
                p[0].invoke(Throwable::class.java.getMethod("getMessage")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("m", o.javaClass.getMethod("call", Throwable::class.java).invoke(o, RuntimeException("m")))
        }
    }

    // Calls of the members Kotlin maps to properties: `entry.getKey()`, `list.size()`, `sb.length()`, `e.name()`.
    @Test
    fun callsOfMembersMappedToProperties() {
        val entry = TypeDef.parameterized(java.util.Map.Entry::class.java, String::class.java, Integer::class.java)
        val list = TypeDef.parameterized(java.util.List::class.java, String::class.java)
        val sb = ClassTypeDef.of(java.lang.StringBuilder::class.java)
        val def = ClassDef.builder("test.R44")
            .addMethod(method("key", entry).returns(TypeDef.STRING).build { _, p -> p[0].invoke(java.util.Map.Entry::class.java.getMethod("getKey")).cast(TypeDef.STRING).returning() })
            .addMethod(method("size", list).returns(TypeDef.Primitive.INT).build { _, p -> p[0].invoke(java.util.List::class.java.getMethod("size")).returning() })
            .addMethod(method("length", sb).returns(TypeDef.Primitive.INT).build { _, p -> p[0].invoke(java.lang.StringBuilder::class.java.getMethod("length")).returning() })
            .addMethod(method("name", ClassTypeDef.of(Thread.State::class.java)).returns(TypeDef.STRING).build { _, p -> p[0].invoke(Thread.State::class.java.getMethod("name")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("k", o.javaClass.getMethod("key", java.util.Map.Entry::class.java).invoke(o, java.util.AbstractMap.SimpleEntry("k", 1)))
            assertEquals(2, o.javaClass.getMethod("size", java.util.List::class.java).invoke(o, listOf("a", "b")))
            assertEquals(3, o.javaClass.getMethod("length", java.lang.StringBuilder::class.java).invoke(o, java.lang.StringBuilder("abc")))
            assertEquals("NEW", o.javaClass.getMethod("name", Thread.State::class.java).invoke(o, Thread.State.NEW))
        }
    }

    // ---- interop ----

    // `list.remove(Object)` on a `List<Integer>` with an Integer value: the element, not the index, is removed.
    @Test
    fun removeByObjectOnAListOfIntegers() {
        val integer = TypeDef.of(Integer::class.java)
        val list = TypeDef.parameterized(java.util.List::class.java, Integer::class.java)
        val def = ClassDef.builder("test.R45")
            .addMethod(method("call", list, integer).returns(TypeDef.Primitive.BOOLEAN).build { _, p ->
                p[0].invoke(java.util.List::class.java.getMethod("remove", Any::class.java), p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val values = arrayListOf(5, 0, 7)
            val method = o.javaClass.methods.first { it.name == "call" }
            assertEquals(true, method.invoke(o, values, 0))
            assertEquals(listOf(5, 7), values)
        }
    }

    // A Java method overloaded on `int`, `Integer` and `Object`: the model's overload is the one called.
    @Disabled("An overload taking a boxed Integer is selected as the Int one: the cast pinning it is written as Int")
    @Test
    fun javaMethodOverloadedOnPrimitiveBoxedAndObject() {
        val integer = TypeDef.of(Integer::class.java)
        val kindObject = MethodDef.builder("kind").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("v", TypeDef.OBJECT).returns(TypeDef.STRING).build()
        val kindInt = MethodDef.builder("kind").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("v", TypeDef.Primitive.INT).returns(TypeDef.STRING).build()
        val kindBoxed = MethodDef.builder("kind").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("v", integer).returns(TypeDef.STRING).build()
        val def = ClassDef.builder("test.R46")
            .addMethod(method("asObject", integer).returns(TypeDef.STRING).build { _, p -> FIXTURES.invokeStatic(kindObject, p[0]).returning() })
            .addMethod(method("asInt", integer).returns(TypeDef.STRING).build { _, p -> FIXTURES.invokeStatic(kindInt, p[0].cast(TypeDef.Primitive.INT)).returning() })
            .addMethod(method("asBoxed", TypeDef.Primitive.INT).returns(TypeDef.STRING).build { _, p -> FIXTURES.invokeStatic(kindBoxed, p[0].cast(integer)).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("object", o.javaClass.methods.first { it.name == "asObject" }.invoke(o, 1))
            assertEquals("int", o.javaClass.methods.first { it.name == "asInt" }.invoke(o, 1))
            assertEquals("boxed", o.javaClass.getMethod("asBoxed", INT).invoke(o, 1))
        }
    }

    // `Map.put` and `Map.get` on a `Map` typed value: the mutator through the mutable type, the lookup nullable.
    @Test
    fun mapPutAndGet() {
        val map = TypeDef.parameterized(java.util.Map::class.java, String::class.java, String::class.java)
        val put = java.util.Map::class.java.getMethod("put", Any::class.java, Any::class.java)
        val get = java.util.Map::class.java.getMethod("get", Any::class.java)
        val def = ClassDef.builder("test.R47")
            .addMethod(method("call", map).returns(TypeDef.STRING.makeNullable()).build { _, p -> StatementDef.multi(
                p[0].invoke(put, ExpressionDef.constant("k"), ExpressionDef.constant("v")),
                p[0].invoke(get, ExpressionDef.constant("k")).cast(TypeDef.STRING.makeNullable()).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            val values = HashMap<String, String>()
            assertEquals("v", o.javaClass.getMethod("call", java.util.Map::class.java).invoke(o, values))
            assertEquals(mapOf("k" to "v"), values)
        }
    }

    // An `Integer.valueOf` result compared referentially with another: two boxes of 1000 are not the same object.
    @Disabled("Locals of a boxed type are typed Int, so === compares values rather than references")
    @Test
    fun referentialEqualityOfBoxedValues() {
        val valueOf = Integer::class.java.getMethod("valueOf", INT)
        val integer = ClassTypeDef.of(Integer::class.java)
        val def = ClassDef.builder("test.R48")
            .addMethod(method("same", TypeDef.Primitive.INT).returns(TypeDef.Primitive.BOOLEAN).build { _, p ->
                integer.invokeStatic(valueOf, p[0]).newLocal("a") { a -> integer.invokeStatic(valueOf, p[0]).newLocal("b") { b ->
                    a.equalsReferentially(b).returning() } } })
            .addMethod(method("equal", TypeDef.Primitive.INT).returns(TypeDef.Primitive.BOOLEAN).build { _, p ->
                integer.invokeStatic(valueOf, p[0]).newLocal("a") { a -> integer.invokeStatic(valueOf, p[0]).newLocal("b") { b ->
                    a.equalsStructurally(b).returning() } } })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(true, o.javaClass.getMethod("same", INT).invoke(o, 1))
            assertEquals(false, o.javaClass.getMethod("same", INT).invoke(o, 1000))
            assertEquals(true, o.javaClass.getMethod("equal", INT).invoke(o, 1000))
        }
    }

    // A parameterized generated interface implemented by a generated class from another file, called through the interface.
    @Test
    fun generatedGenericInterfaceImplementedInAnotherFile() {
        val t = TypeDef.variable("T")
        val convert = MethodDef.builder("convert").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", t).returns(TypeDef.STRING).build()
        val converter = InterfaceDef.builder("test.R49Converter").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addMethod(convert).build()
        val def = ClassDef.builder("test.R49").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(converter.asTypeDef(), TypeDef.of(Integer::class.java)))
            .addMethod(MethodDef.builder("convert").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build { _, p -> ExpressionDef.constant("#").stringConcat(p[0]).returning() })
            .build()
        compile(converter, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("#3", loader.loadClass(converter.name).getMethod("convert", Any::class.java).invoke(o, 3))
        }
    }

    // A String-valued `when` expression result and a `String.valueOf` concatenation of a `char` and `null`.
    @Test
    fun concatenationOfCharNullAndNumbers() {
        val def = ClassDef.builder("test.R50")
            .addMethod(method("call", TypeDef.Primitive.CHAR, TypeDef.OBJECT.makeNullable(), TypeDef.Primitive.DOUBLE).returns(TypeDef.STRING).build { _, p ->
                p[0].stringConcat(p[1]).stringConcat(p[2]).stringConcat(ExpressionDef.constant(1L)).stringConcat(ExpressionDef.constant(true)).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals("cnull1.51true", o.javaClass.getMethod("call", CHAR, Any::class.java, DOUBLE).invoke(o, 'c', null, 1.5))
        }
    }

    // A blank String field returned before anything assigns it, by a method that may return null: the bytecode reads null.
    @Disabled("A blank field read before it is assigned is lateinit, which throws where the bytecode reads null")
    @Test
    fun unassignedFieldReadBeforeAssignment() {
        val field = FieldDef.builder("text", TypeDef.STRING).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.R51").addField(field)
            .addMethod(method("set", TypeDef.STRING).returns(TypeDef.VOID).build { self, p -> self.field(field).put(p[0]) })
            .addMethod(method("get").returns(TypeDef.STRING.makeNullable()).build { self, _ -> self.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertNull(o.javaClass.getMethod("get").invoke(o))
            o.javaClass.getMethod("set", String::class.java).invoke(o, "x")
            assertEquals("x", o.javaClass.getMethod("get").invoke(o))
        }
    }

    // `value instanceof List` on an Object, then the cast to `List<String>` and its size.
    @Test
    fun instanceOfAParameterizedType() {
        val list = TypeDef.parameterized(java.util.List::class.java, String::class.java)
        val def = ClassDef.builder("test.R53")
            .addMethod(method("call", TypeDef.OBJECT).returns(TypeDef.Primitive.INT).build { _, p -> StatementDef.multi(
                p[0].instanceOf(ClassTypeDef.of(java.util.List::class.java)).doIf(p[0].cast(list).invoke(java.util.List::class.java.getMethod("size")).returning()),
                ExpressionDef.constant(-1).returning()) })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(2, o.javaClass.getMethod("call", Any::class.java).invoke(o, listOf("a", "b")))
            assertEquals(-1, o.javaClass.getMethod("call", Any::class.java).invoke(o, "text"))
        }
    }

    // A call through a receiver whose type argument is a wildcard: `List<? extends CharSequence>.get(0).length()`.
    @Test
    fun callThroughAWildcardReceiver() {
        val list = TypeDef.parameterized(ClassTypeDef.of(java.util.List::class.java), TypeDef.wildcardSubtypeOf(ClassTypeDef.of(CharSequence::class.java)))
        val def = ClassDef.builder("test.R54")
            .addMethod(method("call", list).returns(TypeDef.Primitive.INT).build { _, p ->
                p[0].invoke(java.util.List::class.java.getMethod("get", INT), ExpressionDef.constant(0)).cast(ClassTypeDef.of(CharSequence::class.java))
                    .invoke(CharSequence::class.java.getMethod("length")).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(3, o.javaClass.getMethod("call", java.util.List::class.java).invoke(o, listOf(java.lang.StringBuilder("abc"))))
        }
    }

    // A lambda typed by a generated functional interface, created and called in another file.
    @Test
    fun lambdaOfAGeneratedFunctionalInterface() {
        val apply = MethodDef.builder("apply").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", TypeDef.Primitive.INT).returns(TypeDef.Primitive.INT).build()
        val op = InterfaceDef.builder("test.R55Op").addModifiers(Modifier.PUBLIC).addAnnotation(FunctionalInterface::class.java).addMethod(apply).build()
        val def = ClassDef.builder("test.R55")
            .addMethod(method("call", TypeDef.Primitive.INT).returns(TypeDef.Primitive.INT).build { _, p ->
                op.asTypeDef().getLambda().implement(listOf("v")) { _, q -> q[0].math(OpType.MULTIPLICATION, ExpressionDef.constant(3)).returning() }
                    .newLocal("triple") { triple -> triple.invoke(apply, p[0]).returning() } })
            .build()
        compile(op, def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(12, o.javaClass.getMethod("call", INT).invoke(o, 4))
        }
    }

    /** A compiled open parent. */
    open class Base {
        open fun greet(): String = "base"
    }

    /** Compiled fixtures. */
    class Fixtures {
        companion object {
            @JvmStatic fun describe(value: String?): String = value ?: "null"
            @JvmStatic fun kind(v: Any): String = "object"
            @JvmStatic fun kind(v: Int): String = "int"
            @JvmStatic fun kind(v: Int?): String = "boxed"
        }
    }
}
