package io.micronaut.sourcegen.bytecode;

import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ObjectDef;

import java.io.OutputStream;
import java.io.Writer;

public class TestJavaBytecodeGenerator implements SourceGenerator {

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.JAVA;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void write(ObjectDef objectDef, VisitorContext context, Element... originatingElements) {
        context.visitGeneratedFile(objectDef.getName().replace(".", "/") + ".class", originatingElements)
            .ifPresent(generatedFile -> {
                try (OutputStream os = generatedFile.openOutputStream()) {
                    os.write(new ByteCodeWriter().write(objectDef));
                } catch (Exception e) {
                    Element element = originatingElements.length > 0 ? originatingElements[0] : null;
                    throw new ProcessingException(element, "Failed to generate '" + objectDef.getName() + "': " + e.getMessage(), e);
                }
            });
    }
}
