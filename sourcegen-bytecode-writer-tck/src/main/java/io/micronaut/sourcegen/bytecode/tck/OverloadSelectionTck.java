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

import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.Left;
import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.RawBoundedReceiver;
import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.Shared;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.IntegerTaker;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.ObjectChooser;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Receivers;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.StringChooser;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.StringTaker;
import io.micronaut.sourcegen.bytecode.tck.ResolutionFixtures.First;
import io.micronaut.sourcegen.bytecode.tck.ResolutionFixtures.Second;
import io.micronaut.sourcegen.bytecode.tck.visibility.VisibilityOverloads;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Overload selection: the phases of strict, loose and variable arity invocation, the most specific
 * method, accessibility of candidates and explicit descriptors. Each generated call must choose the
 * overload javac chooses for the same call on a compiled fixture.
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
public abstract class OverloadSelectionTck extends AbstractByteCodeWriterTck {

    /**
     * A call by name of a reflected overloaded method chooses the overload javac chooses for the same argument.
     *
     * @param method   The overloaded method of {@link Overloads}
     * @param argument The argument of the call
     * @param expected The overload javac chooses, as the value it returns
     * @throws Exception If the generated call cannot be loaded or invoked
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reflectedOverloadCalls")
    public void reflectedOverloadCallChoosesJavacsOverload(String method, ExpressionDef argument, String expected) throws Exception {
        assertEquals(expected, callOverload(method, argument));
    }

    static Stream<Arguments> reflectedOverloadCalls() {
        return Stream.of(
            Arguments.of(Named.of("a null literal selects the most specific reference overload", "reference"),
                ExpressionDef.nullValue(), "sequence"),
            Arguments.of(Named.of("a null literal is passed as the variable arity array itself", "varargs"),
                ExpressionDef.nullValue(), "null"),
            Arguments.of(Named.of("a fixed arity overload precedes a variable arity one", "fixed"),
                ExpressionDef.constant("ok"), "object"),
            Arguments.of(Named.of("a String chooses the most specific reference overload", "reference"),
                ExpressionDef.constant("abc"), "sequence"),
            Arguments.of(Named.of("an int chooses primitive widening before boxing", "numeric"),
                ExpressionDef.constant(3), "long"),
            Arguments.of(Named.of("an Integer is unboxed and then widened", "widen"),
                ExpressionDef.constant(3).cast(TypeDef.of(Integer.class)), "long"));
    }

    @Test
    public void explicitDescriptorSelectsRequestedOverload() throws Exception {
        var target = MethodDef.of(Overloads.class.getMethod("reference", Object.class));
        var def = ClassDef.builder("test.ExplicitOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(Overloads.class)
                    .invokeStatic(target, ExpressionDef.constant("abc")).returning())).build();
        assertEquals("object", define(def).getMethod("call").invoke(null));
    }

    @Test
    public void choosesMostSpecificConstructor() throws Exception {
        var def = ClassDef.builder("test.OverloadedConstructor").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.of(Overloads.class)).build((self, p) -> ClassTypeDef.of(Overloads.class)
                    .instantiate(ExpressionDef.constant("abc")).returning())).build();
        assertEquals("sequence", ((Overloads) define(def).getMethod("call").invoke(null)).selected);
    }

    @Test
    public void generatedOverloadChoosesMostSpecificSupertype() throws Exception {
        var target = ClassDef.builder("test.edges.GeneratedOverloads").addModifiers(Modifier.PUBLIC)
            .addMethod(selection("choose", TypeDef.OBJECT, "object"))
            .addMethod(selection("choose", TypeDef.of(CharSequence.class), "sequence")).build();
        var caller = caller(target.asTypeDef().invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant("abc")));
        assertEquals("sequence", run(caller, target));
    }

    @Test
    public void generatedOverloadChoosesPrimitiveWidening() throws Exception {
        var target = ClassDef.builder("test.edges.GeneratedPrimitiveOverloads").addModifiers(Modifier.PUBLIC)
            .addMethod(selection("choose", TypeDef.Primitive.LONG, "long"))
            .addMethod(selection("choose", TypeDef.of(Integer.class), "boxed")).build();
        assertEquals("long", run(caller(target.asTypeDef().invokeStatic("choose", TypeDef.STRING,
            ExpressionDef.constant(1))), target));
    }

    @Test
    public void parameterizedReceiverParticipatesInOverloadSelection() throws Exception {
        var receiver = TypeDef.parameterized(ReceiverOverloads.class, TypeDef.STRING);
        var definition = ClassDef.builder("test.edges.ParameterizedReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("receiver", receiver).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant("abc")).returning()))
            .build();
        var instance = new ReceiverOverloads<String>();
        assertEquals("generic", instance.choose("abc"));
        assertEquals(instance.choose("abc"), define(definition).getMethod("call", ReceiverOverloads.class).invoke(null, instance));
    }

    @Test
    public void genericIntersectionConstrainsOverloadApplicability() throws Exception {
        assertEquals("object", CompiledOverloads.constrained(Integer.valueOf(1)));
        assertEquals(CompiledOverloads.constrained(Integer.valueOf(1)), run(caller(ClassTypeDef.of(CompiledOverloads.class)
            .invokeStatic("constrained", TypeDef.STRING, ExpressionDef.constant(1).cast(TypeDef.of(Integer.class))))));
    }

    @Test
    public void parameterizedArgumentsConstrainOverloadApplicability() throws Exception {
        var definition = ClassDef.builder("test.edges.ParameterizedArgument").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.parameterized(List.class, TypeDef.of(Integer.class))).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(CompiledOverloads.class)
                    .invokeStatic("collections", TypeDef.STRING, p.getFirst()).returning())).build();
        List<Integer> values = List.of(1);
        assertEquals("integers", CompiledOverloads.collections(values));
        assertEquals(CompiledOverloads.collections(values), define(definition).getMethod("call", List.class).invoke(null, values));
    }

    @Test
    public void inaccessibleMethodIsNotAnOverloadCandidate() throws Exception {
        assertEquals("public", OverloadAccessOracle.choose());
        assertEquals(OverloadAccessOracle.choose(), run(caller(ClassTypeDef.of(AccessOverloads.class)
            .invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant("abc")))));
    }

    @Test
    public void secondaryBoundOverloadsUseTheFullDescriptor() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(FirstBound.class), TypeDef.of(SecondBound.class));
        var chosen = MethodDef.of(SecondBound.class.getMethod("choose", String.class));
        var definition = ClassDef.builder("test.edges.IntersectionOverloads").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke(chosen, ExpressionDef.constant("abc")).returning())).build();
        var generated = define(definition);
        assertEquals("second", generated.getMethod("call", FirstBound.class)
            .invoke(generated.getConstructor().newInstance(), new BothBounds()));
    }

    @Test
    public void generatedConstructorChoosesMostSpecificSupertype() throws Exception {
        var selected = FieldDef.builder("selected", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build();
        var target = ClassDef.builder("test.edges.GeneratedConstructors").addModifiers(Modifier.PUBLIC).addField(selected)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .build((self, p) -> self.field(selected).assign(ExpressionDef.constant("object"))))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.of(CharSequence.class))
                .build((self, p) -> self.field(selected).assign(ExpressionDef.constant("sequence")))).build();
        assertEquals("sequence", run(caller(target.asTypeDef().instantiate(ExpressionDef.constant("abc")).field(selected)), target));
    }

    @Test
    public void inaccessibleConstructorIsNotAnOverloadCandidate() throws Exception {
        assertEquals("public", OverloadAccessOracle.construct().selected);
        assertEquals("public", run(caller(ClassTypeDef.of(AccessConstructors.class).instantiate(ExpressionDef.constant("abc"))
            .invoke(AccessConstructors.class.getMethod("getSelected")))));
    }

    @Test
    public void reflectedVarargsChooseMostSpecificWithNoArguments() throws Exception {
        assertEquals("strings", VarargsOverloads.choose());
        assertEquals(VarargsOverloads.choose(), run(caller(ClassTypeDef.of(VarargsOverloads.class)
            .invokeStatic("choose", TypeDef.STRING))));
    }

    @Test
    public void reflectedVarargsPackPrimitiveArguments() throws Exception {
        assertEquals("ints:2", VarargsOverloads.numeric(1, 2));
        assertEquals(VarargsOverloads.numeric(1, 2), run(caller(ClassTypeDef.of(VarargsOverloads.class)
            .invokeStatic("numeric", TypeDef.STRING, ExpressionDef.constant(1), ExpressionDef.constant(2)))));
    }

    @Test
    public void fixedArrayMethodReceivesNullWithoutPacking() throws Exception {
        assertEquals("null", ArrayOverload.choose(null));
        assertEquals(ArrayOverload.choose(null), run(caller(ClassTypeDef.of(ArrayOverload.class)
            .invokeStatic("choose", TypeDef.STRING, ExpressionDef.nullValue()))));
    }

    @Test
    public void reflectedOverloadAcceptsCovariantArrays() throws Exception {
        var values = TypeDef.STRING.array().instantiate(List.of(ExpressionDef.constant("abc")));
        assertEquals("array", ArraySpecificity.choose(new String[] {"abc"}));
        assertEquals("array", run(caller(ClassTypeDef.of(ArraySpecificity.class)
            .invokeStatic("choose", TypeDef.STRING, values))));
    }

    @Test
    public void typedNullUsesItsDeclaredTypeForOverloadSelection() throws Exception {
        assertEquals("object", ApplicabilityFixtures.Overloads.nullChoice((Object) null));
        assertEquals(ApplicabilityFixtures.Overloads.nullChoice((Object) null), run(ClassTypeDef.of(ApplicabilityFixtures.Overloads.class).invokeStatic("nullChoice", TypeDef.STRING,
            ExpressionDef.nullValue().cast(TypeDef.OBJECT))));
    }

    @Test
    public void generatedPrivateOverloadIsNotCallableFromAnotherClass() throws Exception {
        var target = ClassDef.builder("test.additional.PrivateOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(selection("choose", TypeDef.STRING, "private", Modifier.PRIVATE))
            .addMethod(selection("choose", TypeDef.OBJECT, "public", Modifier.PUBLIC)).build();
        assertEquals("public", run(target.asTypeDef().invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant("abc")), target));
    }

    @Test
    public void intersectionReceiverChoosesMatchingReturnDescriptor() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(NumberResult.class), TypeDef.of(IntegerResult.class));
        var definition = ClassDef.builder("test.additional.CovariantReceiver").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(variable).addParameter("input", TypeDef.variable("T")).returns(TypeDef.OBJECT)
                .build((self, p) -> p.getFirst().invoke("value", TypeDef.of(Integer.class)).returning())).build();
        assertEquals(7, define(definition).getMethod("call", NumberResult.class).invoke(null, new BothResults()));
    }

    @Test
    public void generatedPrivateConstructorIsNotCallableFromAnotherClass() throws Exception {
        var field = FieldDef.builder("selected", TypeDef.STRING).addModifiers(Modifier.PUBLIC).build();
        var target = ClassDef.builder("test.additional.PrivateConstructor").addModifiers(Modifier.PUBLIC).addField(field)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("input", TypeDef.OBJECT)
                .build((self, p) -> self.field(field).assign(ExpressionDef.constant("public"))))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PRIVATE).addParameter("input", TypeDef.STRING)
                .build((self, p) -> self.field(field).assign(ExpressionDef.constant("private")))).build();
        assertEquals("public", run(target.asTypeDef().instantiate(ExpressionDef.constant("abc")).field(field), target));
    }

    @Test
    public void generatedBoundedOverloadRetainsCalleeVariables() throws Exception {
        var method = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addParameter("input", TypeDef.variable("T"))
            .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("number").returning());
        var target = ClassDef.builder("test.additional.BoundedOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(method).addMethod(selection("choose", TypeDef.OBJECT, "object", Modifier.PUBLIC)).build();
        assertEquals("number", run(target.asTypeDef().invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant(7)), target));
    }

    @Test
    public void primitiveWideningAndBoxingRemainOrdered() throws Exception {
        assertEquals(ApplicabilityFixtures.Overloads.numeric(7), run(ClassTypeDef.of(ApplicabilityFixtures.Overloads.class).invokeStatic("numeric", TypeDef.STRING, ExpressionDef.constant(7))));
    }

    @Test
    public void packagePrivateReflectedOverloadIsNotAccessible() throws Exception {
        assertEquals("public", VisibilityOverloads.packageChoice("abc"));
        assertEquals(VisibilityOverloads.packageChoice("abc"), run(ClassTypeDef.of(VisibilityOverloads.class)
            .invokeStatic("packageChoice", TypeDef.STRING, ExpressionDef.constant("abc"))));
    }

    @Test
    public void protectedReflectedOverloadIsNotAccessible() throws Exception {
        assertEquals("public", VisibilityOverloads.protectedChoice("abc"));
        assertEquals(VisibilityOverloads.protectedChoice("abc"), run(ClassTypeDef.of(VisibilityOverloads.class)
            .invokeStatic("protectedChoice", TypeDef.STRING, ExpressionDef.constant("abc"))));
    }

    @Test
    public void packagePrivateOverloadAmbiguousWithAnAccessibleOneIsNotAccessible() throws Exception {
        // Among all the overloads `siblingChoice(Comparable)` and `siblingChoice(CharSequence)` are ambiguous for a
        // String; of those another package accesses, the Comparable one is the most specific
        assertEquals("comparable", VisibilityOverloads.siblingChoice("abc"));
        assertEquals(VisibilityOverloads.siblingChoice("abc"), run(ClassTypeDef.of(VisibilityOverloads.class)
            .invokeStatic("siblingChoice", TypeDef.STRING, ExpressionDef.constant("abc"))));
    }

    @Test
    public void packagePrivateConstructorIsNotAccessibleToASuperCall() throws Exception {
        // javac: `super("abc")` of a subclass in another package takes the public constructor
        var subclass = ClassDef.builder("test.additional.PackageConstructorChild").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(VisibilityOverloads.PackageConstructor.class))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> self.superRef().invokeSuperConstructor(ExpressionDef.constant("abc"))))
            .build();
        var instance = (VisibilityOverloads.PackageConstructor) define(subclass).getConstructor().newInstance();
        assertEquals("public", instance.selected());
    }

    @Test
    public void protectedConstructorIsAccessibleToASuperCall() throws Exception {
        // javac: `super("abc")` of a subclass reaches the protected constructor, the more specific one
        var subclass = ClassDef.builder("test.additional.ProtectedConstructorChild").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(VisibilityOverloads.ProtectedConstructor.class))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> self.superRef().invokeSuperConstructor(ExpressionDef.constant("abc"))))
            .build();
        var instance = (VisibilityOverloads.ProtectedConstructor) define(subclass).getConstructor().newInstance();
        assertEquals("protected", instance.selected());
    }

    @Test
    public void packagePrivateConstructorIsNotAccessible() throws Exception {
        assertEquals("public", new VisibilityOverloads.PackageConstructor("abc").selected());
        assertEquals("public", run(ClassTypeDef.of(VisibilityOverloads.PackageConstructor.class)
            .instantiate(ExpressionDef.constant("abc")).invoke("selected", TypeDef.STRING)));
    }

    @Test
    public void protectedConstructorIsNotAccessible() throws Exception {
        assertEquals("public", new VisibilityOverloads.ProtectedConstructor("abc").selected());
        assertEquals("public", run(ClassTypeDef.of(VisibilityOverloads.ProtectedConstructor.class)
            .instantiate(ExpressionDef.constant("abc")).invoke("selected", TypeDef.STRING)));
    }

    @Test
    public void concreteOverloadOutranksAWiderReceiverInstantiation() throws Exception {
        var receiver = new SpecializedReceiver<CharSequence>();
        assertEquals("concrete", receiver.choose("value"));
        assertCall(receiver.choose("value"), List.of(TypeDef.parameterized(SpecializedReceiver.class, TypeDef.of(CharSequence.class)), TypeDef.STRING),
            List.of(receiver, "value"), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void requestedGenericReturnKeepsTheMostSpecificOverload() throws Exception {
        assertCall(CaptureFixtures.Calls.targetReturn("value"), List.of(TypeDef.STRING), List.of("value"),
            p -> ClassTypeDef.of(CaptureFixtures.Calls.class).invokeStatic("targetReturn", TypeDef.of(Integer.class), p));
    }

    @Test
    public void parameterizedClassArgumentsChooseTheGenericConstructorOverload() throws Exception {
        assertCall("generic", List.of(TypeDef.STRING), List.of("value"), p ->
            TypeDef.parameterized(GenericConstructor.class, TypeDef.STRING).instantiate(p).invoke("selected", TypeDef.STRING));
    }

    @Test
    public void inheritedGenericOverloadDoesNotHideTheSpecializedDeclaration() throws Exception {
        var receiver = new SpecializedChild();
        assertEquals("concrete", receiver.choose("value"));
        assertCall(receiver.choose("value"), List.of(TypeDef.of(SpecializedChild.class), TypeDef.STRING),
            List.of(receiver, "value"), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void generatedInterfaceMethodsParticipateInOverloads() throws Exception {
        var contract = InterfaceDef.builder("test.completeness.OverloadedContract").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", TypeDef.of(CharSequence.class)).returns(TypeDef.STRING).build())
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING).build()).build();
        var implementation = ClassDef.builder("test.completeness.OverloadedImplementation").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(contract.asTypeDef())
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.of(CharSequence.class))
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("sequence").returning()))
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.OBJECT)
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("object").returning())).build();
        var caller = ClassDef.builder("test.completeness.InterfaceCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("receiver", contract.asTypeDef()).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant("value")).returning())).build();
        var loader = load(contract, implementation, caller);
        var result = loader.loadClass(caller.getName()).getMethod("call", loader.loadClass(contract.getName()))
            .invoke(null, loader.loadClass(implementation.getName()).getConstructor().newInstance());
        assertEquals("sequence", result);
    }

    /**
     * Generic overloads in generated definitions are distinguished by the complete bound chain. Both declaration
     * orders are checked, to detect accidental first-candidate selection.
     *
     * @param order Which overload the generated class declares first
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "with the {0} overload declared first, the dependent bound keeps its own descriptor")
    @ValueSource(strings = {"bounded", "object"})
    public void generatedDependentBoundsDoNotCollapseOverloadDescriptors(String order) throws Exception {
        boolean reverse = order.equals("object");
        var bounded = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("A", TypeDef.variable("B")))
            .addTypeVariable(TypeDef.variable("B", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("A")).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("number").returning());
        var fallback = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var targetDefinition = ClassDef.builder("test.completeness.GeneratedOverloads").addModifiers(Modifier.PUBLIC)
            .addMethod(reverse ? fallback : bounded).addMethod(reverse ? bounded : fallback).build();
        var caller = ClassDef.builder("test.completeness.GeneratedOverloadCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> targetDefinition.asTypeDef().invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant(1)).returning())).build();
        assertEquals("number", load(targetDefinition, caller).loadClass(caller.getName()).getMethod("call").invoke(null));
    }

    @Test
    public void boundedArgumentCanSelectAnOverloadOnItsSecondaryBound() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.OBJECT, TypeDef.of(Shared.class));
        var definition = ClassDef.builder("test.inference.BoundedArgument").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(variable).addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("bound", TypeDef.STRING, p.getFirst()).returning())).build();
        assertEquals("shared", define(definition).getMethod("call", Object.class).invoke(null, new Left()));
    }

    @Test
    public void generatedMethodOverloadsHaveDistinctBoundedDescriptors() throws Exception {
        var number = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("number").returning());
        var object = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var target = ClassDef.builder("test.inference.GeneratedMethodOverloads").addModifiers(Modifier.PUBLIC)
            .addMethod(number).addMethod(object).build();
        var caller = ClassDef.builder("test.inference.GeneratedMethodCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> target.asTypeDef().invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant("a")).returning())).build();
        assertEquals(new RawBoundedReceiver<Number>().choose("a"),
            load(target, caller).loadClass(caller.getName()).getMethod("call").invoke(null));
    }

    @Test
    public void generatedClassOverloadsHaveDistinctBoundedDescriptors() throws Exception {
        var number = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("number").returning());
        var object = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant("object").returning());
        var target = ClassDef.builder("test.inference.GeneratedClassOverloads").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(number).addMethod(object).build();
        var caller = ClassDef.builder("test.inference.GeneratedClassCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> target.asTypeDef().instantiate().invoke("choose", TypeDef.STRING, ExpressionDef.constant("a")).returning())).build();
        assertEquals(new RawBoundedReceiver<Number>().choose("a"),
            load(target, caller).loadClass(caller.getName()).getMethod("call").invoke(null));
    }

    @Test
    public void mostSpecificMethodResolvesDependentVariableBounds() throws Exception {
        assertEquals("number", InferenceFixtures.Calls.chain(7));
        assertCall(InferenceFixtures.Calls.chain(7), List.of(TypeDef.Primitive.INT), List.of(7), p -> ClassTypeDef.of(InferenceFixtures.Calls.class).invokeStatic("chain", TypeDef.STRING, p));
    }

    /**
     * A generated overload whose parameter is bounded through a long chain of dependent variables stays more
     * specific than an overload taking Object.
     *
     * @param length The number of variables in the chain
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "an overload bounded through a chain of {0} variables is more specific than Object")
    @ValueSource(ints = {4, 8, 16, 32})
    public void generatedOverloadsKeepLongDependentBounds(int length) throws Exception {
        var method = MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.variable("V0")).returns(TypeDef.STRING);
        for (int i = 0; i < length; i++) {
            method.addTypeVariable(TypeDef.variable("V" + i,
                i + 1 == length ? TypeDef.of(Number.class) : TypeDef.variable("V" + (i + 1))));
        }
        var overloads = ClassDef.builder("test.owner.LongBounds").addModifiers(Modifier.PUBLIC)
            .addMethod(method.build((self, p) -> ExpressionDef.constant("number").returning()))
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("object").returning())).build();
        var caller = ClassDef.builder("test.owner.LongBoundsCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> overloads.asTypeDef().invokeStatic("choose", TypeDef.STRING,
                    ExpressionDef.constant(1).cast(TypeDef.of(Integer.class))).returning())).build();
        var loader = load(overloads, caller);
        assertEquals("number", loader.loadClass(caller.getName()).getMethod("call").invoke(null));
    }

    @Test
    public void unrelatedGenericMethodDoesNotMakeSpecificOverloadAmbiguous() throws Exception {
        assertCall(EnclosingTypeFixtures.Calls.specific("hello"), List.of(TypeDef.STRING), List.of("hello"),
            p -> ClassTypeDef.of(EnclosingTypeFixtures.Calls.class).invokeStatic("specific", TypeDef.STRING, p));
    }

    @Test
    public void inheritedCovariantInterfaceMethodsAreOneOverload() throws Exception {
        var receiver = new DiamondImplementation();
        assertCall(receiver.value("hello"), List.of(TypeDef.of(Diamond.class), TypeDef.STRING), List.of(receiver, "hello"),
            p -> p.getFirst().invoke("value", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void secondaryMethodBoundConstrainsMostSpecificOverload() throws Exception {
        var value = new Both();
        assertCall(ResolutionFixtures.Calls.intersection(value), List.of(TypeDef.of(Both.class)), List.of(value),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("intersection", TypeDef.STRING, p));
    }

    @Test
    public void differentVarargsAritiesChooseTheMostSpecificElement() throws Exception {
        assertCall(ResolutionFixtures.Calls.varargs("a", "b"), List.of(TypeDef.STRING, TypeDef.STRING), List.of("a", "b"),
            p -> ClassTypeDef.of(ResolutionFixtures.Calls.class).invokeStatic("varargs", TypeDef.STRING, p));
    }

    /**
     * A single primitive or boxed argument converts to the parameter of the overload javac chooses: primitive
     * widening in the strict phase, and unboxing followed by widening in the loose phase.
     *
     * @param method   The overloaded method of {@link OverloadFixtures.Calls}
     * @param type     The type of the argument
     * @param argument The argument
     * @param expected javac's own call of the same overload with the same argument
     * @throws Exception If the generated call cannot be loaded or invoked
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("primitiveArgumentCalls")
    public void primitiveArgumentChoosesJavacsOverload(String method, TypeDef type, Object argument, Object expected) throws Exception {
        assertCall(expected, List.of(type), List.of(argument),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic(method, TypeDef.STRING, p));
    }

    static Stream<Arguments> primitiveArgumentCalls() {
        return Stream.of(
            Arguments.of(Named.of("a char argument widens to int before long", "widest"),
                TypeDef.Primitive.CHAR, 'x', OverloadFixtures.Calls.widest('x')),
            Arguments.of(Named.of("a short argument does not widen to char", "narrow"),
                TypeDef.Primitive.SHORT, (short) 1, OverloadFixtures.Calls.narrow((short) 1)),
            Arguments.of(Named.of("a byte argument prefers short", "small"),
                TypeDef.Primitive.BYTE, (byte) 1, OverloadFixtures.Calls.small((byte) 1)),
            Arguments.of(Named.of("a boxed Integer unboxes and widens to long", "unboxing"),
                TypeDef.of(Integer.class), 5, OverloadFixtures.Calls.unboxing(Integer.valueOf(5))),
            Arguments.of(Named.of("a boxed Character unboxes and widens to int", "character"),
                TypeDef.of(Character.class), 'x', OverloadFixtures.Calls.character(Character.valueOf('x'))),
            Arguments.of(Named.of("a boxed Character unboxes and widens to long", "characterLong"),
                TypeDef.of(Character.class), 'x', OverloadFixtures.Calls.characterLong(Character.valueOf('x'))),
            Arguments.of(Named.of("a char argument widens to double", "real"),
                TypeDef.Primitive.CHAR, 'x', OverloadFixtures.Calls.real('x')));
    }

    @Test
    public void nullPrefersCharArrayOverObject() throws Exception {
        assertCall(OverloadFixtures.Calls.nullable(null), List.of(), List.of(),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("nullable", TypeDef.STRING, ExpressionDef.nullValue()));
    }

    /**
     * A variable arity call chooses the overload javac chooses and packs, widens or boxes its elements as javac
     * does, while an array argument is passed as the array itself by strict invocation.
     *
     * @param method    The overloaded method of {@link OverloadFixtures.Calls}
     * @param types     The types of the arguments
     * @param arguments The arguments
     * @param expected  javac's own call of the same overload with the same arguments
     * @throws Exception If the generated call cannot be loaded or invoked
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("variableArityCalls")
    public void variableArityCallChoosesJavacsOverload(String method, List<TypeDef> types, List<?> arguments, Object expected) throws Exception {
        assertCall(expected, types, arguments,
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic(method, TypeDef.STRING, p));
    }

    static Stream<Arguments> variableArityCalls() {
        String[] values = {"a", "b"};
        return Stream.of(
            Arguments.of(Named.of("an array argument is passed to Object... by strict invocation", "array"),
                List.of(TypeDef.STRING.array()), List.of((Object) values), OverloadFixtures.Calls.array(values)),
            Arguments.of(Named.of("int... is more specific than long...", "ints"),
                List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT), List.of(1, 2), OverloadFixtures.Calls.ints(1, 2)),
            Arguments.of(Named.of("int arguments widen to the elements of long...", "longs"),
                List.of(TypeDef.Primitive.INT, TypeDef.Primitive.INT), List.of(1, 2), OverloadFixtures.Calls.longs(1, 2)),
            Arguments.of(Named.of("primitive arguments are boxed as the elements of Object...", "objects"),
                List.of(TypeDef.Primitive.INT, TypeDef.Primitive.CHAR), List.of(1, 'c'), OverloadFixtures.Calls.objects(1, 'c')));
    }

    @Test
    public void nonGenericOverloadIsMoreSpecificThanAGenericOne() throws Exception {
        assertCall(OverloadFixtures.Calls.generic("a"), List.of(TypeDef.STRING), List.of("a"),
            p -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("generic", TypeDef.STRING, p));
    }

    @Test
    public void protectedOverloadIsMoreSpecificFromASubclass() throws Exception {
        var definition = ClassDef.builder("test.hardening.ProtectedCaller").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(ProtectedOverloads.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> self.invoke("choose", TypeDef.STRING, p.getFirst()).returning()))
            .build();
        Class<?> generated = define(definition);
        assertEquals(new ProtectedOverloadsChild().call("x"),
            generated.getMethod("call", String.class).invoke(generated.getConstructor().newInstance(), "x"));
    }

    @Test
    public void packagePrivateOverloadIsMoreSpecificFromTheSamePackage() throws Exception {
        var definition = ClassDef.builder(AbstractByteCodeWriterTck.class.getPackageName() + ".HardeningPackageCaller")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> ClassTypeDef.of(PackageOverloads.class)
                    .invokeStatic("choose", TypeDef.STRING, p).returning()))
            .build();
        Class<?> generated = defineInPackage(definition);
        assertEquals(PackageOverloads.choose("x"), generated.getMethod("call", String.class).invoke(null, "x"));
    }

    @Test
    public void protectedOverloadIsMoreSpecificThroughAReceiverOfTheSubclass() throws Exception {
        String name = "test.hardening.ProtectedReceiver";
        // The definition as a receiver's type: its supertypes carry the members
        var shell = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(WiderProtectedOverloads.class)).build();
        var definition = ClassDef.builder(name).addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(WiderProtectedOverloads.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("receiver", shell.asTypeDef()).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)).returning()))
            .build();
        Class<?> generated = define(definition);
        Object receiver = generated.getConstructor().newInstance();
        assertEquals(WiderProtectedOverloadsChild.call(new WiderProtectedOverloadsChild(), "x"),
            generated.getMethod("call", generated, String.class).invoke(null, receiver, "x"));
    }

    @Test
    public void genuinelyAmbiguousCallIsRejected() {
        // javac: "reference to ambiguous is ambiguous" - the call is not written at all
        Object outcome = outcome(() -> {
            var definition = ClassDef.builder("test.hardening.Ambiguous").addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter("first", TypeDef.STRING).addParameter("second", TypeDef.STRING).returns(TypeDef.STRING)
                    .build((self, p) -> ClassTypeDef.of(OverloadFixtures.Calls.class).invokeStatic("ambiguous", TypeDef.STRING, p).returning())).build();
            Class<?> generated;
            try {
                generated = define(definition);
            } catch (RuntimeException e) {
                return "rejected";
            }
            return "written, then called: " + outcome(() -> generated.getMethod("call", String.class, String.class).invoke(null, "a", "b"));
        });
        assertEquals("rejected", outcome);
    }

    @Test
    public void intersectionReceiverChoosesTheMostSpecificOverloadOfAnyBound() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(ObjectChooser.class), TypeDef.of(StringChooser.class));
        var definition = ClassDef.builder("test.hardening.IntersectionOverloads").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("pick", TypeDef.STRING, ExpressionDef.constant("x")).returning())).build();
        var value = new BothChoosers();
        assertEquals(Receivers.intersection(value), define(definition).getMethod("call", ObjectChooser.class).invoke(null, value));
    }

    @Test
    public void intersectionReceiverSkipsAnInapplicableFirstBound() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(IntegerTaker.class), TypeDef.of(StringTaker.class));
        var definition = ClassDef.builder("test.hardening.IntersectionTakers").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", variable).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("take", TypeDef.STRING, ExpressionDef.constant("x")).returning())).build();
        var value = new BothTakers();
        assertEquals(Receivers.takers(value), define(definition).getMethod("call", IntegerTaker.class).invoke(null, value));
    }

    @Test
    public void overloadsOfASuperclassAndAnInterfaceCompete() throws Exception {
        var value = new SuperAndInterface();
        assertCall(value.pick("x"), List.of(TypeDef.of(SuperAndInterface.class)), List.of(value),
            p -> p.getFirst().invoke("pick", TypeDef.STRING, ExpressionDef.constant("x")));
    }

    private Object callOverload(String name, ExpressionDef argument) throws Exception {
        var def = ClassDef.builder("test.InferredOverload").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING)
                .build((self, p) -> ClassTypeDef.of(Overloads.class).invokeStatic(name, TypeDef.STRING, argument).returning()))
            .build();
        return define(def).getMethod("call").invoke(null);
    }

    /**
     * Reflected overloads with observably different results.
     *
     * @since 2.3
     */
    public static class Overloads {
        private final String selected;

        /**
         * Creates an instance recording the selected overload.
         *
         * @param value An object
         */
        public Overloads(Object value) {
            selected = "object";
        }

        /**
         * Creates an instance recording the selected overload.
         *
         * @param value A sequence
         */
        public Overloads(CharSequence value) {
            selected = "sequence";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value An object
         * @return The selected overload
         */
        public static String reference(Object value) {
            return "object";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value A sequence
         * @return The selected overload
         */
        public static String reference(CharSequence value) {
            return "sequence";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value A long
         * @return The selected overload
         */
        public static String numeric(long value) {
            return "long";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value A boxed integer
         * @return The selected overload
         */
        public static String numeric(Integer value) {
            return "boxed";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value A long
         * @return The selected overload
         */
        public static String widen(long value) {
            return "long";
        }

        /**
         * Reports whether the varargs array itself is null.
         *
         * @param values The values
         * @return The array state
         */
        public static String varargs(Object... values) {
            return values == null ? "null" : "array";
        }

        /**
         * Identifies the fixed arity overload.
         *
         * @param value The value
         * @return The selected overload
         */
        public static String fixed(Object value) {
            return "object";
        }

        /**
         * Identifies the variable arity overload.
         *
         * @param values The values
         * @return The selected overload
         */
        public static String fixed(String... values) {
            return "varargs";
        }

        /**
         * Identifies the selected overload.
         *
         * @param value A boolean
         * @return The selected overload
         */
        public static String widen(boolean value) {
            return "boolean";
        }
    }

    /**
     * Overloads whose specificity changes when the receiver is parameterized.
     * @param <T> The receiver's type argument
     * @since 2.3
     */
    public static class ReceiverOverloads<T> {
        /** @param value The input
         * @return The chosen overload
         */
        public String choose(T value) {
            return "generic";
        }

        /** @param value The input
         * @return The chosen overload
         */
        public String choose(CharSequence value) {
            return "sequence";
        }
    }

    /** Reflected generic overloads.
     * @since 2.3
     */
    public static class CompiledOverloads {
        /** @param <T> The constrained input
         * @param value The input
         * @return The chosen overload
         */
        public static <T extends Number & Runnable> String constrained(T value) {
            return "intersection";
        }

        /** @param value The input
         * @return The chosen overload
         */
        public static String constrained(Object value) {
            return "object";
        }

        /** @param value The input
         * @return The chosen overload
         */
        public static String collections(List<String> value) {
            return "strings";
        }

        /** @param value The input
         * @return The chosen overload
         */
        public static String collections(Collection<Integer> value) {
            return "integers";
        }
    }

    /** Public alternatives to inaccessible overloads.
     * @since 2.3
     */
    public static class AccessOverloads {
        /** @param value The input
         * @return The public overload
         */
        public static String choose(Object value) {
            return "public";
        }

        private static String choose(String value) {
            return "private";
        }
    }

    /** First overloaded intersection bound.
     * @since 2.3
     */
    public interface FirstBound {
        /** @param value The input
         * @return The chosen overload
         */
        String choose(Integer value);
    }

    /** Second overloaded intersection bound.
     * @since 2.3
     */
    public interface SecondBound {
        /** @param value The input
         * @return The chosen overload
         */
        String choose(String value);
    }

    /** Implements both intersection bounds.
     * @since 2.3
     */
    public static class BothBounds implements FirstBound, SecondBound {
        @Override
        public String choose(Integer value) {
            return "first";
        }

        @Override
        public String choose(String value) {
            return "second";
        }
    }

    /** Constructors with different access levels.
     * @since 2.3
     */
    public static class AccessConstructors {
        /** The selected constructor. */
        private final String selected;

        /** @param value The input */
        public AccessConstructors(Object value) {
            selected = "public";
        }

        private AccessConstructors(String value) {
            selected = "private";
        }

        /** @return The selected constructor */
        public String getSelected() {
            return selected;
        }
    }

    /** Varargs overload controls.
     * @since 2.3
     */
    public static class VarargsOverloads {
        /** @param values The inputs
         * @return The chosen overload
         */
        public static String choose(Object... values) {
            return "objects";
        }

        /** @param values The inputs
         * @return The chosen overload
         */
        public static String choose(String... values) {
            return "strings";
        }

        /** @param values The inputs
         * @return The chosen overload and length
         */
        public static String numeric(int... values) {
            return "ints:" + values.length;
        }

        /** @param values The inputs
         * @return The chosen overload and length
         */
        public static String numeric(long... values) {
            return "longs:" + values.length;
        }
    }

    /** A fixed arity array declaration.
     * @since 2.3
     */
    public static class ArrayOverload {
        /** @param values The input
         * @return Whether the array is null
         */
        public static String choose(String[] values) {
            return values == null ? "null" : "array";
        }
    }

    /** Array covariance control.
     * @since 2.3
     */
    public static class ArraySpecificity {
        /** @param value The input
         * @return The chosen overload
         */
        public static String choose(Object value) {
            return "object";
        }

        /** @param value The input
         * @return The chosen overload
         */
        public static String choose(Object[] value) {
            return "array";
        }
    }

    /** Covariant receiver bound.
     * @since 2.3
     */
    public interface NumberResult {
        Number value();
    }

    /** Covariant receiver bound.
     * @since 2.3
     */
    public interface IntegerResult {
        Integer value();
    }

    /** Implements both return descriptors.
     * @since 2.3
     */
    public static class BothResults implements NumberResult, IntegerResult {
        @Override
        public Integer value() {
            return 7;
        }
    }

    /** Generic receiver with a concrete overload.
     * @param <T> Receiver variable
     * @since 2.3
     */
    public static class SpecializedReceiver<T> {
        public String choose(T value) {
            return "generic";
        }

        public String choose(String value) {
            return "concrete";
        }
    }

    /** Receiver specialized through inheritance.
     * @since 2.3
     */
    public static class SpecializedChild extends SpecializedReceiver<CharSequence> { }

    /** Generic class with a class-variable overload and an Object fallback.
     * @param <T> The selected value type
     * @since 2.3
     */
    public static class GenericConstructor<T extends CharSequence> {
        private final String selected;

        /**
         * @param value The selected value
         */
        public GenericConstructor(T value) {
            selected = "generic";
        }

        /**
         * @param value The fallback value
         */
        public GenericConstructor(Object value) {
            selected = "object";
        }

        /**
         * @return The selected constructor
         */
        public String selected() {
            return selected;
        }
    }

    /** Wide covariant declaration.
     * @since 2.3
     */
    public interface Wide {
        Object value(CharSequence value);
    }

    /** Narrow covariant declaration.
     * @since 2.3
     */
    public interface Narrow {
        String value(CharSequence value);
    }

    /** Inherited covariant declarations.
     * @since 2.3
     */
    public interface Diamond extends Wide, Narrow { }

    /** Implementation used by the independent oracle.
     * @since 2.3
     */
    public static class DiamondImplementation implements Diamond {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @since 2.3
         */
        @Override
        public String value(CharSequence value) {
            return value.toString();
        }
    }

    /** Class satisfying both intersection bounds.
     * @since 2.3
     */
    public static class Both implements First, Second { }

    /** An overload of a superclass.
     * @since 2.3
     */
    public static class ObjectPicker {
        public String pick(Object value) {
            return "object";
        }
    }

    /** An overload of an interface.
     * @since 2.3
     */
    public interface StringPicker {
        default String pick(String value) {
            return "string";
        }
    }

    /** Both.
     * @since 2.3
     */
    public static class SuperAndInterface extends ObjectPicker implements StringPicker { }

    /** Both bounds.
     * @since 2.3
     */
    public static class BothChoosers implements ObjectChooser, StringChooser { }

    /** Both bounds.
     * @since 2.3
     */
    public static class BothTakers implements IntegerTaker, StringTaker { }

    /** A public and a protected overload taking a supertype of the argument.
     * @since 2.3
     */
    public static class WiderProtectedOverloads {
        public String choose(Object value) {
            return "public-object";
        }

        protected String choose(CharSequence value) {
            return "protected-sequence";
        }
    }

    /** The javac-compiled subclass calling the overloads through a receiver of its own type.
     * @since 2.3
     */
    public static class WiderProtectedOverloadsChild extends WiderProtectedOverloads {
        public static String call(WiderProtectedOverloadsChild receiver, String value) {
            return receiver.choose(value);
        }
    }

    /** A public and a protected overload.
     * @since 2.3
     */
    public static class ProtectedOverloads {
        public String choose(Object value) {
            return "public-object";
        }

        protected String choose(String value) {
            return "protected-string";
        }
    }

    /** The javac-compiled subclass calling the overloads.
     * @since 2.3
     */
    public static class ProtectedOverloadsChild extends ProtectedOverloads {
        public String call(String value) {
            return choose(value);
        }
    }

    /** A public and a package-private overload.
     * @since 2.3
     */
    public static class PackageOverloads {
        public static String choose(Object value) {
            return "public-object";
        }

        static String choose(String value) {
            return "package-string";
        }
    }
}

// Kept outside the fixture nest: javac must perform the same access checks as the generated caller.
final class OverloadAccessOracle {
    static String choose() {
        return OverloadSelectionTck.AccessOverloads.choose("abc");
    }

    static OverloadSelectionTck.AccessConstructors construct() {
        return new OverloadSelectionTck.AccessConstructors("abc");
    }
}
