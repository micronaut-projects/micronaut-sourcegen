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
package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Rewriting the types a body of statements names.
 */
class TypeTransformerTest {

    private static final TypeDef.TypeVariable T = TypeDef.variable("T");
    private static final TypeDef.TypeVariable U = TypeDef.variable("U");
    private static final ClassTypeDef LIST_OF_T = TypeDef.parameterized(ClassTypeDef.of(List.class), T);
    private static final ClassTypeDef LIST_OF_U = TypeDef.parameterized(ClassTypeDef.of(List.class), U);

    /**
     * Renames T to U, and returns any other type as the same instance.
     */
    private static final TypeTransformer RENAMING = TypeTransformer.of(type -> TypeOperations.mentionsVariable(type, "T"::equals)
        ? TypeOperations.substitute(type, Map.of("T", U)) : type);

    @Test
    void rewritesTheTypesOfVariablesAndCasts() {
        VariableDef.Local values = new VariableDef.Local("values", LIST_OF_T);
        StatementDef statement = values.defineAndAssign(new VariableDef.MethodParameter("source", TypeDef.OBJECT).cast(LIST_OF_T));

        StatementDef.DefineAndAssign rewritten = assertInstanceOf(StatementDef.DefineAndAssign.class, RENAMING.statement(statement));

        assertEquals(new VariableDef.Local("values", LIST_OF_U), rewritten.variable());
        ExpressionDef.Cast cast = assertInstanceOf(ExpressionDef.Cast.class, rewritten.expression());
        assertEquals(LIST_OF_U, cast.type());
        assertEquals(new VariableDef.MethodParameter("source", TypeDef.OBJECT), cast.expressionDef());
    }

    @Test
    void keepsWhatNamesNoMappedType() {
        StatementDef statement = new StatementDef.Multi(List.of(
            new VariableDef.Local("text", TypeDef.STRING).defineAndAssign(ExpressionDef.constant("value")),
            new StatementDef.Return(new VariableDef.Local("text", TypeDef.STRING))));
        List<StatementDef> statements = List.of(statement);

        assertSame(statement, RENAMING.statement(statement));
        assertSame(statements, RENAMING.statements(statements));
    }

    @Test
    void rewritesNestedStatements() {
        VariableDef.Local local = new VariableDef.Local("value", T);
        StatementDef statement = new StatementDef.Try(
            new StatementDef.If(local.isNull(), new StatementDef.Throw(ExpressionDef.nullValue().cast(ClassTypeDef.of(RuntimeException.class)))),
            List.of(new StatementDef.Try.Catch(ClassTypeDef.of(RuntimeException.class), local.assign(ExpressionDef.nullValue().cast(T)))),
            new StatementDef.While(ExpressionDef.trueValue(), new StatementDef.Return(local)));

        StatementDef.Try rewritten = assertInstanceOf(StatementDef.Try.class, RENAMING.statement(statement));

        StatementDef.If condition = assertInstanceOf(StatementDef.If.class, rewritten.statement());
        ExpressionDef.IsNull isNull = assertInstanceOf(ExpressionDef.IsNull.class, condition.condition());
        assertEquals(new VariableDef.Local("value", U), assertInstanceOf(ExpressionDef.Cast.class, isNull.expression()).expressionDef());
        // A statement naming no mapped type is kept
        assertSame(((StatementDef.If) ((StatementDef.Try) statement).statement()).statement(), condition.statement());
        StatementDef.Assign assign = assertInstanceOf(StatementDef.Assign.class, rewritten.catches().get(0).statement());
        assertEquals(new VariableDef.Local("value", U), assign.variable());
        assertEquals(U, assign.expression().type());
        StatementDef.While loop = assertInstanceOf(StatementDef.While.class, rewritten.finallyStatement());
        assertEquals(new StatementDef.Return(new VariableDef.Local("value", U)), loop.statement());
    }

    @Test
    void rewritesTheCasesOfASwitch() {
        VariableDef.Local local = new VariableDef.Local("value", T);
        StatementDef statement = new StatementDef.Switch(ExpressionDef.constant(1), TypeDef.Primitive.INT,
            Map.of(ExpressionDef.constant(1), new StatementDef.Return(local)), new StatementDef.Return(ExpressionDef.nullValue().cast(T)));

        StatementDef.Switch rewritten = assertInstanceOf(StatementDef.Switch.class, RENAMING.statement(statement));

        assertEquals(new StatementDef.Return(new VariableDef.Local("value", U)), rewritten.cases().get(ExpressionDef.constant(1)));
        assertEquals(U, assertInstanceOf(StatementDef.Return.class, rewritten.defaultCase()).expression().type());
    }

    @Test
    void rewritesTheImplementationOfALambda() {
        MethodDef target = MethodDef.builder("apply").addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.OBJECT).build();
        MethodDef implementation = MethodDef.builder("lambda$0")
            .addParameter("value", T)
            .addThrows(ClassTypeDef.of(IllegalStateException.class))
            .addJavadoc("The body")
            .returns(LIST_OF_T)
            .addStatement(new StatementDef.Return(ExpressionDef.nullValue().cast(LIST_OF_T)))
            .build();
        ExpressionDef.Lambda lambda = new ExpressionDef.Lambda(TypeDef.parameterized(ClassTypeDef.of(java.util.function.Function.class), T, LIST_OF_T),
            target, implementation);

        ExpressionDef.Lambda rewritten = assertInstanceOf(ExpressionDef.Lambda.class, RENAMING.expression(lambda));

        assertEquals(TypeDef.parameterized(ClassTypeDef.of(java.util.function.Function.class), U, LIST_OF_U), rewritten.type());
        // The method the lambda implements is declared in its own scope
        assertSame(target, rewritten.target());
        MethodDef rewrittenImplementation = rewritten.implementation();
        assertEquals(LIST_OF_U, rewrittenImplementation.getReturnType());
        assertEquals(U, rewrittenImplementation.getParameters().get(0).getType());
        assertEquals("value", rewrittenImplementation.getParameters().get(0).getName());
        assertEquals(new StatementDef.Return(ExpressionDef.nullValue().cast(LIST_OF_U)), rewrittenImplementation.getStatements().get(0));
        // What it declares besides is kept
        assertEquals(implementation.getName(), rewrittenImplementation.getName());
        assertEquals(implementation.getJavadoc(), rewrittenImplementation.getJavadoc());
        assertEquals(implementation.getThrowTypes(), rewrittenImplementation.getThrowTypes());
    }

    @Test
    void keepsTheMethodAnInvocationNames() {
        MethodDef add = MethodDef.builder("add").addParameter("value", T).returns(TypeDef.Primitive.BOOLEAN).build();
        VariableDef.Local list = new VariableDef.Local("list", LIST_OF_T);
        ExpressionDef.InvokeInstanceMethod invoke = list.invoke(add, ExpressionDef.nullValue().cast(T));

        ExpressionDef.InvokeInstanceMethod rewritten = assertInstanceOf(ExpressionDef.InvokeInstanceMethod.class, RENAMING.expression(invoke));

        assertSame(add, rewritten.method());
        assertEquals(new VariableDef.Local("list", LIST_OF_U), rewritten.instance());
        assertEquals(U, rewritten.values().get(0).type());
        // As a statement too
        assertEquals(rewritten, RENAMING.statement(invoke));
    }

    @Test
    void rewritesTheReceiverOfAMethodReference() {
        VariableDef.Local list = new VariableDef.Local("list", TypeDef.parameterized(ArrayList.class, TypeDef.STRING));
        ExpressionDef reference = ClassTypeDef.of(IntSupplier.class).methodReference(new ExpressionDef.Cast(LIST_OF_T, list), "size");

        ExpressionDef rewritten = RENAMING.expression(reference);

        InstanceMethodReferenceExpression bound = assertInstanceOf(InstanceMethodReferenceExpression.class, rewritten);
        assertEquals(LIST_OF_U, bound.instance().type());
        assertEquals(((InstanceMethodReferenceExpression) reference).type(), bound.type());
        assertSame(((InstanceMethodReferenceExpression) reference).method(), bound.method());
    }

    @Test
    void rewritesConditionsAndArrays() {
        VariableDef.Local local = new VariableDef.Local("value", T);
        ExpressionDef expression = new ExpressionDef.IfElse(local.isNonNull().and(local.instanceOf(LIST_OF_T)),
            new ExpressionDef.NewArrayInitialized(TypeDef.array(T), List.of(local)),
            new ExpressionDef.NewArrayOfSize(TypeDef.array(T), 0), TypeDef.array(T));

        ExpressionDef.IfElse rewritten = assertInstanceOf(ExpressionDef.IfElse.class, RENAMING.expression(expression));

        assertEquals(TypeDef.array(U), rewritten.type());
        ExpressionDef.And and = assertInstanceOf(ExpressionDef.And.class, rewritten.condition());
        assertEquals(LIST_OF_U, assertInstanceOf(ExpressionDef.InstanceOf.class, and.right()).instanceType());
        assertEquals(new ExpressionDef.NewArrayInitialized(TypeDef.array(U), List.of(new VariableDef.Local("value", U))), rewritten.ifExpression());
        assertEquals(new ExpressionDef.NewArrayOfSize(TypeDef.array(U), 0), rewritten.elseExpression());
    }

    @Test
    void anOperandTheOperationCastsIsCastOnce() {
        VariableDef.Local local = new VariableDef.Local("value", T);
        ExpressionDef.EqualsReferentially equals = new ExpressionDef.EqualsReferentially(local, ExpressionDef.nullValue());

        ExpressionDef.EqualsReferentially rewritten = assertInstanceOf(ExpressionDef.EqualsReferentially.class, RENAMING.expression(equals));

        assertEquals(new ExpressionDef.Cast(TypeDef.OBJECT, new VariableDef.Local("value", U)), rewritten.instance());
        assertEquals(equals.other(), rewritten.other());
    }

    @Test
    void aClassTypeMappedToAnotherKindOfTypeIsRejected() {
        UnaryOperator<TypeDef> toVariable = type -> type.equals(ClassTypeDef.of(RuntimeException.class)) ? T : type;
        StatementDef statement = new StatementDef.Throw(new ExpressionDef.NewInstance(ClassTypeDef.of(RuntimeException.class), List.of(), List.of()));

        assertThrows(IllegalStateException.class, () -> TypeTransformer.of(toVariable).statement(statement));
    }
}
