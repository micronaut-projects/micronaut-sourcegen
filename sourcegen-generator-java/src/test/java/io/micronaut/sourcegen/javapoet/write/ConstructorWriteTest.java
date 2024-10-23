package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ConstructorWriteTest extends AbstractWriteTest {

    @Test
    public void writeAllFieldsConstructor() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Test")
                .addModifiers(Modifier.PUBLIC)
                .addProperty(PropertyDef.builder("name").ofType(String.class).build())
                .addProperty(PropertyDef.builder("age").ofType(Integer.class).build())
                .addAllFieldsConstructor()
                .build();
        var result = writeClassAndGetConstructor(classDef);

        var expected = """
        Test(String name, Integer age) {
          this.name = name;
          this.age = age;
        }
        """;
        assertEquals(expected.strip(), result.trim());
    }

    @Test
    public void writeNoFieldsConstructor() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Test")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String.class).build())
            .addProperty(PropertyDef.builder("age").ofType(Integer.class).build())
            .addNoFieldsConstructor(Modifier.PUBLIC)
            .build();
        var result = writeClassAndGetConstructor(classDef);

        var expected = """
        public Test() {
        }
        """;
        assertEquals(expected.strip(), result.trim());
    }

    @Test
    public void writeConstructor() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Test")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(String.class).build())
            .addProperty(PropertyDef.builder("age").ofType(Integer.class).build())
            .addConstructor(List.of(ParameterDef.of("name", TypeDef.of(String.class))), Modifier.PROTECTED)
            .build();
        var result = writeClassAndGetConstructor(classDef);

        var expected = """
        protected Test(String name) {
          this.name = name;
        }
        """;
        assertEquals(expected.strip(), result.trim());
    }

}
