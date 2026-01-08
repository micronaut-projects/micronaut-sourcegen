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
package io.micronaut.sourcegen.custom.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import org.jspecify.annotations.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateAnnotatedType;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;

import java.util.List;

@Internal
public final class GenerateAnnotatedTypeVisitor implements TypeElementVisitor<GenerateAnnotatedType, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }

        var MIN_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.Min"))
            .addMember("value", 1).build();
        var MAX_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.Max"))
            .addMember("value", 10).build();
        var NOTNULL_ANN = AnnotationDef.builder(ClassTypeDef.of("jakarta.validation.constraints.NotNull"))
            .build();

        ClassTypeDef.AnnotatedClassTypeDef innerType = TypeDef.parameterized(
            ClassTypeDef.of(List.class),
            TypeDef.Primitive.INT.wrapperType().annotated(MIN_ANN, MAX_ANN))
            .annotated(NOTNULL_ANN);

        PropertyDef propertyDef = PropertyDef.builder("numbers").ofType(innerType).build();
        RecordDef recordDef = RecordDef.builder(element.getPackageName() + ".AnnotatedProperty")
                .addAnnotation(Introspected.class)
                .addProperty(propertyDef).build();

        sourceGenerator.write(recordDef, context, element);
    }
}
