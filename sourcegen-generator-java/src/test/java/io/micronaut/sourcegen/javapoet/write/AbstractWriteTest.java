package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.fail;

public abstract class AbstractWriteTest {

    /**
     * Writes a class and returns the inner contents of the class.
     */
    protected String writeClassWithContents(ClassDef classDef) throws IOException {
        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(classDef, writer);
            result = writer.toString();
        }

        final Pattern CLASS_REGEX = Pattern.compile("package test;[\\s\\S]+" +
            "public class " + classDef.getSimpleName() + " \\{\\s+" +
            "([\\s\\S]+)\\s+}\\s+");
        Matcher matcher = CLASS_REGEX.matcher(result);
        if (!matcher.matches()) {
            fail("Expected class to match regex: \n" + CLASS_REGEX + "\nbut is: \n" + result);
        }
        return matcher.group(1).trim();
    }

    /**
     * Write as class and returns the constructor.
     */
    protected String writeClassAndGetConstructor(ClassDef classDef) throws IOException {
        String content = writeClassWithContents(classDef);

        final Pattern CONSTRUCTOR_REGEX = Pattern.compile(
            "\\s*\\S* ?" + classDef.getSimpleName() + "\\([^)]*\\) \\{([^}]+)}");
        Matcher matcher = CONSTRUCTOR_REGEX.matcher(content);
        if (!matcher.find()) {
            fail("Expected class content to match regex: \n" + CONSTRUCTOR_REGEX + "\nbut is: \n" + content);
        }
        return unindent(matcher.group(0));
    }

    protected String writeMethodWithExpression(ExpressionDef expression) throws IOException {
        ClassDef classDef = ClassDef.builder("test.Test")
            .addMethod(MethodDef.builder("test")
                .returns(expression.type())
                .addStatement(new StatementDef.Return(expression))
                .build()
            )
            .addModifiers(Modifier.PUBLIC)
            .build();
        String classBody = writeClassWithContents(classDef);

        final Pattern METHOD_REGEX = Pattern.compile(
            "\\S+ test\\(\\) \\{\\s+return ([^;]+);\\s+}");
        Matcher methodMatcher = METHOD_REGEX.matcher(classBody);
        if (!methodMatcher.matches()) {
            fail("Expected method to match regex: \n" + METHOD_REGEX + "\nbut is: \n" + classBody);
        }
        return methodMatcher.group(1);
    }

    protected String writeClassWithField(FieldDef field) throws IOException {
        ClassDef classDef = ClassDef.builder("test.Test")
            .addField(field)
            .addModifiers(Modifier.PUBLIC)
            .build();
        return writeClassWithContents(classDef);
    }

    protected String unindent(String content) {
        String[] lines = content.split("\n");
        int indent = 1000;
        for (String line: lines) {
            for (int i = 0; i < indent && i < line.length(); i++) {
                if (!Character.isWhitespace(line.charAt(i))) {
                    indent = i;
                    break;
                }
            }
        }
        int finalIndent = indent;
        return Arrays.stream(lines).map(v -> v.length() > finalIndent ? v.substring(finalIndent) : "")
            .collect(Collectors.joining("\n"));
    }

}
