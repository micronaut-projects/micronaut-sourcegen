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
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.ast.ClassElement;

import java.util.List;

/**
 * The class type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface ClassTypeDef extends TypeDef {

    /**
     * @return The type name
     */
    String getName();

    /**
     * @return The simple name
     */
    default String getSimpleName() {
        return NameUtils.getSimpleName(getName());
    }

    /**
     * @return The package name
     */
    default String getPackageName() {
        return NameUtils.getPackageName(getName());
    }

    @Override
    ClassTypeDef makeNullable();

    /**
     * Create a new type definition.
     *
     * @param type The class
     * @return type definition
     */
    static ClassTypeDef of(Class<?> type) {
        if (type.isPrimitive()) {
            throw new IllegalStateException("Primitive classes cannot be of type: " + ClassTypeDef.class.getName());
        }
        return new JavaClass(type, false);
    }

    /**
     * Create a new type definition.
     *
     * @param className The class name
     * @return type definition
     */
    static ClassTypeDef of(String className) {
        return new ClassName(className, false);
    }

    /**
     * Create a new type definition.
     *
     * @param classElement The class element
     * @return type definition
     */
    static ClassTypeDef of(ClassElement classElement) {
        if (classElement.isPrimitive()) {
            throw new IllegalStateException("Primitive classes cannot be of type: " + ClassTypeDef.class.getName());
        }
        return new ClassName(classElement.getName().replace("$", "."), classElement.isNullable());
    }

    /**
     * Create a new type definition.
     *
     * @param classDef The class definition
     * @return type definition
     */
    static ClassTypeDef of(ClassDef classDef) {
        return new ClassName(classDef.getName(), false);
    }

    /**
     * The class type.
     *
     * @param type     The type
     * @param nullable Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record JavaClass(Class<?> type, boolean nullable) implements ClassTypeDef {

        @Override
        public String getName() {
            return type.getCanonicalName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new JavaClass(type, true);
        }
    }

    /**
     * The class name type.
     *
     * @param className The class name
     * @param nullable  Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record ClassName(String className, boolean nullable) implements ClassTypeDef {

        @Override
        public String getName() {
            return className;
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new ClassName(className, true);
        }

    }

    /**
     * The parameterized type definition.
     *
     * @param rawType       The raw type definition
     * @param typeArguments The type arguments
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Parameterized(ClassTypeDef rawType, List<TypeDef> typeArguments) implements ClassTypeDef {
        @Override
        public String getName() {
            return rawType.getName();
        }

        @Override
        public boolean isNullable() {
            return rawType.isNullable();
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new Parameterized(rawType.makeNullable(), typeArguments);
        }
    }

}
