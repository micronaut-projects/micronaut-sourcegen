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
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModifierUtilsTest {

    @Test
    void mapsEveryMemberModifierToItsClassFileFlag() {
        assertEquals(0, ModifierUtils.memberFlags(Set.of()));
        assertEquals(ModifierUtils.ACC_PUBLIC, ModifierUtils.memberFlags(Set.of(Modifier.PUBLIC)));
        assertEquals(ModifierUtils.ACC_PRIVATE, ModifierUtils.memberFlags(Set.of(Modifier.PRIVATE)));
        assertEquals(ModifierUtils.ACC_PROTECTED, ModifierUtils.memberFlags(Set.of(Modifier.PROTECTED)));
        assertEquals(ModifierUtils.ACC_FINAL, ModifierUtils.memberFlags(Set.of(Modifier.FINAL)));
        assertEquals(ModifierUtils.ACC_ABSTRACT, ModifierUtils.memberFlags(Set.of(Modifier.ABSTRACT)));
        assertEquals(ModifierUtils.ACC_STATIC, ModifierUtils.memberFlags(Set.of(Modifier.STATIC)));
        assertEquals(ModifierUtils.ACC_SYNCHRONIZED, ModifierUtils.memberFlags(Set.of(Modifier.SYNCHRONIZED)));
        assertEquals(ModifierUtils.ACC_NATIVE, ModifierUtils.memberFlags(Set.of(Modifier.NATIVE)));
        assertEquals(ModifierUtils.ACC_STRICT, ModifierUtils.memberFlags(Set.of(Modifier.STRICTFP)));
        assertEquals(ModifierUtils.ACC_TRANSIENT, ModifierUtils.memberFlags(Set.of(Modifier.TRANSIENT)));
        assertEquals(ModifierUtils.ACC_VOLATILE, ModifierUtils.memberFlags(Set.of(Modifier.VOLATILE)));
        assertEquals(ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_FINAL,
            ModifierUtils.memberFlags(Set.of(Modifier.PUBLIC, Modifier.FINAL)));
    }

    @Test
    void marksEachKindOfDefinitionWithItsOwnFlags() {
        ClassDef classDef = ClassDef.builder("example.Simple").addModifiers(Modifier.PUBLIC).build();
        assertEquals(ModifierUtils.ACC_PUBLIC, ModifierUtils.objectFlags(classDef));

        InterfaceDef interfaceDef = InterfaceDef.builder("example.Contract").addModifiers(Modifier.PUBLIC).build();
        assertEquals(ModifierUtils.ACC_INTERFACE | ModifierUtils.ACC_ABSTRACT | ModifierUtils.ACC_PUBLIC,
            ModifierUtils.objectFlags(interfaceDef));

        // A record is implicitly final and carries no record flag of its own; the Record attribute
        // is what marks it
        RecordDef recordDef = RecordDef.builder("example.Point")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("x").ofType(TypeDef.Primitive.INT).build())
            .build();
        assertEquals(ModifierUtils.ACC_FINAL | ModifierUtils.ACC_PUBLIC, ModifierUtils.objectFlags(recordDef));

        EnumDef enumDef = EnumDef.builder("example.Colour")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("RED")
            .build();
        assertEquals(ModifierUtils.ACC_ENUM | ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_FINAL,
            ModifierUtils.objectFlags(enumDef));
    }

    @Test
    void keepsMemberAccessOutOfTheClassFileAccessField() {
        // A class file cannot be private, protected or static; those belong to the InnerClasses entry
        assertEquals(ModifierUtils.ACC_PUBLIC,
            ModifierUtils.classFlags(Set.of(Modifier.PROTECTED), null));
        assertEquals(0, ModifierUtils.classFlags(Set.of(Modifier.PRIVATE), null));
        assertEquals(ModifierUtils.ACC_FINAL,
            ModifierUtils.classFlags(Set.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL), null));

        // A member of an interface is implicitly public (JLS 9.5)
        ClassTypeDef contract = InterfaceDef.builder("example.Contract").build().asTypeDef();
        assertEquals(ModifierUtils.ACC_PUBLIC, ModifierUtils.classFlags(Set.of(), contract));
        ClassTypeDef holder = ClassDef.builder("example.Holder").build().asTypeDef();
        assertEquals(0, ModifierUtils.classFlags(Set.of(), holder));
    }

    @Test
    void makesEveryInnerClassEntryStaticAndPublicInsideAnInterface() {
        ClassDef member = ClassDef.builder("example.Outer$Member").build();
        assertEquals(ModifierUtils.ACC_STATIC, ModifierUtils.innerClassFlags(member, false));
        assertEquals(ModifierUtils.ACC_STATIC | ModifierUtils.ACC_PUBLIC,
            ModifierUtils.innerClassFlags(member, true));

        ClassDef privateMember = ClassDef.builder("example.Outer$Hidden").addModifiers(Modifier.PRIVATE).build();
        assertEquals(ModifierUtils.ACC_STATIC | ModifierUtils.ACC_PRIVATE,
            ModifierUtils.innerClassFlags(privateMember, true));
    }
}
