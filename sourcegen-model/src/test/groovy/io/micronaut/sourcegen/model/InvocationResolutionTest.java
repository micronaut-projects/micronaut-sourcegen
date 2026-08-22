package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that an invocation built from the arguments alone takes its signature from the declaration when
 * the declaring type is resolvable, instead of from the static types of the arguments - which names a
 * method that does not exist whenever an argument is narrower than the declared parameter.
 */
class InvocationResolutionTest {

    private static final ClassTypeDef SUPPORT = ClassTypeDef.of(Support.class);
    private static final ExpressionDef A_STRING = ExpressionDef.constant("a");
    private static final ExpressionDef B_STRING = ExpressionDef.constant("b");

    private static List<TypeDef> parameterTypes(MethodDef methodDef) {
        return methodDef.getParameters().stream().map(ParameterDef::getType).toList();
    }

    @Test
    void staticCallUsesTheDeclaredParameterTypes() {
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("concat", TypeDef.STRING, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.OBJECT, TypeDef.OBJECT), parameterTypes(call.method()));
        assertEquals(2, call.values().size());
    }

    @Test
    void instanceCallUsesTheDeclaredParameterTypes() {
        ExpressionDef instance = new VariableDef.Local("support", SUPPORT);

        ExpressionDef.InvokeInstanceMethod call = instance.invoke("join", TypeDef.STRING, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.OBJECT, TypeDef.OBJECT), parameterTypes(call.method()));
    }

    @Test
    void variableArityArgumentsArePackedIntoAnArray() {
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("of", TypeDef.STRING, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.OBJECT.array()), parameterTypes(call.method()));
        assertEquals(1, call.values().size());
        ExpressionDef packed = call.values().get(0);
        assertEquals(TypeDef.OBJECT.array(), packed.type());
        assertEquals(2, ((ExpressionDef.NewArrayInitialized) packed).expressions().size());
    }

    @Test
    void variableArityOverloadsAreResolvedWithASingleTrailingArgument() {
        // String.format(String, Object...) and format(Locale, String, Object...) both accept two arguments
        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of(String.class)
            .invokeStatic("format", TypeDef.STRING, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.STRING, TypeDef.OBJECT.array()), parameterTypes(call.method()));
        assertEquals(2, call.values().size());
        assertEquals(TypeDef.OBJECT.array(), call.values().get(1).type());
    }

    @Test
    void aReferenceArgumentDoesNotUnboxToAPrimitiveParameter() {
        // Neither pick(int) nor pick(String) accepts an Object, so the signature stays inferred
        ExpressionDef object = new VariableDef.Local("value", TypeDef.OBJECT);

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("pick", TypeDef.STRING, object);

        assertEquals(List.of(TypeDef.OBJECT), parameterTypes(call.method()));
    }

    @Test
    void aPrimitiveArgumentBoxesToAWiderParameter() {
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("describe", TypeDef.STRING, ExpressionDef.constant(1));

        assertEquals(List.of(TypeDef.of(Number.class)), parameterTypes(call.method()));
    }

    @Test
    void arrayOverloadsAreToldApartByTheComponentType() {
        ExpressionDef strings = new VariableDef.Local("values", TypeDef.STRING.array());

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("first", TypeDef.STRING, strings);

        assertEquals(List.of(TypeDef.STRING.array()), parameterTypes(call.method()));
    }

    @Test
    void anArrayAlreadyPassedForAVariableArityParameterIsLeftAlone() {
        ExpressionDef array = new ExpressionDef.NewArrayInitialized(TypeDef.OBJECT.array(), List.of(A_STRING));

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("of", TypeDef.STRING, array);

        assertEquals(List.of(TypeDef.OBJECT.array()), parameterTypes(call.method()));
        assertEquals(List.of(array), call.values());
    }

    @Test
    void constructorOverloadsAreToldApartByTheArgument() {
        ExpressionDef list = new VariableDef.Local("values", ClassTypeDef.of(List.class));

        ExpressionDef.NewInstance instance = ClassTypeDef.of(ArrayList.class).instantiate(list);

        assertEquals(List.of(TypeDef.of(Collection.class)), instance.parameterTypes());
    }

    @Test
    void aBridgeMethodIsAValidTarget() {
        // ReentrantReadWriteLock covariantly overrides ReadWriteLock#readLock, leaving a bridge returning Lock
        ExpressionDef lock = new VariableDef.Local("lock", ClassTypeDef.of(ReentrantReadWriteLock.class));

        ExpressionDef.InvokeInstanceMethod call = lock.invoke("readLock", TypeDef.of(Lock.class));

        assertEquals(List.of(), parameterTypes(call.method()));
        assertEquals(TypeDef.of(Lock.class), call.method().getReturnType());
    }

    @Test
    void aTypeKnownOnlyByNameKeepsTheInferredSignature() {
        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of("com.example.Support")
            .invokeStatic("concat", TypeDef.STRING, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.STRING, TypeDef.STRING), parameterTypes(call.method()));
    }

    @Test
    void ambiguousOverloadsKeepTheInferredSignature() {
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("ambiguous", TypeDef.STRING, A_STRING);

        assertEquals(List.of(TypeDef.STRING), parameterTypes(call.method()));
    }

    @Test
    void aReturnTypeMatchingNoDeclarationKeepsTheInferredSignature() {
        // The Java writer lets javac resolve this; the bytecode writer reports it
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("concat", TypeDef.OBJECT, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.STRING, TypeDef.STRING), parameterTypes(call.method()));
    }

    @SuppressWarnings("unused")
    static class Support {

        static String concat(Object left, Object right) {
            return String.valueOf(left) + right;
        }

        static String of(Object... elements) {
            return String.valueOf(elements.length);
        }

        static String ambiguous(CharSequence value) {
            return value.toString();
        }

        static String ambiguous(Object value) {
            return String.valueOf(value);
        }

        static String pick(int value) {
            return String.valueOf(value);
        }

        static String pick(String value) {
            return value;
        }

        static String first(String[] values) {
            return values[0];
        }

        static String first(Integer[] values) {
            return String.valueOf(values[0]);
        }

        static String describe(Number value) {
            return String.valueOf(value);
        }

        static String describe(String value) {
            return value;
        }

        String join(Object left, Object right) {
            return concat(left, right);
        }
    }

}
