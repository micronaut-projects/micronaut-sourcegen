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
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The method references a metafactory cannot link to their method directly, which are written as a lambda calling it.
 *
 * @since 2.3
 */
@Internal
public final class ReferenceAdapters {

    private ReferenceAdapters() {
    }

    /**
     * A reference to a method of variable arity that the functional method passes the values of the array to - a
     * {@code Function<String, String>} of a {@code <T> String component(T...)} - as the lambda javac writes for it,
     * packing them into an array of the type they are passed with.
     *
     * @param reference The reference
     * @return The lambda, or {@code null} where the method takes the arguments as they are passed
     */
    public static ExpressionDef.@Nullable Lambda varargsAdapter(MethodReferenceExpression reference) {
        return varargsAdapter(reference, reference.instance());
    }

    /**
     * Whether the receiver of a reference is read where the adapter is invoked unless the writer reads it first: a
     * field, which a bound reference reads once, where it is created. The writer reads it, checks it is not null,
     * holds it in a local and passes the local to {@link #varargsAdapter(MethodReferenceExpression, ExpressionDef)}.
     *
     * @param reference The reference
     * @return Whether the receiver is read into a local before the adapter is written
     */
    public static boolean capturesReceiverValue(MethodReferenceExpression reference) {
        return reference.instance() instanceof VariableDef.Field && varargsAdapter(reference) != null;
    }

    /**
     * The adapter of {@link #varargsAdapter(MethodReferenceExpression)}, invoking the method on the receiver given: the
     * local a writer holds the value of a field receiver in.
     *
     * @param reference The reference
     * @param instance  The receiver, or {@code null} for a static method
     * @return The lambda, or {@code null} where the method takes the arguments as they are passed
     */
    public static ExpressionDef.@Nullable Lambda varargsAdapter(MethodReferenceExpression reference, @Nullable ExpressionDef instance) {
        MethodDef method = reference.method();
        List<ParameterDef> parameters = method.getParameters();
        if (reference.isConstructor() || parameters.isEmpty()
            || !(TypeHierarchy.unwrap(parameters.getLast().getType()) instanceof TypeDef.Array array)
            || !reference.isStatic() && !(instance instanceof VariableDef) || instance instanceof VariableDef.Super) {
            return null;
        }
        List<ParameterDef> passed = reference.instantiated().getParameters();
        int fixed = parameters.size() - 1;
        if (passed.size() < fixed || passed.size() == parameters.size()
            && TypeHierarchy.unwrap(passed.getLast().getType()) instanceof TypeDef.Array) {
            return null;
        }
        return adapter(reference, instance, array, fixed);
    }

    private static ExpressionDef.Lambda adapter(MethodReferenceExpression reference,
                                                @Nullable ExpressionDef instance,
                                                TypeDef.Array array,
                                                int fixed) {
        MethodDef method = reference.method();
        return reference.type().getLambda().implement((aThis, values) -> {
            List<ExpressionDef> arguments = new ArrayList<>(values.subList(0, fixed));
            List<VariableDef.MethodParameter> tail = values.subList(fixed, values.size());
            arguments.add(TypeDef.array(passedComponent(array, tail), 1).instantiate(tail));
            ExpressionDef call = instance == null ? reference.owner().invokeStatic(method, arguments)
                : instance.invoke(method, arguments);
            return TypeDef.VOID.equals(method.getReturnType()) || TypeDef.VOID.equals(reference.instantiated().getReturnType())
                ? (StatementDef) call : call.returning();
        });
    }

    /**
     * The array of what the values are passed as, `String[]`, as javac infers the variable of the method.
     */
    private static TypeDef passedComponent(TypeDef.Array array, List<VariableDef.MethodParameter> tail) {
        return tail.isEmpty() || tail.stream().anyMatch(value -> !value.type().equals(tail.getFirst().type()))
            || tail.getFirst().type().isPrimitive()
            ? (array.dimensions() == 1 ? array.componentType() : TypeDef.array(array.componentType(), array.dimensions() - 1))
            : tail.getFirst().type();
    }
}
