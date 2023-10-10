package io.micronaut.sourcegen.visitors


import io.micronaut.sourcegen.annotations.Builder

@Builder
record Person(Long id, String name) {
}
