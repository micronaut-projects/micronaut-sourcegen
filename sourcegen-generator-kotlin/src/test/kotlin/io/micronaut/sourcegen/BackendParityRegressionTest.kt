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
import io.micronaut.sourcegen.KotlinCompileAssertions.render
import io.micronaut.sourcegen.KotlinCompileAssertions.runMethod
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import java.util.function.Supplier
import javax.lang.model.element.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Models the Java source generator and both bytecode writers accept, whose Kotlin source did not compile or behaved
 * differently. Every program is compiled, and run where it has a behavior to compare.
 */
class BackendParityRegressionTest {

    private val byteType = ClassTypeDef.of(java.lang.Byte::class.java)
    private val characterType = ClassTypeDef.of(Character::class.java)

    /** A lambda captures the value a local has where it is created, as the bytecode passes it. */
    @Test
    fun localReassignedAfterALambdaCapturesIt() {
        val supplier = TypeDef.parameterized(Supplier::class.java, String::class.java)
        assertEquals("first", runMethod("test.CapturedValue", TypeDef.STRING, listOf()) { _, _ ->
            ExpressionDef.constant("first").newLocal("text") { text ->
                supplier.lambda.implement { _, _ -> text.returning() }.newLocal("supplier") { s ->
                    StatementDef.multi(
                        (text as VariableDef.Local).assign(ExpressionDef.constant("second")),
                        s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning()
                    )
                }
            }
        })
    }

    /** `!p as Boolean` would negate `p` before the cast. */
    @Test
    fun negatedCastIsParenthesized() {
        assertEquals(false, runMethod("test.NegatedCast", TypeDef.Primitive.BOOLEAN, listOf(TypeDef.OBJECT), true) { _, p ->
            p[0].cast(TypeDef.Primitive.BOOLEAN).isFalse.returning()
        })
    }

    /** The keys of a switch on a wrapper are of its primitive: `1.toByte()`. */
    @Test
    fun statementSwitchOnABoxedSelectorWidensTheKeys() {
        assertEquals("one", runMethod("test.BoxedSwitchStatement", TypeDef.STRING, listOf(byteType), 1.toByte()) { _, p ->
            StatementDef.multi(
                p[0].asStatementSwitch(byteType, linkedMapOf(
                    ExpressionDef.constant(1) to ExpressionDef.constant("one").returning(),
                    ExpressionDef.constant(2) to ExpressionDef.constant("two").returning()
                ), null),
                ExpressionDef.constant("other").returning()
            )
        })
    }

    @Test
    fun expressionSwitchOnABoxedSelectorWidensTheKeys() {
        assertEquals("a", runMethod("test.BoxedSwitchExpression", TypeDef.STRING, listOf(characterType), 'a') { _, p ->
            p[0].asExpressionSwitch(TypeDef.STRING, linkedMapOf(
                ExpressionDef.constant(97) to ExpressionDef.constant("a")
            ), ExpressionDef.constant("other")).returning()
        })
    }

    /** A local holding a conditional one of whose values is `null` is nullable. */
    @Test
    fun conditionalWithANullBranchIsStoredInANullableLocal() {
        assertNull(runMethod("test.NullBranchLocal", TypeDef.STRING, listOf(TypeDef.Primitive.BOOLEAN), false) { _, p ->
            ExpressionDef.IfElse(p[0].isTrue, ExpressionDef.constant("a"), ExpressionDef.nullValue(), TypeDef.STRING)
                .newLocal("s") { s -> s.returning() }
        })
    }

    /** A function returning a switch expression one of whose values is `null` returns a nullable value. */
    @Test
    fun switchExpressionWithANullCaseIsNullable() {
        assertNull(runMethod("test.NullCaseSwitch", TypeDef.STRING, listOf(TypeDef.Primitive.INT), 1) { _, p ->
            p[0].asExpressionSwitch(TypeDef.STRING, mapOf(ExpressionDef.constant(1) to ExpressionDef.nullValue()),
                ExpressionDef.constant("a")).returning()
        })
    }

    /** An index of a byte, a short or a char is widened to the Int Kotlin takes. */
    @Test
    fun narrowArrayIndexIsWidened() {
        val array = TypeDef.Primitive.INT.array()
        assertEquals(20, runMethod("test.ByteIndex", TypeDef.Primitive.INT, listOf(array, TypeDef.Primitive.BYTE),
            intArrayOf(10, 20), 1.toByte()) { _, p ->
            ExpressionDef.ArrayElement(p[0], TypeDef.Primitive.INT, p[1]).returning()
        })
        assertEquals(20, runMethod("test.CharIndex", TypeDef.Primitive.INT, listOf(array, TypeDef.Primitive.CHAR),
            intArrayOf(10, 20), 1.toChar()) { _, p ->
            ExpressionDef.ArrayElement(p[0], TypeDef.Primitive.INT, p[1]).returning()
        })
    }

    /** The private primary constructor of a nested class the outer one calls is exposed, with its annotations. */
    @Test
    fun privatePrimaryConstructorOfANestedClassIsExposed() {
        val deprecated = AnnotationDef.builder(ClassTypeDef.of(java.lang.Deprecated::class.java)).build()
        val holder = ClassDef.builder("Holder").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PRIVATE).addAnnotation(deprecated)
                .build { aThis, _ -> aThis.superRef().invokeSuperConstructor() })
            .build()
        val holderType = ClassTypeDef.of("test.OuterA\$Holder", true)
        val outer = ClassDef.builder("test.OuterA").addModifiers(Modifier.PUBLIC).addInnerType(holder)
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build { _, _ -> holderType.instantiate().returning() })
            .build()
        assertTrue(render(outer).contains("Deprecated"), render(outer))
        compile(outer).use { loader ->
            val cls = loader.loadClass(outer.name)
            assertEquals("test.OuterA\$Holder", cls.getMethod("create").invoke(cls.getConstructor().newInstance()).javaClass.name)
        }
    }

    /** The annotations of a parameter are written. */
    @Test
    fun parameterAnnotationsAreWritten() {
        val deprecated = AnnotationDef.builder(ClassTypeDef.of(java.lang.Deprecated::class.java)).build()
        val def = ClassDef.builder("test.AnnotatedParameter").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .addParameter(io.micronaut.sourcegen.model.ParameterDef.builder("value", TypeDef.STRING).addAnnotation(deprecated).build())
                .build { _, p -> p[0].returning() })
            .build()
        val source = render(def)
        assertTrue(source.contains("@Deprecated `value`: String"), source)
        compile(def).use { }
    }

    /** A field a constructor and a setter assign, with accessor methods, is no lateinit var whose accessors clash. */
    @Test
    fun reassignedFieldWithAccessorMethods() {
        val field = FieldDef.builder("name", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build()
        val def = ClassDef.builder("test.NamedBean").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("name", TypeDef.STRING)
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .addMethod(MethodDef.builder("getName").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build { aThis, _ -> aThis.field(field).returning() })
            .addMethod(MethodDef.builder("setName").addModifiers(Modifier.PUBLIC).addParameter("name", TypeDef.STRING)
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .build()
        compile(def).use { loader ->
            val o = loader.loadClass(def.name).getConstructor(String::class.java).newInstance("a")
            o.javaClass.getMethod("setName", String::class.java).invoke(o, "b")
            assertEquals("b", o.javaClass.getMethod("getName").invoke(o))
        }
    }

    /** A wrapper field every constructor assigns holds `null` where it is assigned it, as the JVM's Integer does. */
    @Test
    fun boxedFieldAssignedByTheConstructorHoldsNull() {
        val integer = ClassTypeDef.of(Integer::class.java)
        val field = FieldDef.builder("count", integer).addModifiers(Modifier.PRIVATE).build()
        val def = ClassDef.builder("test.BoxedBean").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("count", integer)
                .build { aThis, p -> aThis.field(field).put(p[0]) })
            .addMethod(MethodDef.builder("getCount").addModifiers(Modifier.PUBLIC).returns(integer)
                .build { aThis, _ -> aThis.field(field).returning() })
            .build()
        compile(def).use { loader ->
            val type = loader.loadClass(def.name)
            assertEquals(Integer::class.java, type.getDeclaredField("count").type)
            val o = type.getConstructor(Integer::class.java).newInstance(null)
            assertNull(o.javaClass.getMethod("getCount").invoke(o))
        }
    }

    /** A raw generic supertype has the arguments of Java's raw type, which a star projection is not allowed as. */
    @Test
    fun rawGenericSupertypes() {
        val def = ClassDef.builder("test.RawSuper").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(ClassTypeDef.of(java.util.ArrayList::class.java))
            .addSuperinterface(ClassTypeDef.of(Supplier::class.java))
            .build()
        compile(def).use { loader ->
            assertEquals(java.util.ArrayList::class.java, loader.loadClass(def.name).superclass)
        }
    }

    /** The bridge `String.compareTo(Object)` is not the signature read: `Comparable<T>.compareTo(T)` of String is. */
    @Test
    fun bridgeSignatureOfConcreteReceiver() {
        val compareTo = Comparable::class.java.getMethod("compareTo", Any::class.java)
        assertEquals(0, runMethod("test.BridgeReceiver", TypeDef.Primitive.INT, listOf(TypeDef.STRING, TypeDef.OBJECT), "a", "a") { _, p ->
            p[0].invoke(compareTo, p[1]).returning()
        })
    }
}
