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
 * The statement definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public interface StatementDef {

    /**
     * The return statement.
     *
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Return(ExpressionDef expression) implements StatementDef {
    }

    /**
     * The assign statement.
     *
     * @param variable   The variable to assign
     * @param expression The expression
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Assign(VariableDef variable,
                  ExpressionDef expression) implements StatementDef {
    }

}
