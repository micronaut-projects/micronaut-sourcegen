package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TypeDefTest extends AbstractWriteTest {

    @Test
    public void classElement() throws IOException {
        ClassElement element = ClassElement.of(String.class);
        String result = writeClassWithField(
            FieldDef.builder("v").ofType(ClassTypeDef.of(element)).build()
        );

        assertEquals("String v;", result);
    }

    @Test
    public void parametrizedClassElement() throws IOException {
        Map<String, ClassElement> vars = new LinkedHashMap<>();
        vars.put("K", ClassElement.of(String.class));
        vars.put("V", ClassElement.of(List.class, AnnotationMetadata.EMPTY_METADATA, Map.of(
            "E", ClassElement.of(Integer.class)
        )));
        ClassElement element = ClassElement.of(
            Map.class, AnnotationMetadata.EMPTY_METADATA, vars
        );
        String result = writeClassWithField(
            FieldDef.builder("v").ofType(ClassTypeDef.of(element)).build()
        );

        assertEquals("Map<String, List<Integer>> v;", result);
    }

}
