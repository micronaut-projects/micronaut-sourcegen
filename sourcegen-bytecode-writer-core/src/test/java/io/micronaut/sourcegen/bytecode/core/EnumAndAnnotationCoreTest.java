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

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumAndAnnotationCoreTest {

    @Test
    void lowersAnEnumToTheClassAnEnumCompilesTo() {
        EnumDef enumDef = EnumDef.builder("example.Colour")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("RED")
            .addEnumConstant("GREEN")
            .build();

        ClassDef classDef = EnumGenUtils.toClassDef(enumDef);

        assertEquals("example.Colour", classDef.getName());
        assertTrue(classDef.getModifiers().contains(Modifier.FINAL));
        assertEquals(Enum.class.getName(), classDef.getSuperclass().getName());
        assertTrue(EnumGenUtils.isEnum(classDef));

        List<String> fields = classDef.getFields().stream().map(FieldDef::getName).toList();
        assertTrue(fields.containsAll(List.of("RED", "GREEN", "$VALUES")), fields.toString());
        List<String> methods = classDef.getMethods().stream().map(MethodDef::getName).toList();
        assertTrue(methods.containsAll(List.of("values", "valueOf", "$values")), methods.toString());

        // The constants are the fields the writer has to mark ACC_ENUM
        FieldDef red = classDef.getFields().stream()
            .filter(field -> field.getName().equals("RED")).findFirst().orElseThrow();
        assertTrue(EnumGenUtils.isEnumField(classDef, red));
        FieldDef values = classDef.getFields().stream()
            .filter(field -> field.getName().equals("$VALUES")).findFirst().orElseThrow();
        assertFalse(EnumGenUtils.isEnumField(classDef, values));
    }

    @Test
    void routesADeclaredEnumConstructorThroughASyntheticMethod() {
        EnumDef enumDef = EnumDef.builder("example.Planet")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("EARTH", ExpressionDef.constant(1))
            .addField(FieldDef.builder("order", TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addConstructor(List.of(ParameterDef.of("order", TypeDef.Primitive.INT)), Modifier.PRIVATE)
            .build();

        ClassDef classDef = EnumGenUtils.toClassDef(enumDef);

        // The declared body moves to $constructor0 so the synthesized constructor can call super first
        assertTrue(classDef.getMethods().stream().anyMatch(method -> method.getName().equals("$constructor0")));
        assertTrue(classDef.getMethods().stream().anyMatch(MethodDef::isConstructor));
    }

    @Test
    void doesNotMistakeAnOrdinaryClassForAnEnum() {
        ClassDef plain = ClassDef.builder("example.Plain").build();
        assertFalse(EnumGenUtils.isEnum(plain));
        assertFalse(EnumGenUtils.isEnumField(plain,
            FieldDef.builder("value", TypeDef.STRING).build()));
    }

    @Test
    void readsAnnotationTargetsFromTheModelAndFromALoadedClass() {
        // Declared on the model itself
        AnnotationDef target = AnnotationDef.builder(ClassTypeDef.of(Target.class))
            .addMember("value", new VariableDef.StaticField(
                ClassTypeDef.of(ElementType.class), "METHOD", ClassTypeDef.of(ElementType.class)))
            .build();
        ClassDef annotation = ClassDef.builder("example.Marker").addAnnotation(target).build();
        assertEquals(Set.of(ElementType.METHOD),
            AnnotationTargetUtils.declaredTargetsOf(annotation).orElseThrow());

        // Resolved by loading the annotation type
        AnnotationDef override = AnnotationDef.builder(ClassTypeDef.of(Override.class)).build();
        assertEquals(Set.of(ElementType.METHOD),
            AnnotationTargetUtils.targetsOf(override, getClass().getClassLoader()).orElseThrow());

        // An annotation with no target declaration, and one that cannot be resolved at all
        AnnotationDef unknown = AnnotationDef.builder(ClassTypeDef.of("example.NotOnTheClassPath")).build();
        assertTrue(AnnotationTargetUtils.targetsOf(unknown, getClass().getClassLoader()).isEmpty());
    }

    @Test
    void convertsEveryShapeOfTargetMemberValue() {
        assertEquals(Set.of(), AnnotationTargetUtils.toElementTypes(null));
        assertEquals(Set.of(ElementType.FIELD), AnnotationTargetUtils.toElementTypes(ElementType.FIELD));
        assertEquals(Set.of(ElementType.FIELD, ElementType.METHOD),
            AnnotationTargetUtils.toElementTypes(List.of(ElementType.FIELD, ElementType.METHOD)));
        assertEquals(Set.of(ElementType.TYPE),
            AnnotationTargetUtils.toElementTypes(new Object[] {ElementType.TYPE}));
        assertEquals(Set.of(ElementType.PARAMETER),
            AnnotationTargetUtils.toElementTypes("PARAMETER"));
        assertEquals(Set.of(ElementType.TYPE_USE), AnnotationTargetUtils.toElementTypes(
            new VariableDef.StaticField(ClassTypeDef.of(ElementType.class), "TYPE_USE",
                ClassTypeDef.of(ElementType.class))));
        // An unrecognised name contributes nothing rather than failing the whole write
        assertEquals(Set.of(), AnnotationTargetUtils.toElementTypes("NOT_AN_ELEMENT_TYPE"));
    }
}
