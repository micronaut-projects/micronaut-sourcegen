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

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertSource;

/**
 * Regression coverage for conversions across generic scopes.
 */
class GenericConversionScopeRegressionTest {

    @Test
    void narrowedInvocationArgumentUsesRawBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.NarrowedArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(get).addMethod(accept)
            .addMethod(MethodDef.builder("call").returns(void.class)
                .build((self, p) -> self.invoke(accept, self.invoke(get)))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;

            class NarrowedArgument<T extends List<Object>> implements Supplier<List<String>>, Consumer<T> {
              public List<String> get() {
                return null;
              }

              public void accept(T value) {
                return;
              }

              void call() {
                this.accept((T) (List) this.get());
              }
            }
            """));
    }

    @Test
    void returnConvertsVariableWithIncompatibleBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(List.class, String.class));
        var def = ClassDef.builder("test.VariableReturn").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), v, t))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class VariableReturn<T extends List<Object>, V extends List<String>> implements Function<V, T> {
              public T apply(V value) {
                return (T) (List) value;
              }
            }
            """));
    }

    @Test
    void referenceResultConvertsVariableWithIncompatibleBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(List.class, String.class));
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t);
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var def = ClassDef.builder("test.VariableReferenceResult").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), v)).addMethod(get)
            .addMethod(MethodDef.builder("delegate").returns(supplier)
                .build((self, p) -> supplier.methodReference(self, get).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class VariableReferenceResult<T extends List<Object>, V extends List<String>> implements Supplier<V> {
              public V get() {
                return null;
              }

              Supplier<T> delegate() {
                return () -> (T) (List) this.get();
              }
            }
            """));
    }

    @Test
    void invocationConvertsVariableWithIncompatibleBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(List.class, String.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.VariableArgument").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", v).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;

            class VariableArgument<T extends List<Object>, V extends List<String>> implements Consumer<T> {
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
    void referenceArgumentConvertsVariableWithIncompatibleBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var v = TypeDef.variable("V", TypeDef.parameterized(List.class, String.class));
        var consumer = TypeDef.parameterized(ClassTypeDef.of(Consumer.class), v);
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.VariableReferenceArgument").addTypeVariable(t).addTypeVariable(v)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("delegate").returns(consumer)
                .build((self, p) -> consumer.methodReference(self, accept).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;

            class VariableReferenceArgument<T extends List<Object>, V extends List<String>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              Consumer<V> delegate() {
                return (arg) -> this.accept((T) (List) arg);
              }
            }
            """));
    }

    @Test
    void lambdaResolvesEnclosingMethodBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", TypeDef.parameterized(List.class, String.class)).build();
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.variable("T"));
        var def = ClassDef.builder("test.MethodLambda").addTypeVariable(TypeDef.variable("T", TypeDef.parameterized(List.class, String.class))).addField(values)
            .addMethod(MethodDef.builder("delegate").addTypeVariable(t).returns(supplier)
                .build((self, p) -> supplier.getLambda().implement((ls, lp) -> self.field(values).returning()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class MethodLambda<T extends List<String>> {
              List<String> values;

              <T extends List<Object>> Supplier<T> delegate() {
                return () -> (T) (List) this.values;
              }
            }
            """));
    }

    @Test
    void explicitCastRespectsAllIntersectionBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Serializable.class), TypeDef.of(CharSequence.class));
        var integers = TypeDef.parameterized(List.class, Integer.class);
        var target = TypeDef.parameterized(ClassTypeDef.of(List.class), t);
        var def = ClassDef.builder("test.IntersectionCast").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), integers, TypeDef.OBJECT))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().cast(target).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.io.Serializable;
            import java.lang.CharSequence;
            import java.lang.Integer;
            import java.lang.Object;
            import java.util.List;
            import java.util.function.Function;

            class IntersectionCast<T extends Serializable & CharSequence> implements Function<List<Integer>, Object> {
              public Object apply(List<Integer> value) {
                return (List) value;
              }
            }
            """));
    }

    @Test
    void returnChecksAllParameterizedIntersectionBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(Supplier.class, String.class), TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", TypeDef.parameterized(List.class, String.class)).build();
        var def = ClassDef.builder("test.MultipleBounds").addTypeVariable(t).addField(values)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> self.field(values).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class MultipleBounds<T extends Supplier<String> & List<Object>> implements Supplier<T> {
              List<String> values;

              public T get() {
                return (T) (List) this.values;
              }
            }
            """));
    }

    @Test
    void inferredBoundedMethodVariableAcceptsErasedArgument() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.BoundedInference").addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class))).addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.CharSequence;
            import java.lang.Number;
            import java.lang.Object;

            class BoundedInference<T extends CharSequence> {
              <T extends Number> T identity(T value) {
                return value;
              }

              Number call(Object value) {
                return (Number) this.identity((Number) value);
              }
            }
            """));
    }

    @Test
    void capturedParameterizedBoundRetainsTypeArguments() throws IOException {
        var a = TypeDef.variable("A");
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(List.class), a));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((self, p) -> ExpressionDef.nullValue().returning());
        var target = ClassDef.builder("test.CapturedListTarget").addTypeVariable(a).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t)).addMethod(get).build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.STRING, TypeDef.wildcard());
        var objects = TypeDef.parameterized(List.class, Object.class);
        var caller = ClassDef.builder("test.CapturedListCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).returns(objects)
                .build((self, p) -> p.getFirst().invoke(get).cast(objects).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.util.List;
                import java.util.function.Supplier;

                class CapturedListTarget<A, T extends List<A>> implements Supplier<T> {
                  public T get() {
                    return null;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Object;
                import java.lang.String;
                import java.util.List;

                class CapturedListCaller {
                  List<Object> call(CapturedListTarget<String, ?> target) {
                    return (List) target.get();
                  }
                }
                """)
        );
    }
}
