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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.ArrayTypeName;
import io.micronaut.sourcegen.javapoet.ClassName;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.javapoet.FieldSpec;
import io.micronaut.sourcegen.javapoet.JavaFile;
import io.micronaut.sourcegen.javapoet.MethodSpec;
import io.micronaut.sourcegen.javapoet.ParameterSpec;
import io.micronaut.sourcegen.javapoet.ParameterizedTypeName;
import io.micronaut.sourcegen.javapoet.TypeName;
import io.micronaut.sourcegen.javapoet.TypeSpec;
import io.micronaut.sourcegen.javapoet.TypeVariableName;
import io.micronaut.sourcegen.javapoet.Util;
import io.micronaut.sourcegen.javapoet.WildcardTypeName;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The Java source generator.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public sealed class JavaPoetSourceGenerator implements SourceGenerator permits GroovyPoetSourceGenerator {

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) throws IOException {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(writer, classDef);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(writer, recordDef);
        } else if (objectDef instanceof InterfaceDef interfaceDef) {
            writeInterface(writer, interfaceDef);
        } else if (objectDef instanceof EnumDef enumDef) {
            writeEnum(writer, enumDef);
        } else {
            throw new IllegalStateException("Unknown object definition: " + objectDef);
        }
    }

    private void writeInterface(Writer writer, InterfaceDef interfaceDef) throws IOException {
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(interfaceDef.getSimpleName());
        interfaceBuilder.addModifiers(interfaceDef.getModifiersArray());
        interfaceDef.getTypeVariables().stream().map(t -> asTypeVariable(t, interfaceDef)).forEach(interfaceBuilder::addTypeVariable);
        interfaceDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, interfaceDef)).forEach(interfaceBuilder::addSuperinterface);
        interfaceDef.getJavadoc().forEach(interfaceBuilder::addJavadoc);

        for (AnnotationDef annotation : interfaceDef.getAnnotations()) {
            interfaceBuilder.addAnnotation(asAnnotationSpec(annotation));
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            TypeName propertyType = asType(property.getType(), interfaceDef);
            String propertyName = property.getName();
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                propertyType,
                propertyName
            ).addModifiers(Modifier.PRIVATE);
            property.getJavadoc().forEach(fieldBuilder::addJavadoc);

            for (AnnotationDef annotation : property.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            interfaceBuilder.addField(
                fieldBuilder
                    .build()
            );
            String capitalizedPropertyName = NameUtils.capitalize(propertyName);
            interfaceBuilder.addMethod(MethodSpec.methodBuilder("get" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .returns(propertyType)
//                    .addStatement("return this." + propertyName)
                .build());
            interfaceBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
//                    .addStatement("this." + propertyName + " = " + propertyName)
                .build());
        }
        for (MethodDef method : interfaceDef.getMethods()) {
            interfaceBuilder.addMethod(
                asMethodSpec(interfaceDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(interfaceDef.getPackageName(), interfaceBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeEnum(Writer writer, EnumDef enumDef) throws IOException {
        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(enumDef.getSimpleName());
        enumBuilder.addModifiers(enumDef.getModifiersArray());
        enumDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, enumDef)).forEach(enumBuilder::addSuperinterface);
        enumDef.getJavadoc().forEach(enumBuilder::addJavadoc);

        for (AnnotationDef annotation : enumDef.getAnnotations()) {
            enumBuilder.addAnnotation(asAnnotationSpec(annotation));
        }

        for (String enumConstant : enumDef.getEnumConstants()) {
            enumBuilder.addEnumConstant(enumConstant);
        }

        for (MethodDef method : enumDef.getMethods()) {
            enumBuilder.addMethod(
                asMethodSpec(enumDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(enumDef.getPackageName(), enumBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeClass(Writer writer, ClassDef classDef) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(classDef.getSimpleName());
        classBuilder.addModifiers(classDef.getModifiersArray());
        classDef.getTypeVariables().stream().map(t -> asTypeVariable(t, classDef)).forEach(classBuilder::addTypeVariable);
        classDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, classDef)).forEach(classBuilder::addSuperinterface);
        classDef.getJavadoc().forEach(classBuilder::addJavadoc);
        if (classDef.getSuperclass() != null) {
            classBuilder.superclass(asType(classDef.getSuperclass(), classDef));
        }

        for (AnnotationDef annotation : classDef.getAnnotations()) {
            classBuilder.addAnnotation(asAnnotationSpec(annotation));
        }
        for (PropertyDef property : classDef.getProperties()) {
            TypeName propertyType = asType(property.getType(), classDef);
            String propertyName = property.getName();
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                propertyType,
                propertyName
            ).addModifiers(Modifier.PRIVATE);
            for (AnnotationDef annotation : property.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            property.getJavadoc().forEach(fieldBuilder::addJavadoc);
            classBuilder.addField(
                fieldBuilder
                    .build()
            );
            String capitalizedPropertyName = NameUtils.capitalize(propertyName);
            classBuilder.addMethod(MethodSpec.methodBuilder("get" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .returns(propertyType)
                .addStatement("return this." + propertyName)
                .build());
            classBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
                .addStatement("this." + propertyName + " = " + propertyName)
                .build());
        }
        for (FieldDef field : classDef.getFields()) {
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                asType(field.getType(), classDef),
                field.getName()
            ).addModifiers(field.getModifiersArray());
            field.getInitializer().ifPresent(init ->
                fieldBuilder.initializer(renderExpression(
                    null,
                    null,
                    init
                ))
            );
            field.getJavadoc().forEach(fieldBuilder::addJavadoc);
            for (AnnotationDef annotation : field.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            classBuilder.addField(
                fieldBuilder
                    .build()
            );
        }
        for (MethodDef method : classDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(classDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(classDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private void writeRecord(Writer writer, RecordDef recordDef) throws IOException {
        TypeSpec.Builder classBuilder = TypeSpec.recordBuilder(recordDef.getSimpleName());
        classBuilder.addModifiers(recordDef.getModifiersArray());
        recordDef.getTypeVariables().stream().map(t -> asTypeVariable(t, recordDef)).forEach(classBuilder::addTypeVariable);
        recordDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, recordDef)).forEach(classBuilder::addSuperinterface);
        recordDef.getJavadoc().forEach(classBuilder::addJavadoc);

        for (AnnotationDef annotation : recordDef.getAnnotations()) {
            classBuilder.addAnnotation(asAnnotationSpec(annotation));
        }
        for (PropertyDef property : recordDef.getProperties()) {
            TypeName propertyType = asType(property.getType(), recordDef);
            String propertyName = property.getName();
            ParameterSpec.Builder componentBuilder = ParameterSpec.builder(propertyType, propertyName);
            property.getJavadoc().forEach(componentBuilder::addJavadoc);
            for (AnnotationDef annotation : property.getAnnotations()) {
                componentBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            classBuilder.addRecordComponent(
                componentBuilder.build()
            );
        }
        for (MethodDef method : recordDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(recordDef, method)
            );
        }
        JavaFile javaFile = JavaFile.builder(recordDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private MethodSpec asMethodSpec(ObjectDef objectDef, MethodDef method) {
        String methodName = method.getName();
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
            .addModifiers(method.getModifiersArray())
            .addParameters(
                method.getParameters().stream()
                    .map(param -> ParameterSpec.builder(
                        asType(param.getType(), objectDef),
                        param.getName(),
                        param.getModifiersArray()
                    ).addAnnotations(param.getAnnotations().stream().map(this::asAnnotationSpec).toList()).build())
                    .toList()
            );
        if (!methodName.equals(MethodSpec.CONSTRUCTOR)) {
            methodBuilder.returns(asType(method.getReturnType(), objectDef));
        }
        method.getJavadoc().forEach(methodBuilder::addJavadoc);
        for (AnnotationDef annotation : method.getAnnotations()) {
            methodBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        method.getStatements().stream()
            .map(st -> renderStatementCodeBlock(objectDef, method, st))
            .forEach(methodBuilder::addCode);

        return methodBuilder.build();
    }

    private TypeVariableName asTypeVariable(TypeDef.TypeVariable tv, ObjectDef objectDef) {
        return TypeVariableName.get(
            tv.name(),
            tv.bounds().stream().map(t -> asType(t, objectDef)).toArray(TypeName[]::new)
        );
    }

    private AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(ClassName.bestGuess(annotationDef.getType().getName()));
        for (Map.Entry<String, Object> e : annotationDef.getValues().entrySet()) {
            addAnnotationValue(builder, e.getKey(), e.getValue());
        }
        return builder.build();
    }

    private void addAnnotationValue(AnnotationSpec.Builder builder, String memberName, Object value) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(v -> addAnnotationValue(builder, memberName, v));
        } else if (value instanceof AnnotationDef annotationValue) {
            builder.addMember(memberName, asAnnotationSpec(annotationValue));
        } else if (value instanceof VariableDef variableDef) {
            builder.addMember(memberName, renderVariable(null, null, variableDef));
        } else if (value instanceof Class<?>) {
            builder.addMember(memberName, "$T.class", value);
        } else if (value instanceof Enum) {
            builder.addMember(memberName, "$T.$L", value.getClass(), ((Enum<?>) value).name());
        } else if (value instanceof String) {
            builder.addMember(memberName, "$S", value);
        } else if (value instanceof Float) {
            builder.addMember(memberName, "$Lf", value);
        } else if (value instanceof Character) {
            builder.addMember(memberName, "'$L'", Util.characterLiteralWithoutSingleQuotes((char) value));
        } else {
            builder.addMember(memberName, "$L", value);
        }
    }

    private TypeName asType(TypeDef typeDef, ObjectDef objectDef) {
        if (typeDef.equals(TypeDef.THIS)) {
            if (objectDef == null) {
                throw new IllegalStateException("This type is used outside of the instance scope!");
            }
            return asType(objectDef.asTypeDef(), null);
        }
        if (typeDef instanceof TypeDef.Array array) {
            TypeName arrayTypeName = ArrayTypeName.of(asType(array.componentType(), objectDef));
            for (int i = 1; i < array.dimensions(); ++i) {
                arrayTypeName = ArrayTypeName.of(arrayTypeName);
            }
            return arrayTypeName;
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            return ParameterizedTypeName.get(
                asClassType(parameterized.rawType()),
                parameterized.typeArguments().stream().map(t -> asType(t, objectDef)).toArray(TypeName[]::new)
            );
        }
        if (typeDef instanceof TypeDef.Primitive primitive) {
            return switch (primitive.name()) {
                case "void" -> TypeName.VOID;
                case "byte" -> TypeName.BYTE;
                case "short" -> TypeName.SHORT;
                case "char" -> TypeName.CHAR;
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "float" -> TypeName.FLOAT;
                case "double" -> TypeName.DOUBLE;
                case "boolean" -> TypeName.BOOLEAN;
                default ->
                    throw new IllegalStateException("Unrecognized primitive name: " + primitive.name());
            };
        }
        if (typeDef instanceof ClassTypeDef classType) {
            return ClassName.bestGuess(classType.getName());
        }
        if (typeDef instanceof TypeDef.Wildcard wildcard) {
            if (!wildcard.lowerBounds().isEmpty()) {
                return WildcardTypeName.supertypeOf(
                    asType(
                        wildcard.lowerBounds().get(0),
                        objectDef
                    )
                );
            }
            return WildcardTypeName.subtypeOf(
                asType(
                    wildcard.upperBounds().get(0),
                    objectDef
                )
            );
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            return asTypeVariable(typeVariable, objectDef);
        }
        throw new IllegalStateException("Unrecognized type definition " + typeDef);
    }

    private static ClassName asClassType(ClassTypeDef classTypeDef) {
        return ClassName.bestGuess(classTypeDef.getName());
    }

    private CodeBlock renderStatement(@Nullable ObjectDef objectDef, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Throw aThrow) {
            return CodeBlock.concat(
                CodeBlock.of("throw "),
                renderExpression(objectDef, methodDef, aThrow.variableDef())
            );
        }
        if (statementDef instanceof StatementDef.Return aReturn) {
            return CodeBlock.concat(
                CodeBlock.of("return "),
                renderExpression(objectDef, methodDef, aReturn.expression())
            );
        }
        if (statementDef instanceof StatementDef.Assign assign) {
            return CodeBlock.concat(
                renderExpression(objectDef, methodDef, assign.variable()),
                CodeBlock.of(" = "),
                renderExpression(objectDef, methodDef, assign.expression())
            );
        }
        if (statementDef instanceof StatementDef.DefineAndAssign assign) {
            return CodeBlock.concat(
                CodeBlock.of("$T $L", asType(assign.variable().type(), objectDef), assign.variable().name()),
                CodeBlock.of(" = "),
                renderExpression(objectDef, methodDef, assign.expression())
            );
        }
        if (statementDef instanceof ExpressionDef expressionDef) {
            return renderExpression(objectDef, methodDef, expressionDef);
        }
        throw new IllegalStateException("Unrecognized statement: " + statementDef);
    }

    private CodeBlock renderStatementCodeBlock(@Nullable ObjectDef objectDef, MethodDef methodDef, StatementDef statementDef) {
        if (statementDef instanceof StatementDef.Multi statements) {
            CodeBlock.Builder builder = CodeBlock.builder();
            for (StatementDef statement : statements.statements()) {
                builder.add(renderStatementCodeBlock(objectDef, methodDef, statement));
            }
            return builder.build();
        }
        if (statementDef instanceof StatementDef.If ifStatement) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("if (");
            builder.add(renderExpression(objectDef, methodDef, ifStatement.condition()));
            builder.add(") {\n");
            builder.indent();
            builder.add(renderStatementCodeBlock(objectDef, methodDef, ifStatement.statement()));
            builder.unindent();
            builder.add("}\n");
            return builder.build();
        }
        if (statementDef instanceof StatementDef.IfElse ifStatement) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("if (");
            builder.add(renderExpression(objectDef, methodDef, ifStatement.condition()));
            builder.add(") {\n");
            builder.indent();
            builder.add(renderStatementCodeBlock(objectDef, methodDef, ifStatement.statement()));
            builder.unindent();
            builder.add("} else {\n");
            builder.indent();
            builder.add(renderStatementCodeBlock(objectDef, methodDef, ifStatement.elseStatement()));
            builder.unindent();
            builder.add("}\n");
            return builder.build();
        }
        if (statementDef instanceof StatementDef.Switch aSwitch) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("switch (");
            builder.add(renderExpression(objectDef, methodDef, aSwitch.expression()));
            builder.add(") {\n");
            builder.indent();
            for (Map.Entry<ExpressionDef.Constant, StatementDef> e : aSwitch.cases().entrySet()) {
                if (e.getKey() == null || e.getKey().value() == null) {
                    builder.add("default");
                } else {
                    builder.add("case ");
                    builder.add(renderConstantExpression(e.getKey()));
                }
                builder.add(": {\n");
                builder.indent();
                StatementDef statement = e.getValue();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, statement));
                builder.unindent();
                builder.add("}\n");
            }
            builder.unindent();
            builder.add("}\n");
            return builder.build();
        }
        if (statementDef instanceof StatementDef.While aWhile) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("while (");
            builder.add(renderExpression(objectDef, methodDef, aWhile.expression()));
            builder.add(") {\n");
            builder.indent();
            builder.add(renderStatementCodeBlock(objectDef, methodDef, aWhile.statement()));
            builder.unindent();
            builder.add("}\n");
            return builder.build();
        }
        return CodeBlock.builder()
            .addStatement(
                renderStatement(objectDef, methodDef, statementDef)
            ).build();
    }

    private CodeBlock renderExpression(@Nullable ObjectDef objectDef, MethodDef methodDef, ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.NewInstance newInstance) {
            return CodeBlock.concat(
                CodeBlock.of("new $L(", asType(newInstance.type(), objectDef)),
                newInstance.values()
                    .stream()
                    .map(exp -> renderExpression(objectDef, methodDef, exp))
                    .collect(CodeBlock.joining(", ")),
                CodeBlock.of(")")
            );
        }
        if (expressionDef instanceof TypeDef.Primitive.PrimitiveInstance primitiveInstance) {
            return renderExpression(objectDef, methodDef, primitiveInstance.value());
        }
        if (expressionDef instanceof ExpressionDef.NewArrayOfSize newArray) {
            return CodeBlock.of("new $T[$L]", asType(newArray.type().componentType(), objectDef), newArray.size());
        }
        if (expressionDef instanceof ExpressionDef.NewArrayInitialized newArray) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("new $T[]{", asType(newArray.type().componentType(), objectDef));
            for (Iterator<ExpressionDef> iterator = newArray.expressions().iterator(); iterator.hasNext(); ) {
                ExpressionDef expression = iterator.next();
                builder.add(renderExpression(objectDef, methodDef, expression));
                if (iterator.hasNext()) {
                    builder.add(",");
                }
            }
            builder.add("}");
            return builder.build();
        }
        if (expressionDef instanceof ExpressionDef.Convert convertExpressionDef) {
            return renderExpression(objectDef, methodDef, convertExpressionDef.expressionDef());
        }
        if (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            if (castExpressionDef.expressionDef() instanceof VariableDef variableDef) {
                return CodeBlock.concat(
                    CodeBlock.of("($T) ", asType(castExpressionDef.type(), objectDef)),
                    renderExpression(objectDef, methodDef, variableDef)
                );
            }
            return CodeBlock.concat(
                CodeBlock.of("($T) (", asType(castExpressionDef.type(), objectDef)),
                renderExpression(objectDef, methodDef, castExpressionDef.expressionDef()),
                CodeBlock.of(")")
            );
        }
        if (expressionDef instanceof ExpressionDef.Constant constant) {
            return renderConstantExpression(constant);
        }
        if (expressionDef instanceof ExpressionDef.CallInstanceMethod callInstanceMethod) {
            return CodeBlock.concat(
                CodeBlock.of(renderExpression(objectDef, methodDef, callInstanceMethod.instance())
                    + "." + callInstanceMethod.name()
                    + "("),
                callInstanceMethod.parameters()
                    .stream()
                    .map(exp -> renderExpression(objectDef, methodDef, exp))
                    .collect(CodeBlock.joining(", ")),
                CodeBlock.of(")")
            );
        }
        if (expressionDef instanceof ExpressionDef.CallStaticMethod staticMethod) {
            return CodeBlock.concat(
                CodeBlock.of("$T." + staticMethod.name() + "(", asType(staticMethod.classDef(), objectDef)),
                staticMethod.parameters()
                    .stream()
                    .map(exp -> renderExpression(objectDef, methodDef, exp))
                    .collect(CodeBlock.joining(", ")),
                CodeBlock.of(")")
            );
        }
        if (expressionDef instanceof ExpressionDef.GetPropertyValue getPropertyValue) {
            PropertyElement propertyElement = getPropertyValue.propertyElement();
            Optional<? extends MemberElement> readPropertyMember = propertyElement.getReadMember();
            if (readPropertyMember.isEmpty()) {
                throw new IllegalStateException("Read member not found for property: " + propertyElement);
            }
            MemberElement memberElement = readPropertyMember.get();
            if (memberElement instanceof MethodElement methodElement) {
                return renderExpression(objectDef, methodDef, getPropertyValue.instance().invoke(methodElement));
            }
            if (memberElement instanceof FieldElement fieldElement) {
                // TODO: support field
            }
            throw new IllegalStateException("Unrecognized property read element: " + propertyElement);
        }
        if (expressionDef instanceof ExpressionDef.IsNull isNull) {
            return renderExpression(objectDef, methodDef, new ExpressionDef.Condition("==", isNull.expression(), ExpressionDef.nullValue()));
        }
        if (expressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
            return renderExpression(objectDef, methodDef, new ExpressionDef.Condition("!=", isNotNull.expression(), ExpressionDef.nullValue()));
        }
        if (expressionDef instanceof ExpressionDef.Condition condition) {
            return CodeBlock.concat(
                renderCondition(objectDef, methodDef, condition.left()),
                CodeBlock.of(condition.operator()),
                renderCondition(objectDef, methodDef, condition.right())
            );
        }
        if (expressionDef instanceof ExpressionDef.And andExpressionDef) {
            return CodeBlock.concat(
                renderCondition(objectDef, methodDef, andExpressionDef.left()),
                CodeBlock.of(" && "),
                renderCondition(objectDef, methodDef, andExpressionDef.right())
            );
        }
        if (expressionDef instanceof ExpressionDef.Or orExpressionDef) {
            return CodeBlock.concat(
                renderCondition(objectDef, methodDef, orExpressionDef.left()),
                CodeBlock.of(" || "),
                renderCondition(objectDef, methodDef, orExpressionDef.right())
            );
        }
        if (expressionDef instanceof ExpressionDef.IfElse condition) {
            return CodeBlock.concat(
                renderExpression(objectDef, methodDef, condition.condition()),
                CodeBlock.of(" ? "),
                renderExpression(objectDef, methodDef, condition.expression()),
                CodeBlock.of(" : "),
                renderExpression(objectDef, methodDef, condition.elseExpression())
            );
        }
        if (expressionDef instanceof ExpressionDef.Switch aSwitch) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("switch (");
            builder.add(renderExpression(objectDef, methodDef, aSwitch.expression()));
            builder.add(") {\n");
            builder.indent();
            for (Map.Entry<ExpressionDef.Constant, ExpressionDef> e : aSwitch.cases().entrySet()) {
                if (e.getKey() == null || e.getKey().value() == null) {
                    builder.add("default");
                } else {
                    builder.add("case ");
                    builder.add(renderConstantExpression(e.getKey()));
                }
                builder.add(" -> ");
                ExpressionDef value = e.getValue();
                builder.add(renderExpression(objectDef, methodDef, value));
                if (value instanceof ExpressionDef.SwitchYieldCase) {
                    builder.add("\n");
                } else {
                    builder.add(";\n");
                }
            }
            builder.unindent();
            builder.add("}");
            return builder.build();
        }
        if (expressionDef instanceof ExpressionDef.SwitchYieldCase switchYieldCase) {
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.add("{\n");
            builder.indent();
            StatementDef statement = switchYieldCase.statement();
            List<StatementDef> flatten = statement.flatten();
            if (flatten.isEmpty()) {
                throw new IllegalStateException("SwitchYieldCase did not return any statements");
            }
            StatementDef last = flatten.get(flatten.size() - 1);
            List<StatementDef> rest = flatten.subList(0, flatten.size() - 1);
            for (StatementDef statementDef : rest) {
                builder.add(renderStatementCodeBlock(objectDef, methodDef, statementDef));
            }
            renderYield(builder, methodDef, last, objectDef);
            builder.unindent();
            builder.add("}");
            String str = builder.build().toString();
            // Render the body to prevent nested statements
            return CodeBlock.of(str);
        }
        if (expressionDef instanceof VariableDef variableDef) {
            return renderVariable(objectDef, methodDef, variableDef);
        }
        if (expressionDef instanceof ExpressionDef.InvokeGetClassMethod invokeGetClassMethod) {
            return renderExpression(objectDef, methodDef, invokeGetClassMethod.instance().invoke("getClass", TypeDef.of(Class.class)));
        }
        if (expressionDef instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
            ExpressionDef instance = invokeHashCodeMethod.instance();
            TypeDef type = instance.type();
            if (type.isArray()) {
                if (type instanceof TypeDef.Array array && array.dimensions() > 1) {
                    return renderExpression(
                        objectDef,
                        methodDef,
                        ClassTypeDef.of(Arrays.class)
                            .invokeStatic(
                                "deepHashCode",
                                TypeDef.Primitive.BOOLEAN,
                                invokeHashCodeMethod.instance()
                            ));
                }
                return renderExpression(
                    objectDef,
                    methodDef,
                    ClassTypeDef.of(Arrays.class)
                        .invokeStatic(
                            "hashCode",
                            TypeDef.Primitive.BOOLEAN,
                            invokeHashCodeMethod.instance()
                        ));
            }
            if (type instanceof TypeDef.Primitive primitive) {
                return renderExpression(
                    objectDef,
                    methodDef,
                    primitive.wrapperType()
                        .invokeStatic(
                            "hashCode",
                            TypeDef.Primitive.BOOLEAN,
                            invokeHashCodeMethod.instance()
                        ));
            }
            return renderExpression(objectDef, methodDef, instance.isNull().asConditionIfElse(
                ExpressionDef.constant(0),
                instance.invoke("hashCode", TypeDef.Primitive.BOOLEAN))
            );
        }
        if (expressionDef instanceof ExpressionDef.EqualsStructurally equalsStructurally) {
            var type = equalsStructurally.instance().type();
            if (type.isArray()) {
                if (type instanceof TypeDef.Array array && array.dimensions() > 1) {
                    return renderExpression(
                        objectDef,
                        methodDef,
                        ClassTypeDef.of(Arrays.class)
                            .invokeStatic(
                                "deepEquals",
                                TypeDef.Primitive.BOOLEAN,
                                equalsStructurally.instance(),
                                equalsStructurally.other())
                    );
                }
                return renderExpression(
                    objectDef,
                    methodDef,
                    ClassTypeDef.of(Arrays.class)
                        .invokeStatic(
                            "equals",
                            TypeDef.Primitive.BOOLEAN,
                            equalsStructurally.instance(),
                            equalsStructurally.other())
                );
            }
            return renderExpression(
                objectDef,
                methodDef,
                equalsStructurally.instance().invoke("equals", TypeDef.Primitive.BOOLEAN, equalsStructurally.other())
            );
        }
        if (expressionDef instanceof ExpressionDef.EqualsReferentially equalsReferentially) {
            return CodeBlock.builder()
                .add(renderExpression(objectDef, methodDef, equalsReferentially.instance()))
                .add(" == ")
                .add(renderExpression(objectDef, methodDef, equalsReferentially.other()))
                .build();
        }
        throw new IllegalStateException("Unrecognized expression: " + expressionDef);
    }

    private CodeBlock renderCondition(@Nullable ObjectDef objectDef, MethodDef methodDef, ExpressionDef expressionDef) {
        var rendered = renderExpression(objectDef, methodDef, expressionDef);
        if (expressionDef instanceof StatementDef || expressionDef instanceof VariableDef || expressionDef instanceof ExpressionDef.And || expressionDef instanceof ExpressionDef.Constant) {
            return rendered;
        }
        return CodeBlock.concat(
            CodeBlock.of("("),
            rendered,
            CodeBlock.of(")")
        );
    }

    private void renderYield(CodeBlock.Builder builder, MethodDef methodDef, StatementDef statementDef, ObjectDef objectDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            builder.addStatement(
                CodeBlock.concat(
                    CodeBlock.of("yield "),
                    renderExpression(objectDef, methodDef, aReturn.expression())
                )
            );
        } else {
            throw new IllegalStateException("The last statement of SwitchYieldCase should be a return. Found: " + statementDef);
        }
    }

    private CodeBlock renderConstantExpression(ExpressionDef.Constant constant) {
        TypeDef type = constant.type();
        Object value = constant.value();
        if (value == null) {
            return CodeBlock.of("null");
        }
        if (type instanceof ClassTypeDef classTypeDef && classTypeDef.isEnum()) {
            return renderExpression(null, null, new VariableDef.StaticField(
                type,
                value instanceof Enum<?> anEnum ? anEnum.name() : value.toString(),
                type
            ));
        }
        if (type instanceof TypeDef.Primitive primitive) {
            return switch (primitive.name()) {
                case "long" -> CodeBlock.of(value + "l");
                case "float" -> CodeBlock.of(value + "f");
                case "double" -> CodeBlock.of(value + "d");
                default -> CodeBlock.of("$L", value);
            };
        } else if (type instanceof TypeDef.Array arrayDef) {
            if (value.getClass().isArray()) {
                final var array = value;
                final var values = IntStream.range(0, Array.getLength(array))
                    .mapToObj(i -> renderConstantExpression(new ExpressionDef.Constant(arrayDef.componentType(), Array.get(array, i))))
                    .collect(CodeBlock.joining(", "));
                final String typeName;
                if (arrayDef.componentType() instanceof ClassTypeDef arrayClassTypeDef) {
                    typeName = arrayClassTypeDef.getSimpleName();
                } else if (arrayDef.componentType() instanceof TypeDef.Primitive arrayPrimitive) {
                    typeName = arrayPrimitive.name();
                } else {
                    throw new IllegalStateException("Unrecognized expression: " + constant);
                }
                return CodeBlock.concat(
                    CodeBlock.of("new $N[] {", typeName),
                    values,
                    CodeBlock.of("}"));
            }
        } else if (type instanceof ClassTypeDef classTypeDef) {
            String name = classTypeDef.getName();
            if (ClassUtils.isJavaLangType(name)) {
                return switch (name) {
                    case "java.lang.Long" -> CodeBlock.of(value + "l");
                    case "java.lang.Float" -> CodeBlock.of(value + "f");
                    case "java.lang.Double" -> CodeBlock.of(value + "d");
                    case "java.lang.String" -> CodeBlock.of("$S", value);
                    default -> CodeBlock.of("$L", value);
                };
            } else {
                return CodeBlock.of("$L", value);
            }
        }
        throw new IllegalStateException("Unrecognized expression: " + constant);
    }

    private CodeBlock renderVariable(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, VariableDef variableDef) {
        if (variableDef instanceof VariableDef.Local localVariableDef) {
            return CodeBlock.of(localVariableDef.name());
        }
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            if (methodDef == null) {
                throw new IllegalStateException("Accessing method parameters is not available");
            }
            methodDef.getParameter(parameterVariableDef.name()); // Check if exists
            return CodeBlock.of(parameterVariableDef.name());
        }
        if (variableDef instanceof VariableDef.StaticField staticField) {
            return CodeBlock.of("$T.$L", asType(staticField.ownerType(), objectDef), staticField.name());
        }
        if (variableDef instanceof VariableDef.Field field) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            if (objectDef instanceof ClassDef classDef) {
                if (!classDef.hasField(field.name())) {
                    throw new IllegalStateException("Field " + field.name() + " is not available in [" + classDef + "]:" + classDef.getFields());
                }
            } else {
                throw new IllegalStateException("Field access no supported on the object definition: " + objectDef);
            }
            return CodeBlock.of(renderExpression(objectDef, methodDef, field.instance()) + "." + field.name());
        }
        if (variableDef instanceof VariableDef.This) {
            if (objectDef == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            return CodeBlock.of("this");
        }
        throw new IllegalStateException("Unrecognized variable: " + variableDef);
    }

}
