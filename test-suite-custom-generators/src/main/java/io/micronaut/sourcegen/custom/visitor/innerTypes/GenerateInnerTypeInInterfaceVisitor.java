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
package io.micronaut.sourcegen.custom.visitor.innerTypes;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateInnerTypes;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.RecordDef;

import javax.lang.model.element.Modifier;

import static io.micronaut.sourcegen.custom.visitor.innerTypes.GenerateInnerTypeInEnumVisitor.getInnerClassDef;
import static io.micronaut.sourcegen.custom.visitor.innerTypes.GenerateInnerTypeInEnumVisitor.getInnerEnumDef;
import static io.micronaut.sourcegen.custom.visitor.innerTypes.GenerateInnerTypeInEnumVisitor.getInnerInterfaceDef;
import static io.micronaut.sourcegen.custom.visitor.innerTypes.GenerateInnerTypeInEnumVisitor.getInnerRecordDef;

@Internal
public final class GenerateInnerTypeInInterfaceVisitor implements TypeElementVisitor<GenerateInnerTypes, Object> {

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

        InterfaceDef objectDef = getInterfaceDef(element, context.getLanguage());

        sourceGenerator.write(objectDef, context, element);
    }

    private static InterfaceDef getInterfaceDef(ClassElement element, VisitorContext.Language language) {
        String className = element.getPackageName() + ".InterfaceWithInnerTypes";

        EnumDef innerEnum = getInnerEnumDef();

        RecordDef innerRecord = getInnerRecordDef();

        ClassDef innerClass = getInnerClassDef(language);

        InterfaceDef innerInterface = getInnerInterfaceDef();

        //outer
        return InterfaceDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("hello")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addStatement(ExpressionDef.trueValue().returning())
                .returns(Boolean.class)
                .build())
            .addInnerType(innerEnum)
            .addInnerType(innerRecord)
            .addInnerType(innerClass)
            .addInnerType(innerInterface)
            .build();
    }
}
