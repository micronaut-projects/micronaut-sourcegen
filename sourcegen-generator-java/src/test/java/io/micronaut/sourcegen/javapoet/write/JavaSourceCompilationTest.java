package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Models shaped like the ones written for bytecode, rendered as Java source that has to compile.
 */
class JavaSourceCompilationTest extends AbstractWriteTest {

    @Test
    void superConstructorWithExplicitSuperType() throws Exception {
        var objectConstructor = Object.class.getConstructor();
        ClassDef classDef = ClassDef.builder("test.Child")
            .superclass(ClassTypeDef.of(Object.class))
            .addMethod(MethodDef.constructor().build((aThis, methodParameters) ->
                aThis.superRef(ClassTypeDef.of(Object.class)).invokeConstructor(objectConstructor)))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("super();"), source);
        assertFalse(source.contains("Object.super"), source);
        assertCompiles(source);
    }

    @Test
    void superMethodOfSuperclassAndDefaultMethodOfInterface() throws Exception {
        ClassTypeDef iteratorType = ClassTypeDef.of(Iterator.class);
        var removeMethod = Iterator.class.getMethod("remove");
        var toStringMethod = Object.class.getMethod("toString");
        ClassDef classDef = ClassDef.builder("test.Iter")
            .addSuperinterface(iteratorType)
            .addMethod(MethodDef.builder("hasNext").addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .build((aThis, methodParameters) -> ExpressionDef.constant(false).returning()))
            .addMethod(MethodDef.builder("next").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.builder("remove").addModifiers(Modifier.PUBLIC)
                .build((aThis, methodParameters) ->
                    aThis.superRef(iteratorType).invoke(removeMethod)))
            .addMethod(MethodDef.builder("toString").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((aThis, methodParameters) ->
                    aThis.superRef(ClassTypeDef.of(Object.class)).invoke(toStringMethod).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("Iterator.super.remove()"), source);
        assertTrue(source.contains("return super.toString();"), source);
        assertCompiles(source);
    }

    @Test
    void blankFinalStaticFieldsAssignedInTryCatch() throws IOException {
        ClassTypeDef type = ClassTypeDef.of("test.Holder");
        FieldDef value = FieldDef.builder("VALUE", String.class)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .build();
        FieldDef failure = FieldDef.builder("FAILURE", Throwable.class)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassDef classDef = ClassDef.builder(type.getName())
            .addField(value)
            .addField(failure)
            .addStaticInitializer(StatementDef.multi(
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("a")))
                    .doCatch(Throwable.class, exceptionVar -> type.getStaticField(failure).put(exceptionVar)),
                StatementDef.doTry(type.getStaticField(value).put(ExpressionDef.constant("b")))
                    .doCatch(Throwable.class, exceptionVar -> type.getStaticField(value).put(ExpressionDef.constant("c")))
            ))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("private static String VALUE;"), source);
        assertTrue(source.contains("private static Throwable FAILURE;"), source);
        assertCompiles(source);
    }

    @Test
    void namesContainingDollarSigns() throws IOException {
        FieldDef field = FieldDef.builder("$field", String.class).addModifiers(Modifier.PRIVATE).build();
        MethodDef getter = MethodDef.builder("$get").returns(String.class)
            .build((aThis, methodParameters) -> aThis.field(field).returning());
        ClassDef classDef = ClassDef.builder("test.$Holder$Definition")
            .addField(field)
            .addMethod(getter)
            .addMethod(MethodDef.builder("$copy").addParameter("$value", String.class).returns(String.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    aThis.field(field).put(methodParameters.get(0)),
                    aThis.invoke(getter).newLocal("$local", local -> local.returning())
                )))
            .build();

        String source = writeClass(classDef);

        assertCompiles(source);
    }

    @Test
    void objectTypedArgumentsAndReturnValuesAreCast() throws Exception {
        MethodDef take = MethodDef.builder("take").addParameter("text", String.class).build();
        var stringBuilderConstructor = StringBuilder.class.getConstructor(String.class);
        ClassDef classDef = ClassDef.builder("test.Dispatch")
            .addMethod(take)
            .addMethod(MethodDef.builder("dispatch").addParameter("value", Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(take, methodParameters.get(0))))
            .addMethod(MethodDef.builder("create").addParameter("value", Object.class).returns(StringBuilder.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(StringBuilder.class)
                    .instantiate(stringBuilderConstructor, methodParameters.get(0))
                    .returning()))
            .addMethod(MethodDef.builder("narrow").addParameter("value", Object.class).returns(String.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("this.take((String) value)"), source);
        assertTrue(source.contains("new java.lang.StringBuilder((String) value)"), source);
        assertTrue(source.contains("return (String) value;"), source);
        assertCompiles(source);
    }

    @Test
    void returningVoidInvocationAndUnreachableFallback() throws IOException {
        MethodDef run = MethodDef.builder("run").build();
        ClassDef classDef = ClassDef.builder("test.Flow")
            .addMethod(run)
            .addMethod(MethodDef.builder("delegate")
                .build((aThis, methodParameters) -> aThis.invoke(run).returning()))
            .addMethod(MethodDef.builder("select").addParameter("index", int.class).returns(Object.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).asStatementSwitch(
                        TypeDef.OBJECT,
                        Map.of(ExpressionDef.constant(0), ExpressionDef.constant("zero").returning()),
                        ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()
                    ),
                    ExpressionDef.nullValue().returning()
                )))
            .addMethod(MethodDef.builder("guarded").returns(Object.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    StatementDef.doTry(ExpressionDef.constant("value").returning())
                        .doCatch(Throwable.class, exceptionVar -> ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()),
                    ExpressionDef.nullValue().returning()
                )))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("return this.run()"), source);
        assertFalse(source.contains("return null;"), source);
        assertCompiles(source);
    }

    @Test
    void instanceOfNestedType() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Check")
            .addMethod(MethodDef.builder("isEntry").addParameter("value", Object.class).returns(boolean.class)
                .build((aThis, methodParameters) ->
                    new ExpressionDef.InstanceOf(methodParameters.get(0), ClassTypeDef.of(Map.Entry.class)).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("instanceof java.util.Map.Entry"), source);
        assertCompiles(source);
    }

    @Test
    void fieldOfAnotherType() throws IOException {
        ClassTypeDef otherType = ClassTypeDef.of("test.Other");
        ClassDef other = ClassDef.builder(otherType.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("name", String.class).addModifiers(Modifier.PUBLIC).build())
            .build();
        ClassDef accessor = ClassDef.builder("test.Accessor")
            .addMethod(MethodDef.builder("read").addParameter("value", Object.class).returns(String.class)
                .build((aThis, methodParameters) -> new VariableDef.Field(
                    methodParameters.get(0).cast(otherType), otherType, "name", TypeDef.STRING).returning()))
            .build();

        assertCompiles(writeClass(other), writeClass(accessor));
    }

    @Test
    void arrayAnnotationMemberAndMultiDimensionalArrays() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Arrays2")
            .addAnnotation(AnnotationDef.builder(SuppressWarnings.class)
                .addMember("value", new String[]{"unchecked", "rawtypes"})
                .build())
            .addMethod(MethodDef.builder("matrix").returns(TypeDef.STRING.array(2))
                .build((aThis, methodParameters) -> TypeDef.STRING.array(2).instantiate(List.of(
                    TypeDef.STRING.array().instantiate(List.of(ExpressionDef.constant("a")))
                )).returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("[Ljava.lang.String;"), source);
        assertTrue(source.contains("new String[][]{"), source);
        assertCompiles(source);
    }

    @Test
    void erasedOverridesOfGenericInterfacesTakeTheTypeArguments() throws Exception {
        var compareTo = Comparable.class.getMethod("compareTo", Object.class);
        var get = Supplier.class.getMethod("get");
        ClassDef classDef = ClassDef.builder("test.Typed")
            .addSuperinterface(TypeDef.parameterized(Comparable.class, String.class))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            // The erased signatures a model written for bytecode declares
            .addMethod(MethodDef.override(compareTo)
                .build((aThis, methodParameters) -> ExpressionDef.constant(0).returning()))
            .addMethod(MethodDef.override(get)
                .build((aThis, methodParameters) -> ExpressionDef.constant("value").cast(TypeDef.OBJECT).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("compareTo(String "), source);
        assertTrue(source.contains("String get()"), source);
        assertCompiles(source);
    }

    @Test
    void erasedOverridesWithTheTypeVariablesOfTheDeclaringType() throws Exception {
        var get = Supplier.class.getMethod("get");
        var accept = Consumer.class.getMethod("accept", Object.class);
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        ClassDef classDef = ClassDef.builder("test.Box")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), variable))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), variable))
            // `Object get()` does not implement `T get()`; an erased parameter is a valid override as is
            .addMethod(MethodDef.override(get)
                .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning()))
            .addMethod(MethodDef.override(accept)
                .build((aThis, methodParameters) -> StatementDef.multi()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("T get()"), source);
        assertTrue(source.contains("accept(Object "), source);
        assertCompiles(source);
    }

    @Test
    void staticFieldOfTheWrittenClassIsQualified() throws Exception {
        FieldDef value = FieldDef.builder("value", TypeDef.STRING)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .initializer(ExpressionDef.constant("static"))
            .build();
        ClassTypeDef shadowType = ClassTypeDef.of("test.Shadow");
        // A parameter of the same name shadows an unqualified reference to the field
        ClassDef classDef = ClassDef.builder("test.Shadow")
            .addField(value)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC)
                .addParameter("value", String.class)
                .returns(String.class)
                .build((aThis, methodParameters) -> shadowType.getStaticField(value).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("return Shadow.value;"), source);
        assertCompiles(source);
    }

    @Test
    void returningAVoidInvocationInsideACondition() throws Exception {
        MethodDef run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build((aThis, methodParameters) -> StatementDef.multi());
        // Without the return, the branch falls through and the call below it runs as well
        ClassDef classDef = ClassDef.builder("test.Branch")
            .addMethod(run)
            .addMethod(MethodDef.builder("dispatch").addModifiers(Modifier.PUBLIC)
                .addParameter("stop", boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isTrue().doIf(aThis.invoke(run).returning()),
                    aThis.invoke(run))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("return;"), source);
        assertCompiles(source);
    }

    @Test
    void blankFinalStaticFieldKeepsFinalWhereItIsAssignedOnce() throws Exception {
        FieldDef kept = FieldDef.builder("KEPT", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        FieldDef fallback = FieldDef.builder("FALLBACK", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build();
        ClassTypeDef type = ClassTypeDef.of("test.Statics");
        ClassDef classDef = ClassDef.builder("test.Statics")
            .addField(kept)
            .addField(fallback)
            // The second field is assigned in both a try and its catch, which definite assignment rejects
            .addStaticInitializer(StatementDef.multi(
                type.getStaticField(kept).put(ExpressionDef.constant("kept")),
                StatementDef.doTry(type.getStaticField(fallback).put(ExpressionDef.constant("value")))
                    .doCatch(Throwable.class, exception ->
                        type.getStaticField(fallback).put(ExpressionDef.constant("fallback")))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("public static final String KEPT;"), source);
        assertTrue(source.contains("public static String FALLBACK;"), source);
        assertCompiles(source);
    }

    @Test
    void emptyArrayAnnotationMemberIsWritten() throws Exception {
        ClassDef classDef = ClassDef.builder("test.EmptyMember")
            .addAnnotation(AnnotationDef.builder(SuppressWarnings.class)
                .addMember("value", new String[0])
                .build())
            .build();

        String source = writeClass(classDef);

        // Without a value the member is omitted, and `@SuppressWarnings` alone does not compile
        assertTrue(source.contains("{}"), source);
        assertCompiles(source);
    }

    @Test
    void returningFromAFinallyBlock() throws Exception {
        MethodDef run = MethodDef.builder("run").addModifiers(Modifier.PRIVATE)
            .build((aThis, methodParameters) -> StatementDef.multi());
        // The return discards the exception of the try; dropping it would let the exception out
        ClassDef classDef = ClassDef.builder("test.Finally")
            .addMethod(run)
            .addMethod(MethodDef.builder("guarded").addModifiers(Modifier.PUBLIC)
                .build((aThis, methodParameters) -> StatementDef
                    .doTry(ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())
                    .doFinally(StatementDef.multi(aThis.invoke(run), aThis.invoke(run).returning()))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("return;"), source);
        assertCompiles(source);
    }

    @Test
    void staticFieldAssignedBesideALocalOfTheSameName() throws Exception {
        FieldDef value = FieldDef.builder("value", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build();
        ClassTypeDef type = ClassTypeDef.of("test.LocalShadow");
        // A local of the same name would take over an unqualified assignment, leaving the field unwritten
        ClassDef classDef = ClassDef.builder("test.LocalShadow")
            .addField(value)
            .addStaticInitializer(ExpressionDef.constant(1).newLocal("value", local -> StatementDef.multi(
                type.getStaticField(value).put(ExpressionDef.constant(7)),
                type.getStaticField(value).put(local)
            )))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("LocalShadow.value = 7;"), source);
        assertCompiles(source);
    }

    @Test
    void blankFinalStaticFieldAssignedInEachBranchKeepsFinal() throws Exception {
        FieldDef value = FieldDef.builder("VALUE", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassTypeDef type = ClassTypeDef.of("test.Branches");
        // Mutually exclusive branches assign the field exactly once, which definite assignment accepts
        ClassDef classDef = ClassDef.builder("test.Branches")
            .addField(value)
            .addStaticInitializer(ClassTypeDef.of(Boolean.class)
                .invokeStatic("getBoolean", TypeDef.Primitive.BOOLEAN, ExpressionDef.constant("flag"))
                .isTrue()
                .doIfElse(
                    type.getStaticField(value).put(ExpressionDef.constant("yes")),
                    type.getStaticField(value).put(ExpressionDef.constant("no"))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("public static final String VALUE;"), source);
        assertCompiles(source);
    }

    @Test
    void blankFinalStaticFieldAssignedInACatchAndAFinally() throws Exception {
        FieldDef value = FieldDef.builder("VALUE", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassTypeDef type = ClassTypeDef.of("test.CatchAndFinally");
        // The catch may have assigned the field before the finally does, which definite assignment rejects
        ClassDef classDef = ClassDef.builder("test.CatchAndFinally")
            .addField(value)
            .addStaticInitializer(StatementDef
                .doTry(ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())
                .doCatch(Throwable.class, exception -> type.getStaticField(value).put(ExpressionDef.constant("a")))
                .doFinally(type.getStaticField(value).put(ExpressionDef.constant("b"))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("public static String VALUE;"), source);
        assertCompiles(source);
    }

    @Test
    void assignmentOfTheSameFieldNameOfAnotherType() throws Exception {
        FieldDef external = FieldDef.builder("VALUE", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build();
        ClassDef other = ClassDef.builder("test.External")
            .addModifiers(Modifier.PUBLIC)
            .addField(external)
            .build();
        FieldDef value = FieldDef.builder("VALUE", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        // The static initializer assigns the field of the other type, leaving this one uninitialized
        ClassDef classDef = ClassDef.builder("test.Owner")
            .addField(value)
            .addStaticInitializer(other.asTypeDef().getStaticField(external).put(ExpressionDef.constant("a")))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("public static String VALUE;"), source);
        assertCompiles(writeClass(other), source);
    }
}
