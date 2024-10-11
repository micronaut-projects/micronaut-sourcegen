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
import io.micronaut.sourcegen.custom.example.GenerateIfsPredicate;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.function.Predicate;

@Internal
public final class GenerateIfsPredicateVisitor implements TypeElementVisitor<GenerateIfsPredicate, Object> {

    public static final String PARAM = "myParam";

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

        Class<?> implementsType = Predicate.class;

        TypeDef paramType = TypeDef.OBJECT.makeNullable();

        ClassDef ifPredicateDef = ClassDef.builder(element.getPackageName() + ".IfPredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addStatement(new StatementDef.If(
                    new VariableDef.MethodParameter(PARAM, paramType).isNull(),
                    ExpressionDef.trueValue().returning()
                ))
                .addStatement(ExpressionDef.falseValue().returning())
                .returns(boolean.class)
                .build())
            .build();

        writeObject(element, context, sourceGenerator, ifPredicateDef);

        ClassDef ifNonPredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonPredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addStatement(
                    new VariableDef.MethodParameter(PARAM, paramType)
                        .isNonNull()
                        .asConditionIf(ExpressionDef.trueValue().returning())
                )
                .addStatement(ExpressionDef.falseValue().returning())
                .returns(boolean.class)
                .build())
            .build();

        writeObject(element, context, sourceGenerator, ifNonPredicateDef);

        ClassDef ifElsePredicateDef = ClassDef.builder(element.getPackageName() + ".IfElsePredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addStatement(new StatementDef.IfElse(
                    new ExpressionDef.Condition(
                        " == ",
                        new VariableDef.MethodParameter(PARAM, paramType),
                        new ExpressionDef.Constant(TypeDef.of(Object.class), null)
                    ),
                    new StatementDef.Return(
                        new ExpressionDef.Constant(TypeDef.of(boolean.class), true)
                    ),
                    new StatementDef.Return(
                        new ExpressionDef.Constant(TypeDef.of(boolean.class), false)
                    )
                ))
                .returns(boolean.class)
                .build())
            .build();

        writeObject(element, context, sourceGenerator, ifElsePredicateDef);

        ClassDef ifNonElsePredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonElsePredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addStatement(
                    new VariableDef.MethodParameter(PARAM, paramType)
                        .isNull()
                        .asConditionIfElse(
                            ExpressionDef.trueValue().returning(),
                            ExpressionDef.falseValue().returning()
                        )
                )
                .returns(boolean.class)
                .build())
            .build();

        writeObject(element, context, sourceGenerator, ifNonElsePredicateDef);

        ClassDef ifNonElseExpressionPredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonElseExpressionPredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((self, methodParameters) -> methodParameters.get(0).isNull().asConditionIfElse(
                    ExpressionDef.trueValue(),
                    ExpressionDef.falseValue()
                ).returning())
            )
            .build();

        writeObject(element, context, sourceGenerator, ifNonElseExpressionPredicateDef);
    }

    private void writeObject(ClassElement element, VisitorContext context, SourceGenerator sourceGenerator, ObjectDef objectDef) {
        context.visitGeneratedSourceFile(objectDef.getPackageName(), objectDef.getSimpleName(), element)
            .ifPresent(generatedFile -> {
                try {
                    generatedFile.write(writer -> sourceGenerator.write(objectDef, writer));
                } catch (Exception e) {
                    throw new ProcessingException(element, e.getMessage(), e);
                }
            });
    }
}
