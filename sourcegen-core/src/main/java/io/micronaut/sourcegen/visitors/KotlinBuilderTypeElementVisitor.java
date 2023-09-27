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

import com.squareup.kotlinpoet.ClassName;
import com.squareup.kotlinpoet.FileSpec;
import com.squareup.kotlinpoet.FunSpec;
import com.squareup.kotlinpoet.KModifier;
import com.squareup.kotlinpoet.ParameterSpec;
import com.squareup.kotlinpoet.ParameterizedTypeName;
import com.squareup.kotlinpoet.PropertySpec;
import com.squareup.kotlinpoet.TypeName;
import com.squareup.kotlinpoet.TypeSpec;
import com.squareup.kotlinpoet.javapoet.J2kInteropKt;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedSourceFile;
import io.micronaut.sourcegen.ann.Builder;
import kotlin.reflect.KClass;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class KotlinBuilderTypeElementVisitor implements TypeElementVisitor<Builder, Object> {
    private final Map<BuilderInfo, TypeSpec.Builder> typeBuilders = new HashMap<>();

    private TypeSpec.Builder findBuilderFor(ClassElement element) {
        var simpleName = element.getSimpleName() + "Builder";
        var builderName = element.getPackageName() + "." + simpleName;
        var info = new BuilderInfo(element.getCanonicalName(), builderName);
        return typeBuilders.computeIfAbsent(info, name ->
            TypeSpec.classBuilder(simpleName).addModifiers(KModifier.DATA)
        );
    }

    private static TypeName kType(String pkg, String name, boolean nullable) {
        if ("".equals(pkg)) {
            TypeName typeName = switch (name) {
                case "void" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.VOID);
                case "byte" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BYTE);
                case "short" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.SHORT);
                case "char" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.CHAR);
                case "int" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.INT);
                case "long" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.LONG);
                case "float" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.FLOAT);
                case "double" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.DOUBLE);
                case "boolean" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BOOLEAN);
                default -> null;
            };
            if (typeName != null) {
                if (nullable) {
                    typeName = asNullable(typeName);
                }
                return typeName;
            }
        }
        var kClassName = J2kInteropKt.toKClassName(com.squareup.javapoet.ClassName.get(pkg, name));
        if (nullable) {
            kClassName = (ClassName) asNullable(kClassName);
        }
        return kClassName;
    }

    private static TypeName asNullable(TypeName kClassName) {
        return kClassName.copy(true, kClassName.getAnnotations(), kClassName.getTags());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        var builderTypeName = element.getSimpleName() + "Builder";
        var builder = findBuilderFor(element);
        var builderClassName = kType(element.getPackageName(), builderTypeName, false);
        for (PropertyElement beanProperty : element.getBeanProperties()) {
            var propertyType = beanProperty.getType();
            var propertyName = beanProperty.getSimpleName();
            var propertyTypeName = kType(propertyType.getPackageName(), propertyType.getSimpleName(), false);

            builder.addFunction(FunSpec.builder(
                    propertyName
                )
                .addParameter(propertyName, propertyTypeName)
                .returns(builderClassName)
                .addStatement("return copy(%L=%L)", propertyName, propertyName)
                .build());
        }
    }

    private String defaultValueFor(TypedElement e) {
        if (e.isPrimitive()) {
            return "0";
        }
        return "null";
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (element.isPublic()) {
            var owningType = element.getOwningType();
            var builder = findBuilderFor(owningType);
            var args = Arrays.stream(element.getParameters())
                .map(ParameterElement::getSimpleName)
                .map(s -> s + "!!")
                .collect(Collectors.joining(","));
            var buildMethodBuilder = FunSpec.builder("build")
                .returns(kType(owningType.getPackageName(), owningType.getSimpleName(), false));
            buildMethodBuilder.addStatement(
                "return " + owningType.getSimpleName() + "(" +
                args +
                ")"
            );
            var ctorBuilder = FunSpec.constructorBuilder()
                .addParameters(
                    Arrays.stream(element.getParameters())
                        .map(p -> ParameterSpec.builder(
                            p.getName(),
                            kType(p.getType().getPackageName(), p.getType().getSimpleName(), true)
                        )
                            .defaultValue(defaultValueFor(p)).build())
                        .toList()
                );
            Arrays.stream(element.getParameters())
                .map(p -> PropertySpec.builder(
                    p.getName(),
                    kType(p.getType().getPackageName(), p.getType().getSimpleName(), true)
                ).initializer(p.getName()).build())
                .forEach(builder::addProperty);
            builder.primaryConstructor(ctorBuilder.build());
            builder.addFunction(buildMethodBuilder.build());
        }
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        for (Map.Entry<BuilderInfo, TypeSpec.Builder> entry : typeBuilders.entrySet()) {
            var info = entry.getKey();
            var fqn = info.builderTypeName();
            var typeSpecBuilder = entry.getValue();
            var packageName = fqn.substring(0, fqn.lastIndexOf("."));
            var fileName = fqn.substring(fqn.lastIndexOf(".") + 1);
            var generatedSourceFile = visitorContext.visitGeneratedSourceFile(packageName, fileName);
            generatedSourceFile.ifPresent(generatedFile -> {
                var sourceFile = FileSpec.builder(packageName, "${fileName}.kt")
                    .addType(typeSpecBuilder.build())
                    .addFunction(FunSpec.builder("builder")
                        .receiver(ParameterizedTypeName.get(ClassName.bestGuess(KClass.class.getName()), ClassName.bestGuess(info.originalTypeName)))
                        .returns(ClassName.bestGuess(fqn))
                        .addStatement("return %T()", ClassName.bestGuess(fqn))
                        .build())
                    .build();
                try {
                    generatedFile.visitLanguage(GeneratedSourceFile.Language.KOTLIN, sourceFile::writeTo);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        typeBuilders.clear();
    }

    private record BuilderInfo(
        String originalTypeName,
        String builderTypeName
    ) {

    }
}
