/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.bytecode.core.AnnotationTargetUtils;
import io.micronaut.sourcegen.bytecode.core.BridgeResolver;
import io.micronaut.sourcegen.bytecode.core.ModifierUtils;
import io.micronaut.sourcegen.bytecode.core.SignatureUtils;
import io.micronaut.sourcegen.bytecode.core.TypeUtils;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.FieldBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import javax.lang.model.element.Modifier;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emits class structure and delegates method bodies to the direct JDK method writer.
 *
 * @since 2.2
 */
@Internal
final class JdkClassFileWriter {

    private final boolean verify;
    private final List<MethodDef> syntheticMethods = new ArrayList<>();

    JdkClassFileWriter(boolean verify) {
        this.verify = verify;
    }

    Optional<byte[]> write(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        if (outerType != null || !supported(objectDef)) {
            return Optional.empty();
        }
        ClassDesc owner = classDesc(objectDef.asTypeDef(), objectDef);
        ClassFile classFile = ClassFile.of(
            ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
            ClassFile.ClassHierarchyResolverOption.of(new SourcegenClassHierarchyResolver(
                Map.of(), SourcegenClassHierarchyResolver.classElements(objectDef),
                List.of(objectDef),
                JdkClassFileWriter.class.getClassLoader()
            ))
        );
        try {
            byte[] bytes = classFile.build(owner, builder -> writeObject(builder, objectDef, owner));
            if (verify) {
                List<VerifyError> errors = classFile.verify(bytes);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("JDK verification failed for " + objectDef.getName() + ": " + errors);
                }
            }
            return Optional.of(bytes);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void writeObject(ClassBuilder builder, ObjectDef objectDef, ClassDesc owner) {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(builder, classDef, owner);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(builder, recordDef, owner);
        }
    }

    private void writeRecord(ClassBuilder builder, RecordDef recordDef, ClassDesc owner) {
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(ModifierUtils.objectFlags(recordDef))
            .withSuperclass(ClassDesc.of("java.lang.Record"))
            .withInterfaceSymbols(recordDef.getSuperinterfaces().stream()
                .map(type -> classDesc(type, recordDef)).toList());
        addAnnotations(builder, recordDef.getAnnotations());
        addSignature(builder, SignatureUtils.getRecordSignature(recordDef));

        List<RecordComponentInfo> components = new ArrayList<>();
        for (PropertyDef property : recordDef.getProperties()) {
            TypeDef componentType = componentType(property);
            ClassDesc type = classDesc(componentType, recordDef);
            FieldDef field = FieldDef.builder(property.getName(), componentType)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .addAnnotations(annotationsFor(property, ElementType.FIELD))
                .build();
            List<java.lang.classfile.Attribute<?>> attributes = new ArrayList<>();
            if (!annotationsFor(property, ElementType.RECORD_COMPONENT).isEmpty()) {
                attributes.add(RuntimeVisibleAnnotationsAttribute.of(
                    annotationsFor(property, ElementType.RECORD_COMPONENT).stream()
                        .map(ByteCodeWriter::toAnnotation).toList()
                ));
            }
            String signature = SignatureUtils.getFieldSignature(recordDef, field);
            if (signature != null) {
                attributes.add(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
            }
            if (!typeAnnotations(componentType, TypeAnnotation.TargetInfo.ofField()).isEmpty()) {
                attributes.add(RuntimeVisibleTypeAnnotationsAttribute.of(
                    typeAnnotations(componentType, TypeAnnotation.TargetInfo.ofField())
                ));
            }
            components.add(RecordComponentInfo.of(property.getName(), type, attributes));
            writeField(builder, recordDef, field);
        }
        builder.with(RecordAttribute.of(components));

        List<TypeDef> componentTypes = recordDef.getProperties().stream().map(PropertyDef::getType).toList();
        if (!hasDeclared(recordDef, MethodDef.CONSTRUCTOR, componentTypes)) {
            writeRecordConstructor(builder, recordDef, owner);
        }
        for (PropertyDef property : recordDef.getProperties()) {
            if (!hasDeclared(recordDef, property.getName(), List.of())) {
                FieldDef field = FieldDef.builder(property.getName(), componentType(property))
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                    .build();
                MethodDef accessor = MethodDef.builder(property.getName())
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .returns(componentType(property))
                    .addAnnotations(annotationsFor(property, ElementType.METHOD))
                    .build((aThis, ignored) -> aThis.field(field).returning());
                writeMethod(builder, recordDef, accessor, owner);
                writeBridgeMethods(builder, recordDef, accessor, owner);
            }
        }
        writeRecordObjectMethods(builder, recordDef, owner);
        for (MethodDef method : recordDef.getMethods()) {
            if (!method.isConstructor()) {
                if (method.getModifiers().contains(Modifier.ABSTRACT)
                    || method.getModifiers().contains(Modifier.NATIVE)) {
                    writeAbstractMethod(builder, recordDef, method);
                } else {
                    writeMethod(builder, recordDef, method, owner);
                }
                writeBridgeMethods(builder, recordDef, method, owner);
            }
        }
    }

    private void writeRecordConstructor(ClassBuilder builder, RecordDef recordDef, ClassDesc owner) {
        List<PropertyDef> properties = recordDef.getProperties();
        MethodTypeDesc type = MethodTypeDesc.of(
            ConstantDescs.CD_void,
            properties.stream().map(property -> classDesc(property.getType(), recordDef)).toList()
        );
        builder.withMethod(MethodDef.CONSTRUCTOR, type,
            constructorFlags(recordDef), methodBuilder -> {
                MethodDef.MethodDefBuilder canonicalBuilder = MethodDef.constructor();
                properties.forEach(property -> canonicalBuilder.addParameter(ParameterDef.builder(
                    property.getName(), componentType(property)
                ).addAnnotations(annotationsFor(property, ElementType.PARAMETER)).build()));
                MethodDef canonical = canonicalBuilder.build();
                addMethodMetadata(methodBuilder, recordDef, canonical);
                List<TypeAnnotation> typeAnnotations = new ArrayList<>();
                for (int i = 0; i < properties.size(); i++) {
                    typeAnnotations.addAll(typeAnnotations(
                        componentType(properties.get(i)), TypeAnnotation.TargetInfo.ofMethodFormalParameter(i)
                    ));
                }
                addTypeAnnotations(methodBuilder, typeAnnotations);
                methodBuilder.withCode(code -> {
                    code.aload(0).invokespecial(ClassDesc.of("java.lang.Record"), MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
                    for (int i = 0; i < properties.size(); i++) {
                        PropertyDef property = properties.get(i);
                        FieldDef field = FieldDef.builder(property.getName(), property.getType())
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
                        code.aload(0).loadLocal(
                            TypeKind.fromDescriptor(TypeUtils.getDescriptor(property.getType(), recordDef)).asLoadable(),
                            code.parameterSlot(i)
                        ).putfield(owner, field.getName(), classDesc(field.getType(), recordDef));
                    }
                    code.return_();
                });
            });
    }

    private void writeRecordObjectMethods(ClassBuilder builder, RecordDef recordDef, ClassDesc owner) {
        List<PropertyDef> properties = recordDef.getProperties();
        String names = properties.stream().map(PropertyDef::getName).collect(java.util.stream.Collectors.joining(";"));
        ClassDesc objectMethods = ClassDesc.of("java.lang.runtime.ObjectMethods");
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String,
            ClassDesc.of("java.lang.invoke.TypeDescriptor"),
            ConstantDescs.CD_Class, ConstantDescs.CD_String, ConstantDescs.CD_MethodHandle.arrayType());
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            objectMethods, "bootstrap", bootstrapType);
        List<DirectMethodHandleDesc> fieldHandles = properties.stream()
            .map(property -> MethodHandleDesc.ofField(DirectMethodHandleDesc.Kind.GETTER, owner,
                property.getName(), classDesc(property.getType(), recordDef)))
            .toList();
        writeRecordObjectMethod(builder, recordDef, owner, "toString", MethodTypeDesc.of(ConstantDescs.CD_String),
            MethodTypeDesc.of(ConstantDescs.CD_String, owner), bootstrap, names, fieldHandles);
        writeRecordObjectMethod(builder, recordDef, owner, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int),
            MethodTypeDesc.of(ConstantDescs.CD_int, owner), bootstrap, names, fieldHandles);
        if (!hasDeclared(recordDef, "equals", List.of(TypeDef.OBJECT))) {
            writeRecordObjectMethod(builder, recordDef, owner, "equals",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                MethodTypeDesc.of(ConstantDescs.CD_boolean, owner, ConstantDescs.CD_Object),
                bootstrap, names, fieldHandles);
        }
    }

    private void writeRecordObjectMethod(ClassBuilder builder, RecordDef recordDef, ClassDesc owner, String name,
                                         MethodTypeDesc methodType, MethodTypeDesc callSiteType,
                                         DirectMethodHandleDesc bootstrap, String names,
                                         List<? extends MethodHandleDesc> fieldHandles) {
        if (hasDeclared(recordDef, name, name.equals("equals") ? List.of(TypeDef.OBJECT) : List.of())) {
            return;
        }
        List<java.lang.constant.ConstantDesc> arguments = new ArrayList<>();
        arguments.add(owner);
        arguments.add(names);
        arguments.addAll(fieldHandles);
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(bootstrap, name, callSiteType,
            arguments.toArray(java.lang.constant.ConstantDesc[]::new));
        builder.withMethod(name, methodType, ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_FINAL, methodBuilder ->
            methodBuilder.withCode(code -> {
                code.aload(0);
                if (name.equals("equals")) {
                    code.aload(code.parameterSlot(0));
                }
                code.invokedynamic(callSite).return_(TypeKind.fromDescriptor(methodType.returnType().descriptorString()).asLoadable());
            }));
    }

    private void writeClass(ClassBuilder builder, ClassDef classDef, ClassDesc owner) {
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(ModifierUtils.classFlags(classDef.getModifiers(), null));
        addAnnotations(builder, classDef.getAnnotations());
        addSignature(builder, classSignature(classDef));
        ClassTypeDef superclass = classDef.getSuperclass();
        builder.withSuperclass(superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef));
        builder.withInterfaceSymbols(classDef.getSuperinterfaces().stream()
            .map(type -> classDesc(type, classDef)).toList());
        for (FieldDef field : classDef.getFields()) {
            writeField(builder, classDef, field);
        }
        for (PropertyDef property : classDef.getProperties()) {
            writePropertyField(builder, classDef, property);
        }
        List<StatementDef> staticInitializer = classDef.getFields().stream()
            .filter(field -> field.getModifiers().contains(Modifier.STATIC))
            .flatMap(field -> field.getInitializer()
                .<StatementDef>map(initializer -> classDef.asTypeDef().getStaticField(field).put(initializer)).stream())
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (classDef.getStaticInitializer() != null) {
            staticInitializer.add(classDef.getStaticInitializer());
        }
        if (!staticInitializer.isEmpty()) {
            MethodDef initializer = MethodDef.builder("<clinit>")
                .addModifiers(Modifier.STATIC)
                .addStatement(StatementDef.multi(staticInitializer))
                .build();
            writeMethod(builder, classDef, initializer, owner);
        }
        List<MethodDef> constructors = classDef.getMethods().stream().filter(MethodDef::isConstructor).toList();
        if (constructors.isEmpty()) {
            writeDefaultConstructor(builder, classDef, superclass, owner);
        } else {
            constructors.forEach(method -> writeConstructor(builder, classDef, method, owner));
        }
        for (PropertyDef property : classDef.getProperties()) {
            writePropertyMethods(builder, classDef, property, owner);
        }
        for (MethodDef method : classDef.getMethods()) {
            if (!method.isConstructor()) {
                if (method.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)
                    || method.getModifiers().contains(javax.lang.model.element.Modifier.NATIVE)) {
                    writeAbstractMethod(builder, classDef, method);
                } else {
                    writeMethod(builder, classDef, method, owner);
                }
                writeBridgeMethods(builder, classDef, method, owner);
            }
        }
        // Lambda expressions are represented by invokedynamic call sites which point at
        // private static implementation methods.  Emit them after the user methods so that
        // lambdas created while writing a method can enqueue further nested lambdas.
        for (int i = 0; i < syntheticMethods.size(); i++) {
            writeMethod(builder, classDef, syntheticMethods.get(i), owner);
        }
    }

    private void writeDefaultConstructor(ClassBuilder builder, ClassDef classDef, @Nullable ClassTypeDef superclass,
                                         ClassDesc owner) {
        int flags = classDef.getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC)
            ? ModifierUtils.ACC_PUBLIC : 0;
        builder.withMethod(MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void, flags, methodBuilder -> {
            methodBuilder.withCode(code -> {
            code.aload(0).invokespecial(superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef),
                MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
            JdkMethodWriter writer = JdkMethodWriter.create(code, classDef,
                MethodDef.constructor().build(), owner);
            writeInstanceInitializers(writer, classDef);
            writer.writeLocalVariables();
            code.return_();
            });
        });
    }

    private void writeConstructor(ClassBuilder builder, ClassDef classDef, MethodDef method, ClassDesc owner) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(classDef, method));
        builder.withMethod(method.getName(), type, methodFlags(method), methodBuilder -> {
            addMethodMetadata(methodBuilder, classDef, method);
            addTypeAnnotations(methodBuilder, parameterTypeAnnotations(method));
            methodBuilder.withCode(code -> {
                JdkMethodWriter writer = JdkMethodWriter.create(code, classDef, method, owner);
                int bodyStart = 0;
                if (!method.getStatements().isEmpty()
                    && method.getStatements().get(0) instanceof StatementDef.InvokeSuperConstructor) {
                    writer.writeStatements(List.of(method.getStatements().get(0)));
                    bodyStart = 1;
                } else {
                    ClassTypeDef superclass = classDef.getSuperclass();
                    code.aload(0).invokespecial(superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef),
                        MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
                }
                writeInstanceInitializers(writer, classDef);
                writer.writeStatements(method.getStatements().subList(bodyStart, method.getStatements().size()));
                syntheticMethods.addAll(writer.lambdaMethods());
                if (canCompleteNormally(method.getStatements().subList(bodyStart, method.getStatements().size()))) {
                    code.return_();
                }
                writer.writeLocalVariables();
            });
        });
    }

    private void writeMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner) {
        writeMethod(builder, objectDef, method, owner, 0);
    }

    private void writeMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner, int extraFlags) {
        writeMethod(builder, objectDef, method, owner, extraFlags, List.of());
    }

    private void writeMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner,
                             int extraFlags, List<TypeAnnotation> typeAnnotations) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(objectDef, method));
        int flags = methodFlags(method) | extraFlags;
        builder.withMethod(method.getName(), type, flags, methodBuilder -> {
            addMethodMetadata(methodBuilder, objectDef, method, (extraFlags & ModifierUtils.ACC_BRIDGE) == 0);
            List<TypeAnnotation> methodTypeAnnotations = new ArrayList<>(typeAnnotations);
            if (!method.isConstructor()) {
                methodTypeAnnotations.addAll(typeAnnotations(method.getReturnType(), TypeAnnotation.TargetInfo.ofMethodReturn()));
            }
            for (int i = 0; i < method.getParameters().size(); i++) {
                methodTypeAnnotations.addAll(typeAnnotations(
                    method.getParameters().get(i).getType(), TypeAnnotation.TargetInfo.ofMethodFormalParameter(i)
                ));
            }
            addTypeAnnotations(methodBuilder, methodTypeAnnotations);
            methodBuilder.withCode(code -> {
                if (method.getStatements().isEmpty()) {
                    code.return_();
                } else {
                    JdkMethodWriter writer = JdkMethodWriter.create(code, objectDef, method, owner);
                    writer.writeStatements(method.getStatements());
                    syntheticMethods.addAll(writer.lambdaMethods());
                    if (canCompleteNormally(method.getStatements())) {
                        code.return_();
                    }
                    writer.writeLocalVariables();
                }
            });
        });
    }

    private void writeAbstractMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method) {
        writeAbstractMethod(builder, objectDef, method, 0);
    }

    private void writeAbstractMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, int extraFlags) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(objectDef, method));
        builder.withMethod(method.getName(), type, methodFlags(method) | extraFlags, methodBuilder -> {
            addMethodMetadata(methodBuilder, objectDef, method, (extraFlags & ModifierUtils.ACC_BRIDGE) == 0);
            addTypeAnnotations(methodBuilder, methodTypeAnnotations(method));
        });
    }

    private static int methodFlags(MethodDef method) {
        int flags = ModifierUtils.memberFlags(method.getModifiers());
        if (method.isSynthetic()) {
            flags |= ModifierUtils.ACC_SYNTHETIC;
        }
        return flags;
    }

    private static int constructorFlags(RecordDef recordDef) {
        int flags = 0;
        if (recordDef.getModifiers().contains(Modifier.PUBLIC)) {
            flags |= ModifierUtils.ACC_PUBLIC;
        } else if (recordDef.getModifiers().contains(Modifier.PROTECTED)) {
            flags |= ModifierUtils.ACC_PROTECTED;
        } else if (recordDef.getModifiers().contains(Modifier.PRIVATE)) {
            flags |= ModifierUtils.ACC_PRIVATE;
        }
        return flags;
    }

    private void writeField(ClassBuilder builder, ObjectDef objectDef, FieldDef field) {
        builder.withField(field.getName(), classDesc(field.getType(), objectDef), fieldBuilder -> {
            int flags = ModifierUtils.memberFlags(field.getModifiers());
            if (field.isSynthetic()) {
                flags |= ModifierUtils.ACC_SYNTHETIC;
            }
            fieldBuilder.withFlags(flags);
            addAnnotations(fieldBuilder, field.getAnnotations());
            addTypeAnnotations(fieldBuilder, typeAnnotations(field.getType(), TypeAnnotation.TargetInfo.ofField()));
            addSignature(fieldBuilder, SignatureUtils.getFieldSignature(objectDef, field));
        });
    }

    private void writePropertyField(ClassBuilder builder, ClassDef classDef, PropertyDef property) {
        FieldDef field = FieldDef.builder(property.getName(), property.getType())
            .addModifiers(Modifier.PRIVATE)
            .addAnnotations(property.getAnnotations())
            .build();
        writeField(builder, classDef, field);
    }

    private void writePropertyMethods(ClassBuilder builder, ClassDef classDef, PropertyDef property, ClassDesc owner) {
        FieldDef field = FieldDef.builder(property.getName(), property.getType())
            .addModifiers(Modifier.PRIVATE)
            .addAnnotations(property.getAnnotations())
            .build();
        String capitalized = io.micronaut.core.naming.NameUtils.capitalize(property.getName());
        MethodDef getter = MethodDef.builder("get" + capitalized)
            .addModifiers(property.getModifiersArray())
            .build((aThis, parameters) -> aThis.field(field).returning());
        writeMethod(builder, classDef, getter, owner);
        MethodDef setter = MethodDef.builder("set" + capitalized)
            .addParameter(property.getName(), property.getType())
            .addModifiers(property.getModifiersArray())
            .build((aThis, parameters) -> aThis.field(field).assign(parameters.get(0)));
        writeMethod(builder, classDef, setter, owner);
    }

    private static void writeInstanceInitializers(JdkMethodWriter writer, ClassDef classDef) {
        for (FieldDef field : classDef.getFields()) {
            if (!field.getModifiers().contains(Modifier.STATIC)) {
                field.getInitializer().ifPresent(initializer -> writer.writeInstanceInitializer(classDef, field, initializer));
            }
        }
    }

    @Nullable
    private static String classSignature(ClassDef classDef) {
        if (!classDef.getTypeVariables().isEmpty()
            || classDef.getSuperclass() instanceof ClassTypeDef.Parameterized
            || classDef.getSuperinterfaces().stream().anyMatch(type -> type instanceof ClassTypeDef.Parameterized)) {
            return SignatureUtils.getClassSignature(classDef);
        }
        return null;
    }

    private static void addMethodMetadata(MethodBuilder builder, ObjectDef objectDef, MethodDef method) {
        addMethodMetadata(builder, objectDef, method, true);
    }

    private static void addMethodMetadata(MethodBuilder builder, ObjectDef objectDef, MethodDef method,
                                          boolean addGenericSignature) {
        addAnnotations(builder, method.getAnnotations());
        if (!method.getParameters().isEmpty()) {
            builder.with(MethodParametersAttribute.of(method.getParameters().stream()
                .map(parameter -> MethodParameterInfo.of(Optional.of(parameter.getName())))
                .toList()));
        }
        if (method.getParameters().stream().anyMatch(parameter -> !parameter.getAnnotations().isEmpty())) {
            builder.with(RuntimeVisibleParameterAnnotationsAttribute.of(method.getParameters().stream()
                .map(parameter -> parameter.getAnnotations().stream().map(ByteCodeWriter::toAnnotation).toList())
                .toList()));
        }
        if (!method.getThrowTypes().isEmpty()) {
            addExceptions(builder, method.getThrowTypes(), objectDef);
        }
        if (addGenericSignature) {
            addSignature(builder, SignatureUtils.getMethodSignature(objectDef, method));
        }
    }

    private static void addExceptions(MethodBuilder builder, List<TypeDef> types, ObjectDef objectDef) {
        builder.with(ExceptionsAttribute.ofSymbols(types.stream().map(type -> classDesc(type, objectDef)).toList()));
    }

    private static void addTypeAnnotations(MethodBuilder builder, List<TypeAnnotation> annotations) {
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleTypeAnnotationsAttribute.of(annotations));
        }
    }

    private static void addTypeAnnotations(FieldBuilder builder, List<TypeAnnotation> annotations) {
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleTypeAnnotationsAttribute.of(annotations));
        }
    }

    private static List<TypeAnnotation> methodTypeAnnotations(MethodDef method) {
        List<TypeAnnotation> annotations = new ArrayList<>();
        if (!method.isConstructor()) {
            annotations.addAll(typeAnnotations(method.getReturnType(), TypeAnnotation.TargetInfo.ofMethodReturn()));
        }
        annotations.addAll(parameterTypeAnnotations(method));
        return annotations;
    }

    private static List<TypeAnnotation> parameterTypeAnnotations(MethodDef method) {
        List<TypeAnnotation> annotations = new ArrayList<>();
        for (int i = 0; i < method.getParameters().size(); i++) {
            annotations.addAll(typeAnnotations(
                method.getParameters().get(i).getType(), TypeAnnotation.TargetInfo.ofMethodFormalParameter(i)
            ));
        }
        return annotations;
    }

    private static List<TypeAnnotation> typeAnnotations(TypeDef type, TypeAnnotation.TargetInfo targetInfo) {
        List<TypeAnnotation> annotations = new ArrayList<>();
        collectTypeAnnotations(type, targetInfo, List.of(), annotations);
        return annotations;
    }

    private static void collectTypeAnnotations(TypeDef type,
                                               TypeAnnotation.TargetInfo targetInfo,
                                               List<TypeAnnotation.TypePathComponent> path,
                                               List<TypeAnnotation> result) {
        switch (type) {
            case TypeDef.AnnotatedTypeDef annotated -> {
                for (AnnotationDef annotation : annotated.annotations()) {
                    result.add(TypeAnnotation.of(targetInfo, path, ByteCodeWriter.toAnnotation(annotation)));
                }
                collectTypeAnnotations(annotated.typeDef(), targetInfo, path, result);
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> {
                for (AnnotationDef annotation : annotated.annotations()) {
                    result.add(TypeAnnotation.of(targetInfo, path, ByteCodeWriter.toAnnotation(annotation)));
                }
                collectTypeAnnotations(annotated.typeDef(), targetInfo, path, result);
            }
            case TypeDef.Array array -> collectTypeAnnotations(array.componentType(), targetInfo,
                appendPath(path, TypeAnnotation.TypePathComponent.ARRAY, array.dimensions()), result);
            case ClassTypeDef.Parameterized parameterized -> {
                collectTypeAnnotations(parameterized.rawType(), targetInfo, path, result);
                for (int i = 0; i < parameterized.typeArguments().size(); i++) {
                    collectTypeAnnotations(parameterized.typeArguments().get(i), targetInfo,
                        appendPath(path, TypeAnnotation.TypePathComponent.of(
                            TypeAnnotation.TypePathComponent.Kind.TYPE_ARGUMENT, i
                        ), 1), result);
                }
            }
            case ClassTypeDef _, TypeDef.Primitive _, TypeDef.TypeVariable _, TypeDef.Wildcard _ -> {
            }
        }
    }

    private static List<TypeAnnotation.TypePathComponent> appendPath(List<TypeAnnotation.TypePathComponent> path,
                                                                       TypeAnnotation.TypePathComponent component,
                                                                       int count) {
        List<TypeAnnotation.TypePathComponent> result = new ArrayList<>(path);
        for (int i = 0; i < count; i++) {
            result.add(component);
        }
        return result;
    }

    private static TypeDef componentType(PropertyDef property) {
        List<AnnotationDef> typeAnnotations = property.getAnnotations().stream()
            .filter(annotation -> AnnotationTargetUtils.targetsOf(annotation, JdkClassFileWriter.class.getClassLoader())
                .map(targets -> targets.contains(ElementType.TYPE_USE)).orElse(false))
            .toList();
        return typeAnnotations.isEmpty() ? property.getType() : annotateComponentType(property.getType(), typeAnnotations);
    }

    private static TypeDef annotateComponentType(TypeDef type, List<AnnotationDef> annotations) {
        if (type instanceof TypeDef.AnnotatedTypeDef annotated) {
            return new TypeDef.AnnotatedTypeDef(annotateComponentType(annotated.typeDef(), annotations), annotated.annotations());
        }
        if (type instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return new ClassTypeDef.AnnotatedClassTypeDef(
                (ClassTypeDef) annotateComponentType(annotated.typeDef(), annotations), annotated.annotations()
            );
        }
        if (type instanceof TypeDef.Array array) {
            return new TypeDef.Array(annotateComponentType(array.componentType(), annotations), array.dimensions(), array.nullable());
        }
        return type.annotated(annotations);
    }

    private static List<AnnotationDef> annotationsFor(PropertyDef property, ElementType elementType) {
        return property.getAnnotations().stream()
            .filter(annotation -> AnnotationTargetUtils.targetsOf(annotation, JdkClassFileWriter.class.getClassLoader())
                .map(targets -> targets.contains(elementType)).orElse(true))
            .toList();
    }

    private static boolean canCompleteNormally(List<StatementDef> statements) {
        if (statements.isEmpty()) {
            return true;
        }
        StatementDef last = statements.getLast();
        if (last instanceof StatementDef.Multi multi) {
            return canCompleteNormally(multi.flatten());
        }
        if (last instanceof StatementDef.IfElse ifElse) {
            return canCompleteNormally(ifElse.statement().flatten())
                || canCompleteNormally(ifElse.elseStatement().flatten());
        }
        return !(last instanceof StatementDef.Return || last instanceof StatementDef.Throw);
    }

    private static void addAnnotations(ClassBuilder builder, List<AnnotationDef> annotations) {
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(annotations.stream()
                .map(ByteCodeWriter::toAnnotation).toList()));
        }
    }

    private static void addAnnotations(MethodBuilder builder, List<AnnotationDef> annotations) {
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(annotations.stream()
                .map(ByteCodeWriter::toAnnotation).toList()));
        }
    }

    private static void addAnnotations(FieldBuilder builder, List<AnnotationDef> annotations) {
        if (!annotations.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(annotations.stream()
                .map(ByteCodeWriter::toAnnotation).toList()));
        }
    }

    private static void addSignature(ClassBuilder builder, @Nullable String signature) {
        if (signature != null) {
            builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
        }
    }

    private static void addSignature(MethodBuilder builder, @Nullable String signature) {
        if (signature != null) {
            builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
        }
    }

    private static void addSignature(FieldBuilder builder, @Nullable String signature) {
        if (signature != null) {
            builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
        }
    }

    private static boolean supported(ObjectDef objectDef) {
        if (!objectDef.getInnerTypes().isEmpty()) {
            return false;
        }
        if (objectDef instanceof RecordDef recordDef) {
            // A user-declared record constructor has special canonical-constructor rules. Let the
            // source fallback handle that uncommon form until it can be lowered without losing
            // those rules.
            if (recordDef.getMethods().stream().anyMatch(MethodDef::isConstructor)) {
                return false;
            }
            return recordDef.getMethods().stream().allMatch(JdkMethodSupport::supported);
        }
        if (!(objectDef instanceof ClassDef classDef)) {
            return false;
        }
        for (FieldDef field : classDef.getFields()) {
            if (field.getInitializer().isPresent()
                && !field.getInitializer().stream().allMatch(JdkMethodSupport::supported)) {
                return false;
            }
        }
        if (classDef.getStaticInitializer() != null && !JdkMethodSupport.supported(classDef.getStaticInitializer())) {
            return false;
        }
        for (MethodDef method : classDef.getMethods()) {
            if (!method.isConstructor() && method.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
                continue;
            }
            if (!JdkMethodSupport.supported(method)) {
                return false;
            }
            if (method.getStatements().isEmpty() && !method.getReturnType().equals(TypeDef.VOID)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDeclared(ObjectDef objectDef, String name, List<TypeDef> parameterTypes) {
        List<String> descriptors = parameterTypes.stream()
            .map(type -> TypeUtils.getDescriptor(type, objectDef)).toList();
        return objectDef.getMethods().stream()
            .filter(method -> method.getName().equals(name))
            .anyMatch(method -> method.getParameters().stream()
                .map(parameter -> TypeUtils.getDescriptor(parameter.getType(), objectDef))
                .toList().equals(descriptors));
    }

    private void writeBridgeMethods(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner) {
        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(objectDef, method);
        for (BridgeResolver.BridgeMethod bridge : bridges) {
            MethodDef.MethodDefBuilder bridgeBuilder = MethodDef.builder(method.getName())
                .addModifiers(bridgeModifiers(method))
                .returns(bridge.returnType())
                .addAnnotations(method.getAnnotations())
                .addThrows(method.getThrowTypes());
            for (int i = 0; i < method.getParameters().size(); i++) {
                var parameter = method.getParameters().get(i);
                bridgeBuilder.addParameter(io.micronaut.sourcegen.model.ParameterDef.builder(
                    parameter.getName(), bridge.parameterTypes().get(i))
                    .addAnnotations(parameter.getAnnotations())
                    .build());
            }
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                bridgeBuilder.addStatement((aThis, parameters) -> {
                    ExpressionDef.InvokeInstanceMethod invocation = aThis.invoke(method, parameters);
                    return bridge.returnType().equals(TypeDef.VOID) ? invocation : invocation.returning();
                });
            }
            MethodDef bridgeMethod = bridgeBuilder.build();
            int flags = ModifierUtils.ACC_BRIDGE | ModifierUtils.ACC_SYNTHETIC;
            if (method.getModifiers().contains(Modifier.ABSTRACT)) {
                writeAbstractMethod(builder, objectDef, bridgeMethod, flags);
            } else {
                writeMethod(builder, objectDef, bridgeMethod, owner, flags);
            }
        }
    }

    private static List<Modifier> bridgeModifiers(MethodDef method) {
        return method.getModifiers().stream()
            .filter(modifier -> modifier == Modifier.PUBLIC || modifier == Modifier.PROTECTED
                || modifier == Modifier.PRIVATE || modifier == Modifier.ABSTRACT)
            .toList();
    }

    private static ClassDesc classDesc(TypeDef type, ObjectDef objectDef) {
        return ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, objectDef));
    }
}
