package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every entry point that builds a method reference, and what each one renders to. Doubles as the
 * worked example set for the API.
 */
public class MethodReferenceApiTest extends AbstractWriteTest {

    private static final ClassTypeDef OWNER = ClassTypeDef.of("test.Owner");

    /** {@code String apply(String)}. */
    private static ClassTypeDef stringFunction() {
        return functionalInterface("test.StringFunction", TypeDef.STRING, TypeDef.STRING);
    }

    /** {@code Integer apply(String)}. */
    private static ClassTypeDef stringToInteger() {
        return functionalInterface("test.StringToInteger", ClassTypeDef.of(Integer.class), TypeDef.STRING);
    }

    /** {@code String apply()}. */
    private static ClassTypeDef stringSupplier() {
        return functionalInterface("test.StringSupplier", TypeDef.STRING);
    }

    private static ClassTypeDef functionalInterface(String name, TypeDef returns, TypeDef... parameters) {
        MethodDef.MethodDefBuilder method = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(returns);
        for (int i = 0; i < parameters.length; i++) {
            method.addParameter("arg" + i, parameters[i]);
        }
        return InterfaceDef.builder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method.build())
            .build()
            .asTypeDef();
    }

    /** A static {@code String shout(String)} declared on {@link #OWNER}. */
    private static MethodDef shout() {
        return MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
    }

    /** An instance {@code String decorate()} declared on {@link #OWNER}. */
    private static MethodDef decorate() {
        return MethodDef.builder("decorate")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build();
    }

    /** An instance {@code String concat(String)} declared on {@link String}. */
    private static MethodDef concat() {
        return MethodDef.builder("concat")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("other", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
    }

    /**
     * Renders just the reference itself, by putting it in a return statement and taking the expression.
     */
    private static String expression(MethodReferenceExpression reference) throws IOException {
        String source = writeClass(ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(reference.type())
                .build((aThis, params) -> reference.returning()))
            .build());
        return source.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("return "))
            .findFirst()
            .orElseThrow()
            .replaceFirst("^return ", "")
            .replaceFirst(";$", "");
    }

    // --------------------------------------------------- resolving a method

    // ---------------------------------------------------------------- static

    @Test
    public void staticReferences() throws Exception {
        ClassTypeDef fn = stringFunction();

        // this = the owner, the interface goes in as an argument
        assertEquals("Owner::shout", expression(fn.staticMethodReference(OWNER, shout())));
        assertEquals("String::copyValueOf", expression(fn.staticMethodReference(TypeDef.STRING, "copyValueOf")));

        // reflective - the owner comes from the method, and so does whether it is static
        assertEquals("Integer::valueOf", expression(MethodReferenceExpression.of(
            stringToInteger(), Integer.class.getMethod("valueOf", String.class))));
    }

    @Test
    public void aReflectiveInstanceMethodIsRejectedAsStatic() throws Exception {
        assertEquals("Method java.lang.String#trim is not static; bind an instance method to a receiver instead",
            assertThrows(IllegalArgumentException.class,
                () -> MethodReferenceExpression.of(stringFunction(), String.class.getMethod("trim"))).getMessage());
    }

    // -------------------------------------------------------------- instance

    @Test
    public void instanceReferences() throws Exception {
        ClassTypeDef fn = stringFunction();
        ExpressionDef receiver = ExpressionDef.constant("prefix_");

        // this = the interface, the receiver goes in as an argument
        assertEquals("\"prefix_\"::concat", expression(fn.methodReference(receiver, concat())));
        assertEquals("\"prefix_\"::concat", expression(fn.methodReference(receiver, "concat")));
        assertEquals("\"prefix_\"::concat",
            expression(fn.methodReference(receiver, String.class.getMethod("concat", String.class))));
    }

    @Test
    public void theReceiverIsAnyExpression() throws IOException {
        assertEquals("this::decorate",
            expression(stringSupplier().methodReference(new VariableDef.This(), decorate())));

        VariableDef.Local local = new VariableDef.Local("greeting", TypeDef.STRING);
        assertEquals("greeting::concat", expression(stringFunction().methodReference(local, "concat")));

        // A receiver that is not a primary expression is parenthesised
        ExpressionDef conditional = new ExpressionDef.IfElse(
            ExpressionDef.trueValue(), ExpressionDef.constant("a"), ExpressionDef.constant("b"));
        assertEquals("(true ? \"a\" : \"b\")::concat",
            expression(stringFunction().methodReference(conditional, "concat")));
    }

    // ----------------------------------------------------------- constructor

    @Test
    public void constructorReferences() throws Exception {
        ClassTypeDef supplier = stringSupplier();
        MethodDef noArgs = MethodDef.constructor().addModifiers(Modifier.PUBLIC).build();

        assertEquals("Owner::new", expression(supplier.constructorReference(OWNER, noArgs)));
        // Resolved by the arity of the interface, so only when one constructor matches
        assertEquals("String::new", expression(supplier.constructorReference(TypeDef.STRING)));
        // reflective
        assertEquals("String::new", expression(MethodReferenceExpression.of(supplier, String.class.getConstructor())));
    }

    // ---------------------------------------------- resolving and validating

    @Test
    public void theByNameConveniencesResolveAgainstTheType() {
        ClassTypeDef fn = stringFunction();
        ExpressionDef receiver = ExpressionDef.constant("prefix_");

        // Several methods can share a name and arity, so findDeclaredMethods returns all of them
        assertEquals(1, TypeDef.STRING.findDeclaredMethods("concat", 1).size());
        assertTrue(TypeDef.STRING.findDeclaredMethods("valueOf", 1).size() > 1);

        // The by-name conveniences have to pick exactly one, and say so when they cannot
        assertEquals("No method java.lang.String#nope accepting 1 argument(s) found",
            assertThrows(IllegalArgumentException.class,
                () -> fn.methodReference(receiver, "nope")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> fn.staticMethodReference(TypeDef.STRING, "valueOf")).getMessage()
            .startsWith("Ambiguous method reference: "));
    }

    @Test
    public void theShapeOfTheReferencedMethodIsValidated() {
        ClassTypeDef fn = stringFunction();
        MethodDef twoArguments = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .addParameter("other", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();

        assertEquals("Method reference test.Owner#shout accepts 2 argument(s) but "
                + "test.StringFunction#apply provides 1",
            assertThrows(IllegalArgumentException.class,
                () -> fn.staticMethodReference(OWNER, twoArguments)).getMessage());

        assertEquals("Static method java.lang.String#shout cannot be referenced on an instance",
            assertThrows(IllegalArgumentException.class,
                () -> fn.methodReference(ExpressionDef.constant("x"), shout())).getMessage());
    }

    @Test
    public void aParameterizedInterfaceResolvesItsOwnTypeVariables() {
        // The arguments of the type resolve T and R, so no map of variables is needed
        ClassTypeDef fn = TypeDef.parameterized(java.util.function.Function.class, String.class, Integer.class);

        assertEquals(ClassTypeDef.of(Integer.class), fn.getLambda().getImplementation().getReturnType());
        assertEquals(TypeDef.STRING, fn.getLambda().getImplementation().getParameters().get(0).getType());

        // The raw type leaves them erased, which is what makes a reference fail to link
        assertEquals(TypeDef.OBJECT,
            ClassTypeDef.of(java.util.function.Function.class).getLambda().getImplementation().getReturnType());
    }

    // ---------------------------------------------------------- what you get

    @Test
    public void theExpressionCarriesTheInterfaceAndTheTargetItPointsAt() {
        ClassTypeDef fn = stringFunction();
        MethodDef shout = shout();
        MethodReferenceExpression reference = fn.staticMethodReference(OWNER, shout);

        assertEquals(fn, reference.type());
        assertEquals("apply", reference.target().getName());
        assertEquals("apply", reference.instantiated().getName());
        assertSame(OWNER, reference.owner());
        assertSame(shout, reference.method());

        // Only a reference bound to an instance carries a receiver
        ExpressionDef receiver = ExpressionDef.constant("x");
        assertSame(receiver, fn.methodReference(receiver, "concat").instance());
    }

    @Test
    public void aReferenceCanBeInvokedLikeALambda() throws IOException {
        ClassTypeDef fn = stringFunction();
        MethodReferenceExpression reference = fn.staticMethodReference(OWNER, shout());
        VariableDef.Local value = new VariableDef.Local("value", TypeDef.STRING);

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, params) -> reference.invoke(value).returning()))
            .build();

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String evaluate() {
    return Owner::shout.apply(value);
  }
}
            """, writeClass(classDef));
    }

    @Test
    public void everyExpressionFormIsItsOwnType() {
        ClassTypeDef fn = stringFunction();

        List<MethodReferenceExpression> all = List.of(
            fn.staticMethodReference(OWNER, shout()),
            fn.methodReference(ExpressionDef.constant("x"), "concat"),
            stringSupplier().constructorReference(OWNER, MethodDef.constructor().build())
        );

        // The implementations are hidden, so the forms are told apart by what they expose
        assertEquals(List.of("static", "instance", "constructor"), all.stream().map(r -> {
            if (r.isStatic()) {
                return "static";
            }
            return r.isConstructor() ? "constructor" : "instance";
        }).toList());
    }

}
