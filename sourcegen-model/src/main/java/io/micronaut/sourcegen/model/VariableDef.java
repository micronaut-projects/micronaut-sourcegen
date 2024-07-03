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

/**
 * The variable definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface VariableDef extends ExpressionDef permits InstanceDef, VariableDef.Field, VariableDef.Local, VariableDef.MethodParameter, VariableDef.StaticField, VariableDef.This {

    /**
     * The condition of this variable.
     *
     * @param op         The operator
     * @param expression The expression of this variable
     * @return The condition expression
     */
    default ExpressionDef asCondition(String op, ExpressionDef expression) {
        return new ExpressionDef.Condition(op, this, expression);
    }

    /**
     * @return Is non-null expression
     */
    default ExpressionDef isNonNull() {
        return asCondition(" != ", ExpressionDef.nullValue());
    }

    /**
     * @return Is null expression
     */
    default ExpressionDef isNull() {
        return asCondition(" == ", ExpressionDef.nullValue());
    }

    /**
     * Convert this variable to a different type.
     *
     * @param typeDef The type
     * @return the convert expression
     */
    default ExpressionDef convert(TypeDef typeDef) {
        return new ExpressionDef.Convert(typeDef, this);
    }

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
     * The local variable.
     *
     * @param name The name
     * @param type The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Local(String name, TypeDef type) implements VariableDef, InstanceDef {

        @Override
        public StatementDef.DefineAndAssign assign(ExpressionDef expression) {
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
    }

    /**
     * The variable of a field.
     *
     * @param instanceVariable The instance variable
     * @param name             The name
     * @param type             The type
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
    record This(TypeDef type) implements VariableDef, InstanceDef {
    }

}
