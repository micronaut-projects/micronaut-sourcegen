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
    @Nullable
    public static ExpressionDef varargsAdapter(MethodReferenceExpression reference) {
        MethodDef method = reference.method();
        List<ParameterDef> parameters = method.getParameters();
        ExpressionDef instance = reference.instance();
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
        return reference.type().getLambda().implement((aThis, values) -> {
            List<ExpressionDef> arguments = new ArrayList<>(values.subList(0, fixed));
            List<VariableDef.MethodParameter> tail = values.subList(fixed, values.size());
            // The array of what the values are passed as, `String[]`, as javac infers the variable of the method
            TypeDef component = tail.isEmpty() || tail.stream().anyMatch(value -> !value.type().equals(tail.getFirst().type()))
                || tail.getFirst().type().isPrimitive()
                ? (array.dimensions() == 1 ? array.componentType() : TypeDef.array(array.componentType(), array.dimensions() - 1))
                : tail.getFirst().type();
            arguments.add(TypeDef.array(component, 1).instantiate(tail));
            ExpressionDef call = instance == null ? reference.owner().invokeStatic(method, arguments)
                : instance.invoke(method, arguments);
            return TypeDef.VOID.equals(method.getReturnType()) || TypeDef.VOID.equals(reference.instantiated().getReturnType())
                ? (io.micronaut.sourcegen.model.StatementDef) call : call.returning();
        });
    }
}
