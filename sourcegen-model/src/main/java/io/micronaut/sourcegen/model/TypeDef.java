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
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.WildcardElement;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface TypeDef permits ClassTypeDef, TypeDef.Array, TypeDef.Primitive, TypeDef.TypeVariable, TypeDef.Wildcard {

    TypeDef VOID = primitive("void");

    TypeDef OBJECT = of(Object.class);

    TypeDef STRING = of(String.class);

    /**
     * A simple type representing a special this-type, in context of a class def, method or field the type will be replaced by the current type.
     */
    TypeDef THIS = of(ThisType.class);

    /**
     * Instantiate an array of this class.
     *
     * @param length The length of the array
     * @return The instantiate expression
     * @since 1.2
     */
    default ExpressionDef instantiateArray(int length) {
        return new ExpressionDef.NewArrayOfSize((Array) this, length);
    }

    /**
     * Instantiate an array of this class.
     *
     * @param expressions The items expressions
     * @return The instantiate expression
     * @since 1.2
     */
    default ExpressionDef instantiateArray(List<ExpressionDef> expressions) {
        return new ExpressionDef.NewArrayInitialized((Array) this, expressions);
    }

    /**
     * Instantiate an array of this class.
     *
     * @param expressions The items expressions
     * @return The instantiate expression
     * @since 1.2
     */
    default ExpressionDef instantiateArray(ExpressionDef... expressions) {
        return instantiateArray(List.of(expressions));
    }

    /**
     * Creates new primitive type.
     *
     * @param name The primitive type name
     * @return a new type definition
     */
    static Primitive primitive(String name) {
        return new Primitive(name);
    }

    /**
     * Creates new primitive type.
     *
     * @param type The primitive type
     * @return a new type definition
     */
    static Primitive primitive(Class<?> type) {
        if (!type.isPrimitive()) {
            throw new IllegalStateException("Expected a primitive type got: " + type);
        }
        return primitive(type.getName());
    }

    static Wildcard wildcard() {
        return new Wildcard(Collections.singletonList(TypeDef.of(Object.class)), Collections.emptyList());
    }

    static Wildcard wildcardSubtypeOf(TypeDef upperBound) {
        return new Wildcard(Collections.singletonList(upperBound), Collections.emptyList());
    }

    static Wildcard wildcardSupertypeOf(TypeDef lowerBound) {
        return new Wildcard(Collections.singletonList(TypeDef.of(Object.class)), Collections.singletonList(lowerBound));
    }

    static Array array(TypeDef componentType) {
        return new Array(componentType, 1, false);
    }

    static Array array(TypeDef componentType, int dimensions) {
        return new Array(componentType, dimensions, false);
    }

    /**
     * Creates a new type.
     *
     * @param type The type
     * @return a new type definition
     */
    static TypeDef of(Class<?> type) {
        if (type.isPrimitive()) {
            return primitive(type);
        }
        if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            TypeDef typeDef;
            if (componentType.isArray()) {
                TypeDef componentArray = of(componentType);
                typeDef = array(componentArray, 1);
            } else {
                typeDef = of(componentType);
            }
            return new Array(typeDef, 1, false);
        }
        return ClassTypeDef.of(type);
    }

    /**
     * Creates a new type with generic parameters.
     *
     * @param type              The type
     * @param genericParameters The parameters
     * @return a new type definition
     */
    static ClassTypeDef parameterized(Class<?> type, Class<?>... genericParameters) {
        return parameterized(ClassTypeDef.of(type), Stream.of(genericParameters).map(TypeDef::of).toList());
    }

    /**
     * Creates a new type with generic parameters.
     *
     * @param type              The type
     * @param genericParameters The parameters
     * @return a new type definition
     */
    static ClassTypeDef parameterized(Class<?> type, TypeDef... genericParameters) {
        return parameterized(ClassTypeDef.of(type), genericParameters);
    }

    /**
     * Creates a new type with generic parameters.
     *
     * @param type              The type
     * @param genericParameters The parameters
     * @return a new type definition
     */
    static ClassTypeDef parameterized(ClassTypeDef type, TypeDef... genericParameters) {
        return parameterized(type, List.of(genericParameters));
    }

    /**
     * Creates a new type with generic parameters.
     *
     * @param type              The type
     * @param genericParameters The parameters
     * @return a new type definition
     */
    static ClassTypeDef parameterized(ClassTypeDef type, Class<?>... genericParameters) {
        return parameterized(type, Stream.of(genericParameters).map(TypeDef::of).toList());
    }

    /**
     * Creates a new type with generic parameters.
     *
     * @param type              The type
     * @param genericParameters The parameters
     * @return a new type definition
     */
    static ClassTypeDef parameterized(ClassTypeDef type, List<TypeDef> genericParameters) {
        return new ClassTypeDef.Parameterized(type, genericParameters);
    }

    /**
     * Creates a new type.
     *
     * @param classElement The class element
     * @return a new type definition
     */
    static TypeDef of(ClassElement classElement) {
        if (classElement.isArray()) {
            int dimensions = classElement.getArrayDimensions();
            for (int i = 0; i < dimensions; ++i) {
                classElement = classElement.fromArray();
            }
            return array(TypeDef.of(classElement), dimensions);
        }
        if (classElement.isPrimitive()) {
            return primitive(classElement.getName());
        }
        if (classElement instanceof GenericPlaceholderElement placeholderElement) {
            return new TypeVariable(
                placeholderElement.getVariableName(),
                placeholderElement.getBounds().stream().map(TypeDef::of).toList()
            );
        }
        if (classElement instanceof WildcardElement wildcardElement) {
            return new Wildcard(
                wildcardElement.getUpperBounds().stream().map(TypeDef::of).toList(),
                wildcardElement.getLowerBounds().stream().map(TypeDef::of).toList()
            );
        }
        return toTypeDef(classElement);
    }

    private static TypeDef toTypeDef(ClassElement classElement) {
        if (classElement.getFirstTypeArgument().isPresent()) {
            Map<String, ClassElement> nextArguments = classElement.getTypeArguments();
            List<? extends GenericPlaceholderElement> placeHolders = classElement.getDeclaredGenericPlaceholders();
            return new ClassTypeDef.Parameterized(
                ClassTypeDef.of(classElement),
                toTypeArguments(placeHolders, nextArguments)
            );
        } else {
            return ClassTypeDef.of(classElement);
        }
    }

    private static List<TypeDef> toTypeArguments(
        List<? extends GenericPlaceholderElement> declaredTypeVariables,
        Map<String, ClassElement> typeArguments) {
        return declaredTypeVariables
            .stream().map(v -> {
                String variableName = v.getVariableName();
                ClassElement classElement = typeArguments.get(variableName);
                return TypeDef.of(classElement != null ? classElement : v.getType());
            }).toList();
    }

    /**
     * @return Is nullable type
     */
    default boolean isNullable() {
        return false;
    }

    /**
     * @return Is primitive type
     */
    default boolean isPrimitive() {
        return this instanceof TypeDef.Primitive;
    }

    /**
     * @return Is Array type
     */
    default boolean isArray() {
        return this instanceof TypeDef.Array;
    }

    /**
     * @return A new nullable type
     */
    default TypeDef makeNullable() {
        return this;
    }

    /**
     * The primitive type name.
     *
     * @param name The primitive type name
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Primitive(String name) implements TypeDef {

        public static final TypeDef.Primitive INT = primitive(int.class);
        public static final TypeDef.Primitive BOOLEAN = primitive(boolean.class);
        public static final TypeDef.Primitive LONG = primitive(long.class);
        public static final TypeDef.Primitive CHAR = primitive(char.class);
        public static final TypeDef.Primitive BYTE = primitive(byte.class);
        public static final TypeDef.Primitive SHORT = primitive(short.class);
        public static final TypeDef.Primitive DOUBLE = primitive(double.class);
        public static final TypeDef.Primitive FLOAT = primitive(float.class);

        @Override
        public boolean isPrimitive() {
            return true;
        }

        @Override
        public boolean isArray() {
            return false;
        }

        @Override
        public TypeDef makeNullable() {
            return wrapperType().makeNullable();
        }

        public ClassTypeDef wrapperType() {
            Class<?> primitiveType = ClassUtils.getPrimitiveType(name).orElseThrow(() -> new IllegalStateException("Unrecognized primitive type: " + name));
            return ClassTypeDef.of(
                ReflectionUtils.getWrapperType(primitiveType)
            );
        }

        /**
         * The new instance expression for primitives.
         *
         * @param value The initial value
         * @return The new instance
         * @since 1.3
         */
        @Experimental
        public PrimitiveInstance initialize(ExpressionDef value) {
            return new PrimitiveInstance(this, value);
        }

        /**
         * The new instance expression for primitives.
         *
         * @param constant The constant
         * @return The new instance
         * @since 1.3
         */
        @Experimental
        public PrimitiveInstance initialize(Object constant) {
            return new PrimitiveInstance(this, new ExpressionDef.Constant(this, constant));
        }

        /**
         * The new instance expression.
         *
         * @param type   The type
         * @param value The initial value
         * @author Elif Kurtay
         * @since 1.3
         */
        @Experimental
        public record PrimitiveInstance(TypeDef.Primitive type,
                                        ExpressionDef value) implements ExpressionDef {
        }
    }

    /**
     * The wildcard type definition.
     *
     * @param upperBounds The upper bounds
     * @param lowerBounds The lower bounds
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Wildcard(List<TypeDef> upperBounds,
                    List<TypeDef> lowerBounds) implements TypeDef {
        @Override
        public boolean isPrimitive() {
            return false;
        }

        @Override
        public boolean isArray() {
            return false;
        }
    }

    /**
     * The type variable ref.
     *
     * @param name   The variable name
     * @param bounds The bounds
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record TypeVariable(String name, List<TypeDef> bounds) implements TypeDef {

        public TypeVariable(String name) {
            this(name, List.of());
        }

        public static TypeVariable of(String name, ClassElement classElement) {
            if (classElement instanceof GenericPlaceholderElement placeholderElement) {
                return new TypeVariable(
                    name,
                    placeholderElement.getBounds().stream().map(TypeDef::of).toList()
                );
            } else {
                return new TypeVariable(name);
            }
        }

    }

    /**
     * The type for representing an array.
     *
     * @param componentType The array component type
     * @param dimensions    The dimensions
     * @param nullable      Is nullable
     * @author Andriy Dmytruk
     * @since 1.0
     */
    @Experimental
    record Array(TypeDef componentType, int dimensions, boolean nullable) implements TypeDef {

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public TypeDef makeNullable() {
            return new Array(componentType, dimensions, true);
        }

        @Override
        public boolean isPrimitive() {
            return false;
        }

        @Override
        public boolean isArray() {
            return true;
        }
    }
}
