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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.Element
import io.micronaut.inject.ast.PropertyElement
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.inject.writer.GeneratedFile
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.AnnotationObjectDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.EnumDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.ParameterDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.io.Writer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.Optional
import java.util.function.BiFunction
import javax.lang.model.element.Modifier
import kotlin.reflect.KClass

/**
 * DSL programs for the lines of the Kotlin generator that no other test reaches - found by line coverage - rendered,
 * compiled and run, with the generator's own error paths asserted where the model can express them.
 *
 * @since 2.2.2
 */
class CoverageShapeProgramTest {

    private val INT = TypeDef.Primitive.INT
    private val intType: Class<*> = Int::class.javaPrimitiveType!!
    private val booleanType: Class<*> = Boolean::class.javaPrimitiveType!!
    private val marker = AnnotationDef.builder(ClassTypeDef.of(Marker::class.java)).build()

    // KotlinPoetSourceGenerator 61: the language of the generator
    @Test
    fun languageIsKotlin() {
        assertEquals(VisitorContext.Language.KOTLIN, KotlinPoetSourceGenerator().language)
    }

    // KotlinPoetSourceGenerator 65-76, 3096, 3100: written through a visitor context - restored after a nested write -
    // which is asked for a type only known by name when a value has to satisfy the bound of a generic method
    @Test
    fun contextIsKeptThroughNestedWritesAndAskedForNamedTypes() {
        val t = TypeDef.variable("T", TypeDef.parameterized(java.util.function.Supplier::class.java, TypeDef.STRING))
        val firstOf = MethodDef.builder("firstOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("a", t).addParameter("b", t).returns(t)
            .build { _, p -> p[0].returning() }
        // Named as the compiler would name it: only the context could say what it inherits
        val named = ClassTypeDef.of(Ranked::class.java.name, true)
        val def = ClassDef.builder("test.ContextUser").addModifiers(Modifier.PUBLIC).addMethod(firstOf)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("a", named).addParameter("b", named).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of("test.ContextUser").invokeStatic(firstOf, p[0], p[1]).returning() })
            .build()
        val other = ClassDef.builder("test.ContextOther").addModifiers(Modifier.PUBLIC).build()
        val writers = HashMap<String, StringWriter>()
        val element = stub(Element::class.java) { _, _ -> null }
        lateinit var context: VisitorContext
        context = stub(VisitorContext::class.java) { name, args ->
            when (name) {
                "visitGeneratedSourceFile" -> {
                    val simpleName = args[1] as String
                    val writer = writers.getOrPut(simpleName) { StringWriter() }
                    Optional.of(stub(GeneratedFile::class.java) { fileMethod, fileArgs ->
                        when (fileMethod) {
                            "openWriter" -> writer
                            "write" -> {
                                if (simpleName == "ContextUser") {
                                    // A nested write, which restores the context of the enclosing one
                                    KotlinPoetSourceGenerator().write(other, context, element)
                                }
                                @Suppress("UNCHECKED_CAST")
                                (fileArgs[0] as GeneratedFile.ThrowingConsumer<Writer>).accept(writer)
                                null
                            }
                            else -> null
                        }
                    })
                }
                else -> null
            }
        }
        KotlinPoetSourceGenerator().write(def, context, element)
        val source = writers.getValue("ContextUser").toString()
        assertTrue(source.contains("firstOf(a as"), source)
        assertTrue(writers.getValue("ContextOther").toString().contains("class ContextOther"))
        KotlinCompileAssertions.compileAndLoad(source, writers.getValue("ContextOther").toString()).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(Ranked(4), cls.getMethod("pick", Ranked::class.java, Ranked::class.java).invoke(cls.getConstructor().newInstance(), Ranked(4), Ranked(9)))
        }
    }

    // KotlinPoetSourceGenerator 2925-2931: a value cast to the `Comparable` bound of a generated generic method names
    // `kotlin.Comparable`, where the generated bound is written as `java.lang.Comparable`, which Kotlin tells apart
    @Test
    fun valueCastToTheComparableBoundOfAGeneratedMethod() {
        val t = TypeDef.variable("T", TypeDef.parameterized(Comparable::class.java, TypeDef.variable("T")))
        val bigger = MethodDef.builder("bigger").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("a", t).addParameter("b", t).returns(t)
            .build { _, p ->
                p[0].invoke("compareTo", listOf<TypeDef>(TypeDef.OBJECT), INT, listOf(p[1]))
                    .compare(OpType.GREATER_THAN, ExpressionDef.constant(0)).doIfElse(p[0], p[1]).returning()
            }
        val def = ClassDef.builder("test.Comparing").addModifiers(Modifier.PUBLIC).addMethod(bigger)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addParameter("a", Ranked::class.java).addParameter("b", Ranked::class.java).returns(TypeDef.OBJECT)
                .build { _, p -> ClassTypeDef.of("test.Comparing").invokeStatic(bigger, p[0], p[1]).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(Ranked(9), cls.getMethod("pick", Ranked::class.java, Ranked::class.java).invoke(cls.getConstructor().newInstance(), Ranked(4), Ranked(9)))
        }
    }

    // KotlinPoetSourceGenerator 129, 143-147, 540-545, 840-841, 3472, 3501: annotations of an annotation member, a method
    // and a field, a static field of an annotation type, an annotation type nested in a class, and annotation
    // values that are a class and a constant
    @Test
    fun annotationsAndAnnotationTypesAreWritten() {
        val typed = AnnotationDef.builder(ClassTypeDef.of(Typed::class.java)).addMember("type", String::class).build()
        val named = AnnotationDef.builder(ClassTypeDef.of(Named::class.java))
            .addMember("value", ClassTypeDef.of(Names::class.java).getStaticField("FIRST", TypeDef.STRING)).build()
        val tagged = AnnotationObjectDef.builder("Tagged").addModifiers(Modifier.PUBLIC).addAnnotation(marker)
            .addMember(AnnotationObjectDef.AnnotationMemberDef.builder("value", TypeDef.STRING).addAnnotation(marker)
                .withDefault(ExpressionDef.constant("tag")).build())
            .addField(FieldDef.builder("LIMIT", intType).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(ExpressionDef.constant(3)).build())
            .build()
        val holder = ClassDef.builder("test.TagHolder").addModifiers(Modifier.PUBLIC).addAnnotation(typed).addAnnotation(named)
            .addInnerType(tagged)
            .addField(FieldDef.builder("count", intType).addModifiers(Modifier.PUBLIC).addAnnotation(marker).initializer(ExpressionDef.constant(1)).build())
            .addMethod(MethodDef.builder("twice").addModifiers(Modifier.PUBLIC).addAnnotation(marker).addParameter("value", intType).returns(intType)
                .build { _, p -> p[0].math(MathBinaryOperation.OpType.MULTIPLICATION, ExpressionDef.constant(2)).returning() })
            .build()
        compile(holder).use { loader ->
            val cls = loader.loadClass(holder.name)
            assertEquals(String::class, cls.getAnnotation(Typed::class.java).type)
            assertEquals(Names.FIRST, cls.getAnnotation(Named::class.java).value)
            // Kotlin keeps the annotations of a property on a synthetic method of its own
            assertNotNull(cls.declaredMethods.first { it.name.endsWith("\$annotations") }.getAnnotation(Marker::class.java))
            assertNotNull(cls.getMethod("twice", intType).getAnnotation(Marker::class.java))
            assertEquals(6, cls.getMethod("twice", intType).invoke(cls.getConstructor().newInstance(), 3))
            val annotationType = loader.loadClass("test.TagHolder\$Tagged")
            assertTrue(annotationType.isAnnotation)
            assertNotNull(annotationType.getAnnotation(Marker::class.java))
            assertEquals("tag", annotationType.getMethod("value").defaultValue)
            val companion = annotationType.getField("Companion").get(null)
            assertEquals(3, companion.javaClass.getMethod("getLIMIT").invoke(companion))
        }
    }

    // KotlinPoetSourceGenerator 180, 187-190: a functional interface is a `fun interface`, and an interface extends another
    @Test
    fun functionalInterfaceExtendingAnotherIsImplemented() {
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("name", String::class.java).returns(String::class.java).build()
        val greeter = InterfaceDef.builder("test.Greeter").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(FunctionalInterface::class.java)).build())
            .addSuperinterface(ClassTypeDef.of(java.io.Serializable::class.java)).addMethod(greet).build()
        val impl = ClassDef.builder("test.LoudGreeter").addModifiers(Modifier.PUBLIC).addSuperinterface(greeter.asTypeDef())
            .addMethod(MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).overrides().addParameter("name", String::class.java).returns(String::class.java)
                .build { _, p -> ExpressionDef.constant("hello ").stringConcat(p[0]).returning() })
            .build()
        compile(greeter, impl).use { loader ->
            val cls = loader.loadClass(impl.name)
            val instance = cls.getConstructor().newInstance()
            assertTrue(instance is java.io.Serializable)
            assertEquals("hello you", loader.loadClass(greeter.name).getMethod("greet", String::class.java).invoke(instance, "you"))
        }
    }

    // KotlinPoetSourceGenerator 197-218: a property of an interface, nullable or not, is written with an initializer,
    // which an interface property cannot have
    @Test
    fun interfacePropertiesAreWritten() {
        val labelled = InterfaceDef.builder("test.LabelledThing").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("label").ofType(String::class.java).build())
            .addProperty(PropertyDef.builder("note").ofType(TypeDef.STRING.makeNullable()).build())
            .build()
        compile(labelled).use { loader ->
            val cls = loader.loadClass(labelled.name)
            assertTrue(java.lang.reflect.Modifier.isAbstract(cls.getMethod("getLabel").modifiers))
            assertTrue(java.lang.reflect.Modifier.isAbstract(cls.getMethod("setNote", String::class.java).modifiers))
        }
    }

    // KotlinPoetSourceGenerator 278-279, 343-346: a static initializer of a class without other static members, and a
    // primary constructor delegating to super through a constructor call
    @Test
    fun staticInitializerAndSuperConstructorCallRun() {
        val setProperty = System::class.java.getMethod("setProperty", String::class.java, String::class.java)
        val def = ClassDef.builder("test.Initialized").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled::class.java))
            .addStaticInitializer(ClassTypeDef.of(System::class.java).invokeStatic(setProperty, ExpressionDef.constant("coverage.initialized"), ExpressionDef.constant("yes")))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("label", String::class.java)
                .build { self, p -> self.superRef().invokeConstructor(listOf<TypeDef>(TypeDef.STRING), p[0]) })
            .build()
        compile(def).use { loader ->
            System.clearProperty("coverage.initialized")
            val cls = Class.forName(def.name, true, loader)
            assertEquals("yes", System.getProperty("coverage.initialized"))
            assertEquals("text", (cls.getConstructor(String::class.java).newInstance("text") as Labelled).label)
        }
    }

    // KotlinPoetSourceGenerator 563-570, 698-699, 1186: a nullable property, an annotated one and a protected method
    @Test
    fun propertiesAndProtectedMethodsAreWritten() {
        val hidden = MethodDef.builder("hidden").addModifiers(Modifier.PROTECTED).returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("hidden").returning() }
        val def = ClassDef.builder("test.Figure").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String::class.java).addModifiers(Modifier.PUBLIC).addAnnotation(marker).build())
            .addProperty(PropertyDef.builder("note").ofType(TypeDef.STRING.makeNullable()).addModifiers(Modifier.PUBLIC).build())
            .addMethod(hidden)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { self, _ -> self.invoke(hidden).returning() })
            .build()
        compile(def).use { loader ->
            val figure = loader.loadClass(def.name)
            assertTrue(java.lang.reflect.Modifier.isProtected(figure.getDeclaredMethod("hidden").modifiers))
            val instance = figure.getConstructor(String::class.java).newInstance("circle")
            assertEquals("circle", figure.getMethod("getName").invoke(instance))
            assertEquals(null, figure.getMethod("getNote").invoke(instance))
            figure.getMethod("setNote", String::class.java).invoke(instance, "noted")
            assertEquals("noted", figure.getMethod("getNote").invoke(instance))
            assertEquals("hidden", figure.getMethod("call").invoke(instance))
        }
    }

    // KotlinPoetSourceGenerator 1189: a sealed class, extended by a generated subclass
    @Test
    fun sealedClassIsExtended() {
        val kinds = MethodDef.builder("kinds").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(intType)
            .build { _, _ -> ExpressionDef.constant(2).returning() }
        val def = ClassDef.builder("test.Shape").addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addField(FieldDef.builder("name", String::class.java).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant("shape")).build())
            .addMethod(kinds)
            .build()
        val circle = ClassDef.builder("test.Circle").addModifiers(Modifier.PUBLIC).superclass(def.asTypeDef()).build()
        compile(def, circle).use { loader ->
            val shape = loader.loadClass(def.name)
            assertTrue(java.lang.reflect.Modifier.isAbstract(shape.modifiers))
            val instance = loader.loadClass(circle.name).getConstructor().newInstance()
            assertEquals("shape", shape.getMethod("getName").invoke(instance))
            val companion = shape.getField("Companion").get(null)
            assertEquals(2, companion.javaClass.getMethod("kinds").invoke(companion))
        }
    }

    // KotlinPoetSourceGenerator 587-598: the primary constructor of a sealed class with properties is written public,
    // which a sealed class cannot have
    @Test
    fun sealedClassWithPropertiesIsConstructed() {
        val def = ClassDef.builder("test.NamedShape").addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addProperty(PropertyDef.builder("name").ofType(String::class.java).addModifiers(Modifier.PUBLIC).build())
            .build()
        val circle = ClassDef.builder("test.NamedCircle").addModifiers(Modifier.PUBLIC).superclass(def.asTypeDef())
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { self, _ -> self.superRef().invokeConstructor(listOf<TypeDef>(TypeDef.STRING), ExpressionDef.constant("circle")) })
            .build()
        compile(def, circle).use { loader ->
            val instance = loader.loadClass(circle.name).getConstructor().newInstance()
            assertEquals("circle", loader.loadClass(def.name).getMethod("getName").invoke(instance))
        }
    }

    // KotlinPoetSourceGenerator 1193, 1320, 1409, 1430, 2456, 2466: a modifier Kotlin has none for, and a primitive the
    // generator does not know as a type, an array and a conversion
    @Test
    fun unsupportedModifiersAndPrimitivesAreRejected() {
        val odd = TypeDef.Primitive(String::class.java)
        assertRejected("Not supported modifier") {
            render(ClassDef.builder("test.Volatile").addField(FieldDef.builder("value", intType).addModifiers(Modifier.VOLATILE).build()).build())
        }
        assertRejected("Unrecognized primitive name") {
            render(classWithBody("OddReturn", odd, ExpressionDef.constant("x").returning()))
        }
        assertRejected("Unrecognized primitive name") {
            render(ClassDef.builder("test.OddArray").addMethod(MethodDef.builder("call").addParameter("values", TypeDef.array(odd)).build()).build())
        }
        assertRejected("Unrecognized primitive name") {
            render(classWithBody("OddArrayValue", TypeDef.VOID, StatementDef.Throw(TypeDef.array(odd).instantiate(ExpressionDef.constant(1)))))
        }
        assertRejected("Unrecognized primitive name") {
            render(classWithBody("OddConversion", TypeDef.VOID, StatementDef.Throw(ExpressionDef.constant(1).cast(odd))))
        }
    }

    // KotlinPoetSourceGenerator 959-967, 1036: an erased override returning a platform value from within every kind of
    // statement is resolved to a nullable result, and a generated supertype cannot be loaded to map its members
    @Test
    fun platformValuesReturnedFromStatementsMakeTheResultNullable() {
        val t = TypeDef.variable("T")
        val source = InterfaceDef.builder("test.Source").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("kind", intType).returns(t).build())
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(intType).build())
            .build()
        val items = FieldDef.builder("items", TypeDef.parameterized(java.util.List::class.java, TypeDef.OBJECT)).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val get = java.util.List::class.java.getMethod("get", intType)
        val def = ClassDef.builder("test.Reader").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(source.asTypeDef(), TypeDef.STRING))
            .addField(items).addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).overrides().addParameter("kind", intType).returns(TypeDef.OBJECT)
                .build { self, p ->
                    val kind = p[0]
                    val platform = self.field(items).invoke(get, ExpressionDef.constant(0)).returning()
                    StatementDef.multi(
                        ExpressionDef.constant(0).newLocal("unused"),
                        kind.compare(OpType.EQUAL_TO, ExpressionDef.constant(1)).doIf(ExpressionDef.constant("one").returning()),
                        kind.compare(OpType.EQUAL_TO, ExpressionDef.constant(2)).doIfElse(ExpressionDef.constant("two").returning(), StatementDef.multi()),
                        kind.compare(OpType.EQUAL_TO, ExpressionDef.constant(3)).whileLoop(ExpressionDef.constant("three").returning()),
                        StatementDef.Synchronized(self, kind.compare(OpType.EQUAL_TO, ExpressionDef.constant(4)).doIf(ExpressionDef.constant("four").returning())),
                        kind.asStatementSwitch(INT, mapOf(ExpressionDef.constant(5) to ExpressionDef.constant("five").returning())),
                        StatementDef.doTry(StatementDef.multi(kind.compare(OpType.EQUAL_TO, ExpressionDef.constant(6)).doIf(ExpressionDef.constant("six").returning())))
                            .doCatch(RuntimeException::class.java) { ExpressionDef.constant("caught").returning() },
                        platform
                    )
                })
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC).overrides().returns(intType)
                .build { self, _ -> self.field(items).invoke("size", INT).returning() })
            .build()
        compile(source, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor(java.util.List::class.java).newInstance(listOf("seven"))
            val read = cls.getMethod("read", intType)
            assertEquals(listOf("one", "two", "three", "four", "five", "six", "seven"), (1..7).map { read.invoke(instance, it) })
            assertEquals(1, cls.getMethod("count").invoke(instance))
        }
    }

    // KotlinPoetSourceGenerator 1112, 1134, 1351: a record property already final, a class named as inner without a
    // `$`, and an undeclared type variable of an enum method
    @Test
    fun recordsPlainInnerNamesAndEnumVariablesAreWritten() {
        val plain = ClassDef.builder("test.Plain").addModifiers(Modifier.PUBLIC).build()
        val record = RecordDef.builder("test.Pair").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("left").ofType(intType).addModifiers(Modifier.PUBLIC, Modifier.FINAL).build())
            .addProperty(PropertyDef.builder("other").ofType(ClassTypeDef.of("test.Plain", true)).addModifiers(Modifier.PUBLIC).build())
            .build()
        val echoing = EnumDef.builder("test.Echoing").addModifiers(Modifier.PUBLIC).addEnumConstant("ONE")
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.variable("T")).returns(TypeDef.OBJECT)
                .build { _, p -> p[0].returning() })
            .build()
        compile(plain, record, echoing).use { loader ->
            val plainInstance = loader.loadClass(plain.name).getConstructor().newInstance()
            val pair = loader.loadClass(record.name).getConstructor(intType, loader.loadClass(plain.name)).newInstance(3, plainInstance)
            assertEquals(3, pair.javaClass.getMethod("getLeft").invoke(pair))
            assertSame(plainInstance, pair.javaClass.getMethod("getOther").invoke(pair))
            val enumClass = loader.loadClass(echoing.name)
            assertEquals("v", enumClass.getMethod("echo", Any::class.java).invoke(enumClass.getField("ONE").get(null), "v"))
        }
    }

    // KotlinPoetSourceGenerator 1236-1238, 1242-1243, 1273-1276: annotated class and primitive types
    @Test
    fun annotatedTypesAreWritten() {
        val mark = AnnotationDef.builder(ClassTypeDef.of(Mark::class.java)).build()
        val def = ClassDef.builder("test.MarkedTypes").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("same").addModifiers(Modifier.PUBLIC)
                .addParameter("text", TypeDef.STRING.annotated(mark)).addParameter("value", INT.annotated(mark)).returns(String::class.java)
                .build { _, p -> p[0].stringConcat(p[1]).returning() })
            .build()
        val source = render(def)
        assertTrue(source.contains("text: @CoverageShapeProgramTest.Mark KotlinString") && source.contains("`value`: @CoverageShapeProgramTest.Mark Int"), source)
        KotlinCompileAssertions.compileAndLoad(source).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("a1", cls.getMethod("same", String::class.java, intType).invoke(cls.getConstructor().newInstance(), "a", 1))
        }
    }

    // KotlinPoetSourceGenerator 1260, 1730, 2242, 2592, 2611, 2625, 2669, 2682, 3687: `this` as a class constant outside
    // an instance scope, a null statement and expression, constants no source spells, an unknown parameter, a field of
    // a record, and an exception variable outside a catch
    @Test
    fun modelsNoSourceCanSpellAreRejected() {
        val length = MethodDef.builder("length").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("text", CharSequence::class.java).returns(intType).build()
        assertRejected("This type is used outside") {
            render(classWithBody("ThisClass", TypeDef.CLASS, ExpressionDef.Constant(TypeDef.CLASS, TypeDef.THIS).returning()))
        }
        assertRejected("Unrecognized statement") {
            render(classWithBody("NullStatement", TypeDef.VOID, StatementDef.If(ExpressionDef.constant(true).isTrue, nothing())))
        }
        assertRejected("Unrecognized expression") {
            render(classWithBody("NullExpression", INT, StatementDef.Return(nothing())))
        }
        assertRejected("Unrecognized expression") {
            render(classWithBody("VariableConstant", TypeDef.OBJECT, ExpressionDef.Constant(TypeDef.variable("T"), 1).returning()))
        }
        assertRejected("Expected a character constant") {
            render(classWithBody("NotAChar", TypeDef.Primitive.CHAR, ExpressionDef.Constant(TypeDef.Primitive.CHAR, "x").returning()))
        }
        assertRejected("Expected a floating point constant") {
            render(classWithBody("NotAFloat", TypeDef.Primitive.FLOAT, ExpressionDef.Constant(TypeDef.Primitive.FLOAT, "x").returning()))
        }
        // Passed as an argument, the parameter is looked up among the enclosing functions before it is written
        assertRejected("doesn't have parameter") {
            render(classWithBody("UnknownParameter", INT, ClassTypeDef.of(Helpers::class.java).invokeStatic(length, VariableDef.MethodParameter("nope", TypeDef.STRING)).returning()))
        }
        val pair = ClassTypeDef.of("test.Pair")
        assertRejected("Field access not supported") {
            render(RecordDef.builder(pair.name).addProperty(PropertyDef.builder("left").ofType(intType).build())
                .addMethod(MethodDef.builder("read").returns(intType).build { self, _ -> VariableDef.Field(self, pair, "left", INT).returning() }).build())
        }
        assertRejected("only available in a catch block") {
            render(classWithBody("LooseException", ClassTypeDef.of(Exception::class.java), VariableDef.ExceptionVar(ClassTypeDef.of(Exception::class.java)).returning()))
        }
    }

    private fun assertRejected(message: String, rendering: () -> String) {
        val error = assertThrows(IllegalStateException::class.java) { rendering() }
        assertTrue(error.message!!.contains(message), error.message)
    }

    // KotlinPoetSourceGenerator 1761, 2700, 3416: a nullable value stored, read through and returned where the model
    // expects a value, with `!!`
    @Test
    fun nullableValuesAreAssertedWhereValuesAreExpected() {
        val labelled = ClassTypeDef.of(Labelled::class.java)
        val def = ClassDef.builder("test.Asserting").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("stored").addModifiers(Modifier.PUBLIC).addParameter("text", TypeDef.STRING.makeNullable()).returns(String::class.java)
                .build { _, p ->
                    val local = VariableDef.Local("copy", TypeDef.STRING)
                    StatementDef.multi(local.defineAndAssign(p[0]), local.returning())
                })
            .addMethod(MethodDef.builder("returned").addModifiers(Modifier.PUBLIC).addParameter("text", TypeDef.STRING.makeNullable()).returns(String::class.java)
                .build { _, p -> p[0].returning() })
            .addMethod(MethodDef.builder("labelOf").addModifiers(Modifier.PUBLIC).addParameter("holder", labelled.makeNullable()).returns(String::class.java)
                .build { _, p -> VariableDef.Field(p[0], labelled.makeNullable(), "label", TypeDef.STRING).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("a", cls.getMethod("stored", String::class.java).invoke(instance, "a"))
            assertEquals("b", cls.getMethod("returned", String::class.java).invoke(instance, "b"))
            assertEquals("held", cls.getMethod("labelOf", Labelled::class.java).invoke(instance, Labelled("held")))
            val error = assertThrows(InvocationTargetException::class.java) { cls.getMethod("returned", String::class.java).invoke(instance, null) }
            assertTrue(error.cause is NullPointerException, error.cause.toString())
        }
    }

    // KotlinPoetSourceGenerator 1620-1631, 1787-1788: a super constructor call that is not the first statement of the
    // constructor - a field of the class is assigned before it, which the verifier allows - is written as a
    // statement, which Kotlin has no place for
    @Test
    fun superConstructorCallAfterAFieldAssignment() {
        val count = FieldDef.builder("count", intType).addModifiers(Modifier.PUBLIC).build()
        val late = ClassDef.builder("test.SuperLate").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled::class.java)).addField(count)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { self, _ -> StatementDef.multi(self.field(count).put(ExpressionDef.constant(1)),
                    self.superRef().invokeSuperConstructor(listOf<TypeDef>(TypeDef.STRING, TypeDef.STRING), ExpressionDef.constant("la"), ExpressionDef.constant("te"))) })
            .build()
        val later = ClassDef.builder("test.SuperLater").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Labelled::class.java)).addField(count)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { self, _ -> StatementDef.multi(self.field(count).put(ExpressionDef.constant(2)),
                    self.superRef().invokeConstructor(listOf<TypeDef>(TypeDef.STRING), ExpressionDef.constant("later"))) })
            .build()
        compile(late, later).use { loader ->
            val one = loader.loadClass(late.name).getConstructor().newInstance() as Labelled
            assertEquals("late", one.label)
            assertEquals("late", one.label)
            assertEquals(1, one.javaClass.getMethod("getCount").invoke(one))
            val two = loader.loadClass(later.name).getConstructor().newInstance() as Labelled
            assertEquals("later", two.label)
        }
    }

    // KotlinPoetSourceGenerator 1875, 2003, 2086, 2093, 2175, 3179-3182, 3213, 3231: casts, class and hash reads of
    // conditionals, an `or` under `isTrue` in an `and`, a lambda of two parameters, a cast and a conditional with a
    // `null` result passed as arguments, and a lambda passing a captured parameter
    @Test
    fun composedExpressionsKeepTheirMeaning() {
        val biFunction = TypeDef.parameterized(BiFunction::class.java, Integer::class.java, Integer::class.java, Integer::class.java)
        val apply = BiFunction::class.java.getMethod("apply", Any::class.java, Any::class.java)
        val run = Runnable::class.java.getMethod("run")
        val add = java.util.List::class.java.getMethod("add", Any::class.java)
        val length = MethodDef.builder("length").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("text", CharSequence::class.java).returns(intType)
            .build { _, p -> p[0].invoke("length", INT).returning() }
        val show = MethodDef.builder("show").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("text", TypeDef.STRING.makeNullable()).returns(String::class.java)
            .build { _, p -> ExpressionDef.constant("<").stringConcat(p[0]).stringConcat(ExpressionDef.constant(">")).returning() }
        val owner = ClassTypeDef.of("test.Composed")
        val def = ClassDef.builder(owner.name).addModifiers(Modifier.PUBLIC).addMethod(length).addMethod(show)
            .addMethod(MethodDef.builder("chosen").addModifiers(Modifier.PUBLIC)
                .addParameter("first", booleanType).addParameter("a", String::class.java).addParameter("b", String::class.java).returns(Array<Any>::class.java)
                .build { _, p ->
                    val choice = p[0].isTrue.doIfElse(p[1], p[2])
                    TypeDef.OBJECT.array().instantiate(
                        choice.cast(CharSequence::class.java),
                        choice.invokeGetClass(),
                        choice.invokeHashCode(),
                        owner.invokeStatic(length, p[1].cast(CharSequence::class.java)),
                        owner.invokeStatic(length, p[2].cast(String::class.java)),
                        owner.invokeStatic(show, p[0].isTrue.doIfElse(p[1], ExpressionDef.nullValue()))
                    ).returning()
                })
            .addMethod(MethodDef.builder("either").addModifiers(Modifier.PUBLIC)
                .addParameter("a", booleanType).addParameter("b", booleanType).addParameter("c", booleanType).returns(booleanType)
                .build { _, p -> p[0].isTrue.or(p[1].isTrue).isTrue.and(p[2].isTrue).returning() })
            .addMethod(MethodDef.builder("sum").addModifiers(Modifier.PUBLIC).addParameter("a", intType).addParameter("b", intType).returns(intType)
                .build { _, p ->
                    biFunction.getLambda().implement(listOf("x", "y")) { _, lp ->
                        lp[0].cast(intType).math(MathBinaryOperation.OpType.ADDITION, lp[1].cast(intType)).returning()
                    }.newLocal("adder") { adder -> adder.invoke(apply, p[0], p[1]).returning() }
                })
            .addMethod(MethodDef.builder("captured").addModifiers(Modifier.PUBLIC).addParameter("text", String::class.java).returns(intType)
                .build { _, p ->
                    val seen = VariableDef.Local("seen", TypeDef.parameterized(java.util.List::class.java, TypeDef.OBJECT))
                    StatementDef.multi(
                        seen.defineAndAssign(ClassTypeDef.of(java.util.ArrayList::class.java).instantiate()),
                        ClassTypeDef.of(Runnable::class.java).getLambda().implement { _, _ ->
                            seen.invoke(add, p[0]).returning()
                        }.newLocal("adder") { adder -> StatementDef.multi(adder.invoke(run), seen.invoke("size", INT).returning()) })
                })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            val chosen = cls.getMethod("chosen", booleanType, String::class.java, String::class.java)
            assertArrayEquals(arrayOf<Any?>("a", String::class.java, "a".hashCode(), 1, 2, "<a>"), chosen.invoke(instance, true, "a", "bb") as Array<*>)
            assertArrayEquals(arrayOf<Any?>("bb", String::class.java, "bb".hashCode(), 1, 2, "<null>"), chosen.invoke(instance, false, "a", "bb") as Array<*>)
            val either = cls.getMethod("either", booleanType, booleanType, booleanType)
            assertEquals(true, either.invoke(instance, false, true, true))
            assertEquals(false, either.invoke(instance, true, true, false))
            assertEquals(7, cls.getMethod("sum", intType, intType).invoke(instance, 3, 4))
            assertEquals(1, cls.getMethod("captured", String::class.java).invoke(instance, "t"))
        }
    }

    // KotlinPoetSourceGenerator 2537-2541, 2586, 2589, 3531: constants of an enum named by a string, of a boxed integer, of
    // another class written as it is, and a quote character
    @Test
    fun constantsOfEveryKindAreWritten() {
        val def = ClassDef.builder("test.Constants").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("values").addModifiers(Modifier.PUBLIC).returns(Array<Any>::class.java)
                .build { _, _ ->
                    TypeDef.OBJECT.array().instantiate(
                        ExpressionDef.Constant(ClassTypeDef.of(java.lang.annotation.RetentionPolicy::class.java), "SOURCE"),
                        ExpressionDef.constant(java.lang.annotation.RetentionPolicy.RUNTIME),
                        ExpressionDef.Constant(ClassTypeDef.of(Integer::class.java), 5),
                        ExpressionDef.Constant(ClassTypeDef.of(java.util.List::class.java), "java.util.List.of<kotlin.String>()"),
                        ExpressionDef.constant('"')
                    ).returning()
                })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertArrayEquals(arrayOf<Any?>(java.lang.annotation.RetentionPolicy.SOURCE, java.lang.annotation.RetentionPolicy.RUNTIME, 5, java.util.List.of<String>(), '"'),
                cls.getMethod("values").invoke(cls.getConstructor().newInstance()) as Array<*>)
        }
    }

    // KotlinPoetSourceGenerator 2679-2680: a field of an enum read through the enum's type by name is checked against
    // its fields
    @Test
    fun enumFieldReadThroughItsTypeIsChecked() {
        val kind = ClassTypeDef.of("test.Kind")
        val weight = FieldDef.builder("weight", intType).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build()
        val def = EnumDef.builder(kind.name).addModifiers(Modifier.PUBLIC).addEnumConstant("LIGHT", ExpressionDef.constant(3))
            .addField(weight).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(intType)
                .build { self, _ -> VariableDef.Field(self, kind, "weight", INT).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals(3, cls.getMethod("read").invoke(cls.getField("LIGHT").get(null)))
        }
        assertThrows(IllegalStateException::class.java) {
            render(EnumDef.builder(kind.name).addEnumConstant("A")
                .addMethod(MethodDef.builder("read").returns(intType).build { self, _ -> VariableDef.Field(self, kind, "missing", INT).returning() }).build())
        }
    }

    // KotlinPoetSourceGenerator 3033-3035: an `Object` passed to a method of another generated class, known by name
    // only - not loadable, so not known to be a Kotlin class - is cast to the nullable parameter type a Java method
    // would take, which the Kotlin method does not
    @Test
    fun objectPassedToANamedGeneratedClass() {
        val greet = MethodDef.builder("greet").addModifiers(Modifier.PUBLIC).addParameter("name", String::class.java).returns(String::class.java)
            .build { _, p -> ExpressionDef.constant("hi ").stringConcat(p[0]).returning() }
        val other = ClassDef.builder("test.Other").addModifiers(Modifier.PUBLIC).addMethod(greet).build()
        val def = ClassDef.builder("test.OtherCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("other", ClassTypeDef.of("test.Other")).addParameter("name", TypeDef.OBJECT).returns(String::class.java)
                .build { _, p -> p[0].invoke(greet, p[1]).returning() })
            .build()
        compile(other, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val otherInstance = loader.loadClass(other.name).getConstructor().newInstance()
            assertEquals("hi you", cls.getMethod("call", loader.loadClass(other.name), Any::class.java).invoke(cls.getConstructor().newInstance(), otherInstance, "you"))
        }
    }

    // KotlinPoetSourceGenerator 2973, 3113, 3115, 3147: a conditional cast to every bound of the callee's variable, values
    // satisfying a bound through the model or as an array, and a reference adapting one of two arguments
    @Test
    fun calleeBoundsAndAdaptedReferences() {
        val both = TypeDef.variable("T", TypeDef.of(CharSequence::class.java), TypeDef.of(Runnable::class.java))
        val bothMethod = MethodDef.builder("both").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(both).addParameter("value", both).returns(both).build()
        val s = TypeDef.variable("S", TypeDef.of(java.io.Serializable::class.java))
        val keep = MethodDef.builder("keep").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(s).addParameter("value", s).returns(s).build()
        val helpers = ClassTypeDef.of(Helpers::class.java)
        val tag = ClassDef.builder("test.Tag").addModifiers(Modifier.PUBLIC).addSuperinterface(ClassTypeDef.of(java.io.Serializable::class.java)).build()
        val t = TypeDef.variable("T")
        // A generated interface a reference implements has to be a `fun interface` for Kotlin's SAM constructor
        val bothOf = InterfaceDef.builder("test.BothOf").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(FunctionalInterface::class.java)).build())
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("a", t).addParameter("b", String::class.java).returns(t).build())
            .build()
        val make = MethodDef.builder("make").addModifiers(Modifier.PUBLIC).overrides().addParameter("a", TypeDef.OBJECT).addParameter("b", String::class.java).returns(TypeDef.OBJECT)
            .build { _, p -> p[0].cast(String::class.java).stringConcat(p[1]).returning() }
        val maker = ClassDef.builder("test.Maker").addModifiers(Modifier.PUBLIC).addSuperinterface(TypeDef.parameterized(bothOf.asTypeDef(), TypeDef.STRING)).addMethod(make).build()
        val bothOfStrings = TypeDef.parameterized(bothOf.asTypeDef(), TypeDef.STRING)
        val def = ClassDef.builder("test.Bounded").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("chosen").addModifiers(Modifier.PUBLIC).addParameter("first", booleanType).returns(TypeDef.OBJECT)
                .build { _, p ->
                    val next: ExpressionDef = helpers.invokeStatic("next", TypeDef.OBJECT)
                    val last: ExpressionDef = helpers.invokeStatic("last", TypeDef.OBJECT)
                    helpers.invokeStatic(bothMethod, p[0].isTrue.doIfElse(next, last)).returning()
                })
            .addMethod(MethodDef.builder("kept").addModifiers(Modifier.PUBLIC).addParameter("tag", tag.asTypeDef()).returns(TypeDef.OBJECT)
                .build { _, p -> helpers.invokeStatic(keep, p[0]).returning() })
            .addMethod(MethodDef.builder("keptArray").addModifiers(Modifier.PUBLIC).addParameter("values", IntArray::class.java).returns(TypeDef.OBJECT)
                .build { _, p -> helpers.invokeStatic(keep, p[0]).returning() })
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("maker", maker.asTypeDef()).returns(bothOfStrings)
                .build { _, p -> bothOfStrings.methodReference(p[0], make).returning() })
            // KotlinPoetSourceGenerator 3041-3053: an `Object` passed to a compiled Kotlin function is cast to the
            // parameter type it declares, which does not take `null`
            .addMethod(MethodDef.builder("greeted").addModifiers(Modifier.PUBLIC).addParameter("name", TypeDef.OBJECT).returns(String::class.java)
                .build { _, p -> helpers.invokeStatic(Helpers::class.java.getMethod("greet", String::class.java), p[0]).returning() })
            .build()
        compile(tag, bothOf, maker, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("next", cls.getMethod("chosen", booleanType).invoke(instance, true).toString())
            assertEquals("last", cls.getMethod("chosen", booleanType).invoke(instance, false).toString())
            val tagInstance = loader.loadClass(tag.name).getConstructor().newInstance()
            assertSame(tagInstance, cls.getMethod("kept", loader.loadClass(tag.name)).invoke(instance, tagInstance))
            assertArrayEquals(intArrayOf(1, 2), cls.getMethod("keptArray", IntArray::class.java).invoke(instance, intArrayOf(1, 2)) as IntArray)
            val makerInstance = loader.loadClass(maker.name).getConstructor().newInstance()
            val reference = cls.getMethod("reference", loader.loadClass(maker.name)).invoke(instance, makerInstance)
            assertEquals("ab", loader.loadClass(bothOf.name).getMethod("make", Any::class.java, String::class.java).invoke(reference, "a", "b"))
            assertEquals("hi you", cls.getMethod("greeted", Any::class.java).invoke(instance, "you"))
        }
    }

    // KotlinPoetSourceGenerator 3199-3204: an element of an array an override narrowed has the narrowed component,
    // one and two dimensions deep, which the compiled method it is passed to takes
    @Test
    fun narrowedArrayElementsKeepTheirComponent() {
        val t = TypeDef.variable("T")
        val grid = InterfaceDef.builder("test.Grid").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("firstOfRow").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("cells", TypeDef.array(t, 2)).returns(intType).build())
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("items", TypeDef.array(t)).returns(intType).build())
            .build()
        val rows = Helpers::class.java.getMethod("rows", Array<String>::class.java)
        val size = Helpers::class.java.getMethod("size", String::class.java)
        val def = ClassDef.builder("test.StringGrid").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(grid.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("firstOfRow").addModifiers(Modifier.PUBLIC).overrides().addParameter("cells", Array<Array<Any>>::class.java).returns(intType)
                .build { _, p -> ClassTypeDef.of(Helpers::class.java).invokeStatic(rows, p[0].arrayElement(0)).returning() })
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC).overrides().addParameter("items", Array<Any>::class.java).returns(intType)
                .build { _, p -> ClassTypeDef.of(Helpers::class.java).invokeStatic(size, p[0].arrayElement(0)).returning() })
            .build()
        compile(grid, def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals(2, cls.getMethod("firstOfRow", Array<Array<String>>::class.java).invoke(instance, arrayOf(arrayOf("a", "b"), arrayOf("c"))))
            assertEquals(3, cls.getMethod("first", Array<String>::class.java).invoke(instance, arrayOf("abc", "d")))
        }
    }

    // KotlinPoetSourceGenerator 3335-3336, 3358: fields assigned in an `if` and a `while` of the constructor are not
    // assigned by it for certain, so they are declared with defaults
    @Test
    fun fieldsAssignedConditionallyByTheConstructorHaveDefaults() {
        val count = FieldDef.builder("count", intType).addModifiers(Modifier.PUBLIC).build()
        val steps = FieldDef.builder("steps", intType).addModifiers(Modifier.PUBLIC).build()
        val def = ClassDef.builder("test.Conditional").addModifiers(Modifier.PUBLIC).addField(count).addField(steps)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("flag", booleanType)
                .build { self, p -> StatementDef.multi(
                    p[0].isTrue.doIf(self.field(count).put(ExpressionDef.constant(1))),
                    self.field(steps).compare(OpType.LESS_THAN, ExpressionDef.constant(3))
                        .whileLoop(self.field(steps).put(self.field(steps).math(MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))))) })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val on = cls.getConstructor(booleanType).newInstance(true)
            assertEquals(1, cls.getMethod("getCount").invoke(on))
            assertEquals(3, cls.getMethod("getSteps").invoke(on))
            assertEquals(0, cls.getMethod("getCount").invoke(cls.getConstructor(booleanType).newInstance(false)))
        }
    }

    // KotlinPoetSourceGenerator 3648, 3685: nested catches each name their exception, and a lambda in a catch reads the
    // exception of the enclosing catch
    @Test
    fun nestedCatchesNameTheirExceptions() {
        val supplier = TypeDef.parameterized(java.util.function.Supplier::class.java, TypeDef.STRING)
        val get = java.util.function.Supplier::class.java.getMethod("get")
        val def = ClassDef.builder("test.NestedCatches").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String::class.java)
                .build { _, _ ->
                    StatementDef.doTry(ClassTypeDef.of(IllegalStateException::class.java).instantiate(ExpressionDef.constant("one")).doThrow())
                        .doCatch(IllegalStateException::class.java) { first ->
                            StatementDef.doTry(ClassTypeDef.of(IllegalArgumentException::class.java).instantiate(first.invoke("toString", TypeDef.STRING).stringConcat(ExpressionDef.constant(" two"))).doThrow())
                                .doCatch(IllegalArgumentException::class.java) { second ->
                                    StatementDef.doTry(ClassTypeDef.of(UnsupportedOperationException::class.java).instantiate(second.invoke("toString", TypeDef.STRING).stringConcat(ExpressionDef.constant(" three"))).doThrow())
                                        .doCatch(UnsupportedOperationException::class.java) { third ->
                                            supplier.getLambda().implement { _, _ -> third.invoke("toString", TypeDef.STRING).returning() }
                                                .newLocal("message") { message -> message.invoke(get).returning() }
                                        }
                                }
                        }
                })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("java.lang.UnsupportedOperationException: java.lang.IllegalArgumentException: java.lang.IllegalStateException: one two three",
                cls.getMethod("call").invoke(cls.getConstructor().newInstance()))
        }
    }

    // KotlinPoetSourceGenerator 1796-1800: `getMessage()` of a throwable is read as the `message` property Kotlin maps it
    // to, which is nullable where the model's `String` result is not
    @Test
    fun throwableMessageIsReadAsAProperty() {
        val getMessage = Throwable::class.java.getMethod("getMessage")
        val def = ClassDef.builder("test.Messages").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("messageOf").addModifiers(Modifier.PUBLIC).addParameter("error", Exception::class.java).returns(String::class.java)
                .build { _, p -> p[0].invoke(getMessage).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            assertEquals("failed", cls.getMethod("messageOf", Exception::class.java).invoke(cls.getConstructor().newInstance(), IllegalStateException("failed")))
        }
    }

    // KotlinPoetSourceGenerator 1826-1832: a property read as the compiler describes it
    @Test
    fun propertyValueIsReadThroughItsName() {
        val label = stub(PropertyElement::class.java) { name, _ ->
            when (name) {
                "getName" -> "label"
                "getType" -> ClassElement.of(String::class.java)
                else -> null
            }
        }
        val def = ClassDef.builder("test.PropertyReader").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("labelOf").addModifiers(Modifier.PUBLIC).addParameter("holder", Labelled::class.java).returns(String::class.java)
                .build { _, p -> p[0].getPropertyValue(label).returning() })
            .addMethod(MethodDef.builder("labelOfAny").addModifiers(Modifier.PUBLIC).addParameter("holder", Any::class.java).returns(String::class.java)
                .build { _, p -> p[0].cast(Labelled::class.java).getPropertyValue(label).returning() })
            .build()
        compile(def).use { loader ->
            val cls = loader.loadClass(def.name)
            val instance = cls.getConstructor().newInstance()
            assertEquals("held", cls.getMethod("labelOf", Labelled::class.java).invoke(instance, Labelled("held")))
            assertEquals("held", cls.getMethod("labelOfAny", Any::class.java).invoke(instance, Labelled("held")))
        }
    }

    private fun classWithBody(name: String, returnType: TypeDef, body: StatementDef): ClassDef =
        ClassDef.builder("test.$name").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(returnType).addStatement(body).build()).build()

    private fun render(definition: ObjectDef): String {
        val writer = StringWriter()
        KotlinPoetSourceGenerator().write(definition, writer)
        if (System.getenv("COVERAGE_PRINT") == "true") {
            println("$writer=====")
        }
        return writer.toString()
    }

    /** A `null` the model's records take, which Kotlin only passes as a platform value. */
    private fun <T> nothing(): T = Optional.empty<T>().orElse(null)

    private fun compile(vararg definitions: ObjectDef): URLClassLoader =
        KotlinCompileAssertions.compileAndLoad(*definitions.map { render(it) }.toTypedArray())

    /**
     * A stub of an interface the compiler provides: the handler answers what the test needs, the rest is empty.
     */
    private fun <T : Any> stub(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T {
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method.name, args ?: emptyArray()) ?: when {
                method.returnType == Optional::class.java -> Optional.empty<Any>()
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == String::class.java -> ""
                java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                else -> null
            }
        }
        return type.cast(proxy)
    }

    @Retention(AnnotationRetention.RUNTIME)
    annotation class Marker

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.TYPE)
    annotation class Mark

    @Retention(AnnotationRetention.RUNTIME)
    annotation class Named(val value: String)

    @Retention(AnnotationRetention.RUNTIME)
    annotation class Typed(val type: KClass<*>)

    object Names {
        const val FIRST = "first"
    }

    open class Labelled(@JvmField val label: String) {
        constructor(first: String, second: String) : this(first + second)
    }

    data class Ranked(val rank: Int) : Comparable<Ranked>, java.util.function.Supplier<String> {
        override fun compareTo(other: Ranked): Int = rank - other.rank

        override fun get(): String = "rank$rank"
    }

    class RunningText(private val text: String) : CharSequence by text, Runnable {
        override fun run() {
        }

        override fun toString(): String = text
    }

    class Helpers {
        companion object {
            @JvmStatic
            fun next(): Any = RunningText("next")

            @JvmStatic
            fun last(): Any = RunningText("last")

            @JvmStatic
            fun <T> both(value: T): T where T : CharSequence, T : Runnable = value

            @JvmStatic
            fun <S : java.io.Serializable> keep(value: S): S = value

            @JvmStatic
            fun rows(values: Array<String>): Int = values.size

            @JvmStatic
            fun size(value: String): Int = value.length

            @JvmStatic
            fun length(text: CharSequence): Int = text.length

            @JvmStatic
            fun greet(name: String): String = "hi $name"
        }
    }
}
