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

import io.micronaut.sourcegen.bytecode.tck.CaptureFixtures.Calls;
import io.micronaut.sourcegen.bytecode.tck.InferenceFixtures.RawBoundedReceiver;
import io.micronaut.sourcegen.bytecode.tck.OverloadFixtures.Receivers;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calls through a receiver: type variable, raw, parameterized, intersection and interface receivers, and
 * the class scope in which a callee's type variables and inherited members are resolved.
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
public abstract class ReceiverTck extends AbstractByteCodeWriterTck {

    @Test
    public void invokesMethodsThroughInterfaceBoundTypeVariables() throws Exception {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        ClassDef definition = ClassDef.builder("example.TckTypeVariableInvocationParity")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("length")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(variable)
                .addParameter("value", variable)
                .returns(TypeDef.Primitive.INT)
                .build((ignored, parameters) -> parameters.get(0)
                    .invoke("length", TypeDef.Primitive.INT).returning()))
            .build();

        Class<?> generated = define(definition);
        assertEquals(5, generated.getMethod("length", CharSequence.class).invoke(null, "hello"));
    }

    @Test
    public void writesDefaultMethodCallsOnAnInterfaceReceiverWithInvokeInterface() throws Exception {
        MethodDef greet = MethodDef.builder("greet")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build((aThis, parameters) -> ExpressionDef.constant("hi").returning());
        InterfaceDef contract = InterfaceDef.builder("example.TckDefaultCallContract")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(greet)
            .build();
        ClassDef implementation = ClassDef.builder("example.TckDefaultCallImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(contract.asTypeDef())
            .build();
        ClassDef caller = ClassDef.builder("example.TckDefaultCaller")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("target", contract.asTypeDef())
                .returns(TypeDef.STRING)
                // The model flags the target as a default method; the call is still virtual
                .build((ignored, parameters) -> new ExpressionDef.InvokeInstanceMethod(
                    parameters.get(0), greet, true, List.of()).returning()))
            .build();

        GeneratedClassLoader loader = new GeneratedClassLoader(Map.of(
            contract.getName(), write(contract),
            implementation.getName(), write(implementation),
            caller.getName(), write(caller)
        ));
        Object instance = loader.loadClass(implementation.getName()).getConstructor().newInstance();
        Class<?> callerClass = loader.loadClass(caller.getName());

        assertEquals("hi", callerClass.getMethod("call", loader.loadClass(contract.getName())).invoke(null, instance));
    }

    @Test
    public void invokesInterfaceBoundClassVariable() throws Exception {
        var variable = TypeDef.variable("T");
        var length = MethodDef.of(CharSequence.class.getMethod("length"));
        var type = define(ClassDef.builder("test.InterfaceBoundReceiver").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("length").addModifiers(Modifier.PUBLIC)
                .addParameter("value", variable).returns(TypeDef.Primitive.INT)
                .build((self, p) -> p.getFirst().invoke(length).returning())).build());
        assertEquals(3, type.getMethod("length", CharSequence.class).invoke(type.getConstructor().newInstance(), "abc"));
    }

    @Test
    public void invokesInlineInterfaceBoundVariable() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(CharSequence.class));
        var length = MethodDef.of(CharSequence.class.getMethod("length"));
        var type = define(ClassDef.builder("test.InlineInterfaceBound").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("length").addModifiers(Modifier.PUBLIC)
                .addParameter("value", variable).returns(TypeDef.Primitive.INT)
                .build((self, p) -> p.getFirst().invoke(length).returning())).build());
        assertEquals(3, type.getMethod("length", CharSequence.class).invoke(type.getConstructor().newInstance(), "abc"));
    }

    @Test
    public void invokesGenericCalleeInItsOwnClassScope() throws Exception {
        var variable = TypeDef.variable("T");
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addParameter("value", variable).returns(variable).build((self, p) -> p.getFirst().returning());
        var callee = ClassDef.builder("test.GenericCallee").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(identity).build();
        var caller = ClassDef.builder("test.GenericCaller").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addParameter("target", ClassTypeDef.of(callee)).returns(TypeDef.of(Number.class))
                .build((self, p) -> p.getFirst().invoke(identity, ExpressionDef.constant(7)).returning())).build();
        var loader = load(callee, caller);
        var calleeClass = loader.loadClass(callee.getName());
        var callerClass = loader.loadClass(caller.getName());
        assertEquals(7, callerClass.getMethod("call", calleeClass)
            .invoke(callerClass.getConstructor().newInstance(), calleeClass.getConstructor().newInstance()));
    }

    @Test
    public void generatedReceiverResolvesInheritedMethod() throws Exception {
        var parent = ClassDef.builder("test.GeneratedOverloadParent").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("select").addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build((self, p) -> ExpressionDef.constant("object").returning())).build();
        var child = ClassDef.builder("test.GeneratedOverloadChild").addModifiers(Modifier.PUBLIC)
            .superclass(parent.asTypeDef()).build();
        var caller = ClassDef.builder("test.InheritedCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("receiver", child.asTypeDef()).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("select", TypeDef.STRING, ExpressionDef.constant("ok")).returning())).build();
        var loader = load(parent, child, caller);
        var childClass = loader.loadClass(child.getName());
        assertEquals("object", loader.loadClass(caller.getName()).getMethod("call", childClass)
            .invoke(null, childClass.getConstructor().newInstance()));
    }

    @Test
    public void invokesMethodDeclaredOnSecondaryIntersectionBound() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(CharSequence.class),
            TypeDef.parameterized(Comparable.class, TypeDef.variable("T")));
        var compare = MethodDef.of(Comparable.class.getMethod("compareTo", Object.class));
        var type = define(ClassDef.builder("test.SecondaryBound").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("compare").addModifiers(Modifier.PUBLIC)
                .addParameter("value", variable).returns(TypeDef.Primitive.INT)
                .build((self, p) -> p.getFirst().invoke(compare, ExpressionDef.constant("abc")).returning())).build());
        assertEquals(0, type.getMethod("compare", CharSequence.class).invoke(type.getConstructor().newInstance(), "abc"));
    }

    @Test
    public void readsGenericFieldInItsDeclaringClassScope() throws Exception {
        var field = FieldDef.builder("value", TypeDef.variable("T")).addModifiers(Modifier.PUBLIC).build();
        var holder = ClassDef.builder("test.GenericFieldHolder").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addField(field).build();
        var caller = ClassDef.builder("test.GenericFieldCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("read").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("holder", holder.asTypeDef()).returns(TypeDef.of(Number.class))
                .build((self, p) -> p.getFirst().field(field).returning())).build();
        var loader = load(holder, caller);
        var holderClass = loader.loadClass(holder.getName());
        var instance = holderClass.getConstructor().newInstance();
        holderClass.getField("value").set(instance, 7);
        assertEquals(7, loader.loadClass(caller.getName()).getMethod("read", holderClass).invoke(null, instance));
    }

    @Test
    public void inheritedGenericMemberKeepsItsDeclaringClassBound() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var parent = ClassDef.builder("test.edges.GenericParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(identity).build();
        var child = ClassDef.builder("test.edges.GenericChild").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class))).build();
        var definition = ClassDef.builder("test.edges.InheritedGenericCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("child", child.asTypeDef()).returns(TypeDef.OBJECT)
                .build((self, p) -> p.getFirst().invoke(identity, ExpressionDef.constant(7)).returning())).build();
        var loader = load(parent, child, definition);
        var childClass = loader.loadClass(child.getName());
        assertEquals(7, loader.loadClass(definition.getName()).getMethod("call", childClass)
            .invoke(null, childClass.getConstructor().newInstance()));
    }

    @Test
    public void inheritedGenericFieldKeepsItsDeclaringClassBound() throws Exception {
        var field = FieldDef.builder("value", TypeDef.variable("T")).addModifiers(Modifier.PUBLIC).build();
        var parent = ClassDef.builder("test.edges.GenericFieldParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addField(field).build();
        var child = ClassDef.builder("test.edges.GenericFieldChild").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class))).build();
        var definition = ClassDef.builder("test.edges.InheritedFieldCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("child", child.asTypeDef()).returns(TypeDef.OBJECT)
                .build((self, p) -> p.getFirst().field(field).returning())).build();
        var loader = load(parent, child, definition);
        var childClass = loader.loadClass(child.getName());
        var instance = childClass.getConstructor().newInstance();
        childClass.getField("value").set(instance, 7);
        assertEquals(7, loader.loadClass(definition.getName()).getMethod("call", childClass).invoke(null, instance));
    }

    @Test
    public void methodVariableShadowsParameterizedReceiverVariable() throws Exception {
        var input = new ShadowedReceiver<String>();
        var definition = callWithParameter(TypeDef.parameterized(ShadowedReceiver.class, TypeDef.STRING), p ->
            p.invoke("choose", TypeDef.STRING, ExpressionDef.constant(1)));
        assertEquals("number", input.choose(1));
        assertEquals(input.choose(1), define(definition).getMethod("call", ShadowedReceiver.class).invoke(null, input));
    }

    @Test
    public void inheritedReceiverArgumentsParticipateInOverloads() throws Exception {
        var input = new StringReceiver();
        var definition = callWithParameter(TypeDef.of(StringReceiver.class), p -> p.invoke("choose", TypeDef.STRING, ExpressionDef.constant("abc")));
        assertEquals("generic", input.choose("abc"));
        assertEquals(input.choose("abc"), define(definition).getMethod("call", StringReceiver.class).invoke(null, input));
    }

    @Test
    public void unboundedCalleeVariableDoesNotCaptureCallerBound() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(TypeDef.variable("T")).addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.additional.UnboundedCallee").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        var caller = ClassDef.builder("test.additional.BoundedCaller").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.OBJECT)
                .build((self, p) -> target.asTypeDef().invokeStatic(identity, ExpressionDef.constant("abc")).returning())).build();
        assertEquals("abc", load(target, caller).loadClass(caller.getName()).getMethod("call").invoke(null));
    }

    @Test
    public void calleeClassBoundChainDoesNotCaptureMethodShadow() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("B", TypeDef.of(CharSequence.class)))
            .addParameter("value", TypeDef.variable("A")).returns(TypeDef.variable("A"))
            .build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.additional.ChainedCallee").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A", TypeDef.variable("B")))
            .addTypeVariable(TypeDef.variable("B", TypeDef.of(Number.class))).addMethod(identity).build();
        var caller = callWithParameter(target.asTypeDef(), p -> p.invoke(identity, ExpressionDef.constant(7)));
        var loader = load(target, caller);
        var clazz = loader.loadClass(target.getName());
        assertEquals(7, loader.loadClass(caller.getName()).getMethod("call", clazz).invoke(null, clazz.getConstructor().newInstance()));
    }

    @Test
    public void overloadedChildDoesNotHideDeclaringScopeOfInheritedMethod() throws Exception {
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T"))
            .build((self, p) -> p.getFirst().returning());
        var parent = ClassDef.builder("test.additional.OverloadedParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(identity).build();
        var child = ClassDef.builder("test.additional.OverloadedChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class)))
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("identity").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> p.getFirst().returning())).build();
        var caller = callWithParameter(child.asTypeDef(), p -> p.invoke(identity, ExpressionDef.constant(7)));
        var loader = load(parent, child, caller);
        var clazz = loader.loadClass(child.getName());
        assertEquals(7, loader.loadClass(caller.getName()).getMethod("call", clazz).invoke(null, clazz.getConstructor().newInstance()));
    }

    @Test
    public void receiverBoundChainRetainsClassScope() throws Exception {
        var definition = ClassDef.builder("test.additional.ScopedReceiver").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A", TypeDef.variable("B")))
            .addTypeVariable(TypeDef.variable("B", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeDef.variable("B", TypeDef.of(CharSequence.class)))
                .addParameter("input", TypeDef.variable("A")).returns(TypeDef.Primitive.INT)
                .build((self, p) -> p.getFirst().invoke("intValue", TypeDef.Primitive.INT).returning())).build();
        var clazz = define(definition);
        assertEquals(7, clazz.getMethod("call", Number.class).invoke(clazz.getConstructor().newInstance(), 7));
    }

    @Test
    public void longCalleeBoundChainResolvesWithoutArbitraryDepthLimit() throws Exception {
        var method = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("input", TypeDef.variable("T0")).returns(TypeDef.variable("T0"));
        for (int i = 0; i < 12; i++) {
            method.addTypeVariable(TypeDef.variable("T" + i, i == 11 ? TypeDef.of(Number.class) : TypeDef.variable("T" + (i + 1))));
        }
        var identity = method.build((self, p) -> p.getFirst().returning());
        var target = ClassDef.builder("test.additional.LongBounds").addModifiers(Modifier.PUBLIC).addMethod(identity).build();
        assertEquals(7, run(target.asTypeDef().invokeStatic(identity, ExpressionDef.constant(7)), target));
    }

    @Test
    public void generatedInheritedReceiverBindsSuperclassVariable() throws Exception {
        var parent = ClassDef.builder("test.additional.ReceiverParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("input", TypeDef.variable("T"))
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("generic").returning()))
            .addMethod(MethodDef.builder("choose").addModifiers(Modifier.PUBLIC).addParameter("input", TypeDef.of(CharSequence.class))
                .returns(TypeDef.STRING).build((self, p) -> ExpressionDef.constant("sequence").returning())).build();
        var child = ClassDef.builder("test.additional.ReceiverChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING)).build();
        var caller = callWithParameter(child.asTypeDef(), p -> p.invoke("choose", TypeDef.STRING, ExpressionDef.constant("abc")));
        var loader = load(parent, child, caller);
        var clazz = loader.loadClass(child.getName());
        assertEquals(new StringReceiver().choose("abc"), loader.loadClass(caller.getName()).getMethod("call", clazz)
            .invoke(null, clazz.getConstructor().newInstance()));
    }

    @Test
    public void methodBoundUsesReceiverSubstitution() throws Exception {
        var input = new ReceiverBound<String>();
        var definition = callWithParameter(TypeDef.parameterized(ReceiverBound.class, TypeDef.STRING), p ->
            p.invoke("choose", TypeDef.STRING, ClassTypeDef.of(StringBuilder.class).instantiate()));
        assertEquals("object", input.choose(new StringBuilder()));
        assertEquals(input.choose(new StringBuilder()), define(definition).getMethod("call", ReceiverBound.class).invoke(null, input));
    }

    @Test
    public void instantiatingGenericConstructorRetainsMethodBounds() throws Exception {
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addParameter("value", TypeDef.variable("T"))
            .build((self, p) -> self.superRef().invokeSuperConstructor());
        var target = ClassDef.builder("test.additional.GenericConstructor").addModifiers(Modifier.PUBLIC).addMethod(constructor).build();
        assertEquals(target.getName(), run(target.asTypeDef().instantiate(ExpressionDef.constant(7)), target).getClass().getName());
    }

    @Test
    public void newInstanceArgumentsUseConstructedClassScope() throws Exception {
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.variable("T"))
            .build((self, p) -> self.superRef().invokeSuperConstructor());
        var target = ClassDef.builder("test.additional.Constructed").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(constructor).build();
        var caller = ClassDef.builder("test.additional.Constructing").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.OBJECT)
                .build((self, p) -> target.asTypeDef().instantiate(List.of(TypeDef.variable("T")), ExpressionDef.constant(7)).returning())).build();
        assertEquals(target.getName(), load(target, caller).loadClass(caller.getName()).getMethod("call").invoke(null).getClass().getName());
    }

    @Test
    public void superConstructorArgumentsUseSuperclassScope() throws Exception {
        var constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.variable("T"))
            .build((self, p) -> self.superRef().invokeSuperConstructor());
        var parent = ClassDef.builder("test.additional.ConstructorParent").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class))).addMethod(constructor).build();
        var child = ClassDef.builder("test.additional.ConstructorChild").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence.class)))
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC)
                .build((self, p) -> self.superRef().invokeSuperConstructor(constructor, ExpressionDef.constant(7)))).build();
        assertEquals(child.getName(), load(parent, child).loadClass(child.getName()).getConstructor().newInstance().getClass().getName());
    }

    @Test
    public void generatedSecondaryReceiverBoundIncludesInheritedMethods() throws Exception {
        var inherited = InterfaceDef.builder("test.additional.InheritedContract").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.Primitive.INT).build()).build();
        var secondary = InterfaceDef.builder("test.additional.SecondaryContract").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(inherited.asTypeDef()).build();
        var implementation = ClassDef.builder("test.additional.IntersectionValue").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.of(Runnable.class)).addSuperinterface(secondary.asTypeDef())
            .addMethod(MethodDef.builder("value").addModifiers(Modifier.PUBLIC).returns(TypeDef.Primitive.INT)
                .build((self, p) -> ExpressionDef.constant(7).returning()))
            .addMethod(MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID)
                .build((self, p) -> new StatementDef.Return(null))).build();
        var caller = ClassDef.builder("test.additional.IntersectionCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addTypeVariable(TypeDef.variable("T", TypeDef.of(Runnable.class), secondary.asTypeDef()))
                .addParameter("input", TypeDef.variable("T")).returns(TypeDef.Primitive.INT)
                .build((self, p) -> p.getFirst().invoke("value", TypeDef.Primitive.INT).returning())).build();
        var loader = load(inherited, secondary, implementation, caller);
        var instance = loader.loadClass(implementation.getName()).getConstructor().newInstance();
        assertEquals(7, loader.loadClass(caller.getName()).getMethod("call", Runnable.class).invoke(null, instance));
    }

    /**
     * Overload lookup must interpret name-only variables in the enclosing declaration.
     *
     * @param scope       Whether the argument's variable is declared by the class or by the method
     * @param declaration How the argument's type variable is written: by name or inline with its bound
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "an argument of a {0} variable written {1} keeps its bound for overload selection")
    @CsvSource({"class, named", "class, inline", "method, named", "method, inline"})
    public void overloadArgumentsRetainTheirLexicalBounds(String scope, String declaration) throws Exception {
        boolean methodScoped = scope.equals("method");
        boolean inline = declaration.equals("inline");
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var builder = ClassDef.builder("test.completeness.LexicalArgument").addModifiers(Modifier.PUBLIC);
        // A variable of the class is passed with its bounds: the model resolves the call where it is built,
        // without the class. One of the method is rebound to the bounds the method declares it with
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", inline || !methodScoped ? variable : TypeDef.variable("T")).returns(TypeDef.STRING);
        if (methodScoped) {
            method.addTypeVariable(variable);
        } else {
            builder.addTypeVariable(variable);
        }
        builder.addMethod(method.build((self, params) -> ClassTypeDef.of(Calls.class).invokeStatic("numeric", TypeDef.STRING, params).returning()));
        var generated = define(builder.build());
        assertEquals("number", generated.getMethod("call", Number.class).invoke(generated.getConstructor().newInstance(), 1));
    }

    /**
     * Long bound chains are also exercised when passing an external generated method's arguments.
     *
     * @param scope  Whether the chain is declared by the callee's class or by its method
     * @param length The number of variables in the chain
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "a {0} chain of {1} bounds stays in the callee's scope")
    @CsvSource({"class, 2", "class, 5", "class, 10", "class, 20", "method, 2", "method, 5", "method, 10", "method, 20"})
    public void externalBoundChainsRemainInTheCalleeScope(String scope, int length) throws Exception {
        boolean methodScoped = scope.equals("method");
        var targetBuilder = ClassDef.builder("test.completeness.LongTarget").addModifiers(Modifier.PUBLIC);
        var identity = MethodDef.builder("identity").addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T0")).returns(TypeDef.variable("T0"));
        for (int i = 0; i < length; i++) {
            var variable = TypeDef.variable("T" + i, i + 1 == length ? TypeDef.of(Number.class) : TypeDef.variable("T" + (i + 1)));
            if (methodScoped) {
                identity.addTypeVariable(variable);
            } else {
                targetBuilder.addTypeVariable(variable);
            }
        }
        var identityMethod = identity.build((self, p) -> p.getFirst().returning());
        var targetDefinition = targetBuilder.addMethod(identityMethod).build();
        var caller = ClassDef.builder("test.completeness.LongCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("receiver", targetDefinition.asTypeDef()).returns(TypeDef.OBJECT)
                .build((self, p) -> p.getFirst().invoke(identityMethod, ExpressionDef.constant(7)).returning())).build();
        var loader = load(targetDefinition, caller);
        var targetClass = loader.loadClass(targetDefinition.getName());
        assertEquals(7, loader.loadClass(caller.getName()).getMethod("call", targetClass)
            .invoke(null, targetClass.getConstructor().newInstance()));
    }

    @Test
    public void rawReceiverErasesGenericParameterApplicability() throws Exception {
        RawReceiver receiver = new RawReceiver();
        List<String> value = List.of("a");
        assertEquals("generic", receiver.choose(value));
        assertCall(receiver.choose(value), List.of(TypeDef.of(RawReceiver.class), TypeDef.parameterized(List.class, TypeDef.STRING)),
            List.of(receiver, value), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void annotatedReceiverRetainsItsOverloadInformation() throws Exception {
        var receiverType = ClassTypeDef.of(InstanceCalls.class).annotated(AnnotationDef.builder(SignatureFixtures.Marker.class).build());
        assertCall("sequence", List.of(receiverType), List.of(new InstanceCalls()),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant("a")));
    }

    @Test
    public void rawGenericReceiverStillSelectsNumericMethod() throws Exception {
        var receiver = new RawMethodReceiver();
        assertEquals("number", receiver.choose(7));
        assertCall(receiver.choose(7), List.of(TypeDef.of(RawMethodReceiver.class)), List.of(receiver),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant(7)));
    }

    @Test
    public void rawBoundedReceiverKeepsTheErasedClassBound() throws Exception {
        RawBoundedReceiver receiver = new RawBoundedReceiver();
        assertEquals("object", receiver.choose("a"));
        assertCall(receiver.choose("a"), List.of(TypeDef.of(RawBoundedReceiver.class)), List.of(receiver),
            p -> p.getFirst().invoke("choose", TypeDef.STRING, ExpressionDef.constant("a")));
    }

    @Test
    public void annotatedReceiverSupportsAnExplicitMethodDescriptor() throws Exception {
        var receiverType = ClassTypeDef.of(InstanceCalls.class).annotated(AnnotationDef.builder(SignatureFixtures.Marker.class).build());
        var method = MethodDef.of(InstanceCalls.class.getMethod("choose", CharSequence.class));
        assertCall("sequence", List.of(receiverType), List.of(new InstanceCalls()),
            p -> p.getFirst().invoke(method, ExpressionDef.constant("a")));
    }

    @Test
    public void annotatedInterfaceReceiverUsesInvokeinterface() throws Exception {
        var receiverType = ClassTypeDef.of(CharSequence.class).annotated(AnnotationDef.builder(SignatureFixtures.Marker.class).build());
        var method = MethodDef.of(CharSequence.class.getMethod("length"));
        assertCall(3, List.of(receiverType), List.of("abc"), p -> p.getFirst().invoke(method));
    }

    @Test
    public void rawSubclassErasesItsParameterizedSuperclass() throws Exception {
        RawChild receiver = new RawChild();
        List<Integer> values = List.of(1);
        assertEquals("list", receiver.choose(values));
        assertCall(receiver.choose(values), List.of(TypeDef.of(RawChild.class), TypeDef.parameterized(List.class, Integer.class)),
            List.of(receiver, values), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void parameterizedSubclassKeepsItsSuperclassArguments() throws Exception {
        RawChild<String> receiver = new RawChild<>();
        List<Integer> values = List.of(1);
        assertCall(receiver.choose(values), List.of(TypeDef.parameterized(RawChild.class, String.class), TypeDef.parameterized(List.class, Integer.class)),
            List.of(receiver, values), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    /**
     * A constructor argument typed by a type variable resolves the constructor with the bound the variable has
     * where it is declared.
     *
     * @param scope       Whether the argument's variable is declared by the class or by the method
     * @param declaration How the argument's type variable is written: by name or inline with its bound
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "a constructor argument of a {0} variable written {1} chooses javac's constructor")
    @CsvSource({"class, named", "class, inline", "method, named", "method, inline"})
    public void constructorsResolveLexicalTypeVariables(String scope, String declaration) throws Exception {
        boolean methodScope = scope.equals("method");
        boolean inline = declaration.equals("inline");
        var variable = TypeDef.variable("T", TypeDef.of(Number.class));
        var builder = ClassDef.builder("test.resolution.ConstructorCaller").addModifiers(Modifier.PUBLIC);
        // A variable of the class is passed with its bounds: the model resolves the call where it is built,
        // without the class. One of the method is rebound to the bounds the method declares it with
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("value", inline || !methodScope ? variable : TypeDef.variable("T")).returns(TypeDef.STRING);
        if (methodScope) {
            method.addTypeVariable(variable);
        } else {
            builder.addTypeVariable(variable);
        }
        builder.addMethod(method.build((self, p) -> ClassTypeDef.of(ConstructorChoice.class).instantiate(p)
            .invoke("selected", TypeDef.STRING).returning()));
        Class<?> generated = define(builder.build());
        assertEquals(new ConstructorChoice((Number) 1).selected(),
            generated.getMethod("call", Number.class).invoke(generated.getConstructor().newInstance(), 1));
    }

    /**
     * A call by name on a receiver typed by a type variable resolves the overloads of the variable's bound.
     *
     * @param scope       Whether the receiver's variable is declared by the class or by the method
     * @param declaration How the receiver's type variable is written: by name or inline with its bound
     * @throws Exception If the generated program cannot be loaded or invoked
     */
    @ParameterizedTest(name = "a receiver of a {0} variable written {1} chooses javac's overload")
    @CsvSource({"class, named", "class, inline", "method, named", "method, inline"})
    public void variableReceiversResolveOverloadsByName(String scope, String declaration) throws Exception {
        boolean methodScope = scope.equals("method");
        boolean inline = declaration.equals("inline");
        var variable = TypeDef.variable("T", TypeDef.of(ResolutionFixtures.Receiver.class));
        var builder = ClassDef.builder("test.resolution.VariableReceiver").addModifiers(Modifier.PUBLIC);
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC)
            .addParameter("receiver", inline ? variable : TypeDef.variable("T")).returns(TypeDef.STRING);
        if (methodScope) {
            method.addTypeVariable(variable);
        } else {
            builder.addTypeVariable(variable);
        }
        builder.addMethod(method.build((self, p) -> p.getFirst().invoke("choose", TypeDef.STRING,
            ExpressionDef.constant("hello")).returning()));
        Class<?> generated = define(builder.build());
        assertEquals(new ResolutionFixtures.Receiver().choose("hello"), generated.getMethod("call", ResolutionFixtures.Receiver.class)
            .invoke(generated.getConstructor().newInstance(), new ResolutionFixtures.Receiver()));
    }

    @Test
    public void genericMethodBoundsUseTheWholeParameterizedReceiver() throws Exception {
        MethodBounds<Integer> receiver = new MethodBounds<>();
        assertCall(receiver.choose(List.of(1.0)), List.of(TypeDef.parameterized(MethodBounds.class, Integer.class), list(TypeDef.of(Double.class))),
            List.of(receiver, List.of(1.0)), p -> p.getFirst().invoke("choose", TypeDef.STRING, p.get(1)));
    }

    @Test
    public void interfaceBoundCanInvokeObjectMethods() throws Exception {
        var variable = TypeDef.variable("T", TypeDef.of(Runnable.class));
        var definition = ClassDef.builder("test.resolution.InterfaceObject").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(variable)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.STRING)
                .build((self, p) -> p.getFirst().invoke("toString", TypeDef.STRING).returning())).build();
        Runnable value = () -> { };
        assertEquals(value.toString(), define(definition).getMethod("call", Runnable.class).invoke(null, value));
    }

    @Test
    public void methodsOfAPackagePrivateSuperclassAreInvokedOnThePublicReceiver() throws Exception {
        var value = new StringBuilder("ab");
        assertCall(new StringBuilder("ab").append('c').length(), List.of(TypeDef.of(StringBuilder.class)), List.of(value),
            p -> p.getFirst().invoke("append", TypeDef.of(StringBuilder.class), ExpressionDef.constant('c'))
                .invoke("length", TypeDef.Primitive.INT));
    }

    @Test
    public void staticMethodsOfInterfacesAreInvokedByName() throws Exception {
        assertCall(java.util.Comparator.<Integer>naturalOrder().compare(1, 2), List.of(), List.of(),
            p -> ClassTypeDef.of(java.util.Comparator.class).invokeStatic("naturalOrder", TypeDef.of(java.util.Comparator.class))
                .invoke("compare", TypeDef.Primitive.INT, ExpressionDef.constant(1), ExpressionDef.constant(2)));
    }

    @Test
    public void callByNameOnThisResolvesAnInheritedOverload() throws Exception {
        var definition = ClassDef.builder("test.hardening.ThisCaller").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(Describer.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> self.invoke("describe", TypeDef.STRING, p.getFirst()).returning()))
            .build();
        Class<?> generated = define(definition);
        assertEquals(new DescriberChild().call("x"),
            generated.getMethod("call", String.class).invoke(generated.getConstructor().newInstance(), "x"));
    }

    @Test
    public void callByNameOnSuperResolvesAnInheritedOverload() throws Exception {
        var definition = ClassDef.builder("test.hardening.SuperCaller").addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(Describer.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).addParameter("value", TypeDef.STRING)
                .returns(TypeDef.STRING).build((self, p) -> self.superRef().invoke("describe", TypeDef.STRING, p.getFirst()).returning()))
            .build();
        Class<?> generated = define(definition);
        assertEquals(new DescriberChild().callSuper("x"),
            generated.getMethod("call", String.class).invoke(generated.getConstructor().newInstance(), "x"));
    }

    @Test
    public void cloneOfAnArrayReceiverIsTypedAsTheArray() throws Exception {
        String[] values = {"a", "b"};
        assertCall(Arrays.asList(values.clone()), List.of(TypeDef.STRING.array()), List.of((Object) values),
            p -> ClassTypeDef.of(Arrays.class).invokeStatic("asList", TypeDef.of(List.class),
                p.getFirst().invoke("clone", TypeDef.STRING.array())));
    }

    @Test
    public void equalsOfAnArrayReceiverTakesAnObject() throws Exception {
        String[] values = {"a"};
        // javac: invokevirtual [Ljava/lang/String;.equals(Ljava/lang/Object;)Z - an array equals itself
        assertCall(Boolean.TRUE, List.of(TypeDef.STRING.array()), List.of((Object) values),
            p -> p.getFirst().invoke("equals", TypeDef.Primitive.BOOLEAN, p.getFirst()));
    }

    @Test
    public void staticMethodCalledOnAnInstanceIsInvokedStatically() throws Exception {
        assertCall(Receivers.staticOnInstance("abc"), List.of(TypeDef.STRING), List.of("abc"),
            p -> p.getFirst().invoke("valueOf", TypeDef.STRING, ExpressionDef.constant(1)));
    }

    @Test
    public void objectMethodOfAnInterfaceReceiverTakesAnObject() throws Exception {
        Runnable value = () -> { };
        // javac: invokevirtual Object.equals(Object) - a value equals itself
        assertCall(Boolean.TRUE, List.of(TypeDef.of(Runnable.class)), List.of(value),
            p -> p.getFirst().invoke("equals", TypeDef.Primitive.BOOLEAN, p.getFirst()));
    }

    @Test
    public void objectMethodOfAGeneratedInterfaceReceiverTakesAnObject() throws Exception {
        var definition = ClassDef.builder("test.hardening.ObjectMethodOfInterface").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("value", TypeDef.of(Marker.class)).addParameter("other", TypeDef.STRING).returns(TypeDef.Primitive.BOOLEAN)
                .build((self, p) -> p.getFirst().invoke("equals", TypeDef.Primitive.BOOLEAN, p.get(1)).returning())).build();
        Marker marker = new Marker() { };
        assertEquals(marker.equals("x"), define(definition).getMethod("call", Marker.class, String.class).invoke(null, marker, "x"));
    }

    @Test
    public void superCallByNameToADefaultMethodOfOneOfTwoInterfaces() throws Exception {
        var definition = ClassDef.builder("test.hardening.BothDefaults").addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of(Left.class))
            .addSuperinterface(ClassTypeDef.of(Right.class))
            .addMethod(MethodDef.builder("name").addModifiers(Modifier.PUBLIC).returns(TypeDef.STRING)
                .build((self, p) -> self.superRef(ClassTypeDef.of(Left.class)).invoke("name", TypeDef.STRING).returning()))
            .build();
        Left generated = (Left) define(definition).getConstructor().newInstance();
        assertEquals(new BothDefaults().name(), generated.name());
    }

    /** Method bound depends on the receiver.
     * @param <T> The receiver argument
     * @since 2.3
     */
    public static class ReceiverBound<T extends CharSequence> {
        public <U extends T> String choose(U input) {
            return "sequence";
        }

        public String choose(Object input) {
            return "object";
        }
    }

    /** A method variable shadows a receiver variable.
     * @param <T> The receiver variable
     * @since 2.3
     */
    public static class ShadowedReceiver<T> {
        public <T extends Number> String choose(T value) {
            return "number";
        }

        public String choose(Object value) {
            return "object";
        }
    }

    /** Inherited generic overloads.
     * @param <T> The receiver variable
     * @since 2.3
     */
    public static class Receiver<T> {
        public String choose(T value) {
            return "generic";
        }

        public String choose(CharSequence value) {
            return "sequence";
        }
    }

    /** A specialized receiver.
     * @since 2.3
     */
    public static class StringReceiver extends Receiver<String> {
    }

    /**
     * Raw receivers erase instance member types.
     * @param <T> Class variable
     * @since 2.3
     */
    public static class RawReceiver<T> {
        public String choose(List<Integer> value) {
            return "generic";
        }

        public String choose(Object value) {
            return "fallback";
        }
    }

    /**
     * Own method variables of a raw class.
     * @param <T> Class variable
     * @since 2.3
     */
    public static class RawMethodReceiver<T> {
        public <U extends Number> String choose(U value) {
            return "number";
        }

        public String choose(Object value) {
            return "object";
        }
    }

    /**
     * Instance overload oracle.
     * @since 2.3
     */
    public static class InstanceCalls {
        public String choose(CharSequence value) {
            return "sequence";
        }

        public String choose(Object value) {
            return "object";
        }
    }

    /** Raw inheritance oracle.
     * @param <T> Fixture type
     * @since 2.3
     */
    public static class ListParent<T> {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param value The fixture input
         *
         * @since 2.3
         */
        public String choose(List<T> value) {
            return "list";
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

    /** A raw use erases this type's entire superclass.
     * @param <T> Fixture type
     * @since 2.3
     */
    public static class RawChild<T> extends ListParent<String> { }

    /** Constructor overload oracle.
     * @since 2.3
     */
    public static class ConstructorChoice {
        private final String chosen;

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @param value The fixture input
         *
         * @since 2.3
         */
        public ConstructorChoice(Number value) {
            chosen = "number";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @param value The fixture input
         *
         * @since 2.3
         */
        public ConstructorChoice(Object value) {
            chosen = "object";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         *
         * @since 2.3
         */
        public String selected() {
            return chosen;
        }
    }

    /** A method bound refers to the receiver's type argument.
     * @param <X> ResolutionFixtures.Receiver numeric type
     * @since 2.3
     */
    public static class MethodBounds<X extends Number> {

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param <T> The fixture input
         * @param values The fixture input
         *
         * @since 2.3
         */
        public <T extends X> String choose(List<T> values) {
            return "generic";
        }

        /**
         * Supplies the javac behavior or signature used by the regression test.
         *
         * @return The javac fixture result
         * @param values The fixture input
         *
         * @since 2.3
         */
        public String choose(Object values) {
            return "object";
        }
    }

    /** Overloads inherited by a generated class.
     * @since 2.3
     */
    public static class Describer {
        public String describe(CharSequence value) {
            return "sequence";
        }

        public String describe(Object value) {
            return "object";
        }
    }

    /** javac's calls on this and super.
     * @since 2.3
     */
    public static class DescriberChild extends Describer {
        public String call(String value) {
            return this.describe(value);
        }

        public String callSuper(String value) {
            return super.describe(value);
        }
    }

    /** A generated interface stand-in.
     * @since 2.3
     */
    public interface Marker { }

    /** A default method.
     * @since 2.3
     */
    public interface Left {
        default String name() {
            return "left";
        }
    }

    /** A default method of the same name.
     * @since 2.3
     */
    public interface Right {
        default String name() {
            return "right";
        }
    }

    /** javac's class of both.
     * @since 2.3
     */
    public static class BothDefaults implements Left, Right {
        @Override
        public String name() {
            return Left.super.name();
        }
    }
}
