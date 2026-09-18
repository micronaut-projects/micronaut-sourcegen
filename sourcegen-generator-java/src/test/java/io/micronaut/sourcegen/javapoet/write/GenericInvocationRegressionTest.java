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
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for inferred calls and inherited parameterizations.
 */
class GenericInvocationRegressionTest {

    @Test
    void returnConvertsParameterizedGeneratedSubtype() throws IOException {
        var e = TypeDef.variable("E");
        var list = ClassDef.builder("test.GenericList").addTypeVariable(e)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(java.util.ArrayList.class), e)).build();
        var strings = TypeDef.parameterized(list.asTypeDef(), TypeDef.STRING);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", strings).build();
        var def = ClassDef.builder("test.GenericSubtypeReturn").addTypeVariable(t).addField(values)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field(values).returning())).build();
        assertCompiles(
            assertSource(list, """
                package test;

                import java.util.ArrayList;

                class GenericList<E> extends ArrayList<E> {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Object;
                import java.lang.String;
                import java.util.List;
                import java.util.function.Supplier;

                class GenericSubtypeReturn<T extends List<Object>> implements Supplier<T> {
                  GenericList<String> values;

                  public T get() {
                    return (T) (List) this.values;
                  }
                }
                """)
        );
    }

    @Test
    void invocationConvertsParameterizedGeneratedSubtype() throws IOException {
        var e = TypeDef.variable("E");
        var list = ClassDef.builder("test.GenericList").addTypeVariable(e)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(java.util.ArrayList.class), e)).build();
        var strings = TypeDef.parameterized(list.asTypeDef(), TypeDef.STRING);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.GenericSubtypeArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", strings).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(
            assertSource(list, """
                package test;

                import java.util.ArrayList;

                class GenericList<E> extends ArrayList<E> {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Object;
                import java.lang.String;
                import java.util.List;
                import java.util.function.Consumer;

                class GenericSubtypeArgument<T extends List<Object>> implements Consumer<T> {
                  public void accept(T value) {
                    return;
                  }

                  void call(GenericList<String> value) {
                    this.accept((T) (List) value);
                  }
                }
                """)
        );
    }

    @Test
    void calleeBoundDoesNotRetainItsOwnTypeArgument() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.RecursiveInference").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Comparable;
            import java.lang.Object;

            class RecursiveInference {
              <T extends Comparable<T>> T identity(T value) {
                return value;
              }

              Object call(Object value) {
                return this.identity((Comparable) value);
              }
            }
            """));
    }

    @Test
    void inferredIntersectionConvertsValueOfItsFirstBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.of(Runnable.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.BoundIntersectionArgument").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Number.class).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Number;
            import java.lang.Runnable;

            class BoundIntersectionArgument {
              <T extends Number & Runnable> T identity(T value) {
                return value;
              }

              Number call(Number value) {
                return (Number) this.identity((Number & Runnable) value);
              }
            }
            """));
    }

    @Test
    void inferredObjectCastPreservesSelectedOverload() throws IOException {
        var t = TypeDef.variable("T");
        var selected = MethodDef.builder("choose").addTypeVariable(t).addParameter("value", t).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("selected").returning());
        var other = MethodDef.builder("choose").addParameter("value", String.class).returns(int.class)
            .build((self, p) -> ExpressionDef.constant(2).returning());
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.constant("text").returning());
        var def = ClassDef.builder("test.InferredOverload")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .addMethod(selected).addMethod(other).addMethod(get)
            .addMethod(MethodDef.builder("call").returns(String.class)
                .build((self, p) -> self.invoke(selected, self.invoke(get)).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.function.Supplier;

            class InferredOverload implements Supplier<String> {
              <T> String choose(T value) {
                return "selected";
              }

              int choose(String value) {
                return 2;
              }

              public String get() {
                return "text";
              }

              String call() {
                return this.choose((Object) this.get());
              }
            }
            """));
    }

    @Test
    void calleeClassBoundUsesReceiverTypeArguments() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.GenericTarget").addTypeVariable(t).addMethod(identity).build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.of(Integer.class));
        var caller = ClassDef.builder("test.ReceiverBoundCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.Number;

                class GenericTarget<T extends Number> {
                  <U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;
                import java.lang.Object;

                class ReceiverBoundCaller {
                  Number call(GenericTarget<Integer> target, Object value) {
                    return (Number) target.identity((Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void primitiveArgumentBoxesBeforeFixedVariableConversion() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.PrimitiveVariableArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", int.class).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.util.function.Consumer;

            class PrimitiveVariableArgument<T extends Number> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              void call(int value) {
                this.accept((T) (Integer) value);
              }
            }
            """));
    }

    @Test
    void primitiveReturnBoxesBeforeFixedVariableConversion() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var def = ClassDef.builder("test.PrimitiveVariableReturn").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant(1).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.util.function.Supplier;

            class PrimitiveVariableReturn<T extends Number> implements Supplier<T> {
              public T get() {
                return (T) (Integer) 1;
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
