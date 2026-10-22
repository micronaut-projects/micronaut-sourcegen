package io.micronaut.sourcegen.model;

import io.micronaut.sourcegen.annotations.GeneratedSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSourceMarkerTest {

    @Test
    void buildersDoNotAddTheMarker() {
        assertTrue(ClassDef.builder("example.Plain").build().getAnnotations().isEmpty());
    }

    @Test
    void marksEveryTypeKindWithoutChangingTheOriginal() {
        for (ObjectDef definition : List.of(
            ClassDef.builder("example.GeneratedClass").build(),
            RecordDef.builder("example.GeneratedRecord").build(),
            EnumDef.builder("example.GeneratedEnum").addEnumConstant("VALUE").build(),
            InterfaceDef.builder("example.GeneratedInterface").build(),
            AnnotationObjectDef.builder("example.GeneratedAnnotation").build()
        )) {
            ObjectDef marked = definition.markAsGenerated();
            assertEquals(definition.getClass(), marked.getClass());
            assertEquals(definition.getName(), marked.getName());
            assertEquals(List.of(GeneratedSource.class.getName()), names(marked));
            assertTrue(definition.getAnnotations().isEmpty());
        }
    }

    @Test
    void keepsExistingAnnotationsWithoutDuplicatingAnExplicitMarker() {
        ClassDef definition = ClassDef.builder("example.Explicit")
            .addAnnotation(Deprecated.class)
            .addAnnotation(GeneratedSource.class)
            .build();

        assertEquals(List.of(Deprecated.class.getName(), GeneratedSource.class.getName()), names(definition.markAsGenerated()));
        assertEquals(names(definition.markAsGenerated()), names(definition.markAsGenerated().markAsGenerated()));
    }

    @Test
    void marksNestedTypes() {
        ObjectDef outer = ClassDef.builder("example.Outer")
            .addInnerType(ClassDef.builder("Inner").build())
            .build()
            .markAsGenerated();

        assertEquals(List.of(GeneratedSource.class.getName()), names(outer.getInnerTypes().get(0)));
    }

    private static List<String> names(ObjectDef definition) {
        return definition.getAnnotations().stream().map(annotation -> annotation.getType().getName()).toList();
    }
}
