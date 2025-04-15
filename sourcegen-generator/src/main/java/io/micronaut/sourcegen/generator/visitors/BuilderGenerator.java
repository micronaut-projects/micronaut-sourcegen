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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.VariableDef;

import java.util.List;
import java.util.function.Function;

/**
 * Exposes methods that can be used to create builders from other processors.
 */
@Experimental
public class BuilderGenerator {
    /**
     * Create a builder for the given arguments.
     * @param packageName            The package name
     * @param elementType            The element type
     * @param builderAnnotationValue The builder annotation value.
     * @param properties             The properties
     * @param constructorParameters  The constructor parameters
     * @return A class definition builder for the builder
     */
    public static @NonNull ClassDef.ClassDefBuilder createBuilder(
        String packageName,
        @NonNull ClassTypeDef elementType,
        @Nullable AnnotationValue<Builder> builderAnnotationValue,
        @NonNull List<PropertyElement> properties,
        @NonNull List<ParameterElement> constructorParameters) {
        Function<BuildContext, StatementDef> returnSelf = (context) -> context.aThis().returning();
        return createBuilder(packageName, elementType, builderAnnotationValue, properties, constructorParameters, returnSelf);
    }

    /**
     * Create a builder for the given arguments.
     *
     * @param packageName            The package name
     * @param elementType            The element type
     * @param builderAnnotationValue The builder annotation value.
     * @param properties             The properties
     * @param constructorParameters  The constructor parameters
     * @param buildReturnStatement   The return statement to use for building.
     * @return A class definition builder for the builder
     */
    public static ClassDef.ClassDefBuilder createBuilder(
        String packageName,
        ClassTypeDef elementType,
        AnnotationValue<Builder> builderAnnotationValue,
        List<PropertyElement> properties,
        List<ParameterElement> constructorParameters,
        Function<BuildContext, StatementDef> buildReturnStatement) {
        return BuilderAnnotationVisitor.createBuilder(
            packageName,
            elementType,
            builderAnnotationValue,
            properties,
            constructorParameters,
            buildReturnStatement
        );
    }

    /**
     * Invocation context for when a builder method is called.
     *
     * @param aThis A this
     * @param field The field being assigned
     */
    public record BuildContext(VariableDef.This aThis, FieldDef field) {
    }
}
