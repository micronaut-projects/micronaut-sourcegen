package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(
            """
            package test;

            class Child {
              Child() {
                super();
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.Iterator;

            class Iter implements Iterator {
              public boolean hasNext() {
                return false;
              }

              public Object next() {
                return null;
              }

              public void remove() {
                Iterator.super.remove();
              }

              public String toString() {
                return super.toString();
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.lang.Throwable;

            class Holder {
              private static String VALUE;

              private static Throwable FAILURE;

              static {
                try {
                  Holder.VALUE = "a";
                } catch (Throwable e0) {
                  Holder.FAILURE = e0;
                }
                try {
                  Holder.VALUE = "b";
                } catch (Throwable e0) {
                  Holder.VALUE = "c";
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;

            class $Holder$Definition {
              private String $field;

              String $get() {
                return this.$field;
              }

              String $copy(String $value) {
                this.$field = $value;
                String $local = this.$get();
                return $local;
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.lang.StringBuilder;

            class Dispatch {
              void take(String text) {
              }

              void dispatch(Object value) {
                this.take((String) value);
              }

              StringBuilder create(Object value) {
                return new java.lang.StringBuilder((String) value);
              }

              String narrow(Object value) {
                return (String) value;
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.Throwable;

            class Flow {
              void run() {
              }

              void delegate() {
                this.run();
              }

              Object select(int index) {
                switch (index) {
                  case 0 -> {
                    return "zero";
                  }
                  default -> {
                    throw new java.lang.IllegalStateException();
                  }
                }
              }

              Object guarded() {
                try {
                  return "value";
                } catch (Throwable e0) {
                  throw new java.lang.IllegalStateException();
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;

            class Check {
              boolean isEntry(Object value) {
                return value instanceof java.util.Map.Entry;
              }
            }
            """, source);
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

        String otherSource = writeClass(other);
        assertEquals(
            """
            package test;

            import java.lang.String;

            public class Other {
              public String name;
            }
            """, otherSource);
        String accessorSource = writeClass(accessor);
        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class Accessor {
              String read(Object value) {
                return ((Other) value).name;
              }
            }
            """, accessorSource);
        assertCompiles(otherSource, accessorSource);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.lang.SuppressWarnings;

            @SuppressWarnings({
                "unchecked",
                "rawtypes"
            })
            class Arrays2 {
              String[][] matrix() {
                return new String[][]{new String[]{"a"}};
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Comparable;
            import java.lang.String;
            import java.util.function.Supplier;

            class Typed implements Comparable<String>, Supplier<String> {
              public int compareTo(String arg0) {
                return 0;
              }

              public String get() {
                return "value";
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.util.function.Consumer;
            import java.util.function.Supplier;

            class Box<T> implements Supplier<T>, Consumer<T> {
              public T get() {
                return null;
              }

              public void accept(Object arg0) {
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;

            class Shadow {
              private static String value = "static";

              public String get(String value) {
                return Shadow.value;
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            class Branch {
              private void run() {
              }

              public void dispatch(boolean stop) {
                if (stop) {
                  this.run();
                  return;
                }
                this.run();
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.lang.Throwable;

            class Statics {
              public static final String KEPT;

              public static String FALLBACK;

              static {
                KEPT = "kept";
                try {
                  Statics.FALLBACK = "value";
                } catch (Throwable e0) {
                  Statics.FALLBACK = "fallback";
                }
              }
            }
            """, source);
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
        assertEquals(
            """
            package test;

            import java.lang.SuppressWarnings;

            @SuppressWarnings({})
            class EmptyMember {
            }
            """, source);
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

        assertEquals(
            """
            package test;

            class Finally {
              private void run() {
              }

              public void guarded() {
                try {
                  throw new java.lang.IllegalStateException();
                } finally {
                  this.run();
                  this.run();
                  return;
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            class LocalShadow {
              public static int value;

              static {
                int value = 1;
                LocalShadow.value = 7;
                LocalShadow.value = value;
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Boolean;
            import java.lang.String;

            class Branches {
              public static final String VALUE;

              static {
                if (Boolean.getBoolean("flag")) {
                  VALUE = "yes";
                } else {
                  VALUE = "no";
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.lang.Throwable;

            class CatchAndFinally {
              public static String VALUE;

              static {
                try {
                  throw new java.lang.IllegalStateException();
                } catch (Throwable e0) {
                  CatchAndFinally.VALUE = "a";
                } finally {
                  CatchAndFinally.VALUE = "b";
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;

            class Owner {
              public static String VALUE;

              static {
                External.VALUE = "a";
              }
            }
            """, source);
        String otherSource = writeClass(other);
        assertEquals(
            """
            package test;

            import java.lang.String;

            public class External {
              public static String VALUE;
            }
            """, otherSource);
        assertCompiles(otherSource, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.Collections;
            import java.util.List;

            class Items {
              public String first(List<String> items) {
                return (String) Collections.unmodifiableList(items).get(0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Integer;

            class Count implements Numeric<Integer> {
              public Integer get() {
                return Integer.valueOf(1);
              }
            }
            """, source);
        String numericSource = render(numeric);
        assertEquals(
            """
            package test;

            import java.lang.Number;

            public interface Numeric<N extends Number> {
              N get();
            }
            """, numericSource);
        assertCompiles(numericSource, source);
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

        assertEquals(
            """
            package test;

            import java.util.Collections;
            import java.util.List;

            class Items2 {
              public Item first(List<Item> items) {
                return (Item) Collections.unmodifiableList(items).get(0);
              }
            }
            """, source);
        String itemSource = writeClass(item);
        assertEquals(
            """
            package test;

            public class Item {
            }
            """, itemSource);
        assertCompiles(itemSource, source);
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

        assertEquals(
            """
            package test;

            import java.util.Collections;
            import java.util.List;

            class Items3 {
              public List first(List<List> items) {
                return (List) Collections.unmodifiableList(items).get(0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.ArrayList;
            import java.util.Collections;

            class Items4 {
              public String first(ArrayList<String> items) {
                return (String) Collections.unmodifiableList(items).get(0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Number;
            import java.lang.Object;
            import java.util.function.Supplier;

            class RawSupplier<T extends Number> implements Supplier {
              public Object get() {
                return "text";
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Boolean;
            import java.lang.String;

            class OrThrow {
              public static final String VALUE;

              static {
                if (Boolean.getBoolean("flag")) {
                  VALUE = "yes";
                } else {
                  throw new java.lang.IllegalStateException();
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Integer;
            import java.util.List;

            class Invariant {
              public int total(List<Integer> numbers) {
                return CompilationSignatures.sum((List) numbers);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Integer;
            import java.util.List;

            class Bounded {
              public int size(List<List<Integer>> lists) {
                return CompilationSignatures.firstSize((List) lists);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Integer;
            import java.lang.String;
            import java.util.List;

            class WithArray {
              public String joined(String[] parts, List<Integer> numbers) {
                return CompilationSignatures.join(parts, (List) numbers);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.Collections;
            import java.util.List;

            class Wildcards {
              public String first(List<? extends String> values) {
                return (String) Collections.unmodifiableList(values).get(0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.util.List;

            class Unbounded {
              public int size(List<? extends List<?>> lists) {
                return CompilationSignatures.firstSize((List) lists);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class Formatted {
              public String describe(Object value) {
                return String.format("%s", value);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.String;
            import java.util.List;

            class Flattened {
              public String first(List<CompilationSignatures.StringList> values) {
                return (String) CompilationSignatures.flatten(values).get(0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Integer;
            import java.util.List;

            class LowerBound {
              public int add(List<List<Integer>> target) {
                return CompilationSignatures.addTo((List) target);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;

            class BoundedBox<T extends CharSequence> implements Bounded<T> {
              public T get() {
                return (T) "value";
              }
            }
            """, source);
        String boundedSource = render(bounded);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;

            public interface Bounded<T extends CharSequence> {
              T get();
            }
            """, boundedSource);
        assertCompiles(boundedSource, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Exception;
            import java.lang.Throwable;

            class Caught {
              public static final Throwable e0;

              static {
                try {
                  throw new java.lang.IllegalStateException();
                } catch (Exception e1) {
                  e0 = e1;
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class Takes {
              public void take(String[] values) {
              }

              public void dispatch(Object value) {
                this.take((String[]) value);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Exception;
            import java.lang.String;

            class Scoped {
              private static final String e0 = "field";

              public void run(String e1) {
                try {
                  throw new java.lang.IllegalStateException();
                } catch (Exception e2) {
                }
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.String;

            class Strings implements BoundedArray<String> {
              private CharSequence[] values = new String[]{"a"};

              public String[] get() {
                return (String[]) this.values;
              }
            }
            """, source);
        String boundedSource = render(bounded);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;

            public interface BoundedArray<T extends CharSequence> {
              T[] get();
            }
            """, boundedSource);
        assertCompiles(boundedSource, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class Chooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) arg0);
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class Lambdas {
              public Function<Object, String> identity() {
                return (arg0) -> (String) arg0;
              }
            }
            """, source);
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

        assertEquals(
            """
            package test;

            import java.lang.Exception;
            import java.lang.Object;
            import java.lang.String;

            class Failure extends Exception {
              public Failure(Object value) {
                super((String) value);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void narrowedParameterPassedAsAVarargsElement() throws Exception {
        var format = String.class.getMethod("format", String.class, Object[].class);
        var apply = Function.class.getMethod("apply", Object.class);
        // The narrowed `String value` is one element of the varargs, not an array to cast to
        ClassDef classDef = ClassDef.builder("test.Formats")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> ClassTypeDef.of(String.class)
                    .invokeStatic(format, ExpressionDef.constant("%s"), methodParameters.get(0))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            class Formats implements Function<String, String> {
              public String apply(String arg0) {
                return String.format("%s", arg0);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void superConstructorVarargsResolvedThroughTheSuperclass() throws Exception {
        ClassTypeDef parent = ClassTypeDef.of(CompilationSignatures.VarargsParent.class);
        // `superRef()` names the superclass by a placeholder, which is resolved to find the varargs constructor
        ClassDef classDef = ClassDef.builder("test.VarargsChild")
            .superclass(parent)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .build((aThis, methodParameters) -> aThis.superRef()
                    .invokeSuperConstructor(List.of(TypeDef.OBJECT.array()), methodParameters.get(0))))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Object;

            class VarargsChild extends CompilationSignatures.VarargsParent {
              public VarargsChild(Object value) {
                super(value);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void narrowedParameterKeepsTheOverloadThroughADroppedCast() throws Exception {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("string").returning());
        var apply = Function.class.getMethod("apply", Object.class);
        // The cast to Object is to the type the value already has in the model, which rendering drops
        ClassDef classDef = ClassDef.builder("test.CastChooser")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(chooseObject)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    methodParameters.get(0).cast(TypeDef.OBJECT)).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class CastChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) arg0);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callOfANarrowedMethodOfTheClassIsConverted() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        // `apply` is written as `apply(String)`, which the Object value passed to it is converted to
        ClassDef classDef = ClassDef.builder("test.Caller")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(apply, methodParameters.get(0)).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class Caller implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }

              public Object call(Object value) {
                return this.apply((String) value);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void narrowedArrayStaysOneVarargsElement() throws Exception {
        var asList = java.util.Arrays.class.getMethod("asList", Object[].class);
        var size = List.class.getMethod("size");
        var apply = Function.class.getMethod("apply", Object.class);
        // The model passes an Object, one element; the narrowed `String[]` would be spread into the varargs
        ClassDef classDef = ClassDef.builder("test.Elements")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class),
                TypeDef.STRING.array(), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> ClassTypeDef.of(java.util.Arrays.class)
                    .invokeStatic(asList, methodParameters.get(0))
                    .invoke(size)
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Integer;
            import java.lang.Object;
            import java.lang.String;
            import java.util.Arrays;
            import java.util.function.Function;

            class Elements implements Function<String[], Integer> {
              public Integer apply(String[] arg0) {
                return Arrays.asList((Object) arg0).size();
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callOfANarrowedMethodOfAnotherGeneratedClassIsConverted() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef target = ClassDef.builder("test.Target")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        // `Target.apply` is written as `apply(String)`, which the caller's Object value is converted to
        ClassDef caller = ClassDef.builder("test.OtherCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", target.asTypeDef())
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class OtherCaller {
              public Object call(Target target, Object value) {
                return target.apply((String) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public class Target implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void narrowedResultKeepsTheOverloadTheModelCalls() throws Exception {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("string").returning());
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        // `apply` returns String once written, where `choose(this.apply(value))` would call `choose(String)`
        ClassDef classDef = ClassDef.builder("test.ResultChooser")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(chooseObject)
            .addMethod(chooseString)
            .addMethod(apply)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
                .addParameter("value", Object.class)
                .returns(String.class)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    aThis.invoke(apply, methodParameters.get(0))).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class ResultChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return (String) arg0;
              }

              public String pick(Object value) {
                return this.choose((Object) this.apply((String) value));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callThroughAParameterizedReceiverTakesItsTypeArguments() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        ClassDef target = ClassDef.builder("test.GenericTarget")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), variable, variable))
            .addMethod(apply)
            .build();
        // `apply(T)` on a `GenericTarget<String>` takes a String
        ClassDef caller = ClassDef.builder("test.GenericCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(target.asTypeDef(), TypeDef.STRING))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class GenericCaller {
              public Object call(GenericTarget<String> target, Object value) {
                return target.apply((String) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class GenericTarget<T extends CharSequence> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void conditionalOfNarrowedParametersKeepsTheOverload() throws Exception {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("string").returning());
        var apply = Function.class.getMethod("apply", Object.class);
        // Both branches are the narrowed parameter, so the conditional is a String in the source
        ClassDef classDef = ClassDef.builder("test.ConditionalChooser")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(chooseObject)
            .addMethod(chooseString)
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    ExpressionDef.constant(true).isTrue()
                        .doIfElse(methodParameters.get(0), methodParameters.get(0))).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class ConditionalChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) (true ? arg0 : arg0));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callThroughAReceiverNamingTheCallersVariable() throws Exception {
        ClassDef target = genericTarget("test.CallerScopedTarget");
        MethodDef apply = target.getMethods().get(0);
        TypeDef.TypeVariable variable = TypeDef.variable("U", TypeDef.of(CharSequence.class));
        // `apply(T)` on a `CallerScopedTarget<U>` takes the caller's `U`
        ClassDef caller = ClassDef.builder("test.ScopedCaller")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(target.asTypeDef(), variable))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class ScopedCaller<U extends CharSequence> {
              public Object call(CallerScopedTarget<U> target, Object value) {
                return target.apply((U) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class CallerScopedTarget<T extends CharSequence> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void callThroughARawReceiverTakesTheErasure() throws Exception {
        ClassDef target = genericTarget("test.RawTarget");
        MethodDef apply = target.getMethods().get(0);
        // `apply(T)` on a raw `RawTarget` takes the erasure of `T`
        ClassDef caller = ClassDef.builder("test.RawCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", ClassTypeDef.of(target))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class RawCaller {
              public Object call(RawTarget target, Object value) {
                return target.apply((CharSequence) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class RawTarget<T extends CharSequence> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void callOfAnInheritedNarrowedMethodIsConverted() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef parent = ClassDef.builder("test.ParentTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        ClassDef child = ClassDef.builder("test.ChildTarget")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parent.asTypeDef())
            .build();
        // `ChildTarget` inherits `apply(String)`, which the caller's Object value is converted to
        ClassDef caller = ClassDef.builder("test.ChildCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", child.asTypeDef())
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class ChildCaller {
              public Object call(ChildTarget target, Object value) {
                return target.apply((String) value);
              }
            }
            """, source);
        String parentSource = writeClass(parent);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public class ParentTarget implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, parentSource);
        String childSource = writeClass(child);
        assertEquals(
            """
            package test;

            public class ChildTarget extends ParentTarget {
            }
            """, childSource);
        assertCompiles(parentSource, childSource, source);
    }

    @Test
    void conditionalWithANullBranchKeepsTheOverload() throws Exception {
        var apply = Function.class.getMethod("apply", Object.class);
        // The branch other than `null` is the narrowed parameter, so the conditional is a String in the source
        ClassDef classDef = chooser("test.NullConditionalChooser", chooseObject -> MethodDef.override(apply)
            .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                ExpressionDef.constant(true).isTrue()
                    .doIfElse(methodParameters.get(0), ExpressionDef.nullValue())).returning()));

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class NullConditionalChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) (true ? arg0 : null));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void switchOfNarrowedParametersKeepsTheOverload() throws Exception {
        var apply = Function.class.getMethod("apply", Object.class);
        // Every case is the narrowed parameter, so the switch is a String in the source
        ClassDef classDef = chooser("test.SwitchChooser", chooseObject -> MethodDef.override(apply)
            .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                ExpressionDef.constant(1).asExpressionSwitch(TypeDef.OBJECT,
                    Map.of(ExpressionDef.constant(1), methodParameters.get(0)),
                    methodParameters.get(0))).returning()));

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class SwitchChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) (switch (1) {
                  case 1 -> arg0;
                  default -> arg0;
                }));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void elementOfANarrowedArrayKeepsTheOverload() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        InterfaceDef arrays = InterfaceDef.builder("test.ArrayChoice")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("accept").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("values", variable.array())
                .returns(String.class)
                .build())
            .build();
        // `accept(Object[] values)` becomes `accept(String[] values)`, where `choose(values[0])` would call
        // `choose(String)`
        ClassDef classDef = chooser("test.ArrayChooser", TypeDef.parameterized(arrays.asTypeDef(), TypeDef.STRING),
            chooseObject -> MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("values", TypeDef.OBJECT.array())
                .returns(String.class)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    methodParameters.get(0).arrayElement(0)).returning()));

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class ArrayChooser implements ArrayChoice<String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String accept(String[] values) {
                return this.choose((Object) values[0]);
              }
            }
            """, source);
        String arraysSource = render(arrays);
        assertEquals(
            """
            package test;

            import java.lang.String;

            public interface ArrayChoice<T> {
              String accept(T[] values);
            }
            """, arraysSource);
        assertCompiles(arraysSource, source);
    }

    @Test
    void referenceToANarrowedMethodConvertsItsArguments() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassTypeDef objectFunction = TypeDef.parameterized(Function.class, Object.class, Object.class);
        // `apply` is written as `apply(String)`, which a `Function<Object, Object>` cannot reference
        ClassDef classDef = ClassDef.builder("test.ReferencedFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(objectFunction)
                .build((aThis, methodParameters) -> objectFunction.methodReference(aThis, apply).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class ReferencedFunction implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }

              public Function<Object, Object> asFunction() {
                return (arg) -> this.apply((String) arg);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callThroughAReceiverNamingTheCallingMethodsVariable() throws Exception {
        ClassDef target = genericTarget("test.MethodScopedTarget");
        MethodDef apply = target.getMethods().get(0);
        TypeDef.TypeVariable variable = TypeDef.variable("U", TypeDef.of(CharSequence.class));
        // `apply(T)` on a `MethodScopedTarget<U>` takes the calling method's `U`
        ClassDef caller = ClassDef.builder("test.MethodScopedCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(variable)
                .addParameter("target", TypeDef.parameterized(target.asTypeDef(), variable))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class MethodScopedCaller {
              public <U extends CharSequence> Object call(MethodScopedTarget<U> target, Object value) {
                return target.apply((U) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class MethodScopedTarget<T extends CharSequence> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void callThroughAReceiverNamingAVariableOutOfScope() throws Exception {
        ClassDef target = genericTarget("test.UnboundTarget");
        MethodDef apply = target.getMethods().get(0);
        // The receiver names the target's own `T`, written where it is out of scope as its bound
        ClassDef caller = ClassDef.builder("test.UnboundCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", target.asTypeDef())
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class UnboundCaller {
              public Object call(UnboundTarget<CharSequence> target, Object value) {
                return target.apply((CharSequence) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class UnboundTarget<T extends CharSequence> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void callOfAnInheritedNarrowedDefaultMethodIsConverted() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .addModifiers(Modifier.DEFAULT)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        InterfaceDef parent = InterfaceDef.builder("test.DefaultTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        ClassDef implementation = ClassDef.builder("test.DefaultTargetImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(parent.asTypeDef())
            .build();
        // `DefaultTargetImpl` inherits the default `apply(String)`, which the caller's Object value is converted to
        ClassDef caller = ClassDef.builder("test.DefaultCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", implementation.asTypeDef())
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class DefaultCaller {
              public Object call(DefaultTargetImpl target, Object value) {
                return target.apply((String) value);
              }
            }
            """, source);
        String parentSource = render(parent);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public interface DefaultTarget extends Function<String, String> {
              default String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, parentSource);
        String implementationSource = writeClass(implementation);
        assertEquals(
            """
            package test;

            public class DefaultTargetImpl implements DefaultTarget {
            }
            """, implementationSource);
        assertCompiles(parentSource, implementationSource, source);
    }

    @Test
    void erasedOverrideOfAMixedGenericMethod() throws Exception {
        TypeDef.TypeVariable classVariable = TypeDef.variable("T");
        TypeDef.TypeVariable methodVariable = TypeDef.variable("U");
        ClassDef parent = ClassDef.builder("test.MixedParent")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(classVariable)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(methodVariable)
                .addParameter("value", classVariable)
                .addParameter("other", methodVariable)
                .returns(classVariable)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();
        // The model declares `Object echo(Object, Object)`, which as source is `String echo(String, Object)`
        ClassDef child = ClassDef.builder("test.MixedChild")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class)
                .addParameter("other", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        String source = writeClass(child);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;

            class MixedChild extends MixedParent<String> {
              public String echo(String value, Object other) {
                return (String) value;
              }
            }
            """, source);
        String parentSource = writeClass(parent);
        assertEquals(
            """
            package test;

            public class MixedParent<T> {
              public <U> T echo(T value, U other) {
                return value;
              }
            }
            """, parentSource);
        assertCompiles(parentSource, source);
    }

    @Test
    void switchOfANarrowedParameterOrNullKeepsTheOverload() throws Exception {
        var apply = Function.class.getMethod("apply", Object.class);
        // The switch is a String in the source, `null` aside
        ClassDef classDef = chooser("test.NullSwitchChooser", chooseObject -> MethodDef.override(apply)
            .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                ExpressionDef.constant(1).asExpressionSwitch(TypeDef.OBJECT,
                    Map.of(ExpressionDef.constant(1), methodParameters.get(0)),
                    ExpressionDef.nullValue())).returning()));

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class NullSwitchChooser implements Function<String, String> {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String apply(String arg0) {
                return this.choose((Object) (switch (1) {
                  case 1 -> arg0;
                  default -> null;
                }));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void conditionalOfDifferentTypesKeepsTheOverload() throws Exception {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(int.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant(1).returning());
        MethodDef chooseSequence = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence.class)
            .returns(int.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant(2).returning());
        // Java types the conditional as the CharSequence both branches are, where the model calls `choose(Object)`
        ClassDef classDef = ClassDef.builder("test.MixedConditionalChooser")
            .addMethod(chooseObject)
            .addMethod(chooseSequence)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
                .addParameter("flag", boolean.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    methodParameters.get(0).isTrue().doIfElse(
                        ExpressionDef.constant("a"),
                        ClassTypeDef.of(StringBuilder.class).instantiate())).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class MixedConditionalChooser {
              public int choose(Object value) {
                return 1;
              }

              public int choose(CharSequence value) {
                return 2;
              }

              public int pick(boolean flag) {
                return this.choose((Object) (flag ? "a" : new java.lang.StringBuilder()));
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void referenceThroughAParameterToANarrowedMethodConvertsItsArguments() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef target = ClassDef.builder("test.ReferencedTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        ClassTypeDef objectFunction = TypeDef.parameterized(Function.class, Object.class, Object.class);
        // `target::apply` names `apply(String)`, which a `Function<Object, Object>` cannot reference
        ClassDef caller = ClassDef.builder("test.ReferencingCaller")
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .addParameter("target", target.asTypeDef())
                .returns(objectFunction)
                .build((aThis, methodParameters) -> objectFunction.methodReference(methodParameters.get(0), apply)
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.Optional;
            import java.util.function.Function;

            class ReferencingCaller {
              public Function<Object, Object> asFunction(ReferencedTarget target) {
                return Optional.of(target).<Function<Object, Object>>map(target1 -> (arg) -> target1.apply((String) arg)).get();
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public class ReferencedTarget implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void callOfANarrowedMethodInADefaultMethodIsConverted() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .addModifiers(Modifier.DEFAULT)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        // Within the interface, `this.apply` is its own `apply(String)`
        InterfaceDef interfaceDef = InterfaceDef.builder("test.DefaultCalls")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(apply, methodParameters.get(0)).returning()))
            .build();

        String source = render(interfaceDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            public interface DefaultCalls extends Function<String, String> {
              default String apply(String arg0) {
                return (String) arg0;
              }

              default Object call(Object value) {
                return this.apply((String) value);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void erasedOverrideOfAGenericMethodWithABoundedVariable() throws Exception {
        // The model declares the erasure `Object echo(Object, Number)`, which as source is
        // `String echo(String, Number)` - `U` is erased to its bound
        ClassDef classDef = ClassDef.builder("test.BoundedEchoChild")
            .superclass(TypeDef.parameterized(CompilationSignatures.BoundedEcho.class, String.class))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class)
                .addParameter("other", Number.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CompilationSignatures;
            import java.lang.Number;
            import java.lang.String;

            class BoundedEchoChild extends CompilationSignatures.BoundedEcho<String> {
              public String echo(String value, Number other) {
                return (String) value;
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callThroughAReceiverNamingAVariableOutOfScopeInAWildcard() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(List.class));
        ClassDef target = ClassDef.builder("test.ListTarget")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), variable, variable))
            .addMethod(MethodDef.override(applyMethod)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();
        MethodDef apply = target.getMethods().get(0);
        // `U` is out of scope, so the receiver is written as `ListTarget<List<? extends CharSequence>>`
        TypeDef.TypeVariable outOfScope = TypeDef.variable("U", TypeDef.of(CharSequence.class));
        ClassDef caller = ClassDef.builder("test.WildcardCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(target),
                    TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.wildcardSubtypeOf(outOfScope))))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;
            import java.util.List;

            class WildcardCaller {
              public Object call(ListTarget<List<? extends CharSequence>> target, Object value) {
                return target.apply((List<? extends CharSequence>) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.util.List;
            import java.util.function.Function;

            public class ListTarget<T extends List> implements Function<T, T> {
              public T apply(T arg0) {
                return (T) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void referencesThroughOtherReceiversReadThemOnce() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef target = ClassDef.builder("test.CapturedTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        ClassTypeDef objectFunction = TypeDef.parameterized(Function.class, Object.class, Object.class);
        FieldDef field = FieldDef.builder("target", target.asTypeDef()).addModifiers(Modifier.PRIVATE).build();
        MethodDef getTarget = MethodDef.builder("getTarget").addModifiers(Modifier.PUBLIC)
            .returns(target.asTypeDef())
            .build((aThis, methodParameters) -> aThis.field(field).returning());
        // A field, a local and a method result are each read once, where the reference is created
        ClassDef caller = ClassDef.builder("test.CapturingCaller")
            .addField(field)
            .addMethod(getTarget)
            .addMethod(MethodDef.builder("fromField").addModifiers(Modifier.PUBLIC)
                .returns(objectFunction)
                .build((aThis, methodParameters) -> objectFunction.methodReference(aThis.field(field), apply)
                    .returning()))
            .addMethod(MethodDef.builder("fromLocal").addModifiers(Modifier.PUBLIC)
                .returns(objectFunction)
                .build((aThis, methodParameters) -> aThis.field(field).newLocal("local",
                    local -> objectFunction.methodReference(local, apply).returning())))
            .addMethod(MethodDef.builder("fromResult").addModifiers(Modifier.PUBLIC)
                .returns(objectFunction)
                .build((aThis, methodParameters) -> objectFunction.methodReference(aThis.invoke(getTarget), apply)
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.Optional;
            import java.util.function.Function;

            class CapturingCaller {
              private CapturedTarget target;

              public CapturedTarget getTarget() {
                return this.target;
              }

              public Function<Object, Object> fromField() {
                return Optional.of(this.target).<Function<Object, Object>>map(target -> (arg) -> target.apply((String) arg)).get();
              }

              public Function<Object, Object> fromLocal() {
                CapturedTarget local = this.target;
                return Optional.of(local).<Function<Object, Object>>map(target -> (arg) -> target.apply((String) arg)).get();
              }

              public Function<Object, Object> fromResult() {
                return Optional.of(this.getTarget()).<Function<Object, Object>>map(target -> (arg) -> target.apply((String) arg)).get();
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public class CapturedTarget implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void referenceToANarrowedMethodConvertsItsResult() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassTypeDef stringFunction = TypeDef.parameterized(Function.class, Object.class, String.class);
        // `apply` is written as `CharSequence apply(CharSequence)`, which a `Function<Object, String>` returns cast
        ClassDef classDef = ClassDef.builder("test.ResultReferenced")
            .addSuperinterface(TypeDef.parameterized(Function.class, CharSequence.class, CharSequence.class))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(stringFunction)
                .build((aThis, methodParameters) -> stringFunction.methodReference(aThis, apply).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class ResultReferenced implements Function<CharSequence, CharSequence> {
              public CharSequence apply(CharSequence arg0) {
                return (CharSequence) arg0;
              }

              public Function<Object, String> asFunction() {
                return (arg) -> (String) this.apply((CharSequence) arg);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void superReferenceToANarrowedMethodIsNotCaptured() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef parent = ClassDef.builder("test.SuperTarget")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();
        ClassTypeDef objectFunction = TypeDef.parameterized(Function.class, Object.class, Object.class);
        ClassDef child = ClassDef.builder("test.SuperReferencing")
            .superclass(parent.asTypeDef())
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(objectFunction)
                .build((aThis, methodParameters) -> objectFunction.methodReference(aThis.superRef(), apply)
                    .returning()))
            .build();

        String source = writeClass(child);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class SuperReferencing extends SuperTarget {
              public Function<Object, Object> asFunction() {
                return (arg) -> super.apply((String) arg);
              }
            }
            """, source);
        String parentSource = writeClass(parent);
        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.function.Function;

            public class SuperTarget implements Function<String, String> {
              public String apply(String arg0) {
                return (String) arg0;
              }
            }
            """, parentSource);
        assertCompiles(parentSource, source);
    }

    @Test
    void referenceResultsOfVariableAndPrimitiveTypes() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        TypeDef.TypeVariable variable = TypeDef.variable("U", TypeDef.of(Number.class));
        ClassTypeDef variableFunction = TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.OBJECT, variable);
        ClassTypeDef intFunction = TypeDef.parameterized(ToIntFunction.class, Object.class);
        // `apply` is written as `Number apply(Number)`: a `Function<Object, U>` returns it cast to `U`, and a
        // `ToIntFunction<Object>` unboxes it
        ClassDef classDef = ClassDef.builder("test.NumberReferenced")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(Function.class, Number.class, Number.class))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(variableFunction)
                .build((aThis, methodParameters) -> variableFunction.methodReference(aThis, apply).returning()))
            .addMethod(MethodDef.builder("asIntFunction").addModifiers(Modifier.PUBLIC)
                .returns(intFunction)
                .build((aThis, methodParameters) -> intFunction.methodReference(aThis, apply).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.lang.Object;
            import java.util.function.Function;
            import java.util.function.ToIntFunction;

            class NumberReferenced<U extends Number> implements Function<Number, Number> {
              public Number apply(Number arg0) {
                return (Number) arg0;
              }

              public Function<Object, U> asFunction() {
                return (arg) -> (U) this.apply((Number) arg);
              }

              public ToIntFunction<Object> asIntFunction() {
                return (arg) -> (Integer) this.apply((Number) arg);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void referenceResultOfAnUnrelatedParameterizationIsCastThroughObject() throws Exception {
        var getMethod = Supplier.class.getMethod("get");
        MethodDef get = MethodDef.override(getMethod)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        ClassTypeDef objectsSupplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
            TypeDef.parameterized(List.class, Object.class));
        // `get` is written as `List<String> get()`, which a `Supplier<List<Object>>` cannot cast directly
        ClassDef classDef = ClassDef.builder("test.ListSupplied")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(List.class, String.class)))
            .addMethod(get)
            .addMethod(MethodDef.builder("asObjects").addModifiers(Modifier.PUBLIC)
                .returns(objectsSupplier)
                .build((aThis, methodParameters) -> objectsSupplier.methodReference(aThis, get).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class ListSupplied implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              public Supplier<List<Object>> asObjects() {
                return () -> (List) this.get();
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void callThroughAReceiverWithAWildcardResultKeepsItsArguments() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        TypeDef.TypeVariable input = TypeDef.variable("A", TypeDef.of(CharSequence.class));
        TypeDef.TypeVariable output = TypeDef.variable("B", TypeDef.of(CharSequence.class));
        ClassDef target = ClassDef.builder("test.TwoTarget")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(input)
            .addTypeVariable(output)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), input, output))
            .addMethod(MethodDef.override(applyMethod)
                .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning()))
            .build();
        MethodDef apply = target.getMethods().get(0);
        // The result is of the captured `? extends CharSequence`, and the argument still a String
        ClassDef caller = ClassDef.builder("test.WildcardResultCaller")
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(target), TypeDef.STRING,
                    TypeDef.wildcardSubtypeOf(TypeDef.of(CharSequence.class))))
                .addParameter("value", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke(apply, methodParameters.get(1))
                    .returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;
            import java.lang.String;

            class WildcardResultCaller {
              public Object call(TwoTarget<String, ? extends CharSequence> target, Object value) {
                return target.apply((String) value);
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Function;

            public class TwoTarget<A extends CharSequence, B extends CharSequence> implements Function<A, B> {
              public B apply(A arg0) {
                return null;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void erasedOverrideOfAMethodVariableBoundByAClassVariable() throws Exception {
        TypeDef.TypeVariable classU = TypeDef.variable("U", TypeDef.of(CharSequence.class));
        ClassDef parent = ClassDef.builder("test.BoundByClassParent")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addTypeVariable(classU)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("T", classU))
                .addParameter("value", TypeDef.variable("T"))
                .returns(classU)
                .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning()))
            .build();
        // The model declares the erasure `CharSequence echo(CharSequence)`, which as a member of
        // `BoundByClassParent<String, String>` is `String echo(String)`
        ClassDef child = ClassDef.builder("test.BoundByClassChild")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING, TypeDef.STRING))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", CharSequence.class)
                .returns(CharSequence.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        String source = writeClass(child);

        assertEquals(
            """
            package test;

            import java.lang.String;

            class BoundByClassChild extends BoundByClassParent<String, String> {
              public String echo(String value) {
                return (String) value;
              }
            }
            """, source);
        String parentSource = writeClass(parent);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;

            public class BoundByClassParent<T, U extends CharSequence> {
              public <T extends U> U echo(T value) {
                return null;
              }
            }
            """, parentSource);
        assertCompiles(parentSource, source);
    }

    @Test
    void unrelatedParameterizationsAreConvertedThroughTheRawType() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef apply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        ClassTypeDef objects = TypeDef.parameterized(List.class, Object.class);
        ClassTypeDef objectsFunction = TypeDef.parameterized(ClassTypeDef.of(Function.class), objects, TypeDef.OBJECT);
        // `apply` is written as `apply(List<String>)`
        ClassDef classDef = ClassDef.builder("test.Unrelated")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class),
                TypeDef.parameterized(List.class, String.class), TypeDef.OBJECT))
            .addMethod(apply)
            .addMethod(MethodDef.builder("asFunction").addModifiers(Modifier.PUBLIC)
                .returns(objectsFunction)
                .build((aThis, methodParameters) -> objectsFunction.methodReference(aThis, apply).returning()))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", objects)
                .returns(Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(apply, methodParameters.get(0)).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class Unrelated implements Function<List<String>, Object> {
              public Object apply(List<String> arg0) {
                return null;
              }

              public Function<List<Object>, Object> asFunction() {
                return (arg) -> this.apply((List) arg);
              }

              public Object call(List<Object> values) {
                return this.apply((List) values);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void rawCastInAGeneratedTypeNamedObject() throws Exception {
        var getMethod = Supplier.class.getMethod("get");
        MethodDef get = MethodDef.override(getMethod)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        ClassTypeDef objectsSupplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
            TypeDef.parameterized(List.class, Object.class));
        // The generated type shadows `java.lang.Object`
        ClassDef classDef = ClassDef.builder("test.Object")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(List.class, String.class)))
            .addMethod(get)
            .addMethod(MethodDef.builder("asObjects").addModifiers(Modifier.PUBLIC)
                .returns(objectsSupplier)
                .build((aThis, methodParameters) -> objectsSupplier.methodReference(aThis, get).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            public final class Object implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              public Supplier<List<java.lang.Object>> asObjects() {
                return () -> (List) this.get();
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void methodVariableBoundByAClassVariableIsWrittenWithItsBound() throws Exception {
        TypeDef.TypeVariable classVariable = TypeDef.variable("T");
        TypeDef.TypeVariable extra = TypeDef.variable("U", classVariable);
        ClassDef parent = ClassDef.builder("test.BoundedExtraParent")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(classVariable)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(extra)
                .addParameter("value", classVariable)
                .addParameter("extra", extra)
                .returns(classVariable)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();
        // `<U extends T>` of a `BoundedExtraParent<String>` erases to `String`, which the override takes
        ClassDef child = ClassDef.builder("test.BoundedExtraChild")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class)
                .addParameter("extra", Object.class)
                .returns(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(1).returning()))
            .build();

        String parentSource = writeClass(parent);
        String source = writeClass(child);

        assertEquals(
            """
            package test;

            public class BoundedExtraParent<T> {
              public <U extends T> T echo(T value, U extra) {
                return value;
              }
            }
            """, parentSource);
        assertEquals(
            """
            package test;

            import java.lang.String;

            class BoundedExtraChild extends BoundedExtraParent<String> {
              public String echo(String value, String extra) {
                return (String) extra;
              }
            }
            """, source);
        assertCompiles(parentSource, source);
    }

    @Test
    void capturedResultIsOfTheVariablesBound() throws Exception {
        MethodDef get = MethodDef.override(Supplier.class.getMethod("get"))
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        ClassDef target = ClassDef.builder("test.BoundedSupplier")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), variable))
            .addMethod(get)
            .build();
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseSequence = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", CharSequence.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("sequence").returning());
        // Through a `BoundedSupplier<?>`, `get()` is a CharSequence, where the model calls `choose(Object)`
        ClassDef caller = ClassDef.builder("test.CapturedChooser")
            .addMethod(chooseObject)
            .addMethod(chooseSequence)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(target), TypeDef.wildcard()))
                .returns(String.class)
                .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                    methodParameters.get(0).invoke(get)).returning()))
            .build();

        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;
            import java.lang.String;

            class CapturedChooser {
              public String choose(Object value) {
                return "object";
              }

              public String choose(CharSequence value) {
                return "sequence";
              }

              public String pick(BoundedSupplier<?> target) {
                return this.choose((Object) target.get());
              }
            }
            """, source);
        String targetSource = writeClass(target);
        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Supplier;

            public class BoundedSupplier<T extends CharSequence> implements Supplier<T> {
              public T get() {
                return null;
              }
            }
            """, targetSource);
        assertCompiles(targetSource, source);
    }

    @Test
    void arrayAndVariableArgumentsOfNarrowedParameters() throws Exception {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        MethodDef arrayApply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        // `apply` is written as `apply(String[])`, which an Object[] is cast to
        ClassDef arrays = ClassDef.builder("test.ArrayArguments")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.STRING.array(),
                TypeDef.OBJECT))
            .addMethod(arrayApply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("values", TypeDef.OBJECT.array())
                .returns(Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(arrayApply, methodParameters.get(0)).returning()))
            .build();
        MethodDef stringApply = MethodDef.override(applyMethod)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        TypeDef.TypeVariable variable = TypeDef.variable("U");
        // `apply` is written as `apply(String)`, which a value of the class's `U` is cast to
        ClassDef variables = ClassDef.builder("test.VariableArguments")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, Object.class))
            .addMethod(stringApply)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", variable)
                .returns(Object.class)
                .build((aThis, methodParameters) -> aThis.invoke(stringApply, methodParameters.get(0)).returning()))
            .build();

        String arraySource = writeClass(arrays);
        String variableSource = writeClass(variables);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class ArrayArguments implements Function<String[], Object> {
              public Object apply(String[] arg0) {
                return null;
              }

              public Object call(Object[] values) {
                return this.apply((String[]) values);
              }
            }
            """, arraySource);
        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Function;

            class VariableArguments<U> implements Function<String, Object> {
              public Object apply(String arg0) {
                return null;
              }

              public Object call(U value) {
                return this.apply((String) value);
              }
            }
            """, variableSource);
        assertCompiles(arraySource);
        assertCompiles(variableSource);
    }

    @Test
    void narrowedParameterizedParameterIsRestoredAsRaw() throws Exception {
        ClassTypeDef objects = TypeDef.parameterized(List.class, Object.class);
        MethodDef consumeList = MethodDef.builder("consume").addModifiers(Modifier.PUBLIC)
            .addParameter("values", objects)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("list").returning());
        MethodDef consumeObject = MethodDef.builder("consume").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        var apply = Function.class.getMethod("apply", Object.class);
        // `apply(Object value)` becomes `apply(List<String> value)`, which is passed on as the `List<Object>` the
        // model calls with
        ClassDef classDef = ClassDef.builder("test.RawRestored")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class),
                TypeDef.parameterized(List.class, String.class), TypeDef.STRING))
            .addMethod(consumeList)
            .addMethod(consumeObject)
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> aThis.invoke(consumeList,
                    methodParameters.get(0).cast(objects)).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class RawRestored implements Function<List<String>, String> {
              public String consume(List<Object> values) {
                return "list";
              }

              public String consume(Object value) {
                return "object";
              }

              public String apply(List<String> arg0) {
                return this.consume((List) arg0);
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void narrowedReturnOfAnUnrelatedParameterizationIsRaw() throws Exception {
        ClassTypeDef objects = TypeDef.parameterized(List.class, Object.class);
        FieldDef field = FieldDef.builder("value", objects).addModifiers(Modifier.PRIVATE).build();
        // `get` is written as `List<String> get()`, which the `List<Object>` field is returned as
        ClassDef classDef = ClassDef.builder("test.RawReturned")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(List.class, String.class)))
            .addField(field)
            .addMethod(MethodDef.override(Supplier.class.getMethod("get"))
                .build((aThis, methodParameters) -> aThis.field(field).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class RawReturned implements Supplier<List<String>> {
              private List<Object> value;

              public List<String> get() {
                return (List) this.value;
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void referenceWithAWildcardResultConvertsItsResult() throws Exception {
        MethodDef get = MethodDef.override(Supplier.class.getMethod("get"))
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        ClassTypeDef stringsSupplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
            TypeDef.wildcardSubtypeOf(TypeDef.STRING));
        // `get` is written as `CharSequence get()`, which a `Supplier<? extends String>` returns as a String
        ClassDef classDef = ClassDef.builder("test.WildcardSupplied")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, CharSequence.class))
            .addMethod(get)
            .addMethod(MethodDef.builder("asStrings").addModifiers(Modifier.PUBLIC)
                .returns(stringsSupplier)
                .build((aThis, methodParameters) -> stringsSupplier.methodReference(aThis, get).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals("""
            package test;

            import java.lang.CharSequence;
            import java.lang.String;
            import java.util.function.Supplier;

            class WildcardSupplied implements Supplier<CharSequence> {
              public CharSequence get() {
                return null;
              }

              public Supplier<? extends String> asStrings() {
                return () -> (String) this.get();
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void compatibleParameterizedCastOfANarrowedParameterIsKept() throws Exception {
        var apply = Function.class.getMethod("apply", Object.class);
        ClassTypeDef sequences = TypeDef.parameterized(ClassTypeDef.of(List.class),
            TypeDef.wildcardSubtypeOf(TypeDef.of(CharSequence.class)));
        ClassTypeDef indexed = TypeDef.parameterized(IntFunction.class, CharSequence.class);
        var listGet = List.class.getMethod("get", int.class);
        // `apply(Object value)` becomes `apply(List<String> value)`, which the cast to `List<? extends CharSequence>`
        // accepts, and which types the reference
        ClassDef classDef = ClassDef.builder("test.IndexedSequences")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class),
                TypeDef.parameterized(List.class, String.class), indexed))
            .addMethod(MethodDef.override(apply)
                .build((aThis, methodParameters) -> indexed.methodReference(methodParameters.get(0).cast(sequences),
                    listGet).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.CharSequence;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.IntFunction;

            class IndexedSequences implements Function<List<String>, IntFunction<CharSequence>> {
              public IntFunction<CharSequence> apply(List<String> arg0) {
                return ((List<? extends CharSequence>) arg0)::get;
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void referenceResultOfAVariableWithAParameterizedBound() throws Exception {
        MethodDef get = MethodDef.override(Supplier.class.getMethod("get"))
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        TypeDef.TypeVariable variable = TypeDef.variable("U", TypeDef.parameterized(List.class, Object.class));
        ClassTypeDef variableSupplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), variable);
        // `get` is written as `List<String> get()`, which converts to `U extends List<Object>` through `List`
        ClassDef classDef = ClassDef.builder("test.BoundListSupplied")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(List.class, String.class)))
            .addMethod(get)
            .addMethod(MethodDef.builder("asVariable").addModifiers(Modifier.PUBLIC)
                .returns(variableSupplier)
                .build((aThis, methodParameters) -> variableSupplier.methodReference(aThis, get).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class BoundListSupplied<U extends List<Object>> implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              public Supplier<U> asVariable() {
                return () -> (U) (List) this.get();
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void capturedResultIsOfTheBoundTheReceiverBinds() throws Exception {
        MethodDef get = MethodDef.override(Supplier.class.getMethod("get"))
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning());
        TypeDef.TypeVariable upper = TypeDef.variable("A");
        TypeDef.TypeVariable variable = TypeDef.variable("T", upper);
        ClassDef target = ClassDef.builder("test.DependentSupplier")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(upper)
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), variable))
            .addMethod(get)
            .build();
        // Through a `DependentSupplier<String, ?>`, `get()` is a String, where the model calls `choose(Object)`
        ClassDef caller = chooser("test.DependentChooser", ClassTypeDef.of(Serializable.class),
            chooseObject -> MethodDef.builder("pick")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(target), TypeDef.STRING, TypeDef.wildcard()))
            .returns(String.class)
            .build((aThis, methodParameters) -> aThis.invoke(chooseObject,
                methodParameters.get(0).invoke(get)).returning()));

        String targetSource = writeClass(target);
        String source = writeClass(caller);

        assertEquals(
            """
            package test;

            import java.util.function.Supplier;

            public class DependentSupplier<A, T extends A> implements Supplier<T> {
              public T get() {
                return null;
              }
            }
            """, targetSource);
        assertEquals(
            """
            package test;

            import java.io.Serializable;
            import java.lang.Object;
            import java.lang.String;

            class DependentChooser implements Serializable {
              public String choose(Object value) {
                return "object";
              }

              public String choose(String value) {
                return "string";
              }

              public String pick(DependentSupplier<String, ?> target) {
                return this.choose((Object) target.get());
              }
            }
            """, source);
        assertCompiles(targetSource, source);
    }

    @Test
    void returnOfAParameterizationTheClassVariableDoesNotAccept() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        FieldDef field = FieldDef.builder("value", TypeDef.parameterized(List.class, String.class))
            .addModifiers(Modifier.PRIVATE)
            .build();
        // `get` is written as `List<T> get()`, which the `List<String>` field only converts to as a raw `List`
        ClassDef classDef = ClassDef.builder("test.VariableListReturned")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
                TypeDef.parameterized(ClassTypeDef.of(List.class), variable)))
            .addField(field)
            .addMethod(MethodDef.override(Supplier.class.getMethod("get"))
                .build((aThis, methodParameters) -> aThis.field(field).returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class VariableListReturned<T> implements Supplier<List<T>> {
              private List<String> value;

              public List<T> get() {
                return (List) this.value;
              }
            }
            """, source);
        assertCompiles(source);
    }

    @Test
    void arrayPassedToVarargsOverloadIsNotCastToTheModelParameter() throws Exception {
        // `Set.of(E...)` with a `Class[]`: javac selects it over `of(E)`, which takes the array too, as the more
        // specific one - a cast to the `Object[]` of the model would make `E` an `Object`
        ClassDef classDef = ClassDef.builder("test.ExposedTypes")
            .addMethod(MethodDef.builder("types").addModifiers(Modifier.STATIC)
                .returns(TypeDef.parameterized(Set.class, TypeDef.Primitive.CLASS))
                .build((aThis, methodParameters) -> ClassTypeDef.of(Set.class)
                    .invokeStatic("of", List.of(TypeDef.OBJECT.array()), ClassTypeDef.of(Set.class),
                        TypeDef.Primitive.CLASS.array().instantiate(List.of(
                            ExpressionDef.constant(TypeDef.of(String.class)),
                            ExpressionDef.constant(TypeDef.of(Integer.class)))))
                    .returning()))
            .build();

        String source = writeClass(classDef);

        assertEquals(
            """
            package test;

            import java.lang.Class;
            import java.util.Set;

            class ExposedTypes {
              static Set<Class> types() {
                return Set.of(new Class[]{java.lang.String.class,java.lang.Integer.class});
              }
            }
            """, source);
        assertCompiles(source);
    }

    private static ClassDef genericTarget(String name) throws NoSuchMethodException {
        var applyMethod = Function.class.getMethod("apply", Object.class);
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        return ClassDef.builder(name)
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), variable, variable))
            .addMethod(MethodDef.override(applyMethod)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();
    }

    private static ClassDef chooser(String name, Function<MethodDef, MethodDef> method) {
        return chooser(name, TypeDef.parameterized(Function.class, String.class, String.class), method);
    }

    /**
     * A class with `choose(Object)` and `choose(String)`, whose method calls the former.
     */
    private static ClassDef chooser(String name, ClassTypeDef superinterface, Function<MethodDef, MethodDef> method) {
        MethodDef chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", Object.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("object").returning());
        MethodDef chooseString = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> ExpressionDef.constant("string").returning());
        return ClassDef.builder(name)
            .addSuperinterface(superinterface)
            .addMethod(chooseObject)
            .addMethod(chooseString)
            .addMethod(method.apply(chooseObject))
            .build();
    }
}
