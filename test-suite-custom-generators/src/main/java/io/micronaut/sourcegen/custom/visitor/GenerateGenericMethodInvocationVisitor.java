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
import io.micronaut.sourcegen.custom.example.GenerateGenericMethodInvocation;
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
import java.util.function.Function;

@Internal
public final class GenerateGenericMethodInvocationVisitor implements TypeElementVisitor<GenerateGenericMethodInvocation, Object> {

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

        ClassDef functionImplementation = ClassDef.builder(element.getPackageName() + ".FunctionImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("input", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build((t, params) -> params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1)).returning())
            )
            .build();

        Local functionVar = new Local("function", TypeDef.parameterized(Function.class, String.class, String.class));
        ClassDef def = ClassDef.builder(element.getPackage() + ".GenericMethodInvoker")
            .addMethod(MethodDef.builder("invoke")
                .returns(TypeDef.STRING)
                .addParameter("input", TypeDef.STRING)
                .build((t, params) -> StatementDef.multi(
                    functionVar.defineAndAssign(functionImplementation.asTypeDef().instantiate()),
                    functionVar.invoke("apply", TypeDef.STRING, params.get(0)).returning()
                ))
            )
            .build();

        sourceGenerator.write(functionImplementation, context, element);
        sourceGenerator.write(def, context, element);
    }

}
