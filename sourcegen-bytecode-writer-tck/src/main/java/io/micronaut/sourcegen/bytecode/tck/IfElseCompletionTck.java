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
package io.micronaut.sourcegen.bytecode.tck;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An if/else statement whose then branch completes normally continues after the whole statement, not into the else
 * branch.
 *
 * @since 2.3
 */
public abstract class IfElseCompletionTck {
    /**
     * @param definition The definition
     * @return The class file
     */
    protected abstract byte[] write(ObjectDef definition);

    @Test
    public void thenBranchThatCompletesSkipsTheElseBranch() throws Exception {
        var definition = ClassDef.builder("test.ifelse.Completes").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("flag", boolean.class).returns(TypeDef.STRING)
                .build((self, p) -> {
                    VariableDef.Local result = new VariableDef.Local("result", TypeDef.STRING);
                    return StatementDef.multi(
                        result.defineAndAssign(ExpressionDef.constant("none")),
                        p.getFirst().isTrue().doIfElse(
                            result.assign(ExpressionDef.constant("then")),
                            result.assign(ExpressionDef.constant("else"))),
                        result.returning());
                }))
            .build();
        Class<?> generated = define(definition);
        assertEquals("then", generated.getMethod("call", boolean.class).invoke(null, true));
        assertEquals("else", generated.getMethod("call", boolean.class).invoke(null, false));
    }

    private Class<?> define(ObjectDef definition) throws Exception {
        byte[] bytes = write(definition);
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return name.equals(definition.getName()) ? defineClass(name, bytes, 0, bytes.length) : super.findClass(name);
            }
        }.loadClass(definition.getName());
    }
}
