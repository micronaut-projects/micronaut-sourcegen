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
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.ast.WildcardElement;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

/**
 * The type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface TypeDef permits ClassTypeDef, TypeDef.Annotated, TypeDef.Array, TypeDef.Primitive, TypeDef.TypeVariable, TypeDef.Wildcard {

    Primitive VOID = primitive(void.class);

    ClassTypeDef OBJECT = ClassTypeDef.of(Object.class);

    ClassTypeDef CLASS = ClassTypeDef.of(Class.class);

    ClassTypeDef STRING = ClassTypeDef.of(String.class);

    /**
     * A simple type representing a special this-type, in context of a class def, method or field the type will be replaced by the current type.
     */
    ClassTypeDef THIS = ClassTypeDef.of(ThisType.class);

    /**
     * A simple type representing a special super-type, in context of a class def, method or field the type will be replaced by the current super type.
     */
    ClassTypeDef SUPER = ClassTypeDef.of(SuperType.class);

    /**
     * Resolve the type variables.
     * @param resolvedTypeVariables The resolved type variables.
     * @return The resolved type or the previous.
     */
    default TypeDef resolveTypeVariables(Map<String, TypeDef> resolvedTypeVariables) {
        return this;
    }

    /**
     * Define a type with annotations.
     *
     * @param annotations the annotation definitions to be added
     * @return The AnnotatedTypeDef
     * @since 1.4
     */
    default Annotated annotated(AnnotationDef... annotations) {
        return annotated(List.of(annotations));
    }

    /**
     * Define a type with annotations.
     *
     * @param annotations The list of the AnnotationDef
     * @return The AnnotatedTypeDef
     * @since 1.4
     */
    default Annotated annotated(List<AnnotationDef> annotations) {
        return new AnnotatedTypeDef(this, annotations);
    }

    /**
     * Create an array type.
     *
     * @return The array type
     * @since 1.5
     */
    default TypeDef.Array array() {
        return new TypeDef.Array(this, 1, false);
    }

    /**
     * Create an array type.
     *
     * @param dimension The dimension of the array
     * @return The array type
     * @since 1.5
     */
    default TypeDef.Array array(int dimension) {
        return new TypeDef.Array(this, dimension, false);
    }

    /**
     * Create a new type definition.
     *
     * @param name The type name
     * @return type definition
     * @since 1.5
     */
    static TypeDef of(String name) {
        int dimension = 0;
        while (name.endsWith("[]")) {
            dimension++;
            name = name.substring(0, name.length() - 2);
        }
        if (dimension > 0) {
            return new TypeDef.Array(of(name), dimension, false);
        }
        return switch (name) {
            case "void", "V" -> TypeDef.VOID;
            case "byte", "B" -> Primitive.BYTE;
            case "int", "I" -> Primitive.INT;
            case "boolean", "Z" -> Primitive.BOOLEAN;
            case "long", "J" -> Primitive.LONG;
            case "char", "C" -> Primitive.CHAR;
            case "short", "S" -> Primitive.SHORT;
            case "double", "D" -> Primitive.DOUBLE;
            case "float", "F" -> Primitive.FLOAT;
            default -> ClassTypeDef.of(name);
        };
    }

    /**
     * Creates new primitive type.
     *
     * @param type The primitive type
     * @return a new type definition
     */
    static Primitive primitive(String type) {
        return switch (type) {
            case "void", "V" -> Primitive.VOID;
            case "byte", "B" -> Primitive.BYTE;
            case "int", "I" -> Primitive.INT;
            case "boolean", "Z" -> Primitive.BOOLEAN;
            case "long", "J" -> Primitive.LONG;
            case "char", "C" -> Primitive.CHAR;
            case "short", "S" -> Primitive.SHORT;
            case "double", "D" -> Primitive.DOUBLE;
            case "float", "F" -> Primitive.FLOAT;
            default -> throw new IllegalStateException("Expected a primitive type got: " + type);
        };
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
        return new Primitive(type);
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
        return array(componentType, 1);
    }

    static Array array(TypeDef componentType, int dimensions) {
        if (componentType instanceof Array array) {
            return new Array(array.componentType, array.dimensions + dimensions, false);
        }
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
            int dimensions = 1;
            while (componentType.isArray()) {
                componentType = componentType.getComponentType();
                dimensions++;
            }
            return new Array(TypeDef.of(componentType), dimensions, false);
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
     * Creates a new type variable.
     *
     * @param name   The type
     * @param bounds The bounds
     * @return a new type variable
     */
    static TypeDef.TypeVariable variable(String name, List<TypeDef> bounds) {
        return new TypeDef.TypeVariable(name, bounds);
    }

    /**
     * Creates a new type variable.
     *
     * @param name   The type
     * @param bounds The bounds
     * @return a new type variable
     */
    static TypeDef.TypeVariable variable(String name, TypeDef... bounds) {
        return new TypeDef.TypeVariable(name, List.of(bounds));
    }

    /**
     * Creates a new type.
     *
     * @param typedElement The typed element
     * @return a new type definition
     */
    static TypeDef of(TypedElement typedElement) {
        return of(typedElement, false);
    }

    /**
     * Creates a new type erasure.
     *
     * @param typedElement The typed element
     * @return a new type definition
     */
    static TypeDef erasure(TypedElement typedElement) {
        return of(typedElement, true);
    }

    /**
     * Creates a new type erasure.
     *
     * @param typedElement The typed element
     * @param resolvedTypeVariables The resolved type variables
     * @return a new type definition
     */
    static TypeDef erasure(TypedElement typedElement, Map<String, TypeDef> resolvedTypeVariables) {
        return of(typedElement, resolvedTypeVariables, true);
    }

    /**
     * Creates a new type.
     *
     * @param typedElement The typed element
     * @param erasure Is erasure type required
     * @return a new type definition
     */
    private static TypeDef of(TypedElement typedElement, boolean erasure) {
        return of(typedElement, Map.of(), erasure);
    }

    /**
     * Creates a new type.
     *
     * @param typedElement The typed element
     * @param resolvedTypeVariables The resolved type variables
     * @return a new type definition
     */
    private static TypeDef of(TypedElement typedElement, Map<String, TypeDef> resolvedTypeVariables) {
        return of(typedElement, resolvedTypeVariables, false);
    }

    /**
     * Creates a new type.
     *
     * @param typedElement The typed element
     * @param resolvedTypeVariables The resolved type variables
     * @param erasure Is erasure type required
     * @return a new type definition
     */
    static TypeDef of(TypedElement typedElement, Map<String, TypeDef> resolvedTypeVariables, boolean erasure) {
        int dimensions = 0;
        while (typedElement.isArray()) {
            ClassElement arrayableClassElement = (ClassElement) typedElement;
            typedElement = arrayableClassElement.fromArray();
            dimensions++;
        }
        if (dimensions > 0) {
            return array(of(typedElement), dimensions);
        }
        if (typedElement.isPrimitive()) {
            return primitive(typedElement.getName());
        }
        if (typedElement instanceof GenericPlaceholderElement placeholderElement) {
            Optional<ClassElement> optionalResolved = placeholderElement.getResolved();
            if (optionalResolved.isPresent()) {
                return of(optionalResolved.get(), resolvedTypeVariables, erasure);
            }
            TypeDef resolved = resolvedTypeVariables.get(placeholderElement.getVariableName());
            if (resolved != null) {
                return resolved;
            }
            return TypeDef.variable(
                placeholderElement.getVariableName(),
                placeholderElement.getBounds().stream().map(e -> TypeDef.of(e, resolvedTypeVariables, erasure)).toList()
            );
        }
        if (typedElement instanceof WildcardElement wildcardElement) {
            return new Wildcard(
                wildcardElement.getUpperBounds().stream().map(te -> of(te, resolvedTypeVariables)).toList(),
                wildcardElement.getLowerBounds().stream().map(te -> of(te, resolvedTypeVariables)).toList()
            );
        }
        if (typedElement instanceof ClassElement classElement) {
            return ClassTypeDef.of(classElement, resolvedTypeVariables, erasure);
        }
        throw new IllegalStateException("Unknown typed element: " + typedElement);
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
        return this instanceof Primitive;
    }

    /**
     * @return Is Array type
     */
    default boolean isArray() {
        return this instanceof Array;
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
     * @param clazz The primitive clazz
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Primitive(Class<?> clazz) implements TypeDef {

        public static final Primitive INT = primitive(int.class);
        public static final Primitive BOOLEAN = primitive(boolean.class);
        public static final Primitive LONG = primitive(long.class);
        public static final Primitive CHAR = primitive(char.class);
        public static final Primitive BYTE = primitive(byte.class);
        public static final Primitive SHORT = primitive(short.class);
        public static final Primitive DOUBLE = primitive(double.class);
        public static final Primitive FLOAT = primitive(float.class);

        public static final ExpressionDef.Constant TRUE = BOOLEAN.constant(true);
        public static final ExpressionDef.Constant FALSE = BOOLEAN.constant(false);

        // Wrappers
        public static final ClassTypeDef BOOLEAN_WRAPPER = TypeDef.Primitive.BOOLEAN.wrapperType();
        public static final ClassTypeDef INT_WRAPPER = TypeDef.Primitive.INT.wrapperType();
        public static final ClassTypeDef LONG_WRAPPER = TypeDef.Primitive.LONG.wrapperType();
        public static final ClassTypeDef DOUBLE_WRAPPER = TypeDef.Primitive.DOUBLE.wrapperType();
        public static final ClassTypeDef FLOAT_WRAPPER = TypeDef.Primitive.FLOAT.wrapperType();
        public static final ClassTypeDef SHORT_WRAPPER = TypeDef.Primitive.SHORT.wrapperType();
        public static final ClassTypeDef BYTE_WRAPPER = TypeDef.Primitive.BYTE.wrapperType();
        public static final ClassTypeDef CHAR_WRAPPER = TypeDef.Primitive.CHAR.wrapperType();

        private static final Map<TypeDef, ClassTypeDef> PRIMITIVE_TO_WRAPPER = Map.of(
            BOOLEAN, BOOLEAN_WRAPPER,
            INT, INT_WRAPPER,
            DOUBLE, DOUBLE_WRAPPER,
            LONG, LONG_WRAPPER,
            FLOAT, FLOAT_WRAPPER,
            SHORT, SHORT_WRAPPER,
            CHAR, CHAR_WRAPPER,
            BYTE, BYTE_WRAPPER);

        private static final Map<String, TypeDef> WRAPPER_TO_PRIMITIVE =
            PRIMITIVE_TO_WRAPPER.entrySet()
                .stream()
                .collect(toMap(e -> e.getValue().getName(), Map.Entry::getKey));

        /**
         * Unbox if possible.
         * @param typeDef The type
         * @return The unboxed or an original type.
         * @since 1.5
         */
        public static TypeDef unboxIfPossible(TypeDef typeDef) {
            if (typeDef instanceof ClassTypeDef classTypeDef) {
                return WRAPPER_TO_PRIMITIVE.getOrDefault(classTypeDef.getName(), typeDef);
            }
            return typeDef;
        }

        public String name() {
            return clazz.getName();
        }

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
            Class<?> primitiveType = ClassUtils.getPrimitiveType(name()).orElseThrow(() -> new IllegalStateException("Unrecognized primitive type: " + name()));
            return ClassTypeDef.of(
                ReflectionUtils.getWrapperType(primitiveType)
            );
        }

        /**
         * A primitive constant expression.
         *
         * @param value The constant value
         * @return The new instance
         * @since 1.3
         */
        @Experimental
        public ExpressionDef.Constant constant(Object value) {
            return new ExpressionDef.Constant(this, value);
        }

        /**
         * @return Is a whole number
         * @since 1.5
         */
        public boolean isWholeNumber() {
            return equals(BYTE) || equals(SHORT) || equals(INT) || equals(LONG);
        }

        /**
         * @return Is a float number
         * @since 1.5
         */
        public boolean isFloatNumber() {
            return equals(DOUBLE) || equals(FLOAT);
        }

        /**
         * @return Is a number
         * @since 1.5
         */
        public boolean isNumber() {
            return isWholeNumber() || isFloatNumber();
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

        @Override
        public TypeDef makeNullable() {
            return this;
        }
    }

    /**
     * The type variable ref.
     *
     * @param name     The variable name
     * @param bounds   The bounds
     * @param nullable The nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record TypeVariable(String name, List<TypeDef> bounds, boolean nullable) implements TypeDef {

        public TypeVariable(String name) {
            this(name, List.of());
        }

        public TypeVariable(String name, List<TypeDef> bounds) {
            this(name, bounds, false);
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

        @Override
        public TypeDef resolveTypeVariables(Map<String, TypeDef> resolvedTypeVariables) {
            return resolvedTypeVariables.getOrDefault(name, this);
        }

        @Override
        public TypeDef makeNullable() {
            return new TypeVariable(name, bounds, true);
        }

        @Override
        public boolean isNullable() {
            return nullable;
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

        public Array {
            if (componentType instanceof Array) {
                throw new IllegalArgumentException("Arrays can't have arrays");
            }
        }

        @Override
        public Array array() {
            return new Array(componentType, dimensions + 1, nullable);
        }

        @Override
        public Array array(int dimension) {
            return new Array(componentType, this.dimensions + dimension, nullable);
        }

        /**
         * Instantiate an array of this class.
         *
         * @param size The size of the array
         * @return The instantiate expression
         * @since 1.5
         */
        public ExpressionDef.NewArrayOfSize instantiate(int size) {
            return new ExpressionDef.NewArrayOfSize(this, size);
        }

        /**
         * Instantiate an array of this class.
         *
         * @param expressions The expressions
         * @return The instantiate expression
         * @since 1.5
         */
        public ExpressionDef.NewArrayInitialized instantiate(List<? extends ExpressionDef> expressions) {
            return new ExpressionDef.NewArrayInitialized(this, expressions);
        }

        /**
         * Instantiate an array of this class.
         *
         * @param expressions The items expressions
         * @return The instantiate expression
         * @since 1.5
         */
        public ExpressionDef instantiate(ExpressionDef... expressions) {
            return instantiate(List.of(expressions));
        }

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

    /**
     * A combined type interface for representing a Type with annotations.
     *
     * @author Elif Kurtay
     * @since 1.4
     */
    @Experimental
    sealed interface Annotated extends TypeDef permits ClassTypeDef.AnnotatedClassTypeDef, AnnotatedTypeDef {
    }

    /**
     * A combined type for representing a TypeDef with annotations.
     *
     * @param typeDef       The raw type definition
     * @param annotations   List of annotations to associate
     * @author Elif Kurtay
     * @since 1.4
     */
    @Experimental
    record AnnotatedTypeDef(TypeDef typeDef, List<AnnotationDef> annotations) implements Annotated {
    }
}
