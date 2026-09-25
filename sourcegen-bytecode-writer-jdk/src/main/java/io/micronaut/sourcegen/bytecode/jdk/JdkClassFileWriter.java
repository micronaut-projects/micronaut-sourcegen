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
import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import io.micronaut.sourcegen.model.Completion;
import io.micronaut.sourcegen.bytecode.core.ConstructorBody;
import io.micronaut.sourcegen.bytecode.core.EnumGenUtils;
import io.micronaut.sourcegen.bytecode.core.ModifierUtils;
import io.micronaut.sourcegen.bytecode.core.SignatureUtils;
import io.micronaut.sourcegen.bytecode.core.TypeUtils;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
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
import org.jspecify.annotations.Nullable;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.FieldBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
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
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emits class structure and delegates method bodies to the direct JDK method writer.
 *
 * @since 2.2
 */
@Internal
final class JdkClassFileWriter {

    private final boolean verify;
    @Nullable
    private final CompilationTypes compilationTypes;
    private final List<MethodDef> syntheticMethods = new ArrayList<>();
    /**
     * The variables of the enclosing class in scope of the class written: {@link EnclosingScope#NONE} but for the
     * writer {@link #write(ObjectDef, ClassTypeDef)} creates for an inner class.
     */
    private final EnclosingScope enclosingScope;

    JdkClassFileWriter(boolean verify) {
        this(verify, null);
    }

    JdkClassFileWriter(boolean verify,
                       @Nullable CompilationTypes compilationTypes) {
        this(verify, compilationTypes, EnclosingScope.NONE);
    }

    private JdkClassFileWriter(boolean verify,
                               @Nullable CompilationTypes compilationTypes,
                               EnclosingScope enclosingScope) {
        this.enclosingScope = enclosingScope;
        this.verify = verify;
        this.compilationTypes = compilationTypes;
    }

    Optional<byte[]> write(ObjectDef definition, @Nullable ClassTypeDef outerType) {
        // An enum is written in its lowered class form, the same way the ASM backend does it
        ObjectDef objectDef = definition instanceof EnumDef enumDef ? EnumGenUtils.toClassDef(enumDef) : definition;
        if (!supported(objectDef)) {
            return Optional.empty();
        }
        // An inner class has the variables of its enclosing class in scope
        return new JdkClassFileWriter(verify, compilationTypes, EnclosingScope.of(objectDef, outerType))
            .writeSupported(objectDef, outerType);
    }

    private Optional<byte[]> writeSupported(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        ClassDesc owner = classDesc(objectDef.asTypeDef(), objectDef);
        ClassFile classFile = ClassFile.of(
            ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
            ClassFile.ClassHierarchyResolverOption.of(new SourcegenClassHierarchyResolver(
                Map.of(), SourcegenClassHierarchyResolver.classElements(objectDef),
                List.of(objectDef),
                JdkClassFileWriter.class.getClassLoader(), compilationTypes
            ))
        );
        try {
            byte[] bytes = classFile.build(owner, builder -> writeObject(builder, objectDef, owner, outerType));
            if (verify) {
                List<VerifyError> errors = classFile.verify(bytes);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("JDK verification failed for " + objectDef.getName() + ": " + errors);
                }
            }
            return Optional.of(bytes);
        } catch (UnsupportedOperationException e) {
            // The only signal that a construct cannot be lowered directly; every other failure is a
            // defect in this writer and must not be hidden behind the source fallback.
            return Optional.empty();
        }
    }

    private void writeObject(ClassBuilder builder, ObjectDef objectDef, ClassDesc owner, @Nullable ClassTypeDef outerType) {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(builder, classDef, owner, outerType);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(builder, recordDef, owner, outerType);
        } else if (objectDef instanceof InterfaceDef interfaceDef) {
            writeInterface(builder, interfaceDef, owner, outerType);
        } else if (objectDef instanceof AnnotationObjectDef annotationObjectDef) {
            writeAnnotationObject(builder, annotationObjectDef, owner, outerType);
        }
    }

    /**
     * Writes an annotation type the way a compiler emits one: an interface flagged
     * {@code ACC_ANNOTATION} that extends {@link java.lang.annotation.Annotation}, with one abstract
     * accessor per member and the member's default, when it has one, in that accessor's
     * {@code AnnotationDefault} attribute.
     */
    private void writeAnnotationObject(ClassBuilder builder, AnnotationObjectDef annotationDef, ClassDesc owner,
                                       @Nullable ClassTypeDef outerType) {
        int flags = ModifierUtils.classFileFlags(annotationDef, outerType);
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(flags)
            .withSuperclass(ConstantDescs.CD_Object)
            .withInterfaceSymbols(List.of(ClassDesc.of(java.lang.annotation.Annotation.class.getName())));
        writeOuterInner(builder, annotationDef, owner, outerType);
        addAnnotations(builder, annotationDef.getAnnotations());
        List<StatementDef> staticInitializer = new ArrayList<>();
        for (FieldDef field : annotationDef.getFields()) {
            writeField(builder, annotationDef, field);
            // A constant of an annotation type is implicitly static, wherever it is assigned from
            field.getInitializer().ifPresent(initializer ->
                staticInitializer.add(annotationDef.asTypeDef().getStaticField(field).put(initializer)));
        }
        if (!staticInitializer.isEmpty()) {
            writeMethod(builder, annotationDef, MethodDef.builder("<clinit>")
                .addModifiers(Modifier.STATIC)
                .addStatement(StatementDef.multi(staticInitializer))
                .build(), owner);
        }
        for (AnnotationObjectDef.AnnotationMemberDef member : annotationDef.getMembers()) {
            writeAnnotationMember(builder, annotationDef, member);
        }
    }

    /**
     * Writes a member of an annotation type as the abstract accessor it is, followed by its default.
     * A default is a single value, which is what the {@code AnnotationDefault} attribute holds.
     */
    private void writeAnnotationMember(ClassBuilder builder, AnnotationObjectDef annotationDef,
                                       AnnotationObjectDef.AnnotationMemberDef member) {
        MethodDef accessor = MethodDef.builder(member.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addAnnotations(member.getAnnotations())
            .returns(member.getType())
            .build();
        Object defaultValue = member.getAnnotationDefaultValue() != null
            ? member.getAnnotationDefaultValue()
            : member.getDefaultValue();
        builder.withMethod(accessor.getName(),
            MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(annotationDef, accessor, enclosingScope)),
            ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_ABSTRACT,
            methodBuilder -> {
                addMethodMetadata(methodBuilder, annotationDef, accessor);
                addTypeAnnotations(methodBuilder, accessor);
                if (defaultValue != null) {
                    methodBuilder.with(AnnotationDefaultAttribute.of(
                        ByteCodeWriter.toAnnotationValue(defaultValue, member.getType())));
                }
            });
    }

    /**
     * Writes the {@code NestHost} and {@code InnerClasses} entries of a member type, and the
     * {@code InnerClasses} and {@code NestMembers} entries for the member types this type declares.
     * The outer and the member both write the entry describing the member, and the two agree by
     * sharing {@link ModifierUtils#innerClassFlags}.
     */
    private void writeOuterInner(ClassBuilder builder, ObjectDef objectDef, ClassDesc owner,
                                 @Nullable ClassTypeDef outerType) {
        List<InnerClassInfo> entries = new ArrayList<>();
        if (outerType != null) {
            ClassDesc outer = classDesc(outerType, objectDef);
            builder.with(NestHostAttribute.of(outer));
            entries.add(InnerClassInfo.of(owner, Optional.of(outer), Optional.of(objectDef.getSimpleName()),
                ModifierUtils.innerClassFlags(objectDef, outerType.isInterface())));
        }
        List<ClassDesc> members = new ArrayList<>();
        for (ObjectDef inner : objectDef.getInnerTypes()) {
            ClassDesc member = classDesc(inner.asTypeDef(), objectDef);
            entries.add(InnerClassInfo.of(member, Optional.of(owner), Optional.of(inner.getSimpleName()),
                ModifierUtils.innerClassFlags(inner, objectDef instanceof InterfaceDef)));
            members.add(member);
        }
        if (!entries.isEmpty()) {
            builder.with(InnerClassesAttribute.of(entries));
        }
        if (!members.isEmpty()) {
            builder.with(NestMembersAttribute.ofSymbols(members));
        }
    }

    private void writeInterface(ClassBuilder builder, InterfaceDef interfaceDef, ClassDesc owner,
                                @Nullable ClassTypeDef outerType) {
        int flags = ModifierUtils.classFileFlags(interfaceDef, outerType);
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(flags)
            .withSuperclass(ConstantDescs.CD_Object)
            .withInterfaceSymbols(interfaceDef.getSuperinterfaces().stream()
                .map(type -> classDesc(type, interfaceDef)).toList());
        writeOuterInner(builder, interfaceDef, owner, outerType);
        addAnnotations(builder, interfaceDef.getAnnotations());
        addSignature(builder, SignatureUtils.getInterfaceSignature(interfaceDef, enclosingScope));
        addBoundAnnotations(builder, interfaceDef.getTypeVariables(), null, interfaceDef.getSuperinterfaces());
        for (MethodDef method : interfaceDef.getMethods()) {
            if (isAbstract(interfaceDef, method)) {
                writeAbstractMethod(builder, interfaceDef, method);
            } else {
                writeMethod(builder, interfaceDef, method, owner);
            }
            writeBridgeMethods(builder, interfaceDef, method, owner);
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            writeInterfaceProperty(builder, interfaceDef, property);
        }
        for (int i = 0; i < syntheticMethods.size(); i++) {
            writeMethod(builder, interfaceDef, syntheticMethods.get(i), owner);
        }
    }

    /**
     * An interface property has no backing field; it is the pair of abstract accessors an
     * implementation has to provide.
     */
    private void writeInterfaceProperty(ClassBuilder builder, InterfaceDef interfaceDef, PropertyDef property) {
        String capitalized = io.micronaut.core.naming.NameUtils.capitalize(property.getName());
        writeAbstractMethod(builder, interfaceDef, MethodDef.builder("get" + capitalized)
            .addModifiers(property.getModifiersArray())
            .returns(property.getType())
            .addAnnotations(property.getAnnotations())
            .build());
        writeAbstractMethod(builder, interfaceDef, MethodDef.builder("set" + capitalized)
            .addModifiers(property.getModifiersArray())
            .addParameter(property.getName(), property.getType())
            .build());
    }

    /**
     * A method is abstract when declared so, and in an interface also when it is neither static nor
     * given a body: the model marks interface methods abstract explicitly, but a body-less instance
     * method can only be abstract there.
     */
    private static boolean isAbstract(ObjectDef objectDef, MethodDef method) {
        if (method.getModifiers().contains(Modifier.ABSTRACT) || method.getModifiers().contains(Modifier.NATIVE)) {
            return true;
        }
        return objectDef instanceof InterfaceDef
            && !method.getModifiers().contains(Modifier.STATIC)
            && method.getStatements().isEmpty();
    }

    private void writeRecord(ClassBuilder builder, RecordDef recordDef, ClassDesc owner, @Nullable ClassTypeDef outerType) {
        int flags = ModifierUtils.classFileFlags(recordDef, outerType);
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(flags)
            .withSuperclass(ClassDesc.of("java.lang.Record"))
            .withInterfaceSymbols(recordDef.getSuperinterfaces().stream()
                .map(type -> classDesc(type, recordDef)).toList());
        writeOuterInner(builder, recordDef, owner, outerType);
        addAnnotations(builder, recordDef.getAnnotations());
        addSignature(builder, SignatureUtils.getRecordSignature(recordDef, enclosingScope));
        addBoundAnnotations(builder, recordDef.getTypeVariables(), null, recordDef.getSuperinterfaces());

        List<RecordComponentInfo> components = new ArrayList<>();
        for (PropertyDef property : recordDef.getProperties()) {
            TypeDef componentType = componentType(property);
            ClassDesc type = classDesc(componentType, recordDef);
            FieldDef field = FieldDef.builder(property.getName(), property.getType())
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .addAnnotations(annotationsFor(property, ElementType.FIELD))
                .build();
            List<java.lang.classfile.Attribute<?>> attributes = new ArrayList<>();
            List<AnnotationDef> componentAnnotations = componentAnnotations(property);
            List<Annotation> visibleComponent = retained(componentAnnotations, RetentionPolicy.RUNTIME);
            if (!visibleComponent.isEmpty()) {
                attributes.add(RuntimeVisibleAnnotationsAttribute.of(visibleComponent));
            }
            List<Annotation> invisibleComponent = retained(componentAnnotations, RetentionPolicy.CLASS);
            if (!invisibleComponent.isEmpty()) {
                attributes.add(RuntimeInvisibleAnnotationsAttribute.of(invisibleComponent));
            }
            String signature = SignatureUtils.getFieldSignature(recordDef, field, enclosingScope);
            if (signature != null) {
                attributes.add(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
            }
            List<TypeAnnotation> visibleComponentType = typeAnnotations(
                componentType, TypeAnnotation.TargetInfo.ofField(), RetentionPolicy.RUNTIME);
            if (!visibleComponentType.isEmpty()) {
                attributes.add(RuntimeVisibleTypeAnnotationsAttribute.of(visibleComponentType));
            }
            List<TypeAnnotation> invisibleComponentType = typeAnnotations(
                componentType, TypeAnnotation.TargetInfo.ofField(), RetentionPolicy.CLASS);
            if (!invisibleComponentType.isEmpty()) {
                attributes.add(RuntimeInvisibleTypeAnnotationsAttribute.of(invisibleComponentType));
            }
            components.add(RecordComponentInfo.of(property.getName(), type, attributes));
            writeField(builder, recordDef, field);
        }
        builder.with(RecordAttribute.of(components));

        List<TypeDef> componentTypes = recordDef.getProperties().stream().map(PropertyDef::getType).toList();
        if (!TypeUtils.declaresMethod(recordDef, MethodDef.CONSTRUCTOR, componentTypes, enclosingScope)) {
            writeRecordConstructor(builder, recordDef, owner);
        }
        for (MethodDef method : recordDef.getMethods()) {
            if (method.isConstructor()) {
                writeConstructor(builder, recordDef, method, owner, ClassDesc.of("java.lang.Record"));
            }
        }
        for (PropertyDef property : recordDef.getProperties()) {
            if (!TypeUtils.declaresMethod(recordDef, property.getName(), List.of(), enclosingScope)) {
                FieldDef field = FieldDef.builder(property.getName(), property.getType())
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                    .build();
                MethodDef accessor = MethodDef.builder(property.getName())
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .returns(property.getType())
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
            ModifierUtils.accessFlags(recordDef.getModifiers()), methodBuilder -> {
                MethodDef.MethodDefBuilder canonicalBuilder = MethodDef.constructor();
                properties.forEach(property -> canonicalBuilder.addParameter(ParameterDef.builder(
                    property.getName(), property.getType()
                ).addAnnotations(annotationsFor(property, ElementType.PARAMETER)).build()));
                MethodDef canonical = canonicalBuilder.build();
                addMethodMetadata(methodBuilder, recordDef, canonical);
                addTypeAnnotations(methodBuilder, canonical);
                methodBuilder.withCode(code -> {
                    code.aload(0).invokespecial(ClassDesc.of("java.lang.Record"), MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
                    for (int i = 0; i < properties.size(); i++) {
                        PropertyDef property = properties.get(i);
                        FieldDef field = FieldDef.builder(property.getName(), property.getType())
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL).build();
                        code.aload(0).loadLocal(
                            TypeKind.fromDescriptor(TypeUtils.getDescriptor(property.getType(), recordDef, enclosingScope)).asLoadable(),
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
        if (!TypeUtils.declaresMethod(recordDef, "equals", List.of(TypeDef.OBJECT), enclosingScope)) {
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
        if (TypeUtils.declaresMethod(recordDef, name, name.equals("equals") ? List.of(TypeDef.OBJECT) : List.of(), enclosingScope)) {
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

    private void writeClass(ClassBuilder builder, ClassDef classDef, ClassDesc owner, @Nullable ClassTypeDef outerType) {
        int flags = ModifierUtils.classFileFlags(classDef, outerType);
        builder.withVersion(ClassFile.JAVA_17_VERSION, 0)
            .withFlags(flags);
        ClassTypeDef superclass = classDef.getSuperclass();
        builder.withSuperclass(superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef));
        builder.withInterfaceSymbols(classDef.getSuperinterfaces().stream()
            .map(type -> classDesc(type, classDef)).toList());
        writeOuterInner(builder, classDef, owner, outerType);
        addAnnotations(builder, classDef.getAnnotations());
        addSignature(builder, classSignature(classDef));
        addBoundAnnotations(builder, classDef.getTypeVariables(), classDef.getSuperclass(), classDef.getSuperinterfaces());
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
            ClassDesc superclassDesc = superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef);
            constructors.forEach(method -> writeConstructor(builder, classDef, method, owner, superclassDesc));
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
        // An implementation inherited from a superclass satisfies an added interface through a bridge of this class
        for (BridgeResolver.Bridge bridge : BridgeResolver.inheritedBridgesOf(classDef, enclosingScope)) {
            writeMethod(builder, classDef, bridge.method(), owner, bridge.flags());
        }
        // A public method inherited from a superclass that is not public is declared again, as javac does
        for (BridgeResolver.Bridge bridge : BridgeResolver.visibilityBridgesOf(classDef, enclosingScope)) {
            writeMethod(builder, classDef, bridge.method(), owner, bridge.flags());
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
        builder.withMethod(MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void, flags, methodBuilder ->
            JdkMethodWriter.withCode(methodBuilder, classDef, MethodDef.constructor().build(), owner, enclosingScope, (code, writer) -> {
                code.aload(0).invokespecial(superclass == null ? ConstantDescs.CD_Object : classDesc(superclass, classDef),
                    MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
                for (FieldDef field : ConstructorBody.initializedFields(classDef)) {
                    writer.writeInstanceInitializer(classDef, field, field.getInitializer().orElseThrow());
                }
                syntheticMethods.addAll(writer.lambdaMethods());
                writer.writeLocalVariables();
                code.return_();
            }));
    }

    private void writeConstructor(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner,
                                  ClassDesc superclass) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope));
        builder.withMethod(method.getName(), type, ModifierUtils.methodFlags(method), methodBuilder -> {
            addMethodMetadata(methodBuilder, objectDef, method);
            addTypeAnnotations(methodBuilder, method);
            JdkMethodWriter.withCode(methodBuilder, objectDef, method, owner, enclosingScope, (code, writer) -> {
                // The constructor call first, then the field initializers - which a delegation to this(...) has run
                ConstructorBody constructorBody = ConstructorBody.of(objectDef, method.getStatements(),
                    ConstructorBody.InitializersAfterThis.SKIP);
                if (constructorBody.implicitSuper()) {
                    code.aload(0).invokespecial(superclass, MethodDef.CONSTRUCTOR, ConstantDescs.MTD_void);
                } else if (constructorBody.hoistedCall() != null) {
                    writer.writeStatements(List.of(constructorBody.hoistedCall()));
                }
                for (FieldDef field : constructorBody.initializedFields()) {
                    writer.writeInstanceInitializer((ClassDef) objectDef, field, field.getInitializer().orElseThrow());
                }
                List<StatementDef> body = constructorBody.statements();
                writer.writeStatements(body);
                syntheticMethods.addAll(writer.lambdaMethods());
                if (Completion.BYTECODE.canCompleteNormally(StatementDef.multi(body))) {
                    code.return_();
                }
                writer.writeLocalVariables();
            });
        });
    }

    private void writeMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner) {
        writeMethod(builder, objectDef, method, owner, 0);
    }

    private void writeMethod(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner,
                             int extraFlags) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope));
        int flags = ModifierUtils.methodFlags(method) | extraFlags;
        builder.withMethod(method.getName(), type, flags, methodBuilder -> {
            addMethodMetadata(methodBuilder, objectDef, method, (extraFlags & ModifierUtils.ACC_BRIDGE) == 0);
            addTypeAnnotations(methodBuilder, method);
            JdkMethodWriter.withCode(methodBuilder, objectDef, method, owner, enclosingScope, (code, writer) -> {
                if (method.getStatements().isEmpty()) {
                    code.return_();
                } else {
                    writer.writeStatements(method.getStatements());
                    syntheticMethods.addAll(writer.lambdaMethods());
                    if (Completion.BYTECODE.canCompleteNormally(StatementDef.multi(method.getStatements()))) {
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
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope));
        int flags = ModifierUtils.methodFlags(method) | extraFlags;
        if (!method.getModifiers().contains(Modifier.NATIVE)) {
            // An interface method without a body is abstract whether or not the model says so
            flags |= ModifierUtils.ACC_ABSTRACT;
        }
        builder.withMethod(method.getName(), type, flags, methodBuilder -> {
            addMethodMetadata(methodBuilder, objectDef, method, (extraFlags & ModifierUtils.ACC_BRIDGE) == 0);
            addTypeAnnotations(methodBuilder, method);
        });
    }

    private void writeField(ClassBuilder builder, ObjectDef objectDef, FieldDef field) {
        builder.withField(field.getName(), classDesc(field.getType(), objectDef), fieldBuilder -> {
            fieldBuilder.withFlags(ModifierUtils.fieldFlags(objectDef, field));
            addAnnotations(fieldBuilder, declarationAnnotations(field.getAnnotations(), ElementType.FIELD));
            addTypeAnnotations(fieldBuilder, annotatedType(field.getType(), field.getAnnotations()));
            addSignature(fieldBuilder, SignatureUtils.getFieldSignature(objectDef, field, enclosingScope));
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

    @Nullable
    private String classSignature(ClassDef classDef) {
        if (!classDef.getTypeVariables().isEmpty()
            || classDef.getSuperclass() instanceof ClassTypeDef.Parameterized
            || classDef.getSuperinterfaces().stream().anyMatch(type -> type instanceof ClassTypeDef.Parameterized)) {
            return SignatureUtils.getClassSignature(classDef, enclosingScope);
        }
        return null;
    }

    private void addMethodMetadata(MethodBuilder builder, ObjectDef objectDef, MethodDef method) {
        addMethodMetadata(builder, objectDef, method, true);
    }

    private void addMethodMetadata(MethodBuilder builder, ObjectDef objectDef, MethodDef method,
                                   boolean addGenericSignature) {
        addAnnotations(builder, declarationAnnotations(method.getAnnotations(),
            method.isConstructor() ? ElementType.CONSTRUCTOR : ElementType.METHOD));
        if (!method.getParameters().isEmpty()) {
            builder.with(MethodParametersAttribute.of(method.getParameters().stream()
                .map(parameter -> MethodParameterInfo.of(Optional.of(parameter.getName())))
                .toList()));
        }
        addParameterAnnotations(builder, method, RetentionPolicy.RUNTIME);
        addParameterAnnotations(builder, method, RetentionPolicy.CLASS);
        if (!method.getThrowTypes().isEmpty()) {
            addExceptions(builder, method, objectDef);
        }
        if (addGenericSignature) {
            addSignature(builder, SignatureUtils.getMethodSignature(objectDef, method, enclosingScope));
        }
    }

    /**
     * Writes one parameter-annotation attribute. The attribute is indexed by parameter, so every
     * parameter contributes a list even when only one of them carries an annotation.
     */
    private static void addParameterAnnotations(MethodBuilder builder, MethodDef method, RetentionPolicy retention) {
        List<List<Annotation>> parameters = method.getParameters().stream()
            .map(parameter -> retained(
                declarationAnnotations(parameter.getAnnotations(), ElementType.PARAMETER), retention
            ))
            .toList();
        if (parameters.stream().allMatch(List::isEmpty)) {
            return;
        }
        if (retention == RetentionPolicy.RUNTIME) {
            builder.with(RuntimeVisibleParameterAnnotationsAttribute.of(parameters));
        } else {
            builder.with(RuntimeInvisibleParameterAnnotationsAttribute.of(parameters));
        }
    }

    private void addExceptions(MethodBuilder builder, MethodDef method, ObjectDef objectDef) {
        // A thrown variable of the method erases to its bound
        builder.with(ExceptionsAttribute.ofSymbols(method.getThrowTypes().stream()
            .map(type -> ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, objectDef, method, enclosingScope))).toList()));
    }

    private static void addTypeAnnotations(MethodBuilder builder, MethodDef method) {
        List<TypeAnnotation> visible = methodTypeAnnotations(method, RetentionPolicy.RUNTIME);
        if (!visible.isEmpty()) {
            builder.with(RuntimeVisibleTypeAnnotationsAttribute.of(visible));
        }
        List<TypeAnnotation> invisible = methodTypeAnnotations(method, RetentionPolicy.CLASS);
        if (!invisible.isEmpty()) {
            builder.with(RuntimeInvisibleTypeAnnotationsAttribute.of(invisible));
        }
    }

    private static void addTypeAnnotations(FieldBuilder builder, TypeDef type) {
        List<TypeAnnotation> visible = typeAnnotations(type, TypeAnnotation.TargetInfo.ofField(), RetentionPolicy.RUNTIME);
        if (!visible.isEmpty()) {
            builder.with(RuntimeVisibleTypeAnnotationsAttribute.of(visible));
        }
        List<TypeAnnotation> invisible = typeAnnotations(type, TypeAnnotation.TargetInfo.ofField(), RetentionPolicy.CLASS);
        if (!invisible.isEmpty()) {
            builder.with(RuntimeInvisibleTypeAnnotationsAttribute.of(invisible));
        }
    }

    /**
     * A type use annotation written on a method is an annotation of the type it returns, and one written on
     * a constructor is an annotation of the type being constructed - both of which the class file reaches
     * through the method return target. The definition of a constructor carries no return type of its own.
     */
    private static List<TypeAnnotation> methodTypeAnnotations(MethodDef method, RetentionPolicy retention) {
        List<TypeAnnotation> annotations = new ArrayList<>();
        TypeDef returnType = method.isConstructor() ? TypeDef.VOID : method.getReturnType();
        annotations.addAll(typeAnnotations(
            annotatedType(returnType, method.getAnnotations()), TypeAnnotation.TargetInfo.ofMethodReturn(), retention
        ));
        for (int i = 0; i < method.getParameters().size(); i++) {
            ParameterDef parameter = method.getParameters().get(i);
            annotations.addAll(typeAnnotations(
                annotatedType(parameter.getType(), parameter.getAnnotations()),
                TypeAnnotation.TargetInfo.ofMethodFormalParameter(i),
                retention
            ));
        }
        // `throws @Marker E`: an annotation of a thrown type
        for (int i = 0; i < method.getThrowTypes().size(); i++) {
            annotations.addAll(typeAnnotations(method.getThrowTypes().get(i), TypeAnnotation.TargetInfo.ofThrows(i), retention));
        }
        // `<T extends @Marker Number>`: an annotation of a bound of a type parameter
        List<TypeDef.TypeVariable> variables = method.getTypeVariables();
        for (int i = 0; i < variables.size(); i++) {
            List<TypeDef> bounds = variables.get(i).bounds();
            for (int j = 0; j < bounds.size(); j++) {
                annotations.addAll(typeAnnotations(bounds.get(j),
                    TypeAnnotation.TargetInfo.ofMethodTypeParameterBound(i, SignatureUtils.boundIndex(bounds, j)), retention));
            }
        }
        return annotations;
    }

    /**
     * Writes the annotations of the bounds of a class's type parameters - {@code T extends @Marker Number} - and of
     * its supertypes - {@code implements @Marker Supplier<@Marker T>}.
     */
    private static void addBoundAnnotations(ClassBuilder builder, List<TypeDef.TypeVariable> variables,
                                            @Nullable TypeDef superclass, List<TypeDef> superinterfaces) {
        for (RetentionPolicy retention : List.of(RetentionPolicy.RUNTIME, RetentionPolicy.CLASS)) {
            List<TypeAnnotation> annotations = new ArrayList<>();
            if (superclass != null) {
                // The superclass is supertype 65535, the interfaces are indexed by their order
                annotations.addAll(typeAnnotations(superclass, TypeAnnotation.TargetInfo.ofClassExtends(65535), retention));
            }
            for (int i = 0; i < superinterfaces.size(); i++) {
                annotations.addAll(typeAnnotations(superinterfaces.get(i), TypeAnnotation.TargetInfo.ofClassExtends(i), retention));
            }
            for (int i = 0; i < variables.size(); i++) {
                List<TypeDef> bounds = variables.get(i).bounds();
                for (int j = 0; j < bounds.size(); j++) {
                    annotations.addAll(typeAnnotations(bounds.get(j),
                        TypeAnnotation.TargetInfo.ofClassTypeParameterBound(i, SignatureUtils.boundIndex(bounds, j)), retention));
                }
            }
            if (!annotations.isEmpty()) {
                builder.with(retention == RetentionPolicy.RUNTIME ? RuntimeVisibleTypeAnnotationsAttribute.of(annotations)
                    : RuntimeInvisibleTypeAnnotationsAttribute.of(annotations));
            }
        }
    }

    private static List<TypeAnnotation> typeAnnotations(TypeDef type, TypeAnnotation.TargetInfo targetInfo,
                                                        RetentionPolicy retention) {
        List<TypeAnnotation> annotations = new ArrayList<>();
        collectTypeAnnotations(type, targetInfo, List.of(), annotations, retention);
        return annotations;
    }

    /**
     * Collects the annotations of the given retention that the type carries, against the path that reaches
     * the part of the type each of them annotates. A {@code SOURCE} annotation belongs in no class file and
     * so is collected under neither retention.
     */
    private static void collectTypeAnnotations(TypeDef type,
                                               TypeAnnotation.TargetInfo targetInfo,
                                               List<TypeAnnotation.TypePathComponent> path,
                                               List<TypeAnnotation> result,
                                               RetentionPolicy retention) {
        // A member of an enclosing type, `Outer<A>.Inner<B>`, is reached through a nested type step per level
        List<TypeAnnotation.TypePathComponent> nested = appendPath(path, TypeAnnotation.TypePathComponent.INNER_TYPE, TypeUtils.memberDepth(type));
        switch (type) {
            case TypeDef.AnnotatedTypeDef annotated -> {
                addTypeAnnotations(annotated.annotations(), targetInfo, nested, result, retention);
                collectTypeAnnotations(annotated.typeDef(), targetInfo, path, result, retention);
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> {
                addTypeAnnotations(annotated.annotations(), targetInfo, nested, result, retention);
                collectTypeAnnotations(annotated.typeDef(), targetInfo, path, result, retention);
            }
            case TypeDef.Array array -> collectTypeAnnotations(array.componentType(), targetInfo,
                appendPath(path, TypeAnnotation.TypePathComponent.ARRAY, array.dimensions()), result, retention);
            case ClassTypeDef.Parameterized parameterized -> {
                collectTypeAnnotations(parameterized.rawType(), targetInfo, path, result, retention);
                for (int i = 0; i < parameterized.typeArguments().size(); i++) {
                    collectTypeAnnotations(parameterized.typeArguments().get(i), targetInfo,
                        appendPath(nested, TypeAnnotation.TypePathComponent.of(
                            TypeAnnotation.TypePathComponent.Kind.TYPE_ARGUMENT, i
                        ), 1), result, retention);
                }
            }
            // The enclosing type and its arguments are reached from where the member is
            case ClassTypeDef memberType when TypeHierarchy.enclosingOf(memberType) instanceof ClassTypeDef enclosing ->
                collectTypeAnnotations(enclosing, targetInfo, path, result, retention);
            case TypeDef.Wildcard wildcard -> {
                // `? extends @Marker T`: an annotation of a bound is reached through the wildcard
                List<TypeAnnotation.TypePathComponent> bound = appendPath(path, TypeAnnotation.TypePathComponent.WILDCARD, 1);
                wildcard.upperBounds().forEach(upper -> collectTypeAnnotations(upper, targetInfo, bound, result, retention));
                wildcard.lowerBounds().forEach(lower -> collectTypeAnnotations(lower, targetInfo, bound, result, retention));
            }
            case ClassTypeDef _, TypeDef.Primitive _, TypeDef.TypeVariable _ -> {
            }
        }
    }

    private static void addTypeAnnotations(List<AnnotationDef> annotations,
                                           TypeAnnotation.TargetInfo targetInfo,
                                           List<TypeAnnotation.TypePathComponent> path,
                                           List<TypeAnnotation> result,
                                           RetentionPolicy retention) {
        for (AnnotationDef annotation : annotations) {
            if (retentionOf(annotation) == retention) {
                result.add(TypeAnnotation.of(targetInfo, path, ByteCodeWriter.toAnnotation(annotation)));
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

    /**
     * The type of a record component, carrying the annotations of the component that are type use ones.
     */
    private static TypeDef componentType(PropertyDef property) {
        return annotatedType(property.getType(), property.getAnnotations());
    }

    /**
     * The annotations of a record component that belong on one of the members it expands to - the field
     * backing it, its accessor and the canonical constructor's parameter. A type use annotation is kept for
     * every one of them, whether or not it also targets the member itself: the member is written with it on
     * the type it declares, which is where the annotation belongs.
     */
    private static List<AnnotationDef> annotationsFor(PropertyDef property, ElementType elementType) {
        return property.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation)
                .map(targets -> targets.contains(elementType) || targets.contains(ElementType.TYPE_USE))
                .orElse(true))
            .toList();
    }

    /**
     * The annotations of a record component that belong on the component itself, where a type use annotation
     * is written against the component's type instead and so is not among them.
     */
    private static List<AnnotationDef> componentAnnotations(PropertyDef property) {
        return property.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation)
                .map(targets -> targets.contains(ElementType.RECORD_COMPONENT))
                .orElse(true))
            .toList();
    }

    private static Optional<Set<ElementType>> targetsOf(AnnotationDef annotation) {
        return AnnotationTargetUtils.targetsOf(annotation, JdkClassFileWriter.class.getClassLoader());
    }

    private static List<AnnotationDef> declarationAnnotations(List<AnnotationDef> annotations, ElementType elementType) {
        return AnnotationTargetUtils.declarationAnnotations(annotations, elementType,
            JdkClassFileWriter.class.getClassLoader());
    }

    private static TypeDef annotatedType(TypeDef type, List<AnnotationDef> annotations) {
        return AnnotationTargetUtils.annotatedType(type, annotations, JdkClassFileWriter.class.getClassLoader());
    }

    private static void addAnnotations(ClassBuilder builder, List<AnnotationDef> annotations) {
        List<Annotation> visible = retained(annotations, RetentionPolicy.RUNTIME);
        List<Annotation> invisible = retained(annotations, RetentionPolicy.CLASS);
        if (!visible.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(visible));
        }
        if (!invisible.isEmpty()) {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(invisible));
        }
    }

    /**
     * The annotations of the given retention, converted for writing. A {@code SOURCE} annotation
     * belongs in no class file at all and so appears under neither retention.
     */
    private static List<Annotation> retained(List<AnnotationDef> annotations, RetentionPolicy retention) {
        return annotations.stream()
            .filter(annotation -> retentionOf(annotation) == retention)
            .map(ByteCodeWriter::toAnnotation)
            .toList();
    }

    private static RetentionPolicy retentionOf(AnnotationDef annotation) {
        return AnnotationTargetUtils.retentionOf(annotation, JdkClassFileWriter.class.getClassLoader());
    }

    private static void addAnnotations(MethodBuilder builder, List<AnnotationDef> annotations) {
        List<Annotation> visible = retained(annotations, RetentionPolicy.RUNTIME);
        List<Annotation> invisible = retained(annotations, RetentionPolicy.CLASS);
        if (!visible.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(visible));
        }
        if (!invisible.isEmpty()) {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(invisible));
        }
    }

    private static void addAnnotations(FieldBuilder builder, List<AnnotationDef> annotations) {
        List<Annotation> visible = retained(annotations, RetentionPolicy.RUNTIME);
        List<Annotation> invisible = retained(annotations, RetentionPolicy.CLASS);
        if (!visible.isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(visible));
        }
        if (!invisible.isEmpty()) {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(invisible));
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

    /**
     * Whether every construct of a definition (given in its lowered class form for an enum) can be
     * emitted by this writer. Member types are emitted by their own write call and do not take part.
     */
    private static boolean supported(ObjectDef objectDef) {
        if (objectDef instanceof ClassDef classDef) {
            for (FieldDef field : classDef.getFields()) {
                if (field.getInitializer().isPresent()
                    && !field.getInitializer().stream().allMatch(JdkMethodSupport::supported)) {
                    return false;
                }
            }
            if (classDef.getStaticInitializer() != null && !JdkMethodSupport.supported(classDef.getStaticInitializer())) {
                return false;
            }
        } else if (objectDef instanceof AnnotationObjectDef annotationObjectDef) {
            for (FieldDef field : annotationObjectDef.getFields()) {
                if (field.getInitializer().isPresent()
                    && !field.getInitializer().stream().allMatch(JdkMethodSupport::supported)) {
                    return false;
                }
            }
            return true;
        } else if (!(objectDef instanceof RecordDef) && !(objectDef instanceof InterfaceDef)) {
            return false;
        }
        for (MethodDef method : objectDef.getMethods()) {
            if (!method.isConstructor() && isAbstract(objectDef, method)) {
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

    private void writeBridgeMethods(ClassBuilder builder, ObjectDef objectDef, MethodDef method, ClassDesc owner) {
        for (BridgeResolver.Bridge bridge : BridgeResolver.bridgesOf(objectDef, method, enclosingScope)) {
            writeMethod(builder, objectDef, bridge.method(), owner, bridge.flags());
        }
    }

    private ClassDesc classDesc(TypeDef type, ObjectDef objectDef) {
        return ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, objectDef, enclosingScope));
    }
}
