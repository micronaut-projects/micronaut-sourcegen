package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Cast;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.EQUAL_TO;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.GREATER_THAN;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.LESS_THAN;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.LESS_THAN_OR_EQUAL;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.ADDITION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_AND;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_OR;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_XOR;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.DIVISION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.MODULUS;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation.OpType.NEGATE;
import static org.junit.Assert.assertEquals;

public class ExpressionWriteTest extends AbstractWriteTest {

    private static final ClassTypeDef STRING = ClassTypeDef.STRING;

    @Test
    public void writeClass() throws IOException {
        String data = writeClass(
            ClassDef.builder("example.Example")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("myMethod1")
                    .addParameters(Class.class)
                    .build())
                .addMethod(MethodDef.builder("myMethod2")
                    .build((aThis, methodParameters) ->
                        aThis.invoke(
                            "myMethod1",
                            List.of(TypeDef.CLASS),
                            TypeDef.VOID,
                            List.of(ExpressionDef.constant(TypeDef.of("example.Example")))
                        ).returning()))
                .addMethod(MethodDef.builder("myMethod3")
                    .build((aThis, methodParameters) ->
                        aThis.invoke(
                            "myMethod1",
                            List.of(TypeDef.CLASS),
                            TypeDef.VOID,
                            List.of(ExpressionDef.constant(TypeDef.of(String.class)))
                        ).returning()))
                .addMethod(MethodDef.builder("myMethod4")
                    .build((aThis, methodParameters) ->
                        aThis.invoke(
                            "myMethod1",
                            List.of(TypeDef.CLASS),
                            TypeDef.VOID,
                            List.of(ExpressionDef.constant(TypeDef.of(int.class)))
                        ).returning()))
                .addMethod(MethodDef.builder("myMethod4")
                    .build((aThis, methodParameters) ->
                        aThis.invoke(
                            "myMethod1",
                            List.of(TypeDef.CLASS),
                            TypeDef.VOID,
                            List.of(ExpressionDef.constant(TypeDef.of(int[].class)))
                        ).returning()))
                .addMethod(MethodDef.builder("myMethod4")
                    .build((aThis, methodParameters) ->
                        aThis.invoke(
                            "myMethod1",
                            List.of(TypeDef.CLASS),
                            TypeDef.VOID,
                            List.of(ExpressionDef.constant(TypeDef.of(String[].class)))
                        ).returning()))
                .build()
        );

        assertEquals("""
package example;

import java.lang.Class;

public class Example {
  void myMethod1(Class arg1) {
  }

  void myMethod2() {
    return this.myMethod1(example.Example.class);
  }

  void myMethod3() {
    return this.myMethod1(java.lang.String.class);
  }

  void myMethod4() {
    return this.myMethod1(int.class);
  }

  void myMethod4() {
    return this.myMethod1(int[].class);
  }

  void myMethod4() {
    return this.myMethod1(java.lang.String[].class);
  }
}
""", data);
    }

    @Test
    public void compareOperations() throws IOException {
        String data = writeClass(
            ClassDef.builder("example.Example")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("myMethod")
                    .addParameters(int.class)
                    .addParameters(int.class)
                    .build((aThis, methodParameters) ->
                        TypeDef.OBJECT.array().instantiate(
                            methodParameters.get(0).compare(EQUAL_TO, methodParameters.get(1)),
                            methodParameters.get(0).compare(NOT_EQUAL_TO, methodParameters.get(1)),
                            methodParameters.get(0).compare(GREATER_THAN, methodParameters.get(1)),
                            methodParameters.get(0).compare(LESS_THAN, methodParameters.get(1)),
                            methodParameters.get(0).compare(GREATER_THAN_OR_EQUAL, methodParameters.get(1)),
                            methodParameters.get(0).compare(LESS_THAN_OR_EQUAL, methodParameters.get(1)),
                            methodParameters.get(0).isNull(),
                            methodParameters.get(0).isNonNull()
                        ).returning()))
                .build()
        );

        assertEquals("""
package example;

import java.lang.Object;

public class Example {
  Object[] myMethod(int arg1, int arg2) {
    return new Object[]{arg1 == arg2,arg1 != arg2,arg1 > arg2,arg1 < arg2,arg1 >= arg2,arg1 <= arg2,arg1 == null,arg1 != null};
  }
}
""", data);
    }

    @Test
    public void mathOperations() throws IOException {
        String data = writeClass(
            ClassDef.builder("example.Example")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("myMethod")
                    .addParameters(int.class)
                    .addParameters(int.class)
                    .build((aThis, methodParameters) ->
                        TypeDef.OBJECT.array().instantiate(
                            methodParameters.get(0).math(ADDITION, methodParameters.get(1)),
                            methodParameters.get(0).math(SUBTRACTION, methodParameters.get(1)),
                            methodParameters.get(0).math(MULTIPLICATION, methodParameters.get(1)),
                            methodParameters.get(0).math(DIVISION, methodParameters.get(1)),
                            methodParameters.get(0).math(MODULUS, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_AND, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_OR, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_XOR, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_LEFT_SHIFT, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_RIGHT_SHIFT, methodParameters.get(1)),
                            methodParameters.get(0).math(BITWISE_UNSIGNED_RIGHT_SHIFT, methodParameters.get(1)),
                            methodParameters.get(0).math(NEGATE)
                        ).returning()))
                .build()
        );

        assertEquals("""
package example;

import java.lang.Object;

public class Example {
  Object[] myMethod(int arg1, int arg2) {
    return new Object[]{arg1 + arg2,arg1 - arg2,arg1 * arg2,arg1 / arg2,arg1 % arg2,arg1 & arg2,arg1 | arg2,arg1 ^ arg2,arg1 << arg2,arg1 >> arg2,arg1 >>> arg2,-arg1};
  }
}
""", data);
    }

    @Test
    public void equalsExpressions() throws IOException {
        ExpressionDef exp1 = ExpressionDef.constant(
            ClassElement.of(String.class), STRING, "hello"
        );
        ExpressionDef exp2 = ExpressionDef.constant(
            ClassElement.of(String.class), STRING, "world"
        );
        String equalsReferentially = writeMethodWithExpression(exp1.equalsReferentially(exp2));

        assertEquals("\"hello\" == \"world\"", equalsReferentially);

        String notEqualsReferentially = writeMethodWithExpression(exp1.notEqualsReferentially(exp2));

        assertEquals("\"hello\" != \"world\"", notEqualsReferentially);

        String equalsStructurally = writeMethodWithExpression(exp1.equalsStructurally(exp2));

        assertEquals("Objects.equals(\"hello\", \"world\")", equalsStructurally);

        String notEqualsStructurally = writeMethodWithExpression(exp1.notEqualsStructurally(exp2));

        assertEquals("(!Objects.equals(\"hello\", \"world\"))", notEqualsStructurally);
    }

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
            ClassElement.of(int.class), TypeDef.Primitive.INT, "2"
        );
        ExpressionDef valueOfTwo = STRING.invokeStatic(
            "valueOf", STRING, two
        );
        String result = writeMethodWithExpression(valueOfTwo);

        assertEquals("String.valueOf(2)", result);
    }

    @Test
    public void returnInvoke() throws IOException {
        ExpressionDef helloString = ExpressionDef.constant(
            ClassElement.of(String.class), STRING, "hello"
        );
        ExpressionDef equals = new VariableDef.This().invoke("equals", TypeDef.Primitive.BOOLEAN, helloString);
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
            .constant(ClassElement.of(Double.TYPE), TypeDef.Primitive.DOUBLE, 10.5)
            .cast(TypeDef.Primitive.FLOAT);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(float) (10.5d)", result);
    }

    @Test
    public void returnCastedValue2() throws IOException {
        ExpressionDef castedExpression = new Cast(
            TypeDef.of(Object.class),
            ExpressionDef.constant(ClassElement.of(String.class), TypeDef.of(String.class), "hello")
        );
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Object) (\"hello\")", result);
    }

    @Test
    public void returnCastedVariable() throws IOException {
        ExpressionDef castedExpression = new Cast(
            TypeDef.of(Integer.class),
            new VariableDef.Local("field", TypeDef.of(Object.class))
        );
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Integer) field", result);
    }

    @Test
    public void returnAndCondition() throws IOException {
        ExpressionDef andExpression = new ExpressionDef.And(
            ExpressionDef.trueValue().isTrue(),
            new VariableDef.Local("field", TypeDef.Primitive.BOOLEAN).isTrue()
        );
        String result = writeMethodWithExpression(andExpression);

        assertEquals("true && field", result);
    }

    @Test
    public void returnInstanceOfCondition() throws IOException {
        ExpressionDef andExpression = new ExpressionDef.InstanceOf(
            ExpressionDef.constant("test"),
            ClassTypeDef.ClassDefType.STRING
        );
        String result = writeMethodWithExpression(andExpression);

        assertEquals("\"test\" instanceof java.lang.String", result);
    }

    @Test
    public void returnAndConditionFalse() throws IOException {
        ExpressionDef andExpression = new ExpressionDef.And(
            ExpressionDef.trueValue().isTrue(),
            new VariableDef.Local("field", TypeDef.Primitive.BOOLEAN).isFalse()
        );
        String result = writeMethodWithExpression(andExpression);

        assertEquals("true && !field", result);
    }

    @Test
    public void returnAndConditionWithParentheses() throws IOException {
        ExpressionDef andExpression = new ExpressionDef.And(
            ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue()),
            ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue())
        );
        String result = writeMethodWithExpression(andExpression);

        assertEquals("(true || false) && (true || false)", result);
    }

    @Test
    public void returnOrCondition() throws IOException {
        ExpressionDef orExpression = new ExpressionDef.Or(
            ExpressionDef.trueValue().isTrue(),
            new VariableDef.Local("field", TypeDef.Primitive.BOOLEAN).isTrue()
        );
        String result = writeMethodWithExpression(orExpression);

        assertEquals("(true || field)", result);
    }

    @Test
    public void returnOrConditionWithParentheses() throws IOException {
        ExpressionDef orExpression = new ExpressionDef.Or(
            ExpressionDef.trueValue().isTrue().and(ExpressionDef.falseValue().isTrue()),
            ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue())
        );
        String result = writeMethodWithExpression(orExpression);

        assertEquals("(true && false || (true || false))", result);
    }

    @Test
    public void returnPrimitiveInitialization() throws IOException {
        ExpressionDef intExpression = ExpressionDef.constant(0);
        String result = writeMethodWithExpression(intExpression);

        assertEquals("0", result);
    }

    @Test
    public void returnPrimitiveInitialization2() throws IOException {
        ExpressionDef intExpression = TypeDef.Primitive.INT.constant(0);
        String result = writeMethodWithExpression(intExpression);

        assertEquals("0", result);
    }

    @Test
    public void stringConcatenation() throws IOException {
        ExpressionDef concat = ExpressionDef.constant("Hello ")
            .stringConcat(ExpressionDef.constant(1));
        String result = writeMethodWithExpression(concat);

        assertEquals("\"Hello \" + 1", result);

        concat = concat.stringConcat(ExpressionDef.constant("Welcome!"));
        result = writeMethodWithExpression(concat);

        assertEquals("\"Hello \" + 1 + \"Welcome!\"", result);
    }

}
