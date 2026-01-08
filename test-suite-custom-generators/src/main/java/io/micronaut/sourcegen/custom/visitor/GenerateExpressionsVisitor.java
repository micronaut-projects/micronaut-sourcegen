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

// tag::class[]
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateExpressions;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.MethodDef;

import javax.lang.model.element.Modifier;

@Internal
public final class GenerateExpressionsVisitor implements TypeElementVisitor<GenerateExpressions, Object> { // <1>

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    } // <2>

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null); // <3>
        if (sourceGenerator == null) {
            return;
        }

        String className = element.getPackageName() + ".Expressions";

        ClassDef def = ClassDef.builder(className) // <4>
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("equalsStructurally")
                .addModifiers(Modifier.PUBLIC)
                .addParameters(String.class, String.class)
                .returns(boolean.class)
                .build((t, p) ->
                    p.get(0).equalsStructurally(p.get(1)).returning()
                )
            )
            .build();

        sourceGenerator.write(def, context, element);
    }
}
// end::class[]
