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

import io.micronaut.core.naming.NameUtils;

/**
 * The interface defining the object type.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
public interface ObjectDef {

    String getName();

    default String getPackageName() {
        return NameUtils.getPackageName(getName());
    }

    default String getSimpleName() {
        return NameUtils.getSimpleName(getName());
    }

    default ClassTypeDef asTypeDef() {
        return ClassTypeDef.of(getName());
    }

}
