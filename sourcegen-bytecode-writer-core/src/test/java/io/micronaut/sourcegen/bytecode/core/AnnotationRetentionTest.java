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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention decides whether an annotation is written runtime-visible, invisible, or not at all, so
 * the answer for a type that cannot be loaded matters as much as for one that can.
 */
class AnnotationRetentionTest {

    private static final ClassLoader LOADER = AnnotationRetentionTest.class.getClassLoader();

    private static RetentionPolicy retentionOf(ClassTypeDef type) {
        return AnnotationTargetUtils.retentionOf(AnnotationDef.builder(type).build(), LOADER);
    }

    @Test
    void readsTheRetentionOfATypeItCanLoad() {
        assertEquals(RetentionPolicy.RUNTIME, retentionOf(ClassTypeDef.of(RuntimeAnnotation.class)));
        assertEquals(RetentionPolicy.CLASS, retentionOf(ClassTypeDef.of(ClassAnnotation.class)));
        assertEquals(RetentionPolicy.SOURCE, retentionOf(ClassTypeDef.of(SourceAnnotation.class)));
        // An annotation that declares no retention has CLASS retention (JLS 9.6.4.2)
        assertEquals(RetentionPolicy.CLASS, retentionOf(ClassTypeDef.of(UndeclaredAnnotation.class)));
    }

    @Test
    void keepsAnnotationsItCannotResolveVisible() {
        // The normal case for a processor writing annotations of the project it is processing:
        // nothing here can read the retention, and hiding them from their readers would be worse
        assertEquals(RetentionPolicy.RUNTIME, retentionOf(ClassTypeDef.of("example.NotOnTheClassPath")));
    }

    @Test
    void assumesClassRetentionForATypeOfTheCompilationItCannotRead() {
        // A type of the current compilation whose element carries no retention it can read
        ClassElement element = ClassElement.of("example.BeingCompiled");
        assertEquals(RetentionPolicy.CLASS,
            retentionOf(new ClassTypeDef.ClassElementType(element, false)));
    }

    @Test
    void readsTheRetentionAGeneratedAnnotationDeclares() {
        // A generated annotation type is not loadable, so its own model is the only source
        ClassDef declaring = ClassDef.builder("example.GeneratedRuntime")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Retention.class))
                .addMember("value", new VariableDef.StaticField(
                    ClassTypeDef.of(RetentionPolicy.class), "RUNTIME", ClassTypeDef.of(RetentionPolicy.class)))
                .build())
            .build();
        assertEquals(RetentionPolicy.RUNTIME,
            retentionOf(new ClassTypeDef.ClassDefType(declaring, false)));
        assertEquals(RetentionPolicy.RUNTIME, AnnotationTargetUtils.declaredRetentionOf(declaring).orElseThrow());

        // ... and one that declares none keeps the default
        ClassDef silent = ClassDef.builder("example.GeneratedSilent").build();
        assertEquals(RetentionPolicy.CLASS, retentionOf(new ClassTypeDef.ClassDefType(silent, false)));
        assertTrue(AnnotationTargetUtils.declaredRetentionOf(silent).isEmpty());
    }

    @Test
    void readsTheRetentionMemberInEveryShapeTheModelUsesForIt() {
        assertEquals(RetentionPolicy.SOURCE, declaredWith(RetentionPolicy.SOURCE));
        assertEquals(RetentionPolicy.CLASS, declaredWith("CLASS"));
        assertEquals(RetentionPolicy.RUNTIME, declaredWith(new VariableDef.StaticField(
            ClassTypeDef.of(RetentionPolicy.class), "RUNTIME", ClassTypeDef.of(RetentionPolicy.class))));

        // A member that names no policy at all leaves the retention undeclared
        ClassDef unreadable = declaring(ExpressionDef.constant("NOT_A_POLICY"));
        assertTrue(AnnotationTargetUtils.declaredRetentionOf(unreadable).isEmpty());
    }

    private static RetentionPolicy declaredWith(Object member) {
        return AnnotationTargetUtils.declaredRetentionOf(declaring(member)).orElseThrow();
    }

    private static ClassDef declaring(Object member) {
        return ClassDef.builder("example.Declaring")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Retention.class))
                .addMember("value", member)
                .build())
            .build();
    }

    @Test
    void readsTheTargetsOfATypeOfTheCompilationItCannotRead() {
        // The same element route as retention, which the target lookup shares
        ClassElement element = ClassElement.of("example.BeingCompiled");
        assertTrue(AnnotationTargetUtils.targetsOf(
            AnnotationDef.builder(new ClassTypeDef.ClassElementType(element, false)).build(), LOADER).isEmpty());
        assertEquals(List.of(), List.copyOf(AnnotationTargetUtils.toElementTypes(null)));
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface RuntimeAnnotation {
    }

    @Retention(RetentionPolicy.CLASS)
    @interface ClassAnnotation {
    }

    @Retention(RetentionPolicy.SOURCE)
    @interface SourceAnnotation {
    }

    @interface UndeclaredAnnotation {
    }
}
