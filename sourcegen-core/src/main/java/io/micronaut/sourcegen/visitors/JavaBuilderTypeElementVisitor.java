/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.visitors;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedSourceFile;
import io.micronaut.sourcegen.ann.Builder;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JavaBuilderTypeElementVisitor implements TypeElementVisitor<Builder, Object> {
    private final Map<String, TypeSpec.Builder> typeBuilders = new HashMap<>();

    private TypeSpec.Builder findBuilderFor(ClassElement element) {
        var simpleName = element.getSimpleName() + "Builder";
        var builderName = element.getPackageName() + "." + simpleName;
        return typeBuilders.computeIfAbsent(builderName, name -> TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC));
    }

    private List<String> orderedPropertyNames(ClassElement element) {
        return element.getBeanProperties()
            .stream()
            .map(Element::getSimpleName)
            .toList();
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        var builderTypeName = element.getSimpleName() + "Builder";
        var builder = findBuilderFor(element);
        var builderClassName = ClassName.get(element.getPackageName(), builderTypeName);
        builder.addMethod(MethodSpec.methodBuilder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderClassName)
            .addStatement("return new $T(" + String.join(", ", defaultValuesFor(element.getBeanProperties())) + ")", builderClassName)
            .build());
        for (PropertyElement beanProperty : element.getBeanProperties()) {
            var propertyType = beanProperty.getType();
            var propertyName = beanProperty.getSimpleName();
            var propertyTypeName = ClassName.get(propertyType.getPackageName(), propertyType.getSimpleName());
            builder.addField(FieldSpec.builder(
                propertyTypeName,
                propertyName,
                Modifier.PRIVATE, Modifier.FINAL
            ).build());
            builder.addMethod(MethodSpec.methodBuilder(propertyName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(propertyTypeName, propertyName)
                .returns(builderClassName)
                .addStatement("return new " + builderTypeName + "(" + String.join(", ", orderedPropertyNames(element)) + ")")
                .build());
        }
    }

    private List<String> defaultValuesFor(List<PropertyElement> beanProperties) {
        return beanProperties.stream()
            .map(p -> {
                if (p.isPrimitive()) {
                    return "0";
                }
                return "null";
            }).toList();
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (element.isPublic()) {
            var owningType = element.getOwningType();
            var builder = findBuilderFor(owningType);
            var args = Arrays.stream(element.getParameters())
                .map(ParameterElement::getSimpleName)
                .collect(Collectors.joining(","));
            var buildMethodBuilder = MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(owningType.getPackageName(), owningType.getSimpleName()));
            buildMethodBuilder.addStatement(
                "return new " + owningType.getSimpleName() + "(" +
                args +
                ")"
            );
            var ctorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameters(
                    Arrays.stream(element.getParameters())
                        .map(p -> ParameterSpec.builder(
                            ClassName.get(p.getType().getPackageName(), p.getType().getSimpleName()),
                            p.getName()
                        ).build())
                        .toList()
                );
            for (String propertyName : orderedPropertyNames(owningType)) {
                ctorBuilder.addStatement("this.$L=$L", propertyName, propertyName);
            }
            builder.addMethod(ctorBuilder.build());
            builder.addMethod(buildMethodBuilder.build());
        }
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        for (Map.Entry<String, TypeSpec.Builder> entry : typeBuilders.entrySet()) {
            var fqn = entry.getKey();
            var typeSpecBuilder = entry.getValue();
            var packageName = fqn.substring(0, fqn.lastIndexOf("."));
            var fileName = fqn.substring(fqn.lastIndexOf(".") + 1);
            var generatedSourceFile = visitorContext.visitGeneratedSourceFile(packageName, fileName);
            generatedSourceFile.ifPresent(generatedFile -> {
                var javaFile = JavaFile.builder(packageName, typeSpecBuilder.build()).build();
                try {
                    generatedFile.visitLanguage(GeneratedSourceFile.Language.JAVA, javaFile::writeTo);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        typeBuilders.clear();
    }

}
