package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ExpressionWriteTest {

    private static final ClassTypeDef STRING = ClassTypeDef.of(String.class);

    @Test
    public void returnConstantExpression() throws IOException {
        ExpressionDef helloString = ExpressionDef.constant(
            ClassElement.of(String.class), STRING, "hello"
        );
        String result = writeMethodWithExpression(helloString);

        assertEquals("\"hello\"", result);
    }

    @Test
    public void returnStaticInvoke() throws IOException {
        ExpressionDef two = ExpressionDef.constant(
            ClassElement.of(int.class), new TypeDef.Primitive("int"), "2"
        );
        ExpressionDef valueOfTwo = ExpressionDef.invokeStatic(
            STRING, "valueOf", List.of(two), STRING
        );
        String result = writeMethodWithExpression(valueOfTwo);

        assertEquals("String.valueOf(2)", result);
    }

    @Test
    public void returnInvoke() throws IOException {
        ExpressionDef helloString = ExpressionDef.constant(
            ClassElement.of(String.class), STRING, "hello"
        );
        ExpressionDef equals = ExpressionDef.invoke(
            new VariableDef.This(ClassTypeDef.of("test.Test")),
            "equals", List.of(helloString), new TypeDef.Primitive("boolean"));
        String result = writeMethodWithExpression(equals);

        assertEquals("this.equals(\"hello\")", result);
    }

    private String writeMethodWithExpression(ExpressionDef expression) throws IOException {
        StatementDef.Return returnStatement = new StatementDef.Return(expression);
        ClassDef classDef = ClassDef.builder("test.Test")
            .addMethod(MethodDef.builder("test")
                .returns(expression.type())
                .addStatement(returnStatement)
                .build()
            )
            .addModifiers(Modifier.PUBLIC)
            .build();

        JavaPoetSourceGenerator generator = new JavaPoetSourceGenerator();
        String result;
        try (StringWriter writer = new StringWriter()) {
            generator.write(classDef, writer);
            result = writer.toString();
        }

        final Pattern CLASS_REGEX = Pattern.compile("package test;[\\s\\S]+" +
            "public class Test \\{\\s+" +
            "([\\s\\S]+)\\s+}\\s+");
        Matcher matcher = CLASS_REGEX.matcher(result);
        if (!matcher.matches()) {
            fail("Expected class to match regex: \n" + CLASS_REGEX + "\nbut is: \n" + result);
        }
        String classBody = matcher.group(1);

        final Pattern METHOD_REGEX = Pattern.compile(
            "\\S+ test\\(\\) \\{\\s+return ([^;]+);\\s+}");
        Matcher methodMatcher = METHOD_REGEX.matcher(classBody);
        if (!methodMatcher.matches()) {
            fail("Expected method to match regex: \n" + METHOD_REGEX + "\nbut is: \n" + classBody);
        }
        return methodMatcher.group(1);
    }

}
