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
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateAccess;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

@Internal
public final class GenerateAccessVisitor implements TypeElementVisitor<GenerateAccess, Object> {

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

        ClassElement foo = context.getRequiredClassElement("io.micronaut.sourcegen.example.access.Foo", context.getElementAnnotationMetadataFactory());
        ClassElement bar = context.getRequiredClassElement("io.micronaut.sourcegen.example.access.Bar", context.getElementAnnotationMetadataFactory());

        ClassDef accessorDef = ClassDef.builder(element.getPackageName() + ".Accessor")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(bar))
            .addMethod(MethodDef.builder("create").addModifiers(Modifier.PUBLIC).build((aThis, methodParameters) -> {
                return ClassTypeDef.of(foo).instantiate().newLocal("foo", fooVar -> {
                    List<FieldElement> fields = foo.getEnclosedElements(ElementQuery.ALL_FIELDS.includeHiddenElements());
                    if (fields.size() != 2) {
                        throw new IllegalStateException("Expected exactly 2 arguments for field accessor: Got " + fields.size());
                    }
                    return StatementDef.multi(statements -> {
                        for (FieldElement field : fields) {
                            statements.add(
                                fooVar.field(field).put(ExpressionDef.constant("HelloWorld" + field.getDeclaringType().getSimpleName()))
                            );
                        }
                        return fooVar.returning();
                    });
                });
            }))
            .addMethod(
                MethodDef.builder("readFooField")
                    .addParameter(ClassTypeDef.of(foo))
                    .addModifiers(Modifier.PUBLIC).build((aThis, methodParameters) -> methodParameters.get(0).field(foo.getFields().get(0)).returning())
            )
            .addMethod(
                MethodDef.builder("readBarField")
                    .addParameter(ClassTypeDef.of(foo))
                    .addModifiers(Modifier.PUBLIC).build((aThis, methodParameters) -> methodParameters.get(0).field(bar.getFields().get(0)).returning())
            )
            .build();

        sourceGenerator.write(accessorDef, context, element);
    }

}
