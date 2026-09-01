package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.PropertyDef
import io.micronaut.sourcegen.model.RecordDef
import io.micronaut.sourcegen.model.TypeDef
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.util.CheckClassAdapter
import org.objectweb.asm.util.TraceClassVisitor

import javax.lang.model.element.Modifier

/**
 * The targets of an annotation type that is being compiled alongside the record, taken from a javac
 * {@link ClassElement}. Its class cannot be loaded, and Micronaut strips {@code Target} from the annotation
 * metadata of an element, so the compiler's own element is the only thing that can answer.
 */
class RecordComponentAnnotationSpec extends AbstractTypeElementSpec {

    void "a component annotation defined in the sources goes only where it is targeted"() {
        given:
        ClassElement holder = buildClassElement('''
package test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

class Holder {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface FieldOnly {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @interface TypeUseOnly {
    }
}
''')
        List<ClassElement> innerTypes = holder.getEnclosedElements(ElementQuery.ALL_INNER_CLASSES)
        ClassElement fieldOnly = innerTypes.find { it.name.endsWith('FieldOnly') }
        ClassElement typeUseOnly = innerTypes.find { it.name.endsWith('TypeUseOnly') }

        when:
        RecordDef recordDef = RecordDef.builder("test.MyRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(fieldOnly)).build())
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(typeUseOnly)).build())
                .build())
            .build()
        String bytecode = write(recordDef)

        then:
        // Targeting a field, it is written on the field and on nothing else
        bytecode.count('@Ltest/Holder$FieldOnly;()\n') == 1
        // Targeting a type use, it is written on the type of each of the four members
        bytecode.count('@Ltest/Holder$TypeUseOnly;() :') == 4
        bytecode.count('@Ltest/Holder$TypeUseOnly;()\n') == 0
    }

    private static String write(RecordDef recordDef) {
        StringWriter stringWriter = new StringWriter()
        def classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
        def tcv = new TraceClassVisitor(new CheckClassAdapter(classWriter), new PrintWriter(stringWriter))
        new ByteCodeWriter(false, false).writeObject(tcv, recordDef)
        tcv.visitEnd()
        return stringWriter.toString()
    }
}
