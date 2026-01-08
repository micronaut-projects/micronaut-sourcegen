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

import org.jspecify.annotations.Nullable;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.bytecode.ByteCodeWriter;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;

import java.io.OutputStream;
import java.io.Writer;
import java.util.LinkedList;

/**
 * Generates the classes directly by writing the bytecode.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
public final class ByteCodeGenerator implements SourceGenerator {

    private static final ByteCodeWriter BYTE_CODE_WRITER = new ByteCodeWriter(false, true);

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) {
        throw new IllegalStateException("ByteCode generator doesn't support writing using `java.io.Writer`");
    }

    @Override
    public void write(ObjectDef objectDef, VisitorContext context, Element... originatingElements) {
        LinkedList<InnerDef> innerTypes = new LinkedList<>();
        write(objectDef, null, context, innerTypes, originatingElements);
        while (!innerTypes.isEmpty()) {
            InnerDef innerType = innerTypes.removeFirst();
            write(innerType.inner, innerType.outer, context, innerTypes, originatingElements);
        }
    }

    private void write(ObjectDef objectDef,
                       @Nullable ClassTypeDef outerType,
                       VisitorContext context,
                       LinkedList<InnerDef> innerTypes,
                       Element[] originatingElements) {
        String className = objectDef.getName();
        try (OutputStream os = context.visitClass(className, originatingElements)) {
            os.write(BYTE_CODE_WRITER.write(objectDef, outerType));
            for (ObjectDef innerType : objectDef.getInnerTypes()) {
                innerTypes.add(new InnerDef(objectDef.asTypeDef(), innerType));
            }
        } catch (Exception e) {
            Element element = originatingElements.length > 0 ? originatingElements[0] : null;
            throw new ProcessingException(element, "Failed to generate '" + className + "': " + e.getMessage(), e);
        }
    }

    private record InnerDef(ClassTypeDef outer, ObjectDef inner) {
    }

}
