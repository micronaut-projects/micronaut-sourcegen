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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.EQUAL_TO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A switch yield case yields the value of its block to the switch. The case is written as a
 * statement block whose returns are its yields, so a switch that is not itself what the method
 * returns - here it is an argument - must not return from the method.
 */
class SwitchYieldCaseTest {

    @Test
    void yieldsToTheSwitchWhenTheSwitchIsAnArgument() throws Exception {
        Method size = define(switchAsArgument("example.AsmSwitchYield")).getMethod("size", int.class);

        assertEquals(10, size.invoke(null, 0));
        assertEquals(20, size.invoke(null, 1));
        assertEquals(30, size.invoke(null, 2));
    }

    /**
     * A class with a {@code size} method that passes a switch to {@code Integer.parseInt}. The
     * default case is a yield block with an early yield and a final one; were either written as a
     * method return, the class would not verify - {@code size} returns an int, not a string.
     */
    static ClassDef switchAsArgument(String name) {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(0), ExpressionDef.constant("10"));
        return ClassDef.builder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("size")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("count", TypeDef.Primitive.INT)
                .returns(TypeDef.Primitive.INT)
                .build((aThis, parameters) -> ClassTypeDef.of(Integer.class).invokeStatic(
                    "parseInt",
                    TypeDef.Primitive.INT,
                    parameters.get(0).asExpressionSwitch(
                        TypeDef.STRING,
                        cases,
                        new ExpressionDef.SwitchYieldCase(
                            TypeDef.STRING,
                            StatementDef.multi(
                                parameters.get(0).compare(EQUAL_TO, TypeDef.Primitive.INT.constant(1))
                                    .ifTrue(ExpressionDef.constant("20").returning()),
                                ExpressionDef.constant("30").returning()
                            )
                        )
                    )
                ).returning()))
            .build();
    }

    private static Class<?> define(ClassDef classDef) {
        byte[] bytes = new ByteCodeWriter().write(classDef);
        return new ClassLoader() {
            Class<?> define() {
                return defineClass(classDef.getName(), bytes, 0, bytes.length);
            }
        }.define();
    }
}
