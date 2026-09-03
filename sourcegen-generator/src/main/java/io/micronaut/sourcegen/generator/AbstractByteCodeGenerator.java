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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;
import org.jspecify.annotations.Nullable;

import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Common class-output and member-type traversal for bytecode generators.
 *
 * <p>A backend returns whether it emitted the definition itself. A source fallback can return
 * {@code false} when its source already contains member types; this prevents those types from being
 * emitted a second time by the traversal.</p>
 *
 * @since 2.2
 */
@Internal
public abstract class AbstractByteCodeGenerator implements SourceGenerator {

    @Override
    public final void write(ObjectDef objectDef, Writer writer) {
        throw new IllegalStateException("ByteCode generator doesn't support writing using `java.io.Writer`");
    }

    @Override
    public final void write(ObjectDef objectDef, VisitorContext context, Element... originatingElements) {
        Deque<InnerDef> innerTypes = new ArrayDeque<>();
        write(objectDef, null, context, innerTypes, originatingElements);
        while (!innerTypes.isEmpty()) {
            InnerDef innerType = innerTypes.removeFirst();
            write(innerType.inner(), innerType.outer(), context, innerTypes, originatingElements);
        }
    }

    private void write(ObjectDef objectDef,
                       @Nullable ClassTypeDef outerType,
                       VisitorContext context,
                       Deque<InnerDef> innerTypes,
                       Element[] originatingElements) {
        try {
            if (writeClass(objectDef, outerType, context, originatingElements)) {
                for (ObjectDef innerType : objectDef.getInnerTypes()) {
                    innerTypes.add(new InnerDef(objectDef.asTypeDef(), innerType));
                }
            }
        } catch (Exception e) {
            Element element = originatingElements.length > 0 ? originatingElements[0] : null;
            throw new ProcessingException(element,
                "Failed to generate '" + objectDef.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Emit one definition.
     *
     * @param objectDef The definition
     * @param outerType The enclosing type, or {@code null}
     * @param context The visitor context
     * @param originatingElements Originating elements
     * @return {@code true} when member definitions should be traversed
     * @throws Exception If emission fails
     */
    protected abstract boolean writeClass(ObjectDef objectDef,
                                          @Nullable ClassTypeDef outerType,
                                          VisitorContext context,
                                          Element[] originatingElements) throws Exception;

    private record InnerDef(ClassTypeDef outer, ObjectDef inner) {
    }
}
