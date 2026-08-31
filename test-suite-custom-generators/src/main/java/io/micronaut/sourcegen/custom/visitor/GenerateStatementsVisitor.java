/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.custom.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateStatements;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.sourcegen.model.VariableDef.Local;
import org.jspecify.annotations.NonNull;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates a class exercising the statements and expressions that have no dedicated syntax of their
 * own in every language - a try/catch/finally, a synchronized block, a static field assignment, an
 * array element, an instanceof and the operations whose rendering depends on operator precedence.
 */
@Internal
public final class GenerateStatementsVisitor implements TypeElementVisitor<GenerateStatements, Object> {

    private static final String CLASS_NAME = "MyStatements";

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        sourceGenerator.write(createClass(element.getPackageName()), context, element);
    }

    public static ClassDef createClass(String packageName) {
        String className = packageName + "." + CLASS_NAME;
        ClassTypeDef selfType = ClassTypeDef.of(className);
        FieldDef lastValue = FieldDef.builder("lastValue")
            .ofType(TypeDef.STRING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .initializer(ExpressionDef.constant("none"))
            .build();

        return ClassDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addField(lastValue)
            .addMethod(divideOrFallback())
            .addMethod(catchAndDescribe())
            .addMethod(tryFinally())
            .addMethod(synchronizedAssign())
            .addMethod(rememberValue(selfType, lastValue))
            .addMethod(recallValue(selfType, lastValue))
            .addMethod(elementOfObjectArray())
            .addMethod(elementOfPrimitiveArray())
            .addMethod(sizedPrimitiveArray())
            .addMethod(isString())
            .addMethod(multiplyBySum())
            .addMethod(shiftOfSum())
            .addMethod(orThenAnd())
            .addMethod(negatedSumAsLong())
            .addMethod(sumAsString())
            .build();
    }

    // divideOrFallback(int divisor) { try { return 100 / divisor; } catch (ArithmeticException e) { return -1; } }
    private static MethodDef divideOrFallback() {
        return MethodDef.builder("divideOrFallback")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("divisor", TypeDef.Primitive.INT)
            .returns(TypeDef.Primitive.INT)
            .buildStatic(params -> ExpressionDef.constant(100)
                .math(OpType.DIVISION, params.get(0))
                .returning()
                .doTry()
                .doCatch(ArithmeticException.class, e -> ExpressionDef.constant(-1).returning()));
    }

    // catchAndDescribe(int divisor) { try { ... } catch (ArithmeticException e) { return e.getMessage(); } }
    private static MethodDef catchAndDescribe() {
        return MethodDef.builder("catchAndDescribe")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("divisor", TypeDef.Primitive.INT)
            .returns(TypeDef.STRING)
            .buildStatic(params -> ClassTypeDef.of(String.class)
                .invokeStatic("valueOf", List.of(TypeDef.Primitive.INT), TypeDef.STRING,
                    ExpressionDef.constant(100).math(OpType.DIVISION, params.get(0)))
                .returning()
                .doTry()
                .doCatch(ArithmeticException.class,
                    e -> e.invoke("toString", TypeDef.STRING).returning()));
    }

    // tryFinally(StringBuilder builder) { try { builder.append("t"); } finally { builder.append("f"); } return builder.toString(); }
    private static MethodDef tryFinally() {
        return MethodDef.builder("tryFinally")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("builder", StringBuilder.class)
            .returns(TypeDef.STRING)
            .buildStatic(params -> StatementDef.multi(
                StatementDef.doTry(
                        params.get(0).invoke("append", TypeDef.of(StringBuilder.class), ExpressionDef.constant("t")))
                    .doFinally(
                        params.get(0).invoke("append", TypeDef.of(StringBuilder.class), ExpressionDef.constant("f"))),
                params.get(0).invoke("toString", TypeDef.STRING).returning()
            ));
    }

    // synchronizedAssign(Object monitor) { String result = "before"; synchronized (monitor) { result = "locked"; } return result; }
    private static MethodDef synchronizedAssign() {
        Local result = new Local("result", TypeDef.STRING);
        return MethodDef.builder("synchronizedAssign")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("monitor", TypeDef.OBJECT)
            .returns(TypeDef.STRING)
            .buildStatic(params -> StatementDef.multi(
                result.defineAndAssign(ExpressionDef.constant("before")),
                new StatementDef.Synchronized(params.get(0), result.assign(ExpressionDef.constant("locked"))),
                result.returning()
            ));
    }

    // rememberValue(String value) { lastValue = value; }
    private static MethodDef rememberValue(ClassTypeDef selfType, FieldDef field) {
        return MethodDef.builder("rememberValue")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.VOID)
            .buildStatic(params -> selfType.getStaticField(field).put(params.get(0)));
    }

    // recallValue() { return lastValue; }
    private static MethodDef recallValue(ClassTypeDef selfType, FieldDef field) {
        return MethodDef.builder("recallValue")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.STRING)
            .buildStatic(params -> selfType.getStaticField(field).returning());
    }

    // elementOfObjectArray(int index) { return new String[]{"a", "b", "c"}[index]; }
    private static MethodDef elementOfObjectArray() {
        return MethodDef.builder("elementOfObjectArray")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("index", TypeDef.Primitive.INT)
            .returns(TypeDef.STRING)
            .buildStatic(params -> TypeDef.STRING.array()
                .instantiate(ExpressionDef.constant("a"), ExpressionDef.constant("b"), ExpressionDef.constant("c"))
                .arrayElement(params.get(0))
                .returning());
    }

    // elementOfPrimitiveArray(int index) { return new int[]{10, 20, 30}[index]; }
    private static MethodDef elementOfPrimitiveArray() {
        return MethodDef.builder("elementOfPrimitiveArray")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("index", TypeDef.Primitive.INT)
            .returns(TypeDef.Primitive.INT)
            .buildStatic(params -> TypeDef.Primitive.INT.array()
                .instantiate(ExpressionDef.constant(10), ExpressionDef.constant(20), ExpressionDef.constant(30))
                .arrayElement(params.get(0))
                .returning());
    }

    // sizedPrimitiveArray() { return new long[3].length; }
    private static MethodDef sizedPrimitiveArray() {
        Local values = new Local("values", TypeDef.Primitive.LONG.array());
        return MethodDef.builder("sizedPrimitiveArray")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.Primitive.LONG)
            .buildStatic(params -> StatementDef.multi(
                values.defineAndAssign(TypeDef.Primitive.LONG.array().instantiate(3)),
                values.arrayElement(2).returning()
            ));
    }

    // isString(Object value) { return value instanceof String; }
    private static MethodDef isString() {
        return MethodDef.builder("isString")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.Primitive.BOOLEAN)
            .buildStatic(params -> params.get(0).instanceOf(ClassTypeDef.of(String.class)).returning());
    }

    // multiplyBySum(int a, int b, int c) { return a * (b + c); }
    private static MethodDef multiplyBySum() {
        return MethodDef.builder("multiplyBySum")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("a", TypeDef.Primitive.INT)
            .addParameter("b", TypeDef.Primitive.INT)
            .addParameter("c", TypeDef.Primitive.INT)
            .returns(TypeDef.Primitive.INT)
            .buildStatic(params -> params.get(0)
                .math(OpType.MULTIPLICATION, params.get(1).math(OpType.ADDITION, params.get(2)))
                .returning());
    }

    // shiftOfSum(int a, int b, int c) { return (a + b) << c; }
    private static MethodDef shiftOfSum() {
        return MethodDef.builder("shiftOfSum")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("a", TypeDef.Primitive.INT)
            .addParameter("b", TypeDef.Primitive.INT)
            .addParameter("c", TypeDef.Primitive.INT)
            .returns(TypeDef.Primitive.INT)
            .buildStatic(params -> params.get(0)
                .math(OpType.ADDITION, params.get(1))
                .math(OpType.BITWISE_LEFT_SHIFT, params.get(2))
                .returning());
    }

    // orThenAnd(boolean a, boolean b, boolean c) { return (a || b) && c; }
    private static MethodDef orThenAnd() {
        return MethodDef.builder("orThenAnd")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("a", TypeDef.Primitive.BOOLEAN)
            .addParameter("b", TypeDef.Primitive.BOOLEAN)
            .addParameter("c", TypeDef.Primitive.BOOLEAN)
            .returns(TypeDef.Primitive.BOOLEAN)
            .buildStatic(params -> params.get(0).isTrue()
                .or(params.get(1).isTrue())
                .and(params.get(2).isTrue())
                .returning());
    }

    // negatedSumAsLong(int a, int b) { return (long) -(a + b); }
    private static MethodDef negatedSumAsLong() {
        return MethodDef.builder("negatedSumAsLong")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("a", TypeDef.Primitive.INT)
            .addParameter("b", TypeDef.Primitive.INT)
            .returns(TypeDef.Primitive.LONG)
            .buildStatic(params -> params.get(0)
                .math(OpType.ADDITION, params.get(1))
                .math(ExpressionDef.MathUnaryOperation.OpType.NEGATE)
                .cast(TypeDef.Primitive.LONG)
                .returning());
    }

    // sumAsString(int a, int b) { return String.valueOf(a + b); }
    private static MethodDef sumAsString() {
        return MethodDef.builder("sumAsString")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("a", TypeDef.Primitive.INT)
            .addParameter("b", TypeDef.Primitive.INT)
            .returns(TypeDef.STRING)
            .buildStatic(params -> ClassTypeDef.of(String.class)
                .invokeStatic("valueOf", List.of(TypeDef.Primitive.INT), TypeDef.STRING,
                    params.get(0).math(OpType.ADDITION, params.get(1)))
                .returning());
    }

}
