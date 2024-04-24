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
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.annotations.Wither;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The visitor that is generation a builder.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class WitherAnnotationVisitor implements TypeElementVisitor<Wither, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement recordElement, VisitorContext context) {
        if (!recordElement.isRecord()) {
            throw new ProcessingException(recordElement, "Only records can be annotated with @Wither");
        }
        String simpleName = recordElement.getSimpleName() + "Wither";
        String witherClassName = recordElement.getPackageName() + "." + simpleName;

        ClassTypeDef witherType = ClassTypeDef.of(witherClassName);
        ClassTypeDef recordType = ClassTypeDef.of(recordElement);

        InterfaceDef.InterfaceDefBuilder wither = InterfaceDef.builder(witherClassName)
            .addModifiers(Modifier.PUBLIC);

        List<PropertyElement> properties = recordElement.getBeanProperties();
        Map<String, MethodDef> propertyAccessMethods = CollectionUtils.newHashMap(properties.size());
        for (PropertyElement beanProperty : properties) {
            MethodDef methodDef = MethodDef.builder(beanProperty.getSimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.of(beanProperty.getType()))
                .build();
            wither.addMethod(
                methodDef
            );
            propertyAccessMethods.put(beanProperty.getName(), methodDef);
        }
        for (PropertyElement beanProperty : properties) {
            String propertyName = beanProperty.getSimpleName();
            TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
            wither.addMethod(
                MethodDef.builder("with" + NameUtils.capitalize(propertyName))
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(recordType)
                    .addParameter(propertyName, propertyTypeDef)
                    .addStatements(withPropertyMethodStatement(recordElement, recordType, witherType, propertyTypeDef, beanProperty, propertyAccessMethods))
                    .build()
            );
        }

        if (recordElement.hasStereotype(Builder.class)) {
            String builderSimpleName = recordElement.getSimpleName() + "Builder";
            String builderClassName = recordElement.getPackageName() + "." + builderSimpleName;
            ClassTypeDef builderType = ClassTypeDef.of(builderClassName);

            MethodDef.MethodDefBuilder withMethodBuilder = MethodDef.builder("with")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .returns(builderType);
            MethodDef withMethod = withMethodBuilder
                .addStatement(withMethodStatement(recordElement, builderType, witherType, propertyAccessMethods))
                .build();

            wither.addMethod(
                withMethod
            );

            ClassTypeDef.Parameterized consumableType = new ClassTypeDef.Parameterized(ClassTypeDef.of(Consumer.class), List.of(builderType));
            MethodDef.MethodDefBuilder withConsumerMethodBuilder = MethodDef.builder("with")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .addParameter("consumer", consumableType)
                .returns(recordType);

            VariableDef.Local builderLocalVariable = new VariableDef.Local("builder", builderType);

            withConsumerMethodBuilder.addStatement(
                new StatementDef.DefineAndAssign(
                    builderLocalVariable,
                    new ExpressionDef.CallInstanceMethod(
                        new VariableDef.This(witherType),
                        withMethod
                    )
                )
            );
            withConsumerMethodBuilder.addStatement(
                new ExpressionDef.CallInstanceMethod(
                    new VariableDef.MethodParameter("consumer", consumableType),
                    "accept", List.of(builderLocalVariable), TypeDef.VOID
                )
            );
            withConsumerMethodBuilder.addStatement(
                new StatementDef.Return(
                    new ExpressionDef.CallInstanceMethod(
                        builderLocalVariable,
                        "build", List.of(), recordType
                    )
                )
            );

            wither.addMethod(withConsumerMethodBuilder.build());

        }

        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }

        InterfaceDef witherDef = wither.build();
        context.visitGeneratedSourceFile(
            witherDef.getPackageName(),
            witherDef.getSimpleName(),
            recordElement
        ).ifPresent(sourceFile -> {
            try {
                sourceFile.write(
                    writer -> sourceGenerator.write(witherDef, writer)
                );
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<StatementDef> withPropertyMethodStatement(ClassElement recordElement,
                                                           ClassTypeDef recordType,
                                                           ClassTypeDef witherType,
                                                           TypeDef propertyTypeDef,
                                                           PropertyElement propertyElement,
                                                           Map<String, MethodDef> propertyAccessMethods) {

        List<ExpressionDef> values = new ArrayList<>();
        for (ParameterElement parameter : recordElement.getPrimaryConstructor().orElseThrow().getParameters()) {
            ExpressionDef exp;
            if (parameter.getName().equals(propertyElement.getName())) {
                exp = new VariableDef.MethodParameter(
                    propertyElement.getName(),
                    propertyTypeDef
                );
            } else {
                exp = new ExpressionDef.CallInstanceMethod(
                    new VariableDef.This(witherType),
                    propertyAccessMethods.get(parameter.getName())
                );
            }
            values.add(exp);
        }
        if (propertyElement.isNonNull()) {
            return List.of(
                new ExpressionDef.CallStaticMethod(
                    ClassTypeDef.of(Objects.class),
                    "requireNonNull",
                    List.of(
                        new VariableDef.MethodParameter(
                            propertyElement.getName(),
                            propertyTypeDef
                        )
                    ),
                    ClassTypeDef.of(Object.class)
                ),
                new StatementDef.Return(ExpressionDef.instantiate(recordType, values))
            );
        }
        return List.of(
            new StatementDef.Return(ExpressionDef.instantiate(recordType, values))
        );
    }

    private StatementDef withMethodStatement(ClassElement recordElement,
                                             ClassTypeDef builderType,
                                             ClassTypeDef witherType,
                                             Map<String, MethodDef> propertyAccessMethods) {

        List<ExpressionDef> values = new ArrayList<>();
        for (ParameterElement parameter : recordElement.getPrimaryConstructor().orElseThrow().getParameters()) {
            ExpressionDef exp = new ExpressionDef.CallInstanceMethod(
                new VariableDef.This(witherType),
                propertyAccessMethods.get(parameter.getName())
            );
            values.add(exp);
        }
        return new StatementDef.Return(ExpressionDef.instantiate(builderType, values));
    }

}
