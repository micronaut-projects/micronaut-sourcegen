package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InnerTypesTest extends AbstractWriteTest {
    /** -----------------------------------------------------------
     * INNER TYPES INSIDE AN ENUM
     * -----------------------------------------------------------
     */
    @Test
    public void enumInEnum() throws IOException {
        String expectedString = """
            package test;

            public enum StatusEnum {

              HI,
              HELLO;

              enum Status {

                SINGLE,
                MARRIED
              }
            }""";
        EnumDef.EnumDefBuilder enumBuilder = EnumDef.builder("Status");
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED");
        EnumDef enumDef = enumBuilder.build();

        EnumDef.EnumDefBuilder classBuilder = getEnumDefBuilderWith(enumDef);
        String actual = writeClass(classBuilder.build(), "enum");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void recordInEnum() throws IOException {
        String expectedString = """
            package test;

            public enum ExampleRecordEnum {

              HI,
              HELLO;

              record ExampleRecord(
                  int id
              ) {
              }
            }""";
        RecordDef.RecordDefBuilder recordBuilder = RecordDef.builder("ExampleRecord");
        PropertyDef.PropertyDefBuilder propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT);
        recordBuilder.addProperty(propertyBuilder.build());

        EnumDef.EnumDefBuilder classBuilder = getEnumDefBuilderWith(recordBuilder.build());
        String actual = writeClass(classBuilder.build(), "enum");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void classInEnum() throws IOException {
        String expectedString = """
            package test;

            public enum InnerEnum {

              HI,
              HELLO;

              class Inner {
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner");

        EnumDef.EnumDefBuilder classBuilder = getEnumDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"enum");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void interfaceInEnum() throws IOException {
        String expectedString = """
            package test;

            public enum InterfaceEnum {

              HI,
              HELLO;

              interface Interface {
              }
            }""";
        InterfaceDef.InterfaceDefBuilder interfaceBuilder = InterfaceDef.builder("Interface");

        EnumDef.EnumDefBuilder classBuilder = getEnumDefBuilderWith(interfaceBuilder.build());
        String actual = writeClass(classBuilder.build(),"enum");
        assertEquals(expectedString.strip(), actual);
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE A CLASS
     * -----------------------------------------------------------
     */
    @Test
    public void enumInClass() throws IOException {
        String expectedString = """
            package test;

            public class StatusClass {
              enum Status {

                SINGLE,
                MARRIED
              }
            }""";
        EnumDef.EnumDefBuilder enumBuilder = EnumDef.builder("Status");
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED");
        EnumDef enumDef = enumBuilder.build();

        ClassDef.ClassDefBuilder classBuilder = getClassDefBuilderWith(enumDef);
        String actual = writeClass(classBuilder.build(), "class");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void recordInClass() throws IOException {
        String expectedString = """
            package test;

            public class ExampleRecordClass {
              record ExampleRecord(
                  int id
              ) {
              }
            }""";
        RecordDef.RecordDefBuilder recordBuilder = RecordDef.builder("ExampleRecord");
        PropertyDef.PropertyDefBuilder propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT);
        recordBuilder.addProperty(propertyBuilder.build());

        ClassDef.ClassDefBuilder classBuilder = getClassDefBuilderWith(recordBuilder.build());
        String actual = writeClass(classBuilder.build(), "class");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void classInClass() throws IOException {
        String expectedString = """
            package test;

            public class InnerClass {
              class Inner {
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner");

        ClassDef.ClassDefBuilder classBuilder = getClassDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"class");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void class2InClass() throws IOException {
        String expectedString = """
            package test;

            import java.lang.String;

            public class InnerClass {
              private class Inner {
                String name;

                Inner(String name) {
                  this.name = name;
                }

                Inner() {
                }
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner")
            .addModifiers(Modifier.PRIVATE)
            .addField(FieldDef.builder("name").ofType(TypeDef.STRING).build())
            .addAllFieldsConstructor()
            .addNoFieldsConstructor();

        ClassDef.ClassDefBuilder classBuilder = getClassDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"class");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void interfaceInClass() throws IOException {
        String expectedString = """
            package test;

            public class InterfaceClass {
              interface Interface {
              }
            }""";
        InterfaceDef.InterfaceDefBuilder interfaceBuilder = InterfaceDef.builder("Interface");

        ClassDef.ClassDefBuilder classBuilder = getClassDefBuilderWith(interfaceBuilder.build());
        String actual = writeClass(classBuilder.build(),"class");
        assertEquals(expectedString.strip(), actual);
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE A RECORD
     * -----------------------------------------------------------
     */
    @Test
    public void enumInRecord() throws IOException {
        String expectedString = """
            package test;

            public record StatusRecord() {
              enum Status {

                SINGLE,
                MARRIED
              }
            }""";
        EnumDef.EnumDefBuilder enumBuilder = EnumDef.builder("Status");
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED");
        EnumDef enumDef = enumBuilder.build();

        RecordDef.RecordDefBuilder classBuilder = getRecordDefBuilderWith(enumDef);
        String actual = writeClass(classBuilder.build(), "record");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void recordInRecord() throws IOException {
        String expectedString = """
            package test;

            public record ExampleRecord() {
              record Example(
                  int id
              ) {
              }
            }""";
        RecordDef.RecordDefBuilder recordBuilder = RecordDef.builder("Example");
        PropertyDef.PropertyDefBuilder propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT);
        recordBuilder.addProperty(propertyBuilder.build());

        RecordDef.RecordDefBuilder classBuilder = getRecordDefBuilderWith(recordBuilder.build());
        String actual = writeClass(classBuilder.build(),"record");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void classInRecord() throws IOException {
        String expectedString = """
            package test;

            public record InnerRecord() {
              class Inner {
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner");

        RecordDef.RecordDefBuilder classBuilder = getRecordDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"record");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void staticClassInRecord() throws IOException {
        String expectedString = """
            package test;

            public record InnerRecord() {
              private static class Inner {
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner");
        innerClassBuilder.addModifiers(Modifier.STATIC, Modifier.PRIVATE);

        RecordDef.RecordDefBuilder classBuilder = getRecordDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"record");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void interfaceInRecord() throws IOException {
        String expectedString = """
            package test;

            public record InterfaceRecord() {
              interface Interface {
              }
            }""";
        InterfaceDef.InterfaceDefBuilder interfaceBuilder = InterfaceDef.builder("Interface");

        RecordDef.RecordDefBuilder classBuilder = getRecordDefBuilderWith(interfaceBuilder.build());
        String actual = writeClass(classBuilder.build(),"record");
        assertEquals(expectedString.strip(), actual);
    }

    /** -----------------------------------------------------------
     * INNER TYPES INSIDE AN INTERFACE
     * -----------------------------------------------------------
     */

    @Test
    public void enumInInterface() throws IOException {
        String expectedString = """
            package test;

            public interface StatusInterface {
              enum Status {

                SINGLE,
                MARRIED
              }
            }""";
        EnumDef.EnumDefBuilder enumBuilder = EnumDef.builder("Status");
        enumBuilder.addEnumConstant("SINGLE").addEnumConstant("MARRIED");
        EnumDef enumDef = enumBuilder.build();

        InterfaceDef.InterfaceDefBuilder classBuilder = getInterfaceDefBuilderWith(enumDef);
        String actual = writeClass(classBuilder.build(), "interface");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void recordInInterface() throws IOException {
        String expectedString = """
            package test;

            public interface RecordInterface {
              record Record(
                  int id
              ) {
              }
            }""";
        RecordDef.RecordDefBuilder recordBuilder = RecordDef.builder("Record");
        PropertyDef.PropertyDefBuilder propertyBuilder = PropertyDef.builder("id").ofType(TypeDef.Primitive.INT);
        recordBuilder.addProperty(propertyBuilder.build());

        InterfaceDef.InterfaceDefBuilder classBuilder = getInterfaceDefBuilderWith(recordBuilder.build());
        String actual = writeClass(classBuilder.build(),"interface");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void classInInterface() throws IOException {
        String expectedString = """
            package test;

            public interface InnerInterface {
              class Inner {
              }
            }""";
        ClassDef.ClassDefBuilder innerClassBuilder = ClassDef.builder("Inner");

        InterfaceDef.InterfaceDefBuilder classBuilder = getInterfaceDefBuilderWith(innerClassBuilder.build());
        String actual = writeClass(classBuilder.build(),"interface");
        assertEquals(expectedString.strip(), actual);
    }

    @Test
    public void interfaceInInterface() throws IOException {
        String expectedString = """
            package test;

            public interface InnerInterface {
              interface Inner {
              }
            }""";
        InterfaceDef.InterfaceDefBuilder interfaceBuilder = InterfaceDef.builder("Inner");

        InterfaceDef.InterfaceDefBuilder classBuilder = getInterfaceDefBuilderWith(interfaceBuilder.build());
        String actual = writeClass(classBuilder.build(),"interface");
        assertEquals(expectedString.strip(), actual);
    }

    /** -----------------------------------------------------------
     * HELPER METHODS
     * -----------------------------------------------------------
     */

    private static ClassDef.ClassDefBuilder getClassDefBuilderWith(ObjectDef objectDef) {
        ClassDef.ClassDefBuilder classBuilder = ClassDef.builder("test." + objectDef.getSimpleName() + "Class")
            .addModifiers(Modifier.PUBLIC);
        classBuilder.addInnerType(objectDef);
        return classBuilder;
    }

    private static RecordDef.RecordDefBuilder getRecordDefBuilderWith(ObjectDef objectDef) {
        RecordDef.RecordDefBuilder classBuilder = RecordDef.builder("test." + objectDef.getSimpleName() + "Record")
            .addModifiers(Modifier.PUBLIC);
        classBuilder.addInnerType(objectDef);
        return classBuilder;
    }

    private static EnumDef.EnumDefBuilder getEnumDefBuilderWith(ObjectDef objectDef) {
        EnumDef.EnumDefBuilder classBuilder = EnumDef.builder("test." + objectDef.getSimpleName() + "Enum")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("HI").addEnumConstant("HELLO");
        classBuilder.addInnerType(objectDef);
        return classBuilder;
    }

    private static InterfaceDef.InterfaceDefBuilder getInterfaceDefBuilderWith(ObjectDef objectDef) {
        InterfaceDef.InterfaceDefBuilder classBuilder = InterfaceDef.builder("test." + objectDef.getSimpleName() + "Interface")
            .addModifiers(Modifier.PUBLIC);
        classBuilder.addInnerType(objectDef);
        return classBuilder;
    }
}
