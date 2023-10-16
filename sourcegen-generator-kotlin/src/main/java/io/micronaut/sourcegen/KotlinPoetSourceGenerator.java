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

import com.squareup.kotlinpoet.AnnotationSpec;
import com.squareup.kotlinpoet.ClassName;
import com.squareup.kotlinpoet.FileSpec;
import com.squareup.kotlinpoet.FunSpec;
import com.squareup.kotlinpoet.KModifier;
import com.squareup.kotlinpoet.ParameterSpec;
import com.squareup.kotlinpoet.ParameterizedTypeName;
import com.squareup.kotlinpoet.PropertySpec;
import com.squareup.kotlinpoet.TypeName;
import com.squareup.kotlinpoet.TypeSpec;
import com.squareup.kotlinpoet.WildcardTypeName;
import com.squareup.kotlinpoet.javapoet.J2kInteropKt;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDefinition;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.Character.isISOControl;

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
    public void write(ObjectDefinition objectDefinition, Writer writer) throws IOException {
        if (objectDefinition instanceof ClassDef classDef) {
            TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
            classBuilder.addModifiers(asKModifiers(classDef.getModifiers()));
            TypeSpec.Builder companionBuilder = null;
            List<PropertyDef> notNullProperties = new ArrayList<>();
            for (PropertyDef property : classDef.getProperties()) {
                PropertySpec propertySpec;
                if (property.getType().isNullable()) {
                    propertySpec = buildNullableProperty(
                        property.getName(),
                        property.getType().makeNullable(),
                        property.getModifiers(),
                        property.getAnnotations()
                    );
                } else {
                    propertySpec = buildNotNullProperty(
                        property.getName(),
                        property.getType(),
                        property.getModifiers(),
                        property.getAnnotations()
                    );
                    notNullProperties.add(property);
                }
                classBuilder.addProperty(
                    propertySpec
                );
            }
            if (!notNullProperties.isEmpty()) {
                classBuilder.setPrimaryConstructor$kotlinpoet(
                    FunSpec.constructorBuilder().addModifiers(KModifier.PUBLIC).addParameters(
                        notNullProperties.stream().map(prop -> ParameterSpec.builder(prop.getName(), asType(prop.getType())).build()).toList()
                    ).build()
                );
            }
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
        } else if (objectDefinition instanceof InterfaceDef interfaceDef) {
            TypeSpec.Builder classBuilder = TypeSpec.interfaceBuilder(interfaceDef.getSimpleName());
            classBuilder.addModifiers(asKModifiers(interfaceDef.getModifiers()));
            TypeSpec.Builder companionBuilder = null;
            for (PropertyDef property : interfaceDef.getProperties()) {
                PropertySpec propertySpec;
                if (property.getType().isNullable()) {
                    propertySpec = buildNullableProperty(
                        property.getName(),
                        property.getType().makeNullable(),
                        property.getModifiers(),
                        property.getAnnotations()
                    );
                } else {
                    propertySpec = buildNotNullProperty(
                        property.getName(),
                        property.getType(),
                        property.getModifiers(),
                        property.getAnnotations()
                    );
                }
                classBuilder.addProperty(
                    propertySpec
                );
            }
            for (MethodDef method : interfaceDef.getMethods()) {
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
                        buildFunction(interfaceDef, method, modifiers)
                    );
                }
            }
            if (companionBuilder != null) {
                classBuilder.addType(companionBuilder.build());
            }
            FileSpec.builder(interfaceDef.getPackageName(), interfaceDef.getSimpleName() + ".kt")
                .addType(classBuilder.build())
                .build()
                .writeTo(writer);
        } else {
            throw new IllegalStateException("Unknown object definition: " + objectDefinition);
        }
    }

    private static PropertySpec buildNullableProperty(String name,
                                                      TypeDef typeDef,
                                                      Set<Modifier> modifiers,
                                                      List<AnnotationDef> annotations) {
        PropertySpec.Builder propertyBuilder = PropertySpec.builder(
            name,
            asType(typeDef),
            asKModifiers(modifiers)
        );
        if (!modifiers.contains(Modifier.FINAL)) {
            propertyBuilder.setMutable$kotlinpoet(true);
        }
        for (AnnotationDef annotation : annotations) {
            propertyBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        return propertyBuilder
            .initializer("null").build();
    }

    private static PropertySpec buildNotNullProperty(String name,
                                                     TypeDef typeDef,
                                                     Set<Modifier> modifiers,
                                                     List<AnnotationDef> annotations) {
        PropertySpec.Builder propertyBuilder = PropertySpec.builder(
            name,
            asType(typeDef),
            asKModifiers(modifiers)
        );
        if (!modifiers.contains(Modifier.FINAL)) {
            propertyBuilder.setMutable$kotlinpoet(true);
        }
        for (AnnotationDef annotation : annotations) {
            propertyBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        return propertyBuilder
            .initializer(name)
            .build();
    }

    private static PropertySpec buildProperty(FieldDef field, Set<Modifier> modifiers) {
        return buildNullableProperty(field.getName(), field.getType(), modifiers, field.getAnnotations());
    }

    private static Set<Modifier> stripStatic(Set<Modifier> modifiers) {
        modifiers = new HashSet<>(modifiers);
        modifiers.remove(Modifier.STATIC);
        return modifiers;
    }

    private static FunSpec buildFunction(ObjectDefinition objectDefinition, MethodDef method, Set<Modifier> modifiers) {
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
        for (AnnotationDef annotation : method.getAnnotations()) {
            funBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        method.getStatements().stream()
            .map(st -> renderStatement(objectDefinition, method, st))
            .forEach(funBuilder::addStatement);
        return funBuilder.build();
    }

    private static TypeName asType(TypeDef typeDef) {
        TypeName result;
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            result = ParameterizedTypeName.get(
                asClassName(parameterized.rawType()),
                parameterized.typeArguments().stream().map(KotlinPoetSourceGenerator::asType).toArray(TypeName[]::new)
            );
        } else if (typeDef instanceof TypeDef.PrimitiveType primitiveType) {
            result = switch (primitiveType.name()) {
                case "void" -> TypeName.Companion.get$kotlinpoet(Void.TYPE, Collections.emptyMap());
                case "byte" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BYTE);
                case "short" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.SHORT);
                case "char" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.CHAR);
                case "int" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.INT);
                case "long" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.LONG);
                case "float" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.FLOAT);
                case "double" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.DOUBLE);
                case "boolean" -> J2kInteropKt.toKTypeName(com.squareup.javapoet.TypeName.BOOLEAN);
                default ->
                    throw new IllegalStateException("Unrecognized primitive name: " + primitiveType.name());
            };
        } else if (typeDef instanceof ClassTypeDef classType) {
            result = asClassName(classType);
        } else if (typeDef instanceof TypeDef.WildcardTypeDef wildcardTypeDef) {
            if (!wildcardTypeDef.lowerBounds().isEmpty()) {
                result = WildcardTypeName.consumerOf(
                    asType(
                        wildcardTypeDef.lowerBounds().get(0)
                    )
                );
            } else {
                result = WildcardTypeName.producerOf(
                    asType(
                        wildcardTypeDef.upperBounds().get(0)
                    )
                );
            }
        } else {
            throw new IllegalStateException("Unrecognized type definition " + typeDef);
        }
        if (typeDef.isNullable()) {
            return asNullable(result);
        }
        return result;
    }

    @NotNull
    private static ClassName asClassName(ClassTypeDef classType) {
        String packageName = classType.getPackageName();
        String simpleName = classType.getSimpleName();
        ClassName result = J2kInteropKt.toKClassName(com.squareup.javapoet.ClassName.get(packageName, simpleName));
        if (result.isNullable()) {
            return (ClassName) asNullable(result);
        }
        return result;
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

    private static String renderStatement(@Nullable ObjectDefinition objectDefinition, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            ExpResult expResult = renderExpression(objectDefinition, methodDef, aReturn.expression());
            return "return " + renderWithNotNullAssertion(expResult, methodDef.getReturnType());
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            ExpResult variableExp = renderVariable(objectDefinition, methodDef, assign.variable());
            ExpResult valueExp = renderExpression(objectDefinition, methodDef, assign.expression());
            return variableExp.rendered
                + " = " +
                renderWithNotNullAssertion(valueExp, variableExp.type);
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private static ExpResult renderExpression(@Nullable ObjectDefinition objectDefinition, MethodDef methodDef, ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            return new ExpResult(
                newInstance.type().getName()
                    + "(" + newInstance.values()
                    .stream()
                    .map(exp -> {
                        TypeDef result = exp.type();
                        ExpResult expResult = renderExpression(objectDefinition, methodDef, exp);
                        return renderWithNotNullAssertion(expResult, result);
                    }).collect(Collectors.joining(", "))
                    + ")",
                newInstance.type()
            );
        }
        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
            ExpResult expResult = renderVariable(objectDefinition, methodDef, convertExpressionDef.variable());
            TypeDef resultType = convertExpressionDef.type();
            return new ExpResult(
                renderWithNotNullAssertion(expResult, resultType),
                resultType
            );
        }
        if (expressionDef instanceof VariableDef variableDef) {
            return renderVariable(objectDefinition, methodDef, variableDef);
        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private static ExpResult renderVariable(@Nullable ObjectDefinition objectDefinition, MethodDef methodDef, VariableDef variableDef) {
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            methodDef.getParameter(parameterVariableDef.name()); // Check if exists
            return new ExpResult(parameterVariableDef.name(), parameterVariableDef.type());
        }
        if (variableDef instanceof VariableDef.Field field) {
            if (objectDefinition == null) {
                throw new IllegalStateException("Field 'this' is not available");
            }
            if (objectDefinition instanceof ClassDef classDef) {
                classDef.getField(field.name()); // Check if exists
            } else {
                throw new IllegalStateException("Field access no supported on the object definition: " + objectDefinition);

            }
            ExpResult expResult = renderVariable(objectDefinition, methodDef, field.instanceVariable());
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
            if (objectDefinition == null) {
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

    private static AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassName.bestGuess(annotationDef.getType().getName()));
        for (Map.Entry<String, Object> e : annotationDef.getValues().entrySet()) {
            String memberName = e.getKey();
            Object value = e.getValue();
            if (value instanceof Class<?>) {
                builder = builder.addMember(memberName + " = %T::class", value);
            } else if (value instanceof Enum) {
                builder = builder.addMember(memberName + " = %T.%L", value.getClass(), ((Enum<?>) value).name());
            } else if (value instanceof String) {
                builder = builder.addMember(memberName + " = %S", value);
            } else if (value instanceof Float) {
                builder = builder.addMember(memberName + " = %Lf", value);
            } else if (value instanceof Character) {
                builder = builder.addMember(memberName + " = '%L'", characterLiteralWithoutSingleQuotes((char) value));
            } else {
                builder = builder.addMember(memberName + " = %L", value);
            }
        }
        return builder.build();
    }

    // Copy from com.squareup.javapoet.Util
    private static String characterLiteralWithoutSingleQuotes(char c) {
        // see https://docs.oracle.com/javase/specs/jls/se7/html/jls-3.html#jls-3.10.6
        return switch (c) {
            case '\b' -> "\\b"; /* \u0008: backspace (BS) */
            case '\t' -> "\\t"; /* \u0009: horizontal tab (HT) */
            case '\n' -> "\\n"; /* \u000a: linefeed (LF) */
            case '\f' -> "\\f"; /* \u000c: form feed (FF) */
            case '\r' -> "\\r"; /* \u000d: carriage return (CR) */
            case '\"' -> "\"";  /* \u0022: double quote (") */
            case '\'' -> "\\'"; /* \u0027: single quote (') */
            case '\\' -> "\\\\";  /* \u005c: backslash (\) */
            default -> isISOControl(c) ? String.format("\\u%04x", (int) c) : Character.toString(c);
        };
    }

    private record ExpResult(String rendered, TypeDef type) {
    }
}
