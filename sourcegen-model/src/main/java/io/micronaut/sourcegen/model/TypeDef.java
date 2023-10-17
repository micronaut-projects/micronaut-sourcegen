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

import java.util.Collections;
import java.util.List;

/**
 * The type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface TypeDef permits ClassTypeDef, TypeDef.Primitive, TypeDef.TypeVariable, TypeDef.Wildcard {

    TypeDef VOID = primitive("void");

    /**
     * Creates new primitive type.
     *
     * @param name The primitive type name
     * @return a new type definition
     */
    static TypeDef primitive(String name) {
        return new Primitive(name);
    }

    /**
     * Creates new primitive type.
     *
     * @param type The primitive type
     * @return a new type definition
     */
    static TypeDef primitive(Class<?> type) {
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
        return ClassTypeDef.of(type);
    }

    /**
     * Creates a new type.
     *
     * @param classElement The class element
     * @return a new type definition
     */
    static TypeDef of(ClassElement classElement) {
        if (classElement.isPrimitive()) {
            return primitive(classElement.getName());
        }
        return ClassTypeDef.of(classElement.getName());
    }

    /**
     * @return Is nullable type
     */
    default boolean isNullable() {
        return false;
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
    }
}
