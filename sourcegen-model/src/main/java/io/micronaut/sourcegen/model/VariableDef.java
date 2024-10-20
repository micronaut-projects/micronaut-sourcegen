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
public sealed interface VariableDef extends ExpressionDef permits VariableDef.Field, VariableDef.Local, VariableDef.MethodParameter, VariableDef.StaticField, VariableDef.Super, VariableDef.This {

    /**
     * Assign this variable an expression.
     *
     * @param expression The expression.
     * @return The statement
     */
    default StatementDef assign(ExpressionDef expression) {
        return new StatementDef.Assign(this, expression);
    }

    /**
     * Assign this variable a parameter value.
     *
     * @param parameterDef The parameterDef.
     * @return The statement
     */
    default StatementDef assign(ParameterDef parameterDef) {
        return new StatementDef.Assign(
            this,
            new MethodParameter(parameterDef.name, parameterDef.getType())
        );
    }

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

        /**
         * Define and assign the variable.
         *
         * @param expression The expression to be assigned.
         * @return The statement
         * @since 1.2
         */
        public StatementDef.DefineAndAssign defineAndAssign(ExpressionDef expression) {
            return new StatementDef.DefineAndAssign(this, expression);
        }

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

        public MethodParameter(ParameterDef parameterDef) {
            this(parameterDef.name, parameterDef.getType());
        }
    }

    /**
     * The variable of a field.
     *
     * @param instance The instance variable
     * @param name     The name
     * @param type     The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Field(ExpressionDef instance,
                 String name,
                 TypeDef type) implements VariableDef {
    }

    /**
     * The variable of a static field.
     *
     * @param ownerType The owner type of the static field
     * @param name      The field name
     * @param type      The type of the field
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

        public Super superRef() {
            return new Super(TypeDef.SUPER);
        }

    }

    /**
     * The variable of `super`.
     *
     * @param type The type
     * @author Denis Stepanov
     * @since 1.4
     */
    @Experimental
    record Super(TypeDef type) implements VariableDef {
    }

}
