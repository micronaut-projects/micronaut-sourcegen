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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
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
 * @author Denis Stepanov
 * @since 1.5
 */
public final class ByteCodeWriter {

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
        int modifiersFlag = ACC_INTERFACE | ACC_ABSTRACT | getModifiersFlag(interfaceDef.getModifiers());
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
            writeMethod(classVisitor, interfaceDef, method);
        }
        for (PropertyDef property : interfaceDef.getProperties()) {
            writeProperty(classVisitor, interfaceDef, property);
        }
    }

    /**
     * Write an interface.
     *
     * @param classVisitor The class visitor
     * @param recordDef    The record definition
     */
    public void writeRecord(ClassVisitor classVisitor, RecordDef recordDef) {
        writeRecord(classVisitor, recordDef, null);
    }

    /**
     * Write an interface.
     *
     * @param classVisitor The class visitor
     * @param recordDef    The record definition
     * @param outerType     The outer type
     */
    public void writeRecord(ClassVisitor classVisitor, RecordDef recordDef, @Nullable ClassTypeDef outerType) {
        int modifiersFlag = ACC_RECORD | getModifiersFlag(recordDef.getModifiers());
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
        ClassTypeDef typeDef = classDef.asTypeDef();

        int modifiersFlag = getModifiersFlag(classDef.getModifiers());

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
            writeMethod(classVisitor, classDef, createStaticInitializer(StatementDef.multi(staticInitStatements)));
        }

        if (classDef.getMethods().stream().noneMatch(MethodDef::isConstructor)) {
            // Add default constructor
            MethodDef.MethodDefBuilder defaultConstructor = MethodDef.constructor();
            if (classDef.getModifiers().contains(Modifier.PUBLIC)) {
                defaultConstructor.addModifiers(Modifier.PUBLIC);
            }
            writeMethod(classVisitor, classDef, defaultConstructor
                .build((aThis, methodParameters) -> aThis.superRef().invokeSuperConstructor(methodParameters)));
        }

        for (PropertyDef property : classDef.getProperties()) {
            writeProperty(classVisitor, classDef, property);
        }
        for (MethodDef method : classDef.getMethods()) {
            writeMethod(classVisitor, classDef, method);
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
                getModifiersFlag(thisDef)
            );
        }
        writeInnerTypes(classVisitor, thisType, thisDef.getInnerTypes());
    }

    private void writeInnerTypes(ClassVisitor outerClassVisitor, ClassTypeDef outerType, List<ObjectDef> innerTypes) {
        for (ObjectDef innerDef : innerTypes) {
            String outerClassInternalName = TypeUtils.getType(outerType).getInternalName();

            ClassTypeDef interType = innerDef.asTypeDef();
            int access =  getModifiersFlag(innerDef);
            access |= ACC_PUBLIC | ACC_STATIC; // Javac always adds public and static
            outerClassVisitor.visitInnerClass(
                TypeUtils.getType(innerDef.asTypeDef()).getInternalName(),
                outerClassInternalName,
                interType.getSimpleName(),
                access
            );
            outerClassVisitor.visitNestMember(TypeUtils.getType(innerDef.asTypeDef()).getInternalName());
        }
    }

    private int getModifiersFlag(ObjectDef objectDef) {
        if (objectDef instanceof EnumDef enumDef) {
            return ACC_ENUM | getModifiersFlag(EnumGenUtils.toClassDef(enumDef));
        }
        if (objectDef instanceof InterfaceDef interfaceDef) {
            return ACC_INTERFACE | ACC_ABSTRACT | getModifiersFlag(interfaceDef.getModifiers());
        }
        if (objectDef instanceof RecordDef recordDef) {
            return getModifiersFlag(recordDef.getModifiers());
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

    private void writeProperty(ClassVisitor classWriter, ObjectDef objectDef, PropertyDef property) {
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

        writeMethod(classWriter, objectDef, getterBuilder.build());

        MethodDef.MethodDefBuilder setterBuilder = MethodDef.builder("set" + capitalizedPropertyName)
            .addParameter(ParameterDef.of(property.getName(), property.getType()))
            .addModifiers(property.getModifiersArray());

        if (!isAbstract) {
            setterBuilder.addStatement((aThis, methodParameters) -> aThis.field(propertyField).assign(methodParameters.get(0)));
        }

        writeMethod(classWriter, objectDef, setterBuilder.build());
    }

    /**
     * Write a method.
     *
     * @param classVisitor The class visitor
     * @param objectDef    The object definition
     * @param methodDef    The method definition
     */
    private void writeMethod(ClassVisitor classVisitor, @Nullable ObjectDef objectDef, MethodDef methodDef) {
        writeMethod(classVisitor, objectDef, methodDef, false);
    }

    private void writeMethod(ClassVisitor classVisitor, @Nullable ObjectDef objectDef, MethodDef methodDef, boolean isLambda) {
        String name = methodDef.getName();
        String methodDescriptor = TypeUtils.getMethodDescriptor(objectDef, methodDef);
        int modifiersFlag = getModifiersFlag(methodDef.getModifiers());
        if (methodDef.isSynthetic()) {
            modifiersFlag |= ACC_SYNTHETIC;
        }
        String[] exceptions = null;
        if (!methodDef.getThrowTypes().isEmpty()) {
            exceptions = methodDef.getThrowTypes().stream()
                .map(t -> TypeUtils.getType(t, objectDef).getClassName().replace(".", "/"))
                .toArray(String[]::new);
        }
        MethodVisitor methodVisitor = classVisitor.visitMethod(
            modifiersFlag,
            name,
            methodDescriptor,
            SignatureWriterUtils.getMethodSignature(objectDef, methodDef),
            exceptions
        );
        GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodVisitor, modifiersFlag, name, methodDescriptor);
        for (AnnotationDef annotation : methodDef.getAnnotations()) {
            generatorAdapter.visitAnnotation(TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
        }

        if (methodDef.getParameters().stream().anyMatch(p -> !p.getAnnotations().isEmpty())) {
            generatorAdapter.visitAnnotableParameterCount(methodDef.getParameters().size(), true);
        }

        MethodContext context = new MethodContext(objectDef, methodDef, isLambda);
        Label startMethod = null;

        int parameterIndex = 0;
        for (ParameterDef parameter : methodDef.getParameters()) {
            if (startMethod == null) {
                startMethod = new Label();
            }
            for (AnnotationDef annotation : parameter.getAnnotations()) {
                AnnotationVisitor annotationVisitor = generatorAdapter.visitParameterAnnotation(parameterIndex, TypeUtils.getType(annotation.getType(), null).getDescriptor(), true);
                visitAnnotation(annotation, annotationVisitor);
            }
            MethodContext.LocalData prevParam = context.locals().put(parameter.getName(), new MethodContext.LocalData(
                parameter.getName(),
                TypeUtils.getType(parameter.getType(), objectDef),
                startMethod,
                parameterIndex + 1
            ));
            if (prevParam != null) {
                throw new IllegalStateException("Duplicate method parameter: " + parameter.getName() + " of method: " + methodDef.getName() + " " + (objectDef == null ? "" : objectDef.getName()));
            }
            parameterIndex++;
        }

        List<StatementDef> statements = methodDef.getStatements();
        if (methodDef.isConstructor()) {
            statements = adjustConstructorStatements(objectDef, statements);
        }
        if (!statements.isEmpty()) {
            generatorAdapter.visitCode();
            if (startMethod != null) {
                generatorAdapter.visitLabel(startMethod);
            }
            for (StatementDef statement : statements) {
                StatementWriter.of(statement).write(generatorAdapter, context, null);
            }
            StatementDef statementDef = statements.getLast();
            if (!hasReturnStatement(statementDef)) {
                if (methodDef.getReturnType().equals(TypeDef.VOID)) {
                    generatorAdapter.returnValue();
                } else {
                    throw new IllegalStateException("The method: " + (objectDef == null ? "" : objectDef.getName()) + " " + methodDef.getName() + " doesn't return the result!");
                }
            }
        }
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
        if (visitMaxs && !statements.isEmpty()) {
            generatorAdapter.visitMaxs(20, 20);
        }
        generatorAdapter.visitEnd();

        for (MethodDef lambdaDef: context.lambdaMethods()) {
            writeMethod(classVisitor, objectDef, lambdaDef, true);
        }
    }

    private List<StatementDef> adjustConstructorStatements(ObjectDef objectDef, List<StatementDef> statements) {
        if (!(objectDef instanceof ClassDef classDef)) {
            return statements;
        }
        List<StatementDef> fieldInitializers = classDef.getFields().stream().filter(fieldDef -> !fieldDef.getModifiers().contains(Modifier.STATIC))
            .flatMap(fieldDef -> fieldDef.getInitializer().<StatementDef>map(initializer -> new VariableDef.This().field(fieldDef).assign(initializer)).stream())
            .toList();
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

}
