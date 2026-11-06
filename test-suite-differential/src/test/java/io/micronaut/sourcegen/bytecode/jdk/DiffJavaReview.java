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
import io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;

import static io.micronaut.sourcegen.bytecode.jdk.DiffModels.*;
import static io.micronaut.sourcegen.model.ExpressionDef.constant;

/**
 * Models of the shapes the Java source generator wrote differently from the other backends: a conditional folded
 * before its conversion, a lambda of a loop condition capturing a local the loop assigns, a reference to a method
 * throwing a checked exception, and a float constant beyond the float range.
 */
final class DiffJavaReview {

    private DiffJavaReview() {
    }

    static void generate(DiffModels m) {
        conditionals(m);
        loopConditionCapture(m);
        throwingReference(m);
        floatOverflow(m);
    }

    private static void conditionals(DiffModels m) {
        ExpressionDef conditional = new ExpressionDef.IfElse(constant(true), constant(0.5), constant(0.7), TypeDef.Primitive.INT);
        m.add("java review loop over a converted constant conditional", INT, List.of(), (self, p) -> StatementDef.multi(
            new StatementDef.While(conditional.compare(OpType.EQUAL_TO, constant(0)), constant(1).returning()),
            constant(2).returning()));
    }

    private static void loopConditionCapture(DiffModels m) {
        ClassTypeDef supplier = ClassTypeDef.of(BooleanSupplier.class);
        m.add("java review lambda of a loop condition reads a local the loop assigns", INT, List.of(INT), (self, p) ->
            constant(0).newLocal("counter", counter -> StatementDef.multi(
                new StatementDef.While(
                    supplier.getLambda().implement((ls, lp) -> counter.compare(OpType.LESS_THAN, p.get(0)).returning())
                        .invoke("getAsBoolean", TypeDef.Primitive.BOOLEAN),
                    counter.assign(counter.math(ExpressionDef.MathBinaryOperation.OpType.ADDITION, constant(1)))),
                counter.returning())), List.of(new Object[] {2}, new Object[] {0}, new Object[] {-1}));
    }

    private static void throwingReference(DiffModels m) {
        Method decode;
        try {
            decode = URLDecoder.class.getMethod("decode", String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        ClassTypeDef function = TypeDef.parameterized(BiFunction.class, String.class, String.class, String.class);
        m.add("java review reference to a method throwing a checked exception", STRING, List.of(STRING), (self, p) ->
            function.staticMethodReference(ClassTypeDef.of(URLDecoder.class), MethodDef.of(decode))
                .invoke("apply", TypeDef.OBJECT, p.get(0), constant("UTF-8")).cast(TypeDef.STRING).returning());
    }

    private static void floatOverflow(DiffModels m) {
        m.add("java review float constant beyond the float range", FLOAT, List.of(), (self, p) ->
            new ExpressionDef.Constant(TypeDef.Primitive.FLOAT, -1e300).returning());
    }
}
