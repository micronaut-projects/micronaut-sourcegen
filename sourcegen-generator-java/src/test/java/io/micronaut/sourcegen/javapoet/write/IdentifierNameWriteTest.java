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
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Function;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.intMethod;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.stringMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Names of locals, parameters, fields and properties that are valid in bytecode and have to be written as Java
 * identifiers: named like a Java keyword or with a {@code $}, or obscuring a type the code refers to (JLS 6.4.2).
 * Every program is compiled and run.
 */
class IdentifierNameWriteTest {

    // ---- Naming: reserved words and dollars ----------------------------------------------------------------

    /**
     * A local named like a Java keyword: its name is only debug information in bytecode.
     */
    @Test
    void localNamedLikeAKeyword() throws Exception {
        var def = stringMethod("test.KeywordLocal", ExpressionDef.constant("x").newLocal("default", VariableDef::returning));
        assertEquals("x", run(def));
    }

    /**
     * A parameter named like a Java keyword - {@code default} is a valid Kotlin parameter name. JavaPoet throws
     * "not a valid name: default".
     */
    @Test
    void parameterNamedLikeAKeyword() throws Exception {
        var def = ClassDef.builder("test.KeywordParameter").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("default", String.class)
                .returns(String.class).build((self, p) -> p.getFirst().returning()))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("x", cls.getMethod("call", String.class).invoke(cls.getConstructor().newInstance(), "x"));
        }
    }

    /**
     * A lambda parameter named like a Java keyword: {@code (new) -> new}.
     */
    @Test
    void lambdaParameterNamedLikeAKeyword() throws Exception {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = stringMethod("test.KeywordLambdaParameter", function.getLambda()
            .implement(List.of("new"), (ls, lp) -> lp.getFirst().returning())
            .newLocal("f", f -> f.invoke("apply", TypeDef.OBJECT, ExpressionDef.constant("x")).cast(TypeDef.STRING).returning()));
        assertEquals("x", run(def));
    }

    /**
     * A lambda parameter named with a {@code $} is passed to JavaPoet as a format string: "index 1 for '$v' not in
     * range".
     */
    @Test
    void lambdaParameterNameWithDollar() throws Exception {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = stringMethod("test.DollarLambdaParameter", function.getLambda()
            .implement(List.of("$value"), (ls, lp) -> lp.getFirst().returning())
            .newLocal("f", f -> f.invoke("apply", TypeDef.OBJECT, ExpressionDef.constant("x")).cast(TypeDef.STRING).returning()));
        assertEquals("x", run(def));
    }

    /**
     * The accessors of a property named with a {@code $} are written from a format string built of its name.
     */
    @Test
    void propertyNameWithDollar() throws Exception {
        var def = ClassDef.builder("test.DollarProperty").addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("$value").ofType(String.class).addModifiers(Modifier.PUBLIC).build())
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            var instance = cls.getConstructor().newInstance();
            cls.getMethod("set$value", String.class).invoke(instance, "x");
            assertEquals("x", cls.getMethod("get$value").invoke(instance));
        }
    }

    // ---- Naming: variables that obscure types --------------------------------------------------------------

    /**
     * A local named {@code Math} obscures the type in {@code Math.max(...)} (JLS 6.4.2), which is written with the
     * simple name of the imported type: "int cannot be dereferenced".
     */
    @Test
    void localNamedLikeATypeItsMethodCalls() throws Exception {
        var math = ClassTypeDef.of(Math.class);
        var def = intMethod("test.ObscuringLocal", ExpressionDef.constant(5).newLocal("Math",
            local -> math.invokeStatic("max", TypeDef.Primitive.INT, local, ExpressionDef.constant(7)).returning()));
        assertEquals(7, run(def));
    }

    /**
     * A field named like a type the class calls obscures the type in every method.
     */
    @Test
    void fieldNamedLikeATypeItsMethodCalls() throws Exception {
        var field = FieldDef.builder("Math", String.class).addModifiers(Modifier.PRIVATE).build();
        var def = ClassDef.builder("test.ObscuringField").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(int.class)
                .build((self, p) -> ClassTypeDef.of(Math.class)
                    .invokeStatic("max", TypeDef.Primitive.INT, ExpressionDef.constant(1), ExpressionDef.constant(2)).returning()))
            .build();
        assertEquals(2, run(def));
    }
}
