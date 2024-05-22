/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.RestResource;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.*;

/**
 * Generates a {@link Controller} for each class annotated with {@link RestResource}.
 *
 * @author Sergio del Amo
 * @since 1.1.0
 */
@Internal
public final class RestResourceAnnotationVisitor implements TypeElementVisitor<RestResource, Object> {
    private static final String CONTROLLER = "Controller";
    private static final String METHOD_NAME_FIND_ALL = "findAll";
    private static final String METHOD_NAME_FIND_BY_ID = "findById";
    private static final String METHOD_NAME_DELETE_BY_ID = "deleteById";
    private static final String  IS_AUTHENTICATED = "isAuthenticated()";
    private static final String METHOD_NAME_UPDATE = "update";
    private static final String METHOD_NAME_SAVE = "save";
    private static final String MEMBER_VALUE = "value";
    private static final String MEMBER_REPOSITORY = "repository";
    private static final String MEMBER_NAME = "name";
    private static final String MEMBER_ROLES_ALLOWED = "rolesAllowed";
    private static final String MEMBER_URI = "uri";
    private static final String FIELD_REPOSITORY = "repository";
    private static final String ID_PATH_VARIABLE = "/{id}";
    private static final String PARAMETER_ID = "id";
    private static final String PARAMETER_ENTITY = "entity";
    private static final String MEMBER_DELETE = "delete";
    private static final String MEMBER_LIST = "list";
    private static final String MEMBER_SHOW = "show";
    private static final String MEMBER_UPDATE = "update";
    private static final String MEMBER_SAVE = "save";
    private static final String SLASH = "/";
    private static final String S = "s";

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

        Optional<AnnotationValue<RestResource>> restResourceAnnotationOptional = element.findAnnotation(RestResource.class);
        if (restResourceAnnotationOptional.isEmpty()) {
            return;
        }
        AnnotationValue<RestResource> restResourceAnnotation = restResourceAnnotationOptional.get();

        Optional<AnnotationClassValue<?>> repositoryAnnotationClassValueOptional = restResourceAnnotation.annotationClassValue(MEMBER_REPOSITORY);
        if (repositoryAnnotationClassValueOptional.isEmpty()) {
            return;
        }

        AnnotationClassValue<?> repositoryAnnotationClassValue = repositoryAnnotationClassValueOptional.get();
        Optional<ClassElement> repositoryClassElementOptional = context.getClassElement(repositoryAnnotationClassValue.getName());
       if (repositoryClassElementOptional.isEmpty()) {
           return;
       }
        ClassElement repositoryClassElement = repositoryClassElementOptional.get();
        ClassElement entityClassElement = repositoryClassElement.getAllTypeArguments().get("io.micronaut.data.repository.CrudRepository").get("E");
        ClassElement idClassElement = repositoryClassElement.getAllTypeArguments().get("io.micronaut.data.repository.CrudRepository").get("ID");
        String resourceName = resourceName(restResourceAnnotation, entityClassElement);
        String controllerBaseUri = controllerBaseUri(restResourceAnnotation, resourceName);

        String builderClassName = element.getPackageName() + "." + resourceName + CONTROLLER;
        ClassDef.ClassDefBuilder controllerBuilder = ClassDef.builder(builderClassName)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Controller.class)).addMember(MEMBER_VALUE, controllerBaseUri).build());
        List<String> rolesAllowed = restResourceAnnotation.get(MEMBER_ROLES_ALLOWED, Argument.listOf(String.class))
                .orElseGet(() -> List.of(IS_AUTHENTICATED));
        if (rolesAllowed.size() == 1) {
            controllerBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of(RolesAllowed.class)).addMember(MEMBER_VALUE, rolesAllowed.iterator().next()).build());
        } else {
            controllerBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of(RolesAllowed.class)).addMember(MEMBER_VALUE, rolesAllowed).build());
        }

        FieldDef repository = FieldDef.builder(FIELD_REPOSITORY)
                .ofType(TypeDef.of(repositoryClassElement))
                .addAnnotation(ClassTypeDef.of(Inject.class))
                .build();
        controllerBuilder.addField(repository);
        ClassDef controllerDef = controllerBuilder.build();
        ClassElement controllerClassElement = ClassElement.of(controllerDef.getClass());

        if (restResourceAnnotation.get(MEMBER_LIST, Argument.of(Boolean.class)).orElse(false)) {
            controllerBuilder.addMethod(findAllMethod(controllerClassElement, repositoryClassElement, entityClassElement));
        }
        if (restResourceAnnotation.get(MEMBER_SHOW, Argument.of(Boolean.class)).orElse(false)) {
            controllerBuilder.addMethod(findByIdMethod(controllerClassElement, repositoryClassElement, entityClassElement, idClassElement));
        }
        if (restResourceAnnotation.get(MEMBER_DELETE, Argument.of(Boolean.class)).orElse(false)) {
            controllerBuilder.addMethod(deleteByIdMethod(controllerClassElement, repositoryClassElement, idClassElement));
        }
        if (restResourceAnnotation.get(MEMBER_SAVE, Argument.of(Boolean.class)).orElse(false)) {
            controllerBuilder.addMethod(saveMethod(controllerClassElement, repositoryClassElement, entityClassElement));
        }
        if (restResourceAnnotation.get(MEMBER_UPDATE, Argument.of(Boolean.class)).orElse(false)) {
            controllerBuilder.addMethod(updateMethod(controllerClassElement, repositoryClassElement, entityClassElement));
        }
        context.visitGeneratedSourceFile(controllerDef.getPackageName(), controllerDef.getSimpleName(), element)
                .ifPresent(generatedFile -> {
                    try {
                        generatedFile.write(writer -> sourceGenerator.write(controllerDef, writer));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static String resourceName(AnnotationValue<RestResource> restResourceAnnotation, ClassElement entityClassElement) {
        Optional<String> resourceNameOptional = restResourceAnnotation.stringValue(MEMBER_NAME);
        return resourceNameOptional
                .orElseGet(entityClassElement::getSimpleName);

    }
    private static String controllerBaseUri(AnnotationValue<RestResource> restResourceAnnotation, String resourceName) {
        Optional<String> uriOptional = restResourceAnnotation.stringValue(MEMBER_URI);
        if (uriOptional.isPresent()) {
            return uriOptional.get();
        }
        String controllerBaseUri = resourceName.toLowerCase();
        if (!controllerBaseUri.startsWith(SLASH)) {
            controllerBaseUri = SLASH + controllerBaseUri;
        }
        if (!resourceName.endsWith(S)) {
            controllerBaseUri += S;
        }
        return controllerBaseUri;
    }

    private static MethodDef findAllMethod(ClassElement controllerClassElement,
                                           ClassElement repositoryClassElement,
                                           ClassElement entityClassElement) {
        return MethodDef.builder(METHOD_NAME_FIND_ALL)
                .addAnnotation(ClassTypeDef.of(Get.class))
                .returns(new ClassTypeDef.Parameterized(ClassTypeDef.of(Iterable.class), Collections.singletonList(TypeDef.of(entityClassElement))))
                .addStatement(new StatementDef.Return(
                        ExpressionDef.invoke(
                                new VariableDef.Field(new VariableDef.This(TypeDef.of(controllerClassElement)), FIELD_REPOSITORY,
                                        TypeDef.of(repositoryClassElement)),
                                METHOD_NAME_FIND_ALL,
                                List.of(),
                                TypeDef.of(String.class)
                        )
                ))
                .build();
    }

    private static MethodDef findByIdMethod(ClassElement controllerClassElement,
                                            ClassElement repositoryClassElement,
                                     ClassElement entityClassElement,
                                     ClassElement idClassElement) {
        TypeDef methodParameterTypeDef = TypeDef.of(idClassElement);
        return MethodDef.builder(METHOD_NAME_FIND_BY_ID)
                .addParameter(ParameterDef.of(PARAMETER_ID, methodParameterTypeDef))
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Get.class)).addMember(MEMBER_VALUE,  ID_PATH_VARIABLE).build())
                .returns(new ClassTypeDef.Parameterized(ClassTypeDef.of(Optional.class), Collections.singletonList(TypeDef.of(entityClassElement))))
                .addStatement(new StatementDef.Return(
                        ExpressionDef.invoke(
                                new VariableDef.Field(new VariableDef.This(TypeDef.of(controllerClassElement)), FIELD_REPOSITORY,
                                        TypeDef.of(repositoryClassElement)),
                                METHOD_NAME_FIND_BY_ID,
                                List.of(new VariableDef.MethodParameter(PARAMETER_ID, methodParameterTypeDef)),
                                TypeDef.of(String.class)
                        )
                ))
                .build();
    }

    private static MethodDef deleteByIdMethod(ClassElement controllerClassElement,
                                              ClassElement repositoryClassElement,
                                              ClassElement idClassElement) {
        TypeDef methodParameterTypeDef = TypeDef.of(idClassElement);
        return MethodDef.builder(METHOD_NAME_DELETE_BY_ID)
                .addParameter(ParameterDef.of(PARAMETER_ID, methodParameterTypeDef))
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Delete.class)).addMember(MEMBER_VALUE,  ID_PATH_VARIABLE).build())
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Status.class)).addMember(MEMBER_VALUE, HttpStatus.NO_CONTENT).build())
                .addStatement(new ExpressionDef.CallInstanceMethod(
                        new VariableDef.Field(new VariableDef.This(TypeDef.of(controllerClassElement)), FIELD_REPOSITORY,
                                TypeDef.of(repositoryClassElement)),
                        METHOD_NAME_DELETE_BY_ID, List.of(new VariableDef.MethodParameter(PARAMETER_ID, methodParameterTypeDef)), TypeDef.VOID
                ))
                .returns(TypeDef.VOID)
                .build();
    }

    private static MethodDef updateMethod(ClassElement controllerClassElement,
                                          ClassElement repositoryClassElement,
                                          ClassElement entityClassElement) {
        TypeDef methodParameterTypeDef = TypeDef.of(entityClassElement);
        return MethodDef.builder(METHOD_NAME_UPDATE)
                .addAnnotation(ClassTypeDef.of(Put.class))
                .addParameter(ParameterDef.builder(PARAMETER_ENTITY, TypeDef.of(entityClassElement)).addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Body.class)).build()).build())
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Status.class)).addMember(MEMBER_VALUE, HttpStatus.NO_CONTENT).build())
                .addStatement(new ExpressionDef.CallInstanceMethod(
                        new VariableDef.Field(new VariableDef.This(TypeDef.of(controllerClassElement)), FIELD_REPOSITORY,
                                TypeDef.of(repositoryClassElement)),
                        METHOD_NAME_UPDATE, List.of(new VariableDef.MethodParameter(PARAMETER_ENTITY, methodParameterTypeDef)), TypeDef.VOID
                ))
                .returns(TypeDef.VOID)
                .build();
    }

    private static MethodDef saveMethod(ClassElement controllerClassElement,
                                 ClassElement repositoryClassElement,
                                 ClassElement entityClassElement) {
        TypeDef methodParameterTypeDef = TypeDef.of(entityClassElement);
        return MethodDef.builder(METHOD_NAME_SAVE)
                .addParameter(ParameterDef.builder(PARAMETER_ENTITY, TypeDef.of(entityClassElement)).addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Body.class)).build()).build())
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Status.class)).addMember(MEMBER_VALUE, HttpStatus.CREATED).build())
                .addAnnotation(ClassTypeDef.of(Post.class))
                .addStatement(new ExpressionDef.CallInstanceMethod(
                        new VariableDef.Field(new VariableDef.This(TypeDef.of(controllerClassElement)), FIELD_REPOSITORY,
                                TypeDef.of(repositoryClassElement)),
                        METHOD_NAME_SAVE, List.of(new VariableDef.MethodParameter(PARAMETER_ENTITY, methodParameterTypeDef)), TypeDef.VOID
                ))
                .returns(TypeDef.VOID)
                .build();
    }
}
