package io.micronaut.sourcegen.example;

import io.micronaut.sourcegen.custom.example.GenerateMyRepository2;

@GenerateMyRepository2
public interface NumberRepository<N extends Number, T> {

    N value(T number);

}
