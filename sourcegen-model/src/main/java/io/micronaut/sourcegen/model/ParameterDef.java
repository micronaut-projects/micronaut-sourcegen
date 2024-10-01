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

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

/**
 * The parameter definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class ParameterDef extends AbstractElement {

    private final TypeDef type;

    private ParameterDef(String name, Set<Modifier> modifiers,
                         List<AnnotationDef> annotations,
                         List<String> javadoc,
                         TypeDef type) {
        super(name, modifiers, annotations, javadoc);
        this.type = type;
    }

    public static ParameterDef of(String name, TypeDef type) {
        return ParameterDef.builder(name, type).build();
    }

    public static ParameterDefBuilder builder(String name, TypeDef type) {
        return new ParameterDefBuilder(name, type);
    }

    public TypeDef getType() {
        return type;
    }

    public VariableDef asExpression() {
        return new VariableDef.MethodParameter(name, type);
    }

    /**
     * @return Return the parameter as a variable
     * @since 1.2
     */
    public VariableDef.MethodParameter asVariable() {
        return new VariableDef.MethodParameter(name, type);
    }

    /**
     * The parameter definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class ParameterDefBuilder extends AbstractElementBuilder<ParameterDefBuilder> {

        private final TypeDef type;

        private ParameterDefBuilder(String name, TypeDef type) {
            super(name);
            this.type = type;
        }

        public ParameterDef build() {
            return new ParameterDef(name, modifiers, annotations, javadoc, type);
        }

    }
}
