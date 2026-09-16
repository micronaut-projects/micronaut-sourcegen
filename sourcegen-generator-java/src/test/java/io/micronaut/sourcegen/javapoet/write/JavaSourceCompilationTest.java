package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
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

    private static String writeSource(ObjectDef objectDef) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(objectDef, writer);
            return writer.toString();
        }
    }

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

    @Test
    void parameterizedArgumentOfARawParameterKeepsItsTypeArguments() throws Exception {
        var unmodifiableList = Collections.class.getMethod("unmodifiableList", List.class);
        var get = List.class.getMethod("get", int.class);
        // A cast to the raw type would make `get` return Object, which does not fit the declared return type
        ClassDef classDef = ClassDef.builder("test.Items")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.STRING))
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Collections.class)
                    .invokeStatic(unmodifiableList, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) items"), source);
        assertCompiles(source);
    }

    @Test
    void erasedOverrideOfABoundedTypeVariableCastsTheReturn() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("N", TypeDef.of(Number.class));
        InterfaceDef numeric = InterfaceDef.builder("test.Numeric")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable)
                .build())
            .build();
        var valueOf = Integer.class.getMethod("valueOf", int.class);
        // The model declares the erasure of the bound, `Number get()`, which as source has to be `Integer get()`
        ClassDef classDef = ClassDef.builder("test.Count")
            .addSuperinterface(TypeDef.parameterized(numeric.asTypeDef(), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .returns(Number.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Integer.class)
                    .invokeStatic(valueOf, ExpressionDef.constant(1))
                    .cast(TypeDef.of(Number.class))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("Integer get()"), source);
        assertCompiles(writeSource(numeric), source);
    }

    @Test
    void parameterizedArgumentOfATypeOnlyTheCompilerKnows() throws Exception {
        var unmodifiableList = Collections.class.getMethod("unmodifiableList", List.class);
        var get = List.class.getMethod("get", int.class);
        ClassDef item = ClassDef.builder("test.Item").addModifiers(Modifier.PUBLIC).build();
        ClassTypeDef itemType = ClassTypeDef.of("test.Item");
        // The generator cannot load `test.Item`, which does not make it a generic type used raw
        ClassDef classDef = ClassDef.builder("test.Items2")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(ClassTypeDef.of(List.class), itemType))
                .returns(itemType)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Collections.class)
                    .invokeStatic(unmodifiableList, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) items"), source);
        assertCompiles(writeClass(item), source);
    }

    @Test
    void parameterizedArgumentOfARawElementType() throws Exception {
        var unmodifiableList = Collections.class.getMethod("unmodifiableList", List.class);
        var get = List.class.getMethod("get", int.class);
        ClassTypeDef rawList = ClassTypeDef.of(List.class);
        // `List<List>` is inferred by `unmodifiableList(List<? extends T>)`, raw element type and all
        ClassDef classDef = ClassDef.builder("test.Items3")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(rawList, rawList))
                .returns(rawList)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Collections.class)
                    .invokeStatic(unmodifiableList, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) items"), source);
        assertCompiles(source);
    }

    @Test
    void argumentOfAGenericSubtypeOfTheDeclaredType() throws Exception {
        var unmodifiableList = Collections.class.getMethod("unmodifiableList", List.class);
        var get = List.class.getMethod("get", int.class);
        // `ArrayList<String>` is the `List<String>` that `unmodifiableList(List<? extends T>)` accepts
        ClassDef classDef = ClassDef.builder("test.Items4")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(ClassTypeDef.of(ArrayList.class), TypeDef.STRING))
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Collections.class)
                    .invokeStatic(unmodifiableList, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) items"), source);
        assertCompiles(source);
    }

    @Test
    void rawSupertypeLeavesTheErasedOverride() throws Exception {
        var get = Supplier.class.getMethod("get");
        // A raw `Supplier` has an erased `get`; its `T` is not the `T` the implementing class declares
        ClassDef classDef = ClassDef.builder("test.RawSupplier")
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(ClassTypeDef.of(Supplier.class))
            .addMethod(MethodDef.override(get)
                .build((aThis, methodParameters) -> ExpressionDef.constant("text").returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("Object get()"), source);
        assertCompiles(source);
    }

    @Test
    void blankFinalStaticFieldAssignedOrThrowingKeepsFinal() throws Exception {
        FieldDef value = FieldDef.builder("VALUE", TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassTypeDef type = ClassTypeDef.of("test.OrThrow");
        // The throwing branch does not complete, so every path that does has assigned the field
        ClassDef classDef = ClassDef.builder("test.OrThrow")
            .addField(value)
            .addStaticInitializer(ClassTypeDef.of(Boolean.class)
                .invokeStatic("getBoolean", TypeDef.Primitive.BOOLEAN, ExpressionDef.constant("flag"))
                .isTrue()
                .doIfElse(
                    type.getStaticField(value).put(ExpressionDef.constant("yes")),
                    ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("public static final String VALUE;"), source);
        assertCompiles(source);
    }

    @Test
    void typeArgumentsAreInvariant() throws Exception {
        var sum = CompilationSignatures.class.getMethod("sum", List.class);
        // `List<Integer>` is not a `List<Number>`: only the unchecked conversion accepts it
        ClassDef classDef = ClassDef.builder("test.Invariant")
            .addMethod(MethodDef.builder("total").addModifiers(Modifier.PUBLIC)
                .addParameter("numbers", TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.of(Integer.class)))
                .returns(int.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(sum, methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(List) numbers"), source);
        assertCompiles(source);
    }

    @Test
    void wildcardBoundKeepsItsTypeArguments() throws Exception {
        var firstSize = CompilationSignatures.class.getMethod("firstSize", List.class);
        TypeDef integers = TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.of(Integer.class));
        // `List<Integer>` is not within `? extends List<String>`
        ClassDef classDef = ClassDef.builder("test.Bounded")
            .addMethod(MethodDef.builder("size").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(ClassTypeDef.of(List.class), integers))
                .returns(int.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(firstSize, methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(List) lists"), source);
        assertCompiles(source);
    }

    @Test
    void arrayParameterDoesNotHideTheSignature() throws Exception {
        var join = CompilationSignatures.class.getMethod("join", String[].class, List.class);
        ClassDef classDef = ClassDef.builder("test.WithArray")
            .addMethod(MethodDef.builder("joined").addModifiers(Modifier.PUBLIC)
                .addParameter("parts", TypeDef.STRING.array())
                .addParameter("numbers", TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.of(Integer.class)))
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(join, methodParameters.get(0), methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(List) numbers"), source);
        assertCompiles(source);
    }

    @Test
    void wildcardArgumentWithinTheDeclaredWildcard() throws Exception {
        var unmodifiableList = Collections.class.getMethod("unmodifiableList", List.class);
        var get = List.class.getMethod("get", int.class);
        // `? extends String` lies within `? extends T`, which infers `T` as String
        ClassDef classDef = ClassDef.builder("test.Wildcards")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.parameterized(ClassTypeDef.of(List.class),
                    TypeDef.wildcardSubtypeOf(TypeDef.STRING)))
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(Collections.class)
                    .invokeStatic(unmodifiableList, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) values"), source);
        assertCompiles(source);
    }

    @Test
    void wildcardArgumentOutsideTheDeclaredWildcard() throws Exception {
        var firstSize = CompilationSignatures.class.getMethod("firstSize", List.class);
        TypeDef lists = TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcard());
        // `?` stands for any list, which `? extends List<String>` does not contain
        ClassDef classDef = ClassDef.builder("test.Unbounded")
            .addMethod(MethodDef.builder("size").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcardSubtypeOf(lists)))
                .returns(int.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(firstSize, methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(List) lists"), source);
        assertCompiles(source);
    }

    @Test
    void singleValueOfVarargsStaysAnElement() throws Exception {
        var format = String.class.getMethod("format", String.class, Object[].class);
        // A cast to Object[] would fail at runtime for any value that is not an array
        ClassDef classDef = ClassDef.builder("test.Formatted")
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(String.class)
                    .invokeStatic(format, ExpressionDef.constant("%s"), methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(Object[])"), source);
        assertCompiles(source);
    }

    @Test
    void concreteSubtypeWithinAParameterizedBound() throws Exception {
        var flatten = CompilationSignatures.class.getMethod("flatten", List.class);
        var get = List.class.getMethod("get", int.class);
        // `StringList` is the `List<String>` that `? extends List<T>` bounds, and infers `T` as String
        ClassDef classDef = ClassDef.builder("test.Flattened")
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.parameterized(ClassTypeDef.of(List.class),
                    ClassTypeDef.of(CompilationSignatures.StringList.class)))
                .returns(String.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(flatten, methodParameters.get(0))
                    .invoke(get, ExpressionDef.constant(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("(List) values"), source);
        assertCompiles(source);
    }

    @Test
    void lowerBoundKeepsItsTypeArguments() throws Exception {
        var addTo = CompilationSignatures.class.getMethod("addTo", List.class);
        TypeDef integers = TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.of(Integer.class));
        // `List<String>` is no subtype of `List<Integer>`, so `? super List<String>` does not take it
        ClassDef classDef = ClassDef.builder("test.LowerBound")
            .addMethod(MethodDef.builder("add").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(List.class), integers))
                .returns(int.class)
                .build((aThis, methodParameters) -> ClassTypeDef.of(CompilationSignatures.class)
                    .invokeStatic(addTo, methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(List) target"), source);
        assertCompiles(source);
    }

    @Test
    void erasedOverrideReturningABoundedTypeVariable() throws Exception {
        TypeDef.TypeVariable interfaceVariable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        InterfaceDef bounded = InterfaceDef.builder("test.Bounded")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(interfaceVariable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(interfaceVariable)
                .build())
            .build();
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        // The model declares the erasure, `CharSequence get()`, which as source is `T get()`
        ClassDef classDef = ClassDef.builder("test.BoundedBox")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), variable))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .returns(CharSequence.class)
                .build((aThis, methodParameters) -> ExpressionDef.constant("value")
                    .cast(TypeDef.of(CharSequence.class))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("T get()"), source);
        assertCompiles(writeSource(bounded), source);
    }

    @Test
    void blankFinalFieldNamedLikeACatchParameter() throws Exception {
        FieldDef failure = FieldDef.builder("e0", Throwable.class)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .build();
        ClassTypeDef type = ClassTypeDef.of("test.Caught");
        // The catch parameter takes another name, so that the assignment writes the field
        ClassDef classDef = ClassDef.builder("test.Caught")
            .addField(failure)
            .addStaticInitializer(StatementDef
                .doTry(ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())
                .doCatch(Exception.class, exception -> type.getStaticField(failure).put(exception)))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("catch (Exception e0)"), source);
        assertCompiles(source);
    }

    @Test
    void objectValueOfAGeneratedArrayParameter() throws Exception {
        // A generated method is not known to take varargs: its array parameter takes the value as an array
        MethodDef take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC)
            .addParameter("values", TypeDef.STRING.array())
            .build((aThis, methodParameters) -> StatementDef.multi());
        ClassDef classDef = ClassDef.builder("test.Takes")
            .addMethod(take)
            .addMethod(MethodDef.builder("dispatch").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(take, methodParameters.get(0))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("(String[]) value"), source);
        assertCompiles(source);
    }

    @Test
    void catchParameterAvoidsTheNamesInScope() throws Exception {
        FieldDef e0 = FieldDef.builder("e0", TypeDef.STRING)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(ExpressionDef.constant("field"))
            .build();
        // The field takes `e0` and the parameter `e1`, so the catch parameter is neither
        ClassDef classDef = ClassDef.builder("test.Scoped")
            .addField(e0)
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC)
                .addParameter("e1", String.class)
                .build((aThis, methodParameters) -> StatementDef
                    .doTry(ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())
                    .doCatch(Exception.class, exception -> StatementDef.multi())))
            .build();

        String source = writeClass(classDef);

        assertFalse(source.contains("Exception e0") || source.contains("Exception e1"), source);
        assertCompiles(source);
    }

    @Test
    void erasedOverrideNarrowingAnArrayReturnCastsIt() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        InterfaceDef bounded = InterfaceDef.builder("test.BoundedArray")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable.array())
                .build())
            .build();
        TypeDef sequences = TypeDef.of(CharSequence.class).array();
        FieldDef values = FieldDef.builder("values", sequences)
            .addModifiers(Modifier.PRIVATE)
            .initializer(TypeDef.STRING.array().instantiate(List.of(ExpressionDef.constant("a"))))
            .build();
        // The model declares the erasure, `CharSequence[] get()`, which as source is `String[] get()`
        ClassDef classDef = ClassDef.builder("test.Strings")
            .addField(values)
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides()
                .returns(sequences)
                .build((aThis, methodParameters) -> aThis.field(values).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("String[] get()"), source);
        assertTrue(source.contains("(String[])"), source);
        assertCompiles(writeSource(bounded), source);
    }

    @Test
    void narrowedParameterKeepsTheOverloadTheModelCalls() throws Exception {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("string").returning());
        var apply = Function.class.getMethod("apply", Object.class);
        // `apply(Object value)` becomes `apply(String value)`, where `choose(value)` would call `choose(String)`
        ClassDef classDef = ClassDef.builder("test.Chooser")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(chooseObject)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject, methodParameters.get(0)).returning()))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("String apply(String "), source);
        assertTrue(source.contains("choose((Object) "), source);
        assertCompiles(source);
    }

    @Test
    void expressionBodiedLambdaCastsItsValue() throws Exception {
        // The model's lambda returns its Object parameter where the implemented method returns String
        ExpressionDef.Lambda lambda = TypeDef.parameterized(Function.class, Object.class, String.class)
            .getLambda()
            .implement((aThis, parameters) -> parameters.get(0).returning());
        ClassDef classDef = ClassDef.builder("test.Lambdas")
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.parameterized(Function.class, Object.class, String.class))
                .build((aThis, methodParameters) -> lambda.returning()))
            .build();

        String source = writeClass(classDef);

        assertCompiles(source);
    }

    @Test
    void superConstructorArgumentIsCast() throws Exception {
        // `Exception(String)` is selected, which an Object value does not match as it is
        ClassDef classDef = ClassDef.builder("test.Failure")
            .superclass(ClassTypeDef.of(Exception.class))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .build((aThis, methodParameters) -> aThis.superRef(ClassTypeDef.of(Exception.class))
                    .invokeSuperConstructor(List.of(TypeDef.STRING), methodParameters.get(0))))
            .build();

        String source = writeClass(classDef);

        assertTrue(source.contains("super((String) value)"), source);
        assertCompiles(source);
    }
}
