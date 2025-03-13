package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.EnumDef.EnumConstantDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

public class EnumWriteTest {
    @Test
    public void writeSimpleEnum() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        enum Status {

          ACTIVE,
          IN_PROGRESS,
          DELETED
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeEnumWithJavadoc() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addJavadoc("Enum representing the status of a process.")
            .addEnumConstant(EnumConstantDef.builder("ACTIVE").addJavadoc("The active state.").build())
            .addEnumConstant(EnumConstantDef.builder("IN_PROGRESS").addJavadoc("The in progress state.").build())
            .addEnumConstant(EnumConstantDef.builder("DELETED").addJavadoc("The in deleted state.").build())
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        /**
         * Enum representing the status of a process.
         */
        enum Status {

          /**
           * The active state.
           */
          ACTIVE,
          /**
           * The in progress state.
           */
          IN_PROGRESS,
          /**
           * The in deleted state.
           */
          DELETED
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void testExceptions() {
        assertThrows(IllegalArgumentException.class, () ->
            EnumDef.builder("test.Status").addEnumConstant("active").build());
        assertThrows(IllegalArgumentException.class, () ->
            EnumDef.builder("test.Status").addEnumConstant("9in progress", ExpressionDef.constant(1)).build());

        EnumDef.EnumDefBuilder enumDefBuilder = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE", ExpressionDef.constant(2))
            .addEnumConstant("IN_PROGRESS", ExpressionDef.constant(1))
            .addEnumConstant("DELETED", ExpressionDef.constant(0));
        assertThrows(IllegalStateException.class, enumDefBuilder::build);

        enumDefBuilder.addNoFieldsConstructor(Modifier.PRIVATE);
        assertThrows(IllegalStateException.class, enumDefBuilder::build);

        enumDefBuilder.addField(FieldDef.builder("intValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addAllFieldsConstructor(Modifier.PUBLIC);
        assertThrows(IllegalStateException.class, enumDefBuilder::build);
    }

    @Test
    public void writeComplexEnumConstant() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE", ExpressionDef.constant(2))
            .addEnumConstant("IN_PROGRESS", ExpressionDef.constant(1))
            .addEnumConstant("DELETED", ExpressionDef.constant(0))
            .addField(FieldDef.builder("intValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        enum Status {

          ACTIVE(2),
          IN_PROGRESS(1),
          DELETED(0);

          public int intValue;

          private Status(int intValue) {
            this.intValue = intValue;
          }
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeComplexEnumConstant2() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE", ExpressionDef.constant(2), ExpressionDef.trueValue())
            .addEnumConstant("IN_PROGRESS", ExpressionDef.constant(1), ExpressionDef.trueValue())
            .addEnumConstant("DELETED", ExpressionDef.constant(0), ExpressionDef.falseValue())
            .addField(FieldDef.builder("intValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addField(FieldDef.builder("boolValue").ofType(TypeDef.Primitive.BOOLEAN).addModifiers(Modifier.PUBLIC).build())
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        enum Status {

          ACTIVE(2, true),
          IN_PROGRESS(1, true),
          DELETED(0, false);

          public int intValue;

          public boolean boolValue;

          private Status(int intValue, boolean boolValue) {
            this.intValue = intValue;
            this.boolValue = boolValue;
          }
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeComplexEnumWithProperty() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.STRING).addModifiers(Modifier.PUBLIC).build())
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        import java.lang.String;

        enum Status {

          ACTIVE,
          IN_PROGRESS,
          DELETED;

          private String value;

          public String getValue() {
            return this.value;
          }
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    @Test
    public void writeComplexEnumWithPropertyMethod() throws IOException {
        EnumDef enumDef = EnumDef.builder("test.Status")
            .addEnumConstant("ACTIVE")
            .addEnumConstant("IN_PROGRESS")
            .addEnumConstant("DELETED")
            .addField(FieldDef.builder("value").ofType(TypeDef.STRING).build())
            .addMethod(MethodDef.builder("getValue")
                .returns(TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC)
                .addStatement(new StatementDef.Return(ExpressionDef.constant("value")))
                .build())
            .build();
        var result = writeEnum(enumDef);

        var expected = """
        package test;

        import java.lang.String;

        enum Status {

          ACTIVE,
          IN_PROGRESS,
          DELETED;

          String value;

          public String getValue() {
            return "value";
          }
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }


    private String writeEnum(EnumDef enumDef) throws IOException {
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(enumDef, writer);
            result = writer.toString();
        }

        // The regex will skip the imports and make sure it is a record
        final Pattern ENUM_REGEX = Pattern.compile("package [^;]+;[^{]+" +
            "enum " + enumDef.getSimpleName() + " \\{\\s+" +
            "([\\s\\S]+)\\s+}\\s+");
        Matcher matcher = ENUM_REGEX.matcher(result);
        if (!matcher.matches()) {
            fail("Expected enum to match regex: \n" + ENUM_REGEX + "\nbut is: \n" + result);
        }
        return matcher.group(0);
    }

}
