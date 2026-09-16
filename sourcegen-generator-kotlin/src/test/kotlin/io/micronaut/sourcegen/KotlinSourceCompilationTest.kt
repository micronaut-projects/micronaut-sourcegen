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
import org.junit.jupiter.api.Assertions.assertTrue
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(classDef))
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

        assertCompiles(writeClass(other), writeClass(accessor))
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

        assertCompiles(writeClass(classDef))
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

        assertTrue(source.contains("compareTo(arg0: String)"), source)
        assertTrue(source.contains("`get`(): String"), source)
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

        assertTrue(source.contains("`get`(): T"), source)
        assertTrue(source.contains("accept(arg0: T)"), source)
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

        assertTrue(source.contains("names(): Set<String>"), source)
        assertCompiles(writeClass(names), source)
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

        assertTrue(source.contains("return\n"), source)
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

        assertTrue(source.contains("return\n"), source)
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

        assertTrue(!source.contains("lateinit"), source)
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

        assertTrue(source.contains("val name: String"), source)
        assertTrue(!source.contains("lateinit"), source)
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

        assertCompiles(writeClass(box), writeClass(strings))
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

        assertTrue(!source.contains("lateinit"), source)
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

        assertCompiles(writeClass(bounded), writeClass(classDef))
    }
}
