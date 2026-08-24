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

import java.util.List;
import java.util.Objects;

/**
 * Definition holding information about a lambda interface that can be implemented.
 * Use {@link ClassTypeDef#getLambda()} to create an instance from an existing type definition.
 *
 * @author Denis Stepanov
 * @since 1.7
 */
@Experimental
public final class LambdaDef {

    private final ClassTypeDef type;
    private final MethodDef method;
    private final MethodDef implementation;

    LambdaDef(ClassTypeDef type, MethodDef target, MethodDef implementation) {
        this.type = type;
        this.method = target;
        this.implementation = implementation;
    }

    /**
     * Implement lambda by providing the method body.
     *
     * @param lambdaBuilder The lambda builder
     * @return the lambda expression
     * @since 1.7
     */
    public ExpressionDef.Lambda implement(MethodDef.MethodBodyBuilder lambdaBuilder) {
        // TODO: check for not resolved variables
        return new ExpressionDef.Lambda(
            type,
            method,
            MethodDef.override(implementation).build(lambdaBuilder)
        );
    }

    /**
     * Implement lambda by providing the parameter names and the method body.
     *
     * <p>The implementation carries the parameter names of the functional interface's own method, so
     * every lambda over the same interface declares the same names. Java forbids a lambda parameter
     * from shadowing a name that is already in scope, which makes nested lambdas over one interface
     * fail to compile; naming the parameters explicitly avoids the collision.
     *
     * @param parameterNames The lambda parameter names
     * @param lambdaBuilder  The lambda builder
     * @return the lambda expression
     * @since 2.2
     */
    public ExpressionDef.Lambda implement(List<String> parameterNames, MethodDef.MethodBodyBuilder lambdaBuilder) {
        Objects.requireNonNull(parameterNames, "Parameter names cannot be null");
        List<ParameterDef> parameters = implementation.getParameters();
        if (parameterNames.size() != parameters.size()) {
            throw new IllegalArgumentException("Lambda method " + implementation.getName() + " has "
                + parameters.size() + " parameter(s) but " + parameterNames.size() + " name(s) were provided");
        }
        MethodDef.MethodDefBuilder builder = MethodDef.builder(implementation.getName())
            .addModifiers(implementation.getModifiers())
            .returns(implementation.getReturnType())
            .overrides();
        for (int i = 0; i < parameters.size(); i++) {
            builder.addParameter(parameters.get(i).withName(parameterNames.get(i)));
        }
        return new ExpressionDef.Lambda(type, method, builder.build(lambdaBuilder));
    }

    /**
     * Implement lambda by providing the parameter names and the method body.
     *
     * @param parameterNames The lambda parameter names
     * @param lambdaBuilder  The lambda builder
     * @return the lambda expression
     * @since 2.2
     */
    public ExpressionDef.Lambda implement(String[] parameterNames, MethodDef.MethodBodyBuilder lambdaBuilder) {
        return implement(List.of(parameterNames), lambdaBuilder);
    }

    /**
     * @return The lambda type
     */
    public ClassTypeDef getType() {
        return type;
    }

    /**
     * @return The lambda method
     */
    public MethodDef getMethod() {
        return method;
    }

    /**
     * @return The lambda implementation
     */
    public MethodDef getImplementation() {
        return implementation;
    }
}
