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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Singular;
import io.micronaut.sourcegen.annotations.StagedBuilder;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.micronaut.sourcegen.generator.visitors.BuilderAnnotationVisitor.addAnnotations;
import static io.micronaut.sourcegen.generator.visitors.BuilderAnnotationVisitor.createAllPropertiesConstructor;
import static io.micronaut.sourcegen.generator.visitors.BuilderAnnotationVisitor.createBuildMethod;
import static io.micronaut.sourcegen.generator.visitors.BuilderAnnotationVisitor.createModifyPropertyMethod;

/**
 * The visitor that is generating a staged builder.
 *
 * <p>The builder assigns the required properties in stages: each of them gets a stage interface
 * declaring the single method that assigns it and returns the next stage, so that the build method is
 * only reachable once every required property has been assigned. The properties that are not required
 * are declared on the final stage and can be assigned in any order.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Internal
public final class StagedBuilderAnnotationVisitor implements TypeElementVisitor<StagedBuilder, Object> {

    private static final String BUILD_STAGE_SUFFIX = "BuildStage";
    private static final String FINAL_STAGE_NAME = "BuildFinal";
    private static final String BUILDER_SUFFIX = "StagedBuilder";
    private static final String BUILDER_IMPLEMENTATION_NAME = "Builder";

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
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(StagedBuilder.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (processed.contains(element.getName())) {
            return;
        }
        try {
            List<PropertyElement> properties = element.getBeanProperties();
            ParameterElement[] constructorElement = element.getPrimaryConstructor()
                .filter(c -> !c.isPrivate())
                .or(element::getDefaultConstructor)
                .map(MethodElement::getParameters).orElse(ParameterElement.ZERO_PARAMETER_ELEMENTS);

            ClassDef builderDef = createStagedBuilder(
                element.getPackageName(),
                ClassTypeDef.of(element),
                element.getAnnotation(StagedBuilder.class),
                properties,
                Arrays.asList(constructorElement)
            );

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            processed.add(element.getName());
            sourceGenerator.write(builderDef, context, element);
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            SourceGenerators.handleFatalException(
                element,
                StagedBuilder.class,
                e,
                (exception -> {
                    processed.remove(element.getName());
                    throw exception;
                })
            );
        }
    }

    /**
     * Create a staged builder for the given arguments.
     *
     * <p>The generated type only holds the stages and the builder method; the builder itself is the
     * nested {@value #BUILDER_IMPLEMENTATION_NAME} class that implements every stage. A class cannot
     * implement an interface nested in itself, so the two cannot be the same type.
     *
     * @param packageName            The package name
     * @param elementType            The element type
     * @param builderAnnotationValue The staged builder annotation value
     * @param properties             The properties
     * @param constructorParameters  The constructor parameters
     * @return The class definition of the generated type
     */
    static @NonNull ClassDef createStagedBuilder(
        String packageName,
        @NonNull ClassTypeDef elementType,
        @Nullable AnnotationValue<StagedBuilder> builderAnnotationValue,
        @NonNull List<PropertyElement> properties,
        @NonNull List<ParameterElement> constructorParameters) {

        String stagedBuilderClassName = stagedBuilderClassName(packageName, elementType);
        List<TypeDef.TypeVariable> typeArguments = typeArguments(elementType);
        ClassTypeDef builderType = nestedType(stagedBuilderClassName, BUILDER_IMPLEMENTATION_NAME, typeArguments);

        List<PropertyElement> requiredProperties = properties.stream().filter(StagedBuilderAnnotationVisitor::isRequired).toList();
        List<PropertyElement> remainingProperties = properties.stream().filter(p -> !isRequired(p)).toList();

        ClassTypeDef finalStageType = nestedType(stagedBuilderClassName, FINAL_STAGE_NAME, typeArguments);
        List<ClassTypeDef> requiredStageTypes = requiredProperties.stream()
            .map(property -> nestedType(stagedBuilderClassName, stageName(property), typeArguments))
            .toList();

        ClassDefBuilder builder = ClassDef.builder(BUILDER_IMPLEMENTATION_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        for (TypeDef.TypeVariable typeArgument : typeArguments) {
            builder.addTypeVariable(typeArgument);
        }
        if (builderAnnotationValue != null) {
            addAnnotations(builder, builderAnnotationValue);
        }
        requiredStageTypes.forEach(builder::addSuperinterface);
        builder.addSuperinterface(finalStageType);

        List<InterfaceDef> stages = new ArrayList<>(requiredStageTypes.size() + 1);
        for (int i = 0; i < requiredProperties.size(); i++) {
            ClassTypeDef nextStageType = i + 1 < requiredStageTypes.size() ? requiredStageTypes.get(i + 1) : finalStageType;
            List<MethodDef> methods = createModifyPropertyMethod(
                builder,
                builderType,
                nextStageType,
                true,
                requiredProperties.get(i),
                buildContext -> buildContext.aThis().returning()
            );
            stages.add(createStage(stageName(requiredProperties.get(i)), typeArguments, methods));
        }

        List<MethodDef> finalStageMethods = new ArrayList<>();
        for (PropertyElement property : remainingProperties) {
            finalStageMethods.addAll(createModifyPropertyMethod(
                builder,
                builderType,
                finalStageType,
                true,
                property,
                buildContext -> buildContext.aThis().returning()
            ));
        }
        MethodDef buildMethod = createBuildMethod(elementType, properties, constructorParameters, true);
        builder.addMethod(buildMethod);
        finalStageMethods.add(buildMethod);
        stages.add(createStage(FINAL_STAGE_NAME, typeArguments, finalStageMethods));

        builder.addMethod(MethodDef.constructor().build());
        if (!properties.isEmpty()) {
            builder.addMethod(createAllPropertiesConstructor(properties));
        }

        ClassDefBuilder stagedBuilder = ClassDef.builder(stagedBuilderClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(createBuilderMethod(
                builderType,
                requiredStageTypes.isEmpty() ? finalStageType : requiredStageTypes.get(0)
            ));
        stages.forEach(stagedBuilder::addInnerType);
        stagedBuilder.addInnerType(builder.build());
        return stagedBuilder.build();
    }

    /**
     * A property is required unless the builder can leave it unassigned: a nullable property keeps its
     * null, a property with a default value keeps that value and a {@link Singular} property
     * accumulates into an empty collection.
     *
     * @param property The property
     * @return True if the property has to be assigned before the instance can be built
     */
    private static boolean isRequired(PropertyElement property) {
        return !property.hasAnnotation(Singular.class)
            && !property.isNullable()
            && property.stringValue(Bindable.class, "defaultValue").isEmpty();
    }

    private static String stagedBuilderClassName(String packageName, ClassTypeDef elementType) {
        String localBinaryName = elementType.getName().startsWith(packageName + ".")
            ? elementType.getName().substring(packageName.isEmpty() ? 0 : packageName.length() + 1)
            : elementType.getName();
        String baseName = elementType.isInner() ? localBinaryName.replace("$", "") : elementType.getSimpleName();
        String builderSimpleName = baseName + BUILDER_SUFFIX;
        return packageName.isEmpty() ? builderSimpleName : packageName + "." + builderSimpleName;
    }

    private static List<TypeDef.TypeVariable> typeArguments(ClassTypeDef elementType) {
        if (elementType instanceof ClassTypeDef.Parameterized parameterizedType) {
            return parameterizedType.typeArguments()
                .stream()
                .filter(TypeDef.TypeVariable.class::isInstance)
                .map(TypeDef.TypeVariable.class::cast)
                .toList();
        }
        return List.of();
    }

    private static ClassTypeDef withTypeArguments(ClassTypeDef type, List<TypeDef.TypeVariable> typeArguments) {
        if (typeArguments.isEmpty()) {
            return type;
        }
        return TypeDef.parameterized(type, typeArguments.toArray(TypeDef[]::new));
    }

    private static String stageName(PropertyElement property) {
        return StringUtils.capitalize(property.getSimpleName()) + BUILD_STAGE_SUFFIX;
    }

    /**
     * The type of one of the generated nested types. The stages and the builder are nested, so that the
     * stages of one builder cannot be confused with the identically named stages of another.
     *
     * @param stagedBuilderClassName The name of the generated type they are nested in
     * @param nestedName             The simple name of the nested type
     * @param typeArguments          The type arguments of the built type
     * @return The type of the nested type
     */
    private static ClassTypeDef nestedType(String stagedBuilderClassName, String nestedName, List<TypeDef.TypeVariable> typeArguments) {
        return withTypeArguments(ClassTypeDef.of(stagedBuilderClassName + "$" + nestedName, true), typeArguments);
    }

    /**
     * Create the stage interface declaring the given methods of the builder.
     *
     * @param stageName     The simple name of the stage
     * @param typeArguments The type arguments of the builder, which a nested interface cannot inherit
     *                      and has to redeclare
     * @param methods       The methods of the builder the stage exposes
     * @return The stage definition
     */
    private static InterfaceDef createStage(String stageName,
                                            List<TypeDef.TypeVariable> typeArguments,
                                            List<MethodDef> methods) {
        InterfaceDef.InterfaceDefBuilder stage = InterfaceDef.builder(stageName)
            .addModifiers(Modifier.PUBLIC);
        for (TypeDef.TypeVariable typeArgument : typeArguments) {
            stage.addTypeVariable(typeArgument);
        }
        for (MethodDef method : methods) {
            stage.addMethod(
                MethodDef.builder(method.getName())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addParameters(method.getParameters())
                    .returns(method.getReturnType())
                    .build()
            );
        }
        return stage.build();
    }

    private static MethodDef createBuilderMethod(ClassTypeDef builderType, ClassTypeDef firstStageType) {
        ClassTypeDef erasure = builderType instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : builderType;
        MethodDef.MethodDefBuilder methodBuilder = MethodDef.builder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addStatement(erasure.instantiate().returning());
        if (firstStageType instanceof ClassTypeDef.Parameterized parameterized) {
            List<TypeDef> resolvedTypeDefs = new ArrayList<>();
            for (TypeDef typeDef : parameterized.typeArguments()) {
                if (typeDef instanceof TypeDef.TypeVariable variable) {
                    TypeDef.TypeVariable newTypeDef = new TypeDef.TypeVariable(
                        variable.name() + "S",
                        variable.bounds(),
                        variable.nullable()
                    );
                    resolvedTypeDefs.add(newTypeDef);
                    methodBuilder.addTypeVariable(newTypeDef);
                }
            }
            methodBuilder.returns(TypeDef.parameterized(
                parameterized.rawType(),
                resolvedTypeDefs.toArray(TypeDef[]::new)
            ));
        } else {
            methodBuilder.returns(firstStageType);
        }
        return methodBuilder.build();
    }

}
