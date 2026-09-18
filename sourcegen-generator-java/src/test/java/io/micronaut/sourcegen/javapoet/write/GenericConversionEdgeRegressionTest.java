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
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Further bound and lambda scope regressions.
 */
class GenericConversionEdgeRegressionTest {

    @Test
    void expressionLambdaRendersMethodVariableWithoutClassShadow() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", TypeDef.parameterized(List.class, String.class)).build();
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.variable("T"));
        var def = ClassDef.builder("test.MethodOnlyLambda").addField(values)
            .addMethod(MethodDef.builder("delegate").addTypeVariable(t).returns(supplier)
                .build((self, p) -> supplier.getLambda().implement((ls, lp) -> self.field(values).returning()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class MethodOnlyLambda {
              List<String> values;

              <T extends List<Object>> Supplier<T> delegate() {
                return () -> (T) (List) this.values;
              }
            }
            """));
    }

    @Test
    void blockLambdaResolvesEnclosingMethodBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var strings = TypeDef.parameterized(List.class, String.class);
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.variable("T"));
        var def = ClassDef.builder("test.BlockMethodLambda")
            .addTypeVariable(TypeDef.variable("T", TypeDef.parameterized(List.class, String.class)))
            .addMethod(MethodDef.builder("delegate").addTypeVariable(t).addParameter("values", strings).returns(supplier)
                .build((self, p) -> supplier.getLambda().implement((ls, lp) -> ExpressionDef.constant(true).isTrue().doIfElse(
                    p.getFirst().returning(), p.getFirst().returning())).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class BlockMethodLambda<T extends List<String>> {
              <T extends List<Object>> Supplier<T> delegate(List<String> values) {
                return () -> {
                  if (true) {
                    return (T) (List) values;
                  } else {
                    return (T) (List) values;
                  }
                };
              }
            }
            """));
    }

    @Test
    void returnChecksEverySourceVariableBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(Supplier.class, String.class), TypeDef.parameterized(List.class, String.class));
        var def = ClassDef.builder("test.SourceIntersectionReturn").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), v, t))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.Supplier;

            class SourceIntersectionReturn<T extends List<Object>, V extends Supplier<String> & List<String>> implements Function<V, T> {
              public T apply(V value) {
                return (T) (List) value;
              }
            }
            """));
    }

    @Test
    void invocationChecksEverySourceVariableBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(Supplier.class, String.class), TypeDef.parameterized(List.class, String.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.SourceIntersectionArgument").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", v).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;

            class SourceIntersectionArgument<T extends List<Object>, V extends Supplier<String> & List<String>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              void call(V value) {
                this.accept((T) (List) value);
              }
            }
            """));
    }

    @Test
    void returnConvertsConcreteParameterizedSubtype() throws IOException {
        var list = ClassDef.builder("test.StringList").superclass(TypeDef.parameterized(java.util.ArrayList.class, String.class)).build();
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", list.asTypeDef()).build();
        var def = ClassDef.builder("test.ConcreteListReturn").addTypeVariable(t).addField(values)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field(values).returning())).build();
        assertCompiles(
            assertSource(list, """
                package test;

                import java.lang.String;
                import java.util.ArrayList;

                class StringList extends ArrayList<String> {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Object;
                import java.util.List;
                import java.util.function.Supplier;

                class ConcreteListReturn<T extends List<Object>> implements Supplier<T> {
                  StringList values;

                  public T get() {
                    return (T) (List) this.values;
                  }
                }
                """)
        );
    }

    @Test
    void referenceResultConvertsConcreteParameterizedSubtype() throws IOException {
        var list = ClassDef.builder("test.StringList").superclass(TypeDef.parameterized(java.util.ArrayList.class, String.class)).build();
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t);
        var def = ClassDef.builder("test.ConcreteListReference").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), list.asTypeDef())).addMethod(get)
            .addMethod(MethodDef.builder("delegate").returns(supplier)
                .build((self, p) -> supplier.methodReference(self, get).returning())).build();
        assertCompiles(
            assertSource(list, """
                package test;

                import java.lang.String;
                import java.util.ArrayList;

                class StringList extends ArrayList<String> {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Object;
                import java.util.List;
                import java.util.function.Supplier;

                class ConcreteListReference<T extends List<Object>> implements Supplier<StringList> {
                  public StringList get() {
                    return null;
                  }

                  Supplier<T> delegate() {
                    return () -> (T) (List) this.get();
                  }
                }
                """)
        );
    }

    @Test
    void invocationResolvesCalleeVariableBoundChain() throws IOException {
        var u = TypeDef.variable("U", TypeDef.of(Number.class));
        var v = TypeDef.variable("V", TypeDef.variable("U"));
        var identity = MethodDef.builder("identity").addTypeVariable(u).addTypeVariable(v).addParameter("value", v).returns(v)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.ChainedInference").addTypeVariable(TypeDef.variable("U")).addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Number;
            import java.lang.Object;

            class ChainedInference<U> {
              <U extends Number, V extends U> V identity(V value) {
                return value;
              }

              Number call(Object value) {
                return (Number) this.identity((Number) value);
              }
            }
            """));
    }

    @Test
    void capturedBoundRetainsCallerVariables() throws IOException {
        var a = TypeDef.variable("A");
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(List.class), a));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var target = ClassDef.builder("test.CapturedVariableTarget").addTypeVariable(a).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t)).addMethod(get).build();
        var n = TypeDef.variable("N", TypeDef.of(Number.class));
        var receiver = TypeDef.parameterized(target.asTypeDef(), n, TypeDef.wildcard());
        var strings = TypeDef.parameterized(List.class, String.class);
        var caller = ClassDef.builder("test.CapturedVariableCaller").addTypeVariable(n)
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).returns(strings)
                .build((self, p) -> p.getFirst().invoke(get).cast(strings).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.util.List;
                import java.util.function.Supplier;

                class CapturedVariableTarget<A, T extends List<A>> implements Supplier<T> {
                  public T get() {
                    return null;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Number;
                import java.lang.String;
                import java.util.List;

                class CapturedVariableCaller<N extends Number> {
                  List<String> call(CapturedVariableTarget<N, ?> target) {
                    return (List) target.get();
                  }
                }
                """)
        );
    }

    @Test
    void narrowedArgumentKeepsCalleeBoundCast() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.constant(1).returning());
        var def = ClassDef.builder("test.NarrowedCalleeBound").addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, Integer.class)).addMethod(get).addMethod(identity)
            .addMethod(MethodDef.builder("call").returns(Number.class)
                .build((self, p) -> self.invoke(identity, self.invoke(get)).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.CharSequence;
            import java.lang.Integer;
            import java.lang.Number;
            import java.util.function.Supplier;

            class NarrowedCalleeBound<T extends CharSequence> implements Supplier<Integer> {
              public Integer get() {
                return 1;
              }

              <T extends Number> T identity(T value) {
                return value;
              }

              Number call() {
                return (Number) this.identity((Number) this.get());
              }
            }
            """));
    }

    @Test
    void invocationRespectsCalleeIntersectionBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.of(Runnable.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.IntersectionInference").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Number;
            import java.lang.Object;
            import java.lang.Runnable;

            class IntersectionInference {
              <T extends Number & Runnable> T identity(T value) {
                return value;
              }

              Number call(Object value) {
                return (Number) this.identity((Number & Runnable) value);
              }
            }
            """));
    }

    @Test
    void invocationResolvesCalleeVariableInsideArray() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t.array()).returns(t.array())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.ArrayInference").addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class))).addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(TypeDef.of(Number.class).array())
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.CharSequence;
            import java.lang.Number;
            import java.lang.Object;

            class ArrayInference<T extends CharSequence> {
              <T extends Number> T[] identity(T[] value) {
                return value;
              }

              Number[] call(Object value) {
                return (Number[]) this.identity((Number[]) value);
              }
            }
            """));
    }

    @Test
    void invocationResolvesCalleeVariableInsideParameterization() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var accept = MethodDef.builder("accept").addTypeVariable(t).addParameter("value", TypeDef.parameterized(ClassTypeDef.of(List.class), t)).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.ParameterizedInference").addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class))).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.CharSequence;
            import java.lang.Number;
            import java.lang.Object;
            import java.util.List;

            class ParameterizedInference<T extends CharSequence> {
              <T extends Number> void accept(List<T> value) {
                return;
              }

              void call(Object value) {
                this.accept((List) value);
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
