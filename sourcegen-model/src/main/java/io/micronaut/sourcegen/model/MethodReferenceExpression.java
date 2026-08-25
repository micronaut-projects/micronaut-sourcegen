/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Experimental;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A method reference used as an expression implementing a functional interface, e.g.
 * {@code MyClass::staticMethod}.
 *
 * <p>It implements the same functional interface a {@link ExpressionDef.Lambda} does, but instead of a
 * generated body it points directly at an existing method. Each form is its own type:
 * {@link StaticMethodReferenceExpression}, {@link InstanceMethodReferenceExpression} and {@link ConstructorMethodReferenceExpression}.
 *
 * <p>Only a reference that needs no further input is an expression - an instance method becomes one once
 * bound to a receiver.
 *
 * <p>Create one from the {@link LambdaDef} of the interface being implemented, or with
 * {@link ClassTypeDef#staticMethodReference}, {@link ClassTypeDef#constructorReference} and
 * {@link ClassTypeDef#methodReference}.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Experimental
public sealed interface MethodReferenceExpression extends ExpressionDef
    permits StaticMethodReferenceExpression, InstanceMethodReferenceExpression, ConstructorMethodReferenceExpression {

    /**
     * Implement the interface with a reference to a static method, e.g. {@code MyClass::staticMethod}.
     *
     * @param functionalInterface The interface the reference implements
     * @param method              The referenced static method
     * @return the method reference expression
     * @since 2.2
     */
    static MethodReferenceExpression of(ClassTypeDef functionalInterface, Method method) {
        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("Method " + method.getDeclaringClass().getName() + "#"
                + method.getName() + " is not static; bind an instance method to a receiver instead");
        }
        return functionalInterface.staticMethodReference(
            ClassTypeDef.of(method.getDeclaringClass()), MethodDef.of(method));
    }

    /**
     * Implement the interface with a reference to a constructor, e.g. {@code MyClass::new}.
     *
     * @param functionalInterface The interface the reference implements
     * @param constructor         The referenced constructor
     * @return the method reference expression
     * @since 2.2
     */
    static MethodReferenceExpression of(ClassTypeDef functionalInterface, Constructor<?> constructor) {
        return functionalInterface.constructorReference(
            ClassTypeDef.of(constructor.getDeclaringClass()), MethodDef.builder(constructor).build());
    }

    /**
     * Returns the functional interface being implemented.
     *
     * @return The type of the functional interface, e.g. {@code Function<String, String>}
     */
    @Override
    ClassTypeDef type();

    /**
     * Whether the referenced method is called statically.
     *
     * <p>The implementations are not visible outside the model, so the forms are told apart with this,
     * {@link #isConstructor()} and {@link #instance()} rather than by type.
     *
     * @return True if this is a reference to a static method
     */
    default boolean isStatic() {
        return false;
    }

    /**
     * Whether the referenced method is a constructor.
     *
     * @return True if this is a reference to a constructor
     */
    default boolean isConstructor() {
        return method().isConstructor();
    }

    /**
     * The receiver a reference bound to an instance captures.
     *
     * @return The receiver, or {@code null} when the reference is not bound to one
     */
    @Nullable
    default ExpressionDef instance() {
        return null;
    }

    /**
     * Returns the method the interface declares, erased as the interface declares it.
     *
     * @return The method declared by the interface, e.g. {@code Object apply(Object)} for a {@link Function}
     */
    MethodDef target();

    /**
     * Returns the interface method with the type variables of the interface resolved.
     *
     * @return The interface method with its type variables resolved, e.g. {@code String apply(String)}
     */
    MethodDef instantiated();

    /**
     * Returns the type the referenced method is declared on.
     *
     * @return The type declaring the referenced method
     */
    ClassTypeDef owner();

    /**
     * Returns the method this reference points at.
     *
     * @return The referenced method
     */
    MethodDef method();

    /**
     * Invoke the method reference.
     *
     * @param expressions The expressions
     * @return The method invocation
     */
    default ExpressionDef.InvokeInstanceMethod invoke(List<? extends ExpressionDef> expressions) {
        return invoke(target(), expressions);
    }

    /**
     * Invoke the method reference.
     *
     * @param expressions The expressions
     * @return The method invocation
     */
    default ExpressionDef.InvokeInstanceMethod invoke(ExpressionDef... expressions) {
        return invoke(Arrays.asList(expressions));
    }

    @Override
    default Stream<? extends ExpressionDef> nestedExpressionsStream() {
        // Only a bound reference holds an expression; the rest name their target statically
        return Stream.empty();
    }
}
