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
import io.micronaut.sourcegen.custom.example.GenerateLambda;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
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
import java.util.Map;
import java.util.function.Function;

@Internal
public final class GenerateLambdaVisitor implements TypeElementVisitor<GenerateLambda, Object> {

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

        String packageName = element.getPackageName();
        Spec result = getSpec(packageName, context);

        sourceGenerator.write(result.lambdaType(), context, element);
        sourceGenerator.write(result.theClass(), context, element);
    }

    public static Spec getSpec(String packageName) {
        return getSpec(packageName, null);
    }

    private static Spec getSpec(String packageName, VisitorContext context) {
        InterfaceDef lambdaType = createLambdaType(packageName + ".StringFunction");
        String className = packageName + ".MyClassWithLambda";

        FieldDef field = FieldDef.builder("name").ofType(TypeDef.STRING).build();
        MethodDef methodInvokerDef = MethodDef.builder("methodInvoker")
            .addParameter(TypeDef.parameterized(Function.class, String.class, String.class))
            .addParameter(String.class)
            .returns(String.class)
            .build((aThis, methodParameters) -> methodParameters.get(0)
                .invoke(
                    "apply",
                    List.of(TypeDef.OBJECT),
                    TypeDef.OBJECT,
                    List.of(methodParameters.get(1))
                ).returning());
        ClassDef.ClassDefBuilder classDefBuilder = ClassDef.builder(className)
            .addModifiers(Modifier.PUBLIC)
            .addField(field)
            .addMethod(methodInvokerDef)
            .addMethod(MethodDef.builder("toString").returns(TypeDef.STRING).addModifiers(Modifier.PUBLIC)
                .build((t, params) -> ExpressionDef.constant("MyClass").returning()))
            .addMethod(createStatelessLambda(lambdaType))
            .addMethod(createStatefulLambda(lambdaType, field))
            .addMethod(createGenericLambda())
            .addMethod(createGenericLambda2(methodInvokerDef));
        if (context != null) {
            classDefBuilder.addMethod(createGenericLambdaAst(context));
        }
        ClassDef theClass = classDefBuilder
            .build();
        return new Spec(lambdaType, theClass);
    }

    public record Spec(InterfaceDef lambdaType, ClassDef theClass) {
    }

    private static InterfaceDef createLambdaType(String className) {
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

    private static MethodDef createStatelessLambda(ObjectDef lambdaType) {
        Local function = new Local("function", lambdaType.asTypeDef());
        Lambda lambda = lambdaType.asTypeDef()
            .getLambdaMethod()
            .implement((t, params) ->
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

    private static MethodDef createStatefulLambda(ObjectDef lambdaType, FieldDef field) {
        Local constant = new Local("constant", TypeDef.STRING);
        Local function = new Local("function", lambdaType.asTypeDef());
        Lambda lambda = lambdaType.asTypeDef()
            .getLambdaMethod()
            .implement((t, params) -> constant.invoke("concat", TypeDef.STRING,
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
    private static MethodDef createGenericLambda() {
        Local constant = new Local("constant", TypeDef.STRING);
        Local function = new Local("function", TypeDef.parameterized(Function.class, String.class, String.class));

        MethodDef target = MethodDef.builder("apply")
            .addParameters(TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .addModifiers(Modifier.ABSTRACT)
            .build();
        Lambda lambda = new Lambda(
            ClassTypeDef.of(Function.class),
            target,
            MethodDef.builder("apply")
                .addParameters(TypeDef.STRING)
                .returns(TypeDef.STRING)
                .addModifiers(Modifier.ABSTRACT)
                .build((t, params) -> constant.invoke("concat", TypeDef.STRING,
                    params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1))
                ).returning())
        );

        return MethodDef.builder("callGenericLambda")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                constant.defineAndAssign(ExpressionDef.constant("prefix_")),
                function.defineAndAssign(lambda),
                function.invoke(lambda, params.get(0)).returning()
            ));
    }


    // callGenericLambda2(String input) {
    //    String constant = "prefix_"
    //    return (arg0) -> constant.concat(arg0.substring(1)).apply(input);
    // }
    private static MethodDef createGenericLambda2(MethodDef methodInvokerDef) {
        TypeDef.TypeVariable tVar = TypeDef.variable("T");
        TypeDef.TypeVariable rVar = TypeDef.variable("R");
        InterfaceDef funcDef = InterfaceDef.builder(Function.class.getName())
            .addTypeVariable(tVar)
            .addTypeVariable(rVar)
            .addMethod(
                MethodDef.builder("apply")
                    .addParameters(tVar)
                    .returns(rVar)
                    .addModifiers(Modifier.ABSTRACT)
                    .build()
            ).build();

        Local constant = new Local("constant", TypeDef.STRING);
        ClassTypeDef funcType = funcDef.asTypeDef();

        Lambda lambda = funcType.getLambdaMethod()
            .implement(Map.of("T", TypeDef.STRING, "R", TypeDef.STRING), (aThis, params) -> constant.invoke("concat", TypeDef.STRING,
                params.get(0).invoke("substring", TypeDef.STRING, ExpressionDef.constant(1))
            ).returning());

        return MethodDef.builder("callGenericLambda2")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                constant.defineAndAssign(ExpressionDef.constant("prefix_")),
                t.invoke(methodInvokerDef, lambda, params.get(0)).returning()
            ));
    }

    // callGenericLambdaAst(String input) {
    //    String constant = "prefix_"
    //    Function<String, String> function = (arg0) -> constant.concat(arg0.substring(1));
    //    return function.apply(input);
    // }
    private static MethodDef createGenericLambdaAst(VisitorContext context) {
        ClassElement functionType = context.getClassElement(Function.class).orElseThrow();

        Map<String, TypeDef> resolvedVariables = Map.of("T", TypeDef.STRING, "R", TypeDef.STRING);

        Local constant = new Local("constant", TypeDef.STRING);
        Local function = new Local("function", TypeDef.of(functionType).resolveTypeVariables(resolvedVariables));

        Lambda lambda = ClassTypeDef.of(functionType)
            .getLambdaMethod()
            .implement(
                resolvedVariables,
                (t, params) ->
                    constant.invoke("concat", TypeDef.STRING, params.get(0)
                            .invoke("substring", TypeDef.STRING, ExpressionDef.constant(1)))
                        .returning()
            );

        return MethodDef.builder("callGenericLambdaAst")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .addParameter("input", TypeDef.STRING)
            .build((t, params) -> StatementDef.multi(
                constant.defineAndAssign(ExpressionDef.constant("prefix_")),
                function.defineAndAssign(lambda),
                function.invoke("apply", List.of(TypeDef.OBJECT), TypeDef.OBJECT, List.of(params.get(0)))
                    .cast(TypeDef.STRING).returning()
            ));
    }
}
