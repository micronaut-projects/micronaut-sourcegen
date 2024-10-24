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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Nullable;
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

import java.util.Objects;

public class SignatureWriterUtils {

    @Nullable
    static String getFieldSignature(@Nullable ObjectDef objectDef, FieldDef fieldDef) {
        if (!needsSignature(fieldDef.getType())) {
            return null;
        }
        SignatureWriter writer = new SignatureWriter();
        writeSignature(writer, objectDef, fieldDef.getType(), true);
        return writer.toString();
    }

    @Nullable
    static String getClassSignature(ClassDef classDef) {
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : classDef.getTypeVariables()) {
            writeSignature(writer, null, typeVariable, true);
        }

        TypeDef superclass = Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT);
        writeSignature(writer.visitSuperclass(), null, superclass, false);

        for (TypeDef superinterface : classDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), null, superinterface, false);
        }

        return writer.toString();
    }

    @Nullable
    static String getRecordSignature(RecordDef recordDef) {
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : recordDef.getTypeVariables()) {
            writeSignature(writer, null, typeVariable, true);
        }

        writeSignature(writer.visitSuperclass(), null, TypeDef.of(Record.class), false);

        for (TypeDef superinterface : recordDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), null, superinterface, false);
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
            writeSignature(writer, null, typeVariable, true);
        }

        SignatureVisitor superclassVisitor = writer.visitSuperclass();
        superclassVisitor.visitClassType(Type.getType(Object.class).getInternalName());
        superclassVisitor.visitEnd();

        for (TypeDef superinterface : interfaceDef.getSuperinterfaces()) {
            writeSignature(writer.visitInterface(), null, superinterface, false);
        }

        return writer.toString();
    }

    @Nullable
    static String getMethodSignature(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (!needsSignature(methodDef)) {
            return null;
        }
        SignatureWriter signatureWriter = new SignatureWriter();
        // TODO: method generic bounds
        for (ParameterDef parameter : methodDef.getParameters()) {
            writeSignature(signatureWriter.visitParameterType(), objectDef, parameter.getType(), false);
        }

        writeSignature(signatureWriter.visitReturnType(), objectDef, methodDef.getReturnType(), false);

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
        return typeDef instanceof ClassTypeDef.Parameterized || typeDef instanceof TypeDef.TypeVariable;
    }

    private static void writeSignature(SignatureVisitor signatureWriter, @Nullable ObjectDef objectDef, TypeDef typeDef, boolean isDefinition) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        if (typeDef instanceof TypeDef.Primitive primitive) {
            Type type = Type.getType(JavaModelUtils.NAME_TO_TYPE_MAP.get(primitive.name()));
            signatureWriter.visitBaseType(type.getDescriptor().charAt(0));
            return;
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            if (isDefinition) {
                signatureWriter.visitFormalTypeParameter(typeVariable.name());
                if (typeVariable.bounds().isEmpty()) {
                    signatureWriter.visitClassType(Type.getType(Object.class).getInternalName());
                    signatureWriter.visitEnd();
                } else {
                    TypeDef bound = typeVariable.bounds().get(0);
                    signatureWriter.visitClassBound();
                    writeSignature(signatureWriter, objectDef, bound, false);
                }
            } else {
                signatureWriter.visitTypeVariable(typeVariable.name());
            }
            return;
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            signatureWriter.visitClassType(TypeUtils.getType(parameterized.rawType()).getInternalName());
            if (!parameterized.typeArguments().isEmpty()) {
                for (TypeDef typeArgument : parameterized.typeArguments()) {
                    SignatureVisitor signatureVisitor = signatureWriter.visitTypeArgument(SignatureVisitor.INSTANCEOF);
                    writeSignature(signatureVisitor, objectDef, typeArgument, false);
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
        throw new IllegalStateException("Not recognized typedef: " + typeDef);
    }

}
