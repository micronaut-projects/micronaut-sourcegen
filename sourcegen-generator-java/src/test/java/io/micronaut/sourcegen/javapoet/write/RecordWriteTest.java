package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    public void writeRecordWithDollarSign() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.$TestRecord")
            .addProperty(PropertyDef.builder("name$").ofType(String.class)
                .addJavadoc("The person's $name").build())
            .addProperty(PropertyDef.builder("age").ofType(Integer.class)
                .addJavadoc("The person's age")
                .build()
            )
            .addJavadoc("A record representing a $person.")
            .build();
        var result = writeRecord(recordDef);

        var expected = """
        /**
         * A record representing a $person.
         *
         * @param name$ The person's $name
         * @param age The person's age
         */
        record $TestRecord(
            String name$,
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


    @Test
    public void writeGenericRecord() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.TestRecord")
            .addTypeVariable(TypeDef.variable("K"))
            .addTypeVariable(TypeDef.variable("V", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of("java.util.function.Supplier"),
                TypeDef.variable("V")
            ))
            .addProperty(PropertyDef.builder("key").ofType(TypeDef.variable("K")).build())
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.variable("V")).build())
            .addProperty(PropertyDef.builder("values").ofType(
                TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.variable("V"))
            ).build())
            // TypeDef.THIS renders as the record parameterized by its own variables
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.THIS)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        var result = writeRecord(recordDef);

        var expected = """
        record TestRecord<K, V extends Number>(
            K key,
            V value,
            List<V> values
        ) implements Supplier<V> {
          public TestRecord<K, V> self() {
            return this;
          }
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void annotationClassValue() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.$TestRecord")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of("jackson.annotation.JsonSubTypes.Type"))
                .addMember("value", ClassTypeDef.STRING)
                .addMember("name", "string")
                .build())
            .build();
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(recordDef, writer);
            result = writer.toString();
        }

        var expected = """
        package test;

        import jackson.annotation.JsonSubTypes;

        @JsonSubTypes.Type(
            value = String.class,
            name = "string"
        )
        record $TestRecord() {
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void staticSelfTypeDoesNotUseRecordTypeVariables() throws IOException {
        RecordDef recordDef = RecordDef.builder("test.StaticSelfRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("create")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.THIS)
                .build((aThis, parameters) -> ClassTypeDef.of(UnsupportedOperationException.class)
                    .instantiate()
                    .doThrow()))
            .build();

        String source = writeRecordFile(recordDef);

        assertTrue(source.contains("static StaticSelfRecord<Object> create()"), source);
        JavaCompileAssertions.assertCompiles(source);
    }

    private String writeRecordFile(RecordDef recordDef) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(recordDef, writer);
            return writer.toString();
        }
    }

}
