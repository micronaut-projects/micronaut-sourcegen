package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.JavaPoetSourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the naming scope of a lambda body: a lambda parameter may not shadow a name that is already
 * in scope, and a lambda body may capture the enclosing method's parameters.
 */
public class LambdaScopeWriteTest extends AbstractWriteTest {

    private static String writeObject(ObjectDef objectDef) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(objectDef, writer);
            return writer.toString();
        }
    }

    /**
     * {@code interface Nested { Nested apply(String context); }} - a functional interface whose method
     * returns the interface itself, so lambdas over it can be nested with single expression bodies.
     */
    private static InterfaceDef nestedFunction() {
        return InterfaceDef.builder("test.Nested")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("context", TypeDef.STRING)
                .returns(ClassTypeDef.of("test.Nested"))
                .build())
            .build();
    }

    private static InterfaceDef stringFunction() {
        return InterfaceDef.builder("test.StringFunction")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("context", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build())
            .build();
    }

    @Test
    public void nestedLambdasGetDistinctParameterNames() throws IOException {
        InterfaceDef nestedDef = nestedFunction();
        ClassTypeDef lambdaType = nestedDef.asTypeDef();

        ExpressionDef.Lambda inner = lambdaType.getLambda()
            .implement((aThis, params) -> ExpressionDef.nullValue().returning());
        ExpressionDef.Lambda outer = lambdaType.getLambda()
            .implement((aThis, params) -> inner.returning());

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
    return (context) -> (context1) -> null;
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(nestedDef), data);
    }

    @Test
    public void threeLevelsOfNestingGetDistinctParameterNames() throws IOException {
        InterfaceDef nestedDef = nestedFunction();
        ClassTypeDef lambdaType = nestedDef.asTypeDef();

        ExpressionDef.Lambda innermost = lambdaType.getLambda()
            .implement((aThis, params) -> ExpressionDef.nullValue().returning());
        ExpressionDef.Lambda middle = lambdaType.getLambda()
            .implement((aThis, params) -> innermost.returning());
        ExpressionDef.Lambda outer = lambdaType.getLambda()
            .implement((aThis, params) -> middle.returning());

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
    return (context) -> (context1) -> (context2) -> null;
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(nestedDef), data);
    }

    @Test
    public void siblingLambdasAreNotRenamed() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        VariableDef.Local first = new VariableDef.Local("first", lambdaType);
        VariableDef.Local second = new VariableDef.Local("second", lambdaType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    first.defineAndAssign(lambdaType.getLambda()
                        .implement((t, params) -> params.get(0).returning())),
                    second.defineAndAssign(lambdaType.getLambda()
                        .implement((t, params) -> params.get(0).returning())),
                    first.invoke("apply", TypeDef.STRING, ExpressionDef.constant("a")).returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String evaluate() {
    StringFunction first = (context) -> context;
    StringFunction second = (context) -> context;
    return first.apply("a");
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void lambdaParameterCollidingWithAnEnclosingMethodParameterIsRenamed() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("context", TypeDef.STRING)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> lambdaType.getLambda()
                    .implement((t, params) -> params.get(0).returning())
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public StringFunction evaluate(String context) {
    return (context1) -> context1;
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void lambdaParameterCollidingWithAnEnclosingLocalIsRenamed() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        VariableDef.Local context = new VariableDef.Local("context", TypeDef.STRING);
        VariableDef.Local function = new VariableDef.Local("function", lambdaType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    context.defineAndAssign(ExpressionDef.constant("a")),
                    function.defineAndAssign(lambdaType.getLambda()
                        .implement((t, params) -> params.get(0).returning())),
                    function.invoke("apply", TypeDef.STRING, context).returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String evaluate() {
    String context = "a";
    StringFunction function = (context1) -> context1;
    return function.apply(context);
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void lambdaCapturesAnEnclosingMethodParameter() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("prefix", TypeDef.STRING)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> lambdaType.getLambda()
                    .implement((t, params) -> methodParameters.get(0)
                        .invoke("concat", TypeDef.STRING, params.get(0))
                        .returning())
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public StringFunction evaluate(String prefix) {
    return (context) -> prefix.concat(context);
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void lambdaCapturesAnEnclosingLocalVariable() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        VariableDef.Local prefix = new VariableDef.Local("prefix", TypeDef.STRING);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    prefix.defineAndAssign(ExpressionDef.constant("prefix_")),
                    lambdaType.getLambda()
                        .implement((t, params) -> prefix
                            .invoke("concat", TypeDef.STRING, params.get(0))
                            .returning())
                        .returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public StringFunction evaluate() {
    String prefix = "prefix_";
    return (context) -> prefix.concat(context);
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void referenceToAnUndeclaredParameterStillFails() {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("prefix", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) ->
                    new VariableDef.MethodParameter("missing", TypeDef.STRING).returning())
            )
            .build();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> writeClass(classDef));

        assertEquals(true, e.getMessage().contains("doesn't have parameter: missing"), e.getMessage());
        assertEquals(true, e.getMessage().contains("evaluate"), e.getMessage());
    }

}
