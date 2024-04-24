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
 * The variable definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface VariableDef extends ExpressionDef {

    /**
     * The local variable.
     *
     * @param name The name
     * @param type The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Local(String name, TypeDef type) implements VariableDef {
    }

    /**
     * The variable of a method parameter.
     *
     * @param name The name
     * @param type The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record MethodParameter(String name, TypeDef type) implements VariableDef {
    }

    /**
     * The variable of a field.
     *
     * @param instanceVariable The instance variable
     * @param name                The name
     * @param type                The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Field(VariableDef instanceVariable,
                 String name,
                 TypeDef type) implements VariableDef {
    }

    /**
     * The variable of a static field.
     *
     * @param ownerType The owner type of the static field
     * @param name The field name
     * @param type The type of the field
     * @author Andriy Dmytruk
     * @since 1.0
     */
    @Experimental
    record StaticField(TypeDef ownerType,
                       String name,
                       TypeDef type) implements VariableDef {
    }

    /**
     * The variable of `this`.
     *
     * @param type The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record This(TypeDef type) implements VariableDef {
    }

}
