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
import io.micronaut.sourcegen.custom.example.GenerateMyRepository1;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;

@Internal
public final class GenerateMyRepository1Visitor implements TypeElementVisitor<GenerateMyRepository1, Object> {

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

        ClassDef myEntityDef = ClassDef.builder(element.getPackageName() + ".MyEntity1")
            .addProperty(
                PropertyDef.builder("id").ofType(TypeDef.of(Long.class).makeNullable()).build()
            )
            .addProperty(
                PropertyDef.builder("firstName").ofType(TypeDef.of(String.class).makeNullable()).build()
            )
            .addProperty(
                PropertyDef.builder("age").ofType(TypeDef.of(Integer.class).makeNullable()).build()
            )
            .build();

        sourceGenerator.write(myEntityDef, context, element);

        TypeDef.TypeVariable entityType = new TypeDef.TypeVariable(
            "E",
            List.of()
        );
        TypeDef.TypeVariable idType = new TypeDef.TypeVariable(
            "ID",
            List.of()
        );
        InterfaceDef crudRepositoryDef = InterfaceDef.builder(element.getPackageName() + ".CrudRepository1")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(
                entityType
            )
            .addTypeVariable(
                idType
            )

            .addMethod(MethodDef.builder("findById")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .addParameter("id", idType)
                .returns(new ClassTypeDef.Parameterized(
                    ClassTypeDef.of(Optional.class),
                    List.of(entityType)
                ))
                .build())

            .addMethod(MethodDef.builder("findAll")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .returns(new ClassTypeDef.Parameterized(
                    ClassTypeDef.of(List.class),
                    List.of(entityType)
                ))
                .build())

            .addMethod(MethodDef.builder("save")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .addParameter("entity", entityType)
                .returns(TypeDef.VOID)
                .build())

            .build();

        sourceGenerator.write(crudRepositoryDef, context, element);

        InterfaceDef myRepositoryRef = InterfaceDef.builder(element.getPackageName() + ".MyRepository1")
            .addModifiers(Modifier.PUBLIC)

            .addSuperinterface(new ClassTypeDef.Parameterized(
                crudRepositoryDef.asTypeDef(),
                List.of(myEntityDef.asTypeDef(), TypeDef.of(Long.class))
            ))

            .build();

        sourceGenerator.write(myRepositoryRef, context, element);
    }

}
