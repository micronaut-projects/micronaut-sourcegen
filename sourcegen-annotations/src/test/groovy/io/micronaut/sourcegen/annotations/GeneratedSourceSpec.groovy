package io.micronaut.sourcegen.annotations

import spock.lang.Specification

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

class GeneratedSourceSpec extends Specification {

    void 'the marker is retained in compiled class files'() {
        expect:
        GeneratedSource.getAnnotation(Retention).value() == RetentionPolicy.CLASS
    }

    void 'the marker applies to types'() {
        expect:
        GeneratedSource.getAnnotation(Target).value().toList() == [ElementType.TYPE]
        !GeneratedType.isAnnotationPresent(GeneratedSource)
    }

    void 'the marker is documented'() {
        expect:
        GeneratedSource.isAnnotationPresent(Documented)
    }

    void 'the marker name is recognized by generated code coverage filters'() {
        expect:
        GeneratedSource.simpleName.contains('Generated')
    }

    @GeneratedSource
    private static class GeneratedType {
    }
}
