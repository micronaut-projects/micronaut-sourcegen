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

/**
 * The type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface TypeDef {

    /**
     * @return The type name
     */
    String getTypeName();

    /**
     * @return Is nullable type
     */
    boolean isNullable();

    /**
     * @return A new nullable type
     */
    TypeDef makeNullable();

    /**
     * @return The simple name
     */
    default String getSimpleName() {
        return NameUtils.getSimpleName(getTypeName());
    }

    /**
     * @return The package name
     */
    default String getPackageName() {
        return NameUtils.getPackageName(getTypeName());
    }

    /**
     * Create a new type definition.
     *
     * @param type The class
     * @return type definition
     */
    static TypeDef of(Class<?> type) {
        return new ClassTypeDef(type, false);
    }

    /**
     * Create a new type definition.
     *
     * @param className The class name
     * @return type definition
     */
    static TypeDef of(String className) {
        return new ClassNameTypeDef(className, false);
    }

    /**
     * Create a new type definition.
     *
     * @param classElement The class element
     * @return type definition
     */
    static TypeDef of(ClassElement classElement) {
        return new ClassElementTypeDef(classElement, false);
    }

    /**
     * Create a new type definition.
     *
     * @param classDef The class definition
     * @return type definition
     */
    static TypeDef of(ClassDef classDef) {
        return new ClassDefTypeDef(classDef, false);
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
    record ClassTypeDef(Class<?> type, boolean nullable) implements TypeDef {

        @Override
        public String getTypeName() {
            return type.getTypeName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public TypeDef makeNullable() {
            return new ClassTypeDef(type, true);
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
    record ClassNameTypeDef(String className, boolean nullable) implements TypeDef {

        @Override
        public String getTypeName() {
            return className;
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public TypeDef makeNullable() {
            return new ClassNameTypeDef(className, true);
        }

    }

    /**
     * The class element type.
     *
     * @param classElement The class element
     * @param nullable     Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record ClassElementTypeDef(ClassElement classElement, boolean nullable) implements TypeDef {

        @Override
        public String getTypeName() {
            return classElement.getName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public TypeDef makeNullable() {
            return new ClassElementTypeDef(classElement, true);
        }

    }

    /**
     * The class definition type.
     *
     * @param classDef The class definition
     * @param nullable Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record ClassDefTypeDef(ClassDef classDef, boolean nullable) implements TypeDef {

        @Override
        public String getTypeName() {
            return classDef.getName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public TypeDef makeNullable() {
            return new ClassDefTypeDef(classDef, true);
        }

    }
}
