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
package io.micronaut.sourcegen.python

import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.python.annotation.processing.test.AbstractPythonTypeElementSpec
import io.micronaut.sourcegen.PythonPoetSourceGenerator
import io.micronaut.sourcegen.generator.SourceGenerators

/**
 * The classes compiled by the Python compiler (micronaut-inject-python) are visited with a context whose language
 * is {@link VisitorContext.Language#PYTHON}: the annotation visitors write Java sources for them, which the
 * compiler compiles together with the stubs of the Python classes.
 */
class PythonSourceGeneratorSpec extends AbstractPythonTypeElementSpec {

    void "the Java source generator is registered for the Python language"() {
        expect:
        SourceGenerators.findByLanguage(VisitorContext.Language.PYTHON).get() instanceof PythonPoetSourceGenerator
    }

    void "a builder is generated for a Python dataclass"() {
        given:
        def context = buildContext('''
from dataclasses import dataclass

from micronaut.sourcegen.annotations import Builder


@Builder
@dataclass
class Person:
    id: int | None = None
    name: str | None = None
''')

        when:
        def builderClass = context.classLoader.loadClass("python.PersonBuilder")
        def person = builderClass.builder()
                .id(123)
                .name("Cédric")
                .build()

        then:
        person.class.name == "python.Person"
        person.id == 123
        person.name == "Cédric"

        cleanup:
        context?.close()
    }

    void "an object is generated for a Python dataclass"() {
        given:
        def context = buildContext('''
from dataclasses import dataclass

from micronaut.sourcegen.annotations import Builder, EqualsAndHashCode, ToString


@Builder
@ToString
@EqualsAndHashCode
@dataclass
class Point:
    x: int = 0
    y: int = 0
''')

        when:
        def builderClass = context.classLoader.loadClass("python.PointBuilder")
        def objectClass = context.classLoader.loadClass("python.PointObject")
        def point = builderClass.builder().x(1).y(2).build()
        def same = builderClass.builder().x(1).y(2).build()

        then:
        objectClass.toString(point) == "Point[x=1, y=2]"
        objectClass.equals(point, same)
        objectClass.hashCode(point) == objectClass.hashCode(same)

        cleanup:
        context?.close()
    }
}
