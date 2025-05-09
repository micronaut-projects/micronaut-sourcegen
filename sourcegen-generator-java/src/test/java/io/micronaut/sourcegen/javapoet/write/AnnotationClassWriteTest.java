package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.custom.visitor.GenerateAnnotationClassVisitor;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ObjectDef;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AnnotationClassWriteTest {

    @Test
    public void writeAnnotationClass() throws IOException {
        AnnotationObjectDef ann = GenerateAnnotationClassVisitor.createAnnotation("test.TestAnnotation");
        var result = writeObject(ann);

        var expected = """
        /**
         * This is my annotation
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface TestAnnotation {
          /**
           * This is a string value
           */
          String string() default "hello";

          /**
           * This is a primitive value
           */
          int primitive() default 2;

          /**
           * This is a primitive array value
           */
          float[] floats();

          /**
           * An enum value with default
           */
          ElementType enumValue() default ElementType.TYPE;

          /**
           * An annotation value with default
           */
          Target inner() default @Target(ElementType.TYPE);
        }
        """;
        assertEquals(expected.strip(), result.strip());
    }

    private String writeObject(ObjectDef objectDef) throws IOException {
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(objectDef, writer);
            result = writer.toString();
        }

        // The regex will skip the imports and make sure it is a record
        final Pattern ANN_REGEX = Pattern.compile("package [^;]+;[^/]+" +
            "((?:/\\*\\*[\\S\\s]+\\*/\\s+|)[\\s\\S]+[\\s\\S]+})\\s*");
        Matcher matcher = ANN_REGEX.matcher(result);
        if (!matcher.matches()) {
            fail("Expected annotation to match regex: \n" + ANN_REGEX + "\nbut is: \n" + result);
        }
        return matcher.group(1);
    }

}
