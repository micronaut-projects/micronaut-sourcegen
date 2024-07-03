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
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The visitor that is generation a builder.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BuilderAnnotationVisitor implements TypeElementVisitor<Builder, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        try {
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
                    throw new IllegalStateException("Could not make the field nullable");
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

            builder.addMethod(MethodDef.constructor().build());
            builder.addMethod(MethodDef.constructor(builderType, properties.stream().map(p -> ParameterDef.of(p.getName(), TypeDef.of(p.getType()))).toList()));

            builder.addMethod(MethodDef.builder("builder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(builderType)
                .addStatement(builderType.instantiate().returning())
                .build());

            builder.addMethod(buildMethod(element, builderType));

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            ClassDef builderDef = builder.build();
            context.visitGeneratedSourceFile(
                builderDef.getPackageName(),
                builderDef.getSimpleName(),
                element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(builderDef, writer)
                    );
                } catch (Exception e) {
                    throw new ProcessingException(element, "Failed to generate a builder: " + e.getMessage(), e);
                }
            });
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ProcessingException(element, "Failed to generate a builder: " + e.getMessage(), e);
        }
    }

    static MethodDef buildMethod(ClassElement producedType, ClassTypeDef builderType) {
        ClassTypeDef buildType = ClassTypeDef.of(producedType);

        MethodElement constructorElement = producedType.getPrimaryConstructor()
            .filter(c -> !c.isPrivate())
            .or(producedType::getDefaultConstructor)
            .orElse(null);

        VariableDef.This thisVariable = new VariableDef.This(builderType);
        List<PropertyElement> beanProperties = new ArrayList<>(producedType.getBeanProperties());
        List<ExpressionDef> values = new ArrayList<>();
        if (constructorElement != null) {
            for (ParameterElement parameter : constructorElement.getParameters()) {
                beanProperties.removeIf(propertyElement -> propertyElement.getName().equals(parameter.getName()));
                // We need to convert it for the correct type in Kotlin
                values.add(
                    thisVariable
                        .field(
                            parameter.getName(),
                            TypeDef.of(parameter.getType()).makeNullable()
                        ).convert(TypeDef.of(parameter.getType()))
                );
            }
        }
        List<StatementDef> statementDefs;
        if (beanProperties.isEmpty()) {
            statementDefs = List.of(
                buildType.instantiate(values).returning()
            );
        } else {
            // Instantiate and set properties not assigned in the constructor
            StatementDef.DefineAndAssign defineAndAssign = buildType.instantiate(values).newLocal("instance");
            VariableDef.Local local = defineAndAssign.variable();
            statementDefs = new ArrayList<>();
            statementDefs.add(defineAndAssign);
            for (PropertyElement beanProperty : beanProperties) {
                Optional<MethodElement> writeMethod = beanProperty.getWriteMethod();
                if (writeMethod.isPresent()) {
                    String propertyName = beanProperty.getSimpleName();
                    TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
                    statementDefs.add(
                        local.invoke(writeMethod.get(), thisVariable.field(propertyName, propertyTypeDef))
                    );
                }
            }
            statementDefs.add(local.returning());
        }

        return MethodDef.builder("build")
            .addModifiers(Modifier.PUBLIC)
            .returns(buildType)
            .addStatements(statementDefs)
            .build();
    }

    private List<StatementDef> propertyBuilderMethod(TypeDef builderType, TypeDef fieldType, PropertyElement propertyElement) {
        return List.of(
            new VariableDef.This(builderType)
                .field(propertyElement.getName(), fieldType)
                .assign(new VariableDef.MethodParameter(
                    propertyElement.getName(),
                    TypeDef.of(propertyElement.getType())
                )),
            new VariableDef.This(builderType).returning()
        );
    }

}
