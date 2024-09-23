package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Cast;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ExpressionWriteTest extends AbstractWriteTest {

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

    @Test
    public void returnConstantStringArray() throws IOException {
        ExpressionDef stringArray = new VariableDef.Constant(TypeDef.array(ClassTypeDef.of(String.class)),
            new String[] {"hello", "world"});
        String result = writeMethodWithExpression(stringArray);

        assertEquals("new String[] {\"hello\", \"world\"}", result);
    }

    @Test
    public void returnConstantIntegerArray() throws IOException {
        ExpressionDef integerArray = new VariableDef.Constant(TypeDef.array(ClassTypeDef.of(Integer.class)),
            new Integer[] {1, 2});
        String result = writeMethodWithExpression(integerArray);

        assertEquals("new Integer[] {1, 2}", result);
    }

    @Test
    public void returnConstantIntArray() throws IOException {
        ExpressionDef integerArray = new VariableDef.Constant(TypeDef.array(TypeDef.primitive(Integer.TYPE)),
            new int[] {1, 2});
        String result = writeMethodWithExpression(integerArray);

        assertEquals("new int[] {1, 2}", result);
    }

    @Test
    public void returnCastedValue() throws IOException {
        ExpressionDef castedExpression = ExpressionDef
            .constant(ClassElement.of(Double.TYPE), TypeDef.primitive("double"), 10.5)
            .cast(TypeDef.primitive("float"));
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(float) 10.5d", result);
    }

    @Test
    public void returnCastedValue2() throws IOException {
        ExpressionDef castedExpression = new Cast(
            TypeDef.of(Object.class),
            ExpressionDef.constant(ClassElement.of(String.class), TypeDef.of(String.class), "hello")
        );
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Object) \"hello\"", result);
    }
}
