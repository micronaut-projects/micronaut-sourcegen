package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.render;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.single;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.stringMethod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the naming scope of a lambda body: a lambda parameter may not shadow a name that is already
 * in scope, and a lambda body may capture the enclosing method's parameters.
 */
public class LambdaScopeWriteTest extends AbstractWriteTest {

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

        JavaCompileAssertions.assertCompiles(render(nestedDef), data);
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

        JavaCompileAssertions.assertCompiles(render(nestedDef), data);
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

        JavaCompileAssertions.assertCompiles(render(functionDef), data);
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

        JavaCompileAssertions.assertCompiles(render(functionDef), data);
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

        JavaCompileAssertions.assertCompiles(render(functionDef), data);
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

        JavaCompileAssertions.assertCompiles(render(functionDef), data);
    }

    @Test
    public void predicateLambdaCapturesAnEnclosingMethodParameter() throws Exception {
        // The shape from the report: a Predicate whose body uses a parameter of the enclosing method
        Method test = Predicate.class.getMethod("test", Object.class);
        Method filter = Stream.class.getMethod("filter", Predicate.class);
        ClassTypeDef helper = ClassTypeDef.of("test.Helper");

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("context", TypeDef.OBJECT)
                .addParameter("items", TypeDef.parameterized(Stream.class, Object.class))
                .returns(TypeDef.parameterized(Stream.class, Object.class))
                .build((aThis, params) -> {
                    ExpressionDef context = params.get(0);
                    ExpressionDef.Lambda lambda = new ExpressionDef.Lambda(
                        ClassTypeDef.of(Predicate.class),
                        MethodDef.of(test),
                        MethodDef.builder("test")
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter("t", TypeDef.OBJECT)
                            .returns(TypeDef.Primitive.BOOLEAN)
                            .build((lt, lp) -> helper
                                .invokeStatic("check", TypeDef.Primitive.BOOLEAN, context, lp.get(0))
                                .returning())
                    );
                    return params.get(1).invoke(filter, lambda).returning();
                })
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Object;
import java.util.stream.Stream;

public class MyClass {
  public Stream<Object> evaluate(Object context, Stream<Object> items) {
    return items.filter((t) -> Helper.check(context, t));
  }
}
            """, data);
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

        JavaCompileAssertions.assertCompiles(render(functionDef), data);
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

    /**
     * A local of a lambda body named like a local of the enclosing method: a redeclaration in Java ("variable value
     * is already defined"). Only the lambda's parameters are renamed.
     */
    @Test
    void lambdaBodyLocalNamedLikeAnEnclosingLocal() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var lambda = supplier.getLambda().implement((ls, lp) ->
            ExpressionDef.constant("inner").newLocal("value", VariableDef::returning));
        var def = stringMethod("test.LambdaLocalShadow", ExpressionDef.constant("outer").newLocal("value", outer ->
            lambda.newLocal("supplier", s -> s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning())));
        assertEquals("inner", run(def));
    }

    /**
     * A local of a lambda body named like a parameter of the enclosing method.
     */
    @Test
    void lambdaBodyLocalNamedLikeAnEnclosingParameter() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var lambda = supplier.getLambda().implement((ls, lp) ->
            ExpressionDef.constant("inner").newLocal("value", VariableDef::returning));
        var def = ClassDef.builder("test.LambdaParameterShadow").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", String.class)
                .returns(String.class)
                .build((self, p) -> lambda.newLocal("supplier", s -> s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning())))
            .build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("inner", cls.getMethod("call", String.class).invoke(cls.getConstructor().newInstance(), "outer"));
        }
    }

    /**
     * A local captured by a lambda and assigned afterwards: the bytecode writer passes its value at the point the
     * lambda is created, javac requires it to be effectively final.
     */
    @Test
    void localReassignedAfterALambdaCapturesIt() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var def = stringMethod("test.CapturedThenReassigned", ExpressionDef.constant("first").newLocal("value", value -> {
            var local = (VariableDef.Local) value;
            return supplier.getLambda().implement((ls, lp) -> local.returning())
                .newLocal("supplier", s -> StatementDef.multi(
                    local.assign(ExpressionDef.constant("second")),
                    s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING).returning()));
        }));
        assertEquals("first", run(def));
    }

    /**
     * A lambda body assigning a local it captures: the bytecode writer assigns the lambda's copy, javac rejects it.
     */
    @Test
    void lambdaAssigningACapturedLocal() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var def = stringMethod("test.LambdaAssignsCaptured", ExpressionDef.constant("outer").newLocal("value", value -> {
            var local = (VariableDef.Local) value;
            return supplier.getLambda().implement((ls, lp) -> StatementDef.multi(
                    local.assign(ExpressionDef.constant("inner")),
                    local.returning()))
                .newLocal("supplier", s -> s.invoke("get", TypeDef.OBJECT).cast(TypeDef.STRING)
                    .stringConcat(local).returning());
        }));
        assertEquals("innerouter", run(def));
    }

    // A local assigned twice and then captured: the bytecode captures its value, javac rejects a local that is not
    // effectively final.
    @Test
    void lambdaCapturesReassignedLocal() throws Exception {
        var supplier = TypeDef.parameterized(Supplier.class, String.class);
        var text = new VariableDef.Local("text", TypeDef.STRING);
        var get = Supplier.class.getMethod("get");
        var def = single("ReassignedCapture", Object.class, List.of(), (self, p) -> StatementDef.multi(
            text.defineAndAssign(ExpressionDef.constant("a")),
            text.assign(ExpressionDef.constant("b")),
            supplier.getLambda().implement((ls, lp) -> text.returning()).invoke(get).returning()));
        assertEquals("b", run(def));
    }

    @Test
    void renamedLambdaParameterDoesNotCollideWithBodyLocal() throws Exception {
        var function = TypeDef.parameterized(Function.class, String.class, String.class);
        var def = ClassDef.builder("test.LambdaLocalCollision").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("reference").addModifiers(Modifier.PUBLIC).addParameter("value", String.class)
                .returns(function).build((s, p) -> function.getLambda().implement(List.of("value"),
                    (ls, lp) -> ExpressionDef.constant("local").newLocal("value1", local -> lp.getFirst().returning()))
                    .returning())).build();
        try (var loader = compile(def)) {
            var cls = loader.loadClass(def.getName());
            @SuppressWarnings("unchecked")
            var functionValue = (Function<String, String>) cls.getMethod("reference", String.class)
                .invoke(cls.getConstructor().newInstance(), "outer");
            assertEquals("input", functionValue.apply("input"));
        }
    }
}
