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
package io.micronaut.sourcegen;

import com.squareup.kotlinpoet.FileSpec;
import com.squareup.kotlinpoet.FunSpec;
import com.squareup.kotlinpoet.KModifier;
import com.squareup.kotlinpoet.ParameterSpec;
import com.squareup.kotlinpoet.PropertySpec;
import com.squareup.kotlinpoet.TypeName;
import com.squareup.kotlinpoet.TypeSpec;
import com.squareup.kotlinpoet.javapoet.J2kInteropKt;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kotlin source code generator.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class KotlinPoetSourceGenerator implements SourceGenerator {

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.KOTLIN;
    }

    @Override
    public void write(ClassDef classDef, Writer writer) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
        classBuilder.addModifiers(asKModifiers(classDef.getModifiers()));
        TypeSpec.Builder companionBuilder = null;
        for (FieldDef field : classDef.getFields()) {
            Set<Modifier> modifiers = field.getModifiers();
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder();
                }
                companionBuilder.addProperty(
                    buildProperty(field, stripStatic(modifiers))
                );
            } else {
                classBuilder.addProperty(
                    buildProperty(field, modifiers)
                );
            }
        }
        for (MethodDef method : classDef.getMethods()) {
            Set<Modifier> modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.STATIC)) {
                if (companionBuilder == null) {
                    companionBuilder = TypeSpec.companionObjectBuilder();
                }
                modifiers = stripStatic(modifiers);
                companionBuilder.addFunction(
                    buildFunction(null, method, modifiers)
                );
            } else {
                classBuilder.addFunction(
                    buildFunction(classDef, method, modifiers)
                );
            }
        }
        if (companionBuilder != null) {
            classBuilder.addType(companionBuilder.build());
        }
        FileSpec.builder(classDef.getPackageName(), classDef.getSimpleName() + ".kt")
            .addType(classBuilder.build())
            .build()
            .writeTo(writer);
    }

    private static PropertySpec buildProperty(FieldDef field, Set<Modifier> modifiers) {
        return PropertySpec.builder(
                field.getName(),
                asType(field.getType()),
                asKModifiers(modifiers)
            )
            .mutable(true)
            .initializer("null").build();
    }

    private static Set<Modifier> stripStatic(Set<Modifier> modifiers) {
        modifiers = new HashSet<>(modifiers);
        modifiers.remove(Modifier.STATIC);
        return modifiers;
    }

    private static FunSpec buildFunction(ClassDef classDef, MethodDef method, Set<Modifier> modifiers) {
        FunSpec.Builder funBuilder = FunSpec.builder(method.getName())
            .addModifiers(asKModifiers(modifiers))
            .returns(asType(method.getReturnType()))
            .addParameters(
                method.getParameters().stream()
                    .map(param -> ParameterSpec.builder(
                        param.getName(),
                        asType(param.getType())
                    ).build())
                    .toList()
            );
        method.getStatements().stream()
            .map(st -> renderStatement(classDef, method, st))
            .forEach(funBuilder::addStatement);
        return funBuilder.build();
    }

    private static TypeName asType(TypeDef typeDef) {
        String packageName = typeDef.getPackageName();
        String simpleName = typeDef.getSimpleName();
        if ("".equals(packageName)) {
            TypeName typeName = switch (simpleName) {
                case "void" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.VOID);
                case "byte" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BYTE);
                case "short" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.SHORT);
                case "char" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.CHAR);
                case "int" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.INT);
                case "long" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.LONG);
                case "float" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.FLOAT);
                case "double" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.DOUBLE);
                case "boolean" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BOOLEAN);
                default -> null;
            };
            if (typeName != null) {
                if (typeDef.isNullable()) {
                    typeName = asNullable(typeName);
                }
                return typeName;
            }
        }
        var kClassName = J2kInteropKt.toKClassName(com.squareup.javapoet.ClassName.get(packageName, simpleName));
        if (typeDef.isNullable()) {
            return asNullable(kClassName);
        }
        return kClassName;
    }

    private static TypeName asNullable(TypeName kClassName) {
        return kClassName.copy(true, kClassName.getAnnotations(), kClassName.getTags());
    }

    private static List<KModifier> asKModifiers(Collection<Modifier> modifier) {
        return modifier.stream().map(m -> switch (m) {
            case PUBLIC -> KModifier.PUBLIC;
            case PROTECTED -> KModifier.PROTECTED;
            case PRIVATE -> KModifier.PRIVATE;
            case ABSTRACT -> KModifier.ABSTRACT;
            case SEALED -> KModifier.SEALED;
            case FINAL -> KModifier.FINAL;
            default -> throw new IllegalStateException("Not supported modifier: " + m);
        }).toList();
    }

    private static String renderStatement(@Nullable ClassDef classDef, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            ExpResult expResult = renderExpression(classDef, methodDef, aReturn.expression());
            return "return " + renderWithNotNullAssertion(expResult, methodDef.getReturnType());
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            ExpResult variableExp = renderVariable(classDef, methodDef, assign.variable());
            ExpResult valueExp = renderExpression(classDef, methodDef, assign.expression());
            return variableExp.rendered
                + " = " +
                renderWithNotNullAssertion(valueExp, variableExp.type);
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private static ExpResult renderExpression(@Nullable ClassDef classDef, MethodDef methodDef, ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            return new ExpResult(
                newInstance.type().getTypeName()
                    + "(" + newInstance.values()
                    .stream()
                    .map(exp -> {
                        TypeDef result = exp.type();
                        ExpResult expResult = renderExpression(classDef, methodDef, exp);
                        return renderWithNotNullAssertion(expResult, result);
                    }).collect(Collectors.joining(", "))
                    + ")",
                newInstance.type()
            );
        }
        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
            ExpResult expResult = renderVariable(classDef, methodDef, convertExpressionDef.variable());
            TypeDef resultType = convertExpressionDef.type();
            return new ExpResult(
                renderWithNotNullAssertion(expResult, resultType),
                resultType
            );
        }
        if (expressionDef instanceof VariableDef variableDef) {
            return renderVariable(classDef, methodDef, variableDef);
        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private static ExpResult renderVariable(@Nullable ClassDef classDef, MethodDef methodDef, VariableDef variableDef) {
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            methodDef.getParameter(parameterVariableDef.name()); // Check if exists
            return new ExpResult(parameterVariableDef.name(), parameterVariableDef.type());
        }
        if (variableDef instanceof VariableDef.Field field) {
            classDef.getField(field.name()); // Check if exists
            ExpResult expResult = renderVariable(classDef, methodDef, field.instanceVariable());
            String rendered = expResult.rendered();
            if (expResult.type().isNullable()) {
                rendered += "!!";
            }
            return new ExpResult(
                rendered + "." + field.name(),
                field.type()
            );
        }
        if (variableDef instanceof VariableDef.This aThis) {
            if (classDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            return new ExpResult("this", aThis.type());
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

    private static String renderWithNotNullAssertion(ExpResult expResult, TypeDef result) {
        String rendered = expResult.rendered();
        if (!result.isNullable() && expResult.type().isNullable()) {
            rendered += "!!";
        }
        return rendered;
    }

    private record ExpResult(String rendered, TypeDef type) {
    }
}
