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
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The method definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class MethodDef extends AbstractElement {

    public static final String CONSTRUCTOR = "<init>";
    private static final Function<String, @Nullable TypeDef> EMPTY = ignore -> null;
    private final TypeDef returnType;
    private final List<ParameterDef> parameters;
    private final List<StatementDef> statements;
    private final boolean override;
    private final List<TypeDef.TypeVariable> typeVariables;
    private final List<TypeDef> throwTypes;

    MethodDef(String name,
              EnumSet<Modifier> modifiers,
              @Nullable
              TypeDef returnType,
              List<ParameterDef> parameters,
              List<StatementDef> statements,
              List<AnnotationDef> annotations,
              List<String> javadoc,
              List<TypeDef.TypeVariable> typeVariables,
              boolean override,
              boolean synthetic,
              List<TypeDef> throwTypes) {
        super(name, modifiers, annotations, javadoc, synthetic);
        this.returnType = Objects.requireNonNullElse(returnType, TypeDef.VOID);
        this.parameters = Collections.unmodifiableList(parameters);
        this.statements = statements;
        this.override = override;
        this.typeVariables = Collections.unmodifiableList(typeVariables);
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
    public static MethodDef of(MethodElement methodElement) {
        return MethodDef.builder(methodElement).build();
    }

    /**
     * Creates a method definition from {@link MethodElement}.
     *
     * @param methodElement         The method element
     * @param resolvedTypeVariables The resolved type variable
     * @return The method definition
     * @since 1.7
     * @deprecated replaced with {@link #of(MethodElement, Function)}
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @SuppressWarnings("java:S1133")
    public static MethodDef of(MethodElement methodElement, Map<String, TypeDef> resolvedTypeVariables) {
        return of(methodElement, resolvedTypeVariables::get);
    }

    /**
     * Creates a method definition from {@link MethodElement}.
     *
     * @param methodElement     The method element
     * @param resolveVariableFn The resolved variable function
     * @return The method definition
     * @since 2.0
     */
    public static MethodDef of(MethodElement methodElement, Function<String, TypeDef> resolveVariableFn) {
        return MethodDef.builder(methodElement, resolveVariableFn).build();
    }

    /**
     * Creates a method definition from {@link Method}.
     *
     * @param method The method
     * @return The method definition
     * @since 1.5
     */
    public static MethodDef of(Method method) {
        return MethodDef.builder(method).build();
    }

    /**
     * Creates a method definition builder from {@link MethodElement}.
     *
     * @param methodElement The methodElement
     * @return The method definition builder
     * @since 1.5
     */
    public static MethodDefBuilder override(MethodElement methodElement) {
        return override(methodElement, ignore -> null);
    }

    /**
     * Creates a method definition builder from {@link MethodElement}.
     *
     * @param methodElement         The methodElement
     * @param resolvedTypeVariables The resolved type variables
     * @return The method definition builder
     * @since 1.5
     * @deprecated replaced with {@link #override(MethodElement, Function)}
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @SuppressWarnings("java:S1133")
    public static MethodDefBuilder override(MethodElement methodElement, Map<String, TypeDef> resolvedTypeVariables) {
        return override(methodElement, resolvedTypeVariables::get);
    }

    /**
     * Creates a method definition builder from {@link MethodElement}.
     *
     * @param methodElement     The methodElement
     * @param resolveVariableFn The resolved variable function
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder override(MethodElement methodElement, Function<String, @Nullable TypeDef> resolveVariableFn) {
        return MethodDef.builder(methodElement, resolveVariableFn)
            .overrides();
    }

    /**
     * Creates a method definition builder from {@link MethodElement} it used `getGeneric` versions of parameters and the return type.
     *
     * @param methodElement         The methodElement
     * @return The method definition builder
     * @since 1.7
     */
    public static MethodDefBuilder overrideGeneric(MethodElement methodElement) {
        return MethodDef.builderGeneric(methodElement)
            .overrides();
    }

    /**
     * Creates a method definition builder from {@link Method}.
     *
     * @param method The method
     * @return The method definition builder
     * @since 1.5
     */
    public static MethodDefBuilder override(Method method) {
        return MethodDef.builder(method)
            .overrides();
    }

    /**
     * Creates a method definition builder from {@link Method}.
     *
     * @param method The method
     * @return The method definition builder
     * @since 1.7
     */
    public static MethodDefBuilder override(MethodDef method) {
        return MethodDef.builder(method)
            .overrides();
    }

    /**
     * Creates a constructor definition builder from {@link Method}.
     *
     * @param constructor The method
     * @return The method definition builder
     * @since 1.5
     */
    public static MethodDefBuilder override(Constructor<?> constructor) {
        return MethodDef.builder(constructor);
    }

    /**
     * Resolves type variables.
     *
     * @param resolvedTypeVariables The type variables map
     * @return the resolved method
     * @deprecated replaced with {@link #resolveTypeVariables(Function)}
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @SuppressWarnings("java:S1133")
    public MethodDef resolveTypeVariables(Map<String, TypeDef> resolvedTypeVariables) {
        return resolveTypeVariables(resolvedTypeVariables::get);
    }

    /**
     * Resolves type variables.
     *
     * @param resolveVariableFn The resolve variable function
     * @return the resolved method
     * @since 2.0
     */
    public MethodDef resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
        if (!statements.isEmpty()) {
            throw new IllegalStateException("Method " + this + " resolving variables with statements not supported");
        }
        return MethodDef.builder(name)
            .addModifiers(modifiers)
            .addParameters(parameters.stream().map(p -> p.resolveTypeVariables(resolveVariableFn)).toList())
            .returns(returnType.resolveTypeVariables(resolveVariableFn))
            .addJavadoc(javadoc)
            .build();
    }

    private static Modifier[] toModifiers(MethodElement methodElement) {
        List<Modifier> modifiersList = new ArrayList<>();
        if (methodElement.isPublic()) {
            modifiersList.add(Modifier.PUBLIC);
        }
        if (methodElement.isProtected()) {
            modifiersList.add(Modifier.PROTECTED);
        }
        if (methodElement.isPrivate()) {
            modifiersList.add(Modifier.PRIVATE);
        }
        if (methodElement.isFinal()) {
            modifiersList.add(Modifier.FINAL);
        }
        return modifiersList.toArray(new Modifier[0]);
    }

    private static Modifier[] toModifiers(int modifiers) {
        List<Modifier> modifiersList = new ArrayList<>();
        if (java.lang.reflect.Modifier.isPublic(modifiers)) {
            modifiersList.add(Modifier.PUBLIC);
        }
        if (java.lang.reflect.Modifier.isProtected(modifiers)) {
            modifiersList.add(Modifier.PROTECTED);
        }
        if (java.lang.reflect.Modifier.isPrivate(modifiers)) {
            modifiersList.add(Modifier.PRIVATE);
        }
        if (java.lang.reflect.Modifier.isFinal(modifiers)) {
            modifiersList.add(Modifier.FINAL);
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
     * @return The type variables
     */
    public List<TypeDef.TypeVariable> getTypeVariables() {
        return typeVariables;
    }

    /**
     * @return The exception types this method throws
     */
    public List<TypeDef> getThrowTypes() {
        return throwTypes;
    }

    public static MethodDefBuilder builder(String name) {
        return new MethodDefBuilder(name);
    }

    /**
     * Creates a builder from {@link MethodElement}.
     *
     * @param methodElement The method element
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(MethodElement methodElement) {
        return builder(methodElement, EMPTY, false);
    }

    /**
     * Creates a builder from {@link MethodElement}.
     *
     * @param methodElement      The method element
     * @param resolvedVariableFn The type variable resolution function
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(MethodElement methodElement,
                                           Function<String, @Nullable TypeDef> resolvedVariableFn) {
        return builder(methodElement, resolvedVariableFn, false);
    }

    /**
     * Creates a builder from {@link MethodElement} preserving generics.
     *
     * @param methodElement The method element
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builderGeneric(MethodElement methodElement) {
        return builder(methodElement, EMPTY, true);
    }

    /**
     * Creates a builder from {@link MethodElement} preserving generics.
     *
     * @param methodElement      The method element
     * @param resolvedVariableFn The type variable resolution function
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builderGeneric(MethodElement methodElement,
                                                  Function<String, @Nullable TypeDef> resolvedVariableFn) {
        return builder(methodElement, resolvedVariableFn, true);
    }

    /**
     * Creates a builder from {@link MethodElement}.
     *
     * @param methodElement      The method element
     * @param resolvedVariableFn The type variable resolution function
     * @param generic            Whether generic types should be preserved
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(MethodElement methodElement,
                                           Function<String, @Nullable TypeDef> resolvedVariableFn,
                                           boolean generic) {
        MethodDefBuilder builder = MethodDef.builder(methodElement.getName());
        var returnTypeElement = generic ? methodElement.getGenericReturnType() : methodElement.getReturnType();
        TypeDef returnType = methodElement.isSuspend() ? TypeDef.OBJECT : TypeDef.erasure(returnTypeElement, resolvedVariableFn);
        return builder
            .addModifiers(toModifiers(methodElement))
            .addParameters(
                Arrays.stream(methodElement.getSuspendParameters())
                    .map(p -> ParameterDef.of(p.getName(), TypeDef.erasure(generic ? p.getGenericType() : p.getType(), resolvedVariableFn)))
                    .toList()
            )
            .returns(returnType);
    }

    /**
     * Creates a builder from an existing {@link MethodDef}.
     *
     * @param methodDef The method definition
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(MethodDef methodDef) {
        return MethodDef.builder(methodDef.getName())
            .addModifiers(methodDef.getModifiers())
            .addParameters(methodDef.getParameters())
            .returns(methodDef.getReturnType());
    }

    /**
     * Creates a builder from a {@link Method}.
     *
     * @param method The reflective method
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(Method method) {
        return MethodDef.builder(method.getName())
            .addModifiers(toModifiers(method.getModifiers()))
            .addParameters(Arrays.stream(method.getParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList())
            .returns(TypeDef.of(method.getReturnType()));
    }

    /**
     * Creates a builder from a {@link Constructor}.
     *
     * @param constructor The reflective constructor
     * @return The method definition builder
     * @since 2.0
     */
    public static MethodDefBuilder builder(Constructor<?> constructor) {
        return MethodDef.constructor()
            .overrides()
            .addModifiers(toModifiers(constructor.getModifiers()))
            .addParameters(Arrays.stream(constructor.getParameters()).map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList());
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
        @Nullable
        private TypeDef returnType;
        private final List<MethodBodyBuilder> bodyBuilders = new ArrayList<>();
        private final List<StatementDef> statements = new ArrayList<>();
        private boolean overrides;
        private final List<TypeDef.TypeVariable> typeVariables = new ArrayList<>();
        private final List<TypeDef> throwTypes = new ArrayList<>();

        private MethodDefBuilder(String name) {
            super(name, MethodDefBuilder.class);
        }

        /**
         * Add a type variable.
         * @param typeVariable The type variable
         * @return The type variable
         */
        public MethodDef.MethodDefBuilder addTypeVariable(TypeDef.TypeVariable typeVariable) {
            typeVariables.add(typeVariable);
            return this;
        }

        /**
         * Add a type variable.
         * @param typeVariables The type variables
         * @return The type variable
         */
        public MethodDef.MethodDefBuilder addTypeVariables(List<TypeDef.TypeVariable> typeVariables) {
            this.typeVariables.addAll(typeVariables);
            return this;
        }

        /**
         * Add a type variable.
         *
         * @param methodElement The method to copy type variables
         * @return The type variable
         * @since 2.0
         */
        public MethodDef.MethodDefBuilder addTypeVariables(MethodElement methodElement) {
            return addTypeVariables(methodElement, EMPTY);
        }

        /**
         * Add a type variables from {@link MethodElement}.
         *
         * @param methodElement     The type variables
         * @param resolveVariableFn The type variable function
         * @return The type variable
         * @since 2.0
         */
        public MethodDef.MethodDefBuilder addTypeVariables(MethodElement methodElement, Function<String, @Nullable TypeDef> resolveVariableFn) {
            return addTypeVariables(methodElement.getTypeArguments().entrySet()
                .stream()
                .flatMap(e -> {
                    TypeDef resolved = resolveVariableFn.apply(e.getKey());
                    if (resolved != null) {
                        return Stream.empty();
                    }
                    return Stream.of(TypeDef.variable(e.getKey(), TypeDef.erasure(e.getValue(), resolveVariableFn)));
                })
                .toList());
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
        public MethodDefBuilder addParameter(String name, TypeDef type) {
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
        public MethodDefBuilder addParameter(TypeDef type) {
            return addParameter("arg" + (parameters.size() + 1), type);
        }

        /**
         * Add a parameter.
         *
         * @param parameterDef The parameter def
         * @return a builder
         * @since 1.5
         */
        public MethodDefBuilder addParameter(ParameterDef parameterDef) {
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
        public MethodDefBuilder addParameters(Collection<ParameterDef> parameters) {
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
        public MethodDefBuilder addParameter(String name, Class<?> type) {
            return addParameter(name, TypeDef.of(type));
        }

        /**
         * Add a parameter.
         *
         * @param type The type
         * @return a builder
         * @since 1.5
         */
        public MethodDefBuilder addParameter(Class<?> type) {
            return addParameter(TypeDef.of(type));
        }

        /**
         * Add a parameters.
         *
         * @param types The types
         * @return a builder
         * @since 1.5
         */
        public MethodDefBuilder addParameters(Class<?>... types) {
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
        public MethodDefBuilder addParameters(TypeDef... types) {
            return addParameters(List.of(types));
        }

        /**
         * Add parameters.
         *
         * @param types The types
         * @return a builder
         * @since 1.5
         */
        public MethodDefBuilder addParameters(List<TypeDef> types) {
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
        public MethodDefBuilder addStaticStatement(Function<List<VariableDef.MethodParameter>, StatementDef> bodyBuilder) {
            return addStatement((aThis, methodParameters) -> bodyBuilder.apply(methodParameters));
        }

        /**
         * Add a statement to the method body.
         *
         * @param statement The statement
         * @return The builder
         */
        public MethodDefBuilder addStatement(StatementDef statement) {
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
        public MethodDefBuilder addStatement(MethodDef.MethodBodyBuilder bodyBuilder) {
            bodyBuilders.add(bodyBuilder);
            return this;
        }

        /**
         * Add statements to the method body.
         *
         * @param newStatements The new statements
         * @return The builder
         */
        public MethodDefBuilder addStatements(Collection<StatementDef> newStatements) {
            statements.addAll(newStatements);
            return this;
        }

        /**
         * Add throw expressions to the method.
         *
         * @param types The types that this method throws
         * @return The builder
         */
        public MethodDefBuilder addThrows(TypeDef... types) {
            throwTypes.addAll(Arrays.asList(types));
            return this;
        }

        /**
         * Add throw expressions to the method.
         *
         * @param types The types that this method throws
         * @return The builder
         */
        public MethodDefBuilder addThrows(List<TypeDef> types) {
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
                StatementDef last = CollectionUtils.last(statements);
                if (last != null) {
                    returnType = findReturnType(last);
                }
            }
            if (returnType == null && !name.equals(CONSTRUCTOR)) {
                returnType = TypeDef.VOID;
            }
            return new MethodDef(name, modifiers, returnType, parameters, statements, annotations, javadoc, typeVariables, overrides, synthetic, throwTypes);
        }

        @Nullable
        private static TypeDef findReturnType(StatementDef statement) {
            if (statement instanceof StatementDef.Multi multi) {
                StatementDef last = CollectionUtils.last(multi.statements());
                if (last == null) {
                    return null;
                }
                return findReturnType(last);
            }
            if (statement instanceof StatementDef.Return aReturn) {
                ExpressionDef expression = aReturn.expression();
                return expression == null ? null : expression.type();
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
        public MethodDef build(MethodDef.MethodBodyBuilder bodyBuilder) {
            bodyBuilders.add(bodyBuilder);
            return build();
        }

        /**
         * Build a static method with a body builder.
         *
         * @param bodyBuilder The body builder
         * @return The builder
         */
        public MethodDef buildStatic(Function<List<VariableDef.MethodParameter>, StatementDef> bodyBuilder) {
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
