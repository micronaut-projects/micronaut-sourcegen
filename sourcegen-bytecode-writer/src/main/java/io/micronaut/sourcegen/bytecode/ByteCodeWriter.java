/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode;

import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.sourcegen.bytecode.statement.StatementWriter;
import io.micronaut.sourcegen.model.AnnotationDef;
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
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.lang.model.element.Modifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_RECORD;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.V17;

/**
 * Generates the classes directly by writing the bytecode.
 *
 * <p>Unlike a compiler, the writer synthesizes the bridge methods a class requires itself, derived
 * from the declared supertypes. That derivation needs the supertype's method and generic metadata, so
 * a supertype should be referenced through its definition — {@link ClassTypeDef#of(ObjectDef)} for
 * another generated type, {@link ClassTypeDef#of(Class)} for a compiled class, or
 * {@code ClassTypeDef.of(ClassElement)} for a source type. A generic supertype referenced only by its
 * name, via {@link ClassTypeDef#of(String)}, carries no such metadata: no bridges can be derived from
 * it, and calls through its erased signatures may fail with an {@link AbstractMethodError} at runtime.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public final class ByteCodeWriter {

    private static final List<Modifier> ACCESS_MODIFIERS = List.of(Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE);

    private final boolean checkClass;
    private final boolean visitMaxs;

    public ByteCodeWriter() {
        this(false, true);
    }

    public ByteCodeWriter(boolean checkClass, boolean visitMaxs) {
        this.checkClass = checkClass;
        this.visitMaxs = visitMaxs;
    }

    private ClassWriter createClassWriterAndWriteObject(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor classVisitor = classWriter;
        if (checkClass) {
            classVisitor = new CheckClassAdapter(classVisitor);
        }
        writeObject(classVisitor, objectDef, outerType);
        classVisitor.visitEnd();
        return classWriter;
    }

    /**
     * Write an object.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     */
    public void writeObject(ClassVisitor classVisitor, ObjectDef objectDef) {
        writeObject(classVisitor, objectDef, null);
    }

    /**
     * Write an object.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param outerType    The outer type
     */
    public void writeObject(ClassVisitor classVisitor, ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        if (objectDef instanceof ClassDef classDef) {
            writeClass(classVisitor, classDef, outerType);
        } else if (objectDef instanceof RecordDef recordDef) {
            writeRecord(classVisitor, recordDef, outerType);
        } else if (objectDef instanceof InterfaceDef interfaceDef) {
            writeInterface(classVisitor, interfaceDef, outerType);
        } else if (objectDef instanceof EnumDef enumDef) {
            writeClass(classVisitor, EnumGenUtils.toClassDef(enumDef), outerType);
        } else {
            throw new UnsupportedOperationException("Unknown object definition: " + objectDef);
        }
    }

    /**
     * Write the annotations a type carries as type annotations of the member it belongs to. A type use
     * annotation belongs there and not among the member's declaration annotations, which is where a
     * consumer would otherwise read it as saying something about the declaration itself.
     *
     * @param typeDef  The type, annotated or not
     * @param typeRef  The reference of the type within the member, from {@link TypeReference}
     * @param member   The member the annotations are written on
     */
    private void writeTypeAnnotations(TypeDef typeDef, int typeRef, TypeAnnotatable member) {
        for (AnnotationDef annotation : typeAnnotationsOf(typeDef)) {
            visitAnnotation(annotation, member.visitTypeAnnotation(
                typeRef,
                null,
                TypeUtils.getType(annotation.getType(), null).getDescriptor(),
                true
            ));
        }
    }

    private static List<AnnotationDef> typeAnnotationsOf(TypeDef typeDef) {
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return annotated.annotations();
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return annotated.annotations();
        }
        return List.of();
    }

    private MethodDef createStaticInitializer(StatementDef statement) {
        return MethodDef.builder("<clinit>")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.STATIC)
            .addStatement(statement)
            .build();
    }

    /**
     * Write an enum.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param fieldDef     The field definition
     */
    public void writeField(ClassVisitor classVisitor, ObjectDef objectDef, FieldDef fieldDef) {
        int modifiersFlag = getModifiersFlag(fieldDef.getModifiers());
        if (fieldDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        if (EnumGenUtils.isEnumField(objectDef, fieldDef)) {
            modifiersFlag |= ACC_ENUM;
        }
        FieldVisitor fieldVisitor = classVisitor.visitField(
            modifiersFlag,
            fieldDef.getName(),
            TypeUtils.getType(fieldDef.getType(), objectDef).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(objectDef, fieldDef),
            null
        );
        for (AnnotationDef annotation : fieldDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = fieldVisitor.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
            visitAnnotation(annotation, annotationVisitor);
        }
        writeTypeAnnotations(
            fieldDef.getType(),
            TypeReference.newTypeReference(TypeReference.FIELD).getValue(),
            fieldVisitor::visitTypeAnnotation
        );
        fieldVisitor.visitEnd();
    }

    /**
     * Write an interface.
     *
     * @param classVisitor The class visitor
     * @param interfaceDef The interface definition
     * @param outerType The outer type
     */
    public void writeInterface(ClassVisitor classVisitor, InterfaceDef interfaceDef, @Nullable ClassTypeDef outerType) {
        Set<String> emittedBridges = new HashSet<>();
        int modifiersFlag = ACC_INTERFACE | ACC_ABSTRACT | getClassModifiersFlag(interfaceDef.getModifiers(), outerType);
        if (interfaceDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        classVisitor.visit(V17,
            modifiersFlag,
            TypeUtils.getType(interfaceDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getInterfaceSignature(interfaceDef),
            TypeUtils.OBJECT_TYPE.getInternalName(),
            interfaceDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, interfaceDef)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, interfaceDef.asTypeDef(), interfaceDef, outerType);
        for (AnnotationDef annotation : interfaceDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = classVisitor.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
            visitAnnotation(annotation, annotationVisitor);
        }
        for (MethodDef method : interfaceDef.getMethods()) {
            writeMethod(classVisitor, interfaceDef, method, emittedBridges);
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            writeProperty(classVisitor, interfaceDef, property, emittedBridges);
        }
    }

    /**
     * Write a record.
     *
     * @param classVisitor The class visitor
     * @param recordDef    The record definition
     */
    public void writeRecord(ClassVisitor classVisitor, RecordDef recordDef) {
        writeRecord(classVisitor, recordDef, null);
    }

    /**
     * Write a record.
     *
     * <p>The components of the record are expanded the way a compiler expands them: a record component,
     * a private final field, the canonical constructor, an accessor per component, and {@code equals},
     * {@code hashCode} and {@code toString} linked through
     * {@code java.lang.runtime.ObjectMethods#bootstrap}. A member the definition declares itself is kept,
     * replacing the one that would have been generated.
     *
     * @param classVisitor The class visitor
     * @param recordDef    The record definition
     * @param outerType     The outer type
     */
    public void writeRecord(ClassVisitor classVisitor, RecordDef recordDef, @Nullable ClassTypeDef outerType) {
        Set<String> emittedBridges = new HashSet<>();
        // A record is always final
        int modifiersFlag = ACC_RECORD | ACC_FINAL | getClassModifiersFlag(recordDef.getModifiers(), outerType);
        if (recordDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        classVisitor.visit(
            V17,
            modifiersFlag,
            TypeUtils.getType(recordDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getRecordSignature(recordDef),
            Type.getType(Record.class).getInternalName(),
            recordDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, recordDef)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, recordDef.asTypeDef(), recordDef, outerType);

        for (AnnotationDef annotation : recordDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = classVisitor.visitAnnotation(
                TypeUtils.getType(annotation.getType(), null).getDescriptor(),
                true);
            visitAnnotation(annotation, annotationVisitor);
        }

        List<PropertyDef> components = recordDef.getProperties();
        List<FieldDef> componentFields = components.stream().map(ByteCodeWriter::toComponentField).toList();

        for (int i = 0; i < components.size(); i++) {
            writeRecordComponent(classVisitor, recordDef, components.get(i), componentFields.get(i));
        }
        for (FieldDef componentField : componentFields) {
            writeField(classVisitor, recordDef, componentField);
        }
        List<TypeDef> componentTypes = components.stream().map(PropertyDef::getType).toList();
        if (!isDeclared(recordDef, MethodDef.CONSTRUCTOR, componentTypes)) {
            writeMethod(classVisitor, recordDef, canonicalConstructor(recordDef, components, componentFields), emittedBridges);
        }
        writeObjectMethods(classVisitor, recordDef, componentFields);
        for (int i = 0; i < components.size(); i++) {
            PropertyDef component = components.get(i);
            if (isDeclared(recordDef, component.getName(), List.of())) {
                continue;
            }
            FieldDef componentField = componentFields.get(i);
            writeMethod(classVisitor, recordDef, MethodDef.builder(component.getName())
                .addModifiers(Modifier.PUBLIC)
                .returns(componentField.getType())
                .addAnnotations(annotationsFor(component, ElementType.METHOD))
                .build((aThis, methodParameters) -> aThis.field(componentField).returning()), emittedBridges);
        }
        for (MethodDef method : recordDef.getMethods()) {
            writeMethod(classVisitor, recordDef, method, emittedBridges);
        }
    }

    private static FieldDef toComponentField(PropertyDef component) {
        return FieldDef.builder(component.getName(), componentType(component))
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .addAnnotations(annotationsFor(component, ElementType.FIELD))
            .build();
    }

    /**
     * The type of a record component, carrying the annotations of the component that are type use ones. A
     * compiler writes those on the type of every member the component expands to, not on the members
     * themselves.
     *
     * @param component The record component
     * @return Its type
     */
    private static TypeDef componentType(PropertyDef component) {
        List<AnnotationDef> typeAnnotations = component.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation).map(t -> t.contains(ElementType.TYPE_USE)).orElse(false))
            .toList();
        return typeAnnotations.isEmpty() ? component.getType() : component.getType().annotated(typeAnnotations);
    }

    /**
     * The annotations of a record component that belong on one of the members it expands to. A compiler
     * spreads a component's annotations over the component itself, the field backing it, its accessor and
     * the canonical constructor's parameter, keeping each only where the annotation's {@link Target} allows
     * it. An annotation that declares no target is applicable in every one of those contexts, and so is one
     * whose type cannot be resolved here - it is written everywhere rather than dropped.
     *
     * @param component   The record component
     * @param elementType The context the annotations are written in
     * @return The annotations that belong there
     */
    private static List<AnnotationDef> annotationsFor(PropertyDef component, ElementType elementType) {
        return component.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation).map(t -> t.contains(elementType)).orElse(true))
            .toList();
    }

    /**
     * The contexts an annotation declares as its targets, or empty when it declares none and is therefore
     * applicable in all of them. The targets are read from the class the definition carries, from the
     * definition of an annotation type being generated alongside, or from the class loaded by name.
     *
     * @param annotation The annotation
     * @return Its targets, or empty when it declares none or its type cannot be resolved here
     */
    private static Optional<Set<ElementType>> targetsOf(AnnotationDef annotation) {
        ClassTypeDef typeDef = annotation.getType();
        if (typeDef instanceof ClassTypeDef.ClassDefType classDefType) {
            return declaredTargetsOf(classDefType.objectDef());
        }
        Class<?> annotationType = resolveAnnotationType(typeDef);
        if (annotationType == null) {
            return Optional.empty();
        }
        Target target = annotationType.getAnnotation(Target.class);
        return target == null ? Optional.empty() : Optional.of(Set.of(target.value()));
    }

    /**
     * The targets of an annotation type that is itself being generated, read off its {@link Target}
     * definition - the class is not loadable, so nothing else can answer for it.
     *
     * @param objectDef The definition of the annotation type
     * @return Its targets, or empty when it declares none
     */
    private static Optional<Set<ElementType>> declaredTargetsOf(ObjectDef objectDef) {
        return objectDef.getAnnotations().stream()
            .filter(a -> a.getType().getName().equals(Target.class.getName()))
            .findFirst()
            .map(a -> {
                Object value = a.getValues().get(AnnotationMetadata.VALUE_MEMBER);
                Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
                return values.stream()
                    .filter(VariableDef.StaticField.class::isInstance)
                    .map(v -> ElementType.valueOf(((VariableDef.StaticField) v).name()))
                    .collect(Collectors.toSet());
            });
    }

    /**
     * @param typeDef The annotation type
     * @return The annotation class, or {@code null} when the definition carries no class for it and none
     * can be loaded by name
     */
    @Nullable
    private static Class<?> resolveAnnotationType(ClassTypeDef typeDef) {
        if (typeDef instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        try {
            return Class.forName(typeDef.getName(), false, ByteCodeWriter.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private void writeRecordComponent(ClassVisitor classVisitor, RecordDef recordDef, PropertyDef component, FieldDef componentField) {
        RecordComponentVisitor recordComponentVisitor = classVisitor.visitRecordComponent(
            component.getName(),
            TypeUtils.getType(component.getType(), recordDef).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(recordDef, componentField)
        );
        for (AnnotationDef annotation : annotationsFor(component, ElementType.RECORD_COMPONENT)) {
            AnnotationVisitor annotationVisitor = recordComponentVisitor.visitAnnotation(
                TypeUtils.getType(annotation.getType(), null).getDescriptor(),
                true);
            visitAnnotation(annotation, annotationVisitor);
        }
        writeTypeAnnotations(
            componentField.getType(),
            TypeReference.newTypeReference(TypeReference.FIELD).getValue(),
            recordComponentVisitor::visitTypeAnnotation
        );
        recordComponentVisitor.visitEnd();
    }

    private MethodDef canonicalConstructor(RecordDef recordDef, List<PropertyDef> components, List<FieldDef> componentFields) {
        MethodDef.MethodDefBuilder builder = MethodDef.constructor();
        // An implicitly declared canonical constructor has the access of the record itself (JLS 8.10.4.1)
        for (Modifier accessModifier : ACCESS_MODIFIERS) {
            if (recordDef.getModifiers().contains(accessModifier)) {
                builder.addModifiers(accessModifier);
            }
        }
        for (int i = 0; i < components.size(); i++) {
            PropertyDef component = components.get(i);
            builder.addParameter(ParameterDef.builder(component.getName(), componentFields.get(i).getType())
                .addAnnotations(annotationsFor(component, ElementType.PARAMETER))
                .build());
        }
        return builder.build((aThis, methodParameters) -> {
            List<StatementDef> statements = new ArrayList<>(componentFields.size() + 1);
            statements.add(aThis.superRef().invokeSuperConstructor());
            for (int i = 0; i < componentFields.size(); i++) {
                statements.add(aThis.field(componentFields.get(i)).assign(methodParameters.get(i)));
            }
            return StatementDef.multi(statements);
        });
    }

    /**
     * Write the {@code equals}, {@code hashCode} and {@code toString} of a record, each of them an
     * {@code invokedynamic} linked through {@code java.lang.runtime.ObjectMethods#bootstrap}, which
     * derives the implementation from the components handed to it as the bootstrap arguments.
     *
     * @param classVisitor    The class visitor
     * @param recordDef       The record definition
     * @param componentFields The fields backing the record components
     */
    private void writeObjectMethods(ClassVisitor classVisitor, RecordDef recordDef, List<FieldDef> componentFields) {
        Type recordType = TypeUtils.getType(recordDef.asTypeDef());
        String internalName = recordType.getInternalName();
        Object[] bootstrapArguments = new Object[componentFields.size() + 2];
        bootstrapArguments[0] = recordType;
        bootstrapArguments[1] = componentFields.stream().map(FieldDef::getName).collect(Collectors.joining(";"));
        for (int i = 0; i < componentFields.size(); i++) {
            FieldDef componentField = componentFields.get(i);
            bootstrapArguments[i + 2] = new Handle(
                Opcodes.H_GETFIELD,
                internalName,
                componentField.getName(),
                TypeUtils.getType(componentField.getType(), recordDef).getDescriptor(),
                false
            );
        }
        writeObjectMethod(classVisitor, recordDef, "toString", Type.getMethodDescriptor(Type.getType(String.class)),
            Type.getMethodDescriptor(Type.getType(String.class), recordType), bootstrapArguments);
        writeObjectMethod(classVisitor, recordDef, "hashCode", Type.getMethodDescriptor(Type.INT_TYPE),
            Type.getMethodDescriptor(Type.INT_TYPE, recordType), bootstrapArguments);
        writeObjectMethod(classVisitor, recordDef, "equals", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, TypeUtils.OBJECT_TYPE),
            Type.getMethodDescriptor(Type.BOOLEAN_TYPE, recordType, TypeUtils.OBJECT_TYPE), bootstrapArguments);
    }

    private void writeObjectMethod(ClassVisitor classVisitor,
                                   RecordDef recordDef,
                                   String name,
                                   String descriptor,
                                   String callSiteDescriptor,
                                   Object[] bootstrapArguments) {
        if (isDeclared(recordDef, name, Type.getArgumentTypes(descriptor))) {
            return;
        }
        int modifiersFlag = ACC_PUBLIC | ACC_FINAL;
        MethodVisitor methodVisitor = classVisitor.visitMethod(modifiersFlag, name, descriptor, null, null);
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodVisitor, modifiersFlag, name, descriptor);
        generatorAdapter.visitCode();
        generatorAdapter.loadThis();
        generatorAdapter.loadArgs();
        generatorAdapter.visitInvokeDynamicInsn(name, callSiteDescriptor, ObjectMethodsHandle.BOOTSTRAP, bootstrapArguments);
        generatorAdapter.returnValue();
        if (visitMaxs) {
            generatorAdapter.visitMaxs(20, 20);
        }
        generatorAdapter.visitEnd();
    }

    private boolean isDeclared(RecordDef recordDef, String name, List<TypeDef> parameterTypes) {
        return isDeclared(recordDef, name, parameterTypes.stream().map(t -> TypeUtils.getType(t, recordDef)).toArray(Type[]::new));
    }

    private boolean isDeclared(RecordDef recordDef, String name, Type[] parameterTypes) {
        return recordDef.getMethods().stream().anyMatch(methodDef -> methodDef.getName().equals(name)
            && Arrays.equals(
                methodDef.getParameters().stream().map(p -> TypeUtils.getType(p.getType(), recordDef)).toArray(Type[]::new),
                parameterTypes));
    }

    /**
     * Write an interface.
     *
     * @param classVisitor The class visitor
     * @param classDef     The class definition
     */
    public void writeClass(ClassVisitor classVisitor, ClassDef classDef) {
        writeClass(classVisitor, classDef, null);
    }

    /**
     * Write an interface.
     *
     * @param classVisitor The class visitor
     * @param classDef     The class definition
     * @param outerType     The outer type
     */
    public void writeClass(ClassVisitor classVisitor, ClassDef classDef, @Nullable ClassTypeDef outerType) {
        // The bridges emitted for this class, so the same erasure inherited by two overloads is
        // written once; local to one emission, a reused writer starts fresh
        Set<String> emittedBridges = new HashSet<>();
        ClassTypeDef typeDef = classDef.asTypeDef();

        int modifiersFlag = getClassModifiersFlag(classDef.getModifiers(), outerType);

        if (classDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        if (EnumGenUtils.isEnum(classDef)) {
            modifiersFlag |= ACC_ENUM;
        }
        classVisitor.visit(
            V17,
            modifiersFlag,
            TypeUtils.getType(classDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getClassSignature(classDef),
            TypeUtils.getType(Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT), null).getInternalName(),
            classDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, classDef)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, classDef.asTypeDef(), classDef, outerType);

        for (AnnotationDef annotation : classDef.getAnnotations()) {
            AnnotationVisitor annotationVisitor = classVisitor.visitAnnotation(
                TypeUtils.getType(annotation.getType(), null).getDescriptor(),
                true);
            visitAnnotation(annotation, annotationVisitor);
        }

        List<StatementDef> staticInitStatements = new ArrayList<>();
        for (FieldDef field : classDef.getFields()) {
            writeField(classVisitor, classDef, field);
            field.getInitializer().ifPresent(expressionDef -> {
                if (field.getModifiers().contains(Modifier.STATIC)) {
                    staticInitStatements.add(typeDef.getStaticField(field).put(expressionDef));
                }
            });
        }

        StatementDef staticInitializer = classDef.getStaticInitializer();
        if (staticInitializer != null) {
            staticInitStatements.add(staticInitializer);
        }
        if (!staticInitStatements.isEmpty()) {
            writeMethod(classVisitor, classDef, createStaticInitializer(StatementDef.multi(staticInitStatements)), emittedBridges);
        }

        if (classDef.getMethods().stream().noneMatch(MethodDef::isConstructor)) {
            // Add default constructor
            MethodDef.MethodDefBuilder defaultConstructor = MethodDef.constructor();
            if (classDef.getModifiers().contains(Modifier.PUBLIC)) {
                defaultConstructor.addModifiers(Modifier.PUBLIC);
            }
            writeMethod(classVisitor, classDef, defaultConstructor
                .build((aThis, methodParameters) -> aThis.superRef().invokeSuperConstructor(methodParameters)), emittedBridges);
        }

        for (PropertyDef property : classDef.getProperties()) {
            writeProperty(classVisitor, classDef, property, emittedBridges);
        }
        for (MethodDef method : classDef.getMethods()) {
            writeMethod(classVisitor, classDef, method, emittedBridges);
        }
    }

    private void writeOuterInner(ClassVisitor classVisitor, ClassTypeDef thisType, ObjectDef thisDef, @Nullable ClassTypeDef outerType) {
        if (outerType != null) {
            String outerInternalName = TypeUtils.getType(outerType).getInternalName();
            classVisitor.visitNestHost(outerInternalName);
            classVisitor.visitInnerClass(
                TypeUtils.getType(thisType).getInternalName(),
                outerInternalName,
                thisType.getSimpleName(),
                getInnerClassModifiersFlag(thisDef, outerType.isInterface())
            );
        }
        writeInnerTypes(classVisitor, thisType, thisDef);
    }

    private void writeInnerTypes(ClassVisitor outerClassVisitor, ClassTypeDef outerType, ObjectDef outerDef) {
        for (ObjectDef innerDef : outerDef.getInnerTypes()) {
            String outerClassInternalName = TypeUtils.getType(outerType).getInternalName();

            ClassTypeDef interType = innerDef.asTypeDef();
            outerClassVisitor.visitInnerClass(
                TypeUtils.getType(innerDef.asTypeDef()).getInternalName(),
                outerClassInternalName,
                interType.getSimpleName(),
                getInnerClassModifiersFlag(innerDef, outerDef instanceof InterfaceDef)
            );
            outerClassVisitor.visitNestMember(TypeUtils.getType(innerDef.asTypeDef()).getInternalName());
        }
    }

    /**
     * The access flags of the {@code InnerClasses} entry of a member type. This is where its declared access
     * lives - the class file itself cannot carry private or protected - and a member type is always static
     * here, without which it reads back as an inner class needing an enclosing instance. The outer type and
     * the member itself both write this entry, and the two have to agree.
     *
     * <p>A member of an interface that declares no access is written public, being implicitly so (JLS 9.5).
     * A member of a class is not: it keeps the package private access it was declared with.
     *
     * @param objectDef          The member type
     * @param declaredInterface  Whether the type enclosing it is an interface
     * @return The access flags of its inner class entry
     */
    private int getInnerClassModifiersFlag(ObjectDef objectDef, boolean declaredInterface) {
        int access = getModifiersFlag(objectDef) | ACC_STATIC;
        if (declaredInterface && (access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0) {
            access |= ACC_PUBLIC;
        }
        return access;
    }

    private int getModifiersFlag(ObjectDef objectDef) {
        if (objectDef instanceof EnumDef enumDef) {
            return ACC_ENUM | getModifiersFlag(EnumGenUtils.toClassDef(enumDef));
        }
        if (objectDef instanceof InterfaceDef interfaceDef) {
            return ACC_INTERFACE | ACC_ABSTRACT | getModifiersFlag(interfaceDef.getModifiers());
        }
        if (objectDef instanceof RecordDef recordDef) {
            // A record is always final; ACC_RECORD is not among the flags an inner class entry may carry
            return ACC_FINAL | getModifiersFlag(recordDef.getModifiers());
        }
        return getModifiersFlag(objectDef.getModifiers());
    }

    private void visitAnnotation(AnnotationDef annotation, AnnotationVisitor annotationVisitor) {
        for (Map.Entry<String, Object> entry : annotation.getValues().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            visitAnnotation(annotationVisitor, key, value);
        }
        annotationVisitor.visitEnd();
    }

    private void visitAnnotation(AnnotationVisitor annotationVisitor, String name, Object value) {
        if (value instanceof VariableDef.StaticField staticField) {
            annotationVisitor.visitEnum(
                name,
                TypeUtils.getType(staticField.ownerType(), null).getDescriptor(),
                staticField.name()
            );
        } else if (value instanceof AnnotationDef nestedAnnotation) {
            visitAnnotation(
                nestedAnnotation,
                annotationVisitor.visitAnnotation(name, TypeUtils.getType(nestedAnnotation.getType(), null).getDescriptor())
            );
        } else if (value instanceof AnnotationDef[] annotations) {
            AnnotationVisitor arrayVisitor = annotationVisitor.visitArray(name);
            for (AnnotationDef annotationDef : annotations) {
                visitAnnotation(
                    annotationDef,
                    annotationVisitor.visitAnnotation(name, TypeUtils.getType(annotationDef.getType(), null).getDescriptor())
                );
            }
            arrayVisitor.visitEnd();
        } else if (value instanceof Collection<?> coll) {
            AnnotationVisitor arrayVisitor = annotationVisitor.visitArray(name);
            for (Object object : coll) {
                visitAnnotation(arrayVisitor, name, object);
            }
            arrayVisitor.visitEnd();
        } else if (value instanceof Object[] array) {
            AnnotationVisitor arrayVisitor = annotationVisitor.visitArray(name);
            for (Object object : array) {
                visitAnnotation(arrayVisitor, name, object);
            }
            arrayVisitor.visitEnd();
        } else {
            annotationVisitor.visit(name, value);
        }
    }

    private void writeProperty(ClassVisitor classWriter, ObjectDef objectDef, PropertyDef property, Set<String> emittedBridges) {
        FieldDef propertyField = FieldDef.builder(property.getName(), property.getType())
            .addModifiers(Modifier.PRIVATE)
            .addAnnotations(property.getAnnotations())
            .build();

        writeField(classWriter, objectDef, propertyField);

        String capitalizedPropertyName = NameUtils.capitalize(property.getName());

        boolean isAbstract = objectDef instanceof InterfaceDef;

        MethodDef.MethodDefBuilder getterBuilder = MethodDef.builder("get" + capitalizedPropertyName)
            .addModifiers(property.getModifiersArray());

        if (!isAbstract) {
            getterBuilder.addStatement((aThis, methodParameters) -> aThis.field(propertyField).returning());
        }

        writeMethod(classWriter, objectDef, getterBuilder.build(), emittedBridges);

        MethodDef.MethodDefBuilder setterBuilder = MethodDef.builder("set" + capitalizedPropertyName)
            .addParameter(ParameterDef.of(property.getName(), property.getType()))
            .addModifiers(property.getModifiersArray());

        if (!isAbstract) {
            setterBuilder.addStatement((aThis, methodParameters) -> aThis.field(propertyField).assign(methodParameters.get(0)));
        }

        writeMethod(classWriter, objectDef, setterBuilder.build(), emittedBridges);
    }

    /**
     * Write a method.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param methodDef    The method definition
     */
    private void writeMethod(ClassVisitor classVisitor, @Nullable ObjectDef objectDef, MethodDef methodDef, Set<String> emittedBridges) {
        writeMethod(classVisitor, objectDef, methodDef, false, 0, emittedBridges);
    }

    private void writeMethod(ClassVisitor classVisitor,
                             @Nullable ObjectDef objectDef,
                             MethodDef methodDef,
                             boolean isLambda,
                             int extraModifiersFlag,
                             Set<String> emittedBridges) {
        String name = methodDef.getName();
        String methodDescriptor = TypeUtils.getMethodDescriptor(objectDef, methodDef);
        int modifiersFlag = getModifiersFlag(methodDef.getModifiers()) | extraModifiersFlag;
        if (methodDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        String[] exceptions = methodDef.getThrowTypes().isEmpty() ? null : methodDef.getThrowTypes().stream()
            .map(t -> TypeUtils.getType(t, objectDef).getClassName().replace(".", "/"))
            .toArray(String[]::new);
        MethodVisitor methodVisitor = classVisitor.visitMethod(
            modifiersFlag,
            name,
            methodDescriptor,
            // A bridge carries an erased signature, it never gets a Signature attribute
            (modifiersFlag & ACC_BRIDGE) == 0 ? SignatureWriterUtils.getMethodSignature(objectDef, methodDef) : null,
            exceptions
        );
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodVisitor, modifiersFlag, name, methodDescriptor);
        for (AnnotationDef annotation : methodDef.getAnnotations()) {
            visitAnnotation(annotation, generatorAdapter.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true));
        }

        if (!methodDef.isConstructor()) {
            writeTypeAnnotations(
                methodDef.getReturnType(),
                TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(),
                generatorAdapter::visitTypeAnnotation
            );
        }

        if (methodDef.getParameters().stream().anyMatch(p -> !p.getAnnotations().isEmpty())) {
            generatorAdapter.visitAnnotableParameterCount(methodDef.getParameters().size(), true);
        }

        MethodContext context = new MethodContext(objectDef, methodDef, isLambda);
        Label startMethod = writeParameters(generatorAdapter, objectDef, methodDef, context);

        List<StatementDef> statements = methodDef.getStatements();
        if (methodDef.isConstructor()) {
            statements = adjustConstructorStatements(objectDef, statements);
        }
        if (!statements.isEmpty()) {
            writeStatements(generatorAdapter, objectDef, methodDef, context, statements, startMethod);
        }
        writeLocalVariableTable(methodVisitor, generatorAdapter, context);
        if (visitMaxs && !statements.isEmpty()) {
            generatorAdapter.visitMaxs(20, 20);
        }
        generatorAdapter.visitEnd();

        for (MethodDef lambdaDef: context.lambdaMethods()) {
            writeMethod(classVisitor, objectDef, lambdaDef, true, 0, emittedBridges);
        }

        if (!isLambda && (extraModifiersFlag & ACC_BRIDGE) == 0) {
            writeBridgeMethods(classVisitor, objectDef, methodDef, emittedBridges);
        }
    }

    /**
     * Register the parameters as locals and write their annotations.
     *
     * @param generatorAdapter The generator adapter
     * @param objectDef        The object definition
     * @param methodDef        The method definition
     * @param context          The method context
     * @return The label the parameters start at, or {@code null} when the method has none
     */
    @Nullable
    private Label writeParameters(GeneratorAdapter generatorAdapter,
                                  @Nullable ObjectDef objectDef,
                                  MethodDef methodDef,
                                  MethodContext context) {
        Label startMethod = null;
        int parameterIndex = 0;
        // The slot of a parameter: `this` takes slot 0 of an instance method, and a long or double takes two
        int slot = methodDef.getModifiers().contains(Modifier.STATIC) ? 0 : 1;
        for (ParameterDef parameter : methodDef.getParameters()) {
            if (startMethod == null) {
                startMethod = new Label();
            }
            for (AnnotationDef annotation : parameter.getAnnotations()) {
                AnnotationVisitor annotationVisitor = generatorAdapter.visitParameterAnnotation(parameterIndex, TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
                visitAnnotation(annotation, annotationVisitor);
            }
            writeTypeAnnotations(
                parameter.getType(),
                TypeReference.newFormalParameterReference(parameterIndex).getValue(),
                generatorAdapter::visitTypeAnnotation
            );
            Type parameterType = TypeUtils.getType(parameter.getType(), objectDef);
            MethodContext.LocalData prevParam = context.locals().put(parameter.getName(), new MethodContext.LocalData(
                parameter.getName(),
                parameterType,
                startMethod,
                slot
            ));
            if (prevParam != null) {
                throw new IllegalStateException("Duplicate method parameter: " + parameter.getName() + " of method: " + methodDef.getName() + " " + (objectDef == null ? "" : objectDef.getName()));
            }
            parameterIndex++;
            slot += parameterType.getSize();
        }
        return startMethod;
    }

    /**
     * Write the body of a method, appending the implicit return of a void method.
     *
     * @param generatorAdapter The generator adapter
     * @param objectDef        The object definition
     * @param methodDef        The method definition
     * @param context          The method context
     * @param statements       The statements to write
     * @param startMethod      The label the parameters start at
     */
    private void writeStatements(GeneratorAdapter generatorAdapter,
                                 @Nullable ObjectDef objectDef,
                                 MethodDef methodDef,
                                 MethodContext context,
                                 List<StatementDef> statements,
                                 @Nullable Label startMethod) {
        generatorAdapter.visitCode();
        if (startMethod != null) {
            generatorAdapter.visitLabel(startMethod);
        }
        for (StatementDef statement : statements) {
            StatementWriter.of(statement).write(generatorAdapter, context, null);
        }
        if (hasReturnStatement(statements.getLast())) {
            return;
        }
        if (!methodDef.getReturnType().equals(TypeDef.VOID)) {
            throw new IllegalStateException("The method: " + (objectDef == null ? "" : objectDef.getName()) + " " + methodDef.getName() + " doesn't return the result!");
        }
        generatorAdapter.returnValue();
    }

    /**
     * Write the local variable table of a method.
     *
     * @param methodVisitor    The method visitor
     * @param generatorAdapter The generator adapter
     * @param context          The method context
     */
    private void writeLocalVariableTable(MethodVisitor methodVisitor, GeneratorAdapter generatorAdapter, MethodContext context) {
        Label endMethod = new Label();
        if (!context.locals().isEmpty()) {
            generatorAdapter.visitLabel(endMethod);
        }
        for (MethodContext.LocalData localsDatum : context.locals().values()) {
            methodVisitor.visitLocalVariable(
                localsDatum.name(),
                localsDatum.type().getDescriptor(),
                null,
                localsDatum.start(),
                endMethod,
                localsDatum.index()
            );
        }
    }

    /**
     * Write the bridge methods a method requires.
     *
     * <p>The bridges are resolved from the declared supertypes: one per inherited method that this
     * method overrides with a different erasure. A bridge delegates to the method, casting any
     * parameter whose type was erased. Bridges of an abstract method are abstract too, and the declared
     * exceptions and the annotations of the delegate are repeated on the bridge, matching what the Java
     * compiler emits.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param methodDef    The method the bridges delegate to
     */
    private void writeBridgeMethods(ClassVisitor classVisitor,
                                    @Nullable ObjectDef objectDef,
                                    MethodDef methodDef,
                                    Set<String> emittedBridges) {
        List<BridgeResolver.BridgeMethod> resolved = BridgeResolver.resolve(objectDef, methodDef);
        if (resolved.isEmpty()) {
            return;
        }
        // An interface bridge is always a concrete default method delegating through the interface,
        // even when the method it bridges is abstract; that is what the Java compiler emits, because
        // interface dispatch reaches the implementation either way
        boolean isInterface = objectDef instanceof InterfaceDef;
        boolean isAbstract = !isInterface && methodDef.getModifiers().contains(Modifier.ABSTRACT);
        List<ParameterDef> parameters = methodDef.getParameters();
        for (BridgeResolver.BridgeMethod bridge : resolved) {
            MethodDef.MethodDefBuilder builder = MethodDef.builder(methodDef.getName())
                .addModifiers(bridgeModifiers(methodDef, isAbstract))
                .returns(bridge.returnType())
                .addAnnotations(methodDef.getAnnotations())
                .addThrows(methodDef.getThrowTypes());
            for (int i = 0; i < parameters.size(); i++) {
                ParameterDef parameter = parameters.get(i);
                builder.addParameter(ParameterDef.builder(parameter.getName(), bridge.parameterTypes().get(i))
                    .addAnnotations(parameter.getAnnotations())
                    .build());
            }
            if (!isAbstract) {
                builder.addStatement((aThis, bridgeParameters) -> {
                    // The invocation casts every parameter to the type of the delegate
                    ExpressionDef.InvokeInstanceMethod invocation = aThis.invoke(methodDef, bridgeParameters);
                    return bridge.returnType().equals(TypeDef.VOID) ? invocation : invocation.returning();
                });
            }
            MethodDef bridgeDef = builder.build();
            // Same-name overloads of the class can each resolve the same bridge; two methods with
            // different names never collide, so the name is part of the key
            if (emittedBridges.add(bridgeDef.getName() + TypeUtils.getMethodDescriptor(objectDef, bridgeDef))) {
                writeMethod(classVisitor, objectDef, bridgeDef, false, ACC_BRIDGE | ACC_SYNTHETIC, emittedBridges);
            }
        }
    }

    private static Collection<Modifier> bridgeModifiers(MethodDef methodDef, boolean isAbstract) {
        return methodDef.getModifiers().stream()
            .filter(m -> m == Modifier.PUBLIC || m == Modifier.PROTECTED || m == Modifier.PRIVATE || (isAbstract && m == Modifier.ABSTRACT))
            .toList();
    }

    private List<StatementDef> adjustConstructorStatements(@Nullable ObjectDef objectDef, List<StatementDef> statements) {
        if (!(objectDef instanceof ClassDef || objectDef instanceof RecordDef)) {
            return statements;
        }
        // A record has no fields of its own, only the ones backing its components, which the canonical constructor assigns
        List<StatementDef> fieldInitializers = objectDef instanceof ClassDef classDef
            ? classDef.getFields().stream().filter(fieldDef -> !fieldDef.getModifiers().contains(Modifier.STATIC))
            .flatMap(fieldDef -> fieldDef.getInitializer().<StatementDef>map(initializer -> new VariableDef.This().field(fieldDef).assign(initializer)).stream())
            .toList()
            : List.of();
        Optional<StatementDef> constructorInvocation = statements.stream().filter(this::isConstructorInvocation).findFirst();
        if (constructorInvocation.isEmpty() || !fieldInitializers.isEmpty()) {
            // Add the constructor or reshuffle the statements to have the field initializers right after the constructor call
            List<StatementDef> newStatements = new ArrayList<>();
            // Constructor call
            newStatements.add(constructorInvocation.orElseGet(this::superConstructorInvocation));
            // Fields initializer
            newStatements.addAll(fieldInitializers);
            // Statements
            if (constructorInvocation.isPresent()) {
                // Remove constructor moved to the front
                List<StatementDef> statementsWithoutConstructor = new ArrayList<>(statements);
                statementsWithoutConstructor.remove(constructorInvocation.get());
                newStatements.addAll(statementsWithoutConstructor);
            } else {
                newStatements.addAll(statements);
            }
            statements = newStatements;
        }
        return statements;
    }

    private boolean hasReturnStatement(StatementDef statement) {
        List<StatementDef> statements = statement.flatten();
        if (statements.isEmpty()) {
            return false;
        }
        StatementDef statementDef = statements.get(statements.size() - 1);
        if (statementDef instanceof StatementDef.IfElse ifElse) {
            return hasReturnStatement(ifElse.statement()) && hasReturnStatement(ifElse.elseStatement());
        }
        if (statementDef instanceof StatementDef.Try aTry) {
            return hasReturnStatement(aTry.statement());
        }
        if (statementDef instanceof StatementDef.Synchronized aSynchronized) {
            return hasReturnStatement(aSynchronized.statement());
        }
        if (statementDef instanceof StatementDef.Switch switchStatement) {
            if (switchStatement.defaultCase() == null) {
                return false;
            }
            return switchStatement.cases().values().stream().allMatch(this::hasReturnStatement);
        }
        return statementDef instanceof StatementDef.Return || statementDef instanceof StatementDef.Throw;
    }

    private StatementDef superConstructorInvocation() {
        return new VariableDef.This().superRef().invokeSuperConstructor();
    }

    private boolean isConstructorInvocation(StatementDef statement) {
        boolean deprecatedCall = statement instanceof ExpressionDef.InvokeInstanceMethod call && call.method().isConstructor();
        return deprecatedCall || statement instanceof StatementDef.InvokeSuperConstructor;
    }

    /**
     * The access flags of a class file. Unlike a member, a class cannot be declared private, protected or
     * static there - those belong to the {@code InnerClasses} entry of a member type. A member type declared
     * protected is reachable from a subclass in another package, so its class file is public, the way a
     * compiler writes it.
     *
     * @param modifiers The declared modifiers
     * @return The access flags of the class file
     */
    private int getClassModifiersFlag(Set<Modifier> modifiers, @Nullable ClassTypeDef outerType) {
        int access = getModifiersFlag(modifiers) & ~(ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC);
        boolean implicitlyPublic = outerType != null
            && outerType.isInterface()
            && (access & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0;
        if (modifiers.contains(Modifier.PROTECTED) || implicitlyPublic) {
            access |= ACC_PUBLIC;
        }
        return access;
    }

    private int getModifiersFlag(Set<Modifier> modifiers) {
        int access = 0;
        if (modifiers.contains(Modifier.PUBLIC)) {
            access |= ACC_PUBLIC;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            access |= ACC_PRIVATE;
        }
        if (modifiers.contains(Modifier.PROTECTED)) {
            access |= ACC_PROTECTED;
        }
        if (modifiers.contains(Modifier.FINAL)) {
            access |= ACC_FINAL;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            access |= ACC_ABSTRACT;
        }
        if (modifiers.contains(Modifier.STATIC)) {
            access |= ACC_STATIC;
        }
        return access;
    }

    /**
     * Writes the bytecode of generated class.
     *
     * @param objectDef The object definition.
     * @return The bytes
     */
    public byte[] write(ObjectDef objectDef) {
        return write(objectDef, null);
    }

    /**
     * Writes the bytecode of generated class.
     *
     * @param objectDef The object definition.
     * @param outerType The outer type.
     * @return The bytes
     */
    public byte[] write(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        return createClassWriterAndWriteObject(objectDef, outerType).toByteArray();
    }

    /**
     * A visitor of a member that a type annotation can be written on - a field, a record component or a
     * method all take one the same way.
     */
    @FunctionalInterface
    private interface TypeAnnotatable {

        AnnotationVisitor visitTypeAnnotation(int typeRef, @Nullable TypePath typePath, String descriptor, boolean visible);
    }

}
