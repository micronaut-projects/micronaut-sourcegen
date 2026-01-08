package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FieldDefTest extends AbstractWriteTest {

    @Test
    public void arrayField() throws IOException {
        TypeDef array = TypeDef.array(TypeDef.Primitive.BYTE);
        String result = writeClassWithField(
            FieldDef.builder("byteArray").ofType(array).build()
        );

        assertEquals("byte[] byteArray;", result);
    }

    @Test
    public void twoDimensionalArrayField() throws IOException {
        TypeDef array = TypeDef.array(
            TypeDef.Primitive.BYTE, 2
        );
        String result = writeClassWithField(
            FieldDef.builder("byteArray").ofType(array).build()
        );

        assertEquals("byte[][] byteArray;", result);
    }


    @Test public void annotatedGenericAndField() throws Exception {
        var MIN_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.Min"))
            .addMember("value", 1).build();
        var MAX_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.Max"))
            .addMember("value", 10).build();
        var NOTNULL_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.NotNull"))
            .build();

        TypeDef innerType = TypeDef.parameterized(ClassTypeDef.of(List.class),
            TypeDef.Primitive.FLOAT.wrapperType().annotated(MIN_ANN, MAX_ANN)).annotated(NOTNULL_ANN);
        PropertyDef propertyDef = PropertyDef.builder("numbers").ofType(innerType).build();

        RecordDef recordDef = RecordDef.builder("Record").addProperty(propertyDef).build();
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(recordDef, writer);
            result = writer.toString();
        }

        assertThat(result).isEqualTo("import jakarta.validation.constraints.Max;\n" +
            "import jakarta.validation.constraints.Min;\n" +
            "import jakarta.validation.constraints.NotNull;\n" +
            "import java.lang.Float;\n" +
            "import java.util.List;\n" +
            "\n" +
            "record Record(\n" +
            "    @NotNull List<@Min(1) @Max(10) Float> numbers\n" +
            ") {\n" +
            "}\n");
    }

    @Test public void annotatedGenericField() throws Exception {
        var MIN_ANN = AnnotationDef
            .builder(ClassTypeDef.of("jakarta.validation.constraints.Min"))
            .addMember("value", 1)
            .build();
        PropertyDef propertyDef = PropertyDef.builder("numbers")
            .ofType(TypeDef.parameterized(
                ClassTypeDef.of(List.class),
                TypeDef.Primitive.FLOAT.wrapperType().annotated(MIN_ANN)))
            .build();

        RecordDef recordDef = RecordDef.builder("Record").addProperty(propertyDef).build();
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(recordDef, writer);
            result = writer.toString();
        }

        assertThat(result).isEqualTo("import jakarta.validation.constraints.Min;\n" +
            "import java.lang.Float;\n" +
            "import java.util.List;\n" +
            "\n" +
            "record Record(\n" +
            "    List<@Min(1) Float> numbers\n" +
            ") {\n" +
            "}\n");
    }

    @Test public void annotatedClassField() throws Exception {
        var MIN_ANN = AnnotationDef
            .builder(ClassTypeDef.of("jakarta.validation.constraints.Min"))
            .addMember("value", 1)
            .build();
        PropertyDef propertyDef = PropertyDef.builder("numbers")
            .ofType(TypeDef.Primitive.FLOAT.wrapperType().annotated(MIN_ANN))
            .build();

        RecordDef recordDef = RecordDef.builder("Record").addProperty(propertyDef).build();
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(recordDef, writer);
            result = writer.toString();
        }

        assertThat(result).isEqualTo("import jakarta.validation.constraints.Min;\n" +
            "import java.lang.Float;\n" +
            "\n" +
            "record Record(\n" +
            "    @Min(1) Float numbers\n" +
            ") {\n" +
            "}\n");
    }

}
