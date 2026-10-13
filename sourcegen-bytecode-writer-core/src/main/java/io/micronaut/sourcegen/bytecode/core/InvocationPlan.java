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

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InvocationResolver;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Objects;

/**
 * An invocation as both bytecode writers emit it, planned once: the instruction and what it names - the owner, the
 * descriptor with the return type the method declares, whether the owner is an interface - what happens to the
 * receiver, the parameters the arguments are converted to, and the conversion of the result to the type the call
 * requests. A writer only writes the receiver and the arguments, and emits the plan.
 *
 * @param kind             The instruction
 * @param ownerDescriptor  The descriptor of the type the instruction names
 * @param name             The name of the method
 * @param descriptor       The descriptor of the method, returning what its declaration does
 * @param ownerInterface   Whether the owner is an interface, as the constant pool entry of the method says
 * @param receiver         What happens to the receiver once it is written
 * @param receiverCast     The descriptor of the type the receiver is cast to, for {@link Receiver#CAST}
 * @param method           The invoked method, in the scope of the class declaring it
 * @param values           The arguments, converted to the parameters of the method
 * @param result           The conversion of the result to the type the call requests, if any
 * @since 2.3
 */
@Internal
public record InvocationPlan(Kind kind,
                             String ownerDescriptor,
                             String name,
                             String descriptor,
                             boolean ownerInterface,
                             Receiver receiver,
                             @Nullable String receiverCast,
                             MethodDef method,
                             List<? extends ExpressionDef> values,
                             @Nullable ResultConversion result) {

    /**
     * The call of a method on a value.
     *
     * <ul>
     *     <li>A method the receiver does not declare - on {@code this} or {@code super}, of a variable named alone - or one
     *     the class being written does not access is resolved where the call is written.</li>
     *     <li>A receiver of a type variable is the bound declaring the method, cast to where it is not the erasure.</li>
     *     <li>The method is erased in the scope of the class declaring it, whose variables the caller's can shadow.</li>
     *     <li>A static method called through a value is invoked statically, the value evaluated and discarded, as javac
     *     does.</li>
     *     <li>A call on {@code super} is dispatched non-virtually against the supertype, which names an interface method
     *     for {@code Interface.super.name()}; a call on an interface is {@code invokeinterface}, even of a default
     *     method.</li>
     *     <li>A call requesting another type than its method declares invokes the declaration, and converts what it
     *     returns, as javac does: a primitive requested of a generic return unboxes the wrapper the call returns.</li>
     * </ul>
     *
     * @param instance  The receiver
     * @param method    The method
     * @param values    The arguments
     * @param current   The definition being written, if any
     * @param enclosing The method being written, if any
     * @return The plan
     */
    public static InvocationPlan ofInstance(ExpressionDef instance,
                                            MethodDef method,
                                            List<? extends ExpressionDef> values,
                                            @Nullable ObjectDef current,
                                            @Nullable MethodDef enclosing) {
        return ofInstance(instance, method, values, current, enclosing, EnclosingScope.NONE);
    }

    /**
     * The call of a method on a value, as {@link #ofInstance(ExpressionDef, MethodDef, List, ObjectDef, MethodDef)} plans
     * it, in the enclosing scope of the class being written.
     *
     * @param instance       The receiver
     * @param method         The method
     * @param values         The arguments
     * @param current        The definition being written, if any
     * @param enclosing      The method being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The plan
     * @since 2.3
     */
    public static InvocationPlan ofInstance(ExpressionDef instance,
                                            MethodDef method,
                                            List<? extends ExpressionDef> values,
                                            @Nullable ObjectDef current,
                                            @Nullable MethodDef enclosing,
                                            EnclosingScope enclosingScope) {
        ClassTypeDef superType = instance instanceof VariableDef.Super aSuper ? superTypeOf(aSuper, current) : null;
        TypeDef instanceType = ObjectDef.getContextualType(current, instance.type());
        InvocationResolver.Call call = InvocationResolver.resolve(superType != null ? superType : instanceType, method, values,
            TypeUtils.variableScope(current, enclosing), current, instance instanceof VariableDef.This || superType != null);
        MethodDef invoked = call == null ? method : call.method();
        List<? extends ExpressionDef> arguments = call == null ? values : call.values();
        TypeUtils.Receiver receiver = TypeUtils.receiverOf(instanceType, invoked, current, enclosing, enclosingScope);
        TypeDef receiverType = receiver.type();
        TypeDef declaringType = superType != null ? superType : receiverType;
        ObjectDef scope = TypeUtils.declaringScope(declaringType, current, invoked, enclosingScope);
        MethodDef scoped = TypeUtils.inDeclaringScope(invoked, scope, current);
        boolean isStatic = scoped.getModifiers().contains(Modifier.STATIC) && !scoped.isConstructor() && superType == null;
        String descriptor = TypeUtils.getMethodDescriptor(scope, scoped, enclosingScope);
        TypeDef declared = DeclaredReturns.of(declaringType, scoped, descriptor, invoked.getReturnType(), current, enclosingScope);
        if (declared != null) {
            descriptor = withReturn(descriptor, declared, current, enclosingScope);
        }
        Kind kind;
        TypeDef owner = receiverType;
        boolean ownerInterface = isInterface(receiverType, current);
        if (isStatic) {
            kind = Kind.STATIC;
        } else if (!(dispatched(receiverType, current) instanceof ClassTypeDef) && !(dispatched(receiverType, current) instanceof TypeDef.Array)) {
            throw new IllegalStateException("Unsupported instance type: " + receiverType);
        } else if (superType != null) {
            kind = Kind.SPECIAL;
            owner = superType;
            ownerInterface = superType.isInterface();
        } else if (scoped.isConstructor()) {
            kind = Kind.SPECIAL;
            ownerInterface = false;
        } else if (ownerInterface) {
            kind = Kind.INTERFACE;
        } else {
            kind = Kind.VIRTUAL;
        }
        Receiver receiverAction;
        if (isStatic) {
            receiverAction = Receiver.DISCARD;
        } else {
            receiverAction = receiver.cast() ? Receiver.CAST : Receiver.KEEP;
        }
        String cast = receiverAction == Receiver.CAST ? TypeUtils.getDescriptor(receiverType, current, enclosing, enclosingScope) : null;
        ResultConversion result = declared == null ? null : new ResultConversion(Conversions.convertedFrom(declared,
            invoked.getReturnType(), declaringType, scoped, descriptor, arguments, current), invoked.getReturnType());
        return new InvocationPlan(kind, TypeUtils.getDescriptor(owner, current, enclosing, enclosingScope), scoped.getName(), descriptor,
            ownerInterface, receiverAction, cast, scoped, arguments, result);
    }

    /**
     * The call of a static method of a class: resolved, erased and converted as a {@link #ofInstance call on a value}
     * is, with no receiver.
     *
     * @param owner     The class declaring the method
     * @param method    The method
     * @param values    The arguments
     * @param current   The definition being written, if any
     * @param enclosing The method being written, if any
     * @return The plan
     */
    public static InvocationPlan ofStatic(ClassTypeDef owner,
                                          MethodDef method,
                                          List<? extends ExpressionDef> values,
                                          @Nullable ObjectDef current,
                                          @Nullable MethodDef enclosing) {
        return ofStatic(owner, method, values, current, enclosing, EnclosingScope.NONE);
    }

    /**
     * The call of a static method of a class, as {@link #ofStatic(ClassTypeDef, MethodDef, List, ObjectDef, MethodDef)}
     * plans it, in the enclosing scope of the class being written.
     *
     * @param owner          The class declaring the method
     * @param method         The method
     * @param values         The arguments
     * @param current        The definition being written, if any
     * @param enclosing      The method being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The plan
     * @since 2.3
     */
    public static InvocationPlan ofStatic(ClassTypeDef owner,
                                          MethodDef method,
                                          List<? extends ExpressionDef> values,
                                          @Nullable ObjectDef current,
                                          @Nullable MethodDef enclosing,
                                          EnclosingScope enclosingScope) {
        // A method the class does not declare, of a variable named alone, or one this class does not access is resolved
        // where the call is written
        InvocationResolver.Call call = InvocationResolver.resolve(owner, method, values, TypeUtils.variableScope(current, enclosing),
            current, false);
        MethodDef invoked = call == null ? method : call.method();
        List<? extends ExpressionDef> arguments = call == null ? values : call.values();
        ObjectDef scope = TypeUtils.declaringScope(owner, current, invoked, enclosingScope);
        MethodDef scoped = TypeUtils.inDeclaringScope(invoked, scope, current);
        String descriptor = TypeUtils.getMethodDescriptor(scope, scoped, enclosingScope);
        TypeDef declared = DeclaredReturns.of(owner, scoped, descriptor, invoked.getReturnType(), current, enclosingScope);
        if (declared != null) {
            descriptor = withReturn(descriptor, declared, current, enclosingScope);
        }
        ResultConversion result = declared == null ? null : new ResultConversion(Conversions.convertedFrom(declared,
            invoked.getReturnType(), owner, scoped, descriptor, arguments, current), invoked.getReturnType());
        return new InvocationPlan(Kind.STATIC, TypeUtils.getDescriptor(owner, current, enclosing, enclosingScope), scoped.getName(), descriptor,
            isInterface(owner, current), Receiver.NONE, null, scoped, arguments, result);
    }

    /**
     * The constructor {@code new} invokes, erased in the scope of the class declaring it; one the class does not
     * declare, of a variable named alone, or one {@code new} does not access from the class being written is resolved
     * where the call is written. The writer writes the {@code new} and {@code dup} the constructor is invoked on.
     *
     * @param newInstance The instantiation
     * @param current     The definition being written, if any
     * @param enclosing   The method being written, if any
     * @return The plan
     */
    public static InvocationPlan ofNewInstance(ExpressionDef.NewInstance newInstance,
                                               @Nullable ObjectDef current,
                                               @Nullable MethodDef enclosing) {
        return ofNewInstance(newInstance, current, enclosing, EnclosingScope.NONE);
    }

    /**
     * The constructor {@code new} invokes, as {@link #ofNewInstance(ExpressionDef.NewInstance, ObjectDef, MethodDef)}
     * plans it, in the enclosing scope of the class being written.
     *
     * @param newInstance    The instantiation
     * @param current        The definition being written, if any
     * @param enclosing      The method being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The plan
     * @since 2.3
     */
    public static InvocationPlan ofNewInstance(ExpressionDef.NewInstance newInstance,
                                               @Nullable ObjectDef current,
                                               @Nullable MethodDef enclosing,
                                               EnclosingScope enclosingScope) {
        MethodDef constructor = MethodDef.constructor().addParameters(newInstance.parameterTypes()).build();
        return ofConstructor(newInstance.type(), constructor, newInstance.values(), false, current, enclosing, enclosingScope);
    }

    /**
     * The superclass constructor an explicit {@code super(...)} invokes, erased in the scope of the superclass; one the
     * class being written does not access by {@code super(...)} - a package-private one of another package - is
     * resolved where the call is written. The writer writes the receiver the constructor is invoked on.
     *
     * @param invocation The invocation
     * @param current    The definition being written, if any
     * @param enclosing  The method being written, if any
     * @param enclosingScope The enclosing scope of the class being written
     * @return The plan
     */
    public static InvocationPlan ofSuperConstructor(StatementDef.InvokeSuperConstructor invocation,
                                                    @Nullable ObjectDef current,
                                                    @Nullable MethodDef enclosing,
                                                    EnclosingScope enclosingScope) {
        return ofConstructor(superTypeOf(invocation.superInstance(), current), invocation.method(), invocation.values(), true,
            current, enclosing, enclosingScope);
    }

    private static InvocationPlan ofConstructor(ClassTypeDef owner,
                                                MethodDef constructor,
                                                List<? extends ExpressionDef> values,
                                                boolean self,
                                                @Nullable ObjectDef current,
                                                @Nullable MethodDef enclosing,
                                                EnclosingScope enclosingScope) {
        ObjectDef scope = TypeUtils.declaringScope(owner, current);
        InvocationResolver.Call call = InvocationResolver.resolve(owner, constructor, values, TypeUtils.variableScope(current, enclosing),
            current, self);
        MethodDef invoked = call == null ? constructor : call.method();
        List<? extends ExpressionDef> arguments = call == null ? values : call.values();
        MethodDef scoped = TypeUtils.inDeclaringScope(invoked, scope, current);
        return new InvocationPlan(Kind.SPECIAL, TypeUtils.getDescriptor(owner, current, enclosing, enclosingScope), MethodDef.CONSTRUCTOR,
            TypeUtils.getMethodDescriptor(scope, scoped, enclosingScope), false, Receiver.NONE, null, scoped, arguments, null);
    }

    /**
     * The supertype a {@code super} reference resolves against: the one it names, or the definition's own supertype
     * where it is the placeholder {@link TypeDef#SUPER} - {@code Enum} of an enum, {@code Record} of a record, the
     * superclass of a class and {@code Object} otherwise.
     *
     * @param aSuper  The reference
     * @param current The definition being written, if any
     * @return The supertype
     */
    public static ClassTypeDef superTypeOf(VariableDef.Super aSuper, @Nullable ObjectDef current) {
        if (!aSuper.type().equals(TypeDef.SUPER)) {
            return aSuper.type();
        }
        if (current instanceof EnumDef) {
            return ClassTypeDef.of(Enum.class);
        }
        if (current instanceof RecordDef) {
            return ClassTypeDef.of(Record.class);
        }
        if (current instanceof ClassDef classDef) {
            return Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT);
        }
        return TypeDef.OBJECT;
    }

    /**
     * @return The types of the parameters the arguments are converted to
     */
    public List<TypeDef> parameterTypes() {
        return method.getParameters().stream().map(ParameterDef::getType).toList();
    }

    /**
     * @return The internal name of the owner, which is the descriptor of an array type
     */
    public String ownerInternalName() {
        return ownerDescriptor.charAt(0) == '[' ? ownerDescriptor : ownerDescriptor.substring(1, ownerDescriptor.length() - 1);
    }

    private static String withReturn(String descriptor, TypeDef returnType, @Nullable ObjectDef current, EnclosingScope enclosingScope) {
        return descriptor.substring(0, descriptor.indexOf(')') + 1) + TypeUtils.getDescriptor(returnType, current, enclosingScope);
    }

    /**
     * The type a call on a receiver is dispatched on: an annotated type is the type it annotates, and a variable its
     * leftmost bound.
     */
    private static TypeDef dispatched(TypeDef type, @Nullable ObjectDef current) {
        TypeDef result = TypeHierarchy.unwrap(ObjectDef.getContextualType(current, type));
        while (result instanceof TypeDef.TypeVariable variable) {
            result = variable.bounds().isEmpty() ? TypeDef.OBJECT : TypeHierarchy.unwrap(variable.bounds().getFirst());
        }
        return result;
    }

    /**
     * Whether a type is an interface, {@code this} and {@code super} once resolved against the definition.
     */
    private static boolean isInterface(TypeDef type, @Nullable ObjectDef current) {
        return dispatched(type, current) instanceof ClassTypeDef classTypeDef && classTypeDef.isInterface();
    }

    /**
     * An invocation instruction.
     */
    public enum Kind {
        /** {@code invokestatic}. */
        STATIC(0xb8),
        /** {@code invokevirtual}. */
        VIRTUAL(0xb6),
        /** {@code invokeinterface}. */
        INTERFACE(0xb9),
        /** {@code invokespecial}: a constructor, or a method of the supertype named by {@code super}. */
        SPECIAL(0xb7);

        private final int opcode;

        Kind(int opcode) {
            this.opcode = opcode;
        }

        /**
         * @return The JVMS opcode of the instruction
         */
        public int opcode() {
            return opcode;
        }
    }

    /**
     * What happens to the receiver of an invocation once it is written.
     */
    public enum Receiver {
        /** The invocation has no receiver the writer writes: a static call, or a constructor. */
        NONE,
        /** The receiver is the one the method is invoked on. */
        KEEP,
        /** The receiver is cast to the bound declaring the method, {@link #receiverCast()}. */
        CAST,
        /** The receiver of a static method is evaluated and discarded. */
        DISCARD
    }

    /**
     * The conversion of what a declaration returns to the type the call requests.
     *
     * @param from The type the value converts from
     * @param to   The type the call requests
     */
    public record ResultConversion(TypeDef from, TypeDef to) {
    }
}
