package io.micronaut.sourcegen.example

import java.lang.annotation.ElementType
import java.lang.annotation.Target

// Test verifies that annotation is generated correctly by using it
@MyAnnotation(
        string = "this",
        primitive = 3,
        floats = [1.0f, 2.0f],
        inner = @Target(ElementType.TYPE)
)
class MyAnnotationSpec {
}
