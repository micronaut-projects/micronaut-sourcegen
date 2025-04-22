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
