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

import javax.lang.model.element.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The names inner definitions get from the definition they are added to. */
class InnerTypeNamingTest {

    /**
     * An inner definition named with its binary name, as a writer wrapping a member in a stub of its outer type
     * names it, keeps that name instead of repeating the outer one: `Outer$Member`, not `Outer$Outer$Member`.
     */
    @Test
    void innerDefinitionNamedWithItsBinaryNameKeepsIt() {
        ClassDef member = ClassDef.builder("example.Outer$Member").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        ClassDef outer = ClassDef.builder("example.Outer").addInnerType(member).build();
        assertEquals("example.Outer$Member", outer.getInnerTypes().getFirst().getName());

        ClassDef simple = ClassDef.builder("Member").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        assertEquals("example.Outer$Member", ClassDef.builder("example.Outer").addInnerType(simple).build()
            .getInnerTypes().getFirst().getName());
    }
}
