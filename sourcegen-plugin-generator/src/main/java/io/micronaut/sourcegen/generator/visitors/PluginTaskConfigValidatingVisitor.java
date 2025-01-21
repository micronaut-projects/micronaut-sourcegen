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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.PluginTask;

import java.util.HashSet;
import java.util.Set;

/**
 * The visitor that validates a PluginTaskConfig annotated type.
 * It also creates a META-INF file with javadoc that will be used for plugin generation.
 *
 * @author Andriy Dmytruk
 * @since 1.6.x
 */
@Internal
public final class PluginTaskConfigValidatingVisitor implements TypeElementVisitor<PluginTask, Object> {

    private final Set<String> processed = new HashSet<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        processed.clear();
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(PluginTask.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (processed.contains(element.getName())) {
            return;
        }

        // Verify that method is present
        PluginUtils.getTaskExecutable(element);

        writeJavaDocForType(context, element);
    }

    private void writeJavaDocForType(VisitorContext context, ClassElement element) {
        writeJavaDocMetaInfFile(element, context);

        for (PropertyElement property: element.getBeanProperties()) {
            ClassElement propertyType = property.getType();
            if (processed.contains(propertyType.getName())) {
                continue;
            }
            if (!propertyType.isEnum() || !ModelUtils.isPOJO(propertyType)) {
                continue;
            }
            processed.add(propertyType.getName());
            writeJavaDocForType(context, propertyType);
        }
    }

    private void writeJavaDocMetaInfFile(ClassElement element, VisitorContext context) {
        context.info("Writing javadoc META-INF file for " + element.getName());
        String fileName = JavadocUtils.META_INF_FOLDER + element.getName() + JavadocUtils.META_INF_EXTENSION;
        context.visitMetaInfFile(fileName, element)
            .ifPresent(generatedFile -> {
                try {
                    generatedFile.write(writer ->
                        writer.write(JavadocUtils.writeJavadocInfo(element))
                    );
                } catch (Exception e) {
                    throw new ProcessingException(element, "Failed to generate '" + fileName + "': " + e.getMessage(), e);
                }
            });
    }

}
