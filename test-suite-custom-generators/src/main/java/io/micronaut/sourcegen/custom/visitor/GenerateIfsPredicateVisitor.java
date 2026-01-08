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
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.example.GenerateIfsPredicate;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
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
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNull().doIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        sourceGenerator.write(ifPredicateDef, context, element);

        ClassDef ifNonPredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonPredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNonNull().doIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        sourceGenerator.write(ifNonPredicateDef, context, element);

        ClassDef ifElsePredicateDef = ClassDef.builder(element.getPackageName() + ".IfElsePredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((aThis, methodParameters)
                    -> methodParameters.get(0).ifNull(
                        TypeDef.Primitive.TRUE.returning(),
                        TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        sourceGenerator.write(ifElsePredicateDef, context, element);

        ClassDef ifElsePredicateDef2 = ClassDef.builder(element.getPackageName() + ".IfElsePredicate2")
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .ifNull(
                        TypeDef.Primitive.INT.constant(1).returning(),
                        TypeDef.Primitive.INT.constant(2).returning()
                    )))
            .build();

        sourceGenerator.write(ifElsePredicateDef2, context, element);

        ClassDef ifNonElsePredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonElsePredicate")
            .addSuperinterface(TypeDef.parameterized(implementsType, paramType))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addStatement(
                    new VariableDef.MethodParameter(PARAM, paramType)
                        .ifNull(
                            ExpressionDef.trueValue().returning(),
                            ExpressionDef.falseValue().returning()
                        )
                )
                .returns(boolean.class)
                .build())
            .build();

        sourceGenerator.write(ifNonElsePredicateDef, context, element);

        ClassDef ifNonElseExpressionPredicateDef = ClassDef.builder(element.getPackageName() + ".IfNonElseExpressionPredicate")
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .build((self, methodParameters) -> methodParameters.get(0).ifNull(
                    ExpressionDef.trueValue(),
                    ExpressionDef.falseValue()
                ).returning())
            )
            .build();

        sourceGenerator.write(ifNonElseExpressionPredicateDef, context, element);

        ClassDef ifNonElseExpressionPredicateDef2 = ClassDef.builder(element.getPackageName() + ".IfNonElseExpressionPredicate2")
            .addMethod(MethodDef.builder("test").addParameter(PARAM, paramType)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .build((self, methodParameters) -> methodParameters.get(0).ifNull(
                    TypeDef.Primitive.INT.constant(1),
                    TypeDef.Primitive.INT.constant(2)
                ).returning())
            )
            .build();

        sourceGenerator.write(ifNonElseExpressionPredicateDef2, context, element);

        ClassDef ifPredicateArray = ClassDef.builder(element.getPackageName() + ".IfPredicateArray")
            .addSuperinterface(TypeDef.parameterized(implementsType, TypeDef.STRING.array()))
            .addMethod(MethodDef.builder("test").addParameter(PARAM, TypeDef.STRING.array())
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNull().doIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        sourceGenerator.write(ifPredicateArray, context, element);

        TypeDef.TypeVariable variableV = TypeDef.variable("V");

        ClassDef ifPredicateArrayGenerics = ClassDef.builder(element.getPackageName() + ".IfPredicateArrayGenerics")
            .addTypeVariable(variableV)
            .addMethod(MethodDef.builder("test")
                .addParameter(TypeDef.STRING.array())
                .addParameter(variableV)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNull().doIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        sourceGenerator.write(ifPredicateArrayGenerics, context, element);

        ClassDef ifPredicatePrimitive1 = ClassDef.builder(element.getPackageName() + ".IfPredicatePrimitive1")
            .addTypeVariable(variableV)
            .addMethod(MethodDef.builder("test")
                .addParameter(TypeDef.of(Integer.class))
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(1)).returning())
            )
            .build();

        sourceGenerator.write(ifPredicatePrimitive1, context, element);

        ClassDef ifPredicatePrimitive2 = ClassDef.builder(element.getPackageName() + ".IfPredicatePrimitive2")
            .addTypeVariable(variableV)
            .addMethod(MethodDef.builder("test")
                .addParameter(TypeDef.Primitive.INT)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning())
            )
            .build();

        sourceGenerator.write(ifPredicatePrimitive2, context, element);

        ClassElement predicateClass = context.getClassElement(Predicate.class).orElseThrow();
        MethodElement predicateTestMethod = predicateClass.findMethod("test").orElseThrow();

        ClassTypeDef.Parameterized predicateClassDef = (ClassTypeDef.Parameterized) ClassTypeDef.of(predicateClass);
        ClassDef predicateGeneric = ClassDef.builder(element.getPackageName() + ".IfPredicateGeneric")
            .addSuperinterface(TypeDef.parameterized(predicateClassDef, TypeDef.of(Integer.class)))
            .addMethod(MethodDef.override(predicateTestMethod, Map.of("T", TypeDef.of(Integer.class)))
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning())
            )
            .build();

        sourceGenerator.write(predicateGeneric, context, element);

        ClassDef predicateGeneric2 = ClassDef.builder(element.getPackageName() + ".IfPredicateGeneric2")
            .addSuperinterface(predicateClassDef)
            .addMethod(MethodDef.override(predicateTestMethod)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning())
            )
            .build();

        sourceGenerator.write(predicateGeneric2, context, element);

        ClassDef predicateGeneric3 = ClassDef.builder(element.getPackageName() + ".IfPredicateGeneric3")
            .addSuperinterface(
                TypeDef.parameterized(predicateClassDef.rawType(), TypeDef.variable("T", TypeDef.OBJECT))
            )

            .addMethod(MethodDef.override(predicateTestMethod)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning())
            )
            .build();

        sourceGenerator.write(predicateGeneric3, context, element);

        ClassElement resolvedPredicateType = predicateClass.withTypeArguments(
            List.of(context.getClassElement(String.class).get())
        );
        ClassDef predicateGeneric4 = ClassDef.builder(element.getPackageName() + ".IfPredicateGeneric4")
            .addSuperinterface(
                TypeDef.of(resolvedPredicateType)
            )
            .addMethod(MethodDef.overrideGeneric(resolvedPredicateType.findMethod("test").get())
                .build((aThis, methodParameters) ->
                    methodParameters.get(0)
                        .invoke(
                            ReflectionUtils.getRequiredMethod(String.class, "contains", CharSequence.class),
                            ExpressionDef.constant("oob")
                        )
                        .returning())
            )
            .build();

        sourceGenerator.write(predicateGeneric4, context, element);
    }

}
