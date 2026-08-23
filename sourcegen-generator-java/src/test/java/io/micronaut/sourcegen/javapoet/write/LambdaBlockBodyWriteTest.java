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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests a lambda whose body is a block rather than a single expression. Such a body renders its own
 * statements, and JavaPoet rejects nesting its statement markers, so the enclosing statement must not
 * be wrapped again.
 */
public class LambdaBlockBodyWriteTest extends AbstractWriteTest {

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

    private static ExpressionDef.Lambda blockBodyLambda(ClassTypeDef lambdaType) {
        VariableDef.Local tmp = new VariableDef.Local("tmp", TypeDef.STRING);
        return lambdaType.getLambda().implement((aThis, params) -> StatementDef.multi(
            tmp.defineAndAssign(params.get(0).invoke("trim", TypeDef.STRING)),
            tmp.returning()
        ));
    }

    private static String writeObject(ObjectDef objectDef) throws IOException {
        try (StringWriter writer = new StringWriter()) {
            new JavaPoetSourceGenerator().write(objectDef, writer);
            return writer.toString();
        }
    }

    @Test
    public void blockBodyLambdaInAReturn() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> blockBodyLambda(lambdaType).returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public StringFunction evaluate() {
    return (context) -> {
      String tmp = context.trim();
      return tmp;
    };
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void singleExpressionLambdaIsUnchanged() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(lambdaType)
                .build((aThis, methodParameters) -> lambdaType.getLambda()
                    .implement((t, params) -> params.get(0).invoke("trim", TypeDef.STRING).returning())
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return (context) -> context.trim();
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void blockBodyLambdaInALocalVariable() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();
        VariableDef.Local function = new VariableDef.Local("function", lambdaType);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    function.defineAndAssign(blockBodyLambda(lambdaType)),
                    function.invoke("apply", TypeDef.STRING, ExpressionDef.constant(" a ")).returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String evaluate() {
    StringFunction function = (context) -> {
      String tmp = context.trim();
      return tmp;
    };
    return function.apply(" a ");
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void blockBodyLambdaAsAMethodArgument() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();

        MethodDef callDef = MethodDef.builder("call")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("function", lambdaType)
            .returns(TypeDef.STRING)
            .build((aThis, methodParameters) -> methodParameters.get(0)
                .invoke("apply", TypeDef.STRING, ExpressionDef.constant(" a "))
                .returning());

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(callDef)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, methodParameters) -> aThis
                    .invoke(callDef, blockBodyLambda(lambdaType))
                    .returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String call(StringFunction function) {
    return function.apply(" a ");
  }

  public String evaluate() {
    return this.call((context) -> {
      String tmp = context.trim();
      return tmp;
    });
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(writeObject(functionDef), data);
    }

    @Test
    public void blockBodyLambdaInsideASingleExpressionLambda() throws IOException {
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
        VariableDef.Local tmp = new VariableDef.Local("tmp", TypeDef.STRING);

        ExpressionDef.Lambda inner = lambdaType.getLambda().implement((aThis, params) -> StatementDef.multi(
            tmp.defineAndAssign(params.get(0).invoke("trim", TypeDef.STRING)),
            ExpressionDef.nullValue().returning()
        ));
        // The outer lambda is a single expression, so the block body is not visible as a nested expression
        ExpressionDef.Lambda outer = lambdaType.getLambda().implement((aThis, params) -> inner.returning());

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

import java.lang.String;

public class MyClass {
  public Nested evaluate() {
    return (context) -> (context1) -> {
      String tmp = context1.trim();
      return null;
    };
  }
}
            """, data);
    }

    @Test
    public void consumerWithAnInvocationBodyAsAMethodArgument() throws Exception {
        // A void functional interface whose body is a statement, not a return - passed as an argument
        Method accept = Consumer.class.getMethod("accept", Object.class);
        Method forEach = Iterable.class.getMethod("forEach", Consumer.class);
        VariableDef.Local sink = new VariableDef.Local("sink", TypeDef.parameterized(List.class, String.class));

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("copy")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("items", TypeDef.parameterized(List.class, String.class))
                .returns(TypeDef.parameterized(List.class, String.class))
                .build((aThis, params) -> StatementDef.multi(
                    sink.defineAndAssign(ClassTypeDef.of(ArrayList.class).instantiate()),
                    params.get(0).invoke(forEach, new ExpressionDef.Lambda(
                        ClassTypeDef.of(Consumer.class),
                        MethodDef.of(accept),
                        MethodDef.builder("accept")
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter("t", TypeDef.OBJECT)
                            .returns(TypeDef.VOID)
                            .build((lt, lp) -> sink.invoke("add", TypeDef.Primitive.BOOLEAN, lp.get(0)))
                    )),
                    sink.returning()
                ))
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;
import java.util.List;

public class MyClass {
  public List<String> copy(List<String> items) {
    List<String> sink = new java.util.ArrayList();
    items.forEach((t) -> {
      sink.add(t);
    });
    return sink;
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(data);
    }

    @Test
    public void nestedBlockBodyLambdas() throws IOException {
        InterfaceDef functionDef = stringFunction();
        ClassTypeDef lambdaType = functionDef.asTypeDef();
        VariableDef.Local inner = new VariableDef.Local("inner", lambdaType);

        ExpressionDef.Lambda outer = lambdaType.getLambda().implement((aThis, params) -> StatementDef.multi(
            inner.defineAndAssign(blockBodyLambda(lambdaType)),
            inner.invoke("apply", TypeDef.STRING, ExpressionDef.constant("a")).returning()
        ));

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

import java.lang.String;

public class MyClass {
  public StringFunction evaluate() {
    return (context) -> {
      StringFunction inner = (context1) -> {
        String tmp = context1.trim();
        return tmp;
      };
      return inner.apply("a");
    };
  }
}
            """, data);
    }

}
