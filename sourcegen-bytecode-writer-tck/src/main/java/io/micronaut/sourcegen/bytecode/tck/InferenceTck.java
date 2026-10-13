/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode.tck;

import io.micronaut.sourcegen.bytecode.tck.ApplicabilityFixtures.Overloads;
import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.Left;
import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.Shared;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Alpha;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Zeta;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.ZetaAlphaOne;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Inference of method type arguments: wildcard capture and containment, bound checks, least upper bounds,
 * generic variable arity calls and the requested return type. Each generated call must behave as javac's
 * call on a compiled fixture.
 *
 * <p>A backend runs these tests by extending this class and implementing
 * {@link #write(io.micronaut.sourcegen.model.ObjectDef)}.
 *
 * @since 2.3
 */
@SuppressWarnings({
    "MissingOverride", "UnusedTypeParameter", "TypeParameterShadowing", "unchecked", "varargs",
    "rawtypes", "TypeParameterUnusedInFormals", "unused", "EqualsIncompatibleType", "UnusedMethod",
    "UnusedVariable"
})
public abstract class InferenceTck extends AbstractByteCodeWriterTck {

    /**
     * A bounded generic call returns the very array it is given, whatever the rank of the array.
     *
     * @param rank The rank of the array
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @ParameterizedTest(name = "an array of rank {0} keeps its identity through a bounded generic call")
    @ValueSource(ints = {1, 2, 3})
    public void genericArrayCallsPreserveValuesAcrossRanks(int rank) throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(Integer.class));
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("value", t.array(rank)).returns(t.array(rank))
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.Rank" + rank).addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.of(Number.class).array(rank)).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        var loader = load(def);
        var cls = loader.loadClass(def.getName());
        Object value = Array.newInstance(Integer.class, new int[rank]);
        Class<?> parameter = Array.newInstance(Number.class, new int[rank]).getClass();
        assertSame(value, cls.getMethod("call", parameter).invoke(cls.getConstructor().newInstance(), value));
    }

    /**
     * A call through a chain of type variables, each bounded by the next, returns the value it is given.
     *
     * @param length The number of variables in the chain
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @ParameterizedTest(name = "a chain of {0} dependent bounds returns the value it is given")
    @ValueSource(ints = {1, 2, 8, 9})
    public void chainedBoundsKeepTheirRuntimeIdentity(int length) throws Exception {
        var variables = new ArrayList<TypeDef.TypeVariable>();
        TypeDef bound = TypeDef.of(CharSequence.class);
        for (int i = length - 1; i >= 0; i--) {
            var variable = TypeDef.variable("T" + i, bound);
            variables.addFirst(variable);
            bound = variable;
        }
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC);
        variables.forEach(method::addTypeVariable);
        var identity = method.addParameter("value", variables.getFirst()).returns(variables.getFirst())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.Chain" + length).addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        var loader = load(def);
        var cls = loader.loadClass(def.getName());
        assertEquals("text", cls.getMethod("call", Object.class).invoke(cls.getConstructor().newInstance(), "text"));
    }

    /**
     * Verifies an erased invocation preserves arrays whose elements have intersection bounds.
     *
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @Test
    public void genericArrayCallsPreserveIntersectionBoundValues() throws Exception {
        var t = TypeDef.variable("T", TypeDef.of(CharSequence.class), TypeDef.of(Serializable.class));
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addParameter("value", t.array()).returns(Object.class).build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.IntersectionArrayCall").addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> self.invoke(identity, p.getFirst()).returning())).build();
        var cls = load(def).loadClass(def.getName());
        var value = new String[]{"text"};
        assertSame(value, cls.getMethod("call", Object.class).invoke(cls.getConstructor().newInstance(), (Object) value));
    }

    /**
     * Verifies array invocation preserves the caller's generic arguments across scopes.
     *
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @Test
    public void intersectionArraysPreserveCallerTypeArguments() throws Exception {
        var x = TypeDef.variable("X");
        var u = TypeDef.variable("U", TypeDef.parameterized(ClassTypeDef.of(List.class), x), TypeDef.of(Serializable.class));
        var arrays = MethodDef.builder("arrays").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u.array()).returns(Object.class).build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.IntersectionTarget").addModifiers(Modifier.PUBLIC).addTypeVariable(x).addMethod(arrays).build();
        var t = TypeDef.variable("T");
        var caller = ClassDef.builder("test.ArrayScopeT").addModifiers(Modifier.PUBLIC).addTypeVariable(t)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", TypeDef.parameterized(ClassTypeDef.of(target), t)).addParameter("value", Object.class).returns(Object.class)
                .build((self, p) -> p.getFirst().invoke(arrays, p.get(1)).returning())).build();
        var loader = load(target, caller);
        var targetClass = loader.loadClass(target.getName());
        var callerClass = loader.loadClass(caller.getName());
        var value = new ArrayList<?>[]{new ArrayList<>(List.of("text"))};
        assertSame(value, callerClass.getMethod("call", targetClass, Object.class)
            .invoke(callerClass.getConstructor().newInstance(), targetClass.getConstructor().newInstance(), value));
    }

    /**
     * Verifies null remains a valid value for a nullable array with intersection-bounded elements.
     *
     * @throws Exception If the generated program cannot be loaded or invoked
     * @since 2.3
     */
    @Test
    public void nullableIntersectionArraysPreserveNull() throws Exception {
        var u = TypeDef.variable("U", TypeDef.of(CharSequence.class), TypeDef.of(Serializable.class));
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addTypeVariable(u)
            .addParameter("value", u.array().makeNullable()).returns(TypeDef.OBJECT.makeNullable())
            .build((self, p) -> p.getFirst().returning());
        var def = ClassDef.builder("test.NullableIntersectionArray").addModifiers(Modifier.PUBLIC).addMethod(identity)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT.makeNullable())
                .build((self, p) -> self.invoke(identity, ExpressionDef.nullValue()).returning())).build();
        var cls = load(def).loadClass(def.getName());
        assertNull(cls.getMethod("call").invoke(cls.getConstructor().newInstance()));
    }

    @Test
    public void genericVarargsInferTheRuntimeComponentType() throws Exception {
        assertEquals(String.class.getName(), InferredVarargs.component("abc"));
        assertEquals(InferredVarargs.component("abc"), run(caller(ClassTypeDef.of(InferredVarargs.class)
            .invokeStatic("component", TypeDef.STRING, ExpressionDef.constant("abc")))));
    }

    @Test
    public void upperWildcardConstrainsApplicability() throws Exception {
        List<String> input = List.of("abc");
        assertEquals("object", Overloads.upper(input));
        assertEquals(Overloads.upper(input), callWithList("upper", TypeDef.STRING, input));
    }

    @Test
    public void lowerWildcardConstrainsApplicability() throws Exception {
        List<Integer> input = List.of(1);
        assertEquals("object", Overloads.lower(input));
        assertEquals(Overloads.lower(input), callWithList("lower", TypeDef.of(Integer.class), input));
    }

    @Test
    public void recursiveBoundConstrainsItsTypeArgument() throws Exception {
        WrongComparable input = new WrongComparable();
        var definition = callWithParameter(TypeDef.of(WrongComparable.class), p -> ClassTypeDef.of(Overloads.class).invokeStatic("recursive", TypeDef.STRING, p));
        assertEquals("object", Overloads.recursive(input));
        assertEquals(Overloads.recursive(input), define(definition).getMethod("call", WrongComparable.class).invoke(null, input));
    }

    @Test
    public void repeatedMethodVariableUnifiesAcrossArguments() throws Exception {
        List<String> input = List.of("abc");
        var definition = callWithParameter(TypeDef.parameterized(List.class, TypeDef.STRING), p ->
            ClassTypeDef.of(Overloads.class).invokeStatic("unify", TypeDef.STRING, p, ExpressionDef.constant(1)));
        assertEquals("collection", Overloads.unify(input, 1));
        assertEquals(Overloads.unify(input, 1), define(definition).getMethod("call", List.class).invoke(null, input));
    }

    @Test
    public void dependentMethodBoundIsResolvedBeforeApplicability() throws Exception {
        assertEquals("object", Overloads.dependent("abc"));
        assertEquals(Overloads.dependent("abc"), run(ClassTypeDef.of(Overloads.class).invokeStatic("dependent", TypeDef.STRING, ExpressionDef.constant("abc"))));
    }

    @Test
    public void genericVarargsKeepNullElementInference() throws Exception {
        assertEquals("java.lang.String", Overloads.component("abc", null));
        assertEquals(Overloads.component("abc", null), run(ClassTypeDef.of(Overloads.class).invokeStatic("component", TypeDef.STRING,
            ExpressionDef.constant("abc"), ExpressionDef.nullValue())));
    }

    @Test
    public void genericVarargsComputeCommonSuperclass() throws Exception {
        var definition = ClassDef.builder("test.additional.CommonArguments").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .addParameter("first", TypeDef.of(First.class)).addParameter("second", TypeDef.of(Second.class))
                .build((self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic("component", TypeDef.STRING, p.get(0), p.get(1)).returning())).build();
        assertEquals(Base.class.getName(), Overloads.component(new First(), new Second()));
        assertEquals(Overloads.component(new First(), new Second()), define(definition).getMethod("call", First.class, Second.class)
            .invoke(null, new First(), new Second()));
    }

    @Test
    public void genericVarargsInferenceIncludesFixedParameters() throws Exception {
        var definition = callWithParameter(TypeDef.OBJECT, p -> ClassTypeDef.of(Overloads.class).invokeStatic("seeded", TypeDef.STRING, p, ExpressionDef.constant("abc")));
        assertEquals("java.lang.Object", Overloads.seeded(new Object(), "abc"));
        assertEquals(Overloads.seeded(new Object(), "abc"), define(definition).getMethod("call", Object.class).invoke(null, new Object()));
    }

    @Test
    public void primitiveArrayIsPackedAsOneSerializableVararg() throws Exception {
        int[] input = {1, 2};
        var definition = callWithParameter(TypeDef.of(int[].class), p -> ClassTypeDef.of(Overloads.class).invokeStatic("serializables", TypeDef.STRING, p));
        assertEquals("1:[I", Overloads.serializables(input));
        assertEquals(Overloads.serializables(input), define(definition).getMethod("call", int[].class).invoke(null, input));
    }

    @Test
    public void constructorWildcardConstrainsApplicability() throws Exception {
        var definition = callWithParameter(TypeDef.parameterized(List.class, TypeDef.STRING), p ->
            ClassTypeDef.of(WildcardConstructor.class).instantiate(p).invoke("selected", TypeDef.STRING));
        List<String> input = List.of("abc");
        assertEquals("object", new WildcardConstructor(input).selected());
        assertEquals(new WildcardConstructor(input).selected, define(definition).getMethod("call", List.class).invoke(null, input));
    }

    @Test
    public void multiDimensionalGenericVarargsInferComponentRank() throws Exception {
        String[] input = {"abc"};
        var definition = callWithParameter(TypeDef.STRING.array(), p -> ClassTypeDef.of(Overloads.class).invokeStatic("rows", TypeDef.STRING, p, p));
        assertEquals("[Ljava.lang.String;", Overloads.rows(input, input));
        assertEquals(Overloads.rows(input, input), define(definition).getMethod("call", String[].class).invoke(null, (Object) input));
    }

    @Test
    public void admissibleWildcardsStillSelectTheSpecificOverload() throws Exception {
        List<Integer> input = List.of(1);
        assertEquals(Overloads.upper(input), callWithList("upper", TypeDef.of(Integer.class), input));
        List<Object> objects = List.of(new Object());
        assertEquals(Overloads.lower(objects), callWithList("lower", TypeDef.OBJECT, objects));
    }

    @Test
    public void capturedUpperWildcardDoesNotAcceptAnArbitrarySubtypeOfItsBound() throws Exception {
        List<? extends Number> values = List.of(1);
        assertEquals("fallback", CaptureFixtures.Calls.captureValue(values, Integer.valueOf(2)));
        assertCall(CaptureFixtures.Calls.captureValue(values, Integer.valueOf(2)),
            List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))), TypeDef.of(Integer.class)),
            List.of(values, 2), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("captureValue", TypeDef.STRING, p));
    }

    @Test
    public void independentWildcardCapturesCannotBeEquated() throws Exception {
        List<? extends Number> first = List.of(1);
        List<? extends Number> second = List.of(2.0);
        assertEquals("fallback", CaptureFixtures.Calls.sameLists(first, second));
        assertCall(CaptureFixtures.Calls.sameLists(first, second),
            List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))), list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("sameLists", TypeDef.STRING, p));
    }

    @Test
    public void wildcardUpperAndLowerConstraintsMustAgree() throws Exception {
        List<? extends Number> source = List.of(1);
        List<String> destination = new ArrayList<>();
        assertEquals("fallback", CaptureFixtures.Calls.transfer(source, destination));
        assertCall(CaptureFixtures.Calls.transfer(source, destination),
            List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))), list(TypeDef.STRING)),
            List.of(source, destination), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("transfer", TypeDef.STRING, p));
    }

    @Test
    public void compatibleWildcardTransferStillSelectsGenericOverload() throws Exception {
        List<? extends Integer> source = List.of(1);
        List<Number> destination = new ArrayList<>();
        assertEquals("generic", CaptureFixtures.Calls.transfer(source, destination));
        assertCall(CaptureFixtures.Calls.transfer(source, destination),
            List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Integer.class))), list(TypeDef.of(Number.class))),
            List.of(source, destination), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("transfer", TypeDef.STRING, p));
    }

    @Test
    public void lowerWildcardCaptureAcceptsValuesWithinItsLowerBound() throws Exception {
        List<? super Integer> values = new ArrayList<Number>();
        assertEquals("generic", CaptureFixtures.Calls.captureValue(values, Integer.valueOf(2)));
        assertCall(CaptureFixtures.Calls.captureValue(values, Integer.valueOf(2)),
            List.of(list(TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class))), TypeDef.of(Integer.class)),
            List.of(values, 2), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("captureValue", TypeDef.STRING, p));
    }

    @Test
    public void wildcardReceiverDoesNotAcceptValuesOfItsUpperBound() throws Exception {
        Receiver<? extends CharSequence> receiver = new Receiver<String>();
        assertEquals("fallback", receiver.choose("value"));
        assertCall(receiver.choose("value"),
            List.of(TypeDef.parameterized(Receiver.class, TypeDef.wildcardSubtypeOf(TypeDef.of(CharSequence.class))), TypeDef.STRING),
            List.of(receiver, "value"), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void recursiveBoundsMustHoldForOneSharedInferenceSolution() throws Exception {
        assertEquals("fallback", CaptureFixtures.Calls.recursive("value", Integer.valueOf(2)));
        assertCall(CaptureFixtures.Calls.recursive("value", Integer.valueOf(2)), List.of(TypeDef.STRING, TypeDef.of(Integer.class)),
            List.of("value", 2), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void compatibleRecursiveBoundsStillSelectGenericOverload() throws Exception {
        assertEquals("generic", CaptureFixtures.Calls.recursive("one", "two"));
        assertCall(CaptureFixtures.Calls.recursive("one", "two"), List.of(TypeDef.STRING, TypeDef.STRING),
            List.of("one", "two"), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void nestedLowerWildcardConstrainsItsTypeArgument() throws Exception {
        List<List<String>> first = List.of(List.of("value"));
        List<Integer> second = List.of(1);
        assertEquals("fallback", CaptureFixtures.Calls.nestedLower(first, second));
        assertCall(CaptureFixtures.Calls.nestedLower(first, second), List.of(list(list(TypeDef.STRING)), list(TypeDef.of(Integer.class))),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("nestedLower", TypeDef.STRING, p));
    }

    @Test
    public void compatibleNestedLowerWildcardStillSelectsGenericOverload() throws Exception {
        List<List<String>> first = List.of(List.of("value"));
        List<String> second = List.of("one");
        assertEquals("generic", CaptureFixtures.Calls.nestedLower(first, second));
        assertCall(CaptureFixtures.Calls.nestedLower(first, second), List.of(list(list(TypeDef.STRING)), list(TypeDef.STRING)),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("nestedLower", TypeDef.STRING, p));
    }

    @Test
    public void genericVarargsRespectAllBoundsWhenChoosingTheArrayComponent() throws Exception {
        var first = new CaptureFixtures.First();
        var second = new CaptureFixtures.Second();
        assertCall(CaptureFixtures.Calls.component(first, second), List.of(TypeDef.of(CaptureFixtures.First.class), TypeDef.of(CaptureFixtures.Second.class)),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void genericArrayInferencePreservesDifferentRanks() throws Exception {
        String[][] first = {{"one"}};
        Integer[] second = {1};
        assertCall(CaptureFixtures.Calls.arrayComponent(first, second), List.of(TypeDef.STRING.array(2), TypeDef.of(Integer.class).array()),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("arrayComponent", TypeDef.STRING, p));
    }

    @Test
    public void methodBoundToAnotherVariableRetainsItsParameterErasure() throws Exception {
        assertCall(CaptureFixtures.Calls.dependent("one", "two"), List.of(TypeDef.STRING, TypeDef.STRING),
            List.of("one", "two"), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("dependent", TypeDef.STRING, p));
    }

    @Test
    public void genericConstructorUsesTheSameCaptureRulesAsMethods() throws Exception {
        List<? extends Number> values = List.of(1);
        assertEquals("fallback", new ConstructorChoice(values, Integer.valueOf(2)).selected());
        assertCall(new ConstructorChoice(values, Integer.valueOf(2)).selected(),
            List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))), TypeDef.of(Integer.class)),
            List.of(values, 2), p -> ClassTypeDef.of(ConstructorChoice.class).instantiate(p).invoke("selected", TypeDef.STRING));
    }

    @Test
    public void parameterizedReceiverSpecializesTheRequestedReturnType() throws Exception {
        var receiver = new GenericGetter<String>();
        assertCall(receiver.get(), List.of(TypeDef.parameterized(GenericGetter.class, TypeDef.STRING)), List.of(receiver),
            p -> p.getFirst().invoke("get", TypeDef.STRING));
    }

    @Test
    public void genericMethodSpecializesTheRequestedReturnType() throws Exception {
        assertCall(CaptureFixtures.Calls.identity(1), List.of(TypeDef.of(Integer.class)), List.of(1),
            p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("identity", TypeDef.of(Integer.class), p));
    }

    @Test
    public void classAndMethodVariablesSpecializeTheRequestedReturnType() throws Exception {
        var receiver = new GenericMethodReceiver<CharSequence>();
        assertCall(receiver.echo("value"),
            List.of(TypeDef.parameterized(GenericMethodReceiver.class, TypeDef.of(CharSequence.class)), TypeDef.STRING),
            List.of(receiver, "value"), p -> p.getFirst().invoke("echo", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void targetTypeSelectsAGenericMethodWithNoArguments() throws Exception {
        assertCall(CaptureFixtures.Calls.create(), List.of(), List.of(),
            p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("create", TypeDef.STRING, p));
    }

    @Test
    public void explicitInstanceMethodReturningTheSpecializedTypeInvokesTheDeclaration() throws Exception {
        Supplier<String> receiver = () -> "value";
        assertCall(5, List.of(TypeDef.parameterized(Supplier.class, TypeDef.STRING)), List.of(receiver),
            p -> p.getFirst().invoke(MethodDef.builder("get").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING).build())
                .invoke("length", TypeDef.Primitive.INT));
    }

    @Test
    public void explicitStaticMethodReturningTheTargetTypeInvokesTheDeclaration() throws Exception {
        assertCall(5, List.of(TypeDef.STRING), List.of("value"),
            p -> ClassTypeDef.of(java.util.Objects.class).invokeStatic(MethodDef.builder("requireNonNull")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("obj", TypeDef.OBJECT).returns(TypeDef.STRING).build(),
                p.getFirst()).invoke("length", TypeDef.Primitive.INT));
    }

    @Test
    public void nestedWildcardInferencePropagatesTheCapturedUpperBound() throws Exception {
        List<? extends List<String>> values = List.of(List.of("value"));
        assertEquals("fallback", CaptureFixtures.Calls.nestedUpper(values, Integer.valueOf(1)));
        assertCall(CaptureFixtures.Calls.nestedUpper(values, Integer.valueOf(1)),
            List.of(list(TypeDef.wildcardSubtypeOf(list(TypeDef.STRING))), TypeDef.of(Integer.class)),
            List.of(values, 1), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("nestedUpper", TypeDef.STRING, p));
    }

    @Test
    public void upperOnlyInferenceConstraintsMustSatisfyTheDeclaredBound() throws Exception {
        List<String> values = List.of("value");
        assertEquals("fallback", CaptureFixtures.Calls.boundedLower(values));
        assertCall(CaptureFixtures.Calls.boundedLower(values), List.of(list(TypeDef.STRING)), List.of(values),
            p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("boundedLower", TypeDef.STRING, p));
    }

    @Test
    public void incompatibleUpperOnlyInferenceConstraintsExcludeAnOverload() throws Exception {
        List<String> first = List.of("value");
        List<Integer> second = List.of(1);
        assertEquals("fallback", CaptureFixtures.Calls.upperOnly(first, second));
        assertCall(CaptureFixtures.Calls.upperOnly(first, second), List.of(list(TypeDef.STRING), list(TypeDef.of(Integer.class))),
            List.of(first, second), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("upperOnly", TypeDef.STRING, p));
    }

    @Test
    public void nestedGenericArraysInferTheRemainingDimensions() throws Exception {
        List<String[][]> values = List.<String[][]>of(new String[][]{{"value"}});
        String[] other = {"one"};
        assertEquals("generic", CaptureFixtures.Calls.rank(values, other));
        assertCall(CaptureFixtures.Calls.rank(values, other), List.of(list(TypeDef.STRING.array(2)), TypeDef.STRING.array()),
            List.of(values, other), p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("rank", TypeDef.STRING, p));
    }

    @Test
    public void emptyVarargsUseTheMethodBoundSpecializedByTheReceiver() throws Exception {
        var receiver = new ReceiverMethodVarargs<Integer>();
        assertCall(receiver.component(), List.of(TypeDef.parameterized(ReceiverMethodVarargs.class, TypeDef.of(Integer.class))),
            List.of(receiver), p -> p.getFirst().invoke("component", TypeDef.STRING));
    }

    @Test
    public void nonemptyVarargsUseTheMethodBoundSpecializedByTheReceiver() throws Exception {
        var receiver = new ReceiverMethodVarargs<Integer>();
        assertCall(receiver.component(1), List.of(TypeDef.parameterized(ReceiverMethodVarargs.class, TypeDef.of(Integer.class))),
            List.of(receiver), p -> p.getFirst().invoke("component", TypeDef.STRING, ExpressionDef.constant(1)));
    }

    @Test
    public void lowerWildcardContributesAnUpperInferenceConstraint() throws Exception {
        var input = List.of("a");
        assertEquals("fallback", InferenceFixtures.Calls.lower(input, 7));
        assertCall(InferenceFixtures.Calls.lower(input, 7), List.of(TypeDef.parameterized(List.class, TypeDef.STRING), TypeDef.Primitive.INT),
            List.of(input, 7), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("lower", TypeDef.STRING, p));
    }

    @Test
    public void compatibleLowerWildcardStillSelectsGenericOverload() throws Exception {
        List<Number> input = List.of(1);
        assertCall(InferenceFixtures.Calls.lower(input, 7), List.of(TypeDef.parameterized(List.class, TypeDef.of(Number.class)), TypeDef.Primitive.INT),
            List.of(input, 7), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("lower", TypeDef.STRING, p));
    }

    @Test
    public void nestedWildcardPropagatesItsTypeArgumentConstraint() throws Exception {
        var input = List.of(List.of("a"));
        assertEquals("fallback", InferenceFixtures.Calls.nested(input, 7));
        assertCall(InferenceFixtures.Calls.nested(input, 7), List.of(TypeDef.parameterized(List.class, TypeDef.parameterized(List.class, TypeDef.STRING)), TypeDef.Primitive.INT),
            List.of(input, 7), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("nested", TypeDef.STRING, p));
    }

    @Test
    public void invariantGenericArrayArgumentsConstrainInference() throws Exception {
        List<String[]> input = List.<String[]>of(new String[]{"a"});
        assertEquals("fallback", InferenceFixtures.Calls.arrayArgument(input, 7));
        assertCall(InferenceFixtures.Calls.arrayArgument(input, 7), List.of(TypeDef.parameterized(List.class, TypeDef.STRING.array()), TypeDef.Primitive.INT),
            List.of(input, 7), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("arrayArgument", TypeDef.STRING, p));
    }

    @Test
    public void wildcardArgumentIsContainedByAnotherUpperWildcard() throws Exception {
        List<? extends Integer> input = List.of(7);
        assertCall(InferenceFixtures.Calls.upper(input), List.of(TypeDef.parameterized(List.class, TypeDef.wildcardSubtypeOf(TypeDef.of(Integer.class)))),
            List.of(input), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("upper", TypeDef.STRING, p));
    }

    @Test
    public void wildcardArgumentIsContainedByAnotherLowerWildcard() throws Exception {
        List<? super Number> input = new ArrayList<Object>();
        assertCall(InferenceFixtures.Calls.supertype(input), List.of(TypeDef.parameterized(List.class, TypeDef.wildcardSupertypeOf(TypeDef.of(Number.class)))),
            List.of(input), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("supertype", TypeDef.STRING, p));
    }

    @Test
    public void wildcardArgumentExcludesAnUnrelatedUpperBound() throws Exception {
        List<? extends String> input = List.of("a");
        assertEquals("fallback", InferenceFixtures.Calls.upper(input));
        assertCall(InferenceFixtures.Calls.upper(input), List.of(TypeDef.parameterized(List.class, TypeDef.wildcardSubtypeOf(TypeDef.STRING))),
            List.of(input), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("upper", TypeDef.STRING, p));
    }

    @Test
    public void dependentVariableBoundsPropagateLowerConstraints() throws Exception {
        var first = new StringBuilder("a");
        assertEquals("generic", InferenceFixtures.Calls.dependent(first, "b"));
        assertCall(InferenceFixtures.Calls.dependent(first, "b"), List.of(TypeDef.of(StringBuilder.class), TypeDef.STRING),
            List.of(first, "b"), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("dependent", TypeDef.STRING, p));
    }

    @Test
    public void inferenceFindsACommonInterfaceOfUnrelatedClasses() throws Exception {
        var first = new Left();
        var second = new Right();
        assertEquals("generic", InferenceFixtures.Calls.shared(first, second));
        assertCall(InferenceFixtures.Calls.shared(first, second), List.of(TypeDef.of(Left.class), TypeDef.of(Right.class)),
            List.of(first, second), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("shared", TypeDef.STRING, p));
    }

    @Test
    public void boundedVarargsInferACommonInterfaceArray() throws Exception {
        var first = new Left();
        var second = new Right();
        assertCall(InferenceFixtures.Calls.sharedArray(first, second), List.of(TypeDef.of(Left.class), TypeDef.of(Right.class)),
            List.of(first, second), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("sharedArray", TypeDef.STRING, p));
    }

    @Test
    public void unboundedVarargsInferACommonInterfaceArray() throws Exception {
        var first = new Left();
        var second = new Right();
        assertCall(InferenceFixtures.Calls.component(first, second), List.of(TypeDef.of(Left.class), TypeDef.of(Right.class)),
            List.of(first, second), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void varargsInferencePreservesArrayLeastUpperBound() throws Exception {
        String[] first = {"a"};
        Integer[] second = {7};
        assertCall(InferenceFixtures.Calls.component(first, second), List.of(TypeDef.STRING.array(), TypeDef.of(Integer.class).array()),
            List.of(first, second), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void genericVarargsUseParameterizedReceiverComponent() throws Exception {
        var receiver = new ClassVarargs<String>();
        assertCall(receiver.component("a", "b"), List.of(TypeDef.parameterized(ClassVarargs.class, TypeDef.STRING)),
            List.of(receiver), p -> p.getFirst().invoke("component", TypeDef.STRING, ExpressionDef.constant("a"), ExpressionDef.constant("b")));
    }

    @Test
    public void emptyGenericVarargsUseParameterizedReceiverComponent() throws Exception {
        var receiver = new ClassVarargs<String>();
        assertCall(receiver.component(), List.of(TypeDef.parameterized(ClassVarargs.class, TypeDef.STRING)),
            List.of(receiver), p -> p.getFirst().invoke("component", TypeDef.STRING));
    }

    @Test
    public void inheritedGenericVarargsUseSpecializedReceiverComponent() throws Exception {
        var receiver = new StringVarargs();
        assertCall(receiver.component("a"), List.of(TypeDef.of(StringVarargs.class)), List.of(receiver),
            p -> p.getFirst().invoke("component", TypeDef.STRING, ExpressionDef.constant("a")));
    }

    @Test
    public void genericVarargsWithOneArrayUsesFixedArity() throws Exception {
        String[] input = {"a"};
        assertCall(InferenceFixtures.Calls.component(input), List.of(TypeDef.STRING.array()), List.of((Object) input),
            p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void unboundedWildcardRemainsApplicable() throws Exception {
        List<?> value = List.of("a");
        assertCall(InferenceFixtures.Calls.any(value), List.of(TypeDef.parameterized(List.class, TypeDef.wildcard())), List.of(value),
            p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("any", TypeDef.STRING, p));
    }

    @Test
    public void invariantGenericArrayArgumentCanInferItsComponent() throws Exception {
        List<String[]> input = List.<String[]>of(new String[]{"a"});
        assertEquals("generic", InferenceFixtures.Calls.arrayArgument(input, "b"));
        assertCall(InferenceFixtures.Calls.arrayArgument(input, "b"), List.of(TypeDef.parameterized(List.class, TypeDef.STRING.array()), TypeDef.STRING),
            List.of(input, "b"), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("arrayArgument", TypeDef.STRING, p));
    }

    @Test
    public void wildcardCaptureRespectsMethodVariableBounds() throws Exception {
        List<? extends String> input = List.of("a");
        assertEquals("object", InferenceFixtures.Calls.capture(input));
        assertCall(InferenceFixtures.Calls.capture(input), List.of(TypeDef.parameterized(List.class, TypeDef.wildcardSubtypeOf(TypeDef.STRING))),
            List.of(input), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("capture", TypeDef.STRING, p));
    }

    @Test
    public void genericArrayTypeArgumentsRemainInvariant() throws Exception {
        List<List<Integer>[]> input = List.of();
        assertEquals("object", InferenceFixtures.Calls.invariantArray(input));
        assertCall(InferenceFixtures.Calls.invariantArray(input), List.of(TypeDef.parameterized(List.class, TypeDef.parameterized(List.class, TypeDef.of(Integer.class)).array())),
            List.of(input), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("invariantArray", TypeDef.STRING, p));
    }

    @Test
    public void primitiveMultidimensionalArraysRetainTheirLeastUpperBoundRank() throws Exception {
        int[][] first = {{1}};
        long[][] second = {{2}};
        assertCall(EnclosingTypeFixtures.Calls.component(first, second), List.of(TypeDef.of(int[][].class), TypeDef.of(long[][].class)),
            List.of(first, second), p -> ClassTypeDef.of(EnclosingTypeFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void lowerWildcardCaptureAllowsBoxing() throws Exception {
        ConsumerBox<? super Integer> receiver = new ConsumerBox<Number>();
        assertCall(receiver.choose(1), List.of(TypeDef.parameterized(ConsumerBox.class, TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class)))),
            List.of(receiver), p -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant(1)));
    }

    /**
     * A variable arity call whose argument is typed by a method type variable, named or declared inline, packs
     * the array javac infers for it.
     *
     * @param declaration How the argument's type variable is written: by name or inline with its bound
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "an argument of a method variable written {0} packs the inferred array")
    @ValueSource(strings = {"named", "inline"})
    public void lexicalVarargsKeepTheInferredArrayType(String declaration) throws Exception {
        boolean inline = declaration.equals("inline");
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var definition = ClassDef.builder("test.resolution.LexicalVarargs").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", inline ? variable : TypeDef.variable("T")).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p).returning())).build();
        assertEquals(ResolutionFixtures.Calls.component((Number) 1), define(definition).getMethod("call", Number.class).invoke(null, 1));
    }

    @Test
    public void lowerWildcardStillContributesItsObjectUpperBound() throws Exception {
        List<? super Integer> values = new ArrayList<Number>();
        assertEquals("object", ResolutionFixtures.Calls.upperNumber(values));
        assertCall(ResolutionFixtures.Calls.upperNumber(values), List.of(list(TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class)))),
            List.of(values), p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperNumber", TypeDef.STRING, p));
    }

    @Test
    public void upperWildcardWithNumericBoundRemainsApplicable() throws Exception {
        List<? extends Integer> values = List.of(1);
        assertCall(ResolutionFixtures.Calls.upperNumber(values), List.of(list(TypeDef.wildcardSubtypeOf(TypeDef.of(Integer.class)))),
            List.of(values), p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperNumber", TypeDef.STRING, p));
    }

    @Test
    public void upperOnlyConstraintsRejectIncompatibleGenericBounds() throws Exception {
        List<List<Integer>> values = List.of(List.of(1));
        assertEquals("object", ResolutionFixtures.Calls.upperList(values));
        assertCall(ResolutionFixtures.Calls.upperList(values), List.of(list(list(TypeDef.of(Integer.class)))), List.of(values),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperList", TypeDef.STRING, p));
    }

    @Test
    public void upperOnlyConstraintsAcceptCompatibleGenericBounds() throws Exception {
        List<List<String>> values = List.of(List.of("one"));
        assertCall(ResolutionFixtures.Calls.upperList(values), List.of(list(list(TypeDef.STRING))), List.of(values),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperList", TypeDef.STRING, p));
    }

    @Test
    public void upperOnlyConstraintsRejectConflictingInterfaceArguments() throws Exception {
        List<Comparable<Integer>> values = List.of(1);
        assertEquals("object", ResolutionFixtures.Calls.upperComparable(values));
        assertCall(ResolutionFixtures.Calls.upperComparable(values), List.of(list(TypeDef.parameterized(Comparable.class, Integer.class))), List.of(values),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperComparable", TypeDef.STRING, p));
    }

    @Test
    public void nestedGenericEqualityChecksRawTypes() throws Exception {
        List<java.util.Set<String>> first = List.of(java.util.Set.of("one"));
        List<String> second = List.of("two");
        assertEquals("object", ResolutionFixtures.Calls.nested(first, second));
        assertCall(ResolutionFixtures.Calls.nested(first, second), List.of(list(TypeDef.parameterized(java.util.Set.class, String.class)), list(TypeDef.STRING)),
            List.of(first, second), p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("nested", TypeDef.STRING, p));
    }

    @Test
    public void methodInferenceUsesTheCallerVariablesBounds() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var definition = ClassDef.builder("test.resolution.InferenceScope").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("values", list(TypeDef.variable("T"))).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperNumber", TypeDef.STRING, p).returning())).build();
        assertEquals(ResolutionFixtures.Calls.upperNumber(List.of(1)), define(definition).getMethod("call", List.class).invoke(null, List.of(1)));
    }

    @Test
    public void wildcardArgumentUsesTheCallerVariablesBounds() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var definition = ClassDef.builder("test.resolution.WildcardScope").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("values", list(TypeDef.wildcardSubtypeOf(TypeDef.variable("T")))).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("upperNumber", TypeDef.STRING, p).returning())).build();
        assertEquals(ResolutionFixtures.Calls.upperNumber(List.of(1)), define(definition).getMethod("call", List.class).invoke(null, List.of(1)));
    }

    @Test
    public void genericInferenceAcceptsRecursiveWildcardBound() throws Exception {
        assertCall(ResolutionFixtures.Calls.recursive("hello"), List.of(TypeDef.STRING), List.of("hello"),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void genericInferenceRejectsRecursiveWildcardBound() throws Exception {
        Object value = new Object();
        assertCall(ResolutionFixtures.Calls.recursive(value), List.of(TypeDef.OBJECT), List.of(value),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void leastUpperBoundOfIntegerAndStringIsSerializable() throws Exception {
        assertCall(OverloadFixtures.Calls.component(1, "a"), List.of(TypeDef.of(Integer.class), TypeDef.STRING), List.of(1, "a"),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void leastUpperBoundOrdersInterfacesByName() throws Exception {
        var first = new ZetaAlphaOne();
        var second = new ZetaAlphaTwo();
        assertCall(OverloadFixtures.Calls.component(first, second), List.of(TypeDef.of(ZetaAlphaOne.class), TypeDef.of(ZetaAlphaTwo.class)),
            List.of(first, second), p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void leastUpperBoundOrdersInterfacesByRank() throws Exception {
        var first = new AlphaMidOne();
        var second = new AlphaMidTwo();
        assertCall(OverloadFixtures.Calls.component(first, second), List.of(TypeDef.of(AlphaMidOne.class), TypeDef.of(AlphaMidTwo.class)),
            List.of(first, second), p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void arrayArgumentsInferAnArrayComponent() throws Exception {
        String[] first = {"a"};
        String[] second = {"b"};
        assertCall(OverloadFixtures.Calls.component(first, second), List.of(TypeDef.STRING.array(), TypeDef.STRING.array()),
            List.of(first, second), p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void primitiveArrayArgumentIsOneElement() throws Exception {
        int[] values = {1};
        assertCall(OverloadFixtures.Calls.component(values), List.of(TypeDef.Primitive.INT.array()),
            List.of((Object) values), p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void collectionsMaxErasesToObjectAndIsTypedAsRequested() throws Exception {
        List<Integer> values = List.of(3, 1, 2);
        assertCall(Collections.max(values), List.of(TypeDef.parameterized(List.class, Integer.class)), List.of(values),
            p -> ClassTypeDef.of(Collections.class).invokeStatic("max", TypeDef.of(Integer.class), p));
    }

    @Test
    public void collectionsMaxOfARecursiveBoundThroughASuperinterface() throws Exception {
        List<LocalDate> values = List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        assertCall(Collections.max(values), List.of(TypeDef.parameterized(List.class, LocalDate.class)), List.of(values),
            p -> ClassTypeDef.of(Collections.class).invokeStatic("max", TypeDef.of(LocalDate.class), p));
    }

    @Test
    public void enumSetOfPacksTheVariableArityTail() throws Exception {
        var type = TypeDef.of(TimeUnit.class);
        List<TypeDef> types = List.of(type, type, type, type, type, type);
        List<Object> values = List.of(TimeUnit.DAYS, TimeUnit.HOURS, TimeUnit.MINUTES, TimeUnit.SECONDS,
            TimeUnit.MILLISECONDS, TimeUnit.NANOSECONDS);
        assertCall(EnumSet.of(TimeUnit.DAYS, TimeUnit.HOURS, TimeUnit.MINUTES, TimeUnit.SECONDS, TimeUnit.MILLISECONDS,
                TimeUnit.NANOSECONDS), types, values,
            p -> ClassTypeDef.of(EnumSet.class).invokeStatic("of", TypeDef.of(EnumSet.class), p));
    }

    @Test
    public void streamCollectResolvesTheCollectorOverload() throws Exception {
        var stream = TypeDef.parameterized(java.util.stream.Stream.class, TypeDef.STRING);
        var collector = TypeDef.parameterized(java.util.stream.Collector.class, TypeDef.STRING, TypeDef.wildcard(),
            TypeDef.parameterized(List.class, TypeDef.STRING));
        assertCall(java.util.stream.Stream.of("a", "b").collect(Collectors.toList()), List.of(stream, collector),
            List.of(java.util.stream.Stream.of("a", "b"), Collectors.toList()),
            p -> p.getFirst().invoke("collect", TypeDef.of(List.class), p.get(1)));
    }

    @Test
    public void leastUpperBoundOfIntegerAndDoubleIsNumber() throws Exception {
        assertCall(OverloadFixtures.Calls.component(1, 2.0), List.of(TypeDef.Primitive.INT, TypeDef.Primitive.DOUBLE), List.of(1, 2.0),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("component", TypeDef.STRING, p));
    }

    @Test
    public void recursiveBoundAcceptsASubclassOfAComparable() throws Exception {
        // javac infers T as java.util.Date, the superclass that is a Comparable of itself
        var value = new java.sql.Date(0);
        assertCall(OverloadFixtures.Calls.recursive(value), List.of(TypeDef.of(java.sql.Date.class)), List.of(value),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void recursiveBoundAcceptsAClassComparableThroughASuperinterface() throws Exception {
        // javac infers T as ChronoLocalDate
        var value = LocalDate.of(2020, 1, 1);
        assertCall(OverloadFixtures.Calls.recursive(value), List.of(TypeDef.of(LocalDate.class)), List.of(value),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("recursive", TypeDef.STRING, p));
    }

    @Test
    public void unboundedWildcardIsCapturedWithTheDeclaredBound() throws Exception {
        OverloadFixtures.NumberBox<?> value = new OverloadFixtures.NumberBox<Integer>();
        assertCall(OverloadFixtures.Calls.boxed(value), List.of(TypeDef.parameterized(OverloadFixtures.NumberBox.class, TypeDef.wildcard())), List.of(value),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("boxed", TypeDef.STRING, p));
    }

    /**
     * An unbounded wildcard of a class that declares its parameter with a bound is captured with that bound: a
     * {@code BoundedBoxFixtures.NumberBox<?>} holds Numbers, so {@code <T extends Number> boxed(BoundedBoxFixtures.NumberBox<T>)} applies and is more
     * specific than {@code boxed(Object)}. The capture took only the wildcard's own bound, so the generic overload
     * did not apply and the call went to {@code boxed(Object)}. It was right before PR 518.
     */
    @Test
    public void unboundedWildcardOfABoundedClassSelectsTheGenericOverload() throws Exception {
        BoundedBoxFixtures.NumberBox<?> value = new BoundedBoxFixtures.NumberBox<Integer>();
        assertEquals("box", BoundedBoxFixtures.Calls.boxed(value));
        assertCall(BoundedBoxFixtures.Calls.boxed(value), List.of(TypeDef.parameterized(BoundedBoxFixtures.NumberBox.class, TypeDef.wildcard())), List.of(value),
            p -> ClassTypeDef.of(BoundedBoxFixtures.Calls.class).invokeStatic("boxed", TypeDef.STRING, p));
    }

    private Object callWithList(String method, TypeDef argument, List<?> input) throws Exception {
        var definition = callWithParameter(TypeDef.parameterized(List.class, argument), p -> ClassTypeDef.of(Overloads.class).invokeStatic(method, TypeDef.STRING, p));
        return define(definition).getMethod("call", List.class).invoke(null, input);
    }

    /** Generic variable arity inference.
     * @since 2.3
     */
    public static class InferredVarargs {
        /** @param <T> The inferred component
         * @param values The varargs array
         * @return The runtime component class name
         */
        @SafeVarargs
        public static <T> String component(T... values) {
            return values.getClass().getComponentType().getName();
        }
    }

    /** Constructor inference oracle.
     * @since 2.3
     */
    public static class WildcardConstructor {
        private final String selected;

        public WildcardConstructor(List<? extends Number> values) {
            selected = "numbers";
        }

        public WildcardConstructor(Object value) {
            selected = "object";
        }

        /** Returns the selected constructor.
         * @return The selected overload
         */
        public String selected() {
            return selected;
        }
    }

    /** A class with a distinct Comparable argument.
     * @since 2.3
     */
    @SuppressWarnings("ComparableType")
    public static class WrongComparable implements Comparable<String> {
        @Override
        public int compareTo(String value) {
            return 0;
        }
    }

    /** A common varargs superclass.
     * @since 2.3
     */
    public static class Base {
    }

    /** A varargs input.
     * @since 2.3
     */
    public static class First extends Base {
    }

    /** Another varargs input.
     * @since 2.3
     */
    public static class Second extends Base {
    }

    /** Wildcard receiver oracle.
     * @param <T> Accepted values
     * @since 2.3
     */
    public static class Receiver<T extends CharSequence> {
        public String choose(T value) {
            return "generic";
        }

        public String choose(Object value) {
            return "fallback";
        }
    }

    /** Constructor overload oracle.
     * @since 2.3
     */
    public static class ConstructorChoice {
        private final String choice;

        public <T> ConstructorChoice(List<T> values, T value) {
            choice = "generic";
        }

        public ConstructorChoice(Object values, Object value) {
            choice = "fallback";
        }

        public String selected() {
            return choice;
        }
    }

    /** Generic receiver whose erased result differs from its parameterized result.
     * @param <T> Result type
     * @since 2.3
     */
    public static class GenericGetter<T> {
        /**
         * @return The generic value
         */
        public T get() {
            return null;
        }
    }

    /** Generic receiver with a method variable bounded by its class variable.
     * @param <T> Receiver bound
     * @since 2.3
     */
    public static class GenericMethodReceiver<T extends CharSequence> {
        /**
         * @param value The generic value
         * @param <U> The method value
         * @return The generic value
         */
        public <U extends T> U echo(U value) {
            return value;
        }
    }

    /** A varargs method whose variable is bounded by its generic receiver.
     * @param <T> Receiver bound
     * @since 2.3
     */
    public static class ReceiverMethodVarargs<T extends Number> {
        public <U extends T> String component(U... values) {
            return values.getClass().getComponentType().getName();
        }
    }

    /**
     * Second unrelated implementation.
     * @since 2.3
     */
    public static class Right implements Shared { }

    /**
     * Varargs whose component belongs to the class.
     * @param <T> Component
     * @since 2.3
     */
    public static class ClassVarargs<T> {
        public String component(T... values) {
            return values.getClass().getComponentType().getName();
        }
    }

    /**
     * Inherited specialization of class varargs.
     * @since 2.3
     */
    public static class StringVarargs extends ClassVarargs<String> { }

    /** Capture conversion oracle.
     * @param <T> Fixture type
     * @since 2.3
     */
    public static class ConsumerBox<T extends Number> {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(T value) {
            return "number";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(Object value) {
            return "object";
        }
    }

    /** Marker.
     * @since 2.3
     */
    public interface Top { }

    /** Marker of a higher rank.
     * @since 2.3
     */
    public interface Mid extends Top { }

    /** Implements the markers in declaration order.
     * @since 2.3
     */
    public static class ZetaAlphaTwo implements Zeta, Alpha { }

    /** Implements the markers in declaration order.
     * @since 2.3
     */
    public static class AlphaMidOne implements Alpha, Mid { }

    /** Implements the markers in declaration order.
     * @since 2.3
     */
    public static class AlphaMidTwo implements Alpha, Mid { }
}
