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
import io.micronaut.sourcegen.model.ObjectDefBuilder;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import java.util.Arrays;
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
            List<PropertyElement> properties = recordElement.getBeanProperties();
            List<ParameterElement> parameters = Arrays.asList(recordElement.getPrimaryConstructor().orElseThrow().getParameters());
            boolean hasBuilder = recordElement.hasStereotype(Builder.class);
            ClassTypeDef recordType = ClassTypeDef.of(recordElement);

            InterfaceDef.InterfaceDefBuilder wither = createWither(recordElement.getPackageName(), recordType, properties, parameters, hasBuilder);

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            InterfaceDef witherDef = wither.build();

            processed.add(recordElement.getName());
            sourceGenerator.write(witherDef, context, recordElement);
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

    /**
     * Builds a wither interface for the given arguments.
     * @param packageName The package name
     * @param recordType The record type
     * @param properties The properties
     * @param parameters The parameters
     * @param hasBuilder Is there a builder
     * @return The interface
     */
    static InterfaceDef.InterfaceDefBuilder createWither(
        String packageName,
        ClassTypeDef recordType,
        List<PropertyElement> properties,
        List<ParameterElement> parameters, boolean hasBuilder) {
        String localBinaryName = recordType.getName().startsWith(packageName + ".")
            ? recordType.getName().substring(packageName.isEmpty() ? 0 : packageName.length() + 1)
            : recordType.getName();
        String witherSimpleName = (recordType.isInner() ? localBinaryName : recordType.getSimpleName()) + "Wither";
        String witherClassName = packageName + "." + witherSimpleName;
        InterfaceDef.InterfaceDefBuilder wither = InterfaceDef.builder(witherClassName)
            .addModifiers(Modifier.PUBLIC);
        if (recordType instanceof ClassTypeDef.Parameterized parameterized) {
            List<TypeDef> typeDefs = parameterized.typeArguments();
            for (TypeDef typeDef : typeDefs) {
                if (typeDef instanceof TypeDef.TypeVariable variable) {
                    wither.addTypeVariable(
                        variable
                    );
                }
            }
        }
        weaveWithMethodsInternal(recordType, properties, parameters, hasBuilder, wither);
        return wither;
    }

    static void weaveWithMethodsInternal(
        ClassTypeDef recordType,
        List<PropertyElement> properties,
        List<ParameterElement> parameters,
        boolean hasBuilder,
        ObjectDefBuilder<?> wither) {
        Map<String, MethodDef> propertyAccessMethods = CollectionUtils.newHashMap(properties.size());
        for (PropertyElement beanProperty : properties) {
            MethodDef methodDef;
            if (wither instanceof InterfaceDef.InterfaceDefBuilder) {
                methodDef = MethodDef.builder(beanProperty.getSimpleName())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(TypeDef.of(beanProperty.getType()))
                    .build();
                wither.addMethod(
                    methodDef
                );
            } else {
                methodDef = MethodDef.builder(beanProperty.getSimpleName())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeDef.of(beanProperty.getType()))
                    .build();
            }
            propertyAccessMethods.put(beanProperty.getName(), methodDef);
        }
        for (PropertyElement beanProperty : properties) {
            wither.addMethod(
                withMethod(wither, parameters, beanProperty, recordType, propertyAccessMethods)
            );
        }

        if (hasBuilder) {
            String fullName = recordType.getName();
            String pkg = fullName.contains(".") ? fullName.substring(0, fullName.lastIndexOf('.')) : "";
            String localBinaryName2 = fullName.startsWith(pkg + ".")
                ? fullName.substring(pkg.isEmpty() ? 0 : pkg.length() + 1)
                : fullName;
            String builderSimpleName = (recordType.isInner() ? localBinaryName2 : recordType.getSimpleName()) + "Builder";
            String builderClassName = pkg.isEmpty() ? builderSimpleName : pkg + "." + builderSimpleName;
            ClassTypeDef builderType;

            if (recordType instanceof ClassTypeDef.Parameterized parameterized) {
                builderType = TypeDef.parameterized(
                    ClassTypeDef.of(builderClassName),
                    parameterized.typeArguments()
                );
            } else {
                builderType = ClassTypeDef.of(builderClassName);
            }

            MethodDef withMethod = createWithMethod(wither, parameters, builderType, propertyAccessMethods);
            wither.addMethod(withMethod);
            MethodDef withConsumer = createWithConsumerMethod(wither, recordType, builderType, withMethod);
            wither.addMethod(withConsumer);
        }
    }

    private static MethodDef createWithConsumerMethod(ObjectDefBuilder<?> wither, ClassTypeDef recordType, ClassTypeDef builderType, MethodDef withMethod) {
        MethodDef.MethodDefBuilder methodDefBuilder = MethodDef.builder("with");
        if (wither instanceof InterfaceDef.InterfaceDefBuilder) {
            methodDefBuilder.addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);
        } else {
            methodDefBuilder.addModifiers(Modifier.PUBLIC);
        }
        return methodDefBuilder
            .addParameter("consumer", TypeDef.parameterized(ClassTypeDef.of(Consumer.class), builderType))
            .returns(recordType).build((self, parameterDefs) ->
                self.invoke(withMethod).newLocal("builder", builderVar ->
                    parameterDefs.get(0).invoke("accept", TypeDef.VOID, builderVar)
                        .after(
                            builderVar.invoke("build", recordType).returning()
                        ))
            );
    }

    private static MethodDef createWithMethod(ObjectDefBuilder<?> wither, List<ParameterElement> parameters, ClassTypeDef builderType, Map<String, MethodDef> propertyAccessMethods) {
        MethodDef.MethodDefBuilder methodDefBuilder = MethodDef.builder("with");
        if (wither instanceof InterfaceDef.InterfaceDefBuilder) {
            methodDefBuilder.addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);
        } else {
            methodDefBuilder.addModifiers(Modifier.PUBLIC);
        }
        return methodDefBuilder
            .returns(builderType)
            .build((self, parameterDefs) -> builderType.instantiate(
                parameters.stream()
                    .<ExpressionDef>map(parameter -> self.invoke(propertyAccessMethods.get(parameter.getName())))
                    .toList()
            ).returning());
    }

    private static MethodDef withMethod(ObjectDefBuilder<?> wither, List<ParameterElement> parameters, PropertyElement beanProperty, ClassTypeDef recordType, Map<String, MethodDef> propertyAccessMethods) {
        MethodDef.MethodDefBuilder methodBuilder = MethodDef.builder("with" + NameUtils.capitalize(beanProperty.getSimpleName()));
        if (wither instanceof InterfaceDef.InterfaceDefBuilder) {
            methodBuilder.addModifiers(Modifier.PUBLIC, Modifier.DEFAULT);
        } else {
            methodBuilder.addModifiers(Modifier.PUBLIC);
        }
        return methodBuilder
            .returns(recordType)
            .addParameter(beanProperty.getSimpleName(), TypeDef.of(beanProperty.getType()))
            .build((self, parameterDefs) -> {
                List<ExpressionDef> values = new ArrayList<>();
                for (ParameterElement parameter : parameters) {
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
