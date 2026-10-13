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

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Statement, scope and naming shapes that are valid for the bytecode writer and have to compile as Java source.
 * The programs are compiled and run; the source they are written as is not prescribed.
 *
 * @since 2.3
 */
public class StatementRenderingRegressionTest {

    /**
     * A method body drops the fallback the model appends after an exhaustive statement; the block body of a lambda
     * is written by a loop of its own that does not, and javac reports the fallback as unreachable.
     */
    @Test
    void lambdaBlockBodyDropsUnreachableFallback() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var lambda = supplier.getLambda().implement((aThis, params) -> StatementDef.multi(
            ExpressionDef.trueValue().isTrue().doIfElse(
                ExpressionDef.constant("then").returning(), ExpressionDef.constant("else").returning()),
            ExpressionDef.constant("unreachable").returning()));
        var def = ClassDef.builder("test.LambdaFallback").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((aThis, p) -> lambda.newLocal("supplier", local -> local.invoke("get", TypeDef.OBJECT).returning())))
            .build();
        assertEquals("then", run(def));
    }

    /**
     * The same fallback after an exhaustive statement in the block of a switch expression case.
     */
    @Test
    void switchYieldBlockDropsUnreachableFallback() throws Exception {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING, StatementDef.multi(
            ExpressionDef.trueValue().isTrue().doIfElse(
                ExpressionDef.constant("then").returning(), ExpressionDef.constant("else").returning()),
            ExpressionDef.constant("unreachable").returning())));
        var def = ClassDef.builder("test.YieldFallback").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((aThis, p) -> ExpressionDef.constant(1)
                    .asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("default")).returning()))
            .build();
        assertEquals("then", run(def));
    }

    /**
     * A local is declared without the method it belongs to, so a variable of that method is written as its bound:
     * {@code Object local = v; return local;} in {@code <U> U id(U v)}.
     */
    @Test
    void localOfMethodTypeVariableKeepsItsType() throws Exception {
        var u = TypeDef.variable("U");
        var id = MethodDef.builder("id").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("v", u).returns(u)
            .build((aThis, p) -> p.get(0).newLocal("local", local -> local.returning()));
        var def = ClassDef.builder("test.VariableLocal").addModifiers(Modifier.PUBLIC).addMethod(id)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((aThis, p) -> aThis.invoke(id, ExpressionDef.constant("text")).returning()))
            .build();
        assertEquals("text", run(def));
    }

    /**
     * The same local of a type parameterized by the method's variable: {@code List<CharSequence> local = v} does
     * not take a {@code List<U>}.
     */
    @Test
    void localParameterizedByMethodTypeVariableKeepsItsType() throws Exception {
        var u = TypeDef.variable("U", TypeDef.of(CharSequence.class));
        var list = TypeDef.parameterized(List.class, u);
        var id = MethodDef.builder("id").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("v", list).returns(list)
            .build((aThis, p) -> p.get(0).newLocal("local", local -> local.returning()));
        var def = ClassDef.builder("test.ParameterizedVariableLocal").addModifiers(Modifier.PUBLIC).addMethod(id).build();
        JavaCompileAssertions.assertCompiles(render(def));
    }

    /**
     * A static method writes a variable of its class as the bound, which is not in scope there. The body of a lambda
     * is rendered as a method that is not static, and names the variable.
     */
    @Test
    void lambdaInStaticMethodDoesNotNameClassVariable() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        var function = TypeDef.parameterized(Function.class, Object.class, Object.class);
        var lambda = function.getLambda().implement((aThis, params) -> params.get(0).cast(t).returning());
        var def = ClassDef.builder("test.StaticLambda").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("direct").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("v", Object.class).returns(Object.class)
                .build((aThis, p) -> p.get(0).cast(t).returning()))
            .addMethod(MethodDef.builder("viaLambda").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(function)
                .build((aThis, p) -> lambda.returning()))
            .build();
        JavaCompileAssertions.assertCompiles(render(def));
    }

    /**
     * The name of a catch parameter avoids the fields, parameters and locals declared before it, but not a local
     * the catch block declares itself.
     */
    @Test
    void catchParameterAvoidsLocalOfItsBlock() throws Exception {
        var def = ClassDef.builder("test.CatchLocal").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((aThis, p) -> StatementDef.doTry(new StatementDef.Throw(ClassTypeDef.of(IllegalStateException.class).instantiate()))
                    .doCatch(IllegalStateException.class, e -> ExpressionDef.constant("caught").newLocal("e0", local -> local.returning()))))
            .build();
        assertEquals("caught", run(def));
    }

    /**
     * An array of a parameterized type cannot be created in Java: {@code new List<String>[2]} is a generic array
     * creation, where {@code new List[2]} is what the bytecode allocates.
     */
    @Test
    void arrayOfParameterizedTypeIsCreated() throws Exception {
        var array = TypeDef.parameterized(List.class, String.class).array();
        var def = ClassDef.builder("test.ParameterizedArray").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((aThis, p) -> array.instantiate(2).returning()))
            .build();
        assertEquals(2, ((Object[]) run(def)).length);
    }

    /**
     * A {@code long} annotation member is written as an {@code int} literal, which one out of its range is not.
     */
    @Test
    void longAnnotationMemberOutOfIntRange() throws Exception {
        var def = ClassDef.builder("test.LongMember").addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(LongMember.class)).addMember("value", Long.MAX_VALUE).build())
            .build();
        JavaCompileAssertions.assertCompiles(render(def));
    }

    /**
     * A structural inequality of primitives is written as {@code ==}, the equality it negates.
     */
    @Test
    void structuralInequalityOfPrimitivesIsNotAnEquality() throws Exception {
        var def = ClassDef.builder("test.PrimitiveInequality").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .build((aThis, p) -> ExpressionDef.constant(1).notEqualsStructurally(ExpressionDef.constant(2)).returning()))
            .build();
        assertEquals(true, run(def));
    }

    /**
     * An annotation with a {@code long} member.
     *
     * @since 2.3
     */
    public @interface LongMember {
        /**
         * @return The value
         */
        long value();
    }
}
