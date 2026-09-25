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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.Completion;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend-neutral plans both bytecode writers emit: completion, conversions, invocations, constructor bodies and
 * finished bridges.
 */
class BackendPlansTest {

    private static final ClassDef OWNER = ClassDef.builder("example.Owner").build();
    private static final MethodDef ENCLOSING = MethodDef.builder("run").returns(TypeDef.VOID).build();

    @Test
    void completesAsJls1422Says() {
        StatementDef returning = ExpressionDef.constant(1).returning();
        StatementDef throwing = ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow();
        StatementDef completing = new VariableDef.Local("value", TypeDef.Primitive.INT).defineAndAssign(ExpressionDef.constant(1));

        assertTrue(Completion.BYTECODE.canCompleteNormally(StatementDef.multi(List.of())));
        assertFalse(Completion.BYTECODE.canCompleteNormally(returning));
        assertFalse(Completion.BYTECODE.canCompleteNormally(throwing));
        assertTrue(Completion.BYTECODE.canCompleteNormally(completing));
        ExpressionDef condition = ExpressionDef.trueValue();
        assertFalse(Completion.BYTECODE.canCompleteNormally(new StatementDef.IfElse(condition, returning, throwing)));
        assertTrue(Completion.BYTECODE.canCompleteNormally(new StatementDef.IfElse(condition, returning, completing)));
        // A body that returns and a catch that completes: the try completes
        StatementDef.Try aTry = new StatementDef.Try(returning, List.of(), null)
            .doCatch(Exception.class, exception -> completing);
        assertTrue(Completion.BYTECODE.canCompleteNormally(aTry));
        // A finally that throws: the try never completes
        assertFalse(Completion.BYTECODE.canCompleteNormally(new StatementDef.Try(completing, List.of(), throwing)));
    }

    @Test
    void plansPrimitiveConversionsUnboxingAndBoxing() {
        assertEquals(List.of(new Conversions.PrimitiveConversion(TypeDef.Primitive.INT, TypeDef.Primitive.LONG)),
            plan(TypeDef.Primitive.INT, TypeDef.Primitive.LONG, Conversions.Checkcasts.ERASURE));
        assertEquals(List.of(), plan(TypeDef.Primitive.INT, TypeDef.Primitive.INT, Conversions.Checkcasts.MODEL));
        // A Character read as an int is unboxed as the Character it is, and widened
        assertEquals(List.of(new Conversions.Unboxing("java/lang/Character", TypeDef.Primitive.CHAR),
                new Conversions.PrimitiveConversion(TypeDef.Primitive.CHAR, TypeDef.Primitive.INT)),
            plan(ClassTypeDef.of(Character.class), TypeDef.Primitive.INT, Conversions.Checkcasts.MODEL));
        assertEquals(List.of(new Conversions.Unboxing("java/lang/Number", TypeDef.Primitive.INT)),
            plan(ClassTypeDef.of(Integer.class), TypeDef.Primitive.INT, Conversions.Checkcasts.ERASURE));
        Conversions.Box box = new Conversions.Box(TypeDef.Primitive.INT);
        assertEquals("java/lang/Integer", box.owner());
        assertEquals("(I)Ljava/lang/Integer;", box.methodDescriptor());
        assertEquals(List.of(box), plan(TypeDef.Primitive.INT, ClassTypeDef.of(Integer.class), Conversions.Checkcasts.MODEL));
    }

    @Test
    void keepsEachWritersRedundantCheckcasts() {
        // The ASM writer casts a boxed value to anything that is not its wrapper; the JDK writer never casts to Object
        assertEquals(List.of(new Conversions.Box(TypeDef.Primitive.INT), new Conversions.CheckCast("Ljava/lang/Object;")),
            plan(TypeDef.Primitive.INT, TypeDef.OBJECT, Conversions.Checkcasts.MODEL));
        assertEquals(List.of(new Conversions.Box(TypeDef.Primitive.INT)),
            plan(TypeDef.Primitive.INT, TypeDef.OBJECT, Conversions.Checkcasts.ERASURE));
        // A reference the model knows assignable is not cast by the ASM writer, one of the same erasure not by the JDK one
        assertEquals(List.of(), plan(TypeDef.STRING, ClassTypeDef.of(CharSequence.class), Conversions.Checkcasts.MODEL));
        assertEquals(List.of(new Conversions.CheckCast("Ljava/lang/CharSequence;")),
            plan(TypeDef.STRING, ClassTypeDef.of(CharSequence.class), Conversions.Checkcasts.ERASURE));
        assertEquals(List.of(), plan(TypeDef.parameterized(List.class, String.class), ClassTypeDef.of(List.class),
            Conversions.Checkcasts.ERASURE));
        for (Conversions.Checkcasts checkcasts : Conversions.Checkcasts.values()) {
            assertEquals(List.of(new Conversions.CheckCast("Ljava/lang/String;")), plan(TypeDef.OBJECT, TypeDef.STRING, checkcasts));
        }
    }

    @Test
    void writesOnlyTheLastCastOfAChain() {
        ExpressionDef value = ExpressionDef.constant("value");
        ExpressionDef.Cast chain = value.cast(TypeDef.OBJECT).cast(ClassTypeDef.of(CharSequence.class));
        assertSame(value, Conversions.castOperand(chain, OWNER, ENCLOSING));
    }

    @Test
    void plansInvocations() {
        InvocationPlan size = InvocationPlan.ofInstance(ExpressionDef.constant(null).cast(ClassTypeDef.of(List.class)),
            MethodDef.builder("size").returns(TypeDef.Primitive.INT).build(), List.of(), OWNER, ENCLOSING);
        assertEquals(InvocationPlan.Kind.INTERFACE, size.kind());
        assertTrue(size.ownerInterface());
        assertEquals("java/util/List", size.ownerInternalName());
        assertEquals("()I", size.descriptor());
        assertEquals(InvocationPlan.Receiver.KEEP, size.receiver());
        assertNull(size.result());

        InvocationPlan length = InvocationPlan.ofInstance(ExpressionDef.constant("value"),
            MethodDef.builder("length").returns(TypeDef.Primitive.INT).build(), List.of(), OWNER, ENCLOSING);
        assertEquals(InvocationPlan.Kind.VIRTUAL, length.kind());
        assertFalse(length.ownerInterface());

        InvocationPlan of = InvocationPlan.ofStatic(ClassTypeDef.of(List.class),
            MethodDef.builder("of").addModifiers(Modifier.STATIC).returns(ClassTypeDef.of(List.class)).build(), List.of(), OWNER, ENCLOSING);
        assertEquals(InvocationPlan.Kind.STATIC, of.kind());
        assertTrue(of.ownerInterface());
        assertEquals(InvocationPlan.Receiver.NONE, of.receiver());

        InvocationPlan newList = InvocationPlan.ofNewInstance(ClassTypeDef.of(ArrayList.class).instantiate(), OWNER, ENCLOSING);
        assertEquals(InvocationPlan.Kind.SPECIAL, newList.kind());
        assertEquals(MethodDef.CONSTRUCTOR, newList.name());
        assertEquals("()V", newList.descriptor());
        assertEquals("Ljava/util/ArrayList;", newList.ownerDescriptor());

        ClassDef child = ClassDef.builder("example.Child").superclass(ClassTypeDef.of(Thread.class)).build();
        InvocationPlan superCall = InvocationPlan.ofInstance(new VariableDef.This().superRef(),
            MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(TypeDef.VOID).build(), List.of(), child, ENCLOSING);
        assertEquals(InvocationPlan.Kind.SPECIAL, superCall.kind());
        assertEquals("java/lang/Thread", superCall.ownerInternalName());
        assertEquals(ClassTypeDef.of(Thread.class), InvocationPlan.superTypeOf(new VariableDef.This().superRef(), child));
        assertEquals(TypeDef.OBJECT, InvocationPlan.superTypeOf(new VariableDef.This().superRef(), OWNER));
    }

    @Test
    void normalizesConstructorBodies() {
        FieldDef field = FieldDef.builder("count", TypeDef.Primitive.INT).initializer(ExpressionDef.constant(1)).build();
        ClassDef classDef = ClassDef.builder("example.Counter").addField(field).build();
        StatementDef local = new VariableDef.Local("value", TypeDef.Primitive.INT).defineAndAssign(ExpressionDef.constant(2));
        StatementDef superCall = new VariableDef.This().superRef().invokeSuperConstructor();

        ConstructorBody implicit = ConstructorBody.of(classDef, List.of(local), ConstructorBody.InitializersAfterThis.SKIP);
        assertTrue(implicit.implicitSuper());
        assertEquals(List.of(field), implicit.initializedFields());
        assertEquals(List.of(local), implicit.statements());
        assertEquals(3, implicit.asStatements().size());

        ConstructorBody hoisted = ConstructorBody.of(classDef, List.of(local, superCall), ConstructorBody.InitializersAfterThis.SKIP);
        assertFalse(hoisted.implicitSuper());
        assertSame(superCall, hoisted.hoistedCall());
        assertEquals(List.of(local), hoisted.statements());

        // Without initializers the call stays where it is written
        ClassDef plain = ClassDef.builder("example.Plain").build();
        ConstructorBody unchanged = ConstructorBody.of(plain, List.of(local, superCall), ConstructorBody.InitializersAfterThis.SKIP);
        assertNull(unchanged.hoistedCall());
        assertEquals(List.of(local, superCall), unchanged.asStatements());

        // A delegation to this(...): the JDK writer runs the initializers once, the ASM writer again
        StatementDef delegation = new VariableDef.This().invoke(MethodDef.constructor().build(), List.of());
        assertEquals(List.of(), ConstructorBody.of(classDef, List.of(delegation), ConstructorBody.InitializersAfterThis.SKIP)
            .initializedFields());
        assertEquals(List.of(field), ConstructorBody.of(classDef, List.of(delegation), ConstructorBody.InitializersAfterThis.RERUN)
            .initializedFields());
    }

    @Test
    void finishesBridges() {
        MethodDef apply = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
        ClassDef function = ClassDef.builder("example.StringFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();

        List<BridgeResolver.Bridge> bridges = BridgeResolver.bridgesOf(function, apply);

        assertEquals(1, bridges.size());
        BridgeResolver.Bridge bridge = bridges.get(0);
        assertSame(apply, bridge.target());
        assertEquals(ModifierUtils.ACC_BRIDGE | ModifierUtils.ACC_SYNTHETIC, bridge.flags());
        // The access of the method only, erased as the inherited method, delegating to the method
        assertEquals(java.util.Set.of(Modifier.PUBLIC), bridge.method().getModifiers());
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", TypeUtils.getMethodDescriptor(function, bridge.method()));
        assertEquals("value", bridge.method().getParameters().get(0).getName());
        assertEquals(1, bridge.method().getStatements().size());
    }

    private static List<Conversions.Step> plan(TypeDef from, TypeDef to, Conversions.Checkcasts checkcasts) {
        return Conversions.plan(from, to, OWNER, ENCLOSING, checkcasts);
    }
}
