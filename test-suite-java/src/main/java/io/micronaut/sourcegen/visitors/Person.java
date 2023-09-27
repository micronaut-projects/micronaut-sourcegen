package io.micronaut.sourcegen.visitors;


import io.micronaut.sourcegen.ann.Builder;

@Builder
public record Person(Long id, String name) {
}
