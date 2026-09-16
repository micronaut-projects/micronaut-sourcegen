from micronaut.sourcegen.annotations import SuperBuilder

from .Animal import Animal

# tag::clazz[]


@SuperBuilder
class Cat(Animal):
    meowLevel: int = 0
    bread: str | None = None
# end::clazz[]
