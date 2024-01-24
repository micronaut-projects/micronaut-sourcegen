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
 * The expression definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface ExpressionDef
    permits ExpressionDef.CallInstanceMethod, ExpressionDef.CallStaticMethod, ExpressionDef.Convert, ExpressionDef.NewInstance, VariableDef {

    /**
     * The type of the expression.
     *
     * @return The type
     */
    TypeDef type();

    /**
     * The new instance expression.
     *
     * @param type   The type
     * @param values The constructor values
     * @return The new instance
     */
    @Experimental
    static NewInstance instantiate(ClassTypeDef type,
                                   List<ExpressionDef> values) {
        return new NewInstance(
            type, values
        );
    }

    /**
     * The call the instance method expression.
     *
     * @param instance   The instance
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the instance method
     */
    @Experimental
    static CallInstanceMethod invoke(
        VariableDef instance,
        String name,
        List<ExpressionDef> parameters,
        TypeDef returning) {
        return new CallInstanceMethod(
            instance,
            name,
            parameters,
            returning
        );
    }


    /**
     * The call the instance method expression.
     *
     * @param typeDef    The class type def
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @return The call to the static method
     */
    @Experimental
    static CallStaticMethod invokeStatic(
        ClassTypeDef typeDef,
        String name,
        List<ExpressionDef> parameters,
        TypeDef returning) {
        return new CallStaticMethod(
            typeDef,
            name,
            parameters,
            returning
        );
    }

    /**
     * The new instance expression.
     *
     * @param type   The type
     * @param values The constructor values
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record NewInstance(ClassTypeDef type,
                       List<ExpressionDef> values) implements ExpressionDef {
    }

    /**
     * The convert variable expression. (To support Kotlin's nullable -> not-null conversion)
     *
     * @param type     The type
     * @param variable The variable reference
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Convert(TypeDef type,
                   VariableDef variable) implements ExpressionDef {
    }

    /**
     * The call an instance method expression.
     *
     * @param instance   The instance
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record CallInstanceMethod(VariableDef instance, String name, List<ExpressionDef> parameters,
                              TypeDef returning) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return returning;
        }
    }

    /**
     * The call a static method expression.
     *
     * @param classDef   The instance
     * @param name       The method name
     * @param parameters The parameters
     * @param returning  The returning
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record CallStaticMethod(ClassTypeDef classDef, String name, List<ExpressionDef> parameters,
                            TypeDef returning) implements ExpressionDef {
        @Override
        public TypeDef type() {
            return returning;
        }
    }
}
