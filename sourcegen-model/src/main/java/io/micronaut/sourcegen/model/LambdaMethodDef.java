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

import java.util.Map;

/**
 * The lambda method definition.
 *
 * @author Denis Stepanov
 * @since 1.7
 */
@Experimental
public final class LambdaMethodDef {

    private final ClassTypeDef type;
    private final MethodDef method;

    LambdaMethodDef(ClassTypeDef type, MethodDef method) {
        this.type = type;
        this.method = method;
    }

    /**
     * Implement the method as lambda.
     *
     * @param lambdaBuilder The lambda builder
     * @return the lambda expression
     * @since 1.7
     */
    public ExpressionDef.Lambda implement(MethodDef.MethodBodyBuilder lambdaBuilder) {
        if (!method.getTypeVariables().isEmpty()) {
            throw new IllegalStateException("");
        }
        return ExpressionDef.Lambda.of(
            type,
            method,
            lambdaBuilder
        );
    }

    /**
     * Implement the method as lambda.
     *
     * @param resolvedTypeVariables The resolved type variables of the method.
     * @param lambdaBuilder The lambda builder
     * @return the lambda expression
     * @since 1.7
     */
    public ExpressionDef.Lambda implement(Map<String, TypeDef> resolvedTypeVariables,
                                          MethodDef.MethodBodyBuilder lambdaBuilder) {
        MethodDef implementedMethod = method.resolveTypeVariables(resolvedTypeVariables);
        return new ExpressionDef.Lambda(
            type,
            method,
            MethodDef.override(implementedMethod).build(lambdaBuilder)
        );
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
}
