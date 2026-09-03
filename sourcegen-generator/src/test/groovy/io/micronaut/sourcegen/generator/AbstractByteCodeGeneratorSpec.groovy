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
package io.micronaut.sourcegen.generator

import io.micronaut.inject.ast.Element
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ObjectDef
import spock.lang.Specification

class AbstractByteCodeGeneratorSpec extends Specification {

    private static ObjectDef nested() {
        // The model qualifies a member type with the name of the type it is added to
        ClassDef.builder("example.Outer")
                .addInnerType(ClassDef.builder("First")
                        .addInnerType(ClassDef.builder("Deep").build())
                        .build())
                .addInnerType(ClassDef.builder("Second").build())
                .build()
    }

    void "a bytecode generator cannot write to a Writer"() {
        given:
            def generator = new RecordingGenerator()

        when:
            generator.write(ClassDef.builder("example.Simple").build(), new StringWriter())

        then:
            def e = thrown(IllegalStateException)
            e.message.contains("java.io.Writer")
    }

    void "member types are visited after their enclosing type"() {
        given:
            def generator = new RecordingGenerator()

        when:
            generator.write(nested(), (VisitorContext) null)

        then:
            // Breadth first: both members of Outer come before the member of First
            generator.written.size() == 4
            generator.written[0..2] == ["example.Outer", "example.Outer\$First", "example.Outer\$Second"]
            generator.outers == [null, "example.Outer", "example.Outer", "example.Outer\$First"]
    }

    void "a backend that emitted the members itself stops the traversal"() {
        given:
            def generator = new RecordingGenerator(traverse: false)

        when:
            generator.write(nested(), (VisitorContext) null)

        then:
            generator.written == ["example.Outer"]
    }

    void "a failure names the class that could not be generated"() {
        given:
            def generator = new RecordingGenerator(failure: new IllegalStateException("no stack map"))

        when:
            generator.write(ClassDef.builder("example.Broken").build(), (VisitorContext) null)

        then:
            def e = thrown(ProcessingException)
            e.message.contains("example.Broken")
            e.message.contains("no stack map")
    }

    static class RecordingGenerator extends AbstractByteCodeGenerator {

        List<String> written = []
        List<String> outers = []
        boolean traverse = true
        RuntimeException failure = null

        @Override
        VisitorContext.Language getLanguage() {
            VisitorContext.Language.JAVA
        }

        @Override
        protected boolean writeClass(ObjectDef objectDef,
                                     ClassTypeDef outerType,
                                     VisitorContext context,
                                     Element[] originatingElements) {
            if (failure != null) {
                throw failure
            }
            written << objectDef.name
            outers << outerType?.name
            traverse
        }
    }
}
