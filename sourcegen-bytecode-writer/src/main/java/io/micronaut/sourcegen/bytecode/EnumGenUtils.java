/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.ObjectDef;

/**
 * Compatibility facade for enum model synthesis.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
public class EnumGenUtils {

    /**
     * Creates the lowered class form of an enum definition.
     *
     * @param enumDef The enum definition
     * @return The lowered class definition
     */
    public static ClassDef toClassDef(EnumDef enumDef) {
        return io.micronaut.sourcegen.bytecode.core.EnumGenUtils.toClassDef(enumDef);
    }

    /**
     * Determines whether a field is a synthesized enum constant.
     *
     * @param objectDef The object definition
     * @param fieldDef The field
     * @return {@code true} if the field is an enum constant
     */
    public static boolean isEnumField(ObjectDef objectDef, FieldDef fieldDef) {
        return io.micronaut.sourcegen.bytecode.core.EnumGenUtils.isEnumField(objectDef, fieldDef);
    }

    /**
     * Determines whether a class is the lowered form of an enum.
     *
     * @param classDef The class definition
     * @return {@code true} if the class is an enum
     */
    public static boolean isEnum(ClassDef classDef) {
        return io.micronaut.sourcegen.bytecode.core.EnumGenUtils.isEnum(classDef);
    }
}
