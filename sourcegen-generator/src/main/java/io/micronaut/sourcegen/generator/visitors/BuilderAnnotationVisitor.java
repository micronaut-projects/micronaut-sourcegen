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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The visitor that is generation a builder.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BuilderAnnotationVisitor implements TypeElementVisitor<Builder, Object> {

    private final List<ElementAndBuilder> builderClasses = new ArrayList<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String simpleName = element.getSimpleName() + "Builder";
        String builderClassName = element.getPackageName() + "." + simpleName;

        ClassTypeDef builderType = ClassTypeDef.of(builderClassName);

        ClassDef.ClassDefBuilder builder = ClassDef.builder(builderClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        List<PropertyElement> properties = element.getBeanProperties();
        for (PropertyElement beanProperty : properties) {
            String propertyName = beanProperty.getSimpleName();
            TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
            TypeDef fieldType = propertyTypeDef.makeNullable();
            if (!fieldType.isNullable()) {
                throw  new IllegalStateException();
            }
            FieldDef.FieldDefBuilder fieldDef = FieldDef.builder(propertyName)
                .ofType(fieldType)
                .addModifiers(Modifier.PRIVATE);
            try {
                beanProperty.stringValue(Bindable.class, "defaultValue").ifPresent(defaultValue ->
                    fieldDef.initializer(ExpressionDef.constant(beanProperty.getType(), fieldType, defaultValue))
                );
            } catch (IllegalArgumentException e) {
                throw new ProcessingException(beanProperty, "Invalid or unsupported default value specified: " + beanProperty.stringValue(Bindable.class, "defaultValue").orElse(null));
            }
            builder.addField(fieldDef
                .build()
            );
            builder.addMethod(
                MethodDef.builder(propertyName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderType)
                    .addParameter(propertyName, propertyTypeDef)
                    .addStatements(propertyBuilderMethod(builderType, fieldType, beanProperty))
                    .build()
            );
        }
        builder.addMethod(MethodDef.builder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderType)
            .addStatement(builderMethod(builderType))
            .build());

        element.getPrimaryConstructor().ifPresent(constructor -> {
            if (constructor.isPublic()) {
                ClassTypeDef buildType = ClassTypeDef.of(element);
                builder.addMethod(MethodDef.builder("build")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(buildType)
                    .addStatement(buildMethod(builderType, buildType, constructor))
                    .build()
                );
            }
        });

        builderClasses.add(new ElementAndBuilder(element, builder.build()));
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(visitorContext.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        for (ElementAndBuilder tuple : builderClasses) {
            ClassDef builderDef = tuple.builderDef();
            visitorContext.visitGeneratedSourceFile(
                builderDef.getPackageName(),
                builderDef.getSimpleName(),
                tuple.element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(builderDef, writer)
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        // Somehow finish is called twice
        builderClasses.clear();
    }

    private List<StatementDef> propertyBuilderMethod(TypeDef builderType, TypeDef fieldType, PropertyElement propertyElement) {
        return List.of(
            new StatementDef.Assign(
                new VariableDef.Field(
                    new VariableDef.This(builderType),
                    propertyElement.getName(),
                    fieldType
                ),
                new VariableDef.MethodParameter(
                    propertyElement.getName(),
                    TypeDef.of(propertyElement.getType())
                )
            ),
            new StatementDef.Return(
                new VariableDef.This(builderType)
            )
        );
    }

    private StatementDef builderMethod(ClassTypeDef builderType) {
        return new StatementDef.Return(
            ExpressionDef.instantiate(builderType, List.of())
        );
    }

    private StatementDef buildMethod(ClassTypeDef builderType,
                                     ClassTypeDef buildType,
                                     MethodElement constructorElement) {
        List<ExpressionDef> values = new ArrayList<>();
        for (ParameterElement parameter : constructorElement.getParameters()) {
            // We need to convert it for the correct type in Kotlin
            values.add(
                new ExpressionDef.Convert(
                    TypeDef.of(parameter.getType()),
                    new VariableDef.Field(
                        new VariableDef.This(builderType),
                        parameter.getName(),
                        TypeDef.of(parameter.getType()).makeNullable()
                    )
                )
            );
        }
        return new StatementDef.Return(ExpressionDef.instantiate(buildType, values));
    }

    private record ElementAndBuilder(ClassElement element, ClassDef builderDef) {
    }
}
