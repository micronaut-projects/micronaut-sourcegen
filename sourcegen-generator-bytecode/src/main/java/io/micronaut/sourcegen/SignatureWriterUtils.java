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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

public class SignatureWriterUtils {

    static String getClassSignature(ClassDef classDef) {
        SignatureWriter writer = new SignatureWriter();

        for (TypeDef.TypeVariable typeVariable : classDef.getTypeVariables()) {
            writeSignature(writer, typeVariable, true);
            writer.visitEnd();
        }

        SignatureVisitor superclassVisitor = writer.visitSuperclass();
        superclassVisitor.visitClassType(Type.getType(Object.class).getInternalName());
        superclassVisitor.visitEnd();

        for (TypeDef superinterface : classDef.getSuperinterfaces()) {
            SignatureVisitor signatureVisitor = writer.visitInterface();
            writeSignature(signatureVisitor, superinterface, false);
            signatureVisitor.visitEnd();
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
            writeSignature(writer, typeVariable, true);
            writer.visitEnd();
        }

        SignatureVisitor superclassVisitor = writer.visitSuperclass();
        superclassVisitor.visitClassType(Type.getType(Object.class).getInternalName());
        superclassVisitor.visitEnd();

        for (TypeDef superinterface : interfaceDef.getSuperinterfaces()) {
            SignatureVisitor signatureVisitor = writer.visitInterface();
            writeSignature(signatureVisitor, superinterface, false);
            signatureVisitor.visitEnd();
        }

        return writer.toString();
    }

    @Nullable
    static String getMethodSignature(MethodDef methodDef) {
        if (!needsSignature(methodDef)) {
            return null;
        }
        SignatureWriter signatureWriter = new SignatureWriter();
        // TODO: method generic bounds
        for (ParameterDef parameter : methodDef.getParameters()) {
            SignatureVisitor visitParameterType = signatureWriter.visitParameterType();
            writeSignature(visitParameterType, parameter.getType(), false);
//            visitParameterType.visitEnd();
        }

        SignatureVisitor signatureVisitor = signatureWriter.visitReturnType();
        writeSignature(signatureVisitor, methodDef.getReturnType(), false);

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

    private static void writeSignature(SignatureVisitor signatureWriter, TypeDef typeDef, boolean isDefinition) {
        if (typeDef instanceof TypeDef.Primitive classDef) {
            signatureWriter.visitBaseType(TypeUtils.getType(classDef).getDescriptor().charAt(0));
            return;
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            if (isDefinition) {
                signatureWriter.visitFormalTypeParameter(typeVariable.name());
                if (typeVariable.bounds().isEmpty()) {
                    signatureWriter.visitClassType(Type.getType(Object.class).getInternalName());
                } else {
                    throw new IllegalStateException("TODO");
                }
            } else {
                signatureWriter.visitTypeVariable(typeVariable.name());
            }
            return;
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            signatureWriter.visitClassType(TypeUtils.getType(parameterized.rawType()).getInternalName());
            for (TypeDef typeArgument : parameterized.typeArguments()) {
                SignatureVisitor signatureVisitor = signatureWriter.visitTypeArgument(SignatureVisitor.INSTANCEOF);
                writeSignature(signatureWriter, typeArgument, false);
                signatureVisitor.visitEnd();
            }
            return;
        }
        if (typeDef instanceof ClassTypeDef classDef) {
            signatureWriter.visitClassType(TypeUtils.getType(classDef).getInternalName());
        }
    }

}
