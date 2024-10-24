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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * The method definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class MethodDef extends AbstractElement {

    private static final String CONSTRUCTOR = "<init>";
    private final TypeDef returnType;
    private final List<ParameterDef> parameters;
    private final List<StatementDef> statements;
    private final boolean override;

    MethodDef(String name,
              EnumSet<Modifier> modifiers,
              TypeDef returnType,
              List<ParameterDef> parameters,
              List<StatementDef> statements,
              List<AnnotationDef> annotations,
              List<String> javadoc, boolean override) {
        super(name, modifiers, annotations, javadoc);
        this.returnType = returnType;
        this.parameters = Collections.unmodifiableList(parameters);
        this.statements = statements;
        this.override = override;
    }

    /**
     * @return Starts a constructor.
     */
    public static MethodDefBuilder constructor() {
        return MethodDef.builder(CONSTRUCTOR);
    }

    /**
     * Create a new constructor with parameters assigned to fields with the same name.
     *
     * @param thisType      The type to be constructed
     * @param parameterDefs The parameters of the body
     * @param modifiers     The constructor modifiers
     * @return A new constructor with a body.
     */
    public static MethodDef constructor(ClassTypeDef thisType, Collection<ParameterDef> parameterDefs, Modifier... modifiers) {
        MethodDefBuilder builder = MethodDef.builder(CONSTRUCTOR);
        for (ParameterDef parameterDef : parameterDefs) {
            builder.addParameter(parameterDef);
            builder.addStatement(new StatementDef.Assign(
                new VariableDef.Field(
                    new VariableDef.This(thisType),
                    parameterDef.getName(),
                    parameterDef.getType()
                ),
                parameterDef.asExpression()
            ));
        }
        builder.addModifiers(modifiers);
        return builder.build();
    }

    public TypeDef getReturnType() {
        return returnType;
    }

    public List<ParameterDef> getParameters() {
        return parameters;
    }

    public List<StatementDef> getStatements() {
        return statements;
    }

    @Nullable
    public ParameterDef findParameter(String name) {
        for (ParameterDef parameter : parameters) {
            if (parameter.getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }

    @NonNull
    public ParameterDef getParameter(String name) {
        ParameterDef parameter = findParameter(name);
        if (parameter == null) {
            throw new IllegalStateException("Method: " + name + " doesn't have parameter: " + name);
        }
        return parameter;
    }

    /**
     * @return True if method is an override
     */
    public boolean isOverride() {
        return override;
    }

    public static MethodDefBuilder builder(String name) {
        return new MethodDefBuilder(name);
    }

    /**
     * The method builder definition.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class MethodDefBuilder extends AbstractElementBuilder<MethodDefBuilder> {

        private final List<ParameterDef> parameters = new ArrayList<>();
        private TypeDef returnType;
        private final List<StatementDef> statements = new ArrayList<>();
        private boolean overrides;

        private MethodDefBuilder(String name) {
            super(name);
        }

        /**
         * The return type of the method.
         * In a case of missing return type it will be extracted from the statements.
         *
         * @param type The return type
         * @return the current builder
         */
        public MethodDefBuilder returns(TypeDef type) {
            this.returnType = type;
            return this;
        }

        /**
         * Mark the method as an override.
         *
         * @return the current builder
         */
        public MethodDefBuilder overrides() {
            return overrides(true);
        }

        /**
         * Mark the method as an override.
         *
         * @param overrides The value
         * @return the current builder
         */
        public MethodDefBuilder overrides(boolean overrides) {
            this.overrides = overrides;
            return this;
        }

        public MethodDefBuilder returns(Class<?> type) {
            return returns(TypeDef.of(type));
        }

        public MethodDefBuilder addParameter(String name, TypeDef type) {
            ParameterDef parameterDef = ParameterDef.builder(name, type).build();
            return addParameter(parameterDef);
        }

        public MethodDefBuilder addParameter(ParameterDef parameterDef) {
            Objects.requireNonNull(parameterDef, "Parameter cannot be null");
            parameters.add(parameterDef);
            return this;
        }

        public MethodDefBuilder addParameter(String name, Class<?> type) {
            return addParameter(name, TypeDef.of(type));
        }

        public MethodDefBuilder addStatement(StatementDef statement) {
            if (statement instanceof StatementDef.Multi multi) {
                multi.statements().forEach(this::addStatement);
            } else {
                statements.add(statement);
            }
            return this;
        }

        public MethodDefBuilder addStatements(Collection<StatementDef> newStatements) {
            statements.addAll(newStatements);
            return this;
        }

        public MethodDef build() {
            return build((self, parameterDefs) -> null);
        }

        public MethodDef build(BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> bodyBuilder) {
            List<VariableDef.MethodParameter> variables = parameters.stream()
                .map(ParameterDef::asVariable)
                .toList();
            StatementDef statement = bodyBuilder.apply(new VariableDef.This(TypeDef.THIS), variables);
            if (statement != null) {
                addStatement(statement);
                if (returnType == null && !statements.isEmpty()) {
                    StatementDef lastStatement = statements.get(statements.size() - 1);
                    if (lastStatement instanceof StatementDef.Return aReturn) {
                        returnType = aReturn.expression().type();
                    }
                }
            }
            if (returnType == null && !name.equals(CONSTRUCTOR)) {
                throw new IllegalStateException("Return type of method: " + name + " not specified!");
            }
            return new MethodDef(name, modifiers, returnType, parameters, statements, annotations, javadoc, overrides);
        }

    }
}
