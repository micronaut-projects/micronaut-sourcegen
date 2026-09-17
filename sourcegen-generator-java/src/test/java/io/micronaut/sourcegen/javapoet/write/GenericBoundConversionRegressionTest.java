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
import java.io.Serializable;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generic bound conversions must retain the scope of variables and the types emitted by override resolution.
 */
class GenericBoundConversionRegressionTest {

    @Test
    void generatedMethodVariableShadowsClassVariable() throws Exception {
        var u = TypeDef.variable("U");
        var identity = MethodDef.builder("identity").addTypeVariable(u).addParameter("value", u).returns(u)
            .build((aThis, parameters) -> parameters.getFirst().returning());
        var length = String.class.getMethod("length");
        var classDef = ClassDef.builder("test.GenericCalls").addTypeVariable(TypeDef.variable("U", TypeDef.of(Number.class)))
            .addMethod(identity)
            .addMethod(MethodDef.builder("length").returns(int.class)
                .build((aThis, parameters) -> aThis.invoke(identity, ExpressionDef.constant("text")).invoke(length).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Number;

            class GenericCalls<U extends Number> {
              <U> U identity(U value) {
                return value;
              }

              int length() {
                return this.identity("text").length();
              }
            }
            """));
    }

    @Test
    void capturedBoundKeepsRemainingReceiverArguments() throws IOException {
        var a = TypeDef.variable("A");
        var b = TypeDef.variable("B", a);
        var c = TypeDef.variable("C", b);
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var target = ClassDef.builder("test.ChainedTarget").addTypeVariable(a).addTypeVariable(b).addTypeVariable(c)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), c))
            .addMethod(get)
            .build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.of(CharSequence.class), TypeDef.wildcard(), TypeDef.wildcard());
        var selected = MethodDef.builder("choose").addModifiers(Modifier.STATIC).addParameter("value", Object.class).returns(String.class)
            .build((aThis, parameters) -> ExpressionDef.constant("selected").returning());
        var other = MethodDef.builder("choose").addModifiers(Modifier.STATIC).addParameter("value", CharSequence.class).returns(int.class)
            .build((aThis, parameters) -> ExpressionDef.constant(2).returning());
        var caller = ClassDef.builder("test.ChainedCaller").addMethod(selected).addMethod(other)
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).returns(String.class)
                .build((aThis, parameters) -> ClassTypeDef.of("test.ChainedCaller")
                    .invokeStatic(selected, parameters.getFirst().invoke(get)).returning()))
            .build();

        assertCompiles(
            assertSource(target, """
                package test;

                import java.util.function.Supplier;

                class ChainedTarget<A, B extends A, C extends B> implements Supplier<C> {
                  public C get() {
                    return null;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.CharSequence;
                import java.lang.Object;
                import java.lang.String;

                class ChainedCaller {
                  static String choose(Object value) {
                    return "selected";
                  }

                  static int choose(CharSequence value) {
                    return 2;
                  }

                  String call(ChainedTarget<CharSequence, ?, ?> target) {
                    return ChainedCaller.choose((Object) target.get());
                  }
                }
                """)
        );
    }

    @Test
    void referenceResultResolvesMethodVariableBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.parameterized(List.class, Object.class));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.variable("U"));
        var classDef = ClassDef.builder("test.MethodReferenceBound")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addMethod(get)
            .addMethod(MethodDef.builder("delegate").addTypeVariable(u).returns(supplier)
                .build((aThis, parameters) -> supplier.methodReference(aThis, get).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class MethodReferenceBound implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              <U extends List<Object>> Supplier<U> delegate() {
                return () -> (U) (List) this.get();
              }
            }
            """));
    }

    @Test
    void returnResolvesMethodVariableBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.parameterized(List.class, Object.class));
        var field = FieldDef.builder("values", strings).build();
        var classDef = ClassDef.builder("test.MethodReturnBound").addField(field)
            .addMethod(MethodDef.builder("get").addTypeVariable(u).returns(TypeDef.variable("U"))
                .build((aThis, parameters) -> aThis.field(field).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;

            class MethodReturnBound {
              List<String> values;

              <U extends List<Object>> U get() {
                return (U) (List) this.values;
              }
            }
            """));
    }

    @Test
    void explicitCastFollowsVariableBoundChain() throws IOException {
        var v = TypeDef.variable("V", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", v);
        var strings = TypeDef.parameterized(List.class, String.class);
        var variables = TypeDef.parameterized(ClassTypeDef.of(List.class), u);
        var classDef = ClassDef.builder("test.ChainedCast").addTypeVariable(v).addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), strings, TypeDef.OBJECT))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((aThis, parameters) -> parameters.getFirst().cast(variables).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Number;
            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class ChainedCast<V extends Number, U extends V> implements Function<List<String>, Object> {
              public Object apply(List<String> value) {
                return (List) value;
              }
            }
            """));
    }

    @Test
    void explicitCastResolvesMethodVariableBound() throws IOException {
        var u = TypeDef.variable("U", TypeDef.of(Number.class));
        var strings = TypeDef.parameterized(List.class, String.class);
        var variables = TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.variable("U"));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var classDef = ClassDef.builder("test.MethodCastBound")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addMethod(get)
            .addMethod(MethodDef.builder("cast").addTypeVariable(u).returns(Object.class)
                .build((aThis, parameters) -> aThis.invoke(get).cast(variables).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Number;
            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class MethodCastBound implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              <U extends Number> Object cast() {
                return (List) this.get();
              }
            }
            """));
    }

    @Test
    void returnConversionUsesNarrowedExpressionType() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.parameterized(List.class, Object.class));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var classDef = ClassDef.builder("test.NarrowedReturn").addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addMethod(get)
            .addMethod(MethodDef.builder("delegate").returns(u)
                .build((aThis, parameters) -> aThis.invoke(get).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class NarrowedReturn<U extends List<Object>> implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              U delegate() {
                return (U) (List) this.get();
              }
            }
            """));
    }

    @Test
    void invocationConvertsThroughParameterizedVariableBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((aThis, parameters) -> new StatementDef.Return(null));
        var classDef = ClassDef.builder("test.BoundedArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("values", strings).returns(void.class)
                .build((aThis, parameters) -> aThis.invoke(accept, parameters.getFirst())))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;

            class BoundedArgument<T extends List<Object>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              void call(List<String> values) {
                this.accept((T) (List) values);
              }
            }
            """));
    }

    @Test
    void referenceConvertsArgumentThroughParameterizedVariableBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((aThis, parameters) -> new StatementDef.Return(null));
        var consumer = TypeDef.parameterized(ClassTypeDef.of(Consumer.class), strings);
        var classDef = ClassDef.builder("test.BoundedReferenceArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(accept)
            .addMethod(MethodDef.builder("delegate").returns(consumer)
                .build((aThis, parameters) -> consumer.methodReference(aThis, accept).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;

            class BoundedReferenceArgument<T extends List<Object>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              Consumer<List<String>> delegate() {
                return (arg) -> this.accept((T) (List) arg);
              }
            }
            """));
    }

    @Test
    void returnRespectsSecondaryParameterizedBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.of(Serializable.class), TypeDef.parameterized(List.class, Object.class));
        var field = FieldDef.builder("values", strings).build();
        var classDef = ClassDef.builder("test.IntersectionReturn").addTypeVariable(u).addField(field)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), u))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((aThis, parameters) -> aThis.field(field).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.io.Serializable;
            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class IntersectionReturn<U extends Serializable & List<Object>> implements Supplier<U> {
              List<String> values;

              public U get() {
                return (U) (List) this.values;
              }
            }
            """));
    }

    @Test
    void referenceResultRespectsSecondaryParameterizedBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.of(Serializable.class), TypeDef.parameterized(List.class, Object.class));
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), u);
        var classDef = ClassDef.builder("test.IntersectionReference").addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addMethod(get)
            .addMethod(MethodDef.builder("delegate").returns(supplier)
                .build((aThis, parameters) -> supplier.methodReference(aThis, get).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.io.Serializable;
            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class IntersectionReference<U extends Serializable & List<Object>> implements Supplier<List<String>> {
              public List<String> get() {
                return null;
              }

              Supplier<U> delegate() {
                return () -> (U) (List) this.get();
              }
            }
            """));
    }

    @Test
    void expressionLambdaConvertsThroughParameterizedVariableBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var u = TypeDef.variable("U", TypeDef.parameterized(List.class, Object.class));
        var values = FieldDef.builder("values", strings).build();
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class), u);
        var classDef = ClassDef.builder("test.BoundedLambda").addTypeVariable(u).addField(values)
            .addMethod(MethodDef.builder("delegate").returns(supplier)
                .build((aThis, parameters) -> supplier.getLambda()
                    .implement((lambdaSelf, lambdaParameters) -> aThis.field(values).returning()).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class BoundedLambda<U extends List<Object>> {
              List<String> values;

              Supplier<U> delegate() {
                return () -> (U) (List) this.values;
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
