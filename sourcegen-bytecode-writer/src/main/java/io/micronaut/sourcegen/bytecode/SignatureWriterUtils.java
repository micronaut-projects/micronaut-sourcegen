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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import io.micronaut.sourcegen.bytecode.core.SignatureUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import org.jspecify.annotations.Nullable;

/**
 * Compatibility facade for the backend-neutral signature utilities.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
final class SignatureWriterUtils {

    private SignatureWriterUtils() {
    }

    @Nullable
    static String getFieldSignature(@Nullable ObjectDef objectDef, FieldDef fieldDef) {
        return getFieldSignature(objectDef, fieldDef, EnclosingScope.NONE);
    }

    static String getClassSignature(ClassDef classDef) {
        return getClassSignature(classDef, EnclosingScope.NONE);
    }

    @Nullable
    static String getInterfaceSignature(InterfaceDef interfaceDef) {
        return getInterfaceSignature(interfaceDef, EnclosingScope.NONE);
    }

    @Nullable
    static String getMethodSignature(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return getMethodSignature(objectDef, methodDef, EnclosingScope.NONE);
    }

    @Nullable
    static String getFieldSignature(@Nullable ObjectDef objectDef, FieldDef fieldDef, EnclosingScope enclosingScope) {
        return SignatureUtils.getFieldSignature(objectDef, fieldDef, enclosingScope);
    }

    static String getClassSignature(ClassDef classDef, EnclosingScope enclosingScope) {
        return SignatureUtils.getClassSignature(classDef, enclosingScope);
    }

    static String getRecordSignature(RecordDef recordDef, EnclosingScope enclosingScope) {
        return SignatureUtils.getRecordSignature(recordDef, enclosingScope);
    }

    @Nullable
    static String getInterfaceSignature(InterfaceDef interfaceDef, EnclosingScope enclosingScope) {
        return SignatureUtils.getInterfaceSignature(interfaceDef, enclosingScope);
    }

    @Nullable
    static String getMethodSignature(@Nullable ObjectDef objectDef, MethodDef methodDef, EnclosingScope enclosingScope) {
        return SignatureUtils.getMethodSignature(objectDef, methodDef, enclosingScope);
    }
}
