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
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateMyInterface1;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;

@Internal
public final class GenerateMyInterface1Visitor implements TypeElementVisitor<GenerateMyInterface1, Object> {

    ClassElement thisElement;

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        thisElement = element;
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        if (thisElement != null) {
            generate(thisElement, visitorContext);
            thisElement = null;
        }
    }

    private void generate(ClassElement element, VisitorContext context) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }

        String builderClassName = element.getPackageName() + ".MyInterface1";

        InterfaceDef interfaceDef = InterfaceDef.builder(builderClassName)
            .addModifiers(Modifier.PUBLIC)

            .addMethod(MethodDef.builder("findLong")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .returns(Long.class)
                .build())

            .addMethod(MethodDef.builder("saveString")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .addParameter("myString", String.class)
                .returns(TypeDef.VOID)
                .build())

            .build();

        context.visitGeneratedSourceFile(interfaceDef.getPackageName(), interfaceDef.getSimpleName(), element)
            .ifPresent(generatedFile -> {
                try {
                    generatedFile.write(writer -> sourceGenerator.write(interfaceDef, writer));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

}
