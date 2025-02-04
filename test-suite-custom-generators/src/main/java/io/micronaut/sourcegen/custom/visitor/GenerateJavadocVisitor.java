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
package io.micronaut.sourcegen.custom.visitor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateJavadoc;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;

@Internal
public final class GenerateJavadocVisitor implements TypeElementVisitor<GenerateJavadoc, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String builderClassName = element.getPackageName() + ".Javadoc";

        ClassDef beanDef = ClassDef.builder(builderClassName)
            .addMethod(MethodDef.constructor().addJavadoc("This is a constructor").build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("""
                A hard or soft tortilla, loosely folded and filled with whatever {@link\s
                {@link java.util.Random random} tex-mex stuff we could find in the pantry
                """)
            .addProperty(PropertyDef.builder("name")
                .ofType(TypeDef.of(String.class).makeNullable())
                .addJavadoc("True for a soft flour tortilla; false for a crunchy corn tortilla.\n")
                .build())
            .addField(FieldDef.builder("array")
                .ofType(TypeDef.STRING)
                .addJavadoc("Sets an array of volumes for the container to use. You can use volumes to share data between services or other steps in a job. You can specify named Docker volumes, anonymous Docker volumes, or bind mounts on the host.\nTo specify a volume, you specify the source and destination path: <source>:<destinationPath>\nThe <source> is a volume name or an absolute path on the host machine, and <destinationPath> is an absolute path in the container.")
                .build())
            .build();

        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        sourceGenerator.write(beanDef, context, element);
    }

}
