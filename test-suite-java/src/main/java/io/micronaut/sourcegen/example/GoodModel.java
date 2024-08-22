package io.micronaut.sourcegen.example;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.annotations.Wither;

@Wither
@Builder
@MappedEntity
public record GoodModel(
    @Id
    long id,
    String data) implements GoodModelWither {
}
