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
package io.micronaut.sourcegen.custom.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateMyBean3;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;

@Internal
public final class GenerateMyBean3Visitor implements TypeElementVisitor<GenerateMyBean3, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String builderClassName = element.getPackageName() + ".MyBean3";

        ClassDef beanDef = ClassDef.builder(builderClassName)
            .addMethod(MethodDef.constructor().build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addField(FieldDef.builder("otherName").ofType(TypeDef.of(String.class).makeNullable()).build())
            .addMethod(MethodDef.constructor().addParameter(
                ParameterDef.builder("num", ClassTypeDef.of(Integer.class))
                    .addAnnotation(Nullable.class)
                    .build()
            ).build())
            .addMethod(MethodDef.constructor().addParameter("name", ClassTypeDef.of(String.class))
                .addStatement(new StatementDef.Assign(
                    new VariableDef.Field(new VariableDef.This(
                        ClassTypeDef.of(builderClassName)),
                        "otherName", ClassTypeDef.of(String.class)
                    ),
                    new VariableDef.MethodParameter("name", ClassTypeDef.of(String.class))
                ))
                .build()
            )
            .addMethod(MethodDef.builder("castPrimitive")
                .addParameter(ParameterDef.of("value", TypeDef.primitive(Double.TYPE)))
                .returns(TypeDef.primitive(Float.TYPE))
                .addStatement(new StatementDef.Return(
                    new ExpressionDef.Cast(
                        TypeDef.primitive(Float.TYPE),
                        new VariableDef.MethodParameter(
                            "value",
                            TypeDef.primitive(Double.TYPE)
                        )
                    )
                ))
                .build()
            )
            .build();

        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        context.visitGeneratedSourceFile(beanDef.getPackageName(), beanDef.getSimpleName(), element)
            .ifPresent(generatedFile -> {
                try {
                    generatedFile.write(writer -> sourceGenerator.write(beanDef, writer));
                } catch (Exception e) {
                    throw new ProcessingException(element, e.getMessage(), e);
                }
            });
    }

}
