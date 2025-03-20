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
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.ast.MethodElement;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The method definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class MethodDef extends AbstractElement {

    public static final String CONSTRUCTOR = "<init>";
    private final TypeDef returnType;
    private final List<ParameterDef> parameters;
    private final List<StatementDef> statements;
    private final boolean override;
    private final List<ClassTypeDef> throwTypes;

    MethodDef(String name,
              EnumSet<Modifier> modifiers,
              TypeDef returnType,
              List<ParameterDef> parameters,
              List<StatementDef> statements,
              List<AnnotationDef> annotations,
              List<String> javadoc,
              boolean override,
              boolean synthetic,
              List<ClassTypeDef> throwTypes) {
        super(name, modifiers, annotations, javadoc, synthetic);
        this.returnType = Objects.requireNonNullElse(returnType, TypeDef.VOID);
        this.parameters = Collections.unmodifiableList(parameters);
        this.statements = statements;
        this.override = override;
        this.throwTypes = throwTypes;
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
     * @param parameterDefs The parameters of the body
     * @param modifiers     The constructor modifiers
     * @return A new constructor with a body.
     */
    public static MethodDef constructor(Collection<ParameterDef> parameterDefs, Modifier... modifiers) {
        MethodDefBuilder builder = MethodDef.builder(CONSTRUCTOR);
        int paramIndex = 0;
        for (ParameterDef parameterDef : parameterDefs) {
            builder.addParameter(parameterDef);
            int finalParamIndex = paramIndex;
            builder.addStatement((aThis, methodParameters) -> aThis.field(parameterDef.getName(), parameterDef.getType())
                .put(methodParameters.get(finalParamIndex)));
            paramIndex++;
        }
        builder.addModifiers(modifiers);
        return builder.build();
    }

    /**
     * Creates a method definition from {@link MethodElement}.
     *
     * @param methodElement The method element
     * @return The method definition
     * @since 1.5
     */
    @NonNull
    public static MethodDef of(@NonNull MethodElement methodElement) {
        return MethodDef.builder(methodElement.getName())
            .addParameters(Arrays.stream(methodElement.getSuspendParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.erasure(p.getType()))).toList())
            .returns(methodElement.isSuspend() ? TypeDef.OBJECT : TypeDef.erasure(methodElement.getReturnType()))
            .build();
    }

    /**
     * Creates a method definition from {@link Method}.
     *
     * @param method The method
     * @return The method definition
     * @since 1.5
     */
    @NonNull
    public static MethodDef of(@NonNull Method method) {
        return MethodDef.builder(method.getName())
            .addParameters(Arrays.stream(method.getParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList())
            .returns(TypeDef.of(method.getReturnType()))
            .build();
    }

    /**
     * Creates a method definition builder from {@link MethodElement}.
     *
     * @param methodElement The methodElement
     * @return The method definition builder
     * @since 1.5
     */
    @NonNull
    public static MethodDefBuilder override(@NonNull MethodElement methodElement) {
        return MethodDef.builder(methodElement.getName())
            .addModifiers(toOverrideModifiers(methodElement))
            .addParameters(Arrays.stream(methodElement.getSuspendParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.erasure(p.getType()))).toList())
            .returns(methodElement.isSuspend() ? TypeDef.OBJECT : TypeDef.erasure(methodElement.getReturnType()));
    }

    /**
     * Creates a method definition builder from {@link Method}.
     *
     * @param method The method
     * @return The method definition builder
     * @since 1.5
     */
    @NonNull
    public static MethodDefBuilder override(@NonNull Method method) {
        return MethodDef.builder(method.getName())
            .addModifiers(toOverrideModifiers(method.getModifiers()))
            .addParameters(Arrays.stream(method.getParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList())
            .returns(TypeDef.of(method.getReturnType()));
    }

    /**
     * Creates a constructor definition builder from {@link Method}.
     *
     * @param constructor The method
     * @return The method definition builder
     * @since 1.5
     */
    @NonNull
    public static MethodDefBuilder override(@NonNull Constructor<?> constructor) {
        return MethodDef.constructor()
            .addModifiers(toOverrideModifiers(constructor.getModifiers()))
            .addParameters(Arrays.stream(constructor.getParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList());
    }

    private static Modifier[] toOverrideModifiers(int modifiers) {
        List<Modifier> modifiersList = new ArrayList<>();
        if (java.lang.reflect.Modifier.isPublic(modifiers)) {
            modifiersList.add(Modifier.PUBLIC);
        }
        if (java.lang.reflect.Modifier.isProtected(modifiers)) {
            modifiersList.add(Modifier.PROTECTED);
        }
        return modifiersList.toArray(new Modifier[0]);
    }

    private static Modifier[] toOverrideModifiers(MethodElement methodElement) {
        List<Modifier> modifiersList = new ArrayList<>();
        if (methodElement.isPublic()) {
            modifiersList.add(Modifier.PUBLIC);
        }
        if (methodElement.isProtected()) {
            modifiersList.add(Modifier.PROTECTED);
        }
        return modifiersList.toArray(new Modifier[0]);
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

    /**
     * @return True if method is a constructor
     */
    public boolean isConstructor() {
        return CONSTRUCTOR.equals(getName());
    }

    /**
     * @return The exception types this method throws
     */
    public List<ClassTypeDef> getThrowTypes() {
        return throwTypes;
    }

    public static MethodDefBuilder builder(String name) {
        return new MethodDefBuilder(name);
    }

    @Override
    public String toString() {
        return "MethodDef{" +
            "name='" + name + '\'' +
            ", modifiers=" + modifiers +
            ", returnType=" + returnType +
            ", parameters=" + parameters +
            ", statements=" + statements +
            ", override=" + override +
            '}';
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
        private final List<MethodBodyBuilder> bodyBuilders = new ArrayList<>();
        private final List<StatementDef> statements = new ArrayList<>();
        private boolean overrides;
        private final List<ClassTypeDef> throwTypes = new ArrayList<>();

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

        /**
         * Add a parameter.
         *
         * @param name The name
         * @param type The type
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameter(@NonNull String name, @NonNull TypeDef type) {
            ParameterDef parameterDef = ParameterDef.builder(name, type).build();
            return addParameter(parameterDef);
        }

        /**
         * Add a parameter.
         *
         * @param type The type
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameter(@NonNull TypeDef type) {
            return addParameter("arg" + (parameters.size() + 1), type);
        }

        /**
         * Add a parameter.
         *
         * @param parameterDef The parameter def
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameter(@NonNull ParameterDef parameterDef) {
            Objects.requireNonNull(parameterDef, "Parameter cannot be null");
            parameters.add(parameterDef);
            return this;
        }

        /**
         * Add parameters.
         *
         * @param parameters The parameters
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameters(@NonNull Collection<ParameterDef> parameters) {
            parameters.forEach(this::addParameter);
            return this;
        }

        /**
         * Add a parameter.
         *
         * @param name The name
         * @param type The type
         * @return a builder
         */
        @NonNull
        public MethodDefBuilder addParameter(@NonNull String name, @NonNull Class<?> type) {
            return addParameter(name, TypeDef.of(type));
        }


        /**
         * Add a parameter.
         *
         * @param type The type
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameter(@NonNull Class<?> type) {
            return addParameter(TypeDef.of(type));
        }

        /**
         * Add a parameters.
         *
         * @param types The types
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameters(@NonNull Class<?>... types) {
            for (Class<?> type : types) {
                addParameter(type);
            }
            return this;
        }

        /**
         * Add parameters.
         *
         * @param types The types
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameters(@NonNull TypeDef... types) {
            return addParameters(List.of(types));
        }

        /**
         * Add parameters.
         *
         * @param types The types
         * @return a builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addParameters(@NonNull List<TypeDef> types) {
            for (TypeDef type : types) {
                addParameter(type);
            }
            return this;
        }

        /**
         * Add a statement to the static method body.
         *
         * @param bodyBuilder The builder
         * @return The builder
         * @since 1.5
         */
        @NonNull
        public MethodDefBuilder addStaticStatement(@NonNull Function<List<VariableDef.MethodParameter>, StatementDef> bodyBuilder) {
            return addStatement((aThis, methodParameters) -> bodyBuilder.apply(methodParameters));
        }

        /**
         * Add a statement to the method body.
         *
         * @param statement The statement
         * @return The builder
         */
        @NonNull
        public MethodDefBuilder addStatement(@NonNull StatementDef statement) {
            if (statement instanceof StatementDef.Multi multi) {
                multi.statements().forEach(this::addStatement);
            } else {
                statements.add(statement);
            }
            return this;
        }

        /**
         * Add a statement to the method body.
         *
         * @param bodyBuilder The body builder
         * @return The builder
         */
        @NonNull
        public MethodDefBuilder addStatement(@NonNull MethodDef.MethodBodyBuilder bodyBuilder) {
            bodyBuilders.add(bodyBuilder);
            return this;
        }

        /**
         * Add statements to the method body.
         *
         * @param newStatements The new statements
         * @return The builder
         */
        @NonNull
        public MethodDefBuilder addStatements(@NonNull Collection<StatementDef> newStatements) {
            statements.addAll(newStatements);
            return this;
        }

        /**
         * Add throw expressions to the method.
         *
         * @param types The types that this method throws
         * @return The builder
         */
        @NonNull
        public MethodDefBuilder addThrows(@NonNull ClassTypeDef... types) {
            throwTypes.addAll(Arrays.asList(types));
            return this;
        }

        /**
         * Add throw expressions to the method.
         *
         * @param types The types that this method throws
         * @return The builder
         */
        @NonNull
        public MethodDefBuilder addThrows(@NonNull List<ClassTypeDef> types) {
            throwTypes.addAll(types);
            return this;
        }

        public MethodDef build() {
            List<VariableDef.MethodParameter> variables = parameters.stream()
                .map(ParameterDef::asVariable)
                .toList();
            for (MethodBodyBuilder bodyBuilder : bodyBuilders) {
                StatementDef statement = bodyBuilder.apply(new VariableDef.This(), variables);
                if (statement != null) {
                    addStatement(statement);
                }
            }
            if (returnType == null && !statements.isEmpty()) {
                returnType = findReturnType(CollectionUtils.last(statements));
            }
            if (returnType == null && !name.equals(CONSTRUCTOR)) {
                returnType = TypeDef.VOID;
            }
            return new MethodDef(name, modifiers, returnType, parameters, statements, annotations, javadoc, overrides, synthetic, throwTypes);
        }

        private static TypeDef findReturnType(StatementDef statement) {
            if (statement instanceof StatementDef.Multi multi) {
                return findReturnType(CollectionUtils.last(multi.statements()));
            }
            if (statement instanceof StatementDef.Return aReturn) {
                return aReturn.expression().type();
            }
            if (statement instanceof StatementDef.Try aTry) {
                return findReturnType(aTry.statement());
            }
            if (statement instanceof StatementDef.Synchronized aSynchronized) {
                return findReturnType(aSynchronized.statement());
            }
            return null;
        }

        /**
         * Build a method with a body builder.
         *
         * @param bodyBuilder The body builder
         * @return The builder
         */
        @NonNull
        public MethodDef build(@NonNull MethodDef.MethodBodyBuilder bodyBuilder) {
            bodyBuilders.add(bodyBuilder);
            return build();
        }

        /**
         * Build a static method with a body builder.
         *
         * @param bodyBuilder The body builder
         * @return The builder
         */
        @NonNull
        public MethodDef buildStatic(@NonNull Function<List<VariableDef.MethodParameter>, StatementDef> bodyBuilder) {
            modifiers.add(Modifier.STATIC);
            bodyBuilders.add((aThis, methodParameters) -> bodyBuilder.apply(methodParameters));
            return build();
        }

    }

    /**
     * The body builder.
     *
     * @author Denis Stepanov
     * @since 1.4
     */
    public interface MethodBodyBuilder extends BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> {
    }
}
