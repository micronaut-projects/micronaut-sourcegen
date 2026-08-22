package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Cast;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExpressionWriteTest extends AbstractWriteTest {

    private static final ClassTypeDef STRING = ClassTypeDef.STRING;

    @Test
    void testSuperConstructorCall() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyList")
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(MethodDef.constructor().build((aThis, methodParameters) ->
                aThis.superRef().invokeSuperConstructor()))
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;
import java.util.AbstractList;

class MyList extends AbstractList<String> {
  MyList() {
    super();
  }
}
            """, data);
    }

    @Test
    void testSuperConstructorCall2() throws Exception {
        Constructor<AbstractList> constructor = AbstractList.class.getDeclaredConstructor();
        ClassDef classDef = ClassDef.builder("test.MyList")
            .superclass(TypeDef.parameterized(AbstractList.class, String.class))
            .addMethod(MethodDef.constructor().build((aThis, methodParameters) ->
                aThis.superRef().invokeSuperConstructor(constructor))
            )
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;
import java.util.AbstractList;

class MyList extends AbstractList<String> {
  MyList() {
    super();
  }
}
            """, data);
    }

    @Test
    void testEliminatePrimitiveCast() throws IOException {
        MethodDef getIntegerValue = MethodDef.builder("getIntegerValue")
            .returns(Object.class)
            .build((aThis, methodParameters) ->
                ExpressionDef.constant(123).returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(Object.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef newInt = aThis.invoke(getIntegerValue);
                    ExpressionDef newExpression = newInt.cast(TypeDef.Primitive.INT);
                    return StatementDef.multi(
                        newExpression.cast(TypeDef.OBJECT).isNull().doIf(ExpressionDef.constant(0).returning()),
                        newExpression.returning()
                    );
                })
            )
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.Object;
import java.util.function.Predicate;

class MyClass implements Predicate {
  int test(Object arg1) {
    if (this.getIntegerValue() == null) {
      return 0;
    }
    return (int) this.getIntegerValue();
  }
}
            """, data);
    }

    @Test
    void testNullCheckEliminatesCast() throws IOException {
        MethodDef getIntegerValue = MethodDef.builder("getIntegerValue")
            .returns(Object.class)
            .build((aThis, methodParameters) ->
                ExpressionDef.constant(123).returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(Object.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef newInt = aThis.invoke(getIntegerValue);
                    ExpressionDef newExpression = newInt.cast(TypeDef.Primitive.INT);
                    return StatementDef.multi(
                        newExpression.isNull().doIf(ExpressionDef.constant(0).returning()),
                        newExpression.returning()
                    );
                })
            )
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.Object;
import java.util.function.Predicate;

class MyClass implements Predicate {
  int test(Object arg1) {
    if (this.getIntegerValue() == null) {
      return 0;
    }
    return (int) this.getIntegerValue();
  }
}
            """, data);
    }

    @Test
    void testNotNullCheckEliminatesCast() throws IOException {
        MethodDef getIntegerValue = MethodDef.builder("getIntegerValue")
            .returns(Object.class)
            .build((aThis, methodParameters) ->
                ExpressionDef.constant(123).returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(Object.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef newInt = aThis.invoke(getIntegerValue);
                    ExpressionDef newExpression = newInt.cast(TypeDef.Primitive.INT);
                    return StatementDef.multi(
                        newExpression.isNonNull().doIf(ExpressionDef.constant(0).returning()),
                        newExpression.returning()
                    );
                })
            )
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.Object;
import java.util.function.Predicate;

class MyClass implements Predicate {
  int test(Object arg1) {
    if (this.getIntegerValue() != null) {
      return 0;
    }
    return (int) this.getIntegerValue();
  }
}
            """, data);
    }

    @Test
    void testInstanceOfEliminatesCast() throws IOException {
        MethodDef getIntegerValue = MethodDef.builder("getIntegerValue")
            .returns(Object.class)
            .build((aThis, methodParameters) ->
                ExpressionDef.constant(123).returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(Object.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef newInt = aThis.invoke(getIntegerValue);
                    ExpressionDef newExpression = newInt.cast(TypeDef.Primitive.INT);
                    return StatementDef.multi(
                        newExpression.instanceOf(TypeDef.STRING).doIf(ExpressionDef.constant(0).returning()),
                        newExpression.returning()
                    );
                })
            )
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.Object;
import java.util.function.Predicate;

class MyClass implements Predicate {
  int test(Object arg1) {
    if (this.getIntegerValue() instanceof java.lang.String) {
      return 0;
    }
    return (int) this.getIntegerValue();
  }
}
            """, data);
    }

    @Test
    void testEliminateRefCompareCast() throws IOException {
        MethodDef getIntegerValue = MethodDef.builder("getIntegerValue")
            .returns(Object.class)
            .build((aThis, methodParameters) ->
                ExpressionDef.constant(123).returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(Object.class)
                .returns(int.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef newInt = aThis.invoke(getIntegerValue);
                    ExpressionDef newExpression = newInt.cast(TypeDef.Primitive.INT);
                    return StatementDef.multi(
                        newExpression.equalsReferentially(ExpressionDef.constant("Hello")).doIf(ExpressionDef.constant(0).returning()),
                        newExpression.returning()
                    );
                })
            )
            .build();

        String data = writeClass(classDef);

        Assertions.assertEquals("""
package test;

import java.lang.Object;
import java.util.function.Predicate;

class MyClass implements Predicate {
  int test(Object arg1) {
    if (this.getIntegerValue() == "Hello") {
      return 0;
    }
    return (int) this.getIntegerValue();
  }
}
            """, data);
    }

    @Test
    void testObjectCastEliminatesFromReferenceChecks() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", String.class)
                .addParameter("other", String.class)
                .returns(boolean.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef value = methodParameters.get(0).cast(TypeDef.OBJECT);
                    ExpressionDef other = methodParameters.get(1).cast(TypeDef.OBJECT);
                    return value.equalsReferentially(other)
                        .and(value.isNonNull())
                        .and(value.notEqualsReferentially(ExpressionDef.constant("ignored").cast(TypeDef.OBJECT)))
                        .returning();
                })
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  boolean test(String value, String other) {
    return value == other && value != null && value != "ignored";
  }
}
            """, data);
    }

    @Test
    void testObjectCastRetainsForPrimitiveReferenceChecks() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", int.class)
                .returns(boolean.class)
                .build((aThis, methodParameters) -> {
                    ExpressionDef value = methodParameters.get(0);
                    return value.equalsReferentially(ExpressionDef.constant(1))
                        .and(value.isNonNull())
                        .and(value.instanceOf(ClassTypeDef.of(Integer.class)))
                        .returning();
                })
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;

class MyClass {
  boolean test(int value) {
    return value == 1 && (Object) value != null && (Object) value instanceof java.lang.Integer;
  }
}
            """, data);
    }

    @Test
    void testDefineLocalWithCastedNullEliminatesCast() throws IOException {
        TypeDef listOfStrings = TypeDef.parameterized(List.class, String.class);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addStatement(StatementDef.multi(
                    new VariableDef.Local("propertyValue0", TypeDef.STRING)
                        .defineAndAssign(ExpressionDef.nullValue().cast(TypeDef.STRING)),
                    new VariableDef.Local("propertyValue1", listOfStrings)
                        .defineAndAssign(ExpressionDef.nullValue().cast(listOfStrings))
                ))
                .build())
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;
import java.util.List;

class MyClass {
  void test() {
    String propertyValue0 = null;
    List<String> propertyValue1 = null;
  }
}
            """, data);
    }

    @Test
    void testMethodArgumentWithCastedNullEliminatesCast() throws IOException {
        MethodDef acceptValue = MethodDef.builder("acceptValue")
            .addParameter("value", TypeDef.STRING)
            .build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(acceptValue)
            .addMethod(MethodDef.builder("test")
                .build((aThis, methodParameters) ->
                    aThis.invoke(acceptValue, ExpressionDef.nullValue().cast(TypeDef.STRING))))
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  void acceptValue(String value) {
  }

  void test() {
    this.acceptValue(null);
  }
}
            """, data);
    }

    @Test
    void testDefineLocalWithTwiceCastedNullEliminatesCasts() throws IOException {
        TypeDef listOfStrings = TypeDef.parameterized(List.class, String.class);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addStatement(StatementDef.multi(
                    new VariableDef.Local("propertyValue0", TypeDef.STRING)
                        .defineAndAssign(ExpressionDef.nullValue().cast(TypeDef.OBJECT).cast(TypeDef.STRING)),
                    new VariableDef.Local("propertyValue1", listOfStrings)
                        .defineAndAssign(ExpressionDef.nullValue().cast(TypeDef.OBJECT).cast(listOfStrings))
                ))
                .build())
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;
import java.util.List;

class MyClass {
  void test() {
    String propertyValue0 = null;
    List<String> propertyValue1 = null;
  }
}
            """, data);
    }

    @Test
    void testMethodArgumentWithTwiceCastedNullEliminatesCasts() throws IOException {
        MethodDef acceptValue = MethodDef.builder("acceptValue")
            .addParameter("value", TypeDef.STRING)
            .build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(acceptValue)
            .addMethod(MethodDef.builder("test")
                .build((aThis, methodParameters) ->
                    aThis.invoke(acceptValue, ExpressionDef.nullValue().cast(TypeDef.OBJECT).cast(TypeDef.STRING))))
            .build();

        String data = writeClass(classDef);
        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  void acceptValue(String value) {
  }

  void test() {
    this.acceptValue(null);
  }
}
            """, data);
    }

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
    void writeStatementSwitchRulesDoNotFallThrough() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        VariableDef.Local result = new VariableDef.Local("result", intType);
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant("abc"), result.assign(intType.constant(1)));
        cases.put(ExpressionDef.constant("xyz"), result.assign(intType.constant(2)));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("param", String.class)
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    result.defineAndAssign(intType.constant(0)),
                    methodParameters.get(0).asStatementSwitch(
                        intType,
                        cases,
                        result.assign(intType.constant(3))
                    ),
                    result.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  int test(String param) {
    int result = 0;
    switch (param) {
      case "abc" -> {
        result = 1;
      }
      case "xyz" -> {
        result = 2;
      }
      default -> {
        result = 3;
      }
    }
    return result;
  }
}
""", data);
    }

    @Test
    void writeStatementSwitchWithoutDefaultPreservesCallerCaseOrder() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        VariableDef.Local result = new VariableDef.Local("result", intType);
        Map<ExpressionDef.Constant, StatementDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), result.assign(intType.constant(10)));
        cases.put(ExpressionDef.constant(0), result.assign(intType.constant(20)));
        cases.put(ExpressionDef.constant(3), result.assign(intType.constant(30)));
        cases.put(ExpressionDef.constant(2), result.assign(intType.constant(40)));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", intType)
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    result.defineAndAssign(intType.constant(-1)),
                    methodParameters.get(0).asStatementSwitch(intType, cases),
                    result.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

class MyClass {
  int test(int value) {
    int result = -1;
    switch (value) {
      case 1 -> {
        result = 10;
      }
      case 0 -> {
        result = 20;
      }
      case 3 -> {
        result = 30;
      }
      case 2 -> {
        result = 40;
      }
    }
    return result;
  }
}
""", data);
    }

    @Test
    void writeExpressionSwitchAlignment() throws IOException {
        TypeDef resultType = TypeDef.of(Integer.class);
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant("abc"), ExpressionDef.constant(1));
        cases.put(ExpressionDef.constant("xyz"), ExpressionDef.constant(2));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("param", String.class)
                .returns(resultType)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .asExpressionSwitch(resultType, cases, ExpressionDef.constant(3))
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Integer;
import java.lang.String;

class MyClass {
  Integer test(String param) {
    return switch (param) {
      case "abc" -> 1;
      case "xyz" -> 2;
      default -> 3;
    };
  }
}
""", data);
    }

    @Test
    void writeExpressionSwitchCaseWithMultipleYields() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("param1", String.class)
                .addParameter("param2", intType)
                .returns(intType)
                .build((aThis, methodParameters) -> {
                    cases.put(ExpressionDef.constant("abc"), yieldWithConditionalBranch(intType, methodParameters.get(1), 1, 11, 12));
                    cases.put(ExpressionDef.constant("xyz"), yieldWithConditionalBranch(intType, methodParameters.get(1), 2, 22, 23));
                    return methodParameters.get(0)
                        .asExpressionSwitch(
                            intType,
                            cases,
                            yieldWithConditionalBranch(intType, methodParameters.get(1), 3, 33, 34)
                        )
                        .returning();
                })
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  int test(String param1, int param2) {
    return switch (param1) {
      case "abc" -> {
        if (param2 == 1) {
          yield 11;
        }
        yield 12;
      }
      case "xyz" -> {
        if (param2 == 2) {
          yield 22;
        }
        yield 23;
      }
      default -> {
        if (param2 == 3) {
          yield 33;
        }
        yield 34;
      }
    };
  }
}
""", data);
    }

    @Test
    void writeExpressionSwitchCaseWithIfElseYields() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("param1", String.class)
                .addParameter("param2", intType)
                .returns(intType)
                .build((aThis, methodParameters) -> {
                    cases.put(ExpressionDef.constant("abc"), new ExpressionDef.SwitchYieldCase(
                        intType,
                        methodParameters.get(1).compare(EQUAL_TO, intType.constant(1))
                            .ifTrue(
                                intType.constant(11).returning(),
                                intType.constant(12).returning()
                            )
                    ));
                    return methodParameters.get(0)
                        .asExpressionSwitch(intType, cases, intType.constant(0))
                        .returning();
                })
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  int test(String param1, int param2) {
    return switch (param1) {
      case "abc" -> {
        if (param2 == 1) {
          yield 11;
        } else {
          yield 12;
        }
      }
      default -> 0;
    };
  }
}
""", data);
    }

    @Test
    void writeTryCatchFinallySpacing() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        VariableDef.Local result = new VariableDef.Local("result", intType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    result.defineAndAssign(intType.constant(0)),
                    result.assign(intType.constant(1))
                        .doTry()
                        .doCatch(RuntimeException.class, exception -> result.assign(intType.constant(2)))
                        .doFinally(result.assign(intType.constant(3))),
                    result.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        Assertions.assertFalse(data.contains(";\n\n    } catch"));
        Assertions.assertFalse(data.contains(";\n\n    } finally"));
        assertEquals("""
package test;

import java.lang.RuntimeException;

class MyClass {
  int test() {
    int result = 0;
    try {
      result = 1;
    } catch (RuntimeException e0) {
      result = 2;
    } finally {
      result = 3;
    }
    return result;
  }
}
""", data);
    }

    @Test
    void writeSynchronizedBlock() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        VariableDef.Local result = new VariableDef.Local("result", intType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    result.defineAndAssign(intType.constant(0)),
                    new StatementDef.Synchronized(aThis, result.assign(intType.constant(1))),
                    result.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

class MyClass {
  int test() {
    int result = 0;
    synchronized (this) {
      result = 1;
    }
    return result;
  }
}
""", data);
    }

    @Test
    void writeSynchronizedBlockWithReturn() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .returns(intType)
                .build((aThis, methodParameters) ->
                    new StatementDef.Synchronized(aThis, intType.constant(1).returning()))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

class MyClass {
  int test() {
    synchronized (this) {
      return 1;
    }
  }
}
""", data);
    }

    @Test
    void writeTryMultipleCatchFinally() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        VariableDef.Local result = new VariableDef.Local("result", intType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    result.defineAndAssign(intType.constant(0)),
                    result.assign(intType.constant(1))
                        .doTry()
                        .doCatch(RuntimeException.class, exception -> result.assign(intType.constant(2)))
                        .doCatch(IOException.class, exception -> result.assign(intType.constant(3)))
                        .doFinally(result.assign(intType.constant(4))),
                    result.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.io.IOException;
import java.lang.RuntimeException;

class MyClass {
  int test() {
    int result = 0;
    try {
      result = 1;
    } catch (RuntimeException e0) {
      result = 2;
    } catch (IOException e1) {
      result = 3;
    } finally {
      result = 4;
    }
    return result;
  }
}
""", data);
    }

    @Test
    void writeTryMultipleCatchFinallyWithReturn() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .returns(intType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    intType.constant(1)
                        .returning()
                        .doTry()
                        .doCatch(RuntimeException.class, exception -> intType.constant(2).returning())
                        .doCatch(IOException.class, exception -> intType.constant(3).returning())
                        .doFinally(intType.constant(4).returning())
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.io.IOException;
import java.lang.RuntimeException;

class MyClass {
  int test() {
    try {
      return 1;
    } catch (RuntimeException e0) {
      return 2;
    } catch (IOException e1) {
      return 3;
    } finally {
      return 4;
    }
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
    return new Object[]{arg1 == arg2,arg1 != arg2,arg1 > arg2,arg1 < arg2,arg1 >= arg2,arg1 <= arg2,(Object) arg1 == null,(Object) arg1 != null};
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
        ExpressionDef stringArray = new ExpressionDef.Constant(TypeDef.array(ClassTypeDef.of(String.class)),
            new String[] {"hello", "world"});
        String result = writeMethodWithExpression(stringArray);

        assertEquals("new String[] {\"hello\", \"world\"}", result);
    }

    @Test
    public void returnConstantIntegerArray() throws IOException {
        ExpressionDef integerArray = new ExpressionDef.Constant(TypeDef.array(ClassTypeDef.of(Integer.class)),
            new Integer[] {1, 2});
        String result = writeMethodWithExpression(integerArray);

        assertEquals("new Integer[] {1, 2}", result);
    }

    @Test
    public void returnConstantIntArray() throws IOException {
        ExpressionDef integerArray = new ExpressionDef.Constant(TypeDef.array(TypeDef.primitive(Integer.TYPE)),
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
    void returnCastedConditionWithParentheses() throws IOException {
        ExpressionDef castedExpression = ExpressionDef.constant(1)
            .compare(LESS_THAN, ExpressionDef.constant(2))
            .cast(TypeDef.OBJECT);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Object) (1 < 2)", result);
    }

    @Test
    void returnCastedIfElseWithParentheses() throws IOException {
        ExpressionDef castedExpression = ExpressionDef.trueValue()
            .ifTrue(ExpressionDef.constant("yes"), ExpressionDef.constant("no"))
            .cast(TypeDef.OBJECT);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Object) (true ? \"yes\" : \"no\")", result);
    }

    @Test
    void returnCastedMathOperationWithParentheses() throws IOException {
        ExpressionDef castedExpression = ExpressionDef.constant(1)
            .math(ADDITION, ExpressionDef.constant(2))
            .cast(TypeDef.Primitive.LONG);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(long) (1 + 2)", result);
    }

    @Test
    void returnNestedMathOperationWithParentheses() throws IOException {
        ExpressionDef expression = ExpressionDef.constant(1)
            .math(MULTIPLICATION, ExpressionDef.constant(2)
                .math(ADDITION, ExpressionDef.constant(3)));
        String result = writeMethodWithExpression(expression);

        assertEquals("1 * (2 + 3)", result);
    }

    @Test
    void returnRightNestedMathOperationWithSamePrecedenceParentheses() throws IOException {
        ExpressionDef expression = ExpressionDef.constant(1)
            .math(SUBTRACTION, ExpressionDef.constant(2)
                .math(SUBTRACTION, ExpressionDef.constant(3)));
        String result = writeMethodWithExpression(expression);

        assertEquals("1 - (2 - 3)", result);
    }

    @Test
    void returnBinaryComparisonWithMathOperandsParentheses() throws IOException {
        ExpressionDef expression = ExpressionDef.constant(1)
            .math(ADDITION, ExpressionDef.constant(2))
            .compare(LESS_THAN, ExpressionDef.constant(3)
                .math(SUBTRACTION, ExpressionDef.constant(4)));
        String result = writeMethodWithExpression(expression);

        assertEquals("(1 + 2) < (3 - 4)", result);
    }

    @Test
    void returnCastedNestedMathOperationWithParentheses() throws IOException {
        ExpressionDef castedExpression = ExpressionDef.constant(1)
            .math(MULTIPLICATION, ExpressionDef.constant(2)
                .math(ADDITION, ExpressionDef.constant(3)))
            .cast(TypeDef.Primitive.LONG);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(long) (1 * (2 + 3))", result);
    }

    @Test
    void returnCastedStringConcatenationWithParentheses() throws IOException {
        ExpressionDef castedExpression = ExpressionDef.constant("value: ")
            .stringConcat(ExpressionDef.constant(1))
            .cast(TypeDef.OBJECT);
        String result = writeMethodWithExpression(castedExpression);

        assertEquals("(Object) (\"value: \" + 1)", result);
    }

    @Test
    void returnSwitchWithoutCast() throws IOException {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), ExpressionDef.constant("one"));
        cases.put(ExpressionDef.constant(2), ExpressionDef.constant("two"));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .asExpressionSwitch(TypeDef.OBJECT, cases, ExpressionDef.constant("other"))
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;

class MyClass {
  Object test(int value) {
    return switch (value) {
      case 1 -> "one";
      case 2 -> "two";
      default -> "other";
    };
  }
}
""", data);
    }

    @Test
    void returnStringSwitchWithoutCast() throws IOException {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), ExpressionDef.constant("one"));
        cases.put(ExpressionDef.constant(2), ExpressionDef.constant("two"));

        ClassDef classDef = ClassDef.builder("test.CastedSwitch")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("other"))
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class CastedSwitch {
  String test(int value) {
    return switch (value) {
      case 1 -> "one";
      case 2 -> "two";
      default -> "other";
    };
  }
}
""", data);
    }

    @Test
    void invokeMethodOnCastedExpressionWithParentheses() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .cast(TypeDef.STRING)
                    .invoke("trim", TypeDef.STRING)
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;
import java.lang.String;

class MyClass {
  String test(Object value) {
    return ((String) value).trim();
  }
}
""", data);
    }

    @Test
    void invokeMethodOnIfElseExpressionWithParentheses() throws IOException {
        ExpressionDef expression = ExpressionDef.trueValue()
            .ifTrue(ExpressionDef.constant(" yes "), ExpressionDef.constant(" no "))
            .invoke("trim", TypeDef.STRING);
        String result = writeMethodWithExpression(expression);

        assertEquals("(true ? \" yes \" : \" no \").trim()", result);
    }

    @Test
    void invokeMethodOnStringConcatenationWithParentheses() throws IOException {
        ExpressionDef expression = ExpressionDef.constant("value: ")
            .stringConcat(ExpressionDef.constant(1))
            .invoke("trim", TypeDef.STRING);
        String result = writeMethodWithExpression(expression);

        assertEquals("(\"value: \" + 1).trim()", result);
    }

    @Test
    void invokeMethodOnSwitchExpressionWithParentheses() throws IOException {
        Map<ExpressionDef.Constant, ExpressionDef> cases = new LinkedHashMap<>();
        cases.put(ExpressionDef.constant(1), ExpressionDef.constant("one"));
        cases.put(ExpressionDef.constant(2), ExpressionDef.constant("two"));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(MethodDef.builder("test")
                .addParameter("value", TypeDef.Primitive.INT)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .asExpressionSwitch(TypeDef.STRING, cases, ExpressionDef.constant("other"))
                    .invoke("trim", TypeDef.STRING)
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

class MyClass {
  String test(int value) {
    return (switch (value) {
      case 1 -> "one";
      case 2 -> "two";
      default -> "other";
    }).trim();
  }
}
""", data);
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

        assertEquals("true || field", result);
    }

    @Test
    public void returnOrConditionWithParentheses() throws IOException {
        ExpressionDef orExpression = new ExpressionDef.Or(
            ExpressionDef.trueValue().isTrue().and(ExpressionDef.falseValue().isTrue()),
            ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue())
        );
        String result = writeMethodWithExpression(orExpression);

        assertEquals("true && false || true || false", result);
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

    @Test
    public void arrayElementByConstantIndex() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("args", TypeDef.OBJECT.array())
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> methodParameters.get(0).arrayElement(0).returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;

public class MyClass {
  public Object run(Object[] args) {
    return args[0];
  }
}
            """, data);
    }

    @Test
    public void arrayElementByExpressionIndex() throws IOException {
        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("args", TypeDef.OBJECT.array())
                .addParameter("i", intType)
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .arrayElement(methodParameters.get(1).math(ADDITION, intType.constant(1)))
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;

public class MyClass {
  public Object run(Object[] args, int i) {
    return args[i + 1];
  }
}
            """, data);
    }

    @Test
    public void arrayElementOfCast() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("o", TypeDef.OBJECT)
                .returns(STRING)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .cast(TypeDef.array(STRING))
                    .arrayElement(0)
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;
import java.lang.String;

public class MyClass {
  public String run(Object o) {
    return ((String[]) o)[0];
  }
}
            """, data);
    }

    @Test
    public void arrayElementOfMethodCall() throws IOException {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("values")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.array(STRING))
                .build((aThis, methodParameters) -> ExpressionDef.nullValue().returning())
            )
            .addMethod(MethodDef.builder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .build((aThis, methodParameters) -> aThis
                    .invoke("values", TypeDef.array(STRING))
                    .arrayElement(0)
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String[] values() {
    return null;
  }

  public String run() {
    return this.values()[0];
  }
}
            """, data);
    }

    @Test
    public void nestedLambdasCanBeGivenDistinctParameterNames() throws IOException {
        ClassTypeDef nestedType = ClassTypeDef.of("test.Nested");
        InterfaceDef nestedDef = InterfaceDef.builder("test.Nested")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("context", TypeDef.STRING)
                .returns(nestedType)
                .build())
            .build();
        ClassTypeDef lambdaType = nestedDef.asTypeDef();

        ExpressionDef.Lambda inner = lambdaType.getLambda()
            .implement(List.of("innerContext"), (aThis, params) -> ExpressionDef.nullValue().returning());
        ExpressionDef.Lambda outer = lambdaType.getLambda()
            .implement(List.of("outerContext"), (aThis, params) -> inner.returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> outer.returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class MyClass {
  public Nested evaluate() {
    return (outerContext) -> (innerContext) -> null;
  }
}
            """, data);
    }

    @Test
    public void lambdaParameterNamesAreValidatedAgainstTheArity() {
        InterfaceDef functionDef = InterfaceDef.builder("test.StringFunction")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("context", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build())
            .build();

        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> functionDef.asTypeDef().getLambda()
                .implement(List.of("a", "b"), (aThis, params) -> params.get(0).returning())
        );

        assertEquals("Lambda method apply has 1 parameter(s) but 2 name(s) were provided", e.getMessage());
    }

    private static ExpressionDef.SwitchYieldCase yieldWithConditionalBranch(TypeDef.Primitive intType,
                                                                            ExpressionDef conditionValue,
                                                                            int expectedValue,
                                                                            int matchingResult,
                                                                            int fallbackResult) {
        return new ExpressionDef.SwitchYieldCase(
            intType,
            StatementDef.multi(
                conditionValue.compare(EQUAL_TO, intType.constant(expectedValue))
                    .ifTrue(intType.constant(matchingResult).returning()),
                intType.constant(fallbackResult).returning()
            )
        );
    }

}
