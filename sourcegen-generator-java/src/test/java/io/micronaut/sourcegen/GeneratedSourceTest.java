package io.micronaut.sourcegen;

import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSourceTest {
    @Test
    void writesTheMarkerSuppliedByEveryTypeBuilder() throws IOException {
        for (SourceGenerator generator : List.of(new JavaPoetSourceGenerator(), new GroovyPoetSourceGenerator())) {
            for (ObjectDef definition : List.of(
                ClassDef.builder("example.MarkedClass").build(),
                RecordDef.builder("example.MarkedRecord").build(),
                EnumDef.builder("example.MarkedEnum").addEnumConstant("VALUE").build(),
                InterfaceDef.builder("example.MarkedInterface").build(),
                AnnotationObjectDef.builder("example.MarkedAnnotation").build()
            )) {
                assertEquals(1, markerCount(write(generator, definition)));
                assertEquals(1, definition.getAnnotations().size());
            }
        }
    }

    @Test
    void marksNestedTypes() throws IOException {
        ObjectDef definition = ClassDef.builder("example.Outer")
            .addInnerType(ClassDef.builder("Inner").build())
            .build();
        assertEquals(2, markerCount(write(new JavaPoetSourceGenerator(), definition)));
    }

    @Test
    void preservesAnnotationsWithoutDuplicatingAnExplicitMarker() throws IOException {
        ObjectDef definition = ClassDef.builder("example.Explicit")
            .addAnnotation(Deprecated.class)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of("io.micronaut.sourcegen.annotations.GeneratedSource")).build())
            .build();
        String source = write(new JavaPoetSourceGenerator(), definition);
        assertEquals(1, markerCount(source));
        assertTrue(source.contains("@Deprecated"));
        assertEquals(2, definition.getAnnotations().size());
    }

    private static long markerCount(String source) {
        return source.lines().filter(line -> line.strip().equals("@GeneratedSource")).count();
    }

    private static String write(SourceGenerator generator, ObjectDef definition) throws IOException {
        StringWriter writer = new StringWriter();
        generator.write(definition, writer);
        return writer.toString();
    }
}
