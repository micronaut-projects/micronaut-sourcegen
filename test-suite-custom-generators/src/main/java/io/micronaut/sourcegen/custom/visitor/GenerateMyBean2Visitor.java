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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateMyBean2;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;

@Internal
public final class GenerateMyBean2Visitor implements TypeElementVisitor<GenerateMyBean2, Object> {

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

        String builderClassName = element.getPackageName() + ".MyBean2";

        ClassDef beanDef = ClassDef.builder(builderClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)

            .addProperty(
                PropertyDef.builder("id")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.primitive(int.class).makeNullable())
                    .addAnnotation(Deprecated.class)
                    .build()
            )

            .addProperty(
                PropertyDef.builder("name")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.of(String.class).makeNullable())
                    .build()
            )

            .addProperty(
                PropertyDef.builder("age")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.of(Integer.class).makeNullable())
                    .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Deprecated.class))
                        .addMember("since", "xyz")
                        .addMember("forRemoval", true)
                        .build())
                    .build()
            )

            .build();

        sourceGenerator.write(beanDef, context, element);
    }

}
