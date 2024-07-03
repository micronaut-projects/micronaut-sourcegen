/*
 * Copyright 2017-2024 original authors
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
import io.micronaut.inject.ast.MethodElement;

import java.util.List;

/**
 * The instance definition.
 *
 * @author Denis Stepanov
 * @since 1.2
 */
@Experimental
public sealed interface InstanceDef extends VariableDef permits ExpressionDef.NewInstance, VariableDef.Local, VariableDef.This {

    /**
     * Reference the field of this variable.
     *
     * @param fieldName The field type
     * @param typeDef   Teh field type
     * @return The field variable
     */
    default VariableDef.Field field(String fieldName, TypeDef typeDef) {
        return new VariableDef.Field(this, fieldName, typeDef);
    }

    /**
     * The call the instance method expression.
     *
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the instance method
     */
    @Experimental
    default CallInstanceMethod invoke(String name, List<ExpressionDef> parameters, TypeDef returning) {
        return new CallInstanceMethod(
            this,
            name,
            parameters,
            returning
        );
    }

    /**
     * The call the instance method expression.
     *
     * @param methodElement The method element
     * @param parameters    The parameters
     * @return The call to the instance method
     */
    @Experimental
    default CallInstanceMethod invoke(MethodElement methodElement, ExpressionDef... parameters) {
        return invoke(methodElement, List.of(parameters));
    }

    /**
     * The call the instance method expression.
     *
     * @param methodElement The method element
     * @param parameters    The parameters
     * @return The call to the instance method
     */
    @Experimental
    default CallInstanceMethod invoke(MethodElement methodElement, List<ExpressionDef> parameters) {
        return new CallInstanceMethod(
            this,
            methodElement.getName(),
            parameters,
            TypeDef.of(methodElement.getReturnType())
        );
    }

}
