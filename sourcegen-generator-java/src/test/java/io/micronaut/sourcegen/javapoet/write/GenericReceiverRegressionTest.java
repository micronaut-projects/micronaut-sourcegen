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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertSource;

/**
 * Regression coverage for receiver scopes and generic argument and return conversions.
 */
class GenericReceiverRegressionTest {

    @Test
    void receiverArgumentsDoNotSubstituteShadowingMethodVariables() throws IOException {
        var methodT = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", methodT);
        var identity = MethodDef.builder("identity").addTypeVariable(methodT).addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.ShadowTarget").addTypeVariable(TypeDef.variable("T")).addMethod(identity).build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.STRING);
        var caller = ClassDef.builder("test.ShadowCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.Number;

                class ShadowTarget<T> {
                  <T extends Number, U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Number;
                import java.lang.Object;
                import java.lang.String;

                class ShadowCaller {
                  Number call(ShadowTarget<String> target, Object value) {
                    return (Number) target.identity((Number) value);
                  }
                }
                """)
        );
    }

    @Test
    void receiverArgumentsDoNotSubstituteRecursiveMethodBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.RecursiveTarget").addTypeVariable(TypeDef.variable("T")).addMethod(identity).build();
        var receiver = TypeDef.parameterized(target.asTypeDef(), TypeDef.of(Integer.class));
        var caller = ClassDef.builder("test.RecursiveCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.Comparable;

                class RecursiveTarget<T> {
                  <T extends Comparable<T>> T identity(T value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Comparable;
                import java.lang.Integer;
                import java.lang.Object;

                class RecursiveCaller {
                  Object call(RecursiveTarget<Integer> target, Object value) {
                    return target.identity((Comparable) value);
                  }
                }
                """)
        );
    }

    @Test
    void inheritedCalleeBoundsUseDeclaringClassArguments() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var parent = ClassDef.builder("test.BoundedParent").addTypeVariable(t).addMethod(identity).build();
        var z = TypeDef.variable("Z", TypeDef.of(Number.class));
        var child = ClassDef.builder("test.BoundedChild").addTypeVariable(z)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), z)).build();
        var receiver = TypeDef.parameterized(child.asTypeDef(), TypeDef.of(Integer.class));
        var caller = ClassDef.builder("test.InheritedBoundCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.lang.Number;

                class BoundedParent<T extends Number> {
                  public <U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(child, """
                package test;

                import java.lang.Number;

                class BoundedChild<Z extends Number> extends BoundedParent<Z> {
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;
                import java.lang.Object;

                class InheritedBoundCaller {
                  Number call(BoundedChild<Integer> target, Object value) {
                    return (Number) target.identity((Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void nonGenericChildKeepsInheritedCalleeBounds() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var parent = ClassDef.builder("test.FixedParent").addTypeVariable(t).addMethod(identity).build();
        var child = ClassDef.builder("test.FixedChild")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class))).build();
        var caller = ClassDef.builder("test.FixedBoundCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", child.asTypeDef()).addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.lang.Number;

                class FixedParent<T extends Number> {
                  public <U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(child, """
                package test;

                import java.lang.Integer;

                class FixedChild extends FixedParent<Integer> {
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;
                import java.lang.Object;

                class FixedBoundCaller {
                  Number call(FixedChild target, Object value) {
                    return (Number) target.identity((Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void inferredBoundChecksTypeArguments() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.ParameterizedBoundCall").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.parameterized(List.class, String.class)).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;

            class ParameterizedBoundCall {
              <T extends List<Object>> T identity(T value) {
                return value;
              }

              Object call(List<String> value) {
                return this.identity((List) value);
              }
            }
            """));
    }

    @Test
    void inferredBoundResolvesTheArgumentVariable() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.of(Runnable.class));
        var v = TypeDef.variable("V", TypeDef.of(Number.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.VariableBoundCall").addTypeVariable(v).addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", v).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Number;
            import java.lang.Runnable;

            class VariableBoundCall<V extends Number> {
              <T extends Number & Runnable> T identity(T value) {
                return value;
              }

              Number call(V value) {
                return (Number) this.identity((Number & Runnable) value);
              }
            }
            """));
    }

    @Test
    void boxedArgumentConvertsThroughIncompatibleGenericBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, String.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.BoxedBoundArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", int.class).returns(void.class)
                .build((self, p) -> self.invoke(accept, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Comparable;
            import java.lang.Integer;
            import java.lang.String;
            import java.util.function.Consumer;

            class BoxedBoundArgument<T extends Comparable<String>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              void call(int value) {
                this.accept((T) (Comparable) (Integer) value);
              }
            }
            """));
    }

    @Test
    void boxedReturnConvertsThroughIncompatibleGenericBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, String.class));
        var def = ClassDef.builder("test.BoxedBoundReturn").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((self, p) -> ExpressionDef.constant(1).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Comparable;
            import java.lang.Integer;
            import java.lang.String;
            import java.util.function.Supplier;

            class BoxedBoundReturn<T extends Comparable<String>> implements Supplier<T> {
              public T get() {
                return (T) (Comparable) (Integer) 1;
              }
            }
            """));
    }

    @Test
    void narrowedArgumentUsesRawParameterizedConversion() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var objects = TypeDef.parameterized(List.class, Object.class);
        var consume = MethodDef.builder("consume").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", objects).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("selected").returning());
        var def = ClassDef.builder("test.ArgumentCall").addMethod(consume)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), strings, TypeDef.STRING))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> ClassTypeDef.of("test.ArgumentCall").invokeStatic(consume, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class ArgumentCall implements Function<List<String>, String> {
              public static String consume(List<Object> value) {
                return "selected";
              }

              public String apply(List<String> value) {
                return ArgumentCall.consume((List) value);
              }
            }
            """));
    }

    @Test
    void inferredBoundChecksTheBoxedArgument() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, String.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.InferredBoxedBound").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", int.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Comparable;
            import java.lang.Integer;
            import java.lang.Object;
            import java.lang.String;

            class InferredBoxedBound {
              <T extends Comparable<String>> T identity(T value) {
                return value;
              }

              Object call(int value) {
                return this.identity((Comparable) (Integer) value);
              }
            }
            """));
    }
}
