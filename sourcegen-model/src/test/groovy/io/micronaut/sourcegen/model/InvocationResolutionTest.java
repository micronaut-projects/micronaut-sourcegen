package io.micronaut.sourcegen.model;

import io.micronaut.inject.ast.ClassElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
    void anAstArgumentIsCheckedAgainstAReflectiveParameter() {
        ExpressionDef integer = new VariableDef.Local("value", ClassTypeDef.of(ClassElement.of(Integer.class)));

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("describe", TypeDef.STRING, integer);

        assertEquals(List.of(TypeDef.of(Number.class)), parameterTypes(call.method()));
    }

    @Test
    void aReflectiveArgumentIsCheckedAgainstAParameterKnownByName() {
        // A definition being generated can declare its parameters by name only; Comparable is reached
        // through Integer's interfaces
        ClassTypeDef owner = ClassDef.builder("test.Generated")
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.STATIC)
                .addParameter("value", ClassTypeDef.of("java.lang.Comparable")).returns(TypeDef.STRING).build())
            .addMethod(MethodDef.builder("describe").addModifiers(Modifier.STATIC)
                .addParameter("value", ClassTypeDef.of("java.lang.String")).returns(TypeDef.STRING).build())
            .build()
            .asTypeDef();

        ExpressionDef.InvokeStaticMethod call = owner.invokeStatic("describe", TypeDef.STRING, ExpressionDef.constant(1));

        assertEquals(List.of(ClassTypeDef.of("java.lang.Comparable")), parameterTypes(call.method()));
    }

    @Test
    void anArgumentKnownByNameOnlyCannotNarrowOverloads() {
        ExpressionDef unknown = new VariableDef.Local("value", ClassTypeDef.of("com.example.Unknown"));

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("describe", TypeDef.STRING, unknown);

        assertEquals(List.of(ClassTypeDef.of("com.example.Unknown")), parameterTypes(call.method()));
    }

    @Test
    void anUnboundedTypeVariableErasesToObject() {
        ExpressionDef variable = new VariableDef.Local("value", TypeDef.variable("T"));

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("describe", TypeDef.STRING, variable);

        assertEquals(List.of(TypeDef.variable("T")), parameterTypes(call.method()));
    }

    @Test
    void annotatedTypesAreComparedByTheTypeTheyAnnotate() {
        AnnotationDef deprecated = AnnotationDef.builder(ClassTypeDef.of(Deprecated.class)).build();
        ExpressionDef annotatedClass = new VariableDef.Local("value", ClassTypeDef.of(Integer.class).annotated(deprecated));
        ExpressionDef annotatedPrimitive = new VariableDef.Local("other", TypeDef.Primitive.INT.annotated(deprecated));

        assertEquals(List.of(TypeDef.of(Number.class)),
            parameterTypes(SUPPORT.invokeStatic("describe", TypeDef.STRING, annotatedClass).method()));
        assertEquals(List.of(TypeDef.of(Number.class)),
            parameterTypes(SUPPORT.invokeStatic("describe", TypeDef.STRING, annotatedPrimitive).method()));
    }

    @Test
    void aWildcardArgumentCannotNarrowOverloads() {
        ExpressionDef wildcard = new VariableDef.Local("value", TypeDef.wildcard());

        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("describe", TypeDef.STRING, wildcard);

        assertEquals(List.of(TypeDef.wildcard()), parameterTypes(call.method()));
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
    void overloadsResolveToTheMostSpecificApplicableOne() {
        // As javac selects it: both overloads take a String, and `CharSequence` is the more specific
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("ambiguous", TypeDef.STRING, A_STRING);

        assertEquals(List.of(TypeDef.of(CharSequence.class)), parameterTypes(call.method()));
    }

    @Test
    void aReturnTypeMatchingNoDeclarationKeepsTheInferredSignature() {
        // The Java writer lets javac resolve this; the bytecode writer reports it. A String converts to no Integer
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("concat", TypeDef.of(Integer.class), A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.STRING, TypeDef.STRING), parameterTypes(call.method()));
    }

    @Test
    void aReturnTypeTheDeclaredOneConvertsToResolvesTheCall() {
        // `Object value = concat("a", "b")`: the String concat returns is an Object
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("concat", TypeDef.OBJECT, A_STRING, B_STRING);

        assertEquals(List.of(TypeDef.OBJECT, TypeDef.OBJECT), parameterTypes(call.method()));
    }

    @Test
    void typeArgumentsCountForSpecificityAgainstANonGenericMethod() {
        // javac: "reference to ambiguous is ambiguous" - neither `ambiguous(List<String>, Object)` nor
        // `<T> ambiguous(List<T>, String)` is more specific, though they are by their erasures
        ExpressionDef strings = new VariableDef.Local("values", TypeDef.parameterized(List.class, String.class));

        OverloadResolution.Resolution resolution = OverloadResolution.resolve(List.of(ClassTypeDef.of(Generic.class)), "ambiguous",
            TypeDef.STRING, List.of(strings, A_STRING), null, null);

        assertEquals(OverloadResolution.Outcome.AMBIGUOUS, resolution.outcome());
        assertEquals(2, resolution.ambiguous().size());
    }

    @Test
    void anAmbiguousCallIsLeftUnresolvedByTheModel() {
        // The bytecode writers reject it where it is written, javac where it is compiled
        ExpressionDef strings = new VariableDef.Local("values", TypeDef.parameterized(List.class, String.class));

        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of(Generic.class).invokeStatic("ambiguous", TypeDef.STRING, strings, A_STRING);

        assertEquals(List.of(TypeDef.parameterized(List.class, String.class), TypeDef.STRING), parameterTypes(call.method()));
    }

    @SuppressWarnings("unused")
    static class Generic {

        static String ambiguous(List<String> values, Object value) {
            return "list-object";
        }

        static <T> String ambiguous(List<T> values, String value) {
            return "generic-string";
        }
    }

    @Test
    void aGenericVariableArityTailIsPackedIntoAnArrayOfTheRequestedElementType() {
        // List.of declares fixed arity overloads up to ten elements, so an eleventh can only resolve to
        // List.of(E...), whose erased Object[] would pin E to Object
        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of(List.class)
            .invokeStatic("of", TypeDef.parameterized(List.class, TypeDef.STRING), elements(11));

        assertEquals(List.of(TypeDef.OBJECT.array()), parameterTypes(call.method()));
        ExpressionDef packed = call.values().get(0);
        assertEquals(TypeDef.STRING.array(), packed.type());
        assertEquals(11, ((ExpressionDef.NewArrayInitialized) packed).expressions().size());
    }

    /**
     * The variable arity tail of {@code List.of(E...)} keeps its erased {@code Object[]} when the requested return type
     * cannot pin it to another array: an {@code Object} element type, an element that is not of the requested type, or
     * a parameterized element type, of which no array can be created.
     */
    @ParameterizedTest(name = "a variable arity tail keeps the erased Object[] for {0}")
    @MethodSource("tailsKeepingTheErasedArray")
    void aVariableArityTailTheRequestedTypeCannotPinKeepsTheErasedArray(TypeDef requested, List<ExpressionDef> values) {
        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of(List.class).invokeStatic("of", requested, values);

        assertEquals(TypeDef.OBJECT.array(), call.values().get(0).type());
    }

    static Stream<Arguments> tailsKeepingTheErasedArray() {
        List<ExpressionDef> notAllStrings = new ArrayList<>(elements(10));
        notAllStrings.add(ExpressionDef.constant(1));
        return Stream.of(
            arguments(named("a requested List<Object>", TypeDef.parameterized(List.class, TypeDef.OBJECT)), elements(11)),
            arguments(named("an int element of a requested List<String>", TypeDef.parameterized(List.class, TypeDef.STRING)),
                notAllStrings),
            arguments(named("a requested List<List<String>>",
                TypeDef.parameterized(List.class, TypeDef.parameterized(List.class, TypeDef.STRING))), elements(11))
        );
    }

    @Test
    void aVariableArityParameterThatIsNotErasedKeepsItsComponentType() {
        // firstOf(String...) is not erased to Object[], so its own component type is kept
        ExpressionDef.InvokeStaticMethod call = SUPPORT.invokeStatic("firstOf", TypeDef.STRING,
            List.of(A_STRING, B_STRING));

        assertEquals(List.of(TypeDef.STRING.array()), parameterTypes(call.method()));
        assertEquals(TypeDef.STRING.array(), call.values().get(0).type());
    }

    private static List<ExpressionDef> elements(int count) {
        List<ExpressionDef> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(ExpressionDef.constant("value" + i));
        }
        return values;
    }

    @SuppressWarnings("unused")
    static class Support {

        static String concat(Object left, Object right) {
            return String.valueOf(left) + right;
        }

        static String of(Object... elements) {
            return String.valueOf(elements.length);
        }

        static String firstOf(String... values) {
            return values[0];
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
