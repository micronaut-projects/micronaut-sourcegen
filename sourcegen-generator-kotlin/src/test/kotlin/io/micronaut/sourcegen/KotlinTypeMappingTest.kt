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
import java.util.AbstractList
import java.util.AbstractMap
import java.util.function.Function
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Java types Kotlin maps to its own - `kotlin.String`, `kotlin.Number`, `kotlin.CharSequence`, the read-only
 * collections, `Map.Entry`, `Throwable` - and Java members it hides or turns into properties: the generated source
 * calls and overrides the Java members the model names. Every program is compiled and run.
 */
class KotlinTypeMappingTest {

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

    @Test
    fun numberSubclassOverridesItsValueMethods() {
        fun value(name: String, type: TypeDef.Primitive, result: ExpressionDef) = MethodDef.builder(name).addModifiers(Modifier.PUBLIC)
            .overrides().returns(type).build { _, _ -> result.returning() }
        val def = ClassDef.builder("test.FixedNumber").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Number::class.java))
            .addMethod(value("intValue", TypeDef.Primitive.INT, ExpressionDef.constant(1)))
            .addMethod(value("longValue", TypeDef.Primitive.LONG, ExpressionDef.constant(1L)))
            .addMethod(value("floatValue", TypeDef.Primitive.FLOAT, ExpressionDef.constant(1f)))
            .addMethod(value("doubleValue", TypeDef.Primitive.DOUBLE, ExpressionDef.constant(1.0)))
            .build()
        compile(def).use { loader ->
            val number = loader.loadClass(def.name).getConstructor().newInstance() as Number
            assertEquals(1, number.toInt())
            assertEquals(1L, number.toLong())
        }
    }

    @Test
    fun charSequenceImplementationOverridesCharAt() {
        val def = ClassDef.builder("test.FixedText").addModifiers(Modifier.PUBLIC).addSuperinterface(ClassTypeDef.of(CharSequence::class.java))
            .addMethod(MethodDef.builder("length").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.Primitive.INT)
                .build { _, _ -> ExpressionDef.constant(1).returning() })
            .addMethod(MethodDef.builder("charAt").addModifiers(Modifier.PUBLIC).overrides().addParameter("index", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.CHAR).build { _, _ -> ExpressionDef.constant('x').returning() })
            .addMethod(MethodDef.builder("subSequence").addModifiers(Modifier.PUBLIC).overrides().addParameter("start", TypeDef.Primitive.INT)
                .addParameter("end", TypeDef.Primitive.INT).returns(CharSequence::class.java).build { _, _ -> ExpressionDef.constant("x").returning() })
            .build()
        compile(def).use { loader ->
            val text = loader.loadClass(def.name).getConstructor().newInstance() as CharSequence
            assertEquals('x', text[0])
        }
    }

    @Test
    fun iteratorImplementationOverridesRemove() {
        val def = ClassDef.builder("test.EmptyIterator").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(java.util.Iterator::class.java, String::class.java))
            .addMethod(MethodDef.builder("hasNext").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.Primitive.BOOLEAN)
                .build { _, _ -> ExpressionDef.constant(false).returning() })
            .addMethod(MethodDef.builder("next").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ClassTypeDef.of(NoSuchElementException::class.java).instantiate().doThrow() })
            .addMethod(MethodDef.builder("remove").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.VOID)
                .build { _, _ -> ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow() })
            .build()
        compile(def).use { loader ->
            val iterator = loader.loadClass(def.name).getConstructor().newInstance() as MutableIterator<*>
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { iterator.remove() }
        }
    }

    @Test
    fun abstractMapSubclassOverridesEntrySet() {
        val entries = TypeDef.parameterized(java.util.Set::class.java,
            TypeDef.parameterized(java.util.Map.Entry::class.java, String::class.java, String::class.java))
        val def = ClassDef.builder("test.EmptyMap").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(AbstractMap::class.java, String::class.java, String::class.java))
            .addMethod(MethodDef.builder("entrySet").addModifiers(Modifier.PUBLIC).overrides().returns(entries)
                .build { _, _ -> ClassTypeDef.of(java.util.Collections::class.java)
                    .invokeStatic(java.util.Collections::class.java.getMethod("emptySet")).returning() })
            .build()
        compile(def).use { loader ->
            val map = loader.loadClass(def.name).getConstructor().newInstance() as Map<*, *>
            assertEquals(0, map.size)
        }
    }

    @Test
    fun javaStringMethodsAreCalled() {
        fun method(name: String, vararg parameters: Class<*>) = String::class.java.getMethod(name, *parameters)
        val def = ClassDef.builder("test.StringMethods").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java).returns(TypeDef.Primitive.CHAR)
                .build { _, p -> p[0].invoke(method("charAt", Int::class.javaPrimitiveType!!), ExpressionDef.constant(0)).returning() })
            .addMethod(MethodDef.builder("bytes").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java).returns(ByteArray::class.java)
                .build { _, p -> p[0].invoke(method("getBytes")).returning() })
            .addMethod(MethodDef.builder("same").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java).returns(TypeDef.Primitive.BOOLEAN)
                .build { _, p -> p[0].invoke(method("equalsIgnoreCase", String::class.java), ExpressionDef.constant("TEXT")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals('t', cls.getMethod("first", String::class.java).invoke(instance, "text"))
            assertArrayEquals("text".toByteArray(), cls.getMethod("bytes", String::class.java).invoke(instance, "text") as ByteArray)
            assertEquals(true, cls.getMethod("same", String::class.java).invoke(instance, "text"))
        }
    }

    @Test
    fun javaStringSplitReturnsTheRegexArray() {
        val split = String::class.java.getMethod("split", String::class.java)
        val def = ClassDef.builder("test.StringSplit").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("parts").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java)
                .returns(TypeDef.STRING.array())
                .build { _, p -> p[0].invoke(split, ExpressionDef.constant("\\.")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertArrayEquals(arrayOf("a", "b"), cls.getMethod("parts", String::class.java).invoke(cls.getConstructor().newInstance(), "a.b") as Array<*>)
        }
    }

    @Test
    fun javaStringTrimRemovesControlCharacters() {
        val trim = String::class.java.getMethod("trim")
        val def = ClassDef.builder("test.StringTrim").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("trimmed").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java)
                .returns(String::class.java).build { _, p -> p[0].invoke(trim).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            // String.trim() removes every character up to U+0020; Kotlin's trim() only whitespace
            assertEquals("x", cls.getMethod("trimmed", String::class.java).invoke(cls.getConstructor().newInstance(), "\u0001x\u0001"))
        }
    }

    @Test
    fun unboxingMethodsAreCalled() {
        val def = ClassDef.builder("test.Unboxing").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("integer").addModifiers(Modifier.PUBLIC).addParameter("value", Integer::class.java).returns(TypeDef.Primitive.INT)
                .build { _, p -> p[0].invoke(Integer::class.java.getMethod("intValue")).returning() })
            .addMethod(MethodDef.builder("number").addModifiers(Modifier.PUBLIC).addParameter("value", Number::class.java).returns(TypeDef.Primitive.LONG)
                .build { _, p -> p[0].invoke(Number::class.java.getMethod("longValue")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(3, cls.getMethod("integer", Integer::class.java).invoke(instance, 3))
            assertEquals(3L, cls.getMethod("number", Number::class.java).invoke(instance, 3))
        }
    }

    @Test
    fun objectMethodsKotlinDoesNotDeclareAreCalled() {
        val lock = FieldDef.builder("lock", TypeDef.OBJECT).addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(ClassTypeDef.of(Any::class.java).instantiate()).build()
        val def = ClassDef.builder("test.ObjectMethods").addModifiers(Modifier.PUBLIC).addField(lock)
            .addMethod(MethodDef.builder("type").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.parameterized(ClassTypeDef.of(Class::class.java), TypeDef.wildcard()))
                .build { _, p -> p[0].invoke(Any::class.java.getMethod("getClass")).returning() })
            .addMethod(MethodDef.builder("wake").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID)
                .build { self, _ -> StatementDef.Synchronized(self.field(lock), self.field(lock).invoke(Any::class.java.getMethod("notifyAll"))) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(String::class.java, cls.getMethod("type", Any::class.java).invoke(instance, "text"))
            cls.getMethod("wake").invoke(instance)
        }
    }

    @Test
    fun staticFieldsOfMappedJavaTypesAreRead() {
        val def = ClassDef.builder("test.MappedStatics").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("yes").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build { _, _ -> ClassTypeDef.of(java.lang.Boolean::class.java).getStaticField("TRUE", ClassTypeDef.of(java.lang.Boolean::class.java)).returning() })
            .addMethod(MethodDef.builder("bits").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build { _, _ -> ClassTypeDef.of(Integer::class.java).getStaticField("SIZE", TypeDef.Primitive.INT).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(true, cls.getMethod("yes").invoke(instance))
            assertEquals(32, cls.getMethod("bits").invoke(instance))
        }
    }

    @Test
    fun collectionAndEntryMembersKotlinHides() {
        val entry = TypeDef.parameterized(java.util.Map.Entry::class.java, String::class.java, String::class.java)
        val def = ClassDef.builder("test.HiddenMembers").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("array").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.parameterized(java.util.List::class.java, String::class.java)).returns(TypeDef.OBJECT.array())
                .build { _, p -> p[0].invoke(java.util.Collection::class.java.getMethod("toArray")).returning() })
            .addMethod(MethodDef.builder("replace").addModifiers(Modifier.PUBLIC).addParameter("entry", entry).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].invoke(java.util.Map.Entry::class.java.getMethod("setValue", Any::class.java), ExpressionDef.constant("new")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertArrayEquals(arrayOf<Any>("a"), cls.getMethod("array", java.util.List::class.java).invoke(instance, listOf("a")) as Array<*>)
            val mapEntry = java.util.AbstractMap.SimpleEntry("k", "old")
            assertEquals("old", cls.getMethod("replace", java.util.Map.Entry::class.java).invoke(instance, mapEntry))
            assertEquals("new", mapEntry.value)
        }
    }

    @Test
    fun exceptionAndEntryOverridesOfMappedProperties() {
        val exception = ClassDef.builder("test.FixedException").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(RuntimeException::class.java))
            .addMethod(MethodDef.builder("getMessage").addModifiers(Modifier.PUBLIC).overrides().returns(String::class.java)
                .build { _, _ -> ExpressionDef.constant("fixed").returning() })
            .build()
        val entry = ClassDef.builder("test.FixedEntry").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(java.util.Map.Entry::class.java, String::class.java, String::class.java))
            .addMethod(MethodDef.builder("getKey").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.constant("k").returning() })
            .addMethod(MethodDef.builder("getValue").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
                .build { _, _ -> ExpressionDef.constant("v").returning() })
            .addMethod(MethodDef.builder("setValue").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.OBJECT).build { _, _ -> ExpressionDef.constant("v").returning() })
            .build()
        compile(exception, entry).use { loader ->
            assertEquals("fixed", (loader.loadClass(exception.name).getConstructor().newInstance() as Throwable).message)
            assertEquals("k", (loader.loadClass(entry.name).getConstructor().newInstance() as Map.Entry<*, *>).key)
        }
    }

    @Test
    fun exceptionMessageInvokedByNameCompiles() {
        val result = runMethod("test.CatchMessage", TypeDef.STRING, listOf()) { _, _ ->
            StatementDef.doTry(ClassTypeDef.of(IllegalStateException::class.java).instantiate(ExpressionDef.constant("boom")).doThrow())
                .doCatch(IllegalStateException::class.java) { e -> e.invoke("getMessage", TypeDef.STRING).returning() }
        }
        assertEquals("boom", result)
    }

    @Test
    fun getClassOfABoxedValueIsTheWrapper() {
        val result = runMethod("test.BoxedClass", TypeDef.STRING, listOf(TypeDef.of(Integer::class.java)), 5) { _, p ->
            p[0].invokeGetClass().invoke("getName", TypeDef.STRING).returning()
        }
        assertEquals("java.lang.Integer", result)
    }

    @Test
    fun javaStringMethodInvokedByNameCompiles() {
        val result = runMethod("test.UpperCase", TypeDef.STRING, listOf(TypeDef.STRING), "abc") { _, p ->
            p[0].invoke("toUpperCase", TypeDef.STRING).returning()
        }
        assertEquals("ABC", result)
    }

    @Test
    fun callToMappedPropertyOnThisCompiles() {
        val size = MethodDef.builder("size").addModifiers(Modifier.PUBLIC).overrides().returns(Int::class.javaPrimitiveType!!)
            .build { _, _ -> ExpressionDef.constant(0).returning() }
        val def = ClassDef.builder("test.SizedList").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(AbstractList::class.java, String::class.java))
            .addMethod(size)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Int::class.javaPrimitiveType!!)
                .build { self, _ -> self.invoke(size).returning() }).build()
        KotlinCompileAssertions.assertCompiles(render(def))
    }

    @Test
    fun listRemoveByIndexPreservesDescriptor() {
        val remove = java.util.List::class.java.getMethod("remove", Int::class.javaPrimitiveType)
        val def = ClassDef.builder("test.RemoveIndex").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.parameterized(java.util.List::class.java, Int::class.javaObjectType))
                .returns(Any::class.java).build { _, p -> p[0].invoke(remove, ExpressionDef.constant(1)).returning() }).build()
        KotlinCompileAssertions.compileAndLoad(render(def)).use { loader ->
            val cls = loader.loadClass(def.name)
            val values = arrayListOf(10, 20)
            assertEquals(20, cls.getMethod("call", java.util.List::class.java).invoke(cls.getConstructor().newInstance(), values))
            assertEquals(listOf(10), values)
        }
    }

    @Test
    fun mappedPropertyOverrideThroughGeneratedSuperclassCompiles() {
        val parent = ClassDef.builder("test.GeneratedListBase").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(java.util.AbstractList::class.java, String::class.java)).build()
        val child = ClassDef.builder("test.GeneratedListChild").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("size").addModifiers(Modifier.PUBLIC).overrides().returns(Int::class.javaPrimitiveType!!)
                .build { _, _ -> ExpressionDef.constant(0).returning() }).build()
        KotlinCompileAssertions.assertCompiles(render(parent), render(child))
    }
}
