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
import org.jspecify.annotations.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ClassUtils;
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
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef.AnnotationMemberDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static io.micronaut.sourcegen.javapoet.TypeSpec.anonymousClassBuilder;

/**
 * The Java source generator.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
@SuppressWarnings("java:S6201")
public sealed class JavaPoetSourceGenerator implements SourceGenerator permits GroovyPoetSourceGenerator {
    private static final String EXCEPTION_NAME = "$exception";

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) throws IOException {
        switch (objectDef) {
            case ClassDef classDef -> writeClass(writer, classDef);
            case RecordDef recordDef -> writeRecord(writer, recordDef);
            case InterfaceDef interfaceDef -> writeInterface(writer, interfaceDef);
            case EnumDef enumDef -> writeEnum(writer, enumDef);
            case AnnotationObjectDef annotationDef -> writeAnnotationObject(writer, annotationDef);
            case null, default ->
                throw new IllegalStateException("Unknown object definition: " + objectDef);
        }
    }

    private void writeInterface(Writer writer, InterfaceDef interfaceDef) throws IOException {
        TypeSpec.Builder interfaceBuilder = getInterfaceBuilder(interfaceDef);
        JavaFile javaFile = JavaFile.builder(interfaceDef.getPackageName(), interfaceBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private TypeSpec.Builder getInterfaceBuilder(InterfaceDef interfaceDef) {
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
                .build());
            interfaceBuilder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
                .build());
        }

        addInnerTypes(interfaceDef.getInnerTypes(), interfaceBuilder, true);

        for (MethodDef method : interfaceDef.getMethods()) {
            interfaceBuilder.addMethod(
                asMethodSpec(interfaceDef, method)
            );
        }
        return interfaceBuilder;
    }

    private void writeEnum(Writer writer, EnumDef enumDef) throws IOException {
        TypeSpec.Builder enumBuilder = getEnumBuilder(enumDef);
        JavaFile javaFile = JavaFile.builder(enumDef.getPackageName(), enumBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private TypeSpec.Builder getEnumBuilder(EnumDef enumDef) {
        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(enumDef.getSimpleName());
        enumBuilder.addModifiers(enumDef.getModifiersArray());
        enumDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, enumDef)).forEach(enumBuilder::addSuperinterface);
        enumDef.getJavadoc().forEach(enumBuilder::addJavadoc);

        for (AnnotationDef annotation : enumDef.getAnnotations()) {
            enumBuilder.addAnnotation(asAnnotationSpec(annotation));
        }

        enumDef.getEnumConstants().forEach(e -> {
            TypeSpec.Builder type = anonymousClassBuilder("");
            if (e.constructorArgs() != null) {
                List<ExpressionDef> constructorArgs = e.constructorArgs();
                CodeBlock.Builder expBuilder = CodeBlock.builder();
                for (int i = 0; i < constructorArgs.size(); i++) {
                    expBuilder.add(renderExpression(null, null, Map.of(), constructorArgs.get(i)));
                    if (i < constructorArgs.size() - 1) {
                        expBuilder.add(", ");
                    }
                }
                type = anonymousClassBuilder(expBuilder.build());
            }
            e.javadoc().forEach(type::addJavadoc);
            enumBuilder.addEnumConstant(e.name(), type.build());
        });

        buildProperties(enumDef, enumBuilder);

        buildFields(enumDef, enumDef.getFields(), enumBuilder);

        for (MethodDef method : enumDef.getMethods()) {
            enumBuilder.addMethod(
                asMethodSpec(enumDef, method)
            );
        }
        addInnerTypes(enumDef.getInnerTypes(), enumBuilder, false);
        return enumBuilder;
    }

    private void writeAnnotationObject(Writer writer, AnnotationObjectDef annotationDef) throws IOException {
        TypeSpec.Builder annotationBuilder = getAnnotationObjectBuilder(annotationDef);
        JavaFile javaFile = JavaFile.builder(annotationDef.getPackageName(), annotationBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private TypeSpec.Builder getAnnotationObjectBuilder(AnnotationObjectDef def) {
        TypeSpec.Builder builder = TypeSpec.annotationBuilder(def.getSimpleName());
        builder.addModifiers(def.getModifiersArray());
        def.getJavadoc().forEach(builder::addJavadoc);
        for (AnnotationDef annotation : def.getAnnotations()) {
            builder.addAnnotation(asAnnotationSpec(annotation));
        }
        buildFields(def, def.getFields(), builder);
        for (AnnotationMemberDef member: def.getMembers()) {
            MethodSpec.Builder method = MethodSpec.methodBuilder(member.getName());
            method.returns(asType(member.getType(), def));
            method.addModifiers(member.getModifiersArray());
            for (AnnotationDef annotation : member.getAnnotations()) {
                method.addAnnotation(asAnnotationSpec(annotation));
            }
            member.getJavadoc().forEach(method::addJavadoc);
            if (member.getDefaultValue() != null) {
                method.defaultValue(renderAnnotationMemberDefault(def, member.getDefaultValue()));
            }
            if (member.getAnnotationDefaultValue() != null) {
                method.defaultAnnotationValue(asAnnotationSpec(member.getAnnotationDefaultValue()));
            }
            builder.addMethod(method.build());
        }
        addInnerTypes(def.getInnerTypes(), builder, false);
        return builder;
    }

    private CodeBlock renderAnnotationMemberDefault(ObjectDef def, ExpressionDef defaultValue) {
        if (defaultValue instanceof ExpressionDef.Constant constant) {
            Object constantValue = constant.value();
            if (constant.type() instanceof TypeDef.Array arrayDef
                && constantValue != null
                && constantValue.getClass().isArray()
            ) {
                final var values = IntStream.range(0, Array.getLength(constantValue))
                    .mapToObj(i -> renderConstantExpression(Collections.emptyMap(),
                        new ExpressionDef.Constant(arrayDef.componentType(), Array.get(constantValue, i))))
                    .collect(CodeBlock.joining(", "));
                return CodeBlock.concat(CodeBlock.of("{"), values, CodeBlock.of("}"));
            }
        }
        return renderExpression(def, null, Collections.emptyMap(), defaultValue);
    }

    private void writeClass(Writer writer, ClassDef classDef) throws IOException {
        TypeSpec.Builder classBuilder = getClassBuilder(classDef);
        JavaFile javaFile = JavaFile.builder(classDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private TypeSpec.Builder getClassBuilder(ClassDef classDef) {
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

        buildProperties(classDef, classBuilder);

        buildFields(classDef, classDef.getFields(), classBuilder);

        addInnerTypes(classDef.getInnerTypes(), classBuilder, false);

        for (MethodDef method : classDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(classDef, method)
            );
        }

        StatementDef staticInitializer = classDef.getStaticInitializer();
        if (staticInitializer != null) {
            CodeBlock staticBlock = renderStatementCodeBlock(classDef, null, Map.of(), staticInitializer);
            classBuilder.addStaticBlock(staticBlock);
        }
        return classBuilder;
    }

    private void writeRecord(Writer writer, RecordDef recordDef) throws IOException {
        TypeSpec.Builder classBuilder = getRecordBuilder(recordDef);
        JavaFile javaFile = JavaFile.builder(recordDef.getPackageName(), classBuilder.build()).build();
        javaFile.writeTo(writer);
    }

    private TypeSpec.Builder getRecordBuilder(RecordDef recordDef) {
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

        addInnerTypes(recordDef.getInnerTypes(), classBuilder, false);

        for (MethodDef method : recordDef.getMethods()) {
            classBuilder.addMethod(
                asMethodSpec(recordDef, method)
            );
        }
        return classBuilder;
    }

    private void addInnerTypes(List<ObjectDef> innerTypes, TypeSpec.Builder classBuilder, boolean isInterface) {
        for (ObjectDef innerType : innerTypes) {
            TypeSpec.Builder innerBuilder = switch (innerType) {
                case ClassDef innerClassDef -> getClassBuilder(innerClassDef);
                case InterfaceDef innerInterfaceDef -> getInterfaceBuilder(innerInterfaceDef);
                case EnumDef innerEnumDef -> getEnumBuilder(innerEnumDef);
                case RecordDef innerRecordDef -> getRecordBuilder(innerRecordDef);
                case AnnotationObjectDef annotationObjectDef ->
                    getAnnotationObjectBuilder(annotationObjectDef);
                case null, default ->
                    throw new IllegalStateException("Unknown object definition: " + innerType);
            };
            if (isInterface) {
                innerBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
            }
            classBuilder.addType(innerBuilder.build());
        }
    }

    private void buildFields(ObjectDef objectDef, List<FieldDef> fields, TypeSpec.Builder builder) {
        for (FieldDef field : fields) {
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                asType(field.getType(), objectDef),
                field.getName()
            ).addModifiers(field.getModifiersArray());
            field.getInitializer().ifPresent(init ->
                fieldBuilder.initializer(renderExpression(
                    objectDef,
                    null,
                    Map.of(),
                    init
                ))
            );
            field.getJavadoc().forEach(fieldBuilder::addJavadoc);
            for (AnnotationDef annotation : field.getAnnotations()) {
                fieldBuilder.addAnnotation(
                    asAnnotationSpec(annotation)
                );
            }
            builder.addField(
                fieldBuilder
                    .build()
            );
        }
    }

    private void buildProperties(ObjectDef objectDef, TypeSpec.Builder builder) {
        for (PropertyDef property : objectDef.getProperties()) {
            TypeName propertyType = asType(property.getType(), objectDef);
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
            builder.addField(
                fieldBuilder
                    .build()
            );
            String capitalizedPropertyName = NameUtils.capitalize(propertyName);
            builder.addMethod(MethodSpec.methodBuilder("get" + capitalizedPropertyName)
                .addModifiers(property.getModifiersArray())
                .returns(propertyType)
                .addStatement("return this." + propertyName)
                .build());
            if (objectDef instanceof ClassDef) {
                builder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                    .addModifiers(property.getModifiersArray())
                    .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
                    .addStatement("this." + propertyName + " = " + propertyName)
                    .build());
            }
        }
    }

    private MethodSpec asMethodSpec(ObjectDef objectDef, MethodDef method) {
        String methodName = method.getName();
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
            .addModifiers(method.getModifiersArray())
            .addParameters(
                method.getParameters().stream()
                    .map(param -> ParameterSpec.builder(
                        asType(param.getType(), objectDef, method),
                        param.getName(),
                        param.getModifiersArray()
                    ).addAnnotations(param.getAnnotations().stream().map(this::asAnnotationSpec).toList()).build())
                    .toList()
            );
        if (!methodName.equals(MethodSpec.CONSTRUCTOR)) {
            methodBuilder.returns(asType(method.getReturnType(), objectDef, method));
        }
        for (TypeDef.TypeVariable typeVariable : method.getTypeVariables()) {
            methodBuilder.addTypeVariable(
                asTypeVariable(typeVariable, null)
            );
        }
        method.getJavadoc().forEach(methodBuilder::addJavadoc);
        for (AnnotationDef annotation : method.getAnnotations()) {
            methodBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        for (TypeDef type: method.getThrowTypes()) {
            methodBuilder.addException(asType(type, objectDef, method));
        }
        method.getStatements().stream()
            .map(st -> renderStatementCodeBlock(objectDef, method, Map.of(), st))
            .forEach(methodBuilder::addCode);

        return methodBuilder.build();
    }

    private TypeVariableName asTypeVariable(TypeDef.TypeVariable tv, @Nullable ObjectDef objectDef) {
        return TypeVariableName.get(
            tv.name(),
            tv.bounds().stream().map(t -> asType(t, objectDef)).toArray(TypeName[]::new)
        );
    }

    private AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(asClassType(annotationDef.getType()));
        for (Map.Entry<String, Object> e : annotationDef.getValues().entrySet()) {
            addAnnotationValue(builder, e.getKey(), e.getValue());
        }
        return builder.build();
    }

    private void addAnnotationValue(AnnotationSpec.Builder builder, String memberName, Object value) {
        switch (value) {
            case Collection<?> collection ->
                collection.forEach(v -> addAnnotationValue(builder, memberName, v));
            case AnnotationDef annotationValue ->
                builder.addMember(memberName, asAnnotationSpec(annotationValue));
            case VariableDef variableDef ->
                builder.addMember(memberName, renderVariable(null, null, Map.of(), variableDef));
            case Class<?> _ -> builder.addMember(memberName, "$T.class", value);
            case Enum<?> anEnum ->
                builder.addMember(memberName, "$T.$L", value.getClass(), anEnum.name());
            case String _ -> builder.addMember(memberName, "$S", value);
            case Float _ -> builder.addMember(memberName, "$Lf", value);
            case Character _ ->
                builder.addMember(memberName, "'$L'", Util.characterLiteralWithoutSingleQuotes((char) value));
            case ClassTypeDef typeDef ->
                builder.addMember(memberName, "$L.class", typeDef.getSimpleName());
            case null, default -> builder.addMember(memberName, "$L", value);
        }
    }

    private TypeName asType(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        return asType(typeDef, objectDef, null);
    }

    private TypeName asType(TypeDef typeDef, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        if (typeDef.equals(TypeDef.THIS)) {
            if (objectDef == null) {
                throw new IllegalStateException("This type is used outside of the instance scope!");
            }
            return asType(objectDef.asTypeDef(), null);
        }
        if (typeDef.equals(TypeDef.SUPER)) {
            if (objectDef == null) {
                throw new IllegalStateException("Super type is used outside of the instance scope!");
            }
            if (objectDef instanceof ClassDef classDef) {
                return asType(Objects.requireNonNullElse(classDef.getSuperclass(), ClassTypeDef.OBJECT), objectDef);
            }
            if (objectDef instanceof EnumDef) {
                return asClassType(ClassTypeDef.of(Enum.class));
            }
            throw new IllegalStateException("Super type is not supported for " + objectDef);
        }
        switch (typeDef) {
            case TypeDef.Array array -> {
                TypeName arrayTypeName = ArrayTypeName.of(asType(array.componentType(), objectDef));
                for (int i = 1; i < array.dimensions(); ++i) {
                    arrayTypeName = ArrayTypeName.of(arrayTypeName);
                }
                return arrayTypeName;
            }
            case ClassTypeDef.Parameterized parameterized -> {
                return ParameterizedTypeName.get(
                    asClassType(parameterized.rawType()),
                    parameterized.typeArguments().stream().map(t -> asType(t, objectDef, methodDef)).toArray(TypeName[]::new)
                );
            }
            case TypeDef.Primitive primitive -> {
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
            case ClassTypeDef.AnnotatedClassTypeDef annotatedType -> {
                var annotationsSpecs = annotatedType.annotations().stream().map(this::asAnnotationSpec).toList();
                return asType(annotatedType.typeDef(), objectDef).annotated(annotationsSpecs);
            }
            case ClassTypeDef classType -> {
                return asClassType(classType);
            }
            case TypeDef.Wildcard wildcard -> {
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
            case TypeDef.TypeVariable typeVariable -> {
                if (isVariablePartOfTheDefinition(typeVariable.name(), objectDef, methodDef)) {
                    return asTypeVariable(typeVariable, objectDef);
                }
                if (typeVariable.bounds().isEmpty()) {
                    return asType(ClassTypeDef.OBJECT, objectDef);
                }
                return asType(typeVariable.bounds().get(0), objectDef);
            }
            case TypeDef.AnnotatedTypeDef annotatedType -> {
                var annotationsSpecs = annotatedType.annotations().stream().map(this::asAnnotationSpec).toList();
                return asType(annotatedType.typeDef(), objectDef).annotated(annotationsSpecs);
            }
            default -> throw new IllegalStateException("Unrecognized type definition " + typeDef);
        }
    }

    private static boolean isVariablePartOfTheDefinition(String variableName, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        if (methodDef != null
            && methodDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(variableName))) {
            return true;
        }
        return switch (objectDef) {
            case ClassDef classDef -> classDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case null, default -> false;
        };
    }

    /**
     * Converts a {@link ClassTypeDef} into a JavaPoet {@link ClassName}.
     *
     * <p>For an inner type the split is taken from the binary name ({@link ClassTypeDef#getName()}),
     * which is unambiguous; {@link ClassTypeDef#getCanonicalName()} cannot be used because it
     * rewrites {@code $} to {@code .}.
     *
     * @param classTypeDef The class type definition
     * @return The class name
     */
    private static ClassName asClassType(ClassTypeDef classTypeDef) {
        if (classTypeDef.isInner()) {
            String binaryName = classTypeDef.getName();
            // The separator is the first '$' of the simple name that is not its first character, so that
            // an enclosing type following the generated `$Foo` convention is not split in the middle
            int simpleNameStart = binaryName.lastIndexOf('.') + 1;
            int i = binaryName.indexOf('$', simpleNameStart + 1);
            if (i != -1) {
                String enclosing = binaryName.substring(0, i);
                String[] nested = binaryName.substring(i + 1).split("\\$", -1);
                return ClassName.get(packageNameOf(enclosing), simpleNameOf(enclosing), nested);
            }
        }
        return asClassName(classTypeDef.getCanonicalName());
    }

    /**
     * A lenient variant of {@link ClassName#bestGuess(String)}.
     *
     * <p>It infers the package the same way - by consuming the leading lower-case segments - but it
     * does not require the remaining simple names to start with an upper-case letter, so generated
     * names following the {@code $Foo$Bar} convention are supported, and it does not fail when the
     * name has no package at all.
     *
     * @param name The fully qualified name
     * @return The class name
     */
    private static ClassName asClassName(String name) {
        int p = 0;
        while (p < name.length() && Character.isLowerCase(name.codePointAt(p))) {
            int dot = name.indexOf('.', p);
            if (dot == -1) {
                break;
            }
            p = dot + 1;
        }
        String packageName = p == 0 ? "" : name.substring(0, p - 1);
        String[] simpleNames = name.substring(p).split("\\.", -1);
        return ClassName.get(
            packageName,
            simpleNames[0],
            Arrays.copyOfRange(simpleNames, 1, simpleNames.length)
        );
    }

    private static String packageNameOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? "" : binaryName.substring(0, i);
    }

    private static String simpleNameOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? binaryName : binaryName.substring(i + 1);
    }

    private CodeBlock renderStatement(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      Map<String, String> remappedLocals,
                                      StatementDef statementDef) {
        switch (statementDef) {
            case StatementDef.InvokeSuperConstructor invokeConstructor -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, invokeConstructor.superInstance()),
                    CodeBlock.of("("),
                    invokeConstructor.values()
                        .stream()
                        .map(exp -> renderExpression(objectDef, methodDef, remappedLocals, exp))
                        .collect(CodeBlock.joining(", ")),
                    CodeBlock.of(")")
                );
            }
            case StatementDef.Throw aThrow -> {
                return CodeBlock.concat(
                    CodeBlock.of("throw "),
                    renderExpression(objectDef, methodDef, remappedLocals, aThrow.expression())
                );
            }
            case StatementDef.Return aReturn -> {
                if (aReturn.expression() == null) {
                    return CodeBlock.of("return");
                }
                return CodeBlock.concat(
                    CodeBlock.of("return "),
                    renderExpression(objectDef, methodDef, remappedLocals, aReturn.expression())
                );
            }
            case StatementDef.Assign assign -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, assign.variable()),
                    CodeBlock.of(" = "),
                    renderExpression(objectDef, methodDef, remappedLocals, assign.expression())
                );
            }
            case StatementDef.PutField putField -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, putField.field()),
                    CodeBlock.of(" = "),
                    renderExpression(objectDef, methodDef, remappedLocals, putField.expression())
                );
            }
            case StatementDef.PutStaticField putStaticField -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, putStaticField.field()),
                    CodeBlock.of(" = "),
                    renderExpression(objectDef, methodDef, remappedLocals, putStaticField.expression())
                );
            }
            case StatementDef.DefineAndAssign assign -> {
                return CodeBlock.concat(
                    CodeBlock.of("$T $L", asType(assign.variable().type(), objectDef), assign.variable().name()),
                    CodeBlock.of(" = "),
                    renderExpression(objectDef, methodDef, remappedLocals, assign.expression())
                );
            }
            case ExpressionDef expressionDef -> {
                return renderExpression(objectDef, methodDef, remappedLocals, expressionDef);
            }
            case null, default -> throw new IllegalStateException("Unrecognized statement: " + statementDef);
        }
    }

    private CodeBlock renderStatementCodeBlock(@Nullable ObjectDef objectDef,
                                               @Nullable MethodDef methodDef,
                                               Map<String, String> remappedLocals,
                                               StatementDef statementDef) {
        switch (statementDef) {
            case StatementDef.Multi statements -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                for (StatementDef statement : statements.statements()) {
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, statement));
                }
                return builder.build();
            }
            case StatementDef.Try tryStatement -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("try {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, tryStatement.statement()));
                builder.unindent();
                int i = 0;
                for (StatementDef.Try.Catch aCatch : tryStatement.catches()) {
                    String exceptionLocal = "e" + i++;
                    builder.add(CodeBlock.of("} catch ($T $L) {\n", asType(aCatch.exception(), objectDef), exceptionLocal));
                    builder.indent();
                    Map<String, String> newRemappedLocals = new LinkedHashMap<>(remappedLocals);
                    newRemappedLocals.put(EXCEPTION_NAME, exceptionLocal);
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, newRemappedLocals, aCatch.statement()));
                    builder.unindent();
                }
                if (tryStatement.finallyStatement() != null) {
                    builder.add("} finally {\n");
                    builder.indent();
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, tryStatement.finallyStatement()));
                    builder.unindent();
                }
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.Synchronized s -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("synchronized (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, s.monitor(), true));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, s.statement()));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.If ifStatement -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, ifStatement.condition()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, ifStatement.statement()));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.IfElse ifStatement -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, ifStatement.condition()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, ifStatement.statement()));
                builder.unindent();
                builder.add("} else {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, ifStatement.elseStatement()));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.Switch aSwitch -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("switch (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, aSwitch.expression()));
                builder.add(") {\n");
                builder.indent();
                for (Map.Entry<ExpressionDef.Constant, StatementDef> e : aSwitch.cases().entrySet()) {
                    builder.add("case ");
                    builder.add(renderConstantExpression(remappedLocals, e.getKey()));
                    builder.add(" -> {\n");
                    builder.indent();
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, e.getValue()));
                    builder.unindent();
                    builder.add("}\n");
                }
                if (aSwitch.defaultCase() != null) {
                    builder.add("default -> {\n");
                    builder.indent();
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, aSwitch.defaultCase()));
                    builder.unindent();
                    builder.add("}\n");
                }
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.While aWhile -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("while (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, aWhile.expression()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, remappedLocals, aWhile.statement()));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case null, default -> {
                CodeBlock statement = renderStatement(objectDef, methodDef, remappedLocals, statementDef);
                if (statementDef != null && containsSwitchExpression(statementDef)) {
                    return CodeBlock.builder()
                        .add(statement)
                        .add(";\n")
                        .build();
                }
                return CodeBlock.builder()
                    .addStatement(statement)
                    .build();
            }
        }
    }

    private static boolean containsSwitchExpression(StatementDef statementDef) {
        return statementDef.nestedExpressionsStream().anyMatch(JavaPoetSourceGenerator::containsSwitchExpression);
    }

    private static boolean containsSwitchExpression(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Switch
            || expressionDef.nestedExpressionsStream().anyMatch(JavaPoetSourceGenerator::containsSwitchExpression);
    }

    private CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       Map<String, String> remappedLocals,
                                       ExpressionDef expressionDef) {
        return renderExpression(objectDef, methodDef, remappedLocals, expressionDef, CastContext.DEFAULT);
    }

    private static boolean isNullLiteral(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast castExpressionDef) {
            expressionDef = castExpressionDef.expressionDef();
        }
        return expressionDef instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    private CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       Map<String, String> remappedLocals,
                                       ExpressionDef expressionDef,
                                       boolean isRef) {
        return renderExpression(objectDef, methodDef, remappedLocals, expressionDef, isRef ? CastContext.OBJECT_REFERENCE : CastContext.DEFAULT);
    }

    private CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       Map<String, String> remappedLocals,
                                       ExpressionDef expressionDef,
                                       CastContext castContext) {
        switch (expressionDef) {
            case ExpressionDef.ConditionExpressionDef conditionExpressionDef -> {
                return renderCondition(objectDef, methodDef, remappedLocals, conditionExpressionDef, false);
            }
            case ExpressionDef.NewInstance newInstance -> {
                return CodeBlock.concat(
                    CodeBlock.of("new $L(", asType(newInstance.type(), objectDef)),
                    newInstance.values()
                        .stream()
                        .map(exp -> renderExpression(objectDef, methodDef, remappedLocals, exp))
                        .collect(CodeBlock.joining(", ")),
                    CodeBlock.of(")")
                );
            }
            case ExpressionDef.NewArrayOfSize newArray -> {
                return CodeBlock.of("new $T[$L]", asType(newArray.type().componentType(), objectDef), newArray.size());
            }
            case ExpressionDef.NewArrayInitialized newArray -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("new $T[]{", asType(newArray.type().componentType(), objectDef));
                for (Iterator<? extends ExpressionDef> iterator = newArray.nestedExpressionsStream().iterator(); iterator.hasNext(); ) {
                    ExpressionDef expression = iterator.next();
                    builder.add(renderExpression(objectDef, methodDef, remappedLocals, expression));
                    if (iterator.hasNext()) {
                        builder.add(",");
                    }
                }
                builder.add("}");
                return builder.build();
            }
            case ExpressionDef.Cast castExpressionDef -> {
                ExpressionDef exp = collapseNestedCasts(castExpressionDef.expressionDef());
                if (isNullLiteral(exp)) {
                    return renderExpression(objectDef, methodDef, remappedLocals, exp);
                }
                if (castExpressionDef.type().equals(exp.type())
                    || canEliminateCastToObject(castExpressionDef, exp, castContext)) {
                    return renderExpression(objectDef, methodDef, remappedLocals, exp, castContext);
                }
                CodeBlock explicitCast = CodeBlock.of("($T)", asType(castExpressionDef.type(), objectDef));
                CodeBlock rendered = renderExpression(objectDef, methodDef, remappedLocals, exp);
                ExpressionDef castOperand = unwrapCasts(exp);
                if (!requiresCastOperandParentheses(castOperand)) {
                    return CodeBlock.concat(explicitCast, CodeBlock.of(" "), rendered);
                }
                return CodeBlock.concat(
                    explicitCast,
                    CodeBlock.of(" "),
                    addParentheses(rendered)
                );
            }
            case ExpressionDef.Constant constant -> {
                return renderConstantExpression(remappedLocals, constant);
            }
            case ExpressionDef.InvokeInstanceMethod invokeInstanceMethod -> {
                MethodDef callMethod = invokeInstanceMethod.method();
                CodeBlock instance = renderExpression(objectDef, methodDef, remappedLocals, invokeInstanceMethod.instance());
                if (!callMethod.isConstructor() && requiresMethodCallTargetParentheses(invokeInstanceMethod.instance())) {
                    instance = addParentheses(instance);
                }
                return CodeBlock.concat(
                    instance,
                    CodeBlock.of((callMethod.isConstructor() ? "" : "." + callMethod.getName()) + "("),
                    invokeInstanceMethod.values()
                        .stream()
                        .map(exp -> renderExpression(objectDef, methodDef, remappedLocals, exp))
                        .collect(CodeBlock.joining(", ")),
                    CodeBlock.of(")")
                );
            }
            case ExpressionDef.InvokeStaticMethod staticMethod -> {
                return CodeBlock.concat(
                    CodeBlock.of("$T." + staticMethod.method().getName() + "(", asType(staticMethod.classDef(), objectDef)),
                    staticMethod.values()
                        .stream()
                        .map(exp -> renderExpression(objectDef, methodDef, remappedLocals, exp))
                        .collect(CodeBlock.joining(", ")),
                    CodeBlock.of(")")
                );
            }
            case ExpressionDef.GetPropertyValue getPropertyValue -> {
                return renderExpression(objectDef, methodDef, remappedLocals, JavaIdioms.getPropertyValue(getPropertyValue));
            }
            case ExpressionDef.MathBinaryOperation mathOperation -> {
                return CodeBlock.concat(
                    renderMathOperand(objectDef, methodDef, remappedLocals, mathOperation, mathOperation.left(), false),
                    CodeBlock.of(getMathOp(mathOperation)),
                    renderMathOperand(objectDef, methodDef, remappedLocals, mathOperation, mathOperation.right(), true)
                );
            }
            case ExpressionDef.MathUnaryOperation mathOperation -> {
                return CodeBlock.concat(
                    CodeBlock.of(getMathOp(mathOperation)),
                    renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, mathOperation.expression())
                );
            }
            case ExpressionDef.IfElse condition -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, condition.condition()),
                    CodeBlock.of(" ? "),
                    renderExpression(objectDef, methodDef, remappedLocals, condition.ifExpression()),
                    CodeBlock.of(" : "),
                    renderExpression(objectDef, methodDef, remappedLocals, condition.elseExpression())
                );
            }
            case ExpressionDef.Switch aSwitch -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("switch (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, aSwitch.expression()));
                builder.add(") {\n");
                builder.indent();
                for (Map.Entry<ExpressionDef.Constant, ? extends ExpressionDef> e : aSwitch.cases().entrySet()) {
                    builder.add("case ");
                    builder.add(renderConstantExpression(remappedLocals, e.getKey()));
                    builder.add(" -> ");
                    ExpressionDef value = e.getValue();
                    builder.add(renderExpression(objectDef, methodDef, remappedLocals, value));
                    if (value instanceof ExpressionDef.SwitchYieldCase) {
                        builder.add("\n");
                    } else {
                        builder.add(";\n");
                    }
                }
                if (aSwitch.defaultCase() != null) {
                    builder.add("default");
                    builder.add(" -> ");
                    builder.add(renderExpression(objectDef, methodDef, remappedLocals, aSwitch.defaultCase()));
                    if (aSwitch.defaultCase() instanceof ExpressionDef.SwitchYieldCase) {
                        builder.add("\n");
                    } else {
                        builder.add(";\n");
                    }
                }
                builder.unindent();
                builder.add("}");
                return builder.build();
            }
            case ExpressionDef.SwitchYieldCase switchYieldCase -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("{\n");
                builder.indent();
                StatementDef statement = switchYieldCase.statement();
                List<StatementDef> flatten = statement.flatten();
                if (flatten.isEmpty()) {
                    throw new IllegalStateException("SwitchYieldCase did not return any statements");
                }
                StatementDef last = flatten.getLast();
                if (!hasSwitchYieldReturn(statement)) {
                    throw new IllegalStateException("The last statement of SwitchYieldCase should be a return. Found: " + last);
                }
                builder.add(renderSwitchYieldStatementCodeBlock(objectDef, methodDef, remappedLocals, statement));
                builder.unindent();
                builder.add("}");
                String str = builder.build().toString();
                // Render the body to prevent nested statements
                return CodeBlock.ofWithoutFormat(str);
            }
            case VariableDef variableDef -> {
                return renderVariable(objectDef, methodDef, remappedLocals, variableDef);
            }
            case ExpressionDef.InvokeGetClassMethod invokeGetClassMethod -> {
                return renderExpression(objectDef, methodDef, remappedLocals, JavaIdioms.getClass(invokeGetClassMethod));
            }
            case ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod -> {
                return renderExpression(objectDef, methodDef, remappedLocals, JavaIdioms.hashCode(invokeHashCodeMethod));
            }
            case Lambda lambda -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("(");
                Iterator<ParameterDef> parameter = lambda.implementation().getParameters().iterator();
                while (parameter.hasNext()) {
                    builder.add(parameter.next().getName());
                    if (parameter.hasNext()) {
                        builder.add(", ");
                    }
                }
                builder.add(") -> ");
                List<StatementDef> statements = lambda.implementation().getStatements();
                if (statements.size() == 1 && statements.get(0) instanceof StatementDef.Return returnStatement
                    && returnStatement.expression() != null) {
                    builder.add(renderExpression(objectDef, lambda.implementation(), remappedLocals, returnStatement.expression()));
                } else {
                    builder.add("{").indent();
                    for (StatementDef statement : statements) {
                        builder.addStatement(renderStatementCodeBlock(objectDef, lambda.implementation(), remappedLocals, statement));
                    }
                    builder.unindent().add("}");
                }
                return builder.build();
            }
            case ExpressionDef.StringConcatenation concat -> {
                ExpressionDef left = concat.left();
                if (!left.type().equals(TypeDef.STRING) && !concat.right().type().equals(TypeDef.STRING)) {
                    left = TypeDef.STRING.invokeStatic("valueOf", TypeDef.STRING, left);
                }
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, left),
                    CodeBlock.of(" + "),
                    renderExpression(objectDef, methodDef, remappedLocals, concat.right())
                );
            }
            case null, default -> throw new IllegalStateException("Unrecognized expression: " + expressionDef);
        }
    }

    private static String getMathOp(ExpressionDef.MathBinaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case ADDITION -> " + ";
            case SUBTRACTION -> " - ";
            case MULTIPLICATION -> " * ";
            case DIVISION -> " / ";
            case MODULUS -> " % ";
            case BITWISE_AND -> " & ";
            case BITWISE_OR -> " | ";
            case BITWISE_XOR -> " ^ ";
            case BITWISE_LEFT_SHIFT -> " << ";
            case BITWISE_RIGHT_SHIFT -> " >> ";
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> " >>> ";
        };
    }

    private static String getMathOp(ExpressionDef.MathUnaryOperation mathOperation) {
        return switch (mathOperation.opType()) {
            case NEGATE -> "-";
        };
    }

    private CodeBlock renderMathOperand(@Nullable ObjectDef objectDef,
                                        @Nullable MethodDef methodDef,
                                        Map<String, String> remappedLocals,
                                        ExpressionDef.MathBinaryOperation parent,
                                        ExpressionDef operand,
                                        boolean rightOperand) {
        if (operand instanceof ExpressionDef.MathBinaryOperation child) {
            CodeBlock rendered = renderExpression(objectDef, methodDef, remappedLocals, child);
            if (requiresMathParentheses(parent, child, rightOperand)) {
                return addParentheses(rendered);
            }
            return rendered;
        }
        return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, operand);
    }

    private static boolean requiresMathParentheses(ExpressionDef.MathBinaryOperation parent,
                                                  ExpressionDef.MathBinaryOperation child,
                                                  boolean rightOperand) {
        int parentPrecedence = mathPrecedence(parent.opType());
        int childPrecedence = mathPrecedence(child.opType());
        return childPrecedence < parentPrecedence || (rightOperand && childPrecedence == parentPrecedence);
    }

    private static int mathPrecedence(ExpressionDef.MathBinaryOperation.OpType opType) {
        return switch (opType) {
            case MULTIPLICATION, DIVISION, MODULUS -> 6;
            case ADDITION, SUBTRACTION -> 5;
            case BITWISE_LEFT_SHIFT, BITWISE_RIGHT_SHIFT, BITWISE_UNSIGNED_RIGHT_SHIFT -> 4;
            case BITWISE_AND -> 3;
            case BITWISE_XOR -> 2;
            case BITWISE_OR -> 1;
        };
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, ExpressionDef expressionDef) {
        return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, expressionDef, CastContext.DEFAULT);
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, ExpressionDef expressionDef, boolean isRef) {
        return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, expressionDef, isRef ? CastContext.OBJECT_REFERENCE : CastContext.DEFAULT);
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef,
                                                      @Nullable MethodDef methodDef,
                                                      Map<String, String> remappedLocals,
                                                      ExpressionDef expressionDef,
                                                      CastContext castContext) {
        var rendered = renderExpression(objectDef, methodDef, remappedLocals, expressionDef, castContext);
        if (!requiresParentheses(expressionDef)) {
            return rendered;
        }
        return addParentheses(rendered);
    }

    private static boolean requiresParentheses(ExpressionDef expressionDef) {
        expressionDef = unwrapCasts(expressionDef);
        if (expressionDef instanceof ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
            TypeDef type = invokeHashCodeMethod.instance().type();
            return !type.isPrimitive() && !type.isArray();
        }
        return !(expressionDef instanceof StatementDef
            || expressionDef instanceof VariableDef
            || expressionDef instanceof ExpressionDef.And
            || expressionDef instanceof ExpressionDef.Constant
            || expressionDef instanceof ExpressionDef.GetPropertyValue
            || expressionDef instanceof ExpressionDef.InvokeGetClassMethod
            || expressionDef instanceof ExpressionDef.ArrayElement
            || expressionDef instanceof ExpressionDef.NewArrayOfSize
            || expressionDef instanceof ExpressionDef.NewArrayInitialized
            || expressionDef instanceof ExpressionDef.NewInstance
            || expressionDef instanceof ExpressionDef.Switch);
    }

    private static ExpressionDef unwrapCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    private static ExpressionDef collapseNestedCasts(ExpressionDef expressionDef) {
        while (expressionDef instanceof ExpressionDef.Cast cast) {
            if (cast.type().isPrimitive()) {
                TypeDef previousCastType = cast.expressionDef().type();
                if (!previousCastType.equals(TypeDef.OBJECT)) {
                    break;
                }
            }
            // Only keep the last cast
            expressionDef = cast.expressionDef();
        }
        return expressionDef;
    }

    private static boolean requiresCastOperandParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.ConditionExpressionDef
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.MathBinaryOperation
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch;
    }

    private static boolean requiresMethodCallTargetParentheses(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Cast
            || expressionDef instanceof ExpressionDef.IfElse
            || expressionDef instanceof ExpressionDef.StringConcatenation
            || expressionDef instanceof ExpressionDef.Switch;
    }

    private static boolean canEliminateCastToObject(ExpressionDef.Cast castExpressionDef,
                                                    ExpressionDef expressionDef,
                                                    CastContext castContext) {
        if (!castExpressionDef.type().equals(TypeDef.OBJECT)) {
            return false;
        }
        return switch (castContext) {
            case DEFAULT -> false;
            case OBJECT_REFERENCE -> !expressionDef.type().isPrimitive();
            case PRIMITIVE_EQUALITY -> true;
        };
    }

    private static boolean arePrimitiveReferenceEqualityOperands(ExpressionDef left, ExpressionDef right) {
        return objectCastOperandType(left).isPrimitive() && objectCastOperandType(right).isPrimitive();
    }

    private static TypeDef objectCastOperandType(ExpressionDef expressionDef) {
        if (expressionDef instanceof ExpressionDef.Cast cast && cast.type().equals(TypeDef.OBJECT)) {
            return collapseNestedCasts(cast.expressionDef()).type();
        }
        return expressionDef.type();
    }

    private enum CastContext {
        DEFAULT,
        OBJECT_REFERENCE,
        PRIMITIVE_EQUALITY
    }

    private CodeBlock addParentheses(CodeBlock rendered) {
        return CodeBlock.concat(
            CodeBlock.of("("),
            rendered,
            CodeBlock.of(")")
        );
    }

    private CodeBlock renderCondition(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      Map<String, String> remappedLocals,
                                      ExpressionDef.ConditionExpressionDef expressionDef,
                                      boolean isRef) {
        switch (expressionDef) {
            case ExpressionDef.IsNull isNull -> {
                return renderCondition(objectDef, methodDef, remappedLocals, new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, isNull.expression(), ExpressionDef.nullValue()), true);
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                return renderCondition(objectDef, methodDef, remappedLocals, new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO, isNotNull.expression(), ExpressionDef.nullValue()), true);
            }
            case ExpressionDef.IsTrue isTrue -> {
                ExpressionDef expression = unwrapCasts(isTrue.expression());
                if (expression instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
                    return renderCondition(objectDef, methodDef, remappedLocals, conditionExpressionDef, isRef);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, isTrue.expression());
            }
            case ExpressionDef.IsFalse isFalse -> {
                ExpressionDef expression = unwrapCasts(isFalse.expression());
                if (expression instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
                    return CodeBlock.concat(
                        CodeBlock.of("!"),
                        addParentheses(renderCondition(objectDef, methodDef, remappedLocals, conditionExpressionDef, isRef))
                    );
                }
                return CodeBlock.concat(
                    CodeBlock.of("!"),
                    renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, isFalse.expression())
                );
            }
            case ExpressionDef.ComparisonOperation comparisonOperation -> {
                return CodeBlock.concat(
                    renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, comparisonOperation.left(), isRef),
                    CodeBlock.of(getOpType(comparisonOperation)),
                    renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, comparisonOperation.right(), isRef)
                );
            }
            case ExpressionDef.InstanceOf instanceOf -> {
                return CodeBlock.concat(
                    renderExpression(objectDef, methodDef, remappedLocals, instanceOf.expression(), true),
                    CodeBlock.of(" instanceof "),
                    CodeBlock.of(instanceOf.instanceType().getCanonicalName())
                );
            }
            case ExpressionDef.And andExpressionDef -> {
                return CodeBlock.concat(
                    renderAndConditionOperand(objectDef, methodDef, remappedLocals, andExpressionDef.left()),
                    CodeBlock.of(" && "),
                    renderAndConditionOperand(objectDef, methodDef, remappedLocals, andExpressionDef.right())
                );
            }
            case ExpressionDef.Or orExpressionDef -> {
                return CodeBlock.concat(
                    renderCondition(objectDef, methodDef, remappedLocals, orExpressionDef.left(), false),
                    CodeBlock.of(" || "),
                    renderCondition(objectDef, methodDef, remappedLocals, orExpressionDef.right(), false)
                );
            }
            case ExpressionDef.EqualsStructurally equalsStructurally -> {
                ExpressionDef left = equalsStructurally.instance();
                TypeDef leftType = left.type();
                ExpressionDef right = equalsStructurally.other();
                TypeDef rightType = right.type();
                if (leftType.isPrimitive() || rightType.isPrimitive()) {
                    return renderEqualsReferentially(objectDef, methodDef, remappedLocals, left, right);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, JavaIdioms.equalsStructurally(equalsStructurally));
            }
            case ExpressionDef.NotEqualsStructurally notEqualsStructurally -> {
                ExpressionDef left = notEqualsStructurally.instance();
                TypeDef leftType = left.type();
                ExpressionDef right = notEqualsStructurally.other();
                TypeDef rightType = right.type();
                if (leftType.isPrimitive() || rightType.isPrimitive()) {
                    return renderEqualsReferentially(objectDef, methodDef, remappedLocals, left, right);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, JavaIdioms.equalsStructurally(notEqualsStructurally.instance(), notEqualsStructurally.other()).isFalse());
            }
            case ExpressionDef.EqualsReferentially equalsReferentially -> {
                ExpressionDef left = equalsReferentially.instance();
                ExpressionDef right = equalsReferentially.other();
                return renderEqualsReferentially(objectDef, methodDef, remappedLocals, left, right);
            }
            case ExpressionDef.NotEqualsReferentially notEqualsReferentially -> {
                ExpressionDef left = notEqualsReferentially.instance();
                ExpressionDef right = notEqualsReferentially.other();
                return renderNotEqualsReferentially(objectDef, methodDef, remappedLocals, left, right);
            }
            case null, default -> throw new IllegalStateException("Unrecognized condition: " + expressionDef);
        }
    }

    private CodeBlock renderAndConditionOperand(@Nullable ObjectDef objectDef,
                                                @Nullable MethodDef methodDef,
                                                Map<String, String> remappedLocals,
                                                ExpressionDef.ConditionExpressionDef expressionDef) {
        CodeBlock rendered = renderCondition(objectDef, methodDef, remappedLocals, expressionDef, false);
        if (isOrCondition(expressionDef)) {
            return addParentheses(rendered);
        }
        return rendered;
    }

    private static boolean isOrCondition(ExpressionDef.ConditionExpressionDef expressionDef) {
        return switch (expressionDef) {
            case ExpressionDef.Or _ -> true;
            case ExpressionDef.IsTrue isTrue when unwrapCasts(isTrue.expression()) instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef ->
                isOrCondition(conditionExpressionDef);
            case null, default -> false;
        };
    }

    private static String getOpType(ExpressionDef.ComparisonOperation comparisonOperation) {
        return switch (comparisonOperation.opType()) {
            case EQUAL_TO -> " == ";
            case NOT_EQUAL_TO -> " != ";
            case GREATER_THAN -> " > ";
            case LESS_THAN -> " < ";
            case GREATER_THAN_OR_EQUAL -> " >= ";
            case LESS_THAN_OR_EQUAL -> " <= ";
        };
    }

    private CodeBlock renderEqualsReferentially(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, ExpressionDef left, ExpressionDef right) {
        CastContext castContext = arePrimitiveReferenceEqualityOperands(left, right)
            ? CastContext.PRIMITIVE_EQUALITY
            : CastContext.OBJECT_REFERENCE;
        return CodeBlock.builder()
            .add(renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, left, castContext))
            .add(" == ")
            .add(renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, right, castContext))
            .build();
    }

    private CodeBlock renderNotEqualsReferentially(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, ExpressionDef left, ExpressionDef right) {
        CastContext castContext = arePrimitiveReferenceEqualityOperands(left, right)
            ? CastContext.PRIMITIVE_EQUALITY
            : CastContext.OBJECT_REFERENCE;
        return CodeBlock.builder()
            .add(renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, left, castContext))
            .add(" != ")
            .add(renderExpressionWithParentheses(objectDef, methodDef, remappedLocals, right, castContext))
            .build();
    }

    private static boolean hasSwitchYieldReturn(StatementDef statementDef) {
        List<StatementDef> statements = statementDef.flatten();
        if (statements.isEmpty()) {
            return false;
        }
        StatementDef last = statements.getLast();
        return switch (last) {
            case StatementDef.Return(_) -> true;
            case StatementDef.IfElse(_, StatementDef statement, StatementDef elseStatement) ->
                hasSwitchYieldReturn(statement) && hasSwitchYieldReturn(elseStatement);
            default -> false;
        };
    }

    private void renderYield(CodeBlock.Builder builder, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, StatementDef statementDef, @Nullable ObjectDef objectDef) {
        if (statementDef instanceof StatementDef.Return aReturn) {
            ExpressionDef expression = aReturn.expression();
            if (expression == null) {
                throw new IllegalStateException("Switch yield return has no value");
            }
            builder.addStatement(
                CodeBlock.concat(
                    CodeBlock.of("yield "),
                    renderExpression(objectDef, methodDef, remappedLocals, expression)
                )
            );
        } else {
            throw new IllegalStateException("The last statement of SwitchYieldCase should be a return. Found: " + statementDef);
        }
    }

    private CodeBlock renderSwitchYieldStatementCodeBlock(@Nullable ObjectDef objectDef,
                                                          @Nullable MethodDef methodDef,
                                                          Map<String, String> remappedLocals,
                                                          StatementDef statementDef) {
        return switch (statementDef) {
            case StatementDef.Multi(List<StatementDef> statements) -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                for (StatementDef statement : statements) {
                    builder.add(renderSwitchYieldStatementCodeBlock(objectDef, methodDef, remappedLocals, statement));
                }
                yield builder.build();
            }
            case StatementDef.If(ExpressionDef condition, StatementDef statement) -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, condition));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderSwitchYieldStatementCodeBlock(objectDef, methodDef, remappedLocals, statement));
                builder.unindent();
                builder.add("}\n");
                yield builder.build();
            }
            case StatementDef.IfElse(ExpressionDef condition, StatementDef statement, StatementDef elseStatement) -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(renderExpression(objectDef, methodDef, remappedLocals, condition));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderSwitchYieldStatementCodeBlock(objectDef, methodDef, remappedLocals, statement));
                builder.unindent();
                builder.add("} else {\n");
                builder.indent();
                builder.add(renderSwitchYieldStatementCodeBlock(objectDef, methodDef, remappedLocals, elseStatement));
                builder.unindent();
                builder.add("}\n");
                yield builder.build();
            }
            case StatementDef.Return aReturn -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                renderYield(builder, methodDef, remappedLocals, aReturn, objectDef);
                yield builder.build();
            }
            case null, default -> renderStatementCodeBlock(objectDef, methodDef, remappedLocals, statementDef);
        };
    }

    private CodeBlock renderConstantExpression(Map<String, String> remappedLocals, ExpressionDef.Constant constant) {
        TypeDef type = constant.type();
        Object value = constant.value();
        if (value == null) {
            return CodeBlock.of("null");
        }
        return switch (type) {
            case ClassTypeDef classTypeDef when classTypeDef.isEnum() -> renderExpression(
                null,
                null,
                remappedLocals,
                classTypeDef.getStaticField(value instanceof Enum<?> anEnum ? anEnum.name() : value.toString(), type)
            );
            case TypeDef.Primitive primitive -> switch (primitive.name()) {
                case "long" -> CodeBlock.of(value + "l");
                case "float" -> CodeBlock.of(value + "f");
                case "double" -> CodeBlock.of(value + "d");
                default -> CodeBlock.of("$L", value);
            };
            case TypeDef.Array arrayDef -> {
                if (value.getClass().isArray()) {
                    final var array = value;
                    final var values = IntStream.range(0, Array.getLength(array))
                        .mapToObj(i -> renderConstantExpression(remappedLocals, new ExpressionDef.Constant(arrayDef.componentType(), Array.get(array, i))))
                        .collect(CodeBlock.joining(", "));
                    final String typeName;
                    if (arrayDef.componentType() instanceof ClassTypeDef arrayClassTypeDef) {
                        typeName = arrayClassTypeDef.getSimpleName();
                    } else if (arrayDef.componentType() instanceof TypeDef.Primitive arrayPrimitive) {
                        typeName = arrayPrimitive.name();
                    } else {
                        throw new IllegalStateException("Unrecognized expression: " + constant);
                    }
                    yield CodeBlock.concat(
                        CodeBlock.of("new $N[] {", typeName),
                        values,
                        CodeBlock.of("}"));
                }
                throw new IllegalStateException("Expected an array; got: " + value.getClass());
            }
            case ClassTypeDef classTypeDef -> {
                String name = classTypeDef.getName();
                if (ClassUtils.isJavaLangType(name)) {
                    yield switch (name) {
                        case "java.lang.Long" -> CodeBlock.of(value + "l");
                        case "java.lang.Float" -> CodeBlock.of(value + "f");
                        case "java.lang.Double" -> CodeBlock.of(value + "d");
                        case "java.lang.String" -> CodeBlock.of("$S", value);
                        default -> CodeBlock.of("$L", value);
                    };
                }
                if (value instanceof TypeDef typeDef) {
                    yield CodeBlock.of("$L.class", getClassName(typeDef));
                }
                yield CodeBlock.of("$L", value);
            }
            default -> throw new IllegalStateException("Unrecognized expression: " + constant);
        };
    }

    private String getClassName(TypeDef typeDef) {
        return switch (typeDef) {
            case ClassTypeDef classType -> asClassType(classType).canonicalName();
            case TypeDef.Primitive primitive -> primitive.name();
            case TypeDef.Array array -> getClassName(array.componentType()) + "[]";
            case null, default ->
                throw new IllegalStateException("Unrecognized type def: " + typeDef);
        };
    }

    private CodeBlock renderVariable(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, Map<String, String> remappedLocals, VariableDef variableDef) {
        switch (variableDef) {
            case VariableDef.ExceptionVar _ -> {
                return CodeBlock.of(Objects.requireNonNull(remappedLocals.get(EXCEPTION_NAME)));
            }
            case VariableDef.Local localVariableDef -> {
                return CodeBlock.of(localVariableDef.name());
            }
            case VariableDef.MethodParameter parameterVariableDef -> {
                if (methodDef == null) {
                    throw new IllegalStateException("Accessing method parameters is not available");
                }
                methodDef.getParameter(parameterVariableDef.name()); // Check if exists

                return CodeBlock.of(parameterVariableDef.name()); // Check if exists
            }
            case VariableDef.StaticField staticField -> {
                return CodeBlock.of("$T.$L", asType(staticField.ownerType(), objectDef), staticField.name());
            }
            case VariableDef.Field field -> {
                validateFieldAccess(objectDef, field);
                ExpressionDef instance = field.instance();
                if (!instance.type().equals(field.declaringType())) {
                    return CodeBlock.of(
                        "(" + renderExpression(objectDef, methodDef, remappedLocals, instance.cast(field.declaringType())) + ")." + field.name());
                }
                return CodeBlock.of(renderExpression(objectDef, methodDef, remappedLocals, instance) + "." + field.name());
            }
            case VariableDef.This _ -> {
                if (objectDef == null) {
                    throw new IllegalStateException("Accessing 'this' is not available");
                }
                return CodeBlock.of("this");
            }
            case VariableDef.Super aSuper -> {
                if (objectDef == null) {
                    throw new IllegalStateException("Accessing 'super' is not available");
                }
                if (aSuper.type() != TypeDef.SUPER) {
                    return CodeBlock.of("$T.super", asType(aSuper.type(), objectDef));
                }
                return CodeBlock.of("super");
            }
            case null, default -> throw new IllegalStateException("Unrecognized variable: " + variableDef);
        }
    }

    private static void validateFieldAccess(@Nullable ObjectDef objectDef, VariableDef.Field field) {
        switch (objectDef) {
            case null ->
                throw new IllegalStateException("Accessing 'this' is not available");
            case ClassDef classDef when classDef.hasField(field.name()) -> {
                return;
            }
            case ClassDef classDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + classDef + "]:" + classDef.getFields());
            case EnumDef enumDef when enumDef.hasField(field.name()) -> {
                return;
            }
            case EnumDef enumDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + enumDef.getName() + "]:" + enumDef.getProperties());
            default ->
                throw new IllegalStateException("Field access not supported on the object definition: " + objectDef);
        }
    }

}
