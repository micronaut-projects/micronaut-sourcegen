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
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateMyBean1;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Internal
public final class GenerateMyBean1Visitor implements TypeElementVisitor<GenerateMyBean1, Object> {

    private final List<ElementAndClass> builderClasses = new ArrayList<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String builderClassName = element.getPackageName() + ".MyBean1";

        ClassDef.ClassDefBuilder builder = ClassDef.builder(builderClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

            builder.addProperty(
                PropertyDef.builder("id")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.of(int.class))
                    .addAnnotation(Deprecated.class)
                    .build()
            );

            builder.addProperty(
                PropertyDef.builder("name")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.of(String.class))
                    .build()
            );

            builder.addProperty(
                PropertyDef.builder("age")
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(TypeDef.of(Integer.class))
                    .addAnnotation(AnnotationDef.builder(TypeDef.of(Deprecated.class))
                        .addMember("since", "xyz")
                        .addMember("forRemoval", true)
                        .build())
                    .build()
            );

        builderClasses.add(new ElementAndClass(element, builder.build()));
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(visitorContext.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        for (ElementAndClass tuple : builderClasses) {
            ClassDef builderDef = tuple.classDef();
            visitorContext.visitGeneratedSourceFile(
                builderDef.getPackageName(),
                builderDef.getSimpleName(),
                tuple.element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(builderDef, writer)
                    );
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        // Somehow finish is called twice
        builderClasses.clear();
    }

    private record ElementAndClass(ClassElement element, ClassDef classDef) {
    }
}
