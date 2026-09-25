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

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertSource;

/** Regression coverage for argument conversions in generated Java source. */
class BackendConsistencyRegressionTest {

    @Test
    void typedArrayArgumentConvertsToCalleeBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Integer.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", t.array()).returns(t.array())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.TypedArrayBound").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.of(Number.class).array()).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.lang.Object;

            class TypedArrayBound {
              <T extends Integer> T[] identity(T[] value) {
                return value;
              }

              Object call(Number[] value) {
                return this.identity((Integer[]) value);
              }
            }
            """));
    }

    @Test
    void multidimensionalArrayArgumentConvertsToCalleeBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Integer.class));
        var identity = MethodDef.builder("identity").addTypeVariable(t).addParameter("value", TypeDef.array(t, 2)).returns(TypeDef.array(t, 2))
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.MatrixBound").addMethod(identity)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.array(TypeDef.of(Number.class), 2)).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.lang.Object;

            class MatrixBound {
              <T extends Integer> T[][] identity(T[][] value) {
                return value;
              }

              Object call(Number[][] value) {
                return this.identity((Integer[][]) value);
              }
            }
            """));
    }

    @Test
    void primitiveReferenceArgumentBoxesBeforeFixedVariableCast() throws IOException {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        assertCompiles(assertSource(reference("PrimitiveReference", t), """
            package test;

            import java.lang.Integer;
            import java.lang.Number;
            import java.util.function.Consumer;
            import java.util.function.IntConsumer;

            class PrimitiveReference<T extends Number> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              IntConsumer delegate() {
                return (arg) -> this.accept((T) (Integer) arg);
              }
            }
            """));
    }

    @Test
    void primitiveReferenceArgumentConvertsThroughGenericBound() throws IOException {
        var t = TypeDef.variable("T", TypeDef.parameterized(Comparable.class, String.class));
        assertCompiles(assertSource(reference("BoundedPrimitiveReference", t), """
            package test;

            import java.lang.Comparable;
            import java.lang.Integer;
            import java.lang.String;
            import java.util.function.Consumer;
            import java.util.function.IntConsumer;

            class BoundedPrimitiveReference<T extends Comparable<String>> implements Consumer<T> {
              public void accept(T value) {
                return;
              }

              IntConsumer delegate() {
                return (arg) -> this.accept((T) (Comparable) (Integer) arg);
              }
            }
            """));
    }

    @Test
    void annotatedParameterUsesRawArgumentConversion() throws IOException {
        var strings = TypeDef.parameterized(List.class, String.class).annotated(AnnotationDef.builder(org.jspecify.annotations.NonNull.class).build());
        var consume = MethodDef.builder("consume").addParameter("value", strings).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var def = ClassDef.builder("test.AnnotatedArgument").addMethod(consume)
            .addMethod(MethodDef.builder("call").addParameter("value", TypeDef.parameterized(List.class, Object.class)).returns(void.class)
                .build((self, p) -> self.invoke(consume, p.getFirst()))).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.Object;
            import java.lang.String;
            import java.util.List;
            import org.jspecify.annotations.NonNull;

            class AnnotatedArgument {
              void consume(@NonNull List<String> value) {
                return;
              }

              void call(List<Object> value) {
                this.consume((List) value);
              }
            }
            """));
    }

    private static ClassDef reference(String name, TypeDef.TypeVariable t) {
        var accept = MethodDef.builder("accept").addModifiers(Modifier.PUBLIC).overrides().addParameter("value", Object.class).returns(void.class)
            .build((self, p) -> new StatementDef.Return(null));
        var functional = ClassTypeDef.of(java.util.function.IntConsumer.class);
        return ClassDef.builder("test." + name).addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer.class), t)).addMethod(accept)
            .addMethod(MethodDef.builder("delegate").returns(functional)
                .build((self, p) -> functional.methodReference(self, accept).returning())).build();
    }
}
