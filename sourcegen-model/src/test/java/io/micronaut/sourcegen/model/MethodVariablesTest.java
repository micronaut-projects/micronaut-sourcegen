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
package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The variables a generic method of an inherited type declares, which shadow those of the type.
 */
class MethodVariablesTest {

    @Test
    void aMethodVariableIsToldApartFromTheTypeVariableOfItsName() {
        // interface Api<T> { <T extends Number> T convert(T value); }
        InterfaceDef api = InterfaceDef.builder("example.Api").addTypeVariable(TypeDef.variable("T")).build();
        TypeHierarchy.InheritedType inherited = TypeHierarchy.declaring(api);
        TypeDef.TypeVariable shadowing = TypeDef.variable("T", TypeDef.of(Number.class));

        TypeHierarchy.MethodVariables scope = inherited.shadowedBy(List.of(shadowing));
        TypeDef substituted = scope.substitute(TypeDef.variable("T"));

        TypeDef.TypeVariable renamed = (TypeDef.TypeVariable) substituted;
        assertEquals(scope.nameOf("T"), renamed.name());
        assertNotEquals("T", renamed.name());
        assertTrue(scope.isMethodVariable(renamed.name()));
        assertFalse(scope.isMethodVariable("T"));
        // It keeps the bound it is declared with, so that it erases as the method declares it
        assertEquals(List.of(TypeDef.of(Number.class)), renamed.bounds());
        assertEquals(TypeDef.of(Number.class), inherited.erase(substituted));
        assertEquals(substituted, inherited.substitute(TypeDef.variable("T"), List.of(shadowing)));
        assertThrows(IllegalArgumentException.class, () -> scope.nameOf("V"));
    }
}
