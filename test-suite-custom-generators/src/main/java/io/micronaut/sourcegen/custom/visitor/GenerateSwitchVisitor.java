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
import io.micronaut.sourcegen.custom.example.GenerateSwitch;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Internal
public final class GenerateSwitchVisitor implements TypeElementVisitor<GenerateSwitch, Object> {

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

        TypeDef resultType1 = TypeDef.of(Integer.class);
        ClassDef switch1Def = ClassDef.builder(element.getPackageName() + ".Switch1")
            .addMethod(MethodDef.builder("test").addParameter("param", String.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType1)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asExpressionSwitch(
                        resultType1,
                        Map.of(
                            ExpressionDef.constant("abc"), ExpressionDef.constant(1),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(2),
                            ExpressionDef.nullValue(), ExpressionDef.constant(3)
                        )
                    ).returning()
                ))
            .build();

        sourceGenerator.write(switch1Def, context, element);

        TypeDef resultType2 = TypeDef.of(TimeUnit.class);
        ClassDef switch2Def = ClassDef.builder(element.getPackageName() + ".Switch2")
            .addMethod(MethodDef.builder("test").addParameter("param", String.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType2)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asExpressionSwitch(
                        resultType2,
                        Map.of(
                            ExpressionDef.constant("abc"), ExpressionDef.constant(TimeUnit.SECONDS),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(TimeUnit.MINUTES),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS)
                        )
                    ).returning()
                ))
            .build();

        sourceGenerator.write(switch2Def, context, element);

        TypeDef resultType3 = TypeDef.of(TimeUnit.class);
        ClassDef switch3Def = ClassDef.builder(element.getPackageName() + ".Switch3")
            .addMethod(MethodDef.builder("test")
                .addParameter("param1", String.class)
                .addParameter("param2", int.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType3)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asExpressionSwitch(
                        resultType3,
                        Map.of(
                            ExpressionDef.constant("abc"), parameterDefs.get(1).asExpressionSwitch(
                                resultType3,
                                Map.of(
                                    ExpressionDef.constant(1), ExpressionDef.constant(TimeUnit.MILLISECONDS),
                                    ExpressionDef.constant(2), ExpressionDef.constant(TimeUnit.NANOSECONDS),
                                    ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.MICROSECONDS)
                                )
                            ),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(TimeUnit.MINUTES),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS)
                        )
                    ).returning()
                ))
            .build();

        sourceGenerator.write(switch3Def, context, element);

        TypeDef.Primitive intType = TypeDef.Primitive.INT;
        ClassDef switch4Def = ClassDef.builder(element.getPackageName() + ".Switch4")
            .addMethod(MethodDef.builder("test").addParameter("param", String.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(intType)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asStatementSwitch(
                        intType,
                        Map.of(
                            ExpressionDef.constant("abc"), intType.constant(1).returning(),
                            ExpressionDef.constant("xyz"), intType.constant(2).returning(),
                            ExpressionDef.nullValue(), intType.constant(3).returning()
                        )
                    )
                ))
            .build();

        sourceGenerator.write(switch4Def, context, element);

        TypeDef resultType5 = TypeDef.of(TimeUnit.class);
        ClassDef switch5Def = ClassDef.builder(element.getPackageName() + ".Switch5")
            .addMethod(MethodDef.builder("test").addParameter("param", String.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType5)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asStatementSwitch(
                        resultType5,
                        Map.of(
                            ExpressionDef.constant("abc"), ExpressionDef.constant(TimeUnit.SECONDS).returning(),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(TimeUnit.MINUTES).returning(),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS).returning()
                        )
                    )
                ))
            .build();

        sourceGenerator.write(switch5Def, context, element);

        TypeDef resultType6 = TypeDef.of(TimeUnit.class);
        ClassDef switch6Def = ClassDef.builder(element.getPackageName() + ".Switch6")
            .addMethod(MethodDef.builder("test")
                .addParameter("param1", String.class)
                .addParameter("param2", int.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType6)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asStatementSwitch(
                        resultType6,
                        Map.of(
                            ExpressionDef.constant("abc"), parameterDefs.get(1).asStatementSwitch(
                                resultType6,
                                Map.of(
                                    ExpressionDef.constant(1), ExpressionDef.constant(TimeUnit.MILLISECONDS).returning(),
                                    ExpressionDef.constant(2), ExpressionDef.constant(TimeUnit.NANOSECONDS).returning(),
                                    ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.MICROSECONDS).returning()
                                )
                            ),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(TimeUnit.MINUTES).returning(),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS).returning()
                        )
                    )
                ))
            .build();

        sourceGenerator.write(switch6Def, context, element);

        TypeDef resultType7 = TypeDef.of(TimeUnit.class);
        ClassDef switch7Def = ClassDef.builder(element.getPackageName() + ".Switch7")
            .addMethod(MethodDef.builder("test")
                .addParameter("param1", String.class)
                .addParameter("param2", int.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType7)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asExpressionSwitch(
                        resultType7,
                        Map.of(
                            ExpressionDef.constant("abc"), parameterDefs.get(1).asExpressionSwitch(
                                resultType7,
                                Map.of(
                                    ExpressionDef.constant(1), ExpressionDef.constant(TimeUnit.MILLISECONDS),
                                    ExpressionDef.constant(2), new ExpressionDef.SwitchYieldCase(
                                        resultType7,
                                        ExpressionDef.constant(TimeUnit.NANOSECONDS).returning()
                                    ),
                                    ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.MICROSECONDS)
                                )
                            ),
                            ExpressionDef.constant("xyz"), ExpressionDef.constant(TimeUnit.MINUTES),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS)
                        )
                    ).returning()
                ))
            .build();

        sourceGenerator.write(switch7Def, context, element);

        TypeDef resultType8 = TypeDef.of(TimeUnit.class);
        ClassDef switch8Def = ClassDef.builder(element.getPackageName() + ".Switch8")
            .addMethod(MethodDef.builder("test").addParameter("param", String.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(resultType8)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asExpressionSwitch(
                        resultType8,
                        Map.of(
                            ExpressionDef.constant("abc"), ExpressionDef.constant(TimeUnit.SECONDS),
                            ExpressionDef.constant("xyz"), new ExpressionDef.SwitchYieldCase(
                                resultType8,
                                ExpressionDef.constant(TimeUnit.MINUTES).returning()
                            ),
                            ExpressionDef.nullValue(), ExpressionDef.constant(TimeUnit.HOURS)
                        )
                    ).returning()
                ))
            .build();

        sourceGenerator.write(switch8Def, context, element);

        ClassDef switch9Def = ClassDef.builder(element.getPackageName() + ".Switch9")
            .addMethod(MethodDef.builder("test").addParameter("param", intType)
                .addModifiers(Modifier.PUBLIC)
                .returns(intType)
                .build((self, parameterDefs) ->
                    parameterDefs.get(0).asStatementSwitch(
                        intType,
                        Map.of(
                            intType.constant(111), intType.constant(1).returning(),
                            intType.constant(222), intType.constant(2).returning(),
                            ExpressionDef.nullValue(), intType.constant(3).returning()
                        )
                    )
                ))
            .build();

        sourceGenerator.write(switch9Def, context, element);
    }

}
