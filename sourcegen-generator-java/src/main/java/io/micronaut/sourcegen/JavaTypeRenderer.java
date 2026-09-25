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
import io.micronaut.core.naming.NameUtils;
import io.micronaut.sourcegen.generator.OverloadRules;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.ArrayTypeName;
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
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static io.micronaut.sourcegen.JavaPoetNames.asPrimitiveType;
import static io.micronaut.sourcegen.javapoet.TypeSpec.anonymousClassBuilder;

/**
 * The declarations of a file the Java source generator writes: its types, with their fields, properties and methods,
 * and the names of the types they use. One is created for each file, with the context of writing it.
 *
 * @since 2.3
 */
@Internal
final class JavaTypeRenderer {

    private final JavaPoetSourceGenerator generator;
    private final JavaWriteContext context;
    private final JavaPoetNames names;
    private final JavaExpressionRenderer expressions;
    private final JavaStatementRenderer statementRenderer;
    private final JavaLiterals literals;
    private final JavaSourceRules sourceRules;

    JavaTypeRenderer(JavaPoetSourceGenerator generator, JavaWriteContext context) {
        this.generator = generator;
        this.context = context;
        this.names = context.names();
        this.expressions = new JavaExpressionRenderer(this, context);
        this.statementRenderer = expressions.statementRenderer();
        this.literals = expressions.literals();
        this.sourceRules = context.sourceRules();
    }

    /**
     * @param objectDef The top level definition of the file
     * @return The file
     */
    JavaFile render(ObjectDef objectDef) {
        TypeSpec.Builder builder = typeBuilder(objectDef);
        if (context.exceptions().usesSneakyThrow()) {
            builder.addMethod(JavaExceptionRules.sneakyThrowHelper());
        }
        return JavaFile.builder(objectDef.getPackageName(), builder.build()).build();
    }

    private TypeSpec.Builder getInterfaceBuilder(InterfaceDef interfaceDef) {
        TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(names.declaredSimpleName(interfaceDef));
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

        for (MethodDef method : OverloadRules.writtenMethods(interfaceDef, context.scope())) {
            interfaceBuilder.addMethod(
                asMethodSpec(interfaceDef, method)
            );
        }
        generator.customizeTypeBuilder(interfaceDef, interfaceBuilder);
        return interfaceBuilder;
    }

    private TypeSpec.Builder getEnumBuilder(EnumDef enumDef) {
        TypeSpec.Builder enumBuilder = TypeSpec.enumBuilder(names.declaredSimpleName(enumDef));
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
                    expBuilder.add(expressions.renderExpression(null, null, RenderScope.root(null), constructorArgs.get(i)));
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

        for (MethodDef method : OverloadRules.writtenMethods(enumDef, context.scope())) {
            enumBuilder.addMethod(
                asMethodSpec(enumDef, method)
            );
        }
        addInnerTypes(enumDef.getInnerTypes(), enumBuilder, false);
        generator.customizeTypeBuilder(enumDef, enumBuilder);
        return enumBuilder;
    }

    private TypeSpec.Builder getAnnotationObjectBuilder(AnnotationObjectDef def) {
        TypeSpec.Builder builder = TypeSpec.annotationBuilder(names.declaredSimpleName(def));
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
                    .mapToObj(i -> literals.render(def, null,
                        new ExpressionDef.Constant(arrayDef.componentType(), Array.get(constantValue, i))))
                    .collect(CodeBlock.joining(", "));
                return CodeBlock.concat(CodeBlock.of("{"), values, CodeBlock.of("}"));
            }
        }
        return expressions.renderExpression(def, null, RenderScope.root(null), defaultValue);
    }

    private TypeSpec.Builder getClassBuilder(ClassDef classDef) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(names.declaredSimpleName(classDef));
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

        for (MethodDef method : OverloadRules.writtenMethods(classDef, context.scope())) {
            classBuilder.addMethod(
                asMethodSpec(classDef, method)
            );
        }

        StatementDef staticInitializer = classDef.getStaticInitializer();
        if (staticInitializer != null) {
            classBuilder.addStaticBlock(statementRenderer.renderInitializer(classDef, staticInitializer));
        }
        generator.customizeTypeBuilder(classDef, classBuilder);
        return classBuilder;
    }

    private TypeSpec.Builder getRecordBuilder(RecordDef recordDef) {
        TypeSpec.Builder classBuilder = TypeSpec.recordBuilder(names.declaredSimpleName(recordDef));
        classBuilder.addModifiers(recordDef.getModifiersArray());
        recordDef.getTypeVariables().stream().map(t -> asTypeVariable(t, recordDef)).forEach(classBuilder::addTypeVariable);
        recordDef.getSuperinterfaces().stream().map(typeDef -> asType(typeDef, recordDef)).forEach(classBuilder::addSuperinterface);
        recordDef.getJavadoc().forEach(classBuilder::addJavadoc);

        for (AnnotationDef annotation : recordDef.getAnnotations()) {
            classBuilder.addAnnotation(asAnnotationSpec(annotation));
        }
        for (PropertyDef property : recordDef.getProperties()) {
            // The accessor of a component overriding a generic method with its erasure returns the substituted type,
            // which Java requires the component to have
            TypeDef narrowed = OverrideResolver.recordComponentType(recordDef, property, context.scope(), false);
            TypeName propertyType = asType(narrowed == null ? property.getType() : narrowed, recordDef);
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

        for (MethodDef method : OverloadRules.writtenMethods(recordDef, context.scope())) {
            classBuilder.addMethod(
                asMethodSpec(recordDef, method)
            );
        }
        generator.customizeTypeBuilder(recordDef, classBuilder);
        return classBuilder;
    }

    private TypeSpec.Builder typeBuilder(ObjectDef objectDef) {
        TypeSpec.Builder builder = switch (objectDef) {
            case ClassDef classDef -> getClassBuilder(classDef);
            case RecordDef recordDef -> getRecordBuilder(recordDef);
            case InterfaceDef interfaceDef -> getInterfaceBuilder(interfaceDef);
            case EnumDef enumDef -> getEnumBuilder(enumDef);
            case AnnotationObjectDef annotationDef -> getAnnotationObjectBuilder(annotationDef);
            case null, default ->
                throw new IllegalStateException("Unknown object definition: " + objectDef);
        };
        return qualifyObscured(objectDef, builder);
    }

    private void addInnerTypes(List<ObjectDef> innerTypes, TypeSpec.Builder classBuilder, boolean isInterface) {
        for (ObjectDef innerType : innerTypes) {
            TypeSpec.Builder innerBuilder = typeBuilder(innerType);
            if (isInterface) {
                innerBuilder.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
            }
            classBuilder.addType(innerBuilder.build());
        }
    }

    /**
     * Qualifies the types named like a variable of the definition, which obscures them (JLS 6.4.2): {@code Math.max}
     * of a local named {@code Math} calls a method of the local.
     */
    private static TypeSpec.Builder qualifyObscured(ObjectDef objectDef, TypeSpec.Builder builder) {
        return builder.alwaysQualify(RenderScope.obscuringNames(objectDef).toArray(String[]::new));
    }

    private void buildFields(ObjectDef objectDef, List<FieldDef> fields, TypeSpec.Builder builder) {
        for (FieldDef field : fields) {
            // A blank final (no inline initializer) that a static initializer assigns conditionally - e.g. one of
            // two outcomes of a try/catch - or not at all fails Java's definite-assignment checks even though the
            // field is written exactly once at runtime; those checks don't apply to bytecode written directly,
            // which is what this pattern was designed for. `final` is kept wherever the initializer does assign
            // the field exactly once and unconditionally, which those checks accept.
            // A blank final instance field the constructors do not assign exactly once, the same
            boolean blankFinal = field.getModifiers().contains(Modifier.FINAL) && field.getInitializer().isEmpty()
                && !(objectDef instanceof InterfaceDef);
            boolean notFinal = blankFinal && (field.getModifiers().contains(Modifier.STATIC) ? !sourceRules.keepsFinal(objectDef, field.getName())
                : !sourceRules.keepsFinalInstanceField(objectDef, field.getName()));
            Modifier[] modifiers = notFinal
                ? field.getModifiers().stream().filter(m -> m != Modifier.FINAL).toArray(Modifier[]::new)
                : field.getModifiersArray();
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                asType(field.getType(), objectDef, field.getModifiers().contains(Modifier.STATIC)),
                field.getName()
            ).addModifiers(modifiers);
            // Converted to the type of the field, as the bytecode puts it
            field.getInitializer().ifPresent(init ->
                fieldBuilder.initializer(expressions.renderStored(objectDef, null, RenderScope.root(null), field.getType(), init))
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
                .addStatement("return this.$L", propertyName)
                .build());
            if (objectDef instanceof ClassDef) {
                builder.addMethod(MethodSpec.methodBuilder("set" + capitalizedPropertyName)
                    .addModifiers(property.getModifiersArray())
                    .addParameter(ParameterSpec.builder(propertyType, propertyName).build())
                    .addStatement("this.$L = $L", propertyName, propertyName)
                    .build());
            }
        }
    }

    private MethodSpec asMethodSpec(ObjectDef objectDef, MethodDef method) {
        String methodName = method.getName();
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        TypeDef returnType = method.getReturnType();
        MethodDef renderMethod = method;
        // A model written for the bytecode writer overrides a generic method with its erased signature, which the
        // verifier accepts; as source it has to take the signature with the type arguments of the supertype
        OverrideResolver.OverriddenMethod overridden = OverrideResolver.resolve(objectDef, method, context.scope());
        if (overridden != null) {
            parameterTypes = overridden.parameterTypes();
            returnType = overridden.returnType();
            // The body is rendered against the resolved signature, so a returned value is cast to its type
            renderMethod = overridden.apply(method);
        }
        List<TypeDef> resolvedParameterTypes = parameterTypes;
        // The resolved method declares the variables the inherited one does - bounded as it bounds them, and renamed
        // where the declared ones would capture a variable of the class the resolved types name
        MethodDef scope = renderMethod;
        RenderScope methodScope = RenderScope.root(renderMethod).body(renderMethod.getStatements()).handling(scope.getThrowTypes());
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
            .addModifiers(method.getModifiersArray())
            .addParameters(
                IntStream.range(0, method.getParameters().size())
                    .mapToObj(i -> {
                        ParameterDef param = method.getParameters().get(i);
                        // A keyword, a valid name of a parameter in Kotlin, is written under another name
                        String name = methodScope.declareParameter(param.getName());
                        return ParameterSpec.builder(
                            asType(resolvedParameterTypes.get(i), objectDef, scope),
                            name,
                            param.getModifiersArray()
                        ).addAnnotations(param.getAnnotations().stream().map(this::asAnnotationSpec).toList()).build();
                    })
                    .toList()
            );
        if (!methodName.equals(MethodSpec.CONSTRUCTOR)) {
            methodBuilder.returns(asType(returnType, objectDef, scope));
        }
        for (TypeDef.TypeVariable typeVariable : scope.getTypeVariables()) {
            // A bound can name a variable of the class or of the method
            methodBuilder.addTypeVariable(asTypeVariable(typeVariable, objectDef, scope));
        }
        method.getJavadoc().forEach(methodBuilder::addJavadoc);
        for (AnnotationDef annotation : method.getAnnotations()) {
            methodBuilder.addAnnotation(
                asAnnotationSpec(annotation)
            );
        }
        for (TypeDef type: scope.getThrowTypes()) {
            methodBuilder.addException(asType(type, objectDef, scope));
        }
        context.enterMethod(renderMethod);
        try {
            methodBuilder.addCode(statementRenderer.renderBody(objectDef, renderMethod, methodScope, renderMethod.getStatements()));
        } finally {
            context.exitMethod();
        }

        return methodBuilder.build();
    }

    private TypeVariableName asTypeVariable(TypeDef.TypeVariable tv, @Nullable ObjectDef objectDef) {
        return asTypeVariable(tv, objectDef, null);
    }

    private TypeVariableName asTypeVariable(TypeDef.TypeVariable tv, @Nullable ObjectDef objectDef, @Nullable MethodDef method) {
        TypeName[] bounds = tv.bounds().stream().map(t -> asType(t, objectDef, method)).toArray(TypeName[]::new);
        if (bounds.length > 1 && TypeName.OBJECT.equals(bounds[0])) {
            // `T extends Object & Comparable<T>` erases to Object (JLS 4.6), as the bytecode writers describe it,
            // where JavaPoet drops the Object bound
            return TypeVariableName.get(tv.name()).withBounds(bounds);
        }
        return TypeVariableName.get(tv.name(), bounds);
    }

    private AnnotationSpec asAnnotationSpec(AnnotationDef annotationDef) {
        AnnotationSpec.Builder builder = AnnotationSpec.builder(names.asClassType(annotationDef.getType()));
        for (Map.Entry<String, Object> e : annotationDef.getValues().entrySet()) {
            addAnnotationValue(builder, e.getKey(), e.getValue());
        }
        return builder.build();
    }

    private void addAnnotationValue(AnnotationSpec.Builder builder, String memberName, Object value) {
        switch (value) {
            case Collection<?> collection -> {
                if (collection.isEmpty()) {
                    // Without a value the member is omitted, which reads as its default, or as none at all
                    builder.addMember(memberName, "{}");
                } else {
                    collection.forEach(v -> addAnnotationValue(builder, memberName, v));
                }
            }
            case AnnotationDef annotationValue ->
                builder.addMember(memberName, asAnnotationSpec(annotationValue));
            case VariableDef variableDef ->
                builder.addMember(memberName, expressions.renderVariable(null, null, RenderScope.root(null), variableDef));
            case Class<?> _ -> builder.addMember(memberName, "$T.class", value);
            case Enum<?> anEnum ->
                builder.addMember(memberName, "$T.$L", value.getClass(), anEnum.name());
            case String _ -> builder.addMember(memberName, "$S", value);
            case Float _ -> builder.addMember(memberName, "$Lf", value);
            case Long _ -> builder.addMember(memberName, "$LL", value);
            case Character _ ->
                builder.addMember(memberName, "'$L'", Util.characterLiteralWithoutSingleQuotes((char) value));
            case ClassTypeDef typeDef ->
                builder.addMember(memberName, "$L.class", typeDef.getSimpleName());
            case null -> builder.addMember(memberName, "$L", value);
            default -> {
                if (value.getClass().isArray()) {
                    // An array-typed annotation member (e.g. String[]) has no useful toString(); render
                    // each element as its own member value, the same way the Collection case does -
                    // JavaPoet merges repeated addMember calls for one name into a `{...}` initializer.
                    int length = java.lang.reflect.Array.getLength(value);
                    if (length == 0) {
                        builder.addMember(memberName, "{}");
                    }
                    for (int i = 0; i < length; i++) {
                        addAnnotationValue(builder, memberName, java.lang.reflect.Array.get(value, i));
                    }
                } else {
                    builder.addMember(memberName, "$L", value);
                }
            }
        }
    }

    TypeName asType(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        return asType(typeDef, objectDef, null, false);
    }

    TypeName asType(TypeDef typeDef, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        return asType(typeDef, objectDef, methodDef,
            methodDef != null && methodDef.getModifiers().contains(Modifier.STATIC));
    }

    TypeName asType(TypeDef typeDef, @Nullable ObjectDef objectDef, boolean staticContext) {
        return asType(typeDef, objectDef, null, staticContext);
    }

    TypeName asType(TypeDef typeDef,
                            @Nullable ObjectDef objectDef,
                            @Nullable MethodDef methodDef,
                            boolean staticContext) {
        if (typeDef.equals(TypeDef.THIS)) {
            return asSelfType(objectDef, methodDef, staticContext);
        }
        if (typeDef.equals(TypeDef.SUPER)) {
            return asSuperType(objectDef, methodDef, staticContext);
        }
        switch (typeDef) {
            case TypeDef.Array array -> {
                return asArrayType(array, objectDef, methodDef, staticContext);
            }
            case ClassTypeDef.Parameterized parameterized -> {
                TypeName[] arguments = parameterized.typeArguments().stream().map(t -> asType(t, objectDef, methodDef, staticContext)).toArray(TypeName[]::new);
                if (TypeHierarchy.enclosingOf(parameterized.rawType()) instanceof ClassTypeDef enclosing) {
                    return names.memberType(asType(enclosing, objectDef, methodDef, staticContext), TypeHierarchy.memberClass(parameterized.rawType()), arguments);
                }
                return ParameterizedTypeName.get(names.asClassType(parameterized.rawType()), arguments);
            }
            case ClassTypeDef memberClass when TypeHierarchy.enclosingOf(memberClass) instanceof ClassTypeDef enclosing -> {
                return names.memberType(asType(enclosing, objectDef, methodDef, staticContext), TypeHierarchy.memberClass(memberClass));
            }
            case TypeDef.Primitive primitive -> {
                return asPrimitiveType(primitive);
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotatedType -> {
                var annotationsSpecs = annotatedType.annotations().stream().map(this::asAnnotationSpec).toList();
                return asType(annotatedType.typeDef(), objectDef, methodDef, staticContext).annotated(annotationsSpecs);
            }
            case ClassTypeDef classType -> {
                return names.asClassType(classType);
            }
            case TypeDef.Wildcard wildcard -> {
                return asWildcardType(wildcard, objectDef, methodDef, staticContext);
            }
            case TypeDef.TypeVariable typeVariable -> {
                return asTypeVariableType(typeVariable, objectDef, methodDef, staticContext);
            }
            case TypeDef.AnnotatedTypeDef annotatedType -> {
                var annotationsSpecs = annotatedType.annotations().stream().map(this::asAnnotationSpec).toList();
                return asType(annotatedType.typeDef(), objectDef, methodDef, staticContext).annotated(annotationsSpecs);
            }
            default -> throw new IllegalStateException("Unrecognized type definition " + typeDef);
        }
    }

    /**
     * The self type in the scope it is written in. In a static context the enclosing definition is dropped,
     * so its type variables are not treated as being in scope.
     */
    private TypeName asSelfType(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, boolean staticContext) {
        if (objectDef == null) {
            throw new IllegalStateException("This type is used outside of the instance scope!");
        }
        // The scope is kept: the self type of a generic definition carries the variables it declares
        return asType(objectDef.asTypeDef(), staticContext ? null : objectDef, methodDef, staticContext);
    }

    private TypeName asSuperType(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, boolean staticContext) {
        if (objectDef == null) {
            throw new IllegalStateException("Super type is used outside of the instance scope!");
        }
        if (objectDef instanceof ClassDef classDef) {
            return asType(Objects.requireNonNullElse(classDef.getSuperclass(), ClassTypeDef.OBJECT), objectDef, methodDef, staticContext);
        }
        if (objectDef instanceof EnumDef) {
            return names.asClassType(ClassTypeDef.of(Enum.class));
        }
        throw new IllegalStateException("Super type is not supported for " + objectDef);
    }

    private TypeName asArrayType(TypeDef.Array array,
                                 @Nullable ObjectDef objectDef,
                                 @Nullable MethodDef methodDef,
                                 boolean staticContext) {
        TypeName arrayTypeName = ArrayTypeName.of(asType(array.componentType(), objectDef, methodDef, staticContext));
        for (int i = 1; i < array.dimensions(); ++i) {
            arrayTypeName = ArrayTypeName.of(arrayTypeName);
        }
        return arrayTypeName;
    }

    private TypeName asWildcardType(TypeDef.Wildcard wildcard,
                                    @Nullable ObjectDef objectDef,
                                    @Nullable MethodDef methodDef,
                                    boolean staticContext) {
        if (!wildcard.lowerBounds().isEmpty()) {
            return WildcardTypeName.supertypeOf(asType(wildcard.lowerBounds().get(0), objectDef, methodDef, staticContext));
        }
        return WildcardTypeName.subtypeOf(asType(wildcard.upperBounds().get(0), objectDef, methodDef, staticContext));
    }

    private TypeName asTypeVariableType(TypeDef.TypeVariable typeVariable,
                                        @Nullable ObjectDef objectDef,
                                        @Nullable MethodDef methodDef,
                                        boolean staticContext) {
        if (names.isVariablePartOfTheDefinition(typeVariable.name(), objectDef, methodDef, staticContext)) {
            return asTypeVariable(typeVariable, objectDef);
        }
        if (typeVariable.bounds().isEmpty()) {
            return asType(ClassTypeDef.OBJECT, objectDef, methodDef, staticContext);
        }
        return asType(typeVariable.bounds().get(0), objectDef, methodDef, staticContext);
    }
}
