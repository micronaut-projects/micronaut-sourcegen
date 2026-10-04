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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertCompiles;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.assertSource;

/** Regression coverage for resolving bounds at generic invocation sites. */
public class CalleeBoundsRegressionTest {

    @Test
    void compiledSubclassBindsInheritedMethodVariable() throws IOException {
        assertCompiles(assertSource(receiver("InheritedJavaCall", ClassTypeDef.of(Child.class)), """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CalleeBoundsRegressionTest;
            import java.lang.Object;
            import java.lang.String;

            class InheritedJavaCall {
              Object call(CalleeBoundsRegressionTest.Child target, Object value) {
                return target.identity((String) value);
              }
            }
            """));
    }

    @Test
    void compiledGenericSubclassBindsInheritedMethodVariable() throws IOException {
        assertCompiles(assertSource(receiver("GenericInheritedJavaCall", TypeDef.parameterized(GenericChild.class, String.class)), """
            package test;

            import io.micronaut.sourcegen.javapoet.write.CalleeBoundsRegressionTest;
            import java.lang.Object;
            import java.lang.String;

            class GenericInheritedJavaCall {
              Object call(CalleeBoundsRegressionTest.GenericChild<String> target, Object value) {
                return target.identity((String) value);
              }
            }
            """));
    }

    @Test
    void longAcyclicBoundChainIsResolved() throws IOException {
        int length = 10;
        var variables = new ArrayList<TypeDef.TypeVariable>();
        TypeDef bound = TypeDef.of(CharSequence.class);
        for (int i = length - 1; i >= 0; i--) {
            var t = TypeDef.variable("T" + i, bound);
            variables.addFirst(t);
            bound = t;
        }
        var builder = MethodDef.builder("identity");
        variables.forEach(builder::addTypeVariable);
        var method = builder.addParameter("value", variables.getFirst()).returns(variables.getFirst())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.LongBoundChain").addMethod(method)
            .addMethod(MethodDef.builder("call").addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(method, p.getFirst()).returning())).build();
        assertCompiles(assertSource(def, """
            package test;

            import java.lang.CharSequence;
            import java.lang.Object;

            class LongBoundChain {
              <T0 extends T1, T1 extends T2, T2 extends T3, T3 extends T4, T4 extends T5, T5 extends T6, T6 extends T7, T7 extends T8, T8 extends T9, T9 extends CharSequence> T0 identity(
                  T0 value) {
                return value;
              }

              Object call(Object value) {
                return this.identity((CharSequence) value);
              }
            }
            """));
    }

    private static ClassDef receiver(String name, ClassTypeDef receiver) {
        var x = TypeDef.variable("X", TypeDef.of(CharSequence.class));
        var u = TypeDef.variable("U", x);
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u).addParameter("value", u).returns(u).build();
        return ClassDef.builder("test." + name)
            .addMethod(MethodDef.builder("call").addParameter("target", receiver).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(method, p.get(1)).returning())).build();
    }

    /** Compiled fixture whose class variable is specialized by its subclasses. */
    public static class Parent<X extends CharSequence> {
        /** Returns the supplied value within the receiver's bound. */
        public <U extends X> U identity(U value) {
            return value;
        }
    }

    /** Compiled receiver with a concrete inherited argument. */
    public static class Child extends Parent<String> {
    }

    /** Compiled receiver that renames its inherited variable. */
    public static class GenericChild<Y extends CharSequence> extends Parent<Y> {
    }
}
