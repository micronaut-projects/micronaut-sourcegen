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
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.Modifier;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hierarchies whose resolution has to stay linear: writing a class of a wide or deeply shared hierarchy must not take
 * time exponential in its depth.
 */
class GenerationPerformanceTest {

    /**
     * A lattice of generated interfaces - each level two interfaces, each extending both of the next level, the last
     * ones {@code Supplier<String>} - and a call of the inherited {@code get()} on the top one. To find the signature
     * a generated method is written with, {@code OverrideResolver.inheritedSignature} followed every generated
     * supertype of every level without remembering the ones it had visited, so it walked each path of the lattice:
     * 2^depth of them for a method that no generated type declares. {@code declaringArguments} and
     * {@code OverloadRules.generatedCandidates} keep a visited set for the same walk. Expected: linear in the number
     * of types.
     *
     * <p>Measured: 18 levels 41 ms, 20 levels 109 ms, 22 levels 370 ms - each two more levels about four times as
     * long, all of it in inheritedSignature -> emittedSignature -> inheritedSignature. 28 levels took minutes.</p>
     */
    @ParameterizedTest(name = "a call through a lattice of {0} levels of generated interfaces is written in time")
    @ValueSource(ints = {22, 28})
    void callThroughALatticeOfGeneratedInterfacesIsLinear(int depth) {
        ClassDef caller = latticeCaller(depth);
        String source = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> render(caller));
        assertTrue(source.contains("value.get()"), source);
    }

    /**
     * Timing of a wide hierarchy: every abstract method of {@code NavigableMap<String, Object>} overridden with its
     * erased signature, each body calling two others on {@code this}. Every written method, every call and every
     * overload check walks the whole hierarchy again, converting the reflected methods of each supertype anew.
     */
    @Test
    void wideHierarchyIsWrittenInReasonableTime() throws Exception {
        var builder = ClassDef.builder("test.WideMap").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(java.util.AbstractMap.class), TypeDef.STRING, TypeDef.OBJECT))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(java.util.NavigableMap.class), TypeDef.STRING, TypeDef.OBJECT));
        var methods = new ArrayList<MethodDef>();
        for (var method : java.util.NavigableMap.class.getMethods()) {
            if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            var m = MethodDef.builder(method.getName()).addModifiers(Modifier.PUBLIC).overrides();
            for (int i = 0; i < method.getParameterCount(); i++) {
                m.addParameter("p" + i, TypeDef.of(method.getParameterTypes()[i]));
            }
            methods.add(m.returns(TypeDef.of(method.getReturnType())).build());
        }
        var zeroArgs = methods.stream().filter(m -> m.getParameters().isEmpty() && !m.getReturnType().isPrimitive()
            && !m.getReturnType().equals(TypeDef.VOID)).toList();
        for (var method : methods) {
            var callee = zeroArgs.get(Math.abs(method.getName().hashCode()) % zeroArgs.size());
            builder.addMethod(MethodDef.builder(method.getName()).addModifiers(Modifier.PUBLIC).overrides()
                .addParameters(method.getParameters()).returns(method.getReturnType())
                .build((self, p) -> {
                    var statements = new ArrayList<io.micronaut.sourcegen.model.StatementDef>();
                    statements.add(self.invoke(callee));
                    statements.add(self.invoke(zeroArgs.get(0)));
                    if (method.getReturnType().equals(TypeDef.VOID)) {
                        return StatementDef.multi(statements);
                    }
                    statements.add(method.getReturnType().isPrimitive()
                        ? ExpressionDef.constant(method.getReturnType().equals(TypeDef.Primitive.BOOLEAN) ? (Object) false : 0)
                            .cast(method.getReturnType()).returning()
                        : ExpressionDef.nullValue().returning());
                    return StatementDef.multi(statements);
                }));
        }
        var def = builder.build();
        long start = System.nanoTime();
        var writer = new StringWriter();
        new JavaPoetSourceGenerator().write(def, writer);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("WIDE HIERARCHY: " + methods.size() + " methods written in " + millis + " ms");
        JavaCompileAssertions.assertCompiles(writer.toString());
    }

    private static ClassDef latticeCaller(int depth) {
        List<ClassTypeDef> next = List.of(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.STRING));
        for (int level = depth; level >= 1; level--) {
            List<ClassTypeDef> current = new ArrayList<>();
            for (String side : List.of("A", "B")) {
                var definition = InterfaceDef.builder("test.Lattice" + level + side).addModifiers(Modifier.PUBLIC);
                next.forEach(definition::addSuperinterface);
                current.add(ClassTypeDef.of(definition.build()));
            }
            next = current;
        }
        var get = MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(Object.class).build();
        return ClassDef.builder("test.LatticeCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", next.getFirst())
                .returns(Object.class).build((self, p) -> p.getFirst().invoke(get).returning()))
            .build();
    }
}
