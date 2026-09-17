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
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compilation regressions for generic overrides and the expressions that use their normalized signatures.
 */
class GenericOverrideRegressionTest {

    @Test
    void capturedResultKeepsTheBoundOfAnotherCapturedVariable() throws IOException {
        var a = TypeDef.variable("A", TypeDef.of(CharSequence.class));
        var t = TypeDef.variable("T", a);
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ExpressionDef.nullValue().returning());
        var target = ClassDef.builder("test.DependentTarget").addTypeVariable(a).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(get)
            .build();
        var receiverType = TypeDef.parameterized(target.asTypeDef(), TypeDef.wildcard(), TypeDef.wildcard());
        var chooseObject = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", Object.class).returns(String.class)
            .build((aThis, parameters) -> ExpressionDef.constant("selected").returning());
        var chooseSequence = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", CharSequence.class).returns(int.class)
            .build((aThis, parameters) -> ExpressionDef.constant(2).returning());
        var caller = ClassDef.builder("test.DependentCaller")
            .addMethod(chooseObject)
            .addMethod(chooseSequence)
            .addMethod(MethodDef.builder("call").addParameter("target", receiverType).returns(String.class)
                .build((aThis, parameters) -> ClassTypeDef.of("test.DependentCaller")
                    .invokeStatic(chooseObject, parameters.getFirst().invoke(get)).returning()))
            .build();

        // Choosing the CharSequence overload instead of the modeled Object overload cannot return a String.
        assertCompiles(
            assertSource(target, """
                package test;

                import java.lang.CharSequence;
                import java.util.function.Supplier;

                class DependentTarget<A extends CharSequence, T extends A> implements Supplier<T> {
                  public T get() {
                    return null;
                  }
                }
                """),
            assertSource(caller, """
                package test;

                import java.lang.CharSequence;
                import java.lang.Object;
                import java.lang.String;

                class DependentCaller {
                  public static String choose(Object value) {
                    return "selected";
                  }

                  public static int choose(CharSequence value) {
                    return 2;
                  }

                  String call(DependentTarget<?, ?> target) {
                    return DependentCaller.choose((Object) target.get());
                  }
                }
                """)
        );
    }

    @Test
    void referenceResultResolvesAChainedParameterizedBound() throws Exception {
        assertReferenceResultCompiles(false);
    }

    @Test
    void referenceResultResolvesABoundFromTheVariableDeclaration() throws Exception {
        assertReferenceResultCompiles(true);
    }

    private void assertReferenceResultCompiles(boolean symbolicReference) throws Exception {
        var strings = TypeDef.parameterized(List.class, String.class);
        var objects = TypeDef.parameterized(List.class, Object.class);
        var v = TypeDef.variable("V", objects);
        var u = TypeDef.variable("U", symbolicReference ? objects : v);
        var singleton = Collections.class.getMethod("singletonList", Object.class);
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
            .build((aThis, parameters) -> ClassTypeDef.of(Collections.class)
                .invokeStatic(singleton, ExpressionDef.constant("text")).returning());
        var supplier = TypeDef.parameterized(ClassTypeDef.of(Supplier.class),
            symbolicReference ? TypeDef.variable("U") : u);
        var classDef = ClassDef.builder("test.VariableReference").addTypeVariable(v).addTypeVariable(u)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), strings))
            .addMethod(get)
            .addMethod(MethodDef.builder("delegate").returns(supplier)
                .build((aThis, parameters) -> supplier.methodReference(aThis, get).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.Collections;
            import java.util.List;
            import java.util.function.Supplier;

            class VariableReference<V extends List<Object>, U extends %s> implements Supplier<List<String>> {
              public List<String> get() {
                return Collections.singletonList("text");
              }

              Supplier<U> delegate() {
                return () -> (U) (List) this.get();
              }
            }
            """.formatted(symbolicReference ? "List<Object>" : "V")));
    }

    @Test
    void normalizedReturnConvertsThroughTheVariablesParameterizedBound() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var objects = TypeDef.parameterized(List.class, Object.class);
        var u = TypeDef.variable("U", objects);
        var values = FieldDef.builder("values", strings).build();
        var classDef = ClassDef.builder("test.VariableReturn").addTypeVariable(u)
            .addField(values)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), u))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((aThis, parameters) -> aThis.field(values).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import java.util.function.Supplier;

            class VariableReturn<U extends List<Object>> implements Supplier<U> {
              List<String> values;

              public U get() {
                return (U) (List) this.values;
              }
            }
            """));
    }

    @Test
    void invocationConvertsAParameterizationContainingAFixedClassVariable() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var t = TypeDef.variable("T");
        var parameterType = TypeDef.parameterized(ClassTypeDef.of(List.class), t);
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(void.class).build();
        var classDef = ClassDef.builder("test.VariableArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), parameterType))
            .addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("values", strings).returns(void.class)
                .build((aThis, parameters) -> aThis.invoke(accept, parameters.getFirst())))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.String;
            import java.util.List;
            import java.util.function.Consumer;

            class VariableArgument<T> implements Consumer<List<T>> {
              public void accept(List<T> value) {
              }

              void call(List<String> values) {
                this.accept((List) values);
              }
            }
            """));
    }

    @Test
    void explicitCastToAnUnboundedClassVariableCompiles() throws IOException {
        assertExplicitCastCompiles(TypeDef.variable("T"));
    }

    @Test
    void explicitCastRespectsTheClassVariablesBound() throws IOException {
        assertExplicitCastCompiles(TypeDef.variable("T", TypeDef.of(Number.class)));
    }

    private void assertExplicitCastCompiles(TypeDef.TypeVariable t) throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class);
        var variables = TypeDef.parameterized(ClassTypeDef.of(List.class), t);
        var classDef = ClassDef.builder("test.VariableCast").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), strings, TypeDef.OBJECT))
            .addMethod(MethodDef.builder("apply").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", Object.class).returns(Object.class)
                .build((aThis, parameters) -> parameters.getFirst().cast(variables).returning()))
            .build();

        String expected = t.bounds().isEmpty()
            ? """
                package test;

                import java.lang.Object;
                import java.lang.String;
                import java.util.List;
                import java.util.function.Function;

                class VariableCast<T> implements Function<List<String>, Object> {
                  public Object apply(List<String> value) {
                    return (List<T>) value;
                  }
                }
                """
            : """
                package test;

                import java.lang.Number;
                import java.lang.Object;
                import java.lang.String;
                import java.util.List;
                import java.util.function.Function;

                class VariableCast<T extends Number> implements Function<List<String>, Object> {
                  public Object apply(List<String> value) {
                    return (List) value;
                  }
                }
                """;
        assertCompiles(assertSource(classDef, expected));
    }

    @Test
    void invocationConvertsTheBoundToAFixedClassVariable() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides()
            .addParameter("value", Object.class).returns(void.class).build();
        var classDef = ClassDef.builder("test.FixedArgument").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t))
            .addMethod(accept)
            .addMethod(MethodDef.builder("call").addParameter("value", CharSequence.class).returns(void.class)
                .build((aThis, parameters) -> aThis.invoke(accept, parameters.getFirst())))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.CharSequence;
            import java.util.function.Consumer;

            class FixedArgument<T extends CharSequence> implements Consumer<T> {
              public void accept(T value) {
              }

              void call(CharSequence value) {
                this.accept((T) value);
              }
            }
            """));
    }

    @Test
    void normalizedReturnConvertsAValueOfAnotherVariable() throws IOException {
        var t = TypeDef.variable("T");
        var v = TypeDef.variable("V");
        var value = FieldDef.builder("value", v).build();
        var classDef = ClassDef.builder("test.VariableValue").addTypeVariable(t).addTypeVariable(v)
            .addField(value)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((aThis, parameters) -> aThis.field(value).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.util.function.Supplier;

            class VariableValue<T, V> implements Supplier<T> {
              V value;

              public T get() {
                return (T) this.value;
              }
            }
            """));
    }

    @Test
    void hierarchySubstitutesTypeArgumentsOnlyOncePerEdge() throws IOException {
        var t = TypeDef.variable("T");
        var parent = ClassDef.builder("test.GenericParent").addModifiers(Modifier.ABSTRACT).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t))
            .build();
        var child = ClassDef.builder("test.GenericChild").addTypeVariable(t)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.parameterized(ClassTypeDef.of(List.class), t)))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(Object.class)
                .build((aThis, parameters) -> ExpressionDef.nullValue().returning()))
            .build();

        // Supplier<T> becomes Supplier<List<T>>, not Supplier<List<List<T>>>.
        assertCompiles(
            assertSource(parent, """
                package test;

                import java.util.function.Supplier;

                abstract class GenericParent<T> implements Supplier<T> {
                }
                """),
            assertSource(child, """
                package test;

                import java.util.List;

                class GenericChild<T> extends GenericParent<List<T>> {
                  public List<T> get() {
                    return null;
                  }
                }
                """)
        );
    }

    @Test
    void rawReturnConversionPreservesAnAnnotatedReturnType() throws IOException {
        var objects = TypeDef.parameterized(List.class, Object.class);
        var result = TypeDef.parameterized(List.class, String.class)
            .annotated(AnnotationDef.builder(NonNull.class).build());
        var values = FieldDef.builder("values", objects).build();
        var classDef = ClassDef.builder("test.AnnotatedReturn")
            .addField(values)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(result)
                .build((aThis, parameters) -> aThis.field(values).returning()))
            .build();

        assertCompiles(assertSource(classDef, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import org.jspecify.annotations.NonNull;

            class AnnotatedReturn {
              List<Object> values;

              public @NonNull List<String> get() {
                return (List) this.values;
              }
            }
            """));
    }

    private static String assertSource(ObjectDef objectDef, String expected) throws IOException {
        String source = writeSource(objectDef);
        assertEquals(expected, source);
        return source;
    }

    private static String writeSource(ObjectDef objectDef) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(objectDef, writer);
            return writer.toString();
        }
    }
}
