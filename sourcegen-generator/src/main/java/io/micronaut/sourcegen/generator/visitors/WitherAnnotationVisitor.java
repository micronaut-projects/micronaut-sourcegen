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

import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Modifier;
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

    private final Set<String> processed = new HashSet<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        processed.clear();
    }

    @Override
    public void visitClass(ClassElement recordElement, VisitorContext context) {
        if (processed.contains(recordElement.getName())) {
            return;
        }
        try {
            if (!recordElement.isRecord()) {
                throw new ProcessingException(recordElement, "Only records can be annotated with @Wither");
            }
            String simpleName = recordElement.getSimpleName() + "Wither";
            String witherClassName = recordElement.getPackageName() + "." + simpleName;

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
                wither.addMethod(
                    withMethod(recordElement, beanProperty, recordType, propertyAccessMethods)
                );
            }

            if (recordElement.hasStereotype(Builder.class)) {
                String builderSimpleName = recordElement.getSimpleName() + "Builder";
                String builderClassName = recordElement.getPackageName() + "." + builderSimpleName;
                ClassTypeDef builderType = ClassTypeDef.of(builderClassName);

                MethodDef withMethod = createWithMethod(recordElement, builderType, propertyAccessMethods);
                wither.addMethod(withMethod);
                wither.addMethod(createWithConsumerMethod(recordType, builderType, withMethod));
            }

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            InterfaceDef witherDef = wither.build();
            processed.add(recordElement.getName());
            context.visitGeneratedSourceFile(
                witherDef.getPackageName(),
                witherDef.getSimpleName(),
                recordElement
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(witherDef, writer)
                    );
                } catch (Exception e) {
                    throw new ProcessingException(recordElement, "Failed to generate a wither: " + e.getMessage(), e);
                }
            });
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            SourceGenerators.handleFatalException(
                recordElement,
                Wither.class,
                e,
                (exception -> {
                    processed.remove(recordElement.getName());
                    throw exception;
                })
            );
        }
    }

    private MethodDef createWithConsumerMethod(ClassTypeDef recordType, ClassTypeDef builderType, MethodDef withMethod) {
        ClassTypeDef.Parameterized consumableType = new ClassTypeDef.Parameterized(ClassTypeDef.of(Consumer.class), List.of(builderType));
        return MethodDef.builder("with")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter("consumer", consumableType)
            .returns(recordType).build((self, parameterDefs) ->
                self.invoke(withMethod).newLocal("builder", builderVar ->
                    parameterDefs.get(0).invoke("accept", TypeDef.VOID, builderVar)
                        .after(
                            builderVar.invoke("build", recordType).returning()
                        ))
            );
    }

    private MethodDef createWithMethod(ClassElement recordElement, ClassTypeDef builderType, Map<String, MethodDef> propertyAccessMethods) {
        return MethodDef.builder("with")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(builderType)
            .build((self, parameterDefs) -> {
                List<ExpressionDef> expressions = new ArrayList<>();
                for (ParameterElement parameter : recordElement.getPrimaryConstructor().orElseThrow().getParameters()) {
                    expressions.add(
                        self.invoke(propertyAccessMethods.get(parameter.getName()))
                    );
                }
                return builderType.instantiate(expressions).returning();
            });
    }

    private MethodDef withMethod(ClassElement recordElement, PropertyElement beanProperty, ClassTypeDef recordType, Map<String, MethodDef> propertyAccessMethods) {
        String propertyName = beanProperty.getSimpleName();
        TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
        return MethodDef.builder("with" + NameUtils.capitalize(propertyName))
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(recordType)
            .addParameter(propertyName, propertyTypeDef)
            .build((self, parameterDefs) -> {
                List<ExpressionDef> values = new ArrayList<>();
                for (ParameterElement parameter : recordElement.getPrimaryConstructor().orElseThrow().getParameters()) {
                    ExpressionDef exp;
                    if (parameter.getName().equals(beanProperty.getName())) {
                        exp = parameterDefs.get(0);
                    } else {
                        exp = self.invoke(propertyAccessMethods.get(parameter.getName()));
                    }
                    values.add(exp);
                }
                if (beanProperty.isNonNull()) {
                    return StatementDef.multi(
                        ClassTypeDef.of(Objects.class).invokeStatic(
                            "requireNonNull",
                            ClassTypeDef.OBJECT,
                            parameterDefs.get(0)
                        ),
                        recordType.instantiate(values).returning()
                    );
                }
                return recordType.instantiate(values).returning();
            });
    }

}
