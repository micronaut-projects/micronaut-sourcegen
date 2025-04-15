/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.RecordDef;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Provides methods for creating withers from other processors.
 */
@Experimental
public class WitherGenerator {
    /**
     * Builds a wither interface for the given arguments.
     * @param packageName The package name
     * @param recordType The record type
     * @param properties The properties
     * @param parameters The parameters
     * @param hasBuilder Is there a builder
     * @return The interface
     */
    public static InterfaceDef.InterfaceDefBuilder createWither(
        String packageName,
        ClassTypeDef recordType,
        List<PropertyElement> properties,
        List<ParameterElement> parameters, boolean hasBuilder) {
        String simpleName = recordType.getSimpleName() + "Wither";
        String witherClassName = packageName + "." + simpleName;
        InterfaceDef.InterfaceDefBuilder wither = InterfaceDef.builder(witherClassName)
            .addModifiers(Modifier.PUBLIC);

        WitherAnnotationVisitor.weaveWithMethodsInternal(recordType, properties, parameters, hasBuilder, wither);
        return wither;
    }

    /**
     * Add with methods to an existing record type being generated.
     *
     * @param recordType    The record type
     * @param recordBuilder The record builder
     * @param properties    The properties
     * @param parameters    The parameters
     * @param hasBuilder    Whether a builder is present
     */
    public static void weaveWithMethods(
        ClassTypeDef recordType,
        RecordDef.RecordDefBuilder recordBuilder,
        List<PropertyElement> properties,
        List<ParameterElement> parameters,
        boolean hasBuilder) {
        WitherAnnotationVisitor.weaveWithMethodsInternal(
            recordType,
            properties,
            parameters,
            hasBuilder,
            recordBuilder
        );
    }
}
