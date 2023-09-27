package io.micronaut.sourcegen.visitors

import io.micronaut.sourcegen.ann.Builder

@Builder
data class Person(val id: Long, val name: String)
