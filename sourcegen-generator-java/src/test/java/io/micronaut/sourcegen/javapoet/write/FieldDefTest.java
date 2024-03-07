package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class FieldDefTest extends AbstractWriteTest {

    @Test
    public void arrayField() throws IOException {
        TypeDef array = TypeDef.array(TypeDef.primitive("byte"));
        String result = writeClassWithField(
            FieldDef.builder("byteArray").ofType(array).build()
        );

        assertEquals("byte[] byteArray;", result);
    }

    @Test
    public void twoDimensionalArrayField() throws IOException {
        TypeDef array = TypeDef.array(
            TypeDef.primitive("byte"), 2
        );
        String result = writeClassWithField(
            FieldDef.builder("byteArray").ofType(array).build()
        );

        assertEquals("byte[][] byteArray;", result);
    }

}
