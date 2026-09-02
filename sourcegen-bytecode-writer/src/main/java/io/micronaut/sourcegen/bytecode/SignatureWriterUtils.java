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

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.util.List;
import java.util.Objects;

/**
 * The bytecode signature utils.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
final class SignatureWriterUtils {

    @Nullable
    static String getFieldSignature(@Nullable ObjectDef objectDef, FieldDef fieldDef) {
        if (!needsSignature(fieldDef.getType())) {
            return null;
        }
        SignatureWriter writer = new SignatureWriter();
        // A field signature references its type, it never defines a type parameter
        writeSignature(writer, objectDef, fieldDef.getType(), false);
        return writer.toString();
    }

    @Nullable
    static String getClassSignature(ClassDef classDef) {
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : classDef.getTypeVariables()) {
            writeSignature(writer, classDef, typeVariable, true);
        }

        TypeDef superclass = Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT);
        writeSignature(writer.visitSuperclass(), classDef, superclass, false);

        for (TypeDef superinterface : classDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), classDef, superinterface, false);
        }

        return writer.toString();
    }

    @Nullable
    static String getRecordSignature(RecordDef recordDef) {
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : recordDef.getTypeVariables()) {
            writeSignature(writer, recordDef, typeVariable, true);
        }

        writeSignature(writer.visitSuperclass(), recordDef, TypeDef.of(Record.class), false);

        for (TypeDef superinterface : recordDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), recordDef, superinterface, false);
        }

        return writer.toString();
    }

    @Nullable
    static String getInterfaceSignature(InterfaceDef interfaceDef) {
        if (interfaceDef.getTypeVariables().isEmpty() && interfaceDef.getSuperinterfaces().isEmpty()) {
            return null;
        }
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : interfaceDef.getTypeVariables()) {
            writeSignature(writer, interfaceDef, typeVariable, true);
        }

        SignatureVisitor superclassVisitor = writer.visitSuperclass();
        superclassVisitor.visitClassType(TypeUtils.OBJECT_TYPE.getInternalName());
        superclassVisitor.visitEnd();

        for (TypeDef superinterface : interfaceDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), interfaceDef, superinterface, false);
        }

        return writer.toString();
    }

    @Nullable
    static String getMethodSignature(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (!needsSignature(methodDef)) {
            return null;
        }
        SignatureWriter signatureWriter = new SignatureWriter();
        for (TypeDef.TypeVariable typeVariable : methodDef.getTypeVariables()) {
            writeSignature(signatureWriter, objectDef, methodDef, typeVariable, true);
        }
        for (ParameterDef parameter : methodDef.getParameters()) {
            writeSignature(signatureWriter.visitParameterType(), objectDef, methodDef, parameter.getType(), false);
        }

        writeSignature(signatureWriter.visitReturnType(), objectDef, methodDef, methodDef.getReturnType(), false);

        return signatureWriter.toString();
    }

    private static boolean needsSignature(MethodDef methodDef) {
        for (ParameterDef parameter : methodDef.getParameters()) {
            if (needsSignature(parameter.getType())) {
                return true;
            }
        }
        return needsSignature(methodDef.getReturnType());
    }

    private static boolean needsSignature(TypeDef typeDef) {
        typeDef = unwrapAnnotated(typeDef);
        if (typeDef instanceof TypeDef.Array array) {
            return needsSignature(array.componentType());
        }
        return typeDef instanceof ClassTypeDef.Parameterized
            || typeDef instanceof TypeDef.TypeVariable
            || typeDef instanceof TypeDef.Wildcard;
    }

    /**
     * @param typeDef The type
     * @return The type without the annotations it carries, which have no place in a signature
     */
    private static TypeDef unwrapAnnotated(TypeDef typeDef) {
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return unwrapAnnotated(annotated.typeDef());
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return unwrapAnnotated(annotated.typeDef());
        }
        return typeDef;
    }

    private static void writeSignature(SignatureVisitor signatureWriter,
                                       @Nullable ObjectDef objectDef,
                                       TypeDef typeDef, boolean isDefinition) {
        writeSignature(signatureWriter, objectDef, null, typeDef, isDefinition);
    }

    private static void writeSignature(SignatureVisitor signatureWriter,
                                       @Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       TypeDef typeDef, boolean isDefinition) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        typeDef = unwrapAnnotated(typeDef);
        if (typeDef instanceof TypeDef.Primitive primitive) {
            Type type = Type.getType(JavaModelUtils.NAME_TO_TYPE_MAP.get(primitive.name()));
            signatureWriter.visitBaseType(type.getDescriptor().charAt(0));
            return;
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            String name = typeVariable.name();
            if (isDefinition) {
                signatureWriter.visitFormalTypeParameter(name);
                if (typeVariable.bounds().isEmpty()) {
                    writeSignature(signatureWriter.visitClassBound(), objectDef, TypeDef.OBJECT, false);
                } else {
                    boolean first = true;
                    for (TypeDef bound : typeVariable.bounds()) {
                        SignatureVisitor boundVisitor = first && !isInterface(bound)
                            ? signatureWriter.visitClassBound()
                            : signatureWriter.visitInterfaceBound();
                        writeSignature(boundVisitor, objectDef, methodDef, bound, false);
                        first = false;
                    }
                }
            } else {
                if (isVariablePartOfTheDefinition(name, objectDef, methodDef)) {
                    signatureWriter.visitTypeVariable(typeVariable.name());
                } else if (typeVariable.bounds().isEmpty()) {
                    signatureWriter.visitClassType(TypeUtils.OBJECT_TYPE.getInternalName());
                    signatureWriter.visitEnd();
                } else {
                    signatureWriter.visitClassType(TypeUtils.getType(typeVariable.bounds().get(0), objectDef).getInternalName());
                    signatureWriter.visitEnd();
                }
            }
            return;
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            signatureWriter.visitClassType(TypeUtils.getType(parameterized.rawType()).getInternalName());
            if (!parameterized.typeArguments().isEmpty()) {
                for (TypeDef typeArgument : parameterized.typeArguments()) {
                    writeTypeArgument(signatureWriter, objectDef, methodDef, typeArgument);
                }
                signatureWriter.visitEnd();
            }
            return;
        }
        if (typeDef instanceof ClassTypeDef classDef) {
            signatureWriter.visitClassType(TypeUtils.getType(classDef.getName()).getInternalName());
            signatureWriter.visitEnd();
            return;
        }
        if (typeDef instanceof TypeDef.Wildcard) {
            signatureWriter.visitClassType(TypeUtils.OBJECT_TYPE.getInternalName());
            signatureWriter.visitEnd();
            return;
        }
        if (typeDef instanceof TypeDef.Array array) {
            if (array.dimensions() == 1) {
                writeSignature(signatureWriter.visitArrayType(), objectDef, methodDef, array.componentType(), false);
            } else {
                writeSignature(signatureWriter.visitArrayType(), objectDef, methodDef, new TypeDef.Array(
                    array.componentType(),
                    array.dimensions() - 1,
                    false
                ), false);
            }
            return;
        }
        throw new IllegalStateException("Not recognized typedef: " + typeDef);
    }

    private static void writeTypeArgument(SignatureVisitor signatureWriter,
                                          @Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef,
                                          TypeDef typeArgument) {
        TypeDef unwrapped = unwrapAnnotated(typeArgument);
        if (!(unwrapped instanceof TypeDef.Wildcard wildcard)) {
            writeSignature(signatureWriter.visitTypeArgument(SignatureVisitor.INSTANCEOF), objectDef, methodDef, typeArgument, false);
            return;
        }
        if (!wildcard.lowerBounds().isEmpty()) {
            writeSignature(
                signatureWriter.visitTypeArgument(SignatureVisitor.SUPER),
                objectDef,
                methodDef,
                wildcard.lowerBounds().get(0),
                false
            );
            return;
        }
        if (wildcard.upperBounds().isEmpty() || wildcard.upperBounds().equals(List.of(TypeDef.OBJECT))) {
            signatureWriter.visitTypeArgument();
            return;
        }
        writeSignature(
            signatureWriter.visitTypeArgument(SignatureVisitor.EXTENDS),
            objectDef,
            methodDef,
            wildcard.upperBounds().get(0),
            false
        );
    }

    /**
     * Whether a bound is an interface, which decides the position it is written in: the first bound goes
     * into the class bound only when it is a class. A {@link ClassTypeDef.ClassName} is only a name and
     * carries no answer, so the class it names is the last place left to ask.
     *
     * @param typeDef The bound
     * @return True when the bound is known to be an interface
     */
    private static boolean isInterface(TypeDef typeDef) {
        typeDef = unwrapAnnotated(typeDef);
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            typeDef = parameterized.rawType();
        }
        if (!(typeDef instanceof ClassTypeDef classTypeDef)) {
            return false;
        }
        if (classTypeDef.isInterface()) {
            return true;
        }
        if (classTypeDef instanceof ClassTypeDef.ClassName) {
            try {
                return Class.forName(classTypeDef.getName(), false, SignatureWriterUtils.class.getClassLoader())
                    .isInterface();
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isVariablePartOfTheDefinition(String variableName, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        if (methodDef != null
            && methodDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(variableName))) {
            return true;
        }
        if (objectDef instanceof ClassDef classDef) {
            return classDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
        }
        if (objectDef instanceof InterfaceDef interfaceDef) {
            return interfaceDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
        }
        if (objectDef instanceof RecordDef recordDef) {
            return recordDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
        }
        return false;
    }

}
