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
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Arrays, instances and class literals of generic types, which the bytecode writer creates of the erasure and Java
 * cannot write as they are: generic array creation, arrays of another parameterization, wildcard instantiation.
 * Every program is compiled and run.
 */
public class GenericInstantiationWriteTest {

    // `new T[2]` is a generic array creation; the bytecode creates an array of the erasure.
    @Test
    void arrayOfSizeOfTypeVariable() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.VariableArrayOfSize").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).returns(Object.class)
                .build((self, p) -> TypeDef.array(t).instantiate(2).returning())).build();
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // As above for `new T[]{p0}`.
    @Test
    void initializedArrayOfTypeVariable() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.VariableArrayInitialized").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).addParameter("p0", t).returns(Object.class)
                .build((self, p) -> TypeDef.array(t).instantiate(p.get(0)).returning())).build();
        assertArrayEquals(new Object[]{"a"}, (Object[]) run(def, "a"));
    }

    // An array of one parameterization passed where an array of another is declared: a `List<Object>[]` for
    // `List<String>[]`, which the verifier takes as `List[]`.
    @Test
    void arrayOfMismatchedParameterizationPassed() throws Exception {
        var strings = TypeDef.parameterized(List.class, String.class).array();
        var take = MethodDef.builder("take").addModifiers(Modifier.PUBLIC).addParameter("values", strings).returns(Object.class)
            .build((self, p) -> p.get(0).returning());
        var def = ClassDef.builder("test.GenericArrayArgument").addModifiers(Modifier.PUBLIC).addMethod(take)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("p0", TypeDef.parameterized(List.class, Object.class).array()).returns(Object.class)
                .build((self, p) -> self.invoke(take, p.get(0)).returning())).build();
        var value = new List<?>[]{List.of()};
        assertEquals(value, run(def, (Object) value));
    }

    // As above for a return.
    @Test
    void arrayOfMismatchedParameterizationReturned() throws Exception {
        var def = single("GenericArrayReturn", TypeDef.parameterized(List.class, String.class).array(),
            List.of(TypeDef.parameterized(List.class, Object.class).array()), (self, p) -> p.get(0).returning());
        var value = new List<?>[]{List.of()};
        assertEquals(value, run(def, (Object) value));
    }

    // An array of a member of a parameterized class: `new Outer<String>.Inner[2]` is a generic array creation.
    @Test
    void arrayOfMemberOfParameterizedClass() throws Exception {
        var member = TypeHierarchy.memberType(TypeDef.parameterized(ClassTypeDef.of(Outer.class), TypeDef.STRING), ClassTypeDef.of(Outer.Inner.class));
        var def = single("MemberArray", Object.class, List.of(), (self, p) -> TypeDef.array(member).instantiate(2).returning());
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // An array of a method variable is created as `new Object[2]`, which is no `T[]` to return.
    @Test
    void arrayOfMethodVariableReturnedAsVariableArray() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.VariableArrayReturned").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).returns(TypeDef.array(t))
                .build((self, p) -> TypeDef.array(t).instantiate(2).returning())).build();
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // An array of a class variable: `new T[2]` is a generic array creation.
    @Test
    void arrayOfClassVariable() throws Exception {
        var t = TypeDef.variable("T");
        var def = ClassDef.builder("test.ClassVariableArray").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> TypeDef.array(t).instantiate(2).returning())).build();
        assertEquals(2, ((Object[]) run(def)).length);
    }

    // `new ArrayList<?>()`: a wildcard argument cannot be instantiated, the bytecode creates the erasure.
    @Test
    void newInstanceOfWildcardParameterizedType() throws Exception {
        var def = single("WildcardInstance", Object.class, List.of(),
            (self, p) -> TypeDef.parameterized(ClassTypeDef.of(ArrayList.class), TypeDef.wildcard()).instantiate().returning());
        assertEquals(new ArrayList<>(), run(def));
    }

    // A class literal of a variable: the bytecode pushes the erasure, the source generator throws.
    @Test
    void classConstantOfTypeVariable() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Number.class));
        var def = ClassDef.builder("test.VariableClassLiteral").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addTypeVariable(t).returns(Object.class)
                .build((self, p) -> ExpressionDef.constant(t).returning())).build();
        assertEquals(Number.class, run(def));
    }

    /**
     * A generic class with an inner class.
     *
     * @param <T> The type
     * @since 2.3
     */
    public static class Outer<T> {
        /** An inner class. */
        public class Inner {
        }
    }
}
