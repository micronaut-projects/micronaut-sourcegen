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

import java.util.Objects;
import java.util.stream.Stream;
import java.util.function.Function;

/**
 * A reference to an instance method of a given receiver, e.g. {@code myInstance::instanceMethod}.
 *
 * @param type         The type of the functional interface, e.g. {@code Function<String, String>}
 * @param target       The method declared by the interface, e.g. {@code Object apply(Object)} for a {@link Function}
 * @param instantiated The interface method with its type variables resolved, e.g. {@code String apply(String)}
 * @param owner        The type declaring the referenced method
 * @param instance     The receiver the reference is bound to
 * @param method       The referenced method
 * @author Denis Stepanov
 * @since 2.2
 */
@Experimental
record InstanceMethodReferenceExpression(
    ClassTypeDef type,
    MethodDef target,
    MethodDef instantiated,
    ClassTypeDef owner,
    ExpressionDef instance,
    MethodDef method
) implements MethodReferenceExpression {

    InstanceMethodReferenceExpression {
        Objects.requireNonNull(instance, "The receiver of an instance method reference cannot be null");
        MethodReferences.validate(type, target, instantiated, owner, method, false);
    }

    @Override
    public Stream<? extends ExpressionDef> nestedExpressionsStream() {
        // The receiver is exposed so that an enclosing lambda captures the variables it reads
        return Stream.of(instance);
    }
}
