package io.micronaut.sourcegen.example


import io.micronaut.sourcegen.annotations.Builder

@Builder
record Person(Long id, String name) {
}
