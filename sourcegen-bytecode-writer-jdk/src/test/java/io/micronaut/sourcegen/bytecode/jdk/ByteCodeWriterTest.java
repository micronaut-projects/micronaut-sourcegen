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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteCodeWriterTest {

    @Test
    void writesVerifiedJava17Class() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkGenerated")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("answer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> TypeDef.Primitive.INT.constant(42).returning()))
            .build();

        byte[] bytes = writeDirect(definition);

        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
        assertTrue(ClassFile.of().verify(bytes).isEmpty());

        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        Method answer = generated.getMethod("answer");
        assertEquals(42, answer.invoke(null));
    }

    @Test
    void verifiesExceptionAndStringBytecode() {
        ClassDef definition = ClassDef.builder("example.JdkVerified")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.STRING)
                .addThrows(TypeDef.of(IOException.class))
                .build((aThis, parameters) -> ExpressionDef.constant("value").returning()))
            .build();

        byte[] bytes = writeDirect(definition);

        assertTrue(ClassFile.of().verify(bytes).isEmpty());
    }

    @Test
    void writesParametersArithmeticAndBranchesWithCodeBuilder() throws Exception {
        ClassDef definition = ClassDef.builder("example.JdkArithmetic")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("sum")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("left", TypeDef.Primitive.INT)
                .addParameter("right", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> parameters.get(0).math(
                    ExpressionDef.MathBinaryOperation.OpType.ADDITION, parameters.get(1)).returning()))
            .addMethod(MethodDef.builder("positiveOrZero")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> parameters.get(0).compare(
                    ExpressionDef.ComparisonOperation.OpType.GREATER_THAN, TypeDef.Primitive.INT.constant(0))
                    .doIfElse(parameters.get(0).returning(), TypeDef.Primitive.INT.constant(0).returning())))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        assertEquals(7, generated.getMethod("sum", int.class, int.class).invoke(null, 3, 4));
        assertEquals(8, generated.getMethod("positiveOrZero", int.class).invoke(null, 8));
        assertEquals(0, generated.getMethod("positiveOrZero", int.class).invoke(null, -1));
    }

    @Test
    void writesFieldsConstructorsAndInstanceMethodsWithCodeBuilder() throws Exception {
        FieldDef value = FieldDef.builder("value", TypeDef.Primitive.INT).build();
        ParameterDef parameter = ParameterDef.of("value", TypeDef.Primitive.INT);
        ClassDef definition = ClassDef.builder("example.JdkState")
            .addModifiers(Modifier.PUBLIC)
            .addField(value)
            .addMethod(MethodDef.constructor(List.of(parameter), Modifier.PUBLIC))
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> aThis.field(value).returning()))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        Object instance = generated.getConstructor(int.class).newInstance(11);
        assertEquals(11, generated.getMethod("value").invoke(instance));
    }

    @Test
    void writesVerifiedRecordWithObjectMethods() throws Exception {
        RecordDef definition = RecordDef.builder("example.JdkRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING).build())
            .addProperty(PropertyDef.builder("age").ofType(TypeDef.Primitive.INT).build())
            .build();

        byte[] bytes = writeDirect(definition);

        assertEquals(ClassFile.JAVA_17_VERSION, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        Object first = generated.getConstructor(String.class, int.class).newInstance("Ada", 37);
        Object second = generated.getConstructor(String.class, int.class).newInstance("Ada", 37);
        assertTrue(generated.isRecord());
        assertEquals("Ada", generated.getMethod("name").invoke(first));
        assertEquals(37, generated.getMethod("age").invoke(first));
        assertEquals("JdkRecord[name=Ada, age=37]", first.toString());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void writesRecordDeclarationAndTypeUseAnnotationsToTheirTargets() throws Exception {
        RecordDef definition = RecordDef.builder("example.JdkAnnotatedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(FieldOnly.class)).build())
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(TypeUseOnly.class)).build())
                .build())
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();

        Field field = generated.getDeclaredField("name");
        assertTrue(field.isAnnotationPresent(FieldOnly.class));
        assertTrue(field.getAnnotatedType().isAnnotationPresent(TypeUseOnly.class));
        assertFalse(generated.getRecordComponents()[0].isAnnotationPresent(FieldOnly.class));
        assertTrue(generated.getRecordComponents()[0].getAnnotatedType().isAnnotationPresent(TypeUseOnly.class));
        assertFalse(generated.getMethod("name").isAnnotationPresent(FieldOnly.class));
        assertTrue(generated.getMethod("name").getAnnotatedReturnType().isAnnotationPresent(TypeUseOnly.class));
        assertTrue(generated.getConstructor(String.class).getAnnotatedParameterTypes()[0]
            .isAnnotationPresent(TypeUseOnly.class));
    }

    @Test
    void writesLambdaWithCapturedLocalAndMethodReference() throws Exception {
        ClassTypeDef function = TypeDef.parameterized(Function.class, String.class, String.class);
        VariableDef.Local prefix = new VariableDef.Local("prefix", TypeDef.STRING);
        VariableDef.Local lambda = new VariableDef.Local("lambda", function);
        VariableDef.Local reference = new VariableDef.Local("reference", function);
        MethodDef shout = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, parameters) -> parameters.get(0)
                .invoke("concat", TypeDef.STRING, ExpressionDef.constant("!"))
                .returning());
        ClassDef definition = ClassDef.builder("example.JdkLambda")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(shout)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) -> StatementDef.multi(
                    prefix.defineAndAssign(ExpressionDef.constant("prefix_")),
                    lambda.defineAndAssign(function.getLambda().implement((ignored, lambdaParameters) ->
                        prefix.invoke("concat", TypeDef.STRING, lambdaParameters.get(0)).returning())),
                    reference.defineAndAssign(function.staticMethodReference(
                        ClassTypeDef.of("example.JdkLambda"), shout)),
                    lambda.invoke("apply", List.of(TypeDef.OBJECT), TypeDef.OBJECT, List.of(parameters.get(0)))
                        .cast(TypeDef.STRING)
                        .invoke("concat", TypeDef.STRING, ExpressionDef.constant("|"))
                        .invoke("concat", TypeDef.STRING, reference.invoke("apply", List.of(TypeDef.OBJECT),
                            TypeDef.OBJECT, List.of(parameters.get(0))).cast(TypeDef.STRING))
                        .returning())))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        assertEquals("prefix_Hello|Hello!", generated.getMethod("apply", String.class).invoke(null, "Hello"));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    private @interface FieldOnly {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    private @interface TypeUseOnly {
    }

    @Test
    void writesClassMembersMetadataInitializersAndConcat() throws Exception {
        FieldDef staticValue = FieldDef.builder("staticValue", TypeDef.Primitive.INT)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .initializer(ExpressionDef.constant(5))
            .build();
        FieldDef instanceValue = FieldDef.builder("instanceValue", TypeDef.Primitive.INT)
            .initializer(ExpressionDef.constant(7))
            .build();
        PropertyDef property = PropertyDef.builder("name")
            .ofType(TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef definition = ClassDef.builder("example.JdkMembers")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Deprecated.class)
            .addField(staticValue)
            .addField(instanceValue)
            .addProperty(property)
            .addMethod(MethodDef.builder("instanceValue")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> aThis.field(instanceValue).returning()))
            .addMethod(MethodDef.builder("concat")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) -> parameters.get(0).stringConcat(ExpressionDef.constant("!")).returning()))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        assertTrue(generated.isAnnotationPresent(Deprecated.class));
        assertEquals(5, generated.getField("staticValue").get(null));
        Object instance = generated.getConstructor().newInstance();
        assertEquals(7, generated.getMethod("instanceValue").invoke(instance));
        assertEquals("hello!", generated.getMethod("concat", String.class).invoke(null, "hello"));
        generated.getMethod("setName", String.class).invoke(instance, "name");
        assertEquals("name", generated.getMethod("getName").invoke(instance));
    }

    @Test
    void writesSwitchTryCatchAndSynchronizedControlFlow() throws Exception {
        Map<ExpressionDef.Constant, StatementDef> intCases = new LinkedHashMap<>();
        intCases.put(ExpressionDef.constant(1), ExpressionDef.constant(10).returning());
        intCases.put(ExpressionDef.constant(2), ExpressionDef.constant(20).returning());
        Map<ExpressionDef.Constant, ExpressionDef> stringCases = new LinkedHashMap<>();
        stringCases.put(ExpressionDef.constant("one"), ExpressionDef.constant(1));
        stringCases.put(ExpressionDef.constant("two"), ExpressionDef.constant(2));
        ClassDef definition = ClassDef.builder("example.JdkControlFlow")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("switchInt")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> parameters.get(0).asStatementSwitch(
                    TypeDef.Primitive.INT, intCases, ExpressionDef.constant(0).returning())))
            .addMethod(MethodDef.builder("switchString")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> parameters.get(0).asExpressionSwitch(
                    TypeDef.Primitive.INT, stringCases, ExpressionDef.constant(0)).returning()))
            .addMethod(MethodDef.builder("caught")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> StatementDef.doTry(
                    ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow()
                ).doCatch(RuntimeException.class, ignored -> ExpressionDef.constant(9).returning())))
            .addMethod(MethodDef.builder("locked")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("monitor", TypeDef.OBJECT)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> new StatementDef.Synchronized(
                    parameters.get(0), ExpressionDef.constant(3).returning())))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        assertEquals(10, generated.getMethod("switchInt", int.class).invoke(null, 1));
        assertEquals(0, generated.getMethod("switchInt", int.class).invoke(null, 9));
        assertEquals(2, generated.getMethod("switchString", String.class).invoke(null, "two"));
        assertEquals(0, generated.getMethod("switchString", String.class).invoke(null, "other"));
        assertEquals(9, generated.getMethod("caught").invoke(null));
        assertEquals(3, generated.getMethod("locked", Object.class).invoke(null, new Object()));
    }
    @Test
    void writesLambdaFieldInitializerWithDefaultConstructor() throws Exception {
        ClassTypeDef function = TypeDef.parameterized(Function.class, String.class, String.class);
        FieldDef mapper = FieldDef.builder("mapper", function)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .initializer(function.getLambda().implement((ignored, lambdaParameters) ->
                lambdaParameters.get(0).invoke("concat", TypeDef.STRING, ExpressionDef.constant("!")).returning()))
            .build();
        ClassDef definition = ClassDef.builder("example.JdkLambdaField")
            .addModifiers(Modifier.PUBLIC)
            .addField(mapper)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) -> aThis.field(mapper)
                    .invoke("apply", List.of(TypeDef.OBJECT), TypeDef.OBJECT, List.of(parameters.get(0)))
                    .cast(TypeDef.STRING)
                    .returning()))
            .build();

        byte[] bytes = writeDirect(definition);
        assertTrue(ClassFile.of().verify(bytes).isEmpty());
        assertTrue(ClassFile.of().parse(bytes).methods().stream()
            .anyMatch(method -> method.methodName().stringValue().startsWith("lambda$")),
            "Expected the field initializer lambda body to be emitted");
        Class<?> generated = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(definition.getName(), bytes, 0, bytes.length);
            }
        }.define();
        Object instance = generated.getConstructor().newInstance();
        assertEquals("Hello!", generated.getMethod("apply", String.class).invoke(instance, "Hello"));
    }

    @Test
    void resolvesGeneratedClassHierarchyByBinaryName() {
        ClassDef parent = ClassDef.builder("example.hierarchy.Parent")
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef child = ClassDef.builder("example.hierarchy.Child")
            .addModifiers(Modifier.PUBLIC)
            .superclass(parent.asTypeDef())
            .build();
        String contract = "example.hierarchy.Contract";
        Map<String, byte[]> generated = new LinkedHashMap<>();
        generated.put(parent.getName(), writeDirect(parent));
        generated.put(child.getName(), writeDirect(child));
        generated.put(contract, ClassFile.of().build(java.lang.constant.ClassDesc.of(contract), builder ->
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)));

        var resolver = new SourcegenClassHierarchyResolver(generated, List.of(), List.of(), getClass().getClassLoader(), null);

        assertEquals(ClassHierarchyInfo.ofClass(java.lang.constant.ClassDesc.of(parent.getName())),
            resolver.getClassInfo(java.lang.constant.ClassDesc.of(child.getName())));
        assertEquals(ClassHierarchyInfo.ofClass(java.lang.constant.ClassDesc.of("java.lang.Object")),
            resolver.getClassInfo(java.lang.constant.ClassDesc.of(parent.getName())));
        assertEquals(ClassHierarchyInfo.ofInterface(),
            resolver.getClassInfo(java.lang.constant.ClassDesc.of(contract)));
    }

    private static byte[] writeDirect(io.micronaut.sourcegen.model.ObjectDef definition) {
        var bytes = new JdkClassFileWriter(true).write(definition, null);
        assertTrue(bytes.isPresent(), () -> "Expected direct ClassFile lowering for " + definition.getName());
        return bytes.orElseThrow();
    }
}
