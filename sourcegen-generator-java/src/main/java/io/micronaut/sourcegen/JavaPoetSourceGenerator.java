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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.ClassName;
import io.micronaut.sourcegen.javapoet.FieldSpec;
import io.micronaut.sourcegen.javapoet.JavaFile;
import io.micronaut.sourcegen.javapoet.MethodSpec;
import io.micronaut.sourcegen.javapoet.ParameterSpec;
import io.micronaut.sourcegen.javapoet.ParameterizedTypeName;
import io.micronaut.sourcegen.javapoet.TypeName;
import io.micronaut.sourcegen.javapoet.TypeSpec;
import io.micronaut.sourcegen.javapoet.TypeVariableName;
import io.micronaut.sourcegen.javapoet.WildcardTypeName;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Java source generator.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public sealed class JavaPoetSourceGenerator implements SourceGenerator permits GroovyPoetSourceGenerator {

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) throws IOException {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(writer, classDef);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(writer, recordDef);
        } else if (objectDef instanceof InterfaceDef interfaceDef) {
            writeInterface(writer, interfaceDef);
        } else if (objectDef instanceof EnumDef enumDef) {
            writeEnum(writer, enumDef);
        } else {
            throw new IllegalStateException("Unknown object definition: " + objectDef);
        }
    }

    private void writeInterface(Writer writer, InterfaceDef interfaceDef) throws IOException {
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(interfaceDef.getSimpleName());
        interfaceBuilder.addModifiers(interfaceDef.getModifiersArray());
        interfaceDef.getTypeVariables().stream().map(this::asTypeVariable).forEach(interfaceBuilder::addTypeVariable);
        interfaceDef.getSuperinterfaces().stream().map(this::asType).forEach(interfaceBuilder::addSuperinterface);

        for (PropertyDef property : interfaceDef.getProperties()) {
            TypeName propertyType = asType(property.getType());
            String propertyName = property.getName();
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                propertyType,
                propertyName
            ).addModifiers(Modifier.PRIVATE);
            for (AnnotationDef annotation : property.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            interfaceBuilder.addField(
                fieldBuilder
                    .build()
            );
            String capitalizedPropertyName = NameUtils.capitalize(propertyName);
            interfaceBuilder.addMethod(MethodSpec.methodBuilder("get" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .returns(propertyType)
//                    .addStatement("return this." + propertyName)
                .build());
            interfaceBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
//                    .addStatement("this." + propertyName + " = " + propertyName)
                .build());
        }
        for (MethodDef method : interfaceDef.getMethods()) {
            interfaceBuilder.addMethod(
                asMethodSpec(interfaceDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(interfaceDef.getPackageName(), interfaceBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeEnum(Writer writer, EnumDef enumDef) throws IOException {
        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(enumDef.getSimpleName());
        enumBuilder.addModifiers(enumDef.getModifiersArray());
        enumDef.getSuperinterfaces().stream().map(this::asType).forEach(enumBuilder::addSuperinterface);

        for (String enumConstant : enumDef.getEnumConstants()) {
            enumBuilder.addEnumConstant(enumConstant);
        }

        for (MethodDef method : enumDef.getMethods()) {
            enumBuilder.addMethod(
                asMethodSpec(enumDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(enumDef.getPackageName(), enumBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeClass(Writer writer, ClassDef classDef) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
        classBuilder.addModifiers(classDef.getModifiersArray());
        classDef.getTypeVariables().stream().map(this::asTypeVariable).forEach(classBuilder::addTypeVariable);
        classDef.getSuperinterfaces().stream().map(this::asType).forEach(classBuilder::addSuperinterface);

        for (PropertyDef property : classDef.getProperties()) {
            TypeName propertyType = asType(property.getType());
            String propertyName = property.getName();
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                propertyType,
                propertyName
            ).addModifiers(Modifier.PRIVATE);
            for (AnnotationDef annotation : property.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            classBuilder.addField(
                fieldBuilder
                    .build()
            );
            String capitalizedPropertyName = NameUtils.capitalize(propertyName);
            classBuilder.addMethod(MethodSpec.methodBuilder("get" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .returns(propertyType)
                .addStatement("return this." + propertyName)
                .build());
            classBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
                .addStatement("this." + propertyName + " = " + propertyName)
                .build());
        }
        for (FieldDef field : classDef.getFields()) {
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                asType(field.getType()),
                field.getName()
            ).addModifiers(field.getModifiersArray());
            for (AnnotationDef annotation : field.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            classBuilder.addField(
                fieldBuilder
                    .build()
            );
        }
        for (MethodDef method : classDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(classDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(classDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeRecord(Writer writer, RecordDef recordDef) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.recordBuilder(recordDef.getSimpleName());
        classBuilder.addModifiers(recordDef.getModifiersArray());
        recordDef.getTypeVariables().stream().map(this::asTypeVariable).forEach(classBuilder::addTypeVariable);
        recordDef.getSuperinterfaces().stream().map(this::asType).forEach(classBuilder::addSuperinterface);

        for (PropertyDef property : recordDef.getProperties()) {
            TypeName propertyType = asType(property.getType());
            String propertyName = property.getName();
            ParameterSpec.Builder componentBuilder = ParameterSpec.builder(propertyType, propertyName);
            for (AnnotationDef annotation : property.getAnnotations()) {
                componentBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            classBuilder.addRecordComponent(
                componentBuilder.build()
            );
        }
        for (MethodDef method : recordDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(recordDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(recordDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private MethodSpec asMethodSpec(ObjectDef objectDef, MethodDef method) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.getName())
            .addModifiers(method.getModifiersArray())
            .returns(asType(method.getReturnType()))
            .addParameters(
                method.getParameters().stream()
                    .map(param -> ParameterSpec.builder(
                        asType(param.getType()),
                        param.getName(),
                        param.getModifiersArray()
                    ).build())
                    .toList()
            );
        for (AnnotationDef annotation : method.getAnnotations()) {
            methodBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        method.getStatements().stream()
            .map(st -> renderStatement(objectDef, method, st))
            .forEach(methodBuilder::addStatement);

        return methodBuilder.build();
    }

    private TypeVariableName asTypeVariable(TypeDef.TypeVariable tv) {
        return TypeVariableName.get(
            tv.name(),
            tv.bounds().stream().map(this::asType).toArray(TypeName[]::new)
        );
    }

    private AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassName.bestGuess(annotationDef.getType().getName()));
        for (Map.Entry<String, Object> e : annotationDef.getValues().entrySet()) {
            String memberName = e.getKey();
            Object value = e.getValue();
            if (value instanceof Class<?>) {
                builder = builder.addMember(memberName, "$T.class", value);
            } else if (value instanceof Enum) {
                builder = builder.addMember(memberName, "$T.$L", value.getClass(), ((Enum<?>) value).name());
            } else if (value instanceof String) {
                builder = builder.addMember(memberName, "$S", value);
            } else if (value instanceof Float) {
                builder = builder.addMember(memberName, "$Lf", value);
            } else if (value instanceof Character) {
                builder = builder.addMember(memberName, "'$L'", io.micronaut.sourcegen.javapoet.Util.characterLiteralWithoutSingleQuotes((char) value));
            } else {
                builder = builder.addMember(memberName, "$L", value);
            }
        }
        return builder.build();
    }

    private TypeName asType(TypeDef typeDef) {
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            return ParameterizedTypeName.get(
                asClassType(parameterized.rawType()),
                parameterized.typeArguments().stream().map(this::asType).toArray(TypeName[]::new)
            );
        }
        if (typeDef instanceof TypeDef.Primitive primitive) {
            return switch (primitive.name()) {
                case "void" -> TypeName.VOID;
                case "byte" -> TypeName.BYTE;
                case "short" -> TypeName.SHORT;
                case "char" -> TypeName.CHAR;
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "float" -> TypeName.FLOAT;
                case "double" -> TypeName.DOUBLE;
                case "boolean" -> TypeName.BOOLEAN;
                default ->
                    throw new IllegalStateException("Unrecognized primitive name: " + primitive.name());
            };
        }
        if (typeDef instanceof ClassTypeDef classType) {
            return ClassName.bestGuess(classType.getName());
        }
        if (typeDef instanceof TypeDef.Wildcard wildcard) {
            if (!wildcard.lowerBounds().isEmpty()) {
                return WildcardTypeName.supertypeOf(
                    asType(
                        wildcard.lowerBounds().get(0)
                    )
                );
            }
            return WildcardTypeName.subtypeOf(
                asType(
                    wildcard.upperBounds().get(0)
                )
            );
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            return asTypeVariable(typeVariable);
        }
        throw new IllegalStateException("Unrecognized type definition " + typeDef);
    }

    private static ClassName asClassType(ClassTypeDef classTypeDef) {
        return ClassName.bestGuess(classTypeDef.getName());
    }

    private static String renderStatement(@Nullable ObjectDef objectDef, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            return "return " + renderExpression(objectDef, methodDef, aReturn.expression());
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            return renderExpression(objectDef, methodDef, assign.variable())
                + " = " +
                renderExpression(objectDef, methodDef, assign.expression());
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private static String renderExpression(@Nullable ObjectDef objectDef, MethodDef methodDef, ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            return "new " + newInstance.type().getName()
                + "(" + newInstance.values()
                .stream()
                .map(exp -> renderExpression(objectDef, methodDef, exp)).collect(Collectors.joining(", "))
                + ")";
        }
        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
            return renderVariable(objectDef, methodDef, convertExpressionDef.variable());
        }
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod callInstanceMethod) {
            return renderVariable(objectDef, methodDef, callInstanceMethod.instance())
                + "." + callInstanceMethod.name()
                + "(" + callInstanceMethod.parameters()
                .stream()
                .map(exp -> renderExpression(objectDef, methodDef, expressionDef))
                .collect(Collectors.joining(", "))
                + ")";
        }
        if (expressionDef instanceof VariableDef variableDef) {
            return renderVariable(objectDef, methodDef, variableDef);
        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private static String renderVariable(@Nullable ObjectDef objectDef, MethodDef methodDef, VariableDef variableDef) {
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            methodDef.getParameter(parameterVariableDef.name()); // Check if exists
            return parameterVariableDef.name();
        }
        if (variableDef instanceof VariableDef.Field field) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            if (objectDef instanceof ClassDef classDef) {
                classDef.getField(field.name()); // Check if exists
            } else {
                throw new IllegalStateException("Field access no supported on the object definition: " + objectDef);
            }
            return renderExpression(objectDef, methodDef, field.instanceVariable()) + "." + field.name();
        }
        if (variableDef instanceof VariableDef.This) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            return "this";
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

}
