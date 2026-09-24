package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a call to a variable arity method whose trailing parameter is generic - {@code List.of(E...)}
 * and its siblings - keeps the element type once there are more arguments than the fixed arity overloads
 * cover, so that the generated source still compiles.
 */
public class VarArgsWriteTest extends AbstractWriteTest {

    private static final String ENTRY = "test.Entry";

    /**
     * More arguments than {@code List.of} declares fixed arity overloads for, so the call can only
     * resolve to {@code List.of(E...)}.
     */
    private static final int OVER_THE_FIXED_ARITY = 14;

    @Test
    public void listOfMoreThanTenRecordsCompiles() throws IOException {
        ClassTypeDef entryType = ClassTypeDef.of(ENTRY);
        List<ExpressionDef> entries = new ArrayList<>();
        for (int i = 0; i < OVER_THE_FIXED_ARITY; i++) {
            entries.add(entryType.instantiate(ExpressionDef.constant("entry" + i)));
        }

        String source = writeListOf(entryType, entries);

        assertTrue(source.contains("Entry[]"), "Expected a typed array, but was:\n" + source);
        JavaCompileAssertions.assertCompiles(source, entryRecord());
    }

    @Test
    public void listOfMoreThanTenStringsCompiles() throws IOException {
        String source = writeListOf(ClassTypeDef.STRING, constants());

        assertTrue(source.contains("String[]"), "Expected a typed array, but was:\n" + source);
        JavaCompileAssertions.assertCompiles(source);
    }

    @Test
    public void setOfMoreThanTenStringsCompiles() throws IOException {
        String source = writeInvocation(Set.class, "of",
            TypeDef.parameterized(Set.class, ClassTypeDef.STRING), constants());

        JavaCompileAssertions.assertCompiles(source);
    }

    @Test
    public void arraysAsListOfMoreThanTenStringsCompiles() throws IOException {
        String source = writeInvocation(Arrays.class, "asList",
            TypeDef.parameterized(List.class, ClassTypeDef.STRING), constants());

        JavaCompileAssertions.assertCompiles(source);
    }

    /**
     * Ten or fewer arguments resolve to a fixed arity overload, which never needed an array.
     */
    @Test
    public void listOfTenStringsCompiles() throws IOException {
        String source = writeListOf(ClassTypeDef.STRING, constants(10));

        JavaCompileAssertions.assertCompiles(source);
    }

    private static List<ExpressionDef> constants() {
        return constants(OVER_THE_FIXED_ARITY);
    }

    private static List<ExpressionDef> constants(int count) {
        return IntStream.range(0, count)
            .<ExpressionDef>mapToObj(i -> ExpressionDef.constant("value" + i))
            .toList();
    }

    private String writeListOf(TypeDef elementType, List<ExpressionDef> values) throws IOException {
        return writeInvocation(List.class, "of", TypeDef.parameterized(List.class, elementType), values);
    }

    private String writeInvocation(Class<?> owner,
                                   String name,
                                   TypeDef returnType,
                                   List<ExpressionDef> values) throws IOException {
        ClassDef classDef = ClassDef.builder("test.Values")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("values")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(returnType)
                .build((aThis, parameters) -> ClassTypeDef.of(owner)
                    .invokeStatic(name, returnType, values)
                    .returning()))
            .build();
        return writeClass(classDef);
    }

    private static String entryRecord() {
        return """
            package test;

            public record Entry(String name) {
            }
            """;
    }

}
