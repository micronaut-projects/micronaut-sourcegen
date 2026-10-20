package io.micronaut.sourcegen.model;

import io.micronaut.sourcegen.annotations.GeneratedSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSourceBuilderTest {
    @Test
    void allTypeBuildersSupplyTheMarkerBeforeEmission() {
        for (ObjectDef definition : List.of(
            ClassDef.builder("example.GeneratedClass").build(),
            RecordDef.builder("example.GeneratedRecord").build(),
            EnumDef.builder("example.GeneratedEnum").addEnumConstant("VALUE").build(),
            InterfaceDef.builder("example.GeneratedInterface").build(),
            AnnotationObjectDef.builder("example.GeneratedAnnotation").build()
        )) {
            assertEquals(List.of(GeneratedSource.class.getName()), definition.getAnnotations().stream()
                .map(annotation -> annotation.getType().getName()).toList());
        }
    }

    @Test
    void keepsExistingAnnotationsWithoutDuplicatingAnExplicitMarker() {
        ClassDef definition = ClassDef.builder("example.Explicit")
            .addAnnotation(Deprecated.class)
            .addAnnotation(GeneratedSource.class)
            .build();

        assertEquals(List.of(Deprecated.class.getName(), GeneratedSource.class.getName()),
            definition.getAnnotations().stream().map(annotation -> annotation.getType().getName()).toList());
    }

    @Test
    void repeatedBuildsDoNotDuplicateTheMarkerOrChangeEarlierDefinitions() {
        ClassDef.ClassDefBuilder builder = ClassDef.builder("example.Reused");
        ClassDef first = builder.build();
        ClassDef second = builder.addAnnotation(Deprecated.class).build();

        assertEquals(1, first.getAnnotations().size());
        assertEquals(2, second.getAnnotations().size());
        assertEquals(1, second.getAnnotations().stream()
            .filter(annotation -> annotation.getType().getName().equals(GeneratedSource.class.getName())).count());
    }

    @Test
    void nestedTypesCarryTheirOwnMarker() {
        ClassDef outer = ClassDef.builder("example.Outer")
            .addInnerType(ClassDef.builder("Inner").build())
            .build();

        assertEquals(1, outer.getAnnotations().size());
        assertEquals(GeneratedSource.class.getName(), outer.getInnerTypes().get(0)
            .getAnnotations().get(0).getType().getName());
    }

    @Test
    void doesNotMarkMembersOrParameters() {
        assertTrue(FieldDef.builder("field", TypeDef.STRING).build().getAnnotations().isEmpty());
        assertTrue(PropertyDef.builder("property").ofType(TypeDef.STRING).build().getAnnotations().isEmpty());
        assertTrue(ParameterDef.builder("parameter", TypeDef.STRING).build().getAnnotations().isEmpty());
        assertTrue(MethodDef.builder("method").returns(TypeDef.VOID).build().getAnnotations().isEmpty());
    }
}
