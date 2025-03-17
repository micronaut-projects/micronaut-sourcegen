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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateLambda;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef.Local;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Function;

@Internal
public final class GenerateLambdaVisitor implements TypeElementVisitor<GenerateLambda, Object> { // <1>

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    } // <2>

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        try {
            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null); // <3>
            if (sourceGenerator == null) {
                return;
            }

            String className = element.getPackageName() + ".MyClassWithLambda";

            ClassElement stringType = context.getClassElement(String.class).get();
            ClassElement functionType = context.getClassElement(Function.class)
                .orElseThrow(() -> new ProcessingException(element, "Could not find Function type"))
                .withTypeArguments(List.of(stringType, stringType));
            Local function = new Local("function", TypeDef.of(functionType));
            ExpressionDef.InlineLambda lambda = ExpressionDef.InlineLambda.extend(
                functionType, (t, params) -> params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1)).returning());

            ClassDef interfaceDef = ClassDef.builder(className) // <4>
                .addModifiers(Modifier.PUBLIC)
                .addMethod(MethodDef.builder("callLambda")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeDef.STRING)
                    .addParameter("input", TypeDef.STRING)
                    .build((t, params) -> StatementDef.multi(
                        function.defineAndAssign(lambda),
                        // TODO the virtual call needs to be TypeDef.STRING instead with a cast
                        function.invoke("apply", TypeDef.OBJECT, params.get(0).cast(TypeDef.OBJECT)).returning()
                    ))
                )
                .build();

            sourceGenerator.write(interfaceDef, context, element);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}
// end::class[]
