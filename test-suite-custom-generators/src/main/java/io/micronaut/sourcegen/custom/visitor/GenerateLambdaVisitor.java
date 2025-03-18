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
import io.micronaut.sourcegen.custom.example.GenerateLambda;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
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
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null); // <3>
        if (sourceGenerator == null) {
            return;
        }

        InterfaceDef lambdaType = createLambdaType(element.getPackageName() + ".StringFunction");
        String className = element.getPackageName() + ".MyClassWithLambda";

        FieldDef field = FieldDef.builder("name").ofType(TypeDef.STRING).build();
        ClassDef interfaceDef = ClassDef.builder(className) // <4>
            .addModifiers(Modifier.PUBLIC)
            .addField(field)
            .addMethod(MethodDef.builder("toString").returns(TypeDef.STRING).addModifiers(Modifier.PUBLIC)
                .build((t, params) -> ExpressionDef.constant("MyClass").returning()))
            .addMethod(createStatelessLambda(context, element, lambdaType))
            .addMethod(createStatefulLambda(context, element, lambdaType, field))
            .build();

        sourceGenerator.write(lambdaType, context, element);
        sourceGenerator.write(interfaceDef, context, element);
    }

    private InterfaceDef createLambdaType(String className) {
        return InterfaceDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.STRING)
                .addParameter(TypeDef.STRING)
                .build()
            )
            .build();
    }

    private MethodDef createStatelessLambda(VisitorContext context, ClassElement element, ObjectDef lambdaType) {
        Local function = new Local("function", lambdaType.asTypeDef());
        Lambda lambda = Lambda.extend(lambdaType.asTypeDef(), (t, params) ->
                params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1)).returning());

        // callLambda(String input) {
        //    StringFunction function = (arg0) -> arg0.substring(1);
        //    return function.apply(input);
        // }
        return MethodDef.builder("callLambda")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                function.defineAndAssign(lambda),
                function.invoke("apply", TypeDef.STRING, params.get(0)).returning()
            ));
    }

    private MethodDef createStatefulLambda(
            VisitorContext context, ClassElement element, ObjectDef lambdaType, FieldDef field
    ) {
        Local constant = new Local("constant", TypeDef.STRING);
        Local function = new Local("function", lambdaType.asTypeDef());
        Lambda lambda = Lambda.extend(lambdaType.asTypeDef(), (t, params) -> constant.invoke("concat", TypeDef.STRING,
            params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1))
                .invoke("concat", TypeDef.STRING, t.invoke("toString", TypeDef.STRING))
                .invoke("concat", TypeDef.STRING, t.field(field))
        ).returning());

        // callStatefulLambda(String input) {
        //    String constant = "prefix_"
        //    StringFunction function = (arg0) -> constant
        //          .concat(arg0.substring(1))
        //          .concat(this.toString())
        //          .concat(this.name);
        //    return function.apply(input);
        // }
        return MethodDef.builder("callStatefulLambda")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                constant.defineAndAssign(ExpressionDef.constant("prefix_")),
                function.defineAndAssign(lambda),
                function.invoke("apply", TypeDef.STRING, params.get(0))
                    .returning()
            ));
    }

    // callGenericLambda(String input) {
    //    String constant = "prefix_"
    //    Function<String, String> function = (arg0) -> constant.concat(arg0.substring(1));
    //    return function.apply(input);
    // }
    private MethodDef createGenericLambda(VisitorContext context, ClassElement element) {
        ClassElement stringType = context.getClassElement(String.class).get();
        ClassElement functionType = context.getClassElement(Function.class)
            .orElseThrow(() -> new ProcessingException(element, "Could not find Function type"))
            .withTypeArguments(List.of(stringType, stringType));
        Local constant = new Local("constant", TypeDef.STRING);
        Local function = new Local("function", TypeDef.of(functionType));

        Lambda lambda = Lambda.extend(
            functionType, (t, params) -> constant.invoke("concat", TypeDef.STRING,
                params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1))
            ).returning());

        return MethodDef.builder("callGenericLambda")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                constant.defineAndAssign(ExpressionDef.constant("prefix_")),
                function.defineAndAssign(lambda),
                // TODO the virtual call needs to be TypeDef.STRING instead with a cast
                function.invoke("apply", TypeDef.OBJECT, params.get(0).cast(TypeDef.OBJECT))
                    .cast(TypeDef.STRING).returning()
            ));
    }
}
