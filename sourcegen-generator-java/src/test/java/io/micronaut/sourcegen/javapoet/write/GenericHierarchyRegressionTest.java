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
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for inherited and inferred generic scopes.
 */
class GenericHierarchyRegressionTest {

    @Test
    void classReceiverBindsInheritedInterfaceVariables() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addTypeVariable(u).addParameter("value", u).returns(u).build((self, p) -> p.getFirst().returning());
        var parent = InterfaceDef.builder("test.BoundParent").addTypeVariable(t).addMethod(identity).build();
        var child = ClassDef.builder("test.BoundChild").addSuperinterface(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class))).build();
        var caller = ClassDef.builder("test.InterfaceCaller")
            .addMethod(MethodDef.builder("call").addParameter("target", child.asTypeDef()).addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.lang.Number;

                interface BoundParent<T extends Number> {
                  default <U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(child, """
                package test;

                import java.lang.Integer;

                class BoundChild implements BoundParent<Integer> {
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;
                import java.lang.Object;

                class InterfaceCaller {
                  Number call(BoundChild target, Object value) {
                    return (Number) target.identity((Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void interfaceReceiverBindsInheritedInterfaceVariables() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", t);
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addTypeVariable(u).addParameter("value", u).returns(u).build((self, p) -> p.getFirst().returning());
        var parent = InterfaceDef.builder("test.RootBound").addTypeVariable(t).addMethod(identity).build();
        var z = TypeDef.variable("Z", TypeDef.of(Number.class));
        var child = InterfaceDef.builder("test.ChildBound").addTypeVariable(z).addSuperinterface(TypeDef.parameterized(parent.asTypeDef(), z)).build();
        var caller = ClassDef.builder("test.InterfaceReceiver")
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(child.asTypeDef(), TypeDef.of(Integer.class)))
                .addParameter("value", Object.class).returns(Number.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.lang.Number;

                interface RootBound<T extends Number> {
                  default <U extends T> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(child, """
                package test;

                import java.lang.Number;

                interface ChildBound<Z extends Number> extends RootBound<Z> {
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.Integer;
                import java.lang.Number;
                import java.lang.Object;

                class InterfaceReceiver {
                  Number call(ChildBound<Integer> target, Object value) {
                    return (Number) target.identity((Integer) value);
                  }
                }
                """)
        );
    }

    @Test
    void receiverArgumentsKeepCallerVariablesOutOfCalleeScope() throws IOException {
        var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var u = TypeDef.variable("U", x);
        var identity = MethodDef.builder("identity").addTypeVariable(t).addTypeVariable(u).addParameter("value", u).returns(u)
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.ScopedTarget").addTypeVariable(x).addMethod(identity).build();
        var callerT = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        var caller = ClassDef.builder("test.ScopedCaller").addTypeVariable(callerT)
            .addMethod(MethodDef.builder("call").addParameter("target", TypeDef.parameterized(target.asTypeDef(), callerT))
                .addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(identity, p.get(1)).returning())).build();
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.CharSequence;
                import java.lang.Number;

                class ScopedTarget<X extends CharSequence> {
                  <T extends Number, U extends X> U identity(U value) {
                    return value;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.CharSequence;
                import java.lang.Object;

                class ScopedCaller<T extends CharSequence> {
                  Object call(ScopedTarget<T> target, Object value) {
                    return target.identity((T) value);
                  }
                }
                """)
        );
    }

    @Test
    void inferredBoundUsesTheNarrowedSourceType() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(List.class, Object.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.NarrowedBound")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.parameterized(List.class, String.class), TypeDef.OBJECT))
            .addMethod(identity)
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Function;

            class NarrowedBound implements Function<List<String>, Object> {
              <T extends List<Object>> T identity(T value) {
                return value;
              }

              public Object apply(List<String> value) {
                return this.identity((List) value);
              }
            }
            """));
    }

    @Test
    void inferredBoundResolvesGeneratedValueHierarchy() throws IOException {
        var number = ClassDef.builder("test.GeneratedNumber").superclass(ClassTypeDef.of(AtomicInteger.class)).build();
        var t = TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.of(Runnable.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t).returns(t)
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.GeneratedBoundCall").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", number.asTypeDef()).returns(Number.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(
            assertSource(number, """
                package test;

                import java.util.concurrent.atomic.AtomicInteger;

                class GeneratedNumber extends AtomicInteger {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Number;
                import java.lang.Runnable;

                class GeneratedBoundCall {
                  <T extends Number & Runnable> T identity(T value) {
                    return value;
                  }

                  Number call(GeneratedNumber value) {
                    return (Number) this.identity((Number & Runnable) value);
                  }
                }
                """)
        );
    }

    @Test
    void narrowedGenericArgumentDoesNotUseTheCallersClassVariable() throws IOException {
        var t = TypeDef.variable("T", TypeDef.OBJECT);
        var singletonList = MethodDef.builder("singletonList").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t).addParameter("value", t).returns(TypeDef.parameterized(ClassTypeDef.of(List.class), t)).build();
        var s = TypeDef.variable("S", TypeDef.of(CharSequence.class));
        var parent = InterfaceDef.builder("test.BoundedSupplier").addTypeVariable(s)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(s).build()).build();
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(CharSequence.class)
            .build((self, p) -> ExpressionDef.constant("text").returning());
        var def = ClassDef.builder("test.NarrowedGenericCall").addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING)).addMethod(get)
            .addMethod(MethodDef.builder("call").returns(TypeDef.parameterized(List.class, Object.class))
                .build((self, p) -> ClassTypeDef.of(Collections.class).invokeStatic(singletonList, self.invoke(get)).returning())).build();
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.lang.CharSequence;

                interface BoundedSupplier<S extends CharSequence> {
                  S get();
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Number;
                import java.lang.Object;
                import java.lang.String;
                import java.util.Collections;
                import java.util.List;

                class NarrowedGenericCall<T extends Number> implements BoundedSupplier<String> {
                  public String get() {
                    return "text";
                  }

                  List<Object> call() {
                    return (List) Collections.singletonList((Object) this.get());
                  }
                }
                """)
        );
    }

    @Test
    void narrowedGeneratedSubtypeUsesRawArgumentConversion() throws IOException {
        var strings = ClassDef.builder("test.StringList").superclass(TypeDef.parameterized(ArrayList.class, String.class)).build();
        var consume = MethodDef.builder("consume").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.parameterized(List.class, Object.class)).returns(String.class)
            .build((self, p) -> ExpressionDef.constant("selected").returning());
        var def = ClassDef.builder("test.GeneratedSubtypeCall").addMethod(consume)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), strings.asTypeDef(), TypeDef.STRING))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> ClassTypeDef.of("test.GeneratedSubtypeCall").invokeStatic(consume, p.getFirst()).returning())).build();
        assertCompiles(
            assertSource(strings, """
                package test;

                import java.lang.String;
                import java.util.ArrayList;

                class StringList extends ArrayList<String> {
                }
                """),
            assertSource(def, """
                package test;

                import java.lang.Object;
                import java.lang.String;
                import java.util.List;
                import java.util.function.Function;

                class GeneratedSubtypeCall implements Function<StringList, String> {
                  public static String consume(List<Object> value) {
                    return "selected";
                  }

                  public String apply(StringList value) {
                    return GeneratedSubtypeCall.consume((List) value);
                  }
                }
                """)
        );
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
