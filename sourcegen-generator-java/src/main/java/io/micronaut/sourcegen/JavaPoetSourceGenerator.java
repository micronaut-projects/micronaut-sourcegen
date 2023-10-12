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

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Character.isISOControl;

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
    public void write(ClassDef classDef, Writer writer) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
        classBuilder.addModifiers(classDef.getModifiersArray());
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
                .map(st -> renderStatement(classDef, method, st))
                .forEach(methodBuilder::addStatement);
            classBuilder.addMethod(
                methodBuilder.build()
            );
        }
        JavaFile javaFile = JavaFile.builder(classDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassName.bestGuess(annotationDef.getType().getTypeName()));
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
                builder = builder.addMember(memberName, "'$L'", characterLiteralWithoutSingleQuotes((char) value));
            } else {
                builder = builder.addMember(memberName, "$L", value);
            }
        }
        return builder.build();
    }

    // Copy from com.squareup.javapoet.Util
    private static String characterLiteralWithoutSingleQuotes(char c) {
        // see https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.10.6
        return switch (c) {
            case '\b' -> "\\b"; /* \u0008: backspace (BS) */
            case '\t' -> "\\t"; /* \u0009: horizontal tab (HT) */
            case '\n' -> "\\n"; /* \u000a: linefeed (LF) */
            case '\f' -> "\\f"; /* \u000c: form feed (FF) */
            case '\r' -> "\\r"; /* \u000d: carriage return (CR) */
            case '\"' -> "\"";  /* \u0022: double quote (") */
            case '\'' -> "\\'"; /* \u0027: single quote (') */
            case '\\' -> "\\\\";  /* \u005c: backslash (\) */
            default -> isISOControl(c) ? String.format("\\u%04x", (int) c) : Character.toString(c);
        };
    }

    private static TypeName asType(TypeDef typeDef) {
        String packageName = typeDef.getPackageName();
        String simpleName = typeDef.getSimpleName();
        if ("".equals(packageName)) {
            TypeName typeName = switch (simpleName) {
                case "void" -> TypeName.VOID;
                case "byte" -> TypeName.BYTE;
                case "short" -> TypeName.SHORT;
                case "char" -> TypeName.CHAR;
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "float" -> TypeName.FLOAT;
                case "double" -> TypeName.DOUBLE;
                case "boolean" -> TypeName.BOOLEAN;
                default -> null;
            };
            if (typeName != null) {
                return typeName;
            }
        }
        return ClassName.bestGuess(typeDef.getTypeName());
    }

    private static String renderStatement(@Nullable ClassDef classDef, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            return "return " + renderExpression(classDef, methodDef, aReturn.expression());
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            return renderExpression(classDef, methodDef, assign.variable())
                + " = " +
                renderExpression(classDef, methodDef, assign.expression());
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private static String renderExpression(@Nullable ClassDef classDef, MethodDef methodDef, ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            return "new " + newInstance.type().getTypeName()
                + "(" + newInstance.values()
                .stream()
                .map(exp -> renderExpression(classDef, methodDef, exp)).collect(Collectors.joining(", "))
                + ")";
        }
        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
            return renderVariable(classDef, methodDef, convertExpressionDef.variable());
        }
        if (expressionDef instanceof VariableDef variableDef) {
            return renderVariable(classDef, methodDef, variableDef);
        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private static String renderVariable(@Nullable ClassDef classDef, MethodDef methodDef, VariableDef variableDef) {
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            methodDef.getParameter(parameterVariableDef.name()); // Check if exists
            return parameterVariableDef.name();
        }
        if (variableDef instanceof VariableDef.Field field) {
            classDef.getField(field.name()); // Check if exists
            return renderExpression(classDef, methodDef, field.instanceVariable()) + "." + field.name();
        }
        if (variableDef instanceof VariableDef.This) {
            if (classDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            return "this";
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

}
