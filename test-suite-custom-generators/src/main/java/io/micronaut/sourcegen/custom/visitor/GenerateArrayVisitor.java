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
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;

@Internal
public final class GenerateArrayVisitor implements TypeElementVisitor<io.micronaut.sourcegen.custom.example.GenerateArray, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        TypeDef.Array arrayType = TypeDef.STRING.makeNullable().array(1);

        ClassDef arrayClassDef1 = ClassDef.builder(element.getPackageName() + ".Array1")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC)
                .build((self, parameterDefs) -> arrayType.instantiate(10).returning()))
            .build();

        writeObject(element, context, arrayClassDef1);

        ClassDef arrayClassDef2 = ClassDef.builder(element.getPackageName() + ".Array2")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC)
                .build((self, parameterDefs) ->
                    arrayType.instantiate(
                        ExpressionDef.constant("A"),
                        ExpressionDef.constant("B"),
                        ExpressionDef.constant("C")
                    ).returning()
                )).build();

        writeObject(element, context, arrayClassDef2);
    }

    private void writeObject(ClassElement element, VisitorContext context, ClassDef arrayClassDef) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        sourceGenerator.write(arrayClassDef, context, element);
    }

}
