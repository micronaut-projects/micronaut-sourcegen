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
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The parameter definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class ParameterDef extends AbstractElement {

    private final TypeDef type;

    private ParameterDef(String name,
                         EnumSet<Modifier> modifiers,
                         List<AnnotationDef> annotations,
                         List<String> javadoc,
                         TypeDef type, boolean synthetic) {
        super(name, modifiers, annotations, javadoc, synthetic);
        this.type = type;
    }

    public static ParameterDef of(String name, TypeDef type) {
        return ParameterDef.builder(name, type).build();
    }

    public static ParameterDefBuilder builder(String name, TypeDef type) {
        return new ParameterDefBuilder(name, type);
    }

    /**
     * Resolve the type variables for this parameter.
     * @param resolvedTypeVariables The resolved type variables
     * @return The resolved parameter
     * @since 1.7
     * @deprecated replaced with {@link #resolveTypeVariables(Function)}
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @SuppressWarnings("java:S1133")
    public ParameterDef resolveTypeVariables(Map<String, TypeDef> resolvedTypeVariables) {
        return resolveTypeVariables(resolvedTypeVariables::get);
    }

    /**
     * Resolve the type variables for this parameter.
     *
     * @param resolveVariableFn The resolved variable function
     * @return The resolved parameter
     * @since 2.0
     */
    public ParameterDef resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
        return ParameterDef.builder(name, type.resolveTypeVariables(resolveVariableFn))
            .addAnnotations(annotations)
            .addModifiers(modifiers)
            .addJavadoc(javadoc)
            .synthetic(synthetic)
            .build();
    }

    /**
     * Creates a copy of this parameter with a different name.
     *
     * @param name The new name
     * @return The renamed parameter
     * @since 2.2
     */
    public ParameterDef withName(String name) {
        if (this.name.equals(name)) {
            return this;
        }
        return ParameterDef.builder(name, type)
            .addAnnotations(annotations)
            .addModifiers(modifiers)
            .addJavadoc(javadoc)
            .synthetic(synthetic)
            .build();
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
            super(name, ParameterDefBuilder.class);
            this.type = type;
        }

        public ParameterDef build() {
            return new ParameterDef(name, modifiers, annotations, javadoc, type, synthetic);
        }

    }
}
