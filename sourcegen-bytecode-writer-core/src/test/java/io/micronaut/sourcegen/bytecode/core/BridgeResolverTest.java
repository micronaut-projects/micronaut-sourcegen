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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeResolverTest {

    @Test
    void resolvesNoBridgesForMethodsThatCannotBeOverridden() {
        MethodDef constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).build();
        MethodDef staticMethod = MethodDef.builder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING).build();
        MethodDef privateMethod = MethodDef.builder("hidden")
            .addModifiers(Modifier.PRIVATE).returns(TypeDef.STRING).build();
        ClassDef classDef = ClassDef.builder("example.Simple")
            .addMethod(constructor).addMethod(staticMethod).addMethod(privateMethod)
            .build();

        assertTrue(BridgeResolver.resolve(classDef, constructor).isEmpty());
        assertTrue(BridgeResolver.resolve(classDef, staticMethod).isEmpty());
        assertTrue(BridgeResolver.resolve(classDef, privateMethod).isEmpty());
        // Without a declaring definition there is no hierarchy to search
        assertTrue(BridgeResolver.resolve(null, staticMethod).isEmpty());
    }

    @Test
    void resolvesABridgeForAnErasedParameter() {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        InterfaceDef consumer = InterfaceDef.builder("example.Consumer")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("accept")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", variable)
                .build())
            .build();
        MethodDef implementation = MethodDef.builder("accept")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .build();
        ClassDef classDef = ClassDef.builder("example.StringConsumer")
            .addSuperinterface(new ClassTypeDef.Parameterized(consumer.asTypeDef(), List.of(TypeDef.STRING)))
            .addMethod(implementation)
            .build();

        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(classDef, implementation);

        assertEquals(1, bridges.size());
        assertEquals(List.of(TypeDef.OBJECT), bridges.get(0).parameterTypes());
        assertEquals(TypeDef.VOID, bridges.get(0).returnType());
    }

    @Test
    void resolvesABridgeThroughAClassSuperTypeAndAJavaInterface() {
        // A supertype from the model
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        ClassDef parent = ClassDef.builder("example.Parent")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable)
                .build())
            .build();
        MethodDef implementation = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build();
        ClassDef child = ClassDef.builder("example.Child")
            .superclass(new ClassTypeDef.Parameterized(parent.asTypeDef(), List.of(TypeDef.STRING)))
            .addMethod(implementation)
            .build();
        List<BridgeResolver.BridgeMethod> inherited = BridgeResolver.resolve(child, implementation);
        assertEquals(1, inherited.size());
        assertTrue(inherited.get(0).parameterTypes().isEmpty());
        assertEquals(TypeDef.OBJECT, inherited.get(0).returnType());

        // A supertype loaded from the class path
        MethodDef apply = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
        ClassDef function = ClassDef.builder("example.StringFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();

        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(function, apply);

        assertEquals(1, bridges.size());
        assertEquals(List.of(TypeDef.OBJECT), bridges.get(0).parameterTypes());
        assertEquals(TypeDef.OBJECT, bridges.get(0).returnType());
    }

    @Test
    void resolvesNoBridgeWhenTheErasureAlreadyMatches() {
        MethodDef apply = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .build();
        ClassDef function = ClassDef.builder("example.ObjectFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, Object.class, Object.class))
            .addMethod(apply)
            .build();

        assertTrue(BridgeResolver.resolve(function, apply).isEmpty());
    }
}
