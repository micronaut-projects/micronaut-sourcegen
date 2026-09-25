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
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Raw and parameterized types where Kotlin has no raw types and invariant type arguments: in signatures, casts,
 * instance checks, array creation and instantiation. Every program is compiled and run.
 */
class GenericTypeUseWriteTest {

    // A `Supplier<Integer>` passed where the model's method takes `Supplier<Number>`: not converted, kotlinc rejects it. The Java generator converts through the raw type (typeArgumentsAreInvariant).
    @Test
    fun invariantTypeArguments() {
        val total = MethodDef.builder("total").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.parameterized(Supplier::class.java, Number::class.java)).returns(TypeDef.Primitive.INT).build()
        val def = ClassDef.builder("test.B25")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.parameterized(Supplier::class.java, Integer::class.java)).returns(TypeDef.Primitive.INT)
                .build { _, p -> ClassTypeDef.of(CompiledFixtures::class.java).invokeStatic(total, p[0]).returning() })
            .build()
        compile(def).use { loader ->
            val o = newInstance(loader, def)
            assertEquals(4, o.javaClass.getMethod("call", Supplier::class.java).invoke(o, Supplier { 4 }))
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

    @Test
    fun rawGenericTypesInSignaturesAndCasts() {
        val list = ClassTypeDef.of(java.util.List::class.java)
        val def = ClassDef.builder("test.RawTypes").addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("last", ClassTypeDef.of(java.util.Map::class.java)).addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC).addParameter("values", list).returns(TypeDef.Primitive.INT)
                .build { _, p -> p[0].invoke(java.util.List::class.java.getMethod("size")).returning() })
            .addMethod(MethodDef.builder("countOf").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT).returns(TypeDef.Primitive.INT)
                .build { _, p -> p[0].cast(list).invoke(java.util.List::class.java.getMethod("size")).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(2, cls.getMethod("count", java.util.List::class.java).invoke(instance, listOf("a", "b")))
            assertEquals(1, cls.getMethod("countOf", Any::class.java).invoke(instance, listOf("a")))
        }
    }

    @Test
    fun newInstanceOfAGenericClassPassedAsAnObject() {
        val def = ClassDef.builder("test.RawInstantiation").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build { _, _ -> ClassTypeDef.of(java.util.ArrayList::class.java).instantiate().returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(ArrayList<Any>(), cls.getMethod("create").invoke(cls.getConstructor().newInstance()))
        }
    }

    @Test
    fun instanceOfAParameterizedType() {
        val def = ClassDef.builder("test.GenericInstanceOf").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("isList").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build { _, p -> p[0].instanceOf(TypeDef.parameterized(java.util.List::class.java, String::class.java)).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(true, cls.getMethod("isList", Any::class.java).invoke(cls.getConstructor().newInstance(), listOf("a")))
        }
    }

    @Test
    fun instanceOfAGeneratedGenericClass() {
        val box = ClassDef.builder("test.Box").addModifiers(Modifier.PUBLIC).addTypeVariable(TypeDef.variable("T")).build()
        val def = ClassDef.builder("test.BoxCheck").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("isBox").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build { _, p -> p[0].instanceOf(box.asTypeDef()).returning() })
            .build()
        compile(box, def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(false, cls.getMethod("isBox", Any::class.java).invoke(cls.getConstructor().newInstance(), "a"))
        }
    }

    @Test
    fun rawGenericParameterCompiles() {
        val result = runMethod("test.RawComparable", TypeDef.Primitive.BOOLEAN, listOf(TypeDef.of(Comparable::class.java)), "x") { _, p ->
            p[0].isNull.returning()
        }
        assertEquals(false, result)
    }
}
