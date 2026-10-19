/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.javapoet.TypeSpec;
import io.micronaut.sourcegen.model.ObjectDef;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;

/**
 * The Java source generator. Each file is written by renderers of its own, with the context of writing it: the
 * generator keeps no state.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
@SuppressWarnings("java:S6201")
public sealed class JavaPoetSourceGenerator implements SourceGenerator permits GroovyPoetSourceGenerator {

    @Override
    public void write(ObjectDef objectDef, VisitorContext context, Element... originatingElements) {
        // As the default does, with the context passed on: it tells a nested class from a generated top-level one
        // by name, and looks up the supertypes of an override that are only known by name
        context.visitGeneratedSourceFile(objectDef.getPackageName(), objectDef.getSimpleName(), originatingElements)
            .ifPresent(generatedFile -> {
                try {
                    generatedFile.write(writer -> write(objectDef, writer, context));
                } catch (Exception e) {
                    Element element = originatingElements.length > 0 ? originatingElements[0] : null;
                    throw new ProcessingException(element, "Failed to generate '" + objectDef.getName() + "': " + e.getMessage(), e);
                }
            });
    }

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) throws IOException {
        write(objectDef, writer, null);
    }

    private void write(ObjectDef objectDef, Writer writer, @Nullable VisitorContext context) throws IOException {
        new JavaTypeRenderer(this, new JavaWriteContext(context, objectDef)).render(objectDef).writeTo(writer);
    }

    /**
     * Hook for subclasses to customize the type builder of a class, record, enum or interface
     * before it is built. Annotation definitions are not passed through this hook.
     *
     * @param objectDef   The object definition
     * @param typeBuilder The type builder
     */
    protected void customizeTypeBuilder(ObjectDef objectDef, TypeSpec.Builder typeBuilder) {
        // no-op by default
    }
}
