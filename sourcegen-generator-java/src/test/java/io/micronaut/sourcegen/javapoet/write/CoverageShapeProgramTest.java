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
package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType;
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DSL programs for the lines of the Java generator that no other test reaches - found by line coverage - rendered,
 * compiled and run, with the generator's own error paths asserted where the model can express them.
 *
 * @since 2.2.2
 */
public class CoverageShapeProgramTest {

    private static final TypeDef.Primitive INT = TypeDef.Primitive.INT;

    // JavaPoetSourceGenerator 143, 157: the language of the generator, and a definition it does not know
    @Test
    void languageIsJavaAndAnUnknownDefinitionIsRejected() {
        assertEquals(VisitorContext.Language.JAVA, new JavaPoetSourceGenerator().getLanguage());
        assertThrows(IllegalStateException.class, () -> new JavaPoetSourceGenerator().write((ObjectDef) null, new StringWriter()));
    }

    // JavaPoetSourceGenerator 177-179, 232-233, 284-285, 404-405: annotations of an interface, an enum, an annotation
    // member, and an annotation type nested in a class
    @Test
    void annotationsOfInterfacesEnumsAndAnnotationTypesAreWritten() throws Exception {
        var marker = AnnotationDef.builder(ClassTypeDef.of(Marker.class)).build();
        var greeter = InterfaceDef.builder("test.MarkedGreeter").addModifiers(Modifier.PUBLIC).addAnnotation(marker).build();
        var level = EnumDef.builder("test.MarkedLevel").addModifiers(Modifier.PUBLIC).addAnnotation(marker)
            .addEnumConstant("LOW").addEnumConstant("HIGH").build();
        var tagged = AnnotationObjectDef.builder("Tagged").addModifiers(Modifier.PUBLIC).addAnnotation(marker)
            .addMember(AnnotationObjectDef.AnnotationMemberDef.builder("value", TypeDef.STRING).addAnnotation(marker)
                .withDefault(ExpressionDef.constant("tag")).build())
            .build();
        var holder = ClassDef.builder("test.TagHolder").addModifiers(Modifier.PUBLIC).addInnerType(tagged).build();
        try (var loader = compile(greeter, level, holder)) {
            assertNotNull(loader.loadClass(greeter.getName()).getAnnotation(Marker.class));
            var levels = loader.loadClass(level.getName());
            assertNotNull(levels.getAnnotation(Marker.class));
            assertEquals(2, levels.getEnumConstants().length);
            var annotationType = loader.loadClass("test.TagHolder$Tagged");
            assertTrue(annotationType.isAnnotation());
            assertNotNull(annotationType.getAnnotation(Marker.class));
            Method value = annotationType.getMethod("value");
            assertNotNull(value.getAnnotation(Marker.class));
            assertEquals("tag", value.getDefaultValue());
        }
    }

    // JavaPoetSourceGenerator 180-207: a property of an interface is written as its accessors; the backing field the
    // generator writes next to them is private, which an interface cannot declare
    @Test
    void interfacePropertyIsWrittenAsAccessors() throws Exception {
        var property = PropertyDef.builder("label").ofType(String.class).addJavadoc("The label.")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Marker.class)).build()).build();
        var labelled = InterfaceDef.builder("test.LabelledThing").addModifiers(Modifier.PUBLIC).addProperty(property).build();
        var impl = ClassDef.builder("test.LabelledImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(labelled.asTypeDef())
            .addProperty(PropertyDef.builder("label").ofType(String.class).addModifiers(Modifier.PUBLIC).build()).build();
        try (var loader = compile(labelled, impl)) {
            var cls = loader.loadClass(impl.getName());
            var instance = cls.getConstructor().newInstance();
            cls.getMethod("setLabel", String.class).invoke(instance, "named");
            assertEquals("named", loader.loadClass(labelled.getName()).getMethod("getLabel").invoke(instance));
        }
    }

    // JavaPoetSourceGenerator 444-447, 464-467, 527-530: annotations of a field, a property and a method
    @Test
    void fieldPropertyAndMethodAnnotationsAreWritten() throws Exception {
        var marker = AnnotationDef.builder(ClassTypeDef.of(Marker.class)).build();
        var def = ClassDef.builder("test.Annotated").addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).addAnnotation(marker)
                .initializer(ExpressionDef.constant(3)).build())
            .addProperty(PropertyDef.builder("name").ofType(String.class).addModifiers(Modifier.PUBLIC).addAnnotation(marker).build())
            .addMethod(MethodDef.builder("twice").addModifiers(Modifier.PUBLIC).addAnnotation(marker).addParameter("value", int.class)
                .returns(int.class).build((self, p) -> p.get(0).math(MathBinaryOperation.OpType.MULTIPLICATION, ExpressionDef.constant(2)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertNotNull(cls.getField("count").getAnnotation(Marker.class));
            assertNotNull(cls.getDeclaredField("name").getAnnotation(Marker.class));
            assertNotNull(cls.getMethod("twice", int.class).getAnnotation(Marker.class));
            var instance = cls.getConstructor().newInstance();
            assertEquals(3, cls.getField("count").get(instance));
            cls.getMethod("setName", String.class).invoke(instance, "n");
            assertEquals("n", cls.getMethod("getName").invoke(instance));
            assertEquals(14, cls.getMethod("twice", int.class).invoke(instance, 7));
        }
    }

    // JavaPoetSourceGenerator 584-585: an annotation member whose value is a static field of a compiled class
    @Test
    void annotationValueReadsAStaticField() throws Exception {
        var named = AnnotationDef.builder(ClassTypeDef.of(Named.class))
            .addMember("value", ClassTypeDef.of(Names.class).getStaticField("FIRST", TypeDef.STRING)).build();
        var def = ClassDef.builder("test.NamedByField").addModifiers(Modifier.PUBLIC).addAnnotation(named).build();
        try (var loader = compile(def)) {
            assertEquals(Names.FIRST, loader.loadClass(def.getName()).getAnnotation(Named.class).value());
        }
    }

    // JavaPoetSourceGenerator 665-667: an annotated primitive type is written with its type annotation
    @Test
    void annotatedPrimitiveTypeIsWritten() throws Exception {
        var mark = AnnotationDef.builder(ClassTypeDef.of(Mark.class)).build();
        var def = ClassDef.builder("test.MarkedInt").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("same").addModifiers(Modifier.PUBLIC).addParameter("value", INT.annotated(mark)).returns(int.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = cls.getMethod("same", int.class);
            assertNotNull(method.getAnnotatedParameterTypes()[0].getAnnotation(Mark.class));
            assertEquals(4, method.invoke(cls.getConstructor().newInstance(), 4));
        }
    }

    // JavaPoetSourceGenerator 679, 687, 1918, 1953, 1959, 1974: `this`, `super`, a field and a method parameter
    // outside an instance scope - the arguments of an enum constant and a field initializer
    @Test
    void thisSuperFieldsAndParametersOutsideTheirScopeAreRejected() {
        assertThrows(IllegalStateException.class, () -> render(enumWithConstantArgument(
            new ExpressionDef.NewInstance((ClassTypeDef) TypeDef.THIS, List.of(), List.of()))));
        assertThrows(IllegalStateException.class, () -> render(enumWithConstantArgument(ExpressionDef.constant(1).cast(TypeDef.SUPER))));
        assertThrows(IllegalStateException.class, () -> render(enumWithConstantArgument(new VariableDef.This())));
        assertThrows(IllegalStateException.class, () -> render(enumWithConstantArgument(new VariableDef.This().superRef())));
        assertThrows(IllegalStateException.class, () -> render(enumWithConstantArgument(
            new VariableDef.Field(ExpressionDef.constant("x"), TypeDef.STRING, "value", TypeDef.STRING))));
        assertThrows(IllegalStateException.class, () -> render(ClassDef.builder("test.ParameterInField").addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("value", int.class).initializer(new VariableDef.MethodParameter("x", INT)).build()).build()));
    }

    // JavaPoetSourceGenerator 692-693, 695: the super type of an enum is `Enum`; an interface and a record have none
    @Test
    void superTypeOfAnEnumIsEnumAndOfOthersIsRejected() throws Exception {
        var ordinalOf = MethodDef.builder("ordinalOf").addModifiers(Modifier.PUBLIC).addParameter("other", TypeDef.SUPER).returns(int.class)
            .build((self, p) -> p.get(0).invoke("ordinal", INT).returning());
        var def = EnumDef.builder("test.SuperEnum").addModifiers(Modifier.PUBLIC).addEnumConstant("A").addEnumConstant("B").addMethod(ordinalOf).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(Enum.class, cls.getMethod("ordinalOf", Enum.class).getParameterTypes()[0]);
            assertEquals(1, cls.getMethod("ordinalOf", Enum.class).invoke(cls.getField("A").get(null), cls.getField("B").get(null)));
        }
        var checks = MethodDef.builder("check").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).addParameter("other", TypeDef.SUPER)
            .returns(boolean.class).build((self, p) -> ExpressionDef.constant(true).returning());
        assertThrows(IllegalStateException.class, () -> render(InterfaceDef.builder("test.SuperInterface").addMethod(checks).build()));
        assertThrows(IllegalStateException.class, () -> render(RecordDef.builder("test.SuperRecord")
            .addProperty(PropertyDef.builder("value").ofType(int.class).build()).addMethod(checks).build()));
    }

    // JavaPoetSourceGenerator 133-139, 757; JavaPoetNames 65-79, 95-111; JavaExpressionRules 827-830: written through a
    // visitor context, a `$` name the context knows as a nested class is written as `Outer.Inner`, and a generated
    // class the context knows as generic is passed raw where a wildcard bound needs a parameterization
    @Test
    void nestedNamesAndGenericClassesAreResolvedThroughTheVisitorContext() throws Exception {
        var outer = ClassDef.builder("test.Outer").addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build()).build();
        var t = TypeDef.variable("T");
        var genNames = ClassDef.builder("test.GenNames").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ArrayList.class, t)).build();
        var sizes = Helpers.class.getMethod("sizes", List.class);
        var def = ClassDef.builder("test.NestedUser").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> ClassTypeDef.of("test.Outer$Inner").instantiate().returning()))
            .addMethod(MethodDef.builder("sizes").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(List.class, ClassTypeDef.of("test.GenNames"))).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(sizes, p.get(0)).returning()))
            .build();

        ClassElement outerElement = mock(ClassElement.class);
        when(outerElement.getName()).thenReturn("test.Outer");
        when(outerElement.getPackageName()).thenReturn("test");
        when(outerElement.getEnclosingType()).thenReturn(Optional.empty());
        ClassElement innerElement = mock(ClassElement.class);
        when(innerElement.getName()).thenReturn("test.Outer$Inner");
        when(innerElement.isInner()).thenReturn(true);
        when(innerElement.getEnclosingType()).thenReturn(Optional.of(outerElement));
        ClassElement genericElement = mock(ClassElement.class);
        when(genericElement.getName()).thenReturn("test.GenNames");
        doReturn(List.of(mock(GenericPlaceholderElement.class))).when(genericElement).getDeclaredGenericPlaceholders();
        VisitorContext context = mock(VisitorContext.class);
        when(context.getClassElement("test.Outer$Inner")).thenReturn(Optional.of(innerElement));
        when(context.getClassElement("test.GenNames")).thenReturn(Optional.of(genericElement));
        var writer = new StringWriter();
        var outerWriter = new StringWriter();
        Element element = mock(Element.class);
        GeneratedFile outerFile = mock(GeneratedFile.class);
        when(outerFile.openWriter()).thenReturn(outerWriter);
        doCallRealMethod().when(outerFile).write(any());
        when(context.visitGeneratedSourceFile(eq("test"), eq("Outer"), any(Element.class))).thenReturn(Optional.of(outerFile));
        GeneratedFile file = mock(GeneratedFile.class);
        // The outer class is written while this one is: a nested write restores the context of the enclosing one
        doAnswer(invocation -> {
            new JavaPoetSourceGenerator().write(outer, context, element);
            invocation.<GeneratedFile.ThrowingConsumer<java.io.Writer>>getArgument(0).accept(writer);
            return null;
        }).when(file).write(any());
        when(context.visitGeneratedSourceFile(eq("test"), eq("NestedUser"), any(Element.class))).thenReturn(Optional.of(file));

        new JavaPoetSourceGenerator().write(def, context, element);
        String source = writer.toString();
        assertTrue(source.contains("new test.Outer.Inner()"), source);
        assertTrue(source.contains("sizes((List) lists)"), source);
        assertTrue(outerWriter.toString().contains("class Outer"), outerWriter.toString());
        try (var loader = JavaCompileAssertions.compileAndLoad(outerWriter.toString(), render(genNames), source)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("test.Outer$Inner", cls.getMethod("make").invoke(instance).getClass().getName());
            var names = loader.loadClass(genNames.getName()).getConstructor().newInstance();
            assertEquals(1, cls.getMethod("sizes", List.class).invoke(instance, List.of(names)));
        }
    }

    // JavaPoetSourceGenerator 846, 1322, 1727: a null statement, a null expression and a null condition in the model
    @Test
    void nullStatementsExpressionsAndConditionsAreRejected() {
        assertThrows(IllegalStateException.class, () -> render(classWithBody("NullStatement", void.class,
            new StatementDef.If(ExpressionDef.constant(true).isTrue(), null))));
        assertThrows(IllegalStateException.class, () -> render(classWithBody("NullExpression", void.class, new StatementDef.Throw(null))));
        assertThrows(IllegalStateException.class, () -> render(classWithBody("NullCondition", void.class,
            new ExpressionDef.And(null, ExpressionDef.constant(true).isTrue()).doIf(ExpressionDef.constant(1).newLocal("x")))));
    }

    // RenderScope 156: an exception variable read outside of the catch that names it
    @Test
    void exceptionVariableOutsideACatchIsRejected() {
        assertThrows(NullPointerException.class, () -> render(classWithBody("LooseException", Exception.class,
            new VariableDef.ExceptionVar(ClassTypeDef.of(Exception.class)).returning())));
    }

    // JavaPoetSourceGenerator 1232, 1236, 1771; JavaSourceRules 290, 297: yield cases without statements, without a
    // final return, with an empty branch, and returning nothing
    @Test
    void switchYieldCasesThatCannotYieldAreRejected() {
        var local = new VariableDef.Local("x", INT);
        assertThrows(IllegalStateException.class, () -> render(switchWithYieldCase(new ExpressionDef.SwitchYieldCase(INT, StatementDef.multi()))));
        assertThrows(IllegalStateException.class, () -> render(switchWithYieldCase(new ExpressionDef.SwitchYieldCase(INT,
            local.defineAndAssign(ExpressionDef.constant(1))))));
        assertThrows(IllegalStateException.class, () -> render(switchWithYieldCase(new ExpressionDef.SwitchYieldCase(INT,
            new StatementDef.IfElse(ExpressionDef.constant(true).isTrue(), StatementDef.multi(), ExpressionDef.constant(1).returning())))));
        assertThrows(IllegalStateException.class, () -> render(switchWithYieldCase(new ExpressionDef.SwitchYieldCase(INT,
            new StatementDef.Return(null)))));
    }

    // JavaPoetSourceGenerator 1800-1831: a yield case declaring a local, branching with `if` and `if/else` before it yields
    @Test
    void switchYieldCasesWithLocalsAndBranchesYield() throws Exception {
        var def = ClassDef.builder("test.YieldingBranches").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", int.class).returns(int.class)
                .build((self, p) -> {
                    var doubled = new VariableDef.Local("doubled", INT);
                    var ifCase = new ExpressionDef.SwitchYieldCase(INT, StatementDef.multi(
                        doubled.defineAndAssign(p.get(0).math(MathBinaryOperation.OpType.MULTIPLICATION, ExpressionDef.constant(2))),
                        doubled.compare(OpType.GREATER_THAN, ExpressionDef.constant(2)).doIf(doubled.returning()),
                        ExpressionDef.constant(-1).returning()));
                    var ifElseCase = new ExpressionDef.SwitchYieldCase(INT, new StatementDef.IfElse(
                        p.get(0).compare(OpType.GREATER_THAN, ExpressionDef.constant(8)),
                        ExpressionDef.constant(100).returning(), ExpressionDef.constant(-100).returning()));
                    Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
                    cases.put(ExpressionDef.constant(1), ifCase);
                    cases.put(ExpressionDef.constant(5), ifCase);
                    cases.put(ExpressionDef.constant(7), ifElseCase);
                    cases.put(ExpressionDef.constant(9), ifElseCase);
                    return p.get(0).asExpressionSwitch(INT, cases, p.get(0)).returning();
                }))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var method = cls.getMethod("call", int.class);
            var instance = cls.getConstructor().newInstance();
            assertEquals(-1, method.invoke(instance, 1));
            assertEquals(10, method.invoke(instance, 5));
            assertEquals(-100, method.invoke(instance, 7));
            assertEquals(100, method.invoke(instance, 9));
            assertEquals(3, method.invoke(instance, 3));
        }
    }

    // JavaPoetSourceGenerator 1269; JavaExpressionRules 689: a lambda of two parameters, and one whose single
    // expression body is a void call
    @Test
    void lambdasWithTwoParametersAndAVoidBodyRun() throws Exception {
        var biFunction = TypeDef.parameterized(BiFunction.class, Integer.class, Integer.class, Integer.class);
        var apply = BiFunction.class.getMethod("apply", Object.class, Object.class);
        var clear = List.class.getMethod("clear");
        var run = Runnable.class.getMethod("run");
        var def = ClassDef.builder("test.Lambdas").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("sum").addModifiers(Modifier.PUBLIC).addParameter("a", int.class).addParameter("b", int.class).returns(int.class)
                .build((self, p) -> biFunction.getLambda().implement(List.of("x", "y"),
                        (lambdaThis, lp) -> lp.get(0).cast(int.class).math(MathBinaryOperation.OpType.ADDITION, lp.get(1).cast(int.class)).returning())
                    .newLocal("adder", adder -> adder.invoke(apply, p.get(0), p.get(1)).returning())))
            .addMethod(MethodDef.builder("cleared").addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(List.class, String.class)).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Runnable.class).getLambda().implement((lambdaThis, lp) -> p.get(0).invoke(clear).returning())
                    .newLocal("clearer", clearer -> StatementDef.multi(clearer.invoke(run), p.get(0).invoke("size", INT).returning()))))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(7, cls.getMethod("sum", int.class, int.class).invoke(instance, 3, 4));
            var items = new ArrayList<>(List.of("a", "b"));
            assertEquals(0, cls.getMethod("cleared", List.class).invoke(instance, items));
            assertTrue(items.isEmpty());
        }
    }

    // JavaPoetSourceGenerator 1316, 1703, 1712: a concatenation of two primitives, and the structural equality of primitives
    @Test
    void primitiveConcatenationAndEqualityUseTheirOperators() throws Exception {
        var def = ClassDef.builder("test.PrimitiveOperators").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("joined").addModifiers(Modifier.PUBLIC).addParameter("a", int.class).addParameter("b", int.class).returns(String.class)
                .build((self, p) -> p.get(0).stringConcat(p.get(1)).returning()))
            .addMethod(MethodDef.builder("same").addModifiers(Modifier.PUBLIC).addParameter("a", int.class).addParameter("b", int.class).returns(boolean[].class)
                .build((self, p) -> TypeDef.Primitive.BOOLEAN.array().instantiate(
                    p.get(0).equalsStructurally(p.get(1)),
                    p.get(0).notEqualsStructurally(p.get(1)),
                    p.get(0).equalsStructurally(ExpressionDef.constant(1))).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("12", cls.getMethod("joined", int.class, int.class).invoke(instance, 1, 2));
            assertArrayEquals(new boolean[]{true, false, true}, (boolean[]) cls.getMethod("same", int.class, int.class).invoke(instance, 1, 1));
            assertArrayEquals(new boolean[]{false, true, false}, (boolean[]) cls.getMethod("same", int.class, int.class).invoke(instance, 2, 1));
        }
    }

    // JavaPoetSourceGenerator 1846, 1850, 1879-1881, 1886-1889: constants of an enum named by a string, a float, the
    // wrappers of long, float and double, an array class, and a value of another type written as it is
    @Test
    void constantsOfEveryKindAreWritten() throws Exception {
        var def = ClassDef.builder("test.Constants").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("values").addModifiers(Modifier.PUBLIC).returns(Object[].class)
                .build((self, p) -> TypeDef.OBJECT.array().instantiate(
                    new ExpressionDef.Constant(ClassTypeDef.of(RetentionPolicy.class), "SOURCE"),
                    ExpressionDef.constant(RetentionPolicy.RUNTIME),
                    ExpressionDef.constant(1.5f),
                    new ExpressionDef.Constant(ClassTypeDef.of(Long.class), 7L),
                    new ExpressionDef.Constant(ClassTypeDef.of(Float.class), 2.5f),
                    new ExpressionDef.Constant(ClassTypeDef.of(Double.class), 3.5d),
                    new ExpressionDef.Constant(TypeDef.CLASS, TypeDef.array(INT)),
                    new ExpressionDef.Constant(ClassTypeDef.of(List.class), "java.util.List.of()")
                ).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertArrayEquals(new Object[]{RetentionPolicy.SOURCE, RetentionPolicy.RUNTIME, 1.5f, 7L, 2.5f, 3.5d, int[].class, List.of()},
                (Object[]) cls.getMethod("values").invoke(cls.getConstructor().newInstance()));
        }
    }

    // JavaPoetSourceGenerator 1866, 1873, 1891, 1901; JavaPoetNames 164: constants no source spells, and a primitive
    // the generator does not know
    @Test
    void constantsOfUnsupportedShapesAreRejected() {
        assertThrows(IllegalStateException.class, () -> render(classWithBody("VariableArray", Object[].class,
            new ExpressionDef.Constant(TypeDef.array(TypeDef.variable("T")), new Object[0]).returning())));
        assertThrows(IllegalStateException.class, () -> render(classWithBody("NotAnArray", int[].class,
            new ExpressionDef.Constant(TypeDef.array(INT), "text").returning())));
        assertThrows(IllegalStateException.class, () -> render(classWithBody("VariableConstant", Object.class,
            new ExpressionDef.Constant(TypeDef.variable("T"), 1).returning())));
        assertThrows(IllegalStateException.class, () -> render(classWithBody("WildcardClass", Class.class,
            new ExpressionDef.Constant(TypeDef.CLASS, TypeDef.wildcard()).returning())));
        assertThrows(IllegalStateException.class, () -> render(ClassDef.builder("test.OddPrimitive").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("odd").addModifiers(Modifier.PUBLIC).returns(new TypeDef.Primitive(String.class))
                .build((self, p) -> ExpressionDef.constant("x").returning())).build()));
    }

    // JavaSourceRules 99: a blank final static field of a class without a static initializer cannot be definitely
    // assigned, so it loses `final`
    @Test
    void blankFinalStaticFieldWithoutInitializerLosesFinal() throws Exception {
        var def = ClassDef.builder("test.BlankFinal").addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("COUNT", int.class).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build())
            .build();
        try (var loader = compile(def)) {
            var field = loader.loadClass(def.getName()).getField("COUNT");
            assertFalse(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            assertEquals(0, field.get(null));
        }
    }

    // JavaSourceRules 246-258, 266-268, 275-283: a loop over a constant condition never completes, so the statement
    // after it is dropped as javac would reject it as unreachable
    @TestFactory
    Stream<DynamicTest> constantLoopConditionsEndTheMethod() {
        var yes = ExpressionDef.constant(true);
        var no = ExpressionDef.constant(false);
        Map<String, ExpressionDef> conditions = new LinkedHashMap<>();
        conditions.put("and", yes.isTrue().and(yes.isTrue()));
        conditions.put("or", no.isTrue().or(yes.isTrue()));
        conditions.put("booleansEqual", yes.equalsReferentially(yes));
        conditions.put("booleansNotEqual", yes.notEqualsReferentially(no));
        conditions.put("numbersEqual", ExpressionDef.constant(1).equalsReferentially(ExpressionDef.constant(1)));
        conditions.put("greater", ExpressionDef.constant(2).compare(OpType.GREATER_THAN, ExpressionDef.constant(1)));
        conditions.put("less", ExpressionDef.constant(1).compare(OpType.LESS_THAN, ExpressionDef.constant(2)));
        conditions.put("greaterOrEqual", ExpressionDef.constant(2).compare(OpType.GREATER_THAN_OR_EQUAL, ExpressionDef.constant(2)));
        conditions.put("lessOrEqual", ExpressionDef.constant(1).compare(OpType.LESS_THAN_OR_EQUAL, ExpressionDef.constant(2)));
        conditions.put("notEqual", ExpressionDef.constant(1).compare(OpType.NOT_EQUAL_TO, ExpressionDef.constant(2)));
        conditions.put("chars", ExpressionDef.constant('b').notEqualsReferentially(ExpressionDef.constant('a')));
        return conditions.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            var def = ClassDef.builder("test.Loop" + entry.getKey()).addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class).build((self, p) -> {
                    var i = new VariableDef.Local("i", INT);
                    return StatementDef.multi(
                        i.defineAndAssign(ExpressionDef.constant(0)),
                        entry.getValue().whileLoop(StatementDef.multi(
                            i.compare(OpType.GREATER_THAN, ExpressionDef.constant(3)).doIf(i.returning()),
                            i.assign(i.math(MathBinaryOperation.OpType.ADDITION, ExpressionDef.constant(1))))),
                        ExpressionDef.constant(-1).returning());
                })).build();
            try (var loader = compile(def)) {
                var cls = loader.loadClass(def.getName());
                assertEquals(4, cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
            }
        }));
    }

    // JavaSourceRules 48: a catch clause in an interface and a record, which declare no fields to clash with
    @Test
    void catchClausesInInterfaceAndRecordMethodsRun() throws Exception {
        var parseInt = Integer.class.getMethod("parseInt", String.class);
        var parse = MethodDef.builder("parse").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).addParameter("text", String.class).returns(int.class)
            .build((self, p) -> StatementDef.doTry(ClassTypeDef.of(Integer.class).invokeStatic(parseInt, p.get(0)).returning())
                .doCatch(NumberFormatException.class, e -> ExpressionDef.constant(-1).returning()));
        var parser = InterfaceDef.builder("test.Parser").addModifiers(Modifier.PUBLIC).addMethod(parse).build();
        var impl = ClassDef.builder("test.ParserImpl").addModifiers(Modifier.PUBLIC).addSuperinterface(parser.asTypeDef()).build();
        var record = RecordDef.builder("test.ParsedText").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("text").ofType(String.class).build())
            .addMethod(MethodDef.builder("parse").addModifiers(Modifier.PUBLIC).addParameter("text", String.class).returns(int.class)
                .build((self, p) -> StatementDef.doTry(ClassTypeDef.of(Integer.class).invokeStatic(parseInt, p.get(0)).returning())
                    .doCatch(NumberFormatException.class, e -> ExpressionDef.constant(-1).returning())))
            .build();
        try (var loader = compile(parser, impl, record)) {
            var implClass = loader.loadClass(impl.getName());
            var method = implClass.getMethod("parse", String.class);
            assertEquals(12, method.invoke(implClass.getConstructor().newInstance(), "12"));
            assertEquals(-1, method.invoke(implClass.getConstructor().newInstance(), "x"));
            var recordClass = loader.loadClass(record.getName());
            var instance = recordClass.getConstructor(String.class).newInstance("t");
            assertEquals(34, recordClass.getMethod("parse", String.class).invoke(instance, "34"));
            assertEquals(-1, recordClass.getMethod("parse", String.class).invoke(instance, "y"));
        }
    }

    // JavaPoetNames 186: a type variable an enum method names without declaring it is written as its erasure
    @Test
    void undeclaredVariableInAnEnumMethodErasesToObject() throws Exception {
        var def = EnumDef.builder("test.Echoing").addModifiers(Modifier.PUBLIC).addEnumConstant("ONE")
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.variable("T")).returns(Object.class)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("v", cls.getMethod("echo", Object.class).invoke(cls.getField("ONE").get(null), "v"));
        }
    }

    // JavaPoetNames 162; JavaExpressionRules 359-366, 378: a double return, every wrapper cast to its primitive, and an
    // int narrowed to a char parameter
    @Test
    void wrappersConvertToTheirPrimitives() throws Exception {
        var valueOf = String.class.getMethod("valueOf", char.class);
        var letter = MethodDef.builder("letter").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("c", char.class).returns(String.class)
            .build((self, p) -> ClassTypeDef.of(String.class).invokeStatic(valueOf, p.get(0)).returning());
        var not = MethodDef.builder("not").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("f", boolean.class).returns(boolean.class)
            .build((self, p) -> p.get(0).isFalse().returning());
        var def = ClassDef.builder("test.Unboxing").addModifiers(Modifier.PUBLIC)
            .addMethod(letter)
            .addMethod(MethodDef.builder("sum").addModifiers(Modifier.PUBLIC)
                .addParameter("d", Double.class).addParameter("f", Float.class).addParameter("l", Long.class)
                .addParameter("s", Short.class).addParameter("b", Byte.class).returns(double.class)
                // The model converts each right operand to the type of the left one, so the double comes first
                .build((self, p) -> p.get(0).cast(double.class)
                    .math(MathBinaryOperation.OpType.ADDITION, p.get(1).cast(float.class))
                    .math(MathBinaryOperation.OpType.ADDITION, p.get(2).cast(long.class))
                    .math(MathBinaryOperation.OpType.ADDITION, p.get(3).cast(short.class))
                    .math(MathBinaryOperation.OpType.ADDITION, p.get(4).cast(byte.class)).returning()))
            .addMethod(MethodDef.builder("flag").addModifiers(Modifier.PUBLIC).addParameter("f", Boolean.class).returns(boolean.class)
                .build((self, p) -> p.get(0).cast(boolean.class).returning()))
            .addMethod(MethodDef.builder("letterOf").addModifiers(Modifier.PUBLIC).addParameter("c", Character.class).returns(char.class)
                .build((self, p) -> p.get(0).cast(char.class).returning()))
            .addMethod(not)
            // A wrapper passed to a parameter of its primitive is unboxed as it is
            .addMethod(MethodDef.builder("negated").addModifiers(Modifier.PUBLIC).addParameter("f", Boolean.class).returns(boolean.class)
                .build((self, p) -> ClassTypeDef.of("test.Unboxing").invokeStatic(not, p.get(0)).returning()))
            .addMethod(MethodDef.builder("codeOf").addModifiers(Modifier.PUBLIC).addParameter("c", Character.class).returns(String.class)
                .build((self, p) -> ClassTypeDef.of("test.Unboxing").invokeStatic(letter, p.get(0)).returning()))
            .addMethod(MethodDef.builder("code").addModifiers(Modifier.PUBLIC).addParameter("code", int.class).returns(String.class)
                .build((self, p) -> ClassTypeDef.of("test.Unboxing").invokeStatic(letter, p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(0.25d + 0.5f + 4L + 2 + 1, cls.getMethod("sum", Double.class, Float.class, Long.class, Short.class, Byte.class)
                .invoke(instance, 0.25d, 0.5f, 4L, (short) 2, (byte) 1));
            assertEquals(Boolean.TRUE, cls.getMethod("flag", Boolean.class).invoke(instance, Boolean.TRUE));
            assertEquals('q', cls.getMethod("letterOf", Character.class).invoke(instance, 'q'));
            assertEquals(Boolean.FALSE, cls.getMethod("negated", Boolean.class).invoke(instance, Boolean.TRUE));
            assertEquals("z", cls.getMethod("codeOf", Character.class).invoke(instance, 'z'));
            assertEquals("A", cls.getMethod("code", int.class).invoke(instance, 65));
        }
    }

    // JavaExpressionRules 143, 308-309: an element of a two-dimensional array an override narrowed has the narrowed
    // component, and an array returned as an array of a type variable is cast
    @Test
    void narrowedArrayElementsAndVariableArraysAreConverted() throws Exception {
        var t = TypeDef.variable("T");
        var grid = InterfaceDef.builder("test.Grid").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("firstRow").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("cells", TypeDef.array(t, 2)).returns(TypeDef.array(t)).build())
            .build();
        var def = ClassDef.builder("test.StringGrid").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(grid.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("firstRow").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("cells", Object[][].class).returns(Object[].class)
                .build((self, p) -> p.get(0).arrayElement(0).returning()))
            .addMethod(MethodDef.builder("asVariables").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
                .addParameter("values", String[].class).returns(TypeDef.array(t))
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(grid, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var firstRow = cls.getMethod("firstRow", String[][].class);
            assertEquals(String[].class, firstRow.getReturnType());
            assertArrayEquals(new String[]{"a", "b"}, (String[]) firstRow.invoke(instance, (Object) new String[][]{{"a", "b"}, {"c"}}));
            var values = new String[]{"x"};
            assertSame(values, cls.getMethod("asVariables", String[].class).invoke(instance, (Object) values));
        }
    }

    // JavaExpressionRules 531: a value of an unbounded variable passed as a bounded variable of a generated method is
    // cast to the raw bound, which the callee's bound does not accept as is
    @Test
    void unboundedVariableIsCastToTheRawBoundOfTheCallee() throws Exception {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, TypeDef.variable("T")));
        var u = TypeDef.variable("U");
        var bigger = MethodDef.builder("bigger").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("a", t).addParameter("b", t).returns(t)
            .build((self, p) -> p.get(0).invoke("compareTo", List.of(TypeDef.OBJECT), INT, List.of(p.get(1)))
                .compare(OpType.GREATER_THAN, ExpressionDef.constant(0)).doIfElse(p.get(0), p.get(1)).returning());
        var def = ClassDef.builder("test.Picker").addModifiers(Modifier.PUBLIC).addMethod(bigger)
            .addMethod(MethodDef.builder("pass").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("a", u).addParameter("b", u).returns(u)
                .build((self, p) -> ClassTypeDef.of("test.Picker").invokeStatic(bigger, p.get(0), p.get(1)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(9, cls.getMethod("pass", Object.class, Object.class).invoke(cls.getConstructor().newInstance(), 4, 9));
        }
    }

    // JavaExpressionRules 591, 656, 662-665; JavaConversionRenderer 179-206: arrays passed as arrays of a variable -
    // an unbounded one of the callee, one of the class of a generated receiver, and one of two bounds, whose
    // component satisfies them or is converted by the intersection helper
    @Test
    void arraysOfVariablesAreConvertedByTheirBounds() throws Exception {
        var e = TypeDef.variable("E");
        var asList = MethodDef.builder("asList").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(e)
            .addParameter("a", TypeDef.array(e)).returns(TypeDef.parameterized(List.class, e)).build();
        var t = TypeDef.variable("T");
        var firstOfBag = MethodDef.builder("first").addModifiers(Modifier.PUBLIC).addParameter("items", TypeDef.array(t)).returns(t)
            .build((self, p) -> p.get(0).arrayElement(0).returning());
        var bag = ClassDef.builder("test.Bag").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addMethod(firstOfBag).build();
        var s = TypeDef.variable("S", TypeDef.of(Number.class), TypeDef.of(java.io.Serializable.class));
        var first = MethodDef.builder("firstSerializable").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(s)
            .addParameter("values", TypeDef.array(s)).returns(s).build();
        var def = ClassDef.builder("test.ArrayArguments").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("listed").addModifiers(Modifier.PUBLIC).addParameter("values", String[].class)
                .returns(TypeDef.parameterized(List.class, String.class))
                .build((self, p) -> ClassTypeDef.of(Arrays.class).invokeStatic(asList, p.get(0)).returning()))
            .addMethod(MethodDef.builder("firstOf").addModifiers(Modifier.PUBLIC)
                .addParameter("bag", TypeDef.parameterized(bag.asTypeDef(), TypeDef.STRING)).addParameter("items", String[].class).returns(String.class)
                .build((self, p) -> p.get(0).invoke(firstOfBag, p.get(1)).returning()))
            .addMethod(MethodDef.builder("firstInteger").addModifiers(Modifier.PUBLIC).addParameter("values", Integer[].class).returns(Number.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(first, p.get(0)).returning()))
            .addMethod(MethodDef.builder("firstObject").addModifiers(Modifier.PUBLIC).addParameter("values", Object[].class).returns(Number.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(first, p.get(0)).returning()))
            .build();
        try (var loader = compile(bag, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(List.of("a", "b"), cls.getMethod("listed", String[].class).invoke(instance, (Object) new String[]{"a", "b"}));
            var bagInstance = loader.loadClass(bag.getName()).getConstructor().newInstance();
            assertEquals("first", cls.getMethod("firstOf", loader.loadClass(bag.getName()), String[].class).invoke(instance, bagInstance, new String[]{"first", "second"}));
            assertEquals(5, cls.getMethod("firstInteger", Integer[].class).invoke(instance, (Object) new Integer[]{5, 6}));
            // An array of numbers is what the callee's array of `S extends Number` can be, as the bytecode's checkcast needs
            assertEquals(7, cls.getMethod("firstObject", Object[].class).invoke(instance, (Object) new Integer[]{7, 8}));
        }
    }

    // JavaExpressionRules 665; JavaConversionRenderer 179-206: an array converted to an array of a variable bounded
    // by itself - `N extends Number & Comparable<N>` - is passed through the intersection helper, whose raw
    // `Comparable` bound javac cannot infer against the self-referential one
    @Test
    void intersectionArrayOfASelfReferentialBoundIsConverted() throws Exception {
        var n = TypeDef.variable("N", TypeDef.of(Number.class), TypeDef.parameterized(Comparable.class, TypeDef.variable("N")));
        var first = MethodDef.builder("first").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(n)
            .addParameter("values", TypeDef.array(n)).returns(n).build();
        var def = ClassDef.builder("test.SelfBoundedArray").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("firstObject").addModifiers(Modifier.PUBLIC).addParameter("values", Object[].class).returns(Number.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(first, p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(7, cls.getMethod("firstObject", Object[].class).invoke(cls.getConstructor().newInstance(), (Object) new Integer[]{7, 8}));
        }
    }

    // JavaExpressionRules 764-774: a parameterized type argument fits the declared one only with the same arguments,
    // a variable, or the same class of arguments that fit
    @Test
    void parameterizedTypeArgumentsDecideTheRawCast() throws Exception {
        var count = Helpers.class.getMethod("count", Map.class);
        var countAny = Helpers.class.getMethod("countAny", Map.class);
        var def = ClassDef.builder("test.MapArguments").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("counts").addModifiers(Modifier.PUBLIC)
                .addParameter("ok", TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.parameterized(List.class, TypeDef.STRING)))
                .addParameter("ints", TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.parameterized(List.class, TypeDef.of(Integer.class))))
                .addParameter("sets", TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.parameterized(Set.class, TypeDef.STRING)))
                .addParameter("flat", TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.STRING))
                .returns(int[].class)
                .build((self, p) -> INT.array().instantiate(
                    ClassTypeDef.of(Helpers.class).invokeStatic(count, p.get(0)),
                    ClassTypeDef.of(Helpers.class).invokeStatic(count, p.get(1)),
                    ClassTypeDef.of(Helpers.class).invokeStatic(count, p.get(2)),
                    ClassTypeDef.of(Helpers.class).invokeStatic(count, p.get(3)),
                    ClassTypeDef.of(Helpers.class).invokeStatic(countAny, p.get(0))).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertArrayEquals(new int[]{1, 2, 3, 4, 1}, (int[]) cls.getMethod("counts", Map.class, Map.class, Map.class, Map.class)
                .invoke(cls.getConstructor().newInstance(), Map.of("a", List.of()), Map.of("a", List.of(), "b", List.of()),
                    Map.of("a", Set.of(), "b", Set.of(), "c", Set.of()), Map.of("a", "", "b", "", "c", "", "d", "")));
        }
    }

    // JavaExpressionRules 763-774: a parameterized type argument whose own argument is a wildcard the value's argument
    // lies within is taken to fit, but type arguments are invariant: `Map<String, List<String>>` is no
    // `Map<String, List<? extends CharSequence>>` for javac, which needs the raw cast
    @Test
    void wildcardWithinAParameterizedTypeArgumentIsInvariant() throws Exception {
        var countWild = Helpers.class.getMethod("countWild", Map.class);
        var def = ClassDef.builder("test.WildMapArgument").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC)
                .addParameter("texts", TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.parameterized(List.class, TypeDef.STRING)))
                .returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(countWild, p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(1, cls.getMethod("count", Map.class).invoke(cls.getConstructor().newInstance(), Map.of("a", List.of("x"))));
        }
    }

    // JavaExpressionRules 806, 809, 815, 818: a wildcard bounded by a parameterization contains a variable, no array,
    // a generated class that is not generic, and no generic class named raw
    @Test
    void wildcardBoundsAgainstVariablesArraysAndGeneratedClasses() throws Exception {
        var sizes = Helpers.class.getMethod("sizes", List.class);
        var names = ClassDef.builder("test.Names").addModifiers(Modifier.PUBLIC).superclass(TypeDef.parameterized(ArrayList.class, TypeDef.STRING)).build();
        var u = TypeDef.variable("U", TypeDef.parameterized(List.class, TypeDef.wildcard()));
        var def = ClassDef.builder("test.WildcardBounds").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("ofVariable").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
                .addParameter("lists", TypeDef.parameterized(List.class, u)).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(sizes, p.get(0)).returning()))
            .addMethod(MethodDef.builder("ofArrays").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(List.class, TypeDef.array(TypeDef.STRING))).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(sizes, p.get(0)).returning()))
            .addMethod(MethodDef.builder("ofNames").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(List.class, names.asTypeDef())).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(sizes, p.get(0)).returning()))
            .addMethod(MethodDef.builder("ofRawLists").addModifiers(Modifier.PUBLIC)
                .addParameter("lists", TypeDef.parameterized(List.class, ClassTypeDef.of(ArrayList.class))).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(sizes, p.get(0)).returning()))
            .build();
        try (var loader = compile(names, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(1, cls.getMethod("ofVariable", List.class).invoke(instance, List.of(List.of())));
            assertEquals(2, cls.getMethod("ofArrays", List.class).invoke(instance, List.of(new String[0], new String[0])));
            assertEquals(1, cls.getMethod("ofNames", List.class).invoke(instance, List.of(loader.loadClass(names.getName()).getConstructor().newInstance())));
            assertEquals(3, cls.getMethod("ofRawLists", List.class).invoke(instance, List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>())));
        }
    }

    // JavaExpressionRules 732: a generated parameterized class, which cannot be loaded, passed where a compiled
    // method declares a parameterization of its supertype is taken to fit
    @Test
    void generatedSubtypePassedToACompiledParameterizedParameter() throws Exception {
        var t = TypeDef.variable("T");
        var names = ClassDef.builder("test.GenericNames").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .superclass(TypeDef.parameterized(ArrayList.class, t)).build();
        var unmodifiable = Collections.class.getMethod("unmodifiableList", List.class);
        var def = ClassDef.builder("test.NamesView").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("view").addModifiers(Modifier.PUBLIC)
                .addParameter("names", TypeDef.parameterized(names.asTypeDef(), TypeDef.STRING))
                .returns(TypeDef.parameterized(List.class, TypeDef.STRING))
                .build((self, p) -> ClassTypeDef.of(Collections.class).invokeStatic(unmodifiable, p.get(0)).returning()))
            .build();
        try (var loader = compile(names, def)) {
            var cls = loader.loadClass(def.getName());
            var namesClass = loader.loadClass(names.getName());
            @SuppressWarnings("unchecked") var instance = (List<String>) namesClass.getConstructor().newInstance();
            instance.add("n");
            var view = (List<?>) cls.getMethod("view", namesClass).invoke(cls.getConstructor().newInstance(), instance);
            assertEquals(List.of("n"), view);
            assertThrows(UnsupportedOperationException.class, () -> view.remove(0));
        }
    }

    // JavaExpressionRules 944, 263: an `or` wrapped in `isTrue` as an operand of `and` is grouped; a cast chain
    // through a primitive keeps the cast under it
    @Test
    void groupedConditionsAndCastChainsKeepTheirMeaning() throws Exception {
        var def = ClassDef.builder("test.Grouping").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("either").addModifiers(Modifier.PUBLIC)
                .addParameter("a", boolean.class).addParameter("b", boolean.class).addParameter("c", boolean.class).returns(boolean.class)
                .build((self, p) -> p.get(0).isTrue().or(p.get(1).isTrue()).isTrue().and(p.get(2).isTrue()).returning()))
            .addMethod(MethodDef.builder("widened").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(long.class)
                .build((self, p) -> p.get(0).cast(Integer.class).cast(int.class).cast(long.class).returning()))
            // JavaExpressionRules 225, 227: the precedence of the shifts and of xor
            .addMethod(MethodDef.builder("bits").addModifiers(Modifier.PUBLIC)
                .addParameter("a", int.class).addParameter("b", int.class).addParameter("c", int.class).returns(int[].class)
                .build((self, p) -> INT.array().instantiate(
                    p.get(0).math(MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT, p.get(1).math(MathBinaryOperation.OpType.ADDITION, p.get(2))),
                    p.get(0).math(MathBinaryOperation.OpType.BITWISE_XOR, p.get(1).math(MathBinaryOperation.OpType.BITWISE_OR, p.get(2))),
                    p.get(0).math(MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT, p.get(1)).math(MathBinaryOperation.OpType.BITWISE_AND, p.get(2))
                ).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var either = cls.getMethod("either", boolean.class, boolean.class, boolean.class);
            assertEquals(true, either.invoke(instance, false, true, true));
            assertEquals(false, either.invoke(instance, true, true, false));
            assertEquals(false, either.invoke(instance, false, false, true));
            assertEquals(5L, cls.getMethod("widened", Object.class).invoke(instance, 5));
            assertArrayEquals(new int[]{40 << (3 + 1), 40 ^ (3 | 1), (40 >>> 3) & 1},
                (int[]) cls.getMethod("bits", int.class, int.class, int.class).invoke(instance, 40, 3, 1));
        }
    }

    // JavaPoetSourceGenerator 1163-1164: a property read through its read member, as the compiler describes it
    @Test
    void propertyValueIsReadThroughItsAccessor() throws Exception {
        MethodElement getLabel = mock(MethodElement.class);
        when(getLabel.getName()).thenReturn("getLabel");
        when(getLabel.isPublic()).thenReturn(true);
        when(getLabel.getReturnType()).thenReturn(ClassElement.of(String.class));
        when(getLabel.getSuspendParameters()).thenReturn(new ParameterElement[0]);
        PropertyElement label = mock(PropertyElement.class);
        when(label.getName()).thenReturn("label");
        when(label.getType()).thenReturn(ClassElement.of(String.class));
        doReturn(Optional.of(getLabel)).when(label).getReadMember();
        var def = ClassDef.builder("test.PropertyReader").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("labelOf").addModifiers(Modifier.PUBLIC).addParameter("holder", LabelHolder.class).returns(String.class)
                .build((self, p) -> p.get(0).getPropertyValue(label).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("held", cls.getMethod("labelOf", LabelHolder.class).invoke(cls.getConstructor().newInstance(), new LabelHolder()));
        }
    }

    // JavaPoetSourceGenerator 1984-1995: a field read through the type being written by name is validated against
    // the fields of a class and an enum, and a record has none to validate
    @Test
    void fieldsReadThroughTheDeclaringTypeAreValidated() throws Exception {
        var owner = ClassTypeDef.of("test.Owner");
        var def = ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("count", int.class).addModifiers(Modifier.PUBLIC).initializer(ExpressionDef.constant(7)).build())
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> new VariableDef.Field(self, owner, "count", INT).returning()))
            .build();
        var kind = ClassTypeDef.of("test.Kind");
        var weight = FieldDef.builder("weight", int.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var enumDef = EnumDef.builder(kind.getName()).addModifiers(Modifier.PUBLIC).addEnumConstant("LIGHT", ExpressionDef.constant(3))
            .addField(weight).addAllFieldsConstructor(Modifier.PRIVATE)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> new VariableDef.Field(self, kind, "weight", INT).returning()))
            .build();
        try (var loader = compile(def, enumDef)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(7, cls.getMethod("read").invoke(cls.getConstructor().newInstance()));
            var kinds = loader.loadClass(enumDef.getName());
            assertEquals(3, kinds.getMethod("read").invoke(kinds.getField("LIGHT").get(null)));
        }
        assertThrows(IllegalStateException.class, () -> render(ClassDef.builder(owner.getName()).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> new VariableDef.Field(self, owner, "missing", INT).returning())).build()));
        assertThrows(IllegalStateException.class, () -> render(EnumDef.builder(kind.getName()).addModifiers(Modifier.PUBLIC).addEnumConstant("A")
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> new VariableDef.Field(self, kind, "missing", INT).returning())).build()));
        var pair = ClassTypeDef.of("test.Pair");
        assertThrows(IllegalStateException.class, () -> render(RecordDef.builder(pair.getName()).addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("left").ofType(int.class).build())
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> new VariableDef.Field(self, pair, "left", INT).returning())).build()));
    }

    // JavaPoetSourceGenerator 1603-1610; JavaConversionRenderer 113-130: a reference to a narrowed generated method
    // for a functional interface returning a generated generic interface named raw is read through `Optional.map`,
    // whose result is cast back to the interface type
    @Test
    void referenceReturningARawGeneratedInterfaceIsCastBack() throws Exception {
        var t = TypeDef.variable("T");
        var shape = InterfaceDef.builder("test.Shape").addModifiers(Modifier.PUBLIC).addTypeVariable(t).build();
        var circle = ClassDef.builder("test.Circle").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(shape.asTypeDef(), TypeDef.STRING)).build();
        var value = FieldDef.builder("value", Object.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> self.field(value).returning());
        var box = ClassDef.builder("test.Box").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, t)).addField(value).addAllFieldsConstructor(Modifier.PUBLIC).addMethod(get).build();
        var supplier = TypeDef.parameterized(Supplier.class, shape.asTypeDef());
        var def = ClassDef.builder("test.ShapePicker").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), circle.asTypeDef())).returns(supplier)
                .build((self, p) -> supplier.methodReference(p.get(0), get).returning()))
            .build();
        try (var loader = compile(shape, circle, box, def)) {
            var boxClass = loader.loadClass(box.getName());
            var aCircle = loader.loadClass(circle.getName()).getConstructor().newInstance();
            var aBox = boxClass.getConstructor(Object.class).newInstance(aCircle);
            var cls = loader.loadClass(def.getName());
            var picked = (Supplier<?>) cls.getMethod("pick", boxClass).invoke(cls.getConstructor().newInstance(), aBox);
            assertSame(aCircle, picked.get());
            assertThrows(NullPointerException.class, () -> {
                try {
                    cls.getMethod("pick", boxClass).invoke(cls.getConstructor().newInstance(), (Object) null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
    }

    // TypeHierarchy 241, 245: a simple name resolves against the definition being written and its inner types
    @Test
    void simpleNamesResolveAgainstTheDefinitionAndItsInnerTypes() throws Exception {
        var def = ClassDef.builder("test.Outer").addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("Inner").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .addMethod(MethodDef.builder("names").addModifiers(Modifier.PUBLIC)
                .addParameter("outer", ClassTypeDef.of("Outer")).addParameter("inner", ClassTypeDef.of("Inner")).returns(String.class)
                .build((self, p) -> p.get(0).invokeGetClass().invoke("getSimpleName", TypeDef.STRING)
                    .stringConcat(p.get(1).invokeGetClass().invoke("getSimpleName", TypeDef.STRING)).returning()))
            .build();
        try (var loader = compile(def)) {
            var outer = loader.loadClass(def.getName());
            var inner = loader.loadClass("test.Outer$Inner");
            assertEquals("OuterInner", outer.getMethod("names", outer, inner)
                .invoke(outer.getConstructor().newInstance(), outer.getConstructor().newInstance(), inner.getConstructor().newInstance()));
        }
    }

    // TypeHierarchy 281, 330: a generated value inherits `Object`, which an unbounded variable of the callee needs; and
    // the bound of a returned variable is searched through a hierarchy that reaches an interface twice
    @Test
    void generatedValuesSatisfyBoundsThroughTheirModel() throws Exception {
        var t = TypeDef.variable("T");
        var requireNonNull = MethodDef.builder("requireNonNull").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("obj", t).returns(t).build();
        var gen = ClassDef.builder("test.Gen").addModifiers(Modifier.PUBLIC).build();
        var serializable = ClassTypeDef.of(java.io.Serializable.class);
        var base = ClassDef.builder("test.Base").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(serializable).addSuperinterface(TypeDef.parameterized(Comparable.class, TypeDef.STRING))
            .addMethod(MethodDef.builder("compareTo").addModifiers(Modifier.PUBLIC).overrides().addParameter("other", Object.class).returns(int.class)
                .build((self, p) -> ExpressionDef.constant(0).returning()))
            .build();
        var child = ClassDef.builder("test.Child").addModifiers(Modifier.PUBLIC).superclass(base.asTypeDef()).addSuperinterface(serializable).build();
        var u = TypeDef.variable("U", TypeDef.parameterized(Comparable.class, TypeDef.STRING));
        var def = ClassDef.builder("test.Bounds").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("kept").addModifiers(Modifier.PUBLIC).addParameter("value", gen.asTypeDef()).returns(Object.class)
                .build((self, p) -> ClassTypeDef.of(java.util.Objects.class).invokeStatic(requireNonNull, p.get(0)).returning()))
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("child", child.asTypeDef()).returns(u)
                .build((self, p) -> p.get(0).returning()))
            .build();
        try (var loader = compile(gen, base, child, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var aGen = loader.loadClass(gen.getName()).getConstructor().newInstance();
            assertSame(aGen, cls.getMethod("kept", loader.loadClass(gen.getName())).invoke(instance, aGen));
            var aChild = loader.loadClass(child.getName()).getConstructor().newInstance();
            assertSame(aChild, cls.getMethod("pick", loader.loadClass(child.getName())).invoke(instance, aChild));
        }
    }

    // OverloadRules 93: an array passed for a variable of the callee, where another overload takes the array itself -
    // `Stream.of(T)` next to `Stream.of(T...)` - is not pinned, so javac selects the varargs overload that the model
    // does not call, which streams the elements rather than the array
    @Test
    void streamOfAnArrayCallsTheSingleElementOverload() throws Exception {
        var t = TypeDef.variable("T");
        var of = MethodDef.builder("of").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("t", t).returns(TypeDef.parameterized(java.util.stream.Stream.class, t)).build();
        var def = ClassDef.builder("test.Streamed").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("count").addModifiers(Modifier.PUBLIC).addParameter("values", String[].class).returns(long.class)
                .build((self, p) -> ClassTypeDef.of(java.util.stream.Stream.class).invokeStatic(of, p.get(0)).invoke("count", TypeDef.Primitive.LONG).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals(1L, cls.getMethod("count", String[].class).invoke(cls.getConstructor().newInstance(), (Object) new String[]{"a", "b"}));
        }
    }

    // OverloadRules 93; OverrideResolver 578: an array passed as an array of the callee's variable, where another
    // overload takes the array's own type, is not pinned either, so javac selects the more specific overload
    @Test
    void arrayPassedAsArrayOfTheCalleeVariableKeepsTheOverload() throws Exception {
        var t = TypeDef.variable("T");
        var copy = MethodDef.builder("copy").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("values", TypeDef.array(t)).returns(String.class).build();
        var def = ClassDef.builder("test.Copied").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("copied").addModifiers(Modifier.PUBLIC).addParameter("values", String[].class).returns(String.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(copy, p.get(0)).returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("generic", cls.getMethod("copied", String[].class).invoke(cls.getConstructor().newInstance(), (Object) new String[]{"a"}));
        }
    }

    // OverloadRules 199, 211, 256: a raw receiver erases a variable bounded by another variable; an overload taking a
    // generated type, and a value that is an array of one, are taken to fit, so the model's overload is pinned
    @Test
    void overloadsAroundGeneratedAndRawTypesArePinned() throws Exception {
        var pick = Helpers.Chain.class.getMethod("pick", Object.class);
        var gen = ClassDef.builder("test.Gen2").addModifiers(Modifier.PUBLIC).build();
        var chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var chooseGen = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", gen.asTypeDef()).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("gen").returning());
        var countObject = MethodDef.builder("count").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var countText = MethodDef.builder("count").addModifiers(Modifier.PUBLIC).addParameter("value", String.class).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("text").returning());
        var def = ClassDef.builder("test.Chooser").addModifiers(Modifier.PUBLIC)
            .addMethod(chooseObject).addMethod(chooseGen).addMethod(countObject).addMethod(countText)
            .addMethod(MethodDef.builder("viaObject").addModifiers(Modifier.PUBLIC).addParameter("text", String.class).returns(String.class)
                .build((self, p) -> self.invoke(chooseObject, p.get(0)).returning()))
            .addMethod(MethodDef.builder("counted").addModifiers(Modifier.PUBLIC).addParameter("values", gen.asTypeDef().array()).returns(String.class)
                .build((self, p) -> self.invoke(countObject, p.get(0)).returning()))
            .addMethod(MethodDef.builder("picked").addModifiers(Modifier.PUBLIC).addParameter("chain", Helpers.Chain.class).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(pick, p.get(1)).returning()))
            .build();
        try (var loader = compile(gen, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals("object", cls.getMethod("viaObject", String.class).invoke(instance, "t"));
            var genArray = java.lang.reflect.Array.newInstance(loader.loadClass(gen.getName()), 0);
            assertEquals("object", cls.getMethod("counted", genArray.getClass()).invoke(instance, genArray));
            assertEquals("v", cls.getMethod("picked", Helpers.Chain.class, Object.class).invoke(instance, new Helpers.Chain<>(), "v"));
        }
    }

    // TypeHierarchy 491: a self-typed return overrides the variable of the supertype, erased as the class itself
    @Test
    void selfTypedReturnOverridesTheSupplierOfItself() throws Exception {
        var def = ClassDef.builder("test.SelfSupplier").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, ClassTypeDef.of("test.SelfSupplier")))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.THIS)
                .build((self, p) -> self.returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertSame(instance, ((Supplier<?>) instance).get());
            assertEquals(cls, cls.getMethod("get").getReturnType());
        }
    }

    // OverrideResolver 262, 290, 292, 296, 301, 308, 354, 392: calls through receivers whose type arguments are
    // wildcards take a captured result as the variable's bound, take no value for a captured parameter, and a
    // reference for a `Supplier<? super String>` is written as it is
    @Test
    void wildcardReceiversOfNarrowedGeneratedClasses() throws Exception {
        var a = TypeDef.variable("A");
        var b = TypeDef.variable("B", a);
        var c = TypeDef.variable("C", TypeDef.of(CharSequence.class));
        var pair = InterfaceDef.builder("test.Pair").addModifiers(Modifier.PUBLIC).addTypeVariable(a).addTypeVariable(TypeDef.variable("B"))
            .addMethod(MethodDef.builder("first").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(a).build())
            .addMethod(MethodDef.builder("second").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.variable("B")).build())
            .build();
        var first = FieldDef.builder("first", Object.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var second = FieldDef.builder("second", Object.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var accepted = FieldDef.builder("accepted", Object.class).addModifiers(Modifier.PUBLIC).build();
        var firstOf = MethodDef.builder("first").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class).build((self, p) -> self.field(first).returning());
        var secondOf = MethodDef.builder("second").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class).build((self, p) -> self.field(second).returning());
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> self.field(accepted).put(p.get(0)));
        var box = ClassDef.builder("test.Box2").addModifiers(Modifier.PUBLIC).addTypeVariable(a).addTypeVariable(b)
            .addSuperinterface(TypeDef.parameterized(pair.asTypeDef(), a, b)).addSuperinterface(TypeDef.parameterized(Consumer.class, a))
            .addField(first).addField(second).addField(accepted).addConstructor(List.of(ParameterDef.of("first", TypeDef.OBJECT), ParameterDef.of("second", TypeDef.OBJECT)), Modifier.PUBLIC)
            .addMethod(firstOf).addMethod(secondOf).addMethod(accept)
            .build();
        var value = FieldDef.builder("value", Object.class).addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class).build((self, p) -> self.field(value).returning());
        var bounded = ClassDef.builder("test.Bounded").addModifiers(Modifier.PUBLIC).addTypeVariable(c)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, c)).addField(value).addAllFieldsConstructor(Modifier.PUBLIC).addMethod(get).build();
        var wild = TypeDef.wildcard();
        var supplierOfSuper = TypeDef.parameterized(Supplier.class, TypeDef.wildcardSupertypeOf(TypeDef.STRING));
        var def = ClassDef.builder("test.Wildcards").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("firstOf").addModifiers(Modifier.PUBLIC).addParameter("box", TypeDef.parameterized(box.asTypeDef(), wild, wild)).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(firstOf).returning()))
            .addMethod(MethodDef.builder("secondOf").addModifiers(Modifier.PUBLIC).addParameter("box", TypeDef.parameterized(box.asTypeDef(), wild, wild)).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(secondOf).returning()))
            .addMethod(MethodDef.builder("secondOfStrings").addModifiers(Modifier.PUBLIC)
                .addParameter("box", TypeDef.parameterized(box.asTypeDef(), TypeDef.wildcardSubtypeOf(TypeDef.STRING), wild)).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(secondOf).returning()))
            .addMethod(MethodDef.builder("feed").addModifiers(Modifier.PUBLIC).addParameter("box", TypeDef.parameterized(box.asTypeDef(), wild, wild)).returns(void.class)
                .build((self, p) -> p.get(0).invoke(accept, ExpressionDef.nullValue())))
            .addMethod(MethodDef.builder("valueOf").addModifiers(Modifier.PUBLIC).addParameter("bounded", TypeDef.parameterized(bounded.asTypeDef(), wild)).returns(Object.class)
                .build((self, p) -> p.get(0).invoke(get).returning()))
            .addMethod(MethodDef.builder("supplier").addModifiers(Modifier.PUBLIC).addParameter("bounded", TypeDef.parameterized(bounded.asTypeDef(), TypeDef.STRING)).returns(supplierOfSuper)
                .build((self, p) -> supplierOfSuper.methodReference(p.get(0), get).returning()))
            .build();
        try (var loader = compile(pair, box, bounded, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            var boxClass = loader.loadClass(box.getName());
            var aBox = boxClass.getConstructor(Object.class, Object.class).newInstance("a", "b");
            assertEquals("a", cls.getMethod("firstOf", boxClass).invoke(instance, aBox));
            assertEquals("b", cls.getMethod("secondOf", boxClass).invoke(instance, aBox));
            assertEquals("b", cls.getMethod("secondOfStrings", boxClass).invoke(instance, aBox));
            cls.getMethod("feed", boxClass).invoke(instance, aBox);
            assertEquals(null, boxClass.getField("accepted").get(aBox));
            var boundedClass = loader.loadClass(bounded.getName());
            var aBounded = boundedClass.getConstructor(Object.class).newInstance("v");
            assertEquals("v", cls.getMethod("valueOf", boundedClass).invoke(instance, aBounded));
            assertEquals("v", ((Supplier<?>) cls.getMethod("supplier", boundedClass).invoke(instance, aBounded)).get());
        }
    }

    // OverrideResolver 597-598, 600-601, 619, 634: the bounds of a compiled method's variables are compared as their
    // erasures, and the declaring type of a call is searched through a diamond of generated interfaces and a
    // compiled superclass
    @Test
    void declaringTypesAreFoundThroughHierarchies() throws Exception {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, TypeDef.wildcardSupertypeOf(TypeDef.variable("T"))));
        var sort = MethodDef.builder("sort").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addParameter("list", TypeDef.parameterized(List.class, t)).returns(void.class).build();
        var a = TypeDef.variable("A");
        var b = TypeDef.variable("B", a);
        var lift = MethodDef.builder("lift").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(a).addTypeVariable(b)
            .addParameter("b", b).returns(a).build();
        var i0 = InterfaceDef.builder("test.I0").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("i0").returning()))
            .build();
        var describe = i0.getMethods().get(0);
        var i1 = InterfaceDef.builder("test.I1").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addSuperinterface(TypeDef.parameterized(i0.asTypeDef(), t)).build();
        var i2 = InterfaceDef.builder("test.I2").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addSuperinterface(TypeDef.parameterized(i0.asTypeDef(), t)).build();
        var diamond = ClassDef.builder("test.Diamond").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(i1.asTypeDef(), TypeDef.STRING)).addSuperinterface(TypeDef.parameterized(i2.asTypeDef(), TypeDef.STRING)).build();
        var names = ClassDef.builder("test.NameList").addModifiers(Modifier.PUBLIC).superclass(TypeDef.parameterized(ArrayList.class, TypeDef.STRING)).build();
        var size = List.class.getMethod("size");
        var def = ClassDef.builder("test.Hierarchies").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("sorted").addModifiers(Modifier.PUBLIC).addParameter("items", TypeDef.parameterized(List.class, TypeDef.STRING))
                .returns(TypeDef.parameterized(List.class, TypeDef.STRING))
                .build((self, p) -> StatementDef.multi(ClassTypeDef.of(Collections.class).invokeStatic(sort, p.get(0)), p.get(0).returning())))
            .addMethod(MethodDef.builder("lifted").addModifiers(Modifier.PUBLIC).addParameter("text", String.class).returns(Object.class)
                .build((self, p) -> ClassTypeDef.of(Helpers.class).invokeStatic(lift, p.get(0)).returning()))
            .addMethod(MethodDef.builder("described").addModifiers(Modifier.PUBLIC).addParameter("diamond", diamond.asTypeDef()).addParameter("names", names.asTypeDef()).returns(String.class)
                .build((self, p) -> p.get(0).invoke(describe).stringConcat(p.get(0).invokeGetClass().invoke("getSimpleName", TypeDef.STRING))
                    .stringConcat(p.get(1).invoke(size)).returning()))
            .build();
        try (var loader = compile(i0, i1, i2, diamond, names, def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            assertEquals(List.of("a", "b", "c"), cls.getMethod("sorted", List.class).invoke(instance, new ArrayList<>(List.of("c", "a", "b"))));
            assertEquals("t", cls.getMethod("lifted", String.class).invoke(instance, "t"));
            var aDiamond = loader.loadClass(diamond.getName()).getConstructor().newInstance();
            var nameList = loader.loadClass(names.getName()).getConstructor().newInstance();
            assertEquals("i0Diamond0", cls.getMethod("described", loader.loadClass(diamond.getName()), loader.loadClass(names.getName())).invoke(instance, aDiamond, nameList));
        }
    }

    // OverrideResolver 969-974, 997, 1050, 1127: the more specific of two wildcard results is chosen, a parameterized
    // declared result is compared through the compiled hierarchy, an overload of another parameter erasure is no
    // override, and the erased result of a generic inherited method is resolved to the bound it substitutes
    @Test
    void overrideResolutionOfResultsAndOverloads() throws Exception {
        var ints = InterfaceDef.builder("test.Ints").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.parameterized(List.class, TypeDef.wildcardSubtypeOf(TypeDef.of(Integer.class)))).build())
            .build();
        var listOf = List.class.getMethod("of", Object.class);
        var numbers = ClassDef.builder("test.Numbers").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.parameterized(List.class, TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))))
            .addSuperinterface(ints.asTypeDef())
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ClassTypeDef.of(List.class).invokeStatic(listOf, ExpressionDef.constant(1)).returning()))
            .build();
        var collected = ClassDef.builder("test.Collected").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Supplier.class, TypeDef.parameterized(java.util.Collection.class, TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.parameterized(ArrayList.class, TypeDef.of(Integer.class)))
                .build((self, p) -> TypeDef.parameterized(ArrayList.class, TypeDef.of(Integer.class)).instantiate().returning()))
            .build();
        var twice = ClassDef.builder("test.Twice").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(java.util.function.Function.class, TypeDef.STRING, TypeDef.STRING))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.get(0).cast(String.class).stringConcat(p.get(0).cast(String.class)).returning()))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Integer.class).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("int").returning()))
            .build();
        var t = TypeDef.variable("T");
        var src = InterfaceDef.builder("test.Src").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addTypeVariable(TypeDef.variable("V", t)).returns(TypeDef.variable("V")).build())
            .build();
        var made = ClassDef.builder("test.Made").addModifiers(Modifier.PUBLIC).addSuperinterface(TypeDef.parameterized(src.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("make").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant("made").returning()))
            .build();
        try (var loader = compile(ints, numbers, collected, twice, src, made)) {
            var numbersClass = loader.loadClass(numbers.getName());
            assertEquals(List.of(1), ((Supplier<?>) numbersClass.getConstructor().newInstance()).get());
            assertEquals("java.util.List<? extends java.lang.Integer>", numbersClass.getMethod("get").getGenericReturnType().getTypeName());
            var collectedClass = loader.loadClass(collected.getName());
            assertEquals(ArrayList.class, collectedClass.getMethod("get").getReturnType());
            assertEquals(List.of(), ((Supplier<?>) collectedClass.getConstructor().newInstance()).get());
            var twiceClass = loader.loadClass(twice.getName());
            var aTwice = twiceClass.getConstructor().newInstance();
            assertEquals("xx", ((java.util.function.Function<String, String>) aTwice).apply("x"));
            assertEquals("int", twiceClass.getMethod("apply", Integer.class).invoke(aTwice, 1));
            var madeClass = loader.loadClass(made.getName());
            assertEquals(String.class, madeClass.getMethod("make").getReturnType());
            assertEquals("made", madeClass.getMethod("make").invoke(madeClass.getConstructor().newInstance()));
        }
    }

    private static EnumDef enumWithConstantArgument(ExpressionDef argument) {
        return EnumDef.builder("test.BadConstant").addModifiers(Modifier.PUBLIC).addEnumConstant("A", argument)
            .addConstructor(List.of(ParameterDef.of("value", TypeDef.OBJECT)), Modifier.PRIVATE).build();
    }

    private static ClassDef classWithBody(String name, Class<?> returnType, StatementDef body) {
        return ClassDef.builder("test." + name).addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(returnType).addStatement(body).build()).build();
    }

    private static ClassDef switchWithYieldCase(ExpressionDef.SwitchYieldCase yieldCase) {
        return ClassDef.builder("test.BadYield").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", int.class).returns(int.class)
                .build((self, p) -> p.get(0).asExpressionSwitch(INT, Map.of(ExpressionDef.constant(1), yieldCase), ExpressionDef.constant(0)).returning()))
            .build();
    }

    static String render(ObjectDef definition) throws Exception {
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(definition, writer);
        if ("true".equals(System.getenv("COVERAGE_PRINT"))) {
            System.out.println(writer + "=====");
        }
        return writer.toString();
    }

    private static URLClassLoader compile(ObjectDef... definitions) throws Exception {
        var sources = new ArrayList<String>();
        for (var definition : definitions) {
            sources.add(render(definition));
        }
        return JavaCompileAssertions.compileAndLoad(sources.toArray(String[]::new));
    }

    /**
     * A marker annotation for declarations.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
    }

    /**
     * A marker annotation for type uses.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE_USE)
    public @interface Mark {
    }

    /**
     * An annotation with a string member.
     *
     * @since 2.2.2
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Named {
        /**
         * @return The name
         */
        String value();
    }

    /**
     * Constants an annotation can name.
     *
     * @since 2.2.2
     */
    public static final class Names {
        /**
         * The first name.
         */
        public static final String FIRST = "first";

        private Names() {
        }
    }

    /**
     * A compiled bean with a read-only property.
     *
     * @since 2.2.2
     */
    public static final class LabelHolder {
        /**
         * @return The label
         */
        public String getLabel() {
            return "held";
        }
    }

    /**
     * Compiled methods with parameterized and bounded parameters.
     *
     * @since 2.2.2
     */
    @SuppressWarnings("checkstyle:MissingJavadocMethod")
    public static final class Helpers {

        private Helpers() {
        }

        public static <N extends Number & Comparable<N>> N first(N[] values) {
            return values[0];
        }

        public static <S extends Number & java.io.Serializable> S firstSerializable(S[] values) {
            return values[0];
        }

        public static int count(Map<String, List<String>> texts) {
            return texts.size();
        }

        public static <T> int countAny(Map<String, List<T>> values) {
            return values.size();
        }

        public static int countWild(Map<String, List<? extends CharSequence>> values) {
            return values.size();
        }

        public static int sizes(List<? extends List<?>> lists) {
            return lists.size();
        }

        public static <T> String copy(String[] values) {
            return "strings";
        }

        public static <T> String copy(T[] values) {
            return "generic";
        }

        public static <A, B extends A> A lift(B value) {
            return value;
        }

        /**
         * A compiled class whose second variable is bounded by the first.
         *
         * @param <A> The first variable
         * @param <B> The second, bounded by the first
         */
        public static final class Chain<A, B extends A> {
            public B pick(B value) {
                return value;
            }
        }
    }
}
