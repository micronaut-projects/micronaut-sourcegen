package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClassTypeDef.JavaClass#getLambda(java.util.function.Function)}, which resolves the
 * single abstract method of a functional interface reflectively.
 */
class JavaClassLambdaTest {

    @Test
    void resolvesTheSingleAbstractMethod() {
        LambdaDef lambda = ClassTypeDef.of(Function.class).getLambda();

        assertEquals("apply", lambda.getMethod().getName());
        assertEquals(ClassTypeDef.of(Function.class), lambda.getType());
        assertEquals(1, lambda.getImplementation().getParameters().size());
    }

    @Test
    void resolvesTypeVariables() {
        LambdaDef lambda = ClassTypeDef.of(Function.class)
            .getLambda(Map.of("T", TypeDef.STRING, "R", TypeDef.of(Integer.class)));

        MethodDef implementation = lambda.getImplementation();
        assertEquals(TypeDef.STRING, implementation.getParameters().get(0).getType());
        assertEquals(TypeDef.of(Integer.class), implementation.getReturnType());
    }

    @Test
    void unresolvedTypeVariablesFallBackToTheErasure() {
        MethodDef implementation = ClassTypeDef.of(Function.class).getLambda().getImplementation();

        assertEquals(TypeDef.OBJECT, implementation.getParameters().get(0).getType());
        assertEquals(TypeDef.OBJECT, implementation.getReturnType());
    }

    @Test
    void ignoresDefaultAndStaticMethods() {
        LambdaDef lambda = ClassTypeDef.of(WithDefaultAndStaticMethods.class).getLambda();

        assertEquals("run", lambda.getMethod().getName());
    }

    @Test
    void ignoresRedeclaredObjectMethods() {
        // Comparator redeclares Object#equals as abstract
        LambdaDef lambda = ClassTypeDef.of(Comparator.class).getLambda();

        assertEquals("compare", lambda.getMethod().getName());
    }

    @Test
    void failsForMoreThanOneAbstractMethod() {
        ClassTypeDef typeDef = ClassTypeDef.of(TwoAbstractMethods.class);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, typeDef::getLambda);

        assertEquals("Parent of a lambda should have exactly one abstract method but has 2", e.getMessage());
    }

    @Test
    void failsForANameOnlyClassTypeDefWithAHelpfulMessage() {
        ClassTypeDef typeDef = ClassTypeDef.of("com.example.Foo");

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, typeDef::getLambda);

        assertTrue(e.getMessage().contains("com.example.Foo"), e.getMessage());
        assertTrue(e.getMessage().contains("ClassTypeDef.of(Class)"), e.getMessage());
        assertTrue(e.getMessage().contains("ClassTypeDef.of(ClassElement)"), e.getMessage());
    }

    @FunctionalInterface
    interface WithDefaultAndStaticMethods {

        String run(String value);

        default String runTwice(String value) {
            return run(run(value));
        }

        static WithDefaultAndStaticMethods identity() {
            return value -> value;
        }
    }

    interface TwoAbstractMethods {

        String first();

        String second();
    }

}
