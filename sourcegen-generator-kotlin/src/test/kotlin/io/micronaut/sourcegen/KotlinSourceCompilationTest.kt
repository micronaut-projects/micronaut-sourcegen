package io.micronaut.sourcegen

import io.micronaut.sourcegen.KotlinCompileAssertions.assertCompiles
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.FieldDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import io.micronaut.sourcegen.model.InterfaceDef
import java.util.Collections
import java.util.function.Consumer
import java.util.function.IntPredicate
import java.util.function.Supplier
import javax.lang.model.element.Modifier

/**
 * Models shaped like the ones written for bytecode, rendered as Kotlin source that has to compile.
 * The same scenarios as the Java generator's `JavaSourceCompilationTest`.
 */
class KotlinSourceCompilationTest {

    private fun writeClass(objectDef: ObjectDef): String {
        StringWriter().use { writer ->
            KotlinPoetSourceGenerator().write(objectDef, writer)
            return writer.toString()
        }
    }

    @Test
    fun superConstructorWithExplicitSuperType() {
        val objectConstructor = Any::class.java.getConstructor()
        val classDef = ClassDef.builder("test.Child")
            .superclass(ClassTypeDef.of(Any::class.java))
            .addMethod(MethodDef.constructor().build { aThis, _ ->
                aThis.superRef(ClassTypeDef.of(Any::class.java)).invokeConstructor(objectConstructor)
            })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |public class Child()
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun superMethodOfSuperclassAndDefaultMethodOfInterface() {
        // A non-generic interface: Kotlin cannot implement a raw one, and sees Iterator.remove() as abstract
        val predicateType = ClassTypeDef.of(IntPredicate::class.java)
        val negateMethod = IntPredicate::class.java.getMethod("negate")
        val toStringMethod = Any::class.java.getMethod("toString")
        val classDef = ClassDef.builder("test.Predicate")
            .addSuperinterface(predicateType)
            .addMethod(MethodDef.builder("test").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build { _, _ -> ExpressionDef.constant(true).returning() })
            .addMethod(MethodDef.builder("negate").addModifiers(Modifier.PUBLIC).overrides()
                .returns(predicateType)
                .build { aThis, _ -> aThis.superRef(predicateType).invoke(negateMethod).returning() })
            .addMethod(MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).overrides()
                .returns(TypeDef.STRING)
                .build { aThis, _ ->
                    aThis.superRef(ClassTypeDef.of(Any::class.java)).invoke(toStringMethod).returning()
                })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.IntPredicate
            |import kotlin.Boolean
            |import kotlin.Int
            |import kotlin.String
            |
            |public class Predicate : IntPredicate {
            |  public override fun test(`value`: Int): Boolean {
            |    return true
            |  }
            |
            |  public override fun negate(): IntPredicate {
            |    return super<IntPredicate>.negate()
            |  }
            |
            |  public override fun toString(): String {
            |    return super.toString()
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun blankFinalStaticFieldsAssignedInTryCatch() {
        val type = ClassTypeDef.of("test.Holder")
        val value = FieldDef.builder("VALUE", String::class.java)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .build()
        val failure = FieldDef.builder("FAILURE", Throwable::class.java)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .build()
        val classDef = ClassDef.builder(type.name)
            .addField(value)
            .addField(failure)
            .addStaticInitializer(StatementDef.multi(
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("a")))
                    .doCatch(Throwable::class.java) { exceptionVar -> type.getStaticField(failure).put(exceptionVar) },
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("b")))
                    .doCatch(Throwable::class.java) { _ -> type.getStaticField(value).put(ExpressionDef.constant("c")) }
            ))
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |import kotlin.Throwable
            |
            |public class Holder {
            |  public companion object {
            |    private final lateinit var VALUE: String
            |
            |    private final lateinit var FAILURE: Throwable
            |
            |    init {
            |      try {
            |        Holder.VALUE = "a"
            |      } catch (e: Throwable) {
            |        Holder.FAILURE = e
            |      }
            |      try {
            |        Holder.VALUE = "b"
            |      } catch (e: Throwable) {
            |        Holder.VALUE = "c"
            |      }
            |    }
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun namesContainingDollarSigns() {
        val field = FieldDef.builder("\$field", String::class.java).addModifiers(Modifier.PRIVATE).build()
        val getter = MethodDef.builder("\$get").returns(String::class.java)
            .build { aThis, _ -> aThis.field(field).returning() }
        val classDef = ClassDef.builder("test.\$Holder\$Definition")
            .addField(field)
            .addMethod(getter)
            .addMethod(MethodDef.builder("\$copy").addParameter("\$value", String::class.java).returns(String::class.java)
                .build { aThis, parameters ->
                    StatementDef.multi(
                        aThis.field(field).put(parameters[0]),
                        aThis.invoke(getter).newLocal("\$local") { local -> local.returning() }
                    )
                })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class `${'$'}Holder${'$'}Definition` {
            |  private lateinit var `${'$'}field`: String
            |
            |  public fun `${'$'}get`(): String {
            |    return this. `${'$'}field`
            |  }
            |
            |  public fun `${'$'}copy`(`${'$'}value`: String): String {
            |    this. `${'$'}field` = `${'$'}value`
            |    var `${'$'}local`:String = this.`${'$'}get`()
            |    return `${'$'}local`
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun objectTypedArgumentsAndReturnValuesAreCast() {
        val take = MethodDef.builder("take").addParameter("text", String::class.java).build()
        val stringBuilderConstructor = StringBuilder::class.java.getConstructor(String::class.java)
        val classDef = ClassDef.builder("test.Dispatch")
            .addMethod(take)
            .addMethod(MethodDef.builder("dispatch").addParameter("value", Any::class.java)
                .build { aThis, parameters -> aThis.invoke(take, parameters[0]) })
            .addMethod(MethodDef.builder("create").addParameter("value", Any::class.java)
                .returns(StringBuilder::class.java)
                .build { _, parameters ->
                    ClassTypeDef.of(StringBuilder::class.java)
                        .instantiate(stringBuilderConstructor, parameters[0])
                        .returning()
                })
            .addMethod(MethodDef.builder("narrow").addParameter("value", Any::class.java).returns(String::class.java)
                .build { _, parameters -> parameters[0].returning() })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import java.lang.StringBuilder
            |import kotlin.Any
            |import kotlin.String
            |
            |public class Dispatch {
            |  public fun take(text: String) {
            |  }
            |
            |  public fun dispatch(`value`: Any) {
            |    this.take(`value` as String)
            |  }
            |
            |  public fun create(`value`: Any): StringBuilder {
            |    return StringBuilder(`value` as String)
            |  }
            |
            |  public fun narrow(`value`: Any): String {
            |    return `value` as String
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun returningVoidInvocationAndUnreachableFallback() {
        val run = MethodDef.builder("run").build()
        val classDef = ClassDef.builder("test.Flow")
            .addMethod(run)
            .addMethod(MethodDef.builder("delegate")
                .build { aThis, _ -> aThis.invoke(run).returning() })
            .addMethod(MethodDef.builder("select").addParameter("index", TypeDef.Primitive.INT).returns(TypeDef.OBJECT)
                .build { _, parameters ->
                    StatementDef.multi(
                        parameters[0].asStatementSwitch(
                            TypeDef.OBJECT,
                            mapOf(ExpressionDef.constant(0) to ExpressionDef.constant("zero").returning()),
                            ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow()
                        ),
                        ExpressionDef.nullValue().returning()
                    )
                })
            .addMethod(MethodDef.builder("guarded").returns(TypeDef.OBJECT)
                .build { _, _ ->
                    StatementDef.multi(
                        StatementDef.doTry(ExpressionDef.constant("value").returning())
                            .doCatch(Throwable::class.java) { _ ->
                                ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow()
                            },
                        ExpressionDef.nullValue().returning()
                    )
                })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import java.lang.IllegalStateException
            |import kotlin.Any
            |import kotlin.Int
            |import kotlin.Throwable
            |
            |public class Flow {
            |  public fun run() {
            |  }
            |
            |  public fun `delegate`() {
            |    this.run()
            |  }
            |
            |  public fun select(index: Int): Any {
            |    when (index) {
            |      0-> {
            |        return "zero"
            |      }
            |      else -> {
            |        throw IllegalStateException()
            |      }
            |    }
            |  }
            |
            |  public fun guarded(): Any {
            |    try {
            |      return "value"
            |    } catch (e: Throwable) {
            |      throw IllegalStateException()
            |    }
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun instanceOfNestedType() {
        val classDef = ClassDef.builder("test.Check")
            .addMethod(MethodDef.builder("isEntry").addParameter("value", Any::class.java)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build { _, parameters ->
                    ExpressionDef.InstanceOf(parameters[0], ClassTypeDef.of(Map.Entry::class.java)).returning()
                })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import java.util.Map
            |import kotlin.Any
            |import kotlin.Boolean
            |
            |public class Check {
            |  public fun isEntry(`value`: Any): Boolean {
            |    return `value` is Map.Entry<*, *>
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun fieldOfAnotherType() {
        val otherType = ClassTypeDef.of("test.Other")
        val other = ClassDef.builder(otherType.name)
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("name", String::class.java).addModifiers(Modifier.PUBLIC).build())
            .build()
        val accessor = ClassDef.builder("test.Accessor")
            .addMethod(MethodDef.builder("read").addParameter("value", Any::class.java).returns(String::class.java)
                .build { _, parameters ->
                    VariableDef.Field(parameters[0].cast(otherType), otherType, "name", TypeDef.STRING).returning()
                })
            .build()

        val otherSource = writeClass(other)
        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class Other {
            |  public lateinit var name: String
            |}
            |""".trimMargin(),
            otherSource
        )
        val accessorSource = writeClass(accessor)
        assertEquals(
            """
            |package test
            |
            |import kotlin.Any
            |import kotlin.String
            |
            |public class Accessor {
            |  public fun read(`value`: Any): String {
            |    return (`value` as Other). name
            |  }
            |}
            |""".trimMargin(),
            accessorSource
        )
        assertCompiles(otherSource, accessorSource)
    }

    @Test
    fun arrayAnnotationMemberAndMultiDimensionalArrays() {
        val classDef = ClassDef.builder("test.Arrays2")
            .addAnnotation(AnnotationDef.builder(SuppressWarnings::class.java)
                .addMember("value", arrayOf("unchecked", "rawtypes"))
                .build())
            .addMethod(MethodDef.builder("matrix").returns(TypeDef.STRING.array(2))
                .build { _, _ ->
                    TypeDef.STRING.array(2).instantiate(listOf(
                        TypeDef.STRING.array().instantiate(listOf(ExpressionDef.constant("a")))
                    )).returning()
                })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import java.lang.SuppressWarnings
            |import kotlin.Array
            |import kotlin.String
            |
            |@SuppressWarnings(value = ["unchecked",
            |"rawtypes"])
            |public class Arrays2 {
            |  public fun matrix(): Array<Array<String>> {
            |    return arrayOf<Array<String>>(arrayOf<String>("a"))
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun erasedOverridesOfGenericInterfacesTakeTheTypeArguments() {
        val compareTo = Comparable::class.java.getMethod("compareTo", Any::class.java)
        val get = Supplier::class.java.getMethod("get")
        val classDef = ClassDef.builder("test.Typed")
            .addSuperinterface(TypeDef.parameterized(Comparable::class.java, String::class.java))
            .addSuperinterface(TypeDef.parameterized(Supplier::class.java, String::class.java))
            // The erased signatures a model written for bytecode declares
            .addMethod(MethodDef.override(compareTo)
                .build { _, _ -> ExpressionDef.constant(0).returning() })
            .addMethod(MethodDef.override(get)
                .build { _, _ -> ExpressionDef.constant("value").cast(TypeDef.OBJECT).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.lang.Comparable
            |import java.util.function.Supplier
            |import kotlin.Int
            |import kotlin.String
            |
            |public class Typed : Comparable<String>, Supplier<String> {
            |  public override fun compareTo(arg0: String): Int {
            |    return 0
            |  }
            |
            |  public override fun `get`(): String {
            |    return "value"
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun erasedOverridesWithTheTypeVariablesOfTheDeclaringType() {
        val get = Supplier::class.java.getMethod("get")
        val accept = Consumer::class.java.getMethod("accept", Any::class.java)
        val variable = TypeDef.variable("T")
        val classDef = ClassDef.builder("test.Box")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), variable))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer::class.java), variable))
            // Kotlin overrides neither `Any get()` for `T get()` nor `accept(Any)` for `accept(T)`
            .addMethod(MethodDef.override(get)
                .build { _, _ -> ExpressionDef.nullValue().cast(variable).returning() })
            .addMethod(MethodDef.override(accept)
                .build { _, _ -> StatementDef.multi() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Consumer
            |import java.util.function.Supplier
            |
            |public class Box<T> : Supplier<T>, Consumer<T> {
            |  public override fun `get`(): T {
            |    return null as T
            |  }
            |
            |  public override fun accept(arg0: T) {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun rawOverrideOfAParameterizedReturnType() {
        val names = InterfaceDef.builder("test.Names")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("names")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.parameterized(Set::class.java, String::class.java))
                .build())
            .build()
        // Kotlin has no raw types: the override takes the type arguments
        val classDef = ClassDef.builder("test.RawNames")
            .addSuperinterface(names.asTypeDef())
            .addMethod(MethodDef.builder("names")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassTypeDef.of(Set::class.java))
                .overrides()
                .build { _, _ ->
                    ClassTypeDef.of(Collections::class.java)
                        .invokeStatic("emptySet", ClassTypeDef.of(Set::class.java))
                        .returning()
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.Collections
            |import kotlin.String
            |import kotlin.collections.Set
            |
            |public class RawNames : Names {
            |  public override fun names(): Set<String> {
            |    return Collections.emptySet()
            |  }
            |}
            |""".trimMargin(),
            source
        )
        val namesSource = writeClass(names)
        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |import kotlin.collections.Set
            |
            |public interface Names {
            |  public fun names(): Set<String>
            |}
            |""".trimMargin(),
            namesSource
        )
        assertCompiles(namesSource, source)
    }

    @Test
    fun returningAVoidInvocationInsideACondition() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        // Without the return, the branch falls through and the call below it runs as well
        val classDef = ClassDef.builder("test.Branch")
            .addMethod(run)
            .addMethod(MethodDef.builder("dispatch").addModifiers(Modifier.PUBLIC)
                .addParameter("stop", TypeDef.primitive(Boolean::class.javaPrimitiveType!!))
                .build { aThis, parameters ->
                    StatementDef.multi(
                        parameters[0].isTrue().doIf(aThis.invoke(run).returning()),
                        aThis.invoke(run)
                    )
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Boolean
            |
            |public class Branch {
            |  private fun run() {
            |  }
            |
            |  public fun dispatch(stop: Boolean) {
            |    if (stop) {
            |      this.run()
            |      return
            |    }
            |    this.run()
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun returningFromAFinallyBlock() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        // The return discards the exception of the try; dropping it would let the exception out
        val classDef = ClassDef.builder("test.Finally")
            .addMethod(run)
            .addMethod(MethodDef.builder("guarded").addModifiers(Modifier.PUBLIC)
                .build { aThis, _ ->
                    StatementDef
                        .doTry(ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow())
                        .doFinally(StatementDef.multi(aThis.invoke(run), aThis.invoke(run).returning()))
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.lang.IllegalStateException
            |
            |public class Finally {
            |  private fun run() {
            |  }
            |
            |  public fun guarded() {
            |    try {
            |      throw IllegalStateException()
            |    } finally {
            |      this.run()
            |      this.run()
            |      return
            |    }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun blankFieldOfABoxedPrimitiveType() {
        val type = ClassTypeDef.of("test.Counter")
        val count = FieldDef.builder("count", Integer::class.java)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .build()
        // Kotlin maps the boxed type to its primitive, which cannot be a lateinit property
        val classDef = ClassDef.builder("test.Counter")
            .addField(count)
            .addStaticInitializer(type.getStaticField(count).put(ExpressionDef.constant(1)))
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Int
            |
            |public class Counter {
            |  public companion object {
            |    private var count: Int = 0
            |
            |    init {
            |      Counter.count = 1
            |    }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun finalFieldAssignedByTheConstructorIsAVal() {
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .build()
        val classDef = ClassDef.builder("test.Named")
            .addField(name)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("name", String::class.java)
                .build { aThis, parameters -> aThis.field(name).put(parameters[0]) })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class Named {
            |  public final val name: String
            |
            |  public constructor(name: String) {
            |    this. name = name
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun anyReturnedAsATypeVariableOrAnArray() {
        val get = Supplier::class.java.getMethod("get")
        val variable = TypeDef.variable("T")
        // The resolved `T get()` and `Array<String> get()` return a value the model types as Any
        val box = ClassDef.builder("test.AnyBox")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), variable))
            .addMethod(MethodDef.override(get)
                .build { _, _ -> ExpressionDef.constant("value").cast(TypeDef.OBJECT).returning() })
            .build()
        val strings = ClassDef.builder("test.AnyStrings")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier::class.java), TypeDef.STRING.array()))
            .addMethod(MethodDef.override(get)
                .build { _, _ ->
                    TypeDef.STRING.array().instantiate(listOf(ExpressionDef.constant("a")))
                        .cast(TypeDef.OBJECT)
                        .returning()
                })
            .build()

        val boxSource = writeClass(box)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Supplier
            |
            |public class AnyBox<T> : Supplier<T> {
            |  public override fun `get`(): T {
            |    return "value" as T
            |  }
            |}
            |""".trimMargin(),
            boxSource
        )
        val stringsSource = writeClass(strings)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Supplier
            |import kotlin.Array
            |import kotlin.String
            |
            |public class AnyStrings : Supplier<Array<String>> {
            |  public override fun `get`(): Array<String> {
            |    return arrayOf<String>("a")
            |  }
            |}
            |""".trimMargin(),
            stringsSource
        )
        assertCompiles(boxSource, stringsSource)
    }

    @Test
    fun jvmFieldAssignedByTheConstructor() {
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(JvmField::class.java).build())
            .build()
        // lateinit cannot be combined with @JvmField, and the constructor assigns the property anyway
        val classDef = ClassDef.builder("test.JvmNamed")
            .addField(name)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("name", String::class.java)
                .build { aThis, parameters -> aThis.field(name).put(parameters[0]) })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |import kotlin.jvm.JvmField
            |
            |public class JvmNamed {
            |  @JvmField
            |  public var name: String
            |
            |  public constructor(name: String) {
            |    this. name = name
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun returnErasedToABoundIsCast() {
        val variable = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val bounded = InterfaceDef.builder("test.Bounded")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable)
                .build())
            .build()
        // The model declares the erasure, `CharSequence get()`, which Kotlin overrides as `String get()`
        val classDef = ClassDef.builder("test.BoundedString")
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .returns(CharSequence::class.java)
                .build { _, _ ->
                    ExpressionDef.constant("value").cast(TypeDef.of(CharSequence::class.java)).returning()
                })
            .build()

        val boundedSource = writeClass(bounded)
        assertEquals(
            """
            |package test
            |
            |import kotlin.CharSequence
            |
            |public interface Bounded<T : CharSequence> {
            |  public fun `get`(): T
            |}
            |""".trimMargin(),
            boundedSource
        )
        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class BoundedString : Bounded<String> {
            |  public override fun `get`(): String {
            |    return "value"
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(boundedSource, classDefSource)
    }

    @Test
    fun constructorAssigningTheFieldOfAnotherInstance() {
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .build()
        val type = ClassTypeDef.of("test.Other")
        // `other.name` leaves `this.name` unassigned, which needs lateinit
        val classDef = ClassDef.builder("test.Other")
            .addField(name)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("other", type)
                .build { _, parameters -> parameters[0].field(name).put(ExpressionDef.constant("value")) })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class Other {
            |  public lateinit var name: String
            |
            |  public constructor(other: Other) {
            |    other. name = "value"
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun jvmFieldAssignedInEachBranchOfTheConstructor() {
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(JvmField::class.java).build())
            .build()
        // Both branches assign the property, which needs no lateinit - and @JvmField allows none
        val classDef = ClassDef.builder("test.Branched")
            .addField(name)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("flag", TypeDef.primitive(Boolean::class.javaPrimitiveType!!))
                .build { aThis, parameters ->
                    parameters[0].isTrue().doIfElse(
                        aThis.field(name).put(ExpressionDef.constant("yes")),
                        aThis.field(name).put(ExpressionDef.constant("no"))
                    )
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Boolean
            |import kotlin.String
            |import kotlin.jvm.JvmField
            |
            |public class Branched {
            |  @JvmField
            |  public var name: String
            |
            |  public constructor(flag: Boolean) {
            |    if (flag) {
            |      this. name = "yes"
            |    } else {
            |      this. name = "no"
            |    }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun assignmentAfterAReturnOfTheConstructor() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .build()
        // The assignment after the return is never reached, and not rendered: the property stays lateinit
        val classDef = ClassDef.builder("test.EarlyReturn")
            .addField(name)
            .addMethod(run)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ ->
                    StatementDef.multi(
                        aThis.invoke(run).returning(),
                        aThis.field(name).put(ExpressionDef.constant("value"))
                    )
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |
            |public class EarlyReturn {
            |  public lateinit var name: String
            |
            |  public constructor() {
            |    this.run()
            |    return
            |  }
            |
            |  private fun run() {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun jvmFieldAssignedInEachBranchBeforeItReturns() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(JvmField::class.java).build())
            .build()
        // Each branch assigns the property before it returns, so no path leaves it unassigned
        val classDef = ClassDef.builder("test.BranchReturns")
            .addField(name)
            .addMethod(run)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("flag", TypeDef.primitive(Boolean::class.javaPrimitiveType!!))
                .build { aThis, parameters ->
                    parameters[0].isTrue().doIfElse(
                        StatementDef.multi(
                            aThis.field(name).put(ExpressionDef.constant("yes")),
                            aThis.invoke(run).returning()
                        ),
                        StatementDef.multi(
                            aThis.field(name).put(ExpressionDef.constant("no")),
                            aThis.invoke(run).returning()
                        )
                    )
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Boolean
            |import kotlin.String
            |import kotlin.jvm.JvmField
            |
            |public class BranchReturns {
            |  @JvmField
            |  public var name: String
            |
            |  public constructor(flag: Boolean) {
            |    if (flag) {
            |      this. name = "yes"
            |      this.run()
            |    } else {
            |      this. name = "no"
            |      this.run()
            |    }
            |  }
            |
            |  private fun run() {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun narrowedArrayReturnIsCast() {
        val variable = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        val bounded = InterfaceDef.builder("test.BoundedArray")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable.array())
                .build())
            .build()
        val sequences = TypeDef.of(CharSequence::class.java).array()
        val values = FieldDef.builder("values", sequences)
            .addModifiers(Modifier.PRIVATE)
            .initializer(sequences.instantiate(listOf(ExpressionDef.constant("a"))))
            .build()
        // The model declares the erasure, `Array<CharSequence>`, which Kotlin overrides as `Array<String>`
        val classDef = ClassDef.builder("test.StringArray")
            .addField(values)
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .returns(sequences)
                .build { aThis, _ -> aThis.field(values).returning() })
            .build()

        val boundedSource = writeClass(bounded)
        assertEquals(
            """
            |package test
            |
            |import kotlin.Array
            |import kotlin.CharSequence
            |
            |public interface BoundedArray<T : CharSequence> {
            |  public fun `get`(): Array<T>
            |}
            |""".trimMargin(),
            boundedSource
        )
        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import kotlin.Array
            |import kotlin.CharSequence
            |import kotlin.String
            |
            |public class StringArray : BoundedArray<String> {
            |  private var values: Array<CharSequence> = arrayOf<CharSequence>("a")
            |
            |  public override fun `get`(): Array<String> {
            |    return this. values as Array<String>
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(boundedSource, classDefSource)
    }

    @Test
    fun jvmFieldAssignedBeforeASwitchWithoutADefault() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(JvmField::class.java).build())
            .build()
        // The path no case matches keeps the assignment made before the switch
        val classDef = ClassDef.builder("test.BeforeSwitch")
            .addField(name)
            .addMethod(run)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("index", TypeDef.Primitive.INT)
                .build { aThis, parameters ->
                    StatementDef.multi(
                        aThis.field(name).put(ExpressionDef.constant("value")),
                        parameters[0].asStatementSwitch(
                            TypeDef.OBJECT,
                            mapOf(ExpressionDef.constant(0) to aThis.invoke(run))
                        )
                    )
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Int
            |import kotlin.String
            |import kotlin.jvm.JvmField
            |
            |public class BeforeSwitch {
            |  @JvmField
            |  public var name: String
            |
            |  public constructor(index: Int) {
            |    this. name = "value"
            |    when (index) {
            |      0-> {
            |        this.run()
            |      }
            |    }
            |  }
            |
            |  private fun run() {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun finallyReturningAfterATryThatOnlyThrows() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .build()
        // The try only throws, which assigns nothing; the finally returns with the property unassigned
        val classDef = ClassDef.builder("test.FinallyReturns")
            .addField(name)
            .addMethod(run)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ ->
                    StatementDef
                        .doTry(ClassTypeDef.of(IllegalStateException::class.java).instantiate().doThrow())
                        .doFinally(StatementDef.multi(aThis.invoke(run), aThis.invoke(run).returning()))
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.lang.IllegalStateException
            |import kotlin.String
            |
            |public class FinallyReturns {
            |  public lateinit var name: String
            |
            |  public constructor() {
            |    try {
            |      throw IllegalStateException()
            |    } finally {
            |      this.run()
            |      this.run()
            |      return
            |    }
            |  }
            |
            |  private fun run() {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun jvmFieldAssignedInATryWhoseFinallyReturns() {
        val run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build { _, _ -> StatementDef.multi() }
        val name = FieldDef.builder("name", String::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(JvmField::class.java).build())
            .build()
        // The try assigns the property on the path that reaches the finally's return
        val classDef = ClassDef.builder("test.TryAssigns")
            .addField(name)
            .addMethod(run)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build { aThis, _ ->
                    StatementDef
                        .doTry(aThis.field(name).put(ExpressionDef.constant("value")))
                        .doFinally(StatementDef.multi(aThis.invoke(run), aThis.invoke(run).returning()))
                })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import kotlin.String
            |import kotlin.jvm.JvmField
            |
            |public class TryAssigns {
            |  @JvmField
            |  public var name: String
            |
            |  public constructor() {
            |    try {
            |      this. name = "value"
            |    } finally {
            |      this.run()
            |      this.run()
            |      return
            |    }
            |  }
            |
            |  private fun run() {
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun anyValueOfAPrimitiveTarget() {
        val take = MethodDef.builder("take").addModifiers(Modifier.PRIVATE)
            .addParameter("count", TypeDef.Primitive.INT)
            .build { _, _ -> StatementDef.multi() }
        // An Any value is passed to an Int parameter, and returned from an Int method
        val classDef = ClassDef.builder("test.Unboxed")
            .addMethod(take)
            .addMethod(MethodDef.builder("pass").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Any::class.java)
                .build { aThis, parameters -> aThis.invoke(take, parameters[0]) })
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Any::class.java)
                .returns(TypeDef.Primitive.INT)
                .build { _, parameters -> parameters[0].returning() })
            .build()

        val classDefSource = writeClass(classDef)
        assertEquals(
            """
            |package test
            |
            |import kotlin.Any
            |import kotlin.Int
            |
            |public class Unboxed {
            |  private fun take(count: Int) {
            |  }
            |
            |  public fun pass(`value`: Any) {
            |    this.take(`value` as Int)
            |  }
            |
            |  public fun count(`value`: Any): Int {
            |    return `value` as Int
            |  }
            |}
            |""".trimMargin(),
            classDefSource
        )
        assertCompiles(classDefSource)
    }

    @Test
    fun narrowedParameterKeepsTheOverloadTheModelCalls() {
        val chooseAny = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Any::class.java)
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("object").returning() }
        val chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String::class.java)
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("string").returning() }
        val apply = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        // `apply(Any)` becomes `apply(String)`, where `choose(value)` would call `choose(String)`
        val classDef = ClassDef.builder("test.Chooser")
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(chooseAny)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build { aThis, parameters -> aThis.invoke(chooseAny, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class Chooser : Function<String, String> {
            |  public fun choose(`value`: Any): String {
            |    return "object"
            |  }
            |
            |  public fun choose(`value`: String): String {
            |    return "string"
            |  }
            |
            |  public override fun apply(arg0: String): String {
            |    return this.choose((arg0 as Any))
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun callOfANarrowedMethodOfTheClassIsConverted() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        // `apply` is written as `apply(String)`, which the Any value passed to it is converted to
        val classDef = ClassDef.builder("test.Caller")
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Any::class.java)
                .returns(Any::class.java)
                .build { aThis, parameters -> aThis.invoke(apply, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class Caller : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |
            |  public fun call(`value`: Any): Any {
            |    return this.apply(`value` as String)
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun narrowedArrayKeepsTheArrayOverloadTheModelCalls() {
        val chooseAny = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("values", TypeDef.OBJECT.array())
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("object").returning() }
        val chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("values", TypeDef.STRING.array())
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("string").returning() }
        val apply = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        // An ordinary array parameter is no varargs: the narrowed `Array<String>` keeps the `Array<Any>` overload
        val classDef = ClassDef.builder("test.ArrayChooser")
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of(java.util.function.Function::class.java), TypeDef.STRING.array(), TypeDef.STRING))
            .addMethod(chooseAny)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build { aThis, parameters -> aThis.invoke(chooseAny, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.String
            |
            |public class ArrayChooser : Function<Array<String>, String> {
            |  public fun choose(values: Array<Any>): String {
            |    return "object"
            |  }
            |
            |  public fun choose(values: Array<String>): String {
            |    return "string"
            |  }
            |
            |  public override fun apply(arg0: Array<String>): String {
            |    return this.choose((arg0 as Array<Any>))
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun anyValueOfANarrowedArrayParameter() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, _ -> ExpressionDef.constant("value").returning() }
        // `apply` is written as `apply(Array<String>)`, an ordinary array parameter the Any value is cast to
        val classDef = ClassDef.builder("test.ArrayCaller")
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of(java.util.function.Function::class.java), TypeDef.STRING.array(), TypeDef.STRING))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Any::class.java)
                .returns(Any::class.java)
                .build { aThis, parameters -> aThis.invoke(apply, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.String
            |
            |public class ArrayCaller : Function<Array<String>, String> {
            |  public override fun apply(arg0: Array<String>): String {
            |    return "value"
            |  }
            |
            |  public fun call(`value`: Any): Any {
            |    return this.apply(`value` as Array<String>)
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun callOfAnInheritedNarrowedMethodIsConverted() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        // Abstract, so that Kotlin lets it be extended
        val parent = ClassDef.builder("test.ParentTarget")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .build()
        val child = ClassDef.builder("test.ChildTarget")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parent.asTypeDef())
            .build()
        // `ChildTarget` inherits `apply(String)`, which the Any value passed to it is converted to
        val caller = ClassDef.builder("test.ChildCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", child.asTypeDef())
                .addParameter("value", Any::class.java)
                .returns(Any::class.java)
                .build { _, parameters -> parameters[0].invoke(apply, parameters[1]).returning() })
            .build()

        val source = writeClass(caller)

        assertEquals(
            """
            |package test
            |
            |import kotlin.Any
            |import kotlin.String
            |
            |public class ChildCaller {
            |  public fun call(target: ChildTarget, `value`: Any): Any {
            |    return target.apply(`value` as String)
            |  }
            |}
            |""".trimMargin(),
            source
        )
        val parentSource = writeClass(parent)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.String
            |
            |public abstract class ParentTarget : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |}
            |""".trimMargin(),
            parentSource
        )
        val childSource = writeClass(child)
        assertEquals(
            """
            |package test
            |
            |public class ChildTarget : ParentTarget()
            |""".trimMargin(),
            childSource
        )
        assertCompiles(parentSource, childSource, source)
    }

    @Test
    fun switchOfNarrowedParametersKeepsTheOverload() {
        val chooseAny = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Any::class.java)
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("object").returning() }
        val chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String::class.java)
            .returns(String::class.java)
            .build { _, _ -> ExpressionDef.constant("string").returning() }
        val apply = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        // Every case is the narrowed parameter, so the switch is a String in the source
        val classDef = ClassDef.builder("test.SwitchChooser")
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(chooseAny)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build { aThis, parameters -> aThis.invoke(chooseAny,
                    ExpressionDef.constant(1).asExpressionSwitch(TypeDef.OBJECT,
                        mapOf(ExpressionDef.constant(1) to parameters[0]), parameters[0])).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class SwitchChooser : Function<String, String> {
            |  public fun choose(`value`: Any): String {
            |    return "object"
            |  }
            |
            |  public fun choose(`value`: String): String {
            |    return "string"
            |  }
            |
            |  public override fun apply(arg0: String): String {
            |    return this.choose((when (1) {
            |          1 -> arg0;
            |          else -> arg0} as Any))
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun conditionalOfDifferentTypesKeepsTheOverload() {
        val chooseAny = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Any::class.java)
            .returns(TypeDef.Primitive.INT)
            .build { _, _ -> ExpressionDef.constant(1).returning() }
        val chooseSequence = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence::class.java)
            .returns(TypeDef.Primitive.INT)
            .build { _, _ -> ExpressionDef.constant(2).returning() }
        // Kotlin types the conditional as the CharSequence both branches are, where the model calls `choose(Any)`
        val classDef = ClassDef.builder("test.MixedConditionalChooser")
            .addMethod(chooseAny)
            .addMethod(chooseSequence)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
                .addParameter("flag", TypeDef.Primitive.BOOLEAN)
                .returns(TypeDef.Primitive.INT)
                .build { aThis, parameters -> aThis.invoke(chooseAny,
                    parameters[0].isTrue().doIfElse(
                        ExpressionDef.constant("a"),
                        ClassTypeDef.of(java.lang.StringBuilder::class.java).instantiate())).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.lang.StringBuilder
            |import kotlin.Any
            |import kotlin.Boolean
            |import kotlin.CharSequence
            |import kotlin.Int
            |
            |public class MixedConditionalChooser {
            |  public fun choose(`value`: Any): Int {
            |    return 1
            |  }
            |
            |  public fun choose(`value`: CharSequence): Int {
            |    return 2
            |  }
            |
            |  public fun pick(flag: Boolean): Int {
            |    return this.choose((if (flag) "a" else StringBuilder() as Any))
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun referenceToANarrowedMethodConvertsItsArguments() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val anyFunction = TypeDef.parameterized(
            java.util.function.Function::class.java, Any::class.java, Any::class.java)
        // `apply` is written as `apply(String)`, which a `Function<Any, Any>` cannot reference
        val classDef = ClassDef.builder("test.ReferencedFunction")
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(anyFunction)
                .build { aThis, _ -> anyFunction.methodReference(aThis, apply).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class ReferencedFunction : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |
            |  public fun asFunction(): Function<Any, Any> {
            |    return Function<Any, Any> { arg0 -> this.apply(arg0 as String) }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun anyValueOfAParameterResolvedToATypeVariable() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val variable = TypeDef.variable("T", TypeDef.of(CharSequence::class.java))
        // `apply(Any)` is written as `apply(T)`, which the Any value passed to it is cast to
        val classDef = ClassDef.builder("test.GenericTarget")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of(java.util.function.Function::class.java), variable, variable))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Any::class.java)
                .returns(Any::class.java)
                .build { aThis, parameters -> aThis.invoke(apply, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.CharSequence
            |
            |public class GenericTarget<T : CharSequence> : Function<T, T> {
            |  public override fun apply(arg0: T): T {
            |    return arg0 as T
            |  }
            |
            |  public fun call(`value`: Any): Any {
            |    return this.apply(`value` as T)
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun referenceThroughAFieldReadsItOnce() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val target = ClassDef.builder("test.CapturedTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .build()
        val anyFunction = TypeDef.parameterized(
            java.util.function.Function::class.java, Any::class.java, Any::class.java)
        val field = FieldDef.builder("target", target.asTypeDef()).addModifiers(Modifier.PRIVATE)
            .initializer(target.asTypeDef().instantiate())
            .build()
        val caller = ClassDef.builder("test.CapturingCaller")
            .addField(field)
            .addMethod(MethodDef.builder("fromField").addModifiers(Modifier.PUBLIC)
                .returns(anyFunction)
                .build { aThis, _ -> anyFunction.methodReference(aThis.field(field), apply).returning() })
            .build()

        val source = writeClass(caller)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class CapturingCaller {
            |  private var target: CapturedTarget = CapturedTarget()
            |
            |  public fun fromField(): Function<Any, Any> {
            |    return this. target.let { target -> Function<Any, Any> { arg0 -> target.apply(arg0 as String) } }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        val targetSource = writeClass(target)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.String
            |
            |public class CapturedTarget : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |}
            |""".trimMargin(),
            targetSource
        )
        assertCompiles(targetSource, source)
    }

    @Test
    fun adaptedReferenceNamesAvoidTheEnclosingLambda() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val target = ClassDef.builder("test.NamedTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .build()
        val anyFunction = TypeDef.parameterized(
            java.util.function.Function::class.java, Any::class.java, Any::class.java)
        val factory = TypeDef.parameterized(ClassTypeDef.of(java.util.function.Function::class.java),
            target.asTypeDef(), anyFunction)
        // The outer lambda's parameter is named `arg0`, which the adapter's own parameter must not shadow
        val caller = ClassDef.builder("test.NestedCaller")
            .addMethod(MethodDef.builder("factory").addModifiers(Modifier.PUBLIC)
                .returns(factory)
                .build { _, _ ->
                    factory.lambda.implement(listOf("arg0")) { _, parameters ->
                        anyFunction.methodReference(parameters[0], apply).returning()
                    }.returning()
                })
            .build()

        val source = writeClass(caller)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class NestedCaller {
            |  public fun factory(): Function<NamedTarget, Function<Any, Any>> {
            |    return Function<NamedTarget, Function<Any, Any>> {arg0: NamedTarget -> Function<Any, Any> { arg0_1 -> arg0.apply(arg0_1 as String) }}
            |  }
            |}
            |""".trimMargin(),
            source
        )
        val targetSource = writeClass(target)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.String
            |
            |public class NamedTarget : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |}
            |""".trimMargin(),
            targetSource
        )
        assertCompiles(targetSource, source)
    }

    @Test
    fun referenceToANarrowedMethodConvertsItsResult() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val stringFunction = TypeDef.parameterized(
            java.util.function.Function::class.java, Any::class.java, String::class.java)
        // `apply` is written as `apply(CharSequence): CharSequence`, which a `Function<Any, String>` returns cast
        val classDef = ClassDef.builder("test.ResultReferenced")
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, CharSequence::class.java, CharSequence::class.java))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(stringFunction)
                .build { aThis, _ -> stringFunction.methodReference(aThis, apply).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.CharSequence
            |import kotlin.String
            |
            |public class ResultReferenced : Function<CharSequence, CharSequence> {
            |  public override fun apply(arg0: CharSequence): CharSequence {
            |    return arg0 as CharSequence
            |  }
            |
            |  public fun asFunction(): Function<Any, String> {
            |    return Function<Any, String> { arg0 -> (this.apply(arg0 as CharSequence) as String) }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun superReferenceToANarrowedMethodIsNotCaptured() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        // Abstract, so that Kotlin lets it be extended
        val parent = ClassDef.builder("test.SuperTarget")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, String::class.java, String::class.java))
            .addMethod(apply)
            .build()
        val anyFunction = TypeDef.parameterized(
            java.util.function.Function::class.java, Any::class.java, Any::class.java)
        val child = ClassDef.builder("test.SuperReferencing")
            .superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(anyFunction)
                .build { aThis, _ -> anyFunction.methodReference(aThis.superRef(), apply).returning() })
            .build()

        val source = writeClass(child)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |
            |public class SuperReferencing : SuperTarget() {
            |  public fun asFunction(): Function<Any, Any> {
            |    return Function<Any, Any> { arg0 -> super.apply(arg0 as String) }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        val parentSource = writeClass(parent)
        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.String
            |
            |public abstract class SuperTarget : Function<String, String> {
            |  public override fun apply(arg0: String): String {
            |    return arg0 as String
            |  }
            |}
            |""".trimMargin(),
            parentSource
        )
        assertCompiles(parentSource, source)
    }

    @Test
    fun referenceResultsOfVariableAndPrimitiveTypes() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, parameters -> parameters[0].returning() }
        val variable = TypeDef.variable("U", TypeDef.of(Number::class.java))
        val variableFunction = TypeDef.parameterized(
            ClassTypeDef.of(java.util.function.Function::class.java), TypeDef.OBJECT, variable)
        val intFunction = TypeDef.parameterized(java.util.function.ToIntFunction::class.java, Any::class.java)
        // `apply` is written as `apply(Number): Number`: a `Function<Any, U>` returns it cast to `U`, and a
        // `ToIntFunction<Any>` to `Int`
        val classDef = ClassDef.builder("test.NumberReferenced")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(
                java.util.function.Function::class.java, Number::class.java, Number::class.java))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(variableFunction)
                .build { aThis, _ -> variableFunction.methodReference(aThis, apply).returning() })
            .addMethod(MethodDef.builder("asIntFunction").addModifiers(Modifier.PUBLIC)
                .returns(intFunction)
                .build { aThis, _ -> intFunction.methodReference(aThis, apply).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.lang.Number
            |import java.util.function.Function
            |import java.util.function.ToIntFunction
            |import kotlin.Any
            |import kotlin.Int
            |
            |public class NumberReferenced<U : Number> : Function<Number, Number> {
            |  public override fun apply(arg0: Number): Number {
            |    return arg0 as Number
            |  }
            |
            |  public fun asFunction(): Function<Any, U> {
            |    return Function<Any, U> { arg0 -> (this.apply(arg0 as Number) as U) }
            |  }
            |
            |  public fun asIntFunction(): ToIntFunction<Any> {
            |    return ToIntFunction<Any> { arg0 -> (this.apply(arg0 as Number) as Int) }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun arrayArgumentOfANarrowedParameter() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, _ -> ExpressionDef.constant("value").returning() }
        // `apply` is written as `apply(Array<String>)`, which an `Array<Any>` is cast to
        val classDef = ClassDef.builder("test.ArrayArguments")
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of(java.util.function.Function::class.java), TypeDef.STRING.array(), TypeDef.OBJECT))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array())
                .returns(Any::class.java)
                .build { aThis, parameters -> aThis.invoke(apply, parameters[0]).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.Array
            |import kotlin.String
            |
            |public class ArrayArguments : Function<Array<String>, Any> {
            |  public override fun apply(arg0: Array<String>): Any {
            |    return "value"
            |  }
            |
            |  public fun call(values: Array<Any>): Any {
            |    return this.apply(values as Array<String>)
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }

    @Test
    fun referenceArgumentKeepsItsParameterization() {
        val applyMethod = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
        val apply = MethodDef.override(applyMethod)
            .build { _, _ -> ExpressionDef.constant("value").returning() }
        val anyListFunction = TypeDef.parameterized(ClassTypeDef.of(java.util.function.Function::class.java),
            TypeDef.parameterized(List::class.java, Any::class.java), TypeDef.OBJECT)
        // `apply` is written as `apply(List<String>)`, which the `List<Any>` passed is cast to as a whole
        val classDef = ClassDef.builder("test.ListReferenced")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(java.util.function.Function::class.java),
                TypeDef.parameterized(List::class.java, String::class.java), TypeDef.OBJECT))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(anyListFunction)
                .build { aThis, _ -> anyListFunction.methodReference(aThis, apply).returning() })
            .build()

        val source = writeClass(classDef)

        assertEquals(
            """
            |package test
            |
            |import java.util.function.Function
            |import kotlin.Any
            |import kotlin.String
            |import kotlin.collections.List
            |
            |public class ListReferenced : Function<List<String>, Any> {
            |  public override fun apply(arg0: List<String>): Any {
            |    return "value"
            |  }
            |
            |  public fun asFunction(): Function<List<Any>, Any> {
            |    return Function<List<Any>, Any> { arg0 -> this.apply(arg0 as List<String>) }
            |  }
            |}
            |""".trimMargin(),
            source
        )
        assertCompiles(source)
    }
}
