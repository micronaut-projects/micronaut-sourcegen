/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodDefBridgeTest {

    @Test
    void covariantReturnBridgeReusesTheMethodParameters() {
        MethodDef method = MethodDef.builder("self")
            .addParameter("value", TypeDef.of(String.class))
            .returns(ClassTypeDef.of("example.Example"))
            .addCovariantReturnBridge(TypeDef.OBJECT)
            .build();

        assertEquals(
            List.of(new BridgeDef(TypeDef.OBJECT, List.of(TypeDef.of(String.class)))),
            method.getBridges()
        );
    }

    @Test
    void bridgeMustHaveTheSameParameterCount() {
        MethodDef.MethodDefBuilder builder = MethodDef.builder("accept")
            .addParameter("value", TypeDef.of(String.class))
            .returns(TypeDef.VOID)
            .addBridge(TypeDef.VOID, List.of(TypeDef.OBJECT, TypeDef.OBJECT));

        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("has 2 parameters but the method has 1"), e.getMessage());
    }

    @Test
    void constructorsCannotHaveBridges() {
        MethodDef.MethodDefBuilder builder = MethodDef.constructor()
            .addCovariantReturnBridge(TypeDef.OBJECT);

        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("A constructor cannot have bridges", e.getMessage());
    }

    @Test
    void staticMethodsCannotHaveBridges() {
        MethodDef.MethodDefBuilder builder = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassTypeDef.of("example.Example"))
            .addCovariantReturnBridge(TypeDef.OBJECT);

        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("The static method: value cannot have bridges", e.getMessage());
    }

    @Test
    void bridgeOfAnOverriddenMethodTakesItsSignature() {
        MethodDef overridden = MethodDef.builder("apply")
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .build();

        MethodDef method = MethodDef.builder("apply")
            .addParameter("value", TypeDef.of(String.class))
            .returns(TypeDef.of(Integer.class))
            .addBridge(overridden)
            .build();

        assertEquals(
            List.of(new BridgeDef(TypeDef.OBJECT, List.of(TypeDef.OBJECT))),
            method.getBridges()
        );
    }

    @Test
    void copyingAMethodDoesNotCopyItsBridges() {
        MethodDef method = MethodDef.builder("self")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of("example.Example"))
            .addCovariantReturnBridge(TypeDef.OBJECT)
            .build();
        assertEquals(1, method.getBridges().size());

        // Only the signature is copied: a method overriding this one needs a bridge for this method's own
        // erasure too, so carrying only these over would produce an incomplete set
        assertEquals(List.of(), MethodDef.builder(method).build().getBridges());
        assertEquals(List.of(), MethodDef.override(method).build().getBridges());
    }
}
