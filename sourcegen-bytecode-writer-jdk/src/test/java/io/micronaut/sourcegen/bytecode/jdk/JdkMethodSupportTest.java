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

import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check that decides whether a definition is lowered directly or handed to the source
 * fallback. A construct wrongly reported as supported fails later inside the writer, and one
 * wrongly reported as unsupported costs a javac invocation.
 */
class JdkMethodSupportTest {

    private static final VariableDef.MethodParameter TEXT =
        new VariableDef.MethodParameter("text", TypeDef.STRING);
    private static final VariableDef.MethodParameter NUMBER =
        new VariableDef.MethodParameter("number", TypeDef.Primitive.INT);
    private static final MethodDef TO_STRING = MethodDef.builder("toString")
        .addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING).build();

    @Test
    void supportsEveryVariableAndConstantForm() {
        assertTrue(JdkMethodSupport.supported(ExpressionDef.constant("value")));
        assertTrue(JdkMethodSupport.supported(new VariableDef.This()));
        assertTrue(JdkMethodSupport.supported(TEXT));
        assertTrue(JdkMethodSupport.supported(new VariableDef.Local("local", TypeDef.STRING)));
        assertTrue(JdkMethodSupport.supported(
            new VariableDef.StaticField(TypeDef.STRING, "CASE_INSENSITIVE_ORDER", TypeDef.OBJECT)));
        assertTrue(JdkMethodSupport.supported(new VariableDef.ExceptionVar(ClassTypeDef.of(RuntimeException.class))));
        assertTrue(JdkMethodSupport.supported(new VariableDef.Super(ClassTypeDef.of(Object.class))));
        assertTrue(JdkMethodSupport.supported(new VariableDef.This()
            .field(FieldDef.builder("field", TypeDef.STRING).build())));
    }

    @Test
    void supportsEveryOperatorAndControlFlowExpression() {
        assertTrue(JdkMethodSupport.supported(TEXT.cast(TypeDef.OBJECT)));
        assertTrue(JdkMethodSupport.supported(TypeDef.STRING.instantiate()));
        assertTrue(JdkMethodSupport.supported((ExpressionDef) TEXT.invoke(TO_STRING)));
        assertTrue(JdkMethodSupport.supported((ExpressionDef) TypeDef.STRING.invokeStatic(
            MethodDef.builder("valueOf").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build(), TEXT)));
        assertTrue(JdkMethodSupport.supported(NUMBER.compare(
            ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, NUMBER)));
        assertTrue(JdkMethodSupport.supported(NUMBER.math(
            ExpressionDef.MathBinaryOperation.OpType.ADDITION, NUMBER)));
        assertTrue(JdkMethodSupport.supported(NUMBER.math(
            ExpressionDef.MathUnaryOperation.OpType.NEGATE)));
        assertTrue(JdkMethodSupport.supported(new ExpressionDef.StringConcatenation(TEXT, TEXT)));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull()));
        assertTrue(JdkMethodSupport.supported(TEXT.isNonNull()));
        assertTrue(JdkMethodSupport.supported(NUMBER.cast(TypeDef.Primitive.BOOLEAN).isTrue()));
        assertTrue(JdkMethodSupport.supported(NUMBER.cast(TypeDef.Primitive.BOOLEAN).isFalse()));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull().and(TEXT.isNonNull())));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull().or(TEXT.isNonNull())));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull().doIfElse(TEXT, TEXT)));
        assertTrue(JdkMethodSupport.supported(TypeDef.STRING.array().instantiate(TEXT)));
        assertTrue(JdkMethodSupport.supported(new ExpressionDef.NewArrayOfSize(TypeDef.STRING.array(), 2)));
        assertTrue(JdkMethodSupport.supported(TypeDef.STRING.array().instantiate(TEXT).arrayElement(0)));
        assertTrue(JdkMethodSupport.supported(TEXT.invokeGetClass()));
        assertTrue(JdkMethodSupport.supported(TEXT.invokeHashCode()));
        assertTrue(JdkMethodSupport.supported(TEXT.equalsStructurally(TEXT)));
        assertTrue(JdkMethodSupport.supported(TEXT.notEqualsStructurally(TEXT)));
        assertTrue(JdkMethodSupport.supported(TEXT.instanceOf(ClassTypeDef.of(String.class))));
    }

    @Test
    void supportsEveryStatementItCanLower() {
        assertTrue(JdkMethodSupport.supported(StatementDef.multi(TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(new StatementDef.Return(null)));
        assertTrue(JdkMethodSupport.supported(TypeDef.STRING.instantiate().doThrow()));
        assertTrue(JdkMethodSupport.supported(TEXT.newLocal("copy")));
        assertTrue(JdkMethodSupport.supported(
            new VariableDef.Local("copy", TypeDef.STRING).assign(TEXT)));
        assertTrue(JdkMethodSupport.supported(new VariableDef.This()
            .field(FieldDef.builder("field", TypeDef.STRING).build()).put(TEXT)));
        assertTrue(JdkMethodSupport.supported(
            TypeDef.STRING.getStaticField("FIELD", TypeDef.STRING).put(TEXT)));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull().doIf(TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(
            TEXT.isNull().doIfElse(TEXT.returning(), TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(TEXT.isNull().whileLoop(TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(StatementDef.doTry(TEXT.returning())
            .doCatch(RuntimeException.class, exception -> TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(StatementDef.doTry(TEXT.returning())
            .doFinally(TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(new StatementDef.Synchronized(TEXT, TEXT.returning())));
    }

    @Test
    void declinesConstructsTheWriterCannotLowerYet() {
        // A switch yield case has no direct lowering
        assertFalse(JdkMethodSupport.supported(NUMBER.asExpressionSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(
                TypeDef.STRING, TEXT.returning())),
            TEXT)));
        // A switch is only lowered over int and String, with distinct keys
        assertFalse(JdkMethodSupport.supported(TEXT.cast(TypeDef.Primitive.LONG)
            .asStatementSwitch(TypeDef.STRING,
                Map.of(ExpressionDef.constant(1), TEXT.returning()), TEXT.returning())));
        // A case body that cannot be lowered makes the whole switch unsupported
        assertFalse(JdkMethodSupport.supported(NUMBER.asStatementSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(
                TypeDef.STRING, TEXT.returning()).returning()),
            TEXT.returning())));
    }

    @Test
    void supportsAStringSwitchWhoseValuesShareAHashCode() {
        // Distinctness is of the values, not their hash codes: "Aa" and "BB" collide
        assertTrue(JdkMethodSupport.supported(TEXT.asStatementSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant("Aa"), TEXT.returning(),
                ExpressionDef.constant("BB"), TEXT.returning()),
            TEXT.returning())));
    }

    @Test
    void supportsSwitchesOverIntAndString() {
        assertTrue(JdkMethodSupport.supported(NUMBER.asStatementSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant(1), TEXT.returning()), TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(TEXT.asStatementSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant("a"), TEXT.returning()), TEXT.returning())));
        assertTrue(JdkMethodSupport.supported(NUMBER.asExpressionSwitch(TypeDef.STRING,
            Map.of(ExpressionDef.constant(1), TEXT), TEXT)));
    }

    @Test
    void requiresABodyForAMethodThatReturnsAValue() {
        assertTrue(JdkMethodSupport.supported(MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID).build()));
        assertFalse(JdkMethodSupport.supported(MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING).build()));
        // A generic constructor has no direct lowering
        assertFalse(JdkMethodSupport.supported(MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addStatement(new StatementDef.Return(null))
            .build()));
        assertTrue(JdkMethodSupport.supported(MethodDef.builder("first")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addStatements(List.of(TEXT.returning()))
            .build()));
    }
}
