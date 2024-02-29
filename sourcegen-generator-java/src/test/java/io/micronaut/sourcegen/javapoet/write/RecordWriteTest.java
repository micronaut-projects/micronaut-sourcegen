package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RecordWriteTest {


    @Test
    public void writeSimpleRecord() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.TestRecord")
                .addProperty(PropertyDef.builder("name").ofType(String.class).build())
                .addProperty(PropertyDef.builder("age").ofType(Integer.class)
                        .addAnnotation(AnnotationDef.builder(
                                ClassTypeDef.of("jakarta.validation.constraints.Min"))
                        .addMember("value", 1).build())
                        .build()
                )
                .addProperty(PropertyDef.builder("description").ofType(String.class)
                        .addAnnotation("jakarta.validation.constraints.NotBlank")
                        .build()
                )
                .build();
        var result = writeRecord(recordDef);

        var expected = """
        record TestRecord(
            String name,
            @Min(1) Integer age,
            @NotBlank String description
        ) {
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeRecordWithJavadoc() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.TestRecord")
                .addProperty(PropertyDef.builder("name").ofType(String.class)
                        .addJavadoc("The person's name").build())
                .addProperty(PropertyDef.builder("age").ofType(Integer.class)
                        .addJavadoc("The person's age")
                        .build()
                )
                .addJavadoc("A record representing a person.")
                .build();
        var result = writeRecord(recordDef);

        var expected = """
        /**
         * A record representing a person.
         *
         * @param name The person's name
         * @param age The person's age
         */
        record TestRecord(
            String name,
            Integer age
        ) {
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeRecordWithOnlyParameterJavadoc() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.TestRecord")
            .addProperty(PropertyDef.builder("name").ofType(String.class)
                .addJavadoc("The person's name").build())
            .build();
        var result = writeRecord(recordDef);

        var expected = """
        /**
         * @param name The person's name
         */
        record TestRecord(
            String name
        ) {
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    private String writeRecord(RecordDef recordDef) throws IOException {
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(recordDef, writer);
            result = writer.toString();
        }

        // The regex will skip the imports and make sure it is a record
        final Pattern RECORD_REGEX = Pattern.compile("package [^;]+;[^/]+" +
            "((?:/\\*\\*[\\S\\s]+\\*/\\s+|)record \\S+[\\s\\S]+})\\s*");
        Matcher matcher = RECORD_REGEX.matcher(result);
        if (!matcher.matches()) {
            fail("Expected record to match regex: \n" + RECORD_REGEX + "\nbut is: \n" + result);
        }
        return matcher.group(1);
    }

}
