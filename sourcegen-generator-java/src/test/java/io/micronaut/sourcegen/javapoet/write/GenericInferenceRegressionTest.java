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

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for bounds resolved in the caller and callee scopes.
 */
class GenericInferenceRegressionTest {

    @Test
    void inferredBoundKeepsTheReceiversFixedVariable() throws IOException {
        var x = TypeDef.variable("X", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", x);
        var identity = MethodDef.builder("identity").addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.FixedTarget").addTypeVariable(x).addMethod(identity).build();
        var v = TypeDef.variable("V", TypeDef.of(Number.class));
        var caller = ClassDef.builder("test.FixedCaller").addTypeVariable(v)
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(target.asTypeDef(), v))
                .addParameter("value", Number.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.Number;

                class FixedTarget<X extends Number> {
                  <U extends X> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Number;

                class FixedCaller<V extends Number> {
                  Number call(FixedTarget<V> target, Number value) {
                    return (Number) target.identity((V) value);
                  }
                }
                """)
        );
    }

    @Test
    void boxedArgumentConvertsToTheReceiversFixedVariable() throws IOException {
        var x = TypeDef.variable("X", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", x);
        var identity = MethodDef.builder("identity").addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.BoxedFixedTarget").addTypeVariable(x).addMethod(identity).build();
        var v = TypeDef.variable("V", TypeDef.of(Number.class));
        var caller = ClassDef.builder("test.BoxedFixedCaller").addTypeVariable(v)
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(target.asTypeDef(), v))
                .addParameter("value", int.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.Number;

                class BoxedFixedTarget<X extends Number> {
                  <U extends X> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;

                class BoxedFixedCaller<V extends Number> {
                  Number call(BoxedFixedTarget<V> target, int value) {
                    return (Number) target.identity((V) (Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void chainedRecursiveBoundDoesNotRetainCalleeVariables() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addTypeVariable(t).addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.ChainedRecursive")
            .addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Comparable;
            import java.lang.Object;

            class ChainedRecursive {
              <T extends Comparable<T>, U extends T> U identity(U value) {
                return value;
              }

              Object call(Object value) {
                return this.identity((Comparable) value);
              }
            }
            """));
    }

    private static String assertSource(ObjectDef objectDef, String expected) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(objectDef, writer);
            String source = writer.toString();
            assertEquals(expected, source);
            return source;
        }
    }
}
