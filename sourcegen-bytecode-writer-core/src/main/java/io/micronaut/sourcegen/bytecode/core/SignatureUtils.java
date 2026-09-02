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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * JVM generic-signature operations shared by bytecode writers.
 *
 * <p>The result is the standard JVMS signature string. Keeping this operation independent of a
 * bytecode library lets the ASM and JDK ClassFile backends use exactly the same generic metadata.</p>
 *
 * @since 2.2
 */
@Internal
public final class SignatureUtils {

    private SignatureUtils() {
    }

    /**
     * @param objectDef The contextual object, if any
     * @param fieldDef The field
     * @return The field signature, or {@code null} when its descriptor is sufficient
     */
    @Nullable
    public static String getFieldSignature(@Nullable ObjectDef objectDef, FieldDef fieldDef) {
        if (!needsSignature(fieldDef.getType())) {
            return null;
        }
        StringBuilder signature = new StringBuilder();
        writeSignature(signature, objectDef, null, fieldDef.getType(), false);
        return signature.toString();
    }

    /**
     * @param classDef The class
     * @return The class signature
     */
    public static String getClassSignature(ClassDef classDef) {
        StringBuilder signature = new StringBuilder();
        writeTypeVariables(signature, classDef, null, classDef.getTypeVariables());
        closeTypeVariables(signature, classDef.getTypeVariables());
        writeSignature(signature, classDef, null,
            Objects.requireNonNullElse(classDef.getSuperclass(), TypeDef.OBJECT), false);
        for (TypeDef superinterface : classDef.getSuperinterfaces()) {
            writeSignature(signature, classDef, null, superinterface, false);
        }
        return signature.toString();
    }

    /**
     * @param recordDef The record
     * @return The record signature
     */
    public static String getRecordSignature(RecordDef recordDef) {
        StringBuilder signature = new StringBuilder();
        writeTypeVariables(signature, recordDef, null, recordDef.getTypeVariables());
        closeTypeVariables(signature, recordDef.getTypeVariables());
        writeSignature(signature, recordDef, null, TypeDef.of(Record.class), false);
        for (TypeDef superinterface : recordDef.getSuperinterfaces()) {
            writeSignature(signature, recordDef, null, superinterface, false);
        }
        return signature.toString();
    }

    /**
     * @param interfaceDef The interface
     * @return The interface signature, or {@code null} when no generic metadata is needed
     */
    @Nullable
    public static String getInterfaceSignature(InterfaceDef interfaceDef) {
        if (interfaceDef.getTypeVariables().isEmpty() && interfaceDef.getSuperinterfaces().isEmpty()) {
            return null;
        }
        StringBuilder signature = new StringBuilder();
        writeTypeVariables(signature, interfaceDef, null, interfaceDef.getTypeVariables());
        closeTypeVariables(signature, interfaceDef.getTypeVariables());
        appendClass(signature, Object.class.getName());
        for (TypeDef superinterface : interfaceDef.getSuperinterfaces()) {
            writeSignature(signature, interfaceDef, null, superinterface, false);
        }
        return signature.toString();
    }

    /**
     * @param objectDef The contextual object, if any
     * @param methodDef The method
     * @return The method signature, or {@code null} when its descriptor is sufficient
     */
    @Nullable
    public static String getMethodSignature(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        if (!needsSignature(methodDef)) {
            return null;
        }
        StringBuilder signature = new StringBuilder();
        writeTypeVariables(signature, objectDef, methodDef, methodDef.getTypeVariables());
        closeTypeVariables(signature, methodDef.getTypeVariables());
        signature.append('(');
        for (ParameterDef parameter : methodDef.getParameters()) {
            writeSignature(signature, objectDef, methodDef, parameter.getType(), false);
        }
        signature.append(')');
        writeSignature(signature, objectDef, methodDef, methodDef.getReturnType(), false);
        return signature.toString();
    }

    private static void writeTypeVariables(StringBuilder signature,
                                           @Nullable ObjectDef objectDef,
                                           @Nullable MethodDef methodDef,
                                           List<TypeDef.TypeVariable> variables) {
        if (!variables.isEmpty()) {
            signature.append('<');
        }
        for (TypeDef.TypeVariable variable : variables) {
            writeSignature(signature, objectDef, methodDef, variable, true);
        }
    }

    private static void closeTypeVariables(StringBuilder signature, List<TypeDef.TypeVariable> variables) {
        if (!variables.isEmpty()) {
            signature.append('>');
        }
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
        return typeDef instanceof ClassTypeDef.Parameterized || typeDef instanceof TypeDef.TypeVariable;
    }

    private static void writeSignature(StringBuilder signature,
                                       @Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       TypeDef typeDef,
                                       boolean definition) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        typeDef = unwrapAnnotated(typeDef);
        if (typeDef instanceof TypeDef.Primitive primitive) {
            signature.append(TypeUtils.getDescriptor(primitive, objectDef));
        } else if (typeDef instanceof TypeDef.TypeVariable variable) {
            writeTypeVariable(signature, objectDef, methodDef, variable, definition);
        } else if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            appendClassStart(signature, parameterized.rawType().getName());
            if (!parameterized.typeArguments().isEmpty()) {
                signature.append('<');
                for (TypeDef argument : parameterized.typeArguments()) {
                    // The ASM backend historically emits unbounded type arguments here. Preserve
                    // that representation so both backends have identical signatures.
                    writeSignature(signature, objectDef, methodDef, argument, false);
                }
                signature.append('>');
            }
            signature.append(';');
        } else if (typeDef instanceof ClassTypeDef classDef) {
            appendClass(signature, classDef.getName());
        } else if (typeDef instanceof TypeDef.Wildcard) {
            appendClass(signature, Object.class.getName());
        } else if (typeDef instanceof TypeDef.Array array) {
            signature.append("[".repeat(array.dimensions()));
            writeSignature(signature, objectDef, methodDef, array.componentType(), false);
        } else {
            throw new IllegalStateException("Not recognized typedef: " + typeDef);
        }
    }

    private static void writeTypeVariable(StringBuilder signature,
                                           @Nullable ObjectDef objectDef,
                                           @Nullable MethodDef methodDef,
                                           TypeDef.TypeVariable variable,
                                           boolean definition) {
        String name = variable.name();
        if (definition) {
            signature.append(name).append(':');
            if (variable.bounds().isEmpty()) {
                appendClass(signature, Object.class.getName());
            } else {
                writeSignature(signature, objectDef, methodDef, variable.bounds().get(0), false);
            }
            return;
        }
        if (isVariablePartOfDefinition(name, objectDef, methodDef)) {
            signature.append('T').append(name).append(';');
        } else if (variable.bounds().isEmpty()) {
            appendClass(signature, Object.class.getName());
        } else {
            appendClass(signature, TypeUtils.getInternalName(TypeUtils.getDescriptor(
                variable.bounds().get(0), objectDef).substring(1,
                TypeUtils.getDescriptor(variable.bounds().get(0), objectDef).length() - 1)));
        }
    }

    private static void appendClassStart(StringBuilder signature, String name) {
        signature.append('L').append(TypeUtils.getInternalName(name));
    }

    private static void appendClass(StringBuilder signature, String name) {
        appendClassStart(signature, name);
        signature.append(';');
    }

    private static boolean isVariablePartOfDefinition(String name,
                                                      @Nullable ObjectDef objectDef,
                                                      @Nullable MethodDef methodDef) {
        if (methodDef != null && methodDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(name))) {
            return true;
        }
        if (objectDef instanceof ClassDef classDef) {
            return classDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(name));
        }
        if (objectDef instanceof InterfaceDef interfaceDef) {
            return interfaceDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(name));
        }
        if (objectDef instanceof RecordDef recordDef) {
            return recordDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(name));
        }
        return false;
    }

    private static TypeDef unwrapAnnotated(TypeDef typeDef) {
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return unwrapAnnotated(annotated.typeDef());
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return unwrapAnnotated(annotated.typeDef());
        }
        return typeDef;
    }
}
