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
import org.jspecify.annotations.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateMyEnum2;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.List;

@Internal
public final class GenerateMyEnum2Visitor implements TypeElementVisitor<GenerateMyEnum2, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String enumClassName = element.getPackageName() + ".MyEnum2";

        EnumDef beanDef = EnumDef.builder(enumClassName)
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("A", ExpressionDef.constant(0))
            .addEnumConstant("B", ExpressionDef.constant(1))
            .addEnumConstant("C", ExpressionDef.constant(2))
            .addField(FieldDef.builder("myValue").ofType(TypeDef.Primitive.INT).addModifiers(Modifier.PUBLIC).build())
            .addMethod(MethodDef.builder("myName")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, parameters) ->
                    aThis.invoke("toString", TypeDef.STRING, List.of()).returning()))
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build();

        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        sourceGenerator.write(beanDef, context, element);
    }

}
