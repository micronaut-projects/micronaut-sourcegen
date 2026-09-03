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
package io.micronaut.sourcegen.generator.bytecode;

import io.micronaut.inject.ast.Element;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.bytecode.ByteCodeWriter;
import io.micronaut.sourcegen.generator.AbstractByteCodeGenerator;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;

import org.jspecify.annotations.Nullable;

import java.io.OutputStream;

/**
 * Generates the classes directly by writing the bytecode.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public final class ByteCodeGenerator extends AbstractByteCodeGenerator {

    private static final ByteCodeWriter BYTE_CODE_WRITER = new ByteCodeWriter(false, true);

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    protected boolean writeClass(ObjectDef objectDef,
                                 @Nullable ClassTypeDef outerType,
                                 VisitorContext context,
                                 Element[] originatingElements) throws Exception {
        String className = objectDef.getName();
        try (OutputStream os = context.visitClass(className, originatingElements)) {
            os.write(BYTE_CODE_WRITER.write(objectDef, outerType));
        }
        return true;
    }

}
