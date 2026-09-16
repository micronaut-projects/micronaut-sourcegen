from micronaut.sourcegen.annotations import SuperBuilder

# tag::clazz[]


@SuperBuilder
class Animal:
    name: str | None = None
    age: int = 0
    color: str | None = None
# end::clazz[]
