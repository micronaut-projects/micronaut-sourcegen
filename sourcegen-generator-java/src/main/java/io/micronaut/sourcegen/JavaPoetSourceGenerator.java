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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import java.io.IOException;
import java.io.Writer;
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
    public void write(ClassDef classDef, Writer writer) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
        classBuilder.addModifiers(classDef.getModifiersArray());
        for (FieldDef field : classDef.getFields()) {
            classBuilder.addField(
                FieldSpec.builder(
                        asType(field.getType()),
                        field.getName()
                    ).addModifiers(field.getModifiersArray())
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
                            param.getName()
                        ).build())
                        .toList()
                );
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

    private static TypeName asType(TypeDef typeDef) {
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
