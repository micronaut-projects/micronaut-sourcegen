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
import io.micronaut.sourcegen.custom.example.GenerateAnnotationClass;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationMemberDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeDef.Primitive;

import javax.lang.model.element.Modifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Internal
public final class GenerateAnnotationClassVisitor implements TypeElementVisitor<GenerateAnnotationClass, Object> {

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        String className = element.getPackageName() + ".MyAnnotation";

        AnnotationObjectDef annotationDef = createAnnotation(className);
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        sourceGenerator.write(annotationDef, context, element);
    }

    public static AnnotationObjectDef createAnnotation(String className) {
        return AnnotationObjectDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Retention.class)
                .addMember("value", RetentionPolicy.RUNTIME)
                .build()
            )
            .addAnnotation(AnnotationDef.builder(Target.class)
                .addMember("value", ElementType.TYPE)
                .build()
            )
            .addJavadoc("This is my annotation")
            .addMember(AnnotationMemberDef.builder("string", TypeDef.STRING)
                .addJavadoc("This is a string value")
                .withDefault(ExpressionDef.constant("hello"))
                .build()
            )
            .addMember(AnnotationMemberDef.builder("primitive", Primitive.INT)
                .addJavadoc("This is a primitive value")
                .withDefault(ExpressionDef.constant(2))
                .build()
            )
            .addMember(AnnotationMemberDef.builder("floats", Primitive.FLOAT.array())
                .addJavadoc("This is a primitive array value")
                .build()
            )
            .addMember(AnnotationMemberDef.builder("enumValue", ClassTypeDef.of(ElementType.class))
                .withDefault(ClassTypeDef.of(ElementType.class)
                    .getStaticField("TYPE", ClassTypeDef.of(ElementType.class)))
                .addJavadoc("An enum value with default")
                .build())
            .addMember(AnnotationMemberDef.builder("inner", ClassTypeDef.of(Target.class))
                .withDefault(AnnotationDef.builder(Target.class)
                    .addMember("value", ElementType.TYPE)
                    .build())
                .addJavadoc("An annotation value with default")
                .build())
            .build();
    }

}
