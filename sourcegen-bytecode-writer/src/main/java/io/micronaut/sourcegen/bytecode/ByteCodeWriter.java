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

import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.sourcegen.bytecode.core.AnnotationTargetUtils;
import io.micronaut.sourcegen.bytecode.core.BridgeResolver;
import io.micronaut.sourcegen.bytecode.core.ConstructorBody;
import io.micronaut.sourcegen.bytecode.core.ModifierUtils;
import io.micronaut.sourcegen.bytecode.statement.StatementWriter;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
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

import static org.objectweb.asm.Opcodes.ACC_RECORD;
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
    /**
     * The variables of the enclosing class in scope of the class this writer writes: {@link EnclosingScope#NONE} but
     * for the writer {@link #write(ObjectDef, ClassTypeDef)} creates for an inner class.
     */
    private final EnclosingScope enclosingScope;

    public ByteCodeWriter() {
        this(false, true);
    }

    public ByteCodeWriter(boolean checkClass, boolean visitMaxs) {
        this(checkClass, visitMaxs, EnclosingScope.NONE);
    }

    private ByteCodeWriter(boolean checkClass, boolean visitMaxs, EnclosingScope enclosingScope) {
        this.checkClass = checkClass;
        this.visitMaxs = visitMaxs;
        this.enclosingScope = enclosingScope;
    }

    private ClassWriter createClassWriterAndWriteObject(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        ClassVisitor classVisitor = classWriter;
        if (checkClass) {
            classVisitor = new CheckClassAdapter(classVisitor);
        }
        // An inner class has the variables of its enclosing class in scope
        new ByteCodeWriter(checkClass, visitMaxs, EnclosingScope.of(objectDef, outerType))
            .writeObject(classVisitor, objectDef, outerType);
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
        } else if (objectDef instanceof AnnotationObjectDef annotationObjectDef) {
            writeAnnotationObject(classVisitor, annotationObjectDef, outerType);
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
    /**
     * Writes the annotations of the bounds of type parameters - {@code T extends @Marker Number} - against the
     * parameter and the bound they annotate.
     */
    private void writeBoundAnnotations(List<TypeDef.TypeVariable> variables, int sort, TypeAnnotatable member) {
        for (int i = 0; i < variables.size(); i++) {
            List<TypeDef> bounds = variables.get(i).bounds();
            for (int j = 0; j < bounds.size(); j++) {
                writeTypeAnnotations(bounds.get(j), TypeReference.newTypeParameterBoundReference(sort, i,
                    io.micronaut.sourcegen.bytecode.core.SignatureUtils.boundIndex(bounds, j)).getValue(), member);
            }
        }
    }

    /**
     * Writes the annotations of the supertypes - {@code implements @Marker Supplier<@Marker T>} - the superclass as
     * supertype -1, the interfaces by their index.
     */
    private void writeSupertypeAnnotations(@Nullable TypeDef superclass, List<? extends TypeDef> superinterfaces, TypeAnnotatable member) {
        if (superclass != null) {
            writeTypeAnnotations(superclass, TypeReference.newSuperTypeReference(-1).getValue(), member);
        }
        for (int i = 0; i < superinterfaces.size(); i++) {
            writeTypeAnnotations(superinterfaces.get(i), TypeReference.newSuperTypeReference(i).getValue(), member);
        }
    }

    private void writeTypeAnnotations(TypeDef typeDef, int typeRef, TypeAnnotatable member) {
        writeTypeAnnotations(typeDef, typeRef, "", member);
    }

    /**
     * Walk a type, writing the annotations each part of it carries against the path that reaches that part.
     * An annotation on the type itself has no path, one on what an array holds is reached through the array,
     * and one on a type argument through the argument it annotates - the way {@code List<@Nullable String>}
     * annotates the argument and not the list.
     *
     * @param typeDef  The type to walk
     * @param typeRef  The reference of the type within the member, from {@link TypeReference}
     * @param typePath The path reaching this part of the type, encoded as {@link TypePath#fromString} reads it
     * @param member   The member the annotations are written on
     */
    private void writeTypeAnnotations(TypeDef typeDef, int typeRef, String typePath, TypeAnnotatable member) {
        // A member of an enclosing type, `Outer<A>.Inner<B>`, is reached through a nested type step per level
        String nested = ".".repeat(io.micronaut.sourcegen.bytecode.core.TypeUtils.memberDepth(typeDef));
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            writeTypeAnnotations(annotated.annotations(), typeRef, typePath + nested, member);
            writeTypeAnnotations(annotated.typeDef(), typeRef, typePath, member);
        } else if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            writeTypeAnnotations(annotated.annotations(), typeRef, typePath + nested, member);
            writeTypeAnnotations(annotated.typeDef(), typeRef, typePath, member);
        } else if (typeDef instanceof TypeDef.Array array) {
            writeTypeAnnotations(array.componentType(), typeRef, typePath + "[".repeat(array.dimensions()), member);
        } else if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            List<TypeDef> typeArguments = parameterized.typeArguments();
            for (int i = 0; i < typeArguments.size(); i++) {
                writeTypeAnnotations(typeArguments.get(i), typeRef, typePath + nested + i + ";", member);
            }
            writeTypeAnnotations(parameterized.rawType(), typeRef, typePath, member);
        } else if (typeDef instanceof ClassTypeDef classType && TypeHierarchy.enclosingOf(classType) instanceof ClassTypeDef enclosing) {
            // The enclosing type and its arguments are reached from where the member is
            writeTypeAnnotations(enclosing, typeRef, typePath, member);
        } else if (typeDef instanceof TypeDef.Wildcard wildcard) {
            for (TypeDef bound : CollectionUtils.concat(wildcard.upperBounds(), wildcard.lowerBounds())) {
                writeTypeAnnotations(bound, typeRef, typePath + "*", member);
            }
        }
    }

    private void writeTypeAnnotations(List<AnnotationDef> annotations, int typeRef, String typePath, TypeAnnotatable member) {
        for (AnnotationDef annotation : annotations) {
            RetentionPolicy retention = retentionOf(annotation);
            if (retention == RetentionPolicy.SOURCE) {
                continue;
            }
            visitAnnotation(annotation, member.visitTypeAnnotation(
                typeRef,
                TypePath.fromString(typePath),
                TypeUtils.getType(annotation.getType(), null, EnclosingScope.NONE).getDescriptor(),
                retention == RetentionPolicy.RUNTIME
            ));
        }
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
        int modifiersFlag = ModifierUtils.fieldFlags(objectDef, fieldDef);
        FieldVisitor fieldVisitor = classVisitor.visitField(
            modifiersFlag,
            fieldDef.getName(),
            TypeUtils.getType(fieldDef.getType(), objectDef, enclosingScope).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(objectDef, fieldDef, enclosingScope),
            null
        );
        for (AnnotationDef annotation : declarationAnnotations(fieldDef.getAnnotations(), ElementType.FIELD)) {
            writeAnnotation(annotation, fieldVisitor::visitAnnotation);
        }
        writeTypeAnnotations(
            annotatedType(fieldDef.getType(), fieldDef.getAnnotations()),
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
        int modifiersFlag = ModifierUtils.classFileFlags(interfaceDef, outerType);
        classVisitor.visit(V17,
            modifiersFlag,
            TypeUtils.getType(interfaceDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getInterfaceSignature(interfaceDef, enclosingScope),
            TypeUtils.OBJECT_TYPE.getInternalName(),
            interfaceDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, interfaceDef, enclosingScope)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, interfaceDef.asTypeDef(), interfaceDef, outerType);
        for (AnnotationDef annotation : interfaceDef.getAnnotations()) {
            writeAnnotation(annotation, classVisitor::visitAnnotation);
        }
        writeBoundAnnotations(interfaceDef.getTypeVariables(), TypeReference.CLASS_TYPE_PARAMETER_BOUND, classVisitor::visitTypeAnnotation);
        writeSupertypeAnnotations(null, interfaceDef.getSuperinterfaces(), classVisitor::visitTypeAnnotation);
        for (MethodDef method : interfaceDef.getMethods()) {
            writeMethod(classVisitor, interfaceDef, method, emittedBridges);
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            writeProperty(classVisitor, interfaceDef, property, emittedBridges);
        }
    }

    /**
     * Write an annotation type.
     *
     * <p>An annotation type is emitted the way a compiler emits it: an interface flagged
     * {@code ACC_ANNOTATION} that extends {@link Annotation}, with one abstract accessor per member and
     * the member's default, when it has one, in that accessor's {@code AnnotationDefault} attribute.</p>
     *
     * @param classVisitor  The class visitor
     * @param annotationDef The annotation definition
     * @param outerType     The outer type
     */
    public void writeAnnotationObject(ClassVisitor classVisitor, AnnotationObjectDef annotationDef, @Nullable ClassTypeDef outerType) {
        int modifiersFlag = ModifierUtils.classFileFlags(annotationDef, outerType);
        ClassTypeDef typeDef = annotationDef.asTypeDef();
        classVisitor.visit(V17,
            modifiersFlag,
            TypeUtils.getType(typeDef).getInternalName(),
            null,
            TypeUtils.OBJECT_TYPE.getInternalName(),
            new String[]{Type.getType(Annotation.class).getInternalName()}
        );
        writeOuterInner(classVisitor, typeDef, annotationDef, outerType);
        for (AnnotationDef annotation : annotationDef.getAnnotations()) {
            writeAnnotation(annotation, classVisitor::visitAnnotation);
        }
        List<StatementDef> staticInitStatements = new ArrayList<>();
        for (FieldDef field : annotationDef.getFields()) {
            writeField(classVisitor, annotationDef, field);
            // A constant of an annotation type is implicitly static, wherever it is assigned from
            field.getInitializer().ifPresent(initializer ->
                staticInitStatements.add(typeDef.getStaticField(field).put(initializer)));
        }
        if (!staticInitStatements.isEmpty()) {
            writeMethod(classVisitor, annotationDef,
                createStaticInitializer(StatementDef.multi(staticInitStatements)), new HashSet<>());
        }
        for (AnnotationMemberDef member : annotationDef.getMembers()) {
            writeAnnotationMember(classVisitor, annotationDef, member);
        }
    }

    /**
     * Write a member of an annotation type as the abstract accessor it is, followed by its default value.
     * A default is a single value written with no name, which is what the {@code AnnotationDefault}
     * attribute holds.
     */
    private void writeAnnotationMember(ClassVisitor classVisitor, AnnotationObjectDef annotationDef, AnnotationMemberDef member) {
        MethodDef accessor = MethodDef.builder(member.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addAnnotations(member.getAnnotations())
            .returns(member.getType())
            .build();
        MethodVisitor methodVisitor = visitMethodHeader(
            classVisitor,
            annotationDef,
            accessor,
            ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_ABSTRACT,
            accessor.getName(),
            TypeUtils.getMethodDescriptor(annotationDef, accessor, enclosingScope)
        );
        for (AnnotationDef annotation : member.getAnnotations()) {
            writeAnnotation(annotation, methodVisitor::visitAnnotation);
        }
        writeTypeAnnotations(
            member.getType(),
            TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(),
            methodVisitor::visitTypeAnnotation
        );
        Object defaultValue = member.getAnnotationDefaultValue() != null
            ? member.getAnnotationDefaultValue()
            : member.getDefaultValue();
        if (defaultValue != null) {
            AnnotationVisitor annotationVisitor = methodVisitor.visitAnnotationDefault();
            if (annotationVisitor != null) {
                if (member.getType() instanceof TypeDef.Array && isSingleValue(defaultValue)) {
                    // A single value is the source shorthand for a one element array
                    visitAnnotationArray(annotationVisitor, null, List.of(defaultValue));
                } else {
                    visitAnnotation(annotationVisitor, null, defaultValue);
                }
                annotationVisitor.visitEnd();
            }
        }
        methodVisitor.visitEnd();
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
        // A record is always final; ACC_RECORD is ASM's own flag, which it writes as the Record attribute
        int modifiersFlag = ACC_RECORD | ModifierUtils.classFileFlags(recordDef, outerType);
        classVisitor.visit(
            V17,
            modifiersFlag,
            TypeUtils.getType(recordDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getRecordSignature(recordDef, enclosingScope),
            Type.getType(Record.class).getInternalName(),
            recordDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, recordDef, enclosingScope)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, recordDef.asTypeDef(), recordDef, outerType);

        for (AnnotationDef annotation : recordDef.getAnnotations()) {
            writeAnnotation(annotation, classVisitor::visitAnnotation);
        }
        writeBoundAnnotations(recordDef.getTypeVariables(), TypeReference.CLASS_TYPE_PARAMETER_BOUND, classVisitor::visitTypeAnnotation);
        writeSupertypeAnnotations(null, recordDef.getSuperinterfaces(), classVisitor::visitTypeAnnotation);

        List<PropertyDef> components = recordDef.getProperties();
        List<FieldDef> componentFields = components.stream().map(ByteCodeWriter::toComponentField).toList();

        for (int i = 0; i < components.size(); i++) {
            writeRecordComponent(classVisitor, recordDef, components.get(i), componentFields.get(i));
        }
        for (FieldDef componentField : componentFields) {
            writeField(classVisitor, recordDef, componentField);
        }
        List<TypeDef> componentTypes = components.stream().map(PropertyDef::getType).toList();
        if (!io.micronaut.sourcegen.bytecode.core.TypeUtils.declaresMethod(recordDef, MethodDef.CONSTRUCTOR, componentTypes, enclosingScope)) {
            writeMethod(classVisitor, recordDef, canonicalConstructor(recordDef, components, componentFields), emittedBridges);
        }
        writeObjectMethods(classVisitor, recordDef, componentFields);
        for (int i = 0; i < components.size(); i++) {
            PropertyDef component = components.get(i);
            if (io.micronaut.sourcegen.bytecode.core.TypeUtils.declaresMethod(recordDef, component.getName(), List.of(), enclosingScope)) {
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
        return FieldDef.builder(component.getName(), component.getType())
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .addAnnotations(annotationsFor(component, ElementType.FIELD))
            .build();
    }

    /**
     * The annotations of a record component that belong on one of the members it expands to. A compiler
     * spreads a component's annotations over the component itself, the field backing it, its accessor and
     * the canonical constructor's parameter, keeping each only where the annotation's {@link Target} allows
     * it. An annotation that declares no target is applicable in every one of those contexts, and so is one
     * whose type cannot be resolved here - it is written everywhere rather than dropped.
     *
     * <p>A type use annotation is kept for every member, whether or not it also targets the member itself:
     * the member is written with it on the type it declares, which is where the annotation belongs.</p>
     *
     * @param component   The record component
     * @param elementType The context the annotations are written in
     * @return The annotations that belong there
     */
    private static List<AnnotationDef> annotationsFor(PropertyDef component, ElementType elementType) {
        return component.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation)
                .map(t -> t.contains(elementType) || t.contains(ElementType.TYPE_USE))
                .orElse(true))
            .toList();
    }

    /**
     * The annotations of a record component that belong on the component itself, where a type use annotation
     * is written against the component's type instead and so is not among them.
     *
     * @param component The record component
     * @return The annotations of the record component attribute
     */
    private static List<AnnotationDef> componentAnnotations(PropertyDef component) {
        return component.getAnnotations().stream()
            .filter(annotation -> targetsOf(annotation)
                .map(t -> t.contains(ElementType.RECORD_COMPONENT))
                .orElse(true))
            .toList();
    }

    /**
     * The contexts an annotation declares as its targets, or empty when it declares none and is therefore
     * applicable in all of them.
     *
     * @param annotation The annotation
     * @return Its targets, or empty when it declares none or its type cannot be resolved here
     */
    private static Optional<Set<ElementType>> targetsOf(AnnotationDef annotation) {
        return AnnotationTargetUtils.targetsOf(annotation, ByteCodeWriter.class.getClassLoader());
    }

    /**
     * @param annotations The annotations of a declaration
     * @param elementType The kind of declaration they are written on
     * @return Those of them that belong among the declaration annotations
     */
    private static List<AnnotationDef> declarationAnnotations(List<AnnotationDef> annotations, ElementType elementType) {
        return AnnotationTargetUtils.declarationAnnotations(annotations, elementType, ByteCodeWriter.class.getClassLoader());
    }

    /**
     * @param typeDef     The declared type
     * @param annotations The annotations of the declaration
     * @return The type, carrying those of the annotations that annotate a type use
     */
    private static TypeDef annotatedType(TypeDef typeDef, List<AnnotationDef> annotations) {
        return AnnotationTargetUtils.annotatedType(typeDef, annotations, ByteCodeWriter.class.getClassLoader());
    }

    private static RetentionPolicy retentionOf(AnnotationDef annotation) {
        return AnnotationTargetUtils.retentionOf(annotation, ByteCodeWriter.class.getClassLoader());
    }

    private void writeRecordComponent(ClassVisitor classVisitor, RecordDef recordDef, PropertyDef component, FieldDef componentField) {
        RecordComponentVisitor recordComponentVisitor = classVisitor.visitRecordComponent(
            component.getName(),
            TypeUtils.getType(component.getType(), recordDef, enclosingScope).getDescriptor(),
            SignatureWriterUtils.getFieldSignature(recordDef, componentField, enclosingScope)
        );
        for (AnnotationDef annotation : componentAnnotations(component)) {
            writeAnnotation(annotation, recordComponentVisitor::visitAnnotation);
        }
        writeTypeAnnotations(
            annotatedType(componentField.getType(), component.getAnnotations()),
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
                TypeUtils.getType(componentField.getType(), recordDef, enclosingScope).getDescriptor(),
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
        if (io.micronaut.sourcegen.bytecode.core.TypeUtils.declaresMethod(recordDef, name, name.equals("equals") ? List.of(TypeDef.OBJECT) : List.of(), enclosingScope)) {
            return;
        }
        int modifiersFlag = ModifierUtils.ACC_PUBLIC | ModifierUtils.ACC_FINAL;
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

        int modifiersFlag = ModifierUtils.classFileFlags(classDef, outerType);
        classVisitor.visit(
            V17,
            modifiersFlag,
            TypeUtils.getType(classDef.asTypeDef()).getInternalName(),
            SignatureWriterUtils.getClassSignature(classDef, enclosingScope),
            TypeUtils.getType(Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT), null, EnclosingScope.NONE).getInternalName(),
            classDef.getSuperinterfaces().stream().map(i -> TypeUtils.getType(i, classDef, enclosingScope)).map(Type::getInternalName).toArray(String[]::new)
        );
        writeOuterInner(classVisitor, classDef.asTypeDef(), classDef, outerType);

        for (AnnotationDef annotation : classDef.getAnnotations()) {
            writeAnnotation(annotation, classVisitor::visitAnnotation);
        }
        writeBoundAnnotations(classDef.getTypeVariables(), TypeReference.CLASS_TYPE_PARAMETER_BOUND, classVisitor::visitTypeAnnotation);
        writeSupertypeAnnotations(classDef.getSuperclass(), classDef.getSuperinterfaces(), classVisitor::visitTypeAnnotation);

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
        // An implementation inherited from a superclass satisfies an added interface through a bridge of this class
        for (BridgeResolver.Bridge bridge : BridgeResolver.inheritedBridgesOf(classDef, enclosingScope)) {
            writeBridge(classVisitor, classDef, bridge, emittedBridges);
        }
        // A public method inherited from a superclass that is not public is declared again, as javac does
        for (BridgeResolver.Bridge bridge : BridgeResolver.visibilityBridgesOf(classDef, enclosingScope)) {
            writeBridge(classVisitor, classDef, bridge, emittedBridges);
        }
    }

    private void writeOuterInner(ClassVisitor classVisitor, ClassTypeDef thisType, ObjectDef thisDef, @Nullable ClassTypeDef outerType) {
        if (outerType != null) {
            String outerInternalName = TypeUtils.getType(outerType).getInternalName();
            classVisitor.visitNestHost(nestHostInternalName(outerType));
            classVisitor.visitInnerClass(
                TypeUtils.getType(thisType).getInternalName(),
                outerInternalName,
                thisType.getSimpleName(),
                ModifierUtils.innerClassFlags(thisDef, outerType.isInterface())
            );
        }
        writeInnerTypes(classVisitor, thisType, thisDef, outerType == null);
    }

    /**
     * The {@code InnerClasses} entries of the member types of a definition, and, when the definition hosts
     * the nest, the {@code NestMembers} entry of every one of them. The nest is flat - a member of a member
     * belongs to the same host - while the entries are not: each one names the type it is declared in, so
     * both are written by descending through the levels rather than only over the direct members. A class
     * file has to carry an entry for every nested class it names, which the deeper members it lists as nest
     * members are.
     *
     * @param outerClassVisitor The visitor of the class being written
     * @param outerType         The type the members are declared in
     * @param outerDef          Its definition
     * @param nestHost          Whether the class being written hosts the nest
     */
    private void writeInnerTypes(ClassVisitor outerClassVisitor,
                                 ClassTypeDef outerType,
                                 ObjectDef outerDef,
                                 boolean nestHost) {
        for (ObjectDef innerDef : outerDef.getInnerTypes()) {
            String outerClassInternalName = TypeUtils.getType(outerType).getInternalName();

            ClassTypeDef interType = innerDef.asTypeDef();
            String innerClassInternalName = TypeUtils.getType(interType).getInternalName();
            outerClassVisitor.visitInnerClass(
                innerClassInternalName,
                outerClassInternalName,
                interType.getSimpleName(),
                ModifierUtils.innerClassFlags(innerDef, outerDef instanceof InterfaceDef)
            );
            if (nestHost) {
                outerClassVisitor.visitNestMember(innerClassInternalName);
                writeInnerTypes(outerClassVisitor, interType, innerDef, true);
            }
        }
    }

    private String nestHostInternalName(ClassTypeDef memberType) {
        String name = memberType.getName();
        int simpleNameStart = name.lastIndexOf('.') + 1;
        int separator = name.indexOf('$', simpleNameStart + 1);
        String hostName = separator == -1 ? name : name.substring(0, separator);
        return TypeUtils.getType(hostName).getInternalName();
    }

    private void writeAnnotation(AnnotationDef annotation, Annotatable member) {
        RetentionPolicy retention = retentionOf(annotation);
        if (retention == RetentionPolicy.SOURCE) {
            return;
        }
        visitAnnotation(annotation, member.visitAnnotation(
            TypeUtils.getType(annotation.getType(), null, EnclosingScope.NONE).getDescriptor(),
            retention == RetentionPolicy.RUNTIME
        ));
    }

    private void visitAnnotation(AnnotationDef annotation, @Nullable AnnotationVisitor annotationVisitor) {
        if (annotationVisitor == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : annotation.getValues().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSingleValue(value) && AnnotationTargetUtils.isArrayMember(annotation, key, getClass().getClassLoader())) {
                // A single value is the source shorthand for a one element array; a class file has no shorthand
                visitAnnotationArray(annotationVisitor, key, List.of(value));
            } else {
                visitAnnotation(annotationVisitor, key, value);
            }
        }
        annotationVisitor.visitEnd();
    }

    private void visitAnnotation(AnnotationVisitor annotationVisitor, @Nullable String name, Object annotationValue) {
        Object value = unwrapConstant(annotationValue, name);
        if (value instanceof VariableDef.StaticField staticField) {
            visitStaticField(annotationVisitor, name, staticField);
        } else if (value instanceof ClassTypeDef classTypeDef) {
            annotationVisitor.visit(name, TypeUtils.getType(classTypeDef, null, EnclosingScope.NONE));
        } else if (value instanceof Class<?> type) {
            annotationVisitor.visit(name, Type.getType(type));
        } else if (value instanceof AnnotationDef nestedAnnotation) {
            visitAnnotation(
                nestedAnnotation,
                annotationVisitor.visitAnnotation(name, TypeUtils.getType(nestedAnnotation.getType(), null, EnclosingScope.NONE).getDescriptor())
            );
        } else if (value instanceof AnnotationDef[] annotations) {
            visitAnnotationArray(annotationVisitor, name, Arrays.asList(annotations));
        } else if (value instanceof Collection<?> coll) {
            visitAnnotationArray(annotationVisitor, name, coll);
        } else if (value instanceof Object[] array) {
            visitAnnotationArray(annotationVisitor, name, Arrays.asList(array));
        } else if (value instanceof Enum<?> anEnum) {
            annotationVisitor.visitEnum(name, Type.getDescriptor(anEnum.getDeclaringClass()), anEnum.name());
        } else {
            annotationVisitor.visit(name, value);
        }
    }

    /**
     * A static field is a class literal when it is the synthetic {@code class} field of a type, and the
     * constant of an enum otherwise.
     */
    private static void visitStaticField(AnnotationVisitor annotationVisitor, @Nullable String name, VariableDef.StaticField staticField) {
        if (staticField.name().equals("class") && staticField.type().equals(TypeDef.CLASS)) {
            annotationVisitor.visit(name, TypeUtils.getType(staticField.ownerType(), null, EnclosingScope.NONE));
        } else {
            annotationVisitor.visitEnum(
                name,
                TypeUtils.getType(staticField.ownerType(), null, EnclosingScope.NONE).getDescriptor(),
                staticField.name()
            );
        }
    }

    /**
     * The value a constant expression stands for. An annotation value is never null.
     */
    private static Object unwrapConstant(Object value, @Nullable String name) {
        if (value instanceof ExpressionDef.Constant constant) {
            Object constantValue = constant.value();
            if (constantValue == null) {
                throw new IllegalArgumentException("An annotation value cannot be null: " + name);
            }
            return constantValue;
        }
        return value;
    }

    /**
     * Whether a value stands for one element rather than for a whole array member.
     */
    private static boolean isSingleValue(Object value) {
        Object actual = value instanceof ExpressionDef.Constant constant ? constant.value() : value;
        return actual != null && !(actual instanceof Collection<?>) && !actual.getClass().isArray();
    }

    private void visitAnnotationArray(AnnotationVisitor annotationVisitor, @Nullable String name, Collection<?> values) {
        AnnotationVisitor arrayVisitor = annotationVisitor.visitArray(name);
        for (Object value : values) {
            visitAnnotation(arrayVisitor, null, value);
        }
        arrayVisitor.visitEnd();
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
        String methodDescriptor = TypeUtils.getMethodDescriptor(objectDef, methodDef, enclosingScope);
        int modifiersFlag = ModifierUtils.methodFlags(methodDef) | extraModifiersFlag;
        MethodVisitor methodVisitor = visitMethodHeader(classVisitor, objectDef, methodDef, modifiersFlag, name, methodDescriptor);
        // The method is buffered: a try registers its exception handlers after those of the statements
        // nested in it, once their labels are visited, and the buffer replays them ahead of the code as a
        // method visitor expects
        MethodNode methodNode = new MethodNode(Opcodes.ASM9, modifiersFlag, name, methodDescriptor, null, null);
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodNode, modifiersFlag, name, methodDescriptor);
        writeMethodAnnotations(generatorAdapter, methodDef);

        MethodContext context = new MethodContext(objectDef, methodDef, isLambda, enclosingScope);
        Label startMethod = writeParameters(generatorAdapter, objectDef, methodDef, context);

        List<StatementDef> statements = methodDef.getStatements();
        if (methodDef.isConstructor()) {
            // The constructor call first, then the field initializers - run again after a delegation to this(...)
            statements = ConstructorBody.of(objectDef, statements, ConstructorBody.InitializersAfterThis.RERUN).asStatements();
        }
        if (!statements.isEmpty()) {
            writeStatements(generatorAdapter, objectDef, methodDef, context, statements, startMethod);
        }
        if (!statements.isEmpty()) {
            // An abstract method has no code for its parameters to be locals of
            writeLocalVariableTable(methodNode, generatorAdapter, context);
        }
        if (visitMaxs && !statements.isEmpty()) {
            generatorAdapter.visitMaxs(20, 20);
        }
        generatorAdapter.visitEnd();
        // A try whose body ends with a return leaves an empty exception range past the finally block the
        // return writes. The JVM rejects an empty range, and javac leaves such ranges out as well
        methodNode.tryCatchBlocks.removeIf(ByteCodeWriter::protectsNoInstruction);
        methodNode.accept(visitMaxs ? methodVisitor : new WithoutMaxsMethodVisitor(methodVisitor));

        for (MethodDef lambdaDef: context.lambdaMethods()) {
            writeMethod(classVisitor, objectDef, lambdaDef, true, 0, emittedBridges);
        }

        if (!isLambda && (extraModifiersFlag & ModifierUtils.ACC_BRIDGE) == 0) {
            writeBridgeMethods(classVisitor, objectDef, methodDef, emittedBridges);
        }
    }

    private static boolean protectsNoInstruction(TryCatchBlockNode tryCatchBlock) {
        for (AbstractInsnNode node = tryCatchBlock.start; node != null && node != tryCatchBlock.end; node = node.getNext()) {
            if (node.getOpcode() >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Declare the method and everything that belongs to its declaration rather than its body: the throws
     * clause, the generic signature - which a bridge never carries - and, for a record's canonical
     * constructor, the component names.
     *
     * @param classVisitor     The class visitor
     * @param objectDef        The object definition
     * @param methodDef        The method definition
     * @param modifiersFlag    The access flags
     * @param name             The method name
     * @param methodDescriptor The method descriptor
     * @return The visitor of the declared method
     */
    private MethodVisitor visitMethodHeader(ClassVisitor classVisitor,
                                                   @Nullable ObjectDef objectDef,
                                                   MethodDef methodDef,
                                                   int modifiersFlag,
                                                   String name,
                                                   String methodDescriptor) {
        String[] exceptions = methodDef.getThrowTypes().isEmpty() ? null : methodDef.getThrowTypes().stream()
            // A thrown variable of the method erases to its bound
            .map(t -> Type.getType(io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(t, objectDef, methodDef, enclosingScope)).getInternalName())
            .toArray(String[]::new);
        MethodVisitor methodVisitor = classVisitor.visitMethod(
            modifiersFlag,
            name,
            methodDescriptor,
            // A bridge carries an erased signature, it never gets a Signature attribute
            (modifiersFlag & ModifierUtils.ACC_BRIDGE) == 0 ? SignatureWriterUtils.getMethodSignature(objectDef, methodDef, enclosingScope) : null,
            exceptions
        );
        if (objectDef instanceof RecordDef recordDef && isCanonicalRecordConstructor(recordDef, methodDef)) {
            writeCanonicalRecordParameters(methodVisitor, methodDef);
        }
        return methodVisitor;
    }

    private void writeMethodAnnotations(GeneratorAdapter generatorAdapter, MethodDef methodDef) {
        ElementType declaration = methodDef.isConstructor() ? ElementType.CONSTRUCTOR : ElementType.METHOD;
        for (AnnotationDef annotation : declarationAnnotations(methodDef.getAnnotations(), declaration)) {
            writeAnnotation(annotation, generatorAdapter::visitAnnotation);
        }
        // A type use annotation written on a method is an annotation of the type it returns, and one written
        // on a constructor is an annotation of the type being constructed - both of which the class file
        // reaches through METHOD_RETURN. The definition of a constructor carries no return type of its own
        TypeDef returnType = methodDef.isConstructor() ? TypeDef.VOID : methodDef.getReturnType();
        writeTypeAnnotations(
            annotatedType(returnType, methodDef.getAnnotations()),
            TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(),
            generatorAdapter::visitTypeAnnotation
        );
        writeBoundAnnotations(methodDef.getTypeVariables(), TypeReference.METHOD_TYPE_PARAMETER_BOUND, generatorAdapter::visitTypeAnnotation);
        // `throws @Marker E`: an annotation of a thrown type
        for (int i = 0; i < methodDef.getThrowTypes().size(); i++) {
            writeTypeAnnotations(methodDef.getThrowTypes().get(i), TypeReference.newExceptionReference(i).getValue(),
                generatorAdapter::visitTypeAnnotation);
        }
        if (methodDef.getParameters().stream()
            .anyMatch(p -> !declarationAnnotations(p.getAnnotations(), ElementType.PARAMETER).isEmpty())) {
            generatorAdapter.visitAnnotableParameterCount(methodDef.getParameters().size(), true);
        }
    }

    /**
     * Write the MethodParameters attribute of a record's canonical constructor, so the component names
     * survive into the class file and reflection can recover them.
     *
     * @param methodVisitor The method visitor
     * @param methodDef     The canonical constructor
     */
    private static void writeCanonicalRecordParameters(MethodVisitor methodVisitor, MethodDef methodDef) {
        for (ParameterDef parameter : methodDef.getParameters()) {
            methodVisitor.visitParameter(parameter.getName(), ModifierUtils.parameterFlags(parameter));
        }
    }

    private boolean isCanonicalRecordConstructor(RecordDef recordDef, MethodDef methodDef) {
        if (!methodDef.isConstructor() || methodDef.getParameters().size() != recordDef.getProperties().size()) {
            return false;
        }
        for (int i = 0; i < methodDef.getParameters().size(); i++) {
            ParameterDef parameter = methodDef.getParameters().get(i);
            PropertyDef property = recordDef.getProperties().get(i);
            if (!parameter.getName().equals(property.getName())
                || !TypeUtils.getType(parameter.getType(), recordDef, enclosingScope).equals(TypeUtils.getType(property.getType(), recordDef, enclosingScope))) {
                return false;
            }
        }
        return true;
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
            for (AnnotationDef annotation : declarationAnnotations(parameter.getAnnotations(), ElementType.PARAMETER)) {
                int index = parameterIndex;
                writeAnnotation(annotation, (descriptor, visible) -> generatorAdapter.visitParameterAnnotation(index, descriptor, visible));
            }
            writeTypeAnnotations(
                annotatedType(parameter.getType(), parameter.getAnnotations()),
                TypeReference.newFormalParameterReference(parameterIndex).getValue(),
                generatorAdapter::visitTypeAnnotation
            );
            // A variable of the method erases to its bound
            Type parameterType = Type.getType(io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(parameter.getType(), objectDef, methodDef, enclosingScope));
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
        if (!io.micronaut.sourcegen.model.Completion.BYTECODE.canCompleteNormally(statements.getLast())) {
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
     * Write the bridge methods a method requires, as {@link BridgeResolver#bridgesOf} finishes them.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param methodDef    The method the bridges delegate to
     */
    private void writeBridgeMethods(ClassVisitor classVisitor,
                                    @Nullable ObjectDef objectDef,
                                    MethodDef methodDef,
                                    Set<String> emittedBridges) {
        for (BridgeResolver.Bridge bridge : BridgeResolver.bridgesOf(objectDef, methodDef, enclosingScope)) {
            writeBridge(classVisitor, objectDef, bridge, emittedBridges);
        }
    }

    private void writeBridge(ClassVisitor classVisitor,
                             @Nullable ObjectDef objectDef,
                             BridgeResolver.Bridge bridge,
                             Set<String> emittedBridges) {
        MethodDef bridgeDef = bridge.method();
        // Same-name overloads of the class can each resolve the same bridge; two methods with
        // different names never collide, so the name is part of the key
        if (emittedBridges.add(bridgeDef.getName() + TypeUtils.getMethodDescriptor(objectDef, bridgeDef, enclosingScope))) {
            writeMethod(classVisitor, objectDef, bridgeDef, false, bridge.flags(), emittedBridges);
        }
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

    @FunctionalInterface
    private interface Annotatable {

        AnnotationVisitor visitAnnotation(String descriptor, boolean visible);
    }

    /**
     * Replays a buffered method to a writer asked not to visit the maximum stack size and locals, which a
     * buffered method always visits.
     */
    private static final class WithoutMaxsMethodVisitor extends MethodVisitor {

        private WithoutMaxsMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Left out on purpose
        }
    }

}
