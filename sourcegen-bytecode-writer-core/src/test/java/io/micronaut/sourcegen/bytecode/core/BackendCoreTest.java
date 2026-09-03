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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCoreTest {

    @Test
    void writesDescriptorsAndGenericSignaturesWithoutABytecodeLibrary() {
        TypeDef stringList = TypeDef.parameterized(List.class, String.class);
        MethodDef method = MethodDef.builder("values")
            .addParameter("values", stringList)
            .returns(stringList)
            .build();

        assertEquals("(Ljava/util/List;)Ljava/util/List;", TypeUtils.getMethodDescriptor(null, method));
        assertEquals("(Ljava/util/List<Ljava/lang/String;>;)Ljava/util/List<Ljava/lang/String;>;",
            SignatureUtils.getMethodSignature(null, method));
        assertEquals("Ljava/util/List<Ljava/lang/String;>;",
            SignatureUtils.getFieldSignature(null,
                io.micronaut.sourcegen.model.FieldDef.builder("values", stringList).build()));
    }

    @Test
    void resolvesAGenericReturnBridgeFromModelTypes() {
        TypeDef.TypeVariable typeVariable = TypeDef.variable("T");
        InterfaceDef parent = InterfaceDef.builder("example.Parent")
            .addTypeVariable(typeVariable)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(typeVariable)
                .build())
            .build();
        MethodDef implementation = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build();
        ClassDef child = ClassDef.builder("example.Child")
            .addSuperinterface(new ClassTypeDef.Parameterized(parent.asTypeDef(), List.of(TypeDef.STRING)))
            .addMethod(implementation)
            .build();

        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(child, implementation);

        assertEquals(1, bridges.size());
        assertEquals(TypeDef.OBJECT, bridges.get(0).returnType());
        assertTrue(bridges.get(0).parameterTypes().isEmpty());
    }
}
