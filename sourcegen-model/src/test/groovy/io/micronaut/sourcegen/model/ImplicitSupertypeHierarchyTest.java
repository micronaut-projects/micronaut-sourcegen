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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the supertype walk.
 */
class ImplicitSupertypeHierarchyTest {

    /**
     * A compiled enum is Comparable (and Serializable) through {@code Enum}, which the walk reaches but does not
     * enter, {@code Enum} being a terminal type: {@code inherits} answers false and {@code asSupertype} null, so a
     * `C extends Comparable<C>` bound is taken as not satisfied by an enum value.
     */
    @Test
    void compiledEnumIsComparable() {
        ClassTypeDef unit = ClassTypeDef.of(TimeUnit.class);
        assertAll(
            () -> assertTrue(TypeHierarchy.inherits(unit, "java.lang.Enum", null)),
            () -> assertTrue(TypeHierarchy.inherits(unit, "java.lang.Comparable", null)),
            () -> assertNotNull(TypeHierarchy.asSupertype(unit, "java.lang.Comparable", null))
        );
    }

    /**
     * A generated enum extends {@code Enum<E>} and a generated record extends {@code Record}, as the compiled ones
     * do; the walk knows neither, because the model lists only the declared superinterfaces. See
     * {@code OverridesTest.generatedEnumIsAnEnum} for an override that is left erased because of it.
     */
    @Test
    void generatedEnumAndRecordInheritTheirImplicitSuperclass() {
        EnumDef kind = EnumDef.builder("test.Kind").addEnumConstant("A").build();
        RecordDef rec = RecordDef.builder("test.Rec").build();
        assertAll(
            () -> assertTrue(TypeHierarchy.inherits(rec.asTypeDef(), "java.lang.Record", null)),
            () -> assertTrue(TypeHierarchy.inherits(kind.asTypeDef(), "java.lang.Enum", null)),
            () -> assertTrue(TypeHierarchy.inherits(kind.asTypeDef(), "java.lang.Comparable", null)),
            () -> assertNotNull(TypeHierarchy.asSupertype(kind.asTypeDef(), "java.lang.Enum", null))
        );
    }
}
