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
import io.micronaut.inject.ast.MethodElement;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The variable definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface VariableDef extends ExpressionDef permits VariableDef.ExceptionVar, VariableDef.Field, VariableDef.Local, VariableDef.MethodParameter, VariableDef.StaticField, VariableDef.Super, VariableDef.This {

    @Override
    default Stream<? extends ExpressionDef> nestedExpressionsStream() {
        return Stream.empty();
    }

    /**
     * Assign this variable an expression.
     *
     * @param expression The expression.
     * @return The statement
     */
    default StatementDef assign(ExpressionDef expression) {
        throw new UnsupportedOperationException("VariableDef " + getClass() + "  does not support assign");
    }

    /**
     * Assign this variable a parameter value.
     *
     * @param parameterDef The parameterDef.
     * @return The statement
     */
    default StatementDef assign(ParameterDef parameterDef) {
        return assign(new MethodParameter(parameterDef.getName(), parameterDef.getType()));
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

        @Override
        public StatementDef.Assign assign(ExpressionDef expression) {
            return new StatementDef.Assign(this, expression);
        }

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
            this(parameterDef.getName(), parameterDef.getType());
        }
    }

    /**
     * The variable of a field.
     *
     * @param instance The instance variable
     * @param declaringType The declared type of the field
     * @param name     The name
     * @param type     The type
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Field(ExpressionDef instance,
                 TypeDef declaringType,
                 String name,
                 TypeDef type) implements VariableDef {

        @Override
        public StatementDef.PutField assign(ExpressionDef expression) {
            return put(expression);
        }

        /**
         * @param expression The expression
         * @return The put expression
         * @since 1.5
         */
        public StatementDef.PutField put(ExpressionDef expression) {
            return new StatementDef.PutField(this, expression);
        }

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
    record StaticField(ClassTypeDef ownerType,
                       String name,
                       TypeDef type) implements VariableDef {

        /**
         * @param expression The expression
         * @return The put expression
         * @since 1.5
         */
        public StatementDef.PutStaticField put(ExpressionDef expression) {
            return new StatementDef.PutStaticField(this, expression);
        }

    }

    /**
     * The variable of `this`.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record This() implements VariableDef {

        public Super superRef() {
            return new Super(TypeDef.SUPER);
        }

        public Super superRef(ClassTypeDef superType) {
            return new Super(superType);
        }

        @Override
        public ClassTypeDef type() {
            return TypeDef.THIS;
        }
    }

    /**
     * The variable of `super`.
     *
     * @param type The type
     * @author Denis Stepanov
     * @since 1.5
     */
    @Experimental
    record Super(ClassTypeDef type) implements VariableDef {

        /**
         * Invoke super constructor statement.
         *
         * @param values The values
         * @return The call to the instance method
         * @since 1.5
         */
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(ExpressionDef... values) {
            return invokeSuperConstructor(Arrays.asList(values));
        }

        /**
         * Invoke super constructor statement.
         *
         * @param values The values
         * @return The call to the instance method
         * @since 1.5
         */
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(List<? extends ExpressionDef> values) {
            return invokeSuperConstructor(values.stream().map(ExpressionDef::type).toList(), values);
        }

        /**
         * Invoke super constructor statement.
         *
         * @param parameterTypes The parameterTypes
         * @param values         The values
         * @return The call to the instance method
         * @since 1.5
         */
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(List<TypeDef> parameterTypes, ExpressionDef... values) {
            return invokeSuperConstructor(parameterTypes, Arrays.asList(values));
        }

        /**
         * Invoke super constructor statement.
         *
         * @param parameterTypes The parameterTypes
         * @param values         The values
         * @return The call to the instance method
         * @since 1.5
         */
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(List<TypeDef> parameterTypes, List<? extends ExpressionDef> values) {
            return new StatementDef.InvokeSuperConstructor(this, MethodDef.constructor().addParameters(parameterTypes).build(), values);
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(Constructor<?> constructor, ExpressionDef... values) {
            return invokeSuperConstructor(constructor, List.of(values));
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(Constructor<?> constructor, List<? extends ExpressionDef> values) {
            return invokeSuperConstructor(Arrays.stream(constructor.getParameterTypes()).map(TypeDef::of).toList(), values);
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(MethodDef constructor, ExpressionDef... values) {
            return invokeSuperConstructor(constructor, List.of(values));
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(MethodDef constructor, List<? extends ExpressionDef> values) {
            return invokeSuperConstructor(constructor.getParameters().stream().map(ParameterDef::getType).toList(), values);
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(MethodElement constructor, ExpressionDef... values) {
            return invokeSuperConstructor(MethodDef.of(constructor), values);
        }

        /**
         * Invoke super constructor statement.
         *
         * @param constructor The constructor
         * @param values      The constructor values
         * @return The new instance
         */
        @Experimental
        public StatementDef.InvokeSuperConstructor invokeSuperConstructor(MethodElement constructor, List<? extends ExpressionDef> values) {
            return invokeSuperConstructor(MethodDef.of(constructor), values);
        }

    }

    /**
     * The exception that is part of Try-Catch block.
     *
     * @param type The exception type
     * @author Denis Stepanov
     * @since 1.5
     */
    @Experimental
    record ExceptionVar(ClassTypeDef type) implements VariableDef {
    }

}
