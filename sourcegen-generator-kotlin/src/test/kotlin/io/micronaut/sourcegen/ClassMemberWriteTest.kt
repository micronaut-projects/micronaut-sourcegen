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
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import java.util.function.Consumer
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Modifiers, visibility, inheritance and field access of the members of generated classes: open classes and methods,
 * protected members, properties with accessors of their own, fields read through the type declaring them. Every
 * program is compiled and run.
 */
class ClassMemberWriteTest {

    @Test
    fun volatileTransientAndSynchronizedModifiersAreWritten() {
        val counter = FieldDef.builder("counter", TypeDef.Primitive.INT).addModifiers(Modifier.PRIVATE, Modifier.VOLATILE).build()
        val cache = FieldDef.builder("cache", TypeDef.OBJECT.makeNullable()).addModifiers(Modifier.PRIVATE, Modifier.TRANSIENT).build()
        val def = ClassDef.builder("test.ConcurrencyModifiers").addModifiers(Modifier.PUBLIC).addField(counter).addField(cache)
            .addMethod(MethodDef.builder("next").addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED).returns(TypeDef.Primitive.INT)
                .build { self, _ -> StatementDef.multi(
                    self.field(counter).put(self.field(counter).math(OpType.ADDITION, ExpressionDef.constant(1))),
                    self.field(counter).returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertTrue(java.lang.reflect.Modifier.isVolatile(cls.getDeclaredField("counter").modifiers))
            assertTrue(java.lang.reflect.Modifier.isTransient(cls.getDeclaredField("cache").modifiers))
            assertTrue(java.lang.reflect.Modifier.isSynchronized(cls.getMethod("next").modifiers))
        }
    }

    @Test
    fun nonFinalClassCanBeExtendedAndItsMethodOverridden() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("parent").returning() }
        val parent = ClassDef.builder("test.OpenParent").addModifiers(Modifier.PUBLIC).addMethod(describe).build()
        val child = ClassDef.builder("test.OpenChild").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().returns(String::class.java)
                .build { _, _ -> ExpressionDef.constant("child").returning() })
            .build()
        compile(parent, child).use { loader ->
            val instance = loader.loadClass(child.name).getConstructor().newInstance()
            assertEquals("child", loader.loadClass(parent.name).getMethod("describe").invoke(instance))
        }
    }

    @Test
    fun concreteMethodOfAnAbstractClassCanBeOverridden() {
        val describe = MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("parent").returning() }
        val parent = ClassDef.builder("test.AbstractParent").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addMethod(describe).build()
        val child = ClassDef.builder("test.AbstractChild").addModifiers(Modifier.PUBLIC).superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC).overrides().returns(String::class.java)
                .build { _, _ -> ExpressionDef.constant("child").returning() })
            .build()
        compile(parent, child).use { loader ->
            val instance = loader.loadClass(child.name).getConstructor().newInstance()
            assertEquals("child", loader.loadClass(parent.name).getMethod("describe").invoke(instance))
        }
    }

    @Test
    fun protectedMemberIsAccessibleFromTheSamePackage() {
        val helper = MethodDef.builder("helper").addModifiers(Modifier.PROTECTED).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("helped").returning() }
        val owner = ClassDef.builder("test.ProtectedOwner").addModifiers(Modifier.PUBLIC).addMethod(helper).build()
        val caller = ClassDef.builder("test.ProtectedCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("owner", owner.asTypeDef()).returns(String::class.java)
                .build { _, p -> p[0].invoke(helper).returning() })
            .build()
        compile(owner, caller).use { loader ->
            val cls = loader.loadClass(caller.name)
            val target = loader.loadClass(owner.name).getConstructor().newInstance()
            assertEquals("helped", cls.getMethod("call", target.javaClass).invoke(cls.getConstructor().newInstance(), target))
        }
    }

    @Test
    fun publicFieldWithAGetterMethodOfItsName() {
        val name = FieldDef.builder("name", String::class.java).addModifiers(Modifier.PUBLIC).build()
        val def = ClassDef.builder("test.FieldAndGetter").addModifiers(Modifier.PUBLIC).addField(name).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("getName").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> self.field(name).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("text", cls.getMethod("getName").invoke(cls.getConstructor(String::class.java).newInstance("text")))
        }
    }

    @Test
    fun classWithAPropertyAndAnAllFieldsConstructor() {
        val def = ClassDef.builder("test.PropertyConstructor").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String::class.java).build())
            .addAllFieldsConstructor(Modifier.PUBLIC)
            .build()
        compile(def).use { loader ->
            loader.loadClass(def.name).getConstructor(String::class.java).newInstance("text")
        }
    }

    /**
     * `TypeDef.SUPER` as a type is written as the model's own marker class, `io.micronaut.sourcegen.model.SuperType`.
     */
    @Test
    fun superTypeIsWrittenAsTheSuperclass() {
        val consumer = TypeDef.parameterized(Consumer::class.java, TypeDef.wildcardSupertypeOf(TypeDef.STRING))
        val accept = Consumer::class.java.getMethod("accept", Any::class.java)
        val def = ClassDef.builder("test.TypeKinds").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Holder::class.java))
            .addMethod(MethodDef.builder("feed").addModifiers(Modifier.PUBLIC).addParameter("consumer", consumer)
                .addParameter("value", TypeDef.STRING).returns(TypeDef.SUPER)
                .build { self, p -> StatementDef.multi(p[0].invoke(accept, p[1]), self.returning()) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val consumed = ArrayList<Any>()
            assertEquals(instance, cls.getMethod("feed", Consumer::class.java, String::class.java).invoke(instance, Consumer<Any> { consumed.add(it) }, "text"))
            assertEquals(listOf<Any>("text"), consumed)
        }
    }

    /**
     * A field declared by another type than the instance has is read without the cast to its declaring type.
     */
    @Test
    fun fieldOfAnotherTypeIsReadThroughItsDeclaringType() {
        val def = ClassDef.builder("test.ForeignField").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("label").addModifiers(Modifier.PUBLIC).addParameter("other", Any::class.java).returns(String::class.java)
                .build { _, p -> VariableDef.Field(p[0], ClassTypeDef.of(Holder::class.java), "label", TypeDef.STRING).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("held", cls.getMethod("label", Any::class.java).invoke(cls.getConstructor().newInstance(), Holder()))
        }
    }

    /**
     * A static method is rendered without its definition, so reading a field of an instance it is given fails
     * with "Field 'this' is not available".
     */
    @Test
    fun staticMethodReadsFieldOfAnInstance() {
        val int: Class<*> = Int::class.javaPrimitiveType!!
        val weight = FieldDef.builder("weight", int).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val type = ClassTypeDef.of("test.StaticFieldReader")
        val def = ClassDef.builder(type.name).addModifiers(Modifier.PUBLIC).addField(weight).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("weightOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", type).returns(int)
                .build { _, p -> p[0].field(weight).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val companion = cls.getField("Companion").get(null)
            assertEquals(7, companion.javaClass.getMethod("weightOf", cls).invoke(companion, cls.getConstructor(int).newInstance(7)))
        }
    }

    open class Holder {
        @JvmField
        var label: String = "held"
    }
}
