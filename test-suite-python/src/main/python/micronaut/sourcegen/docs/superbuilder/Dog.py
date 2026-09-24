from dataclasses import dataclass

from micronaut.sourcegen.annotations import SuperBuilder

from .Animal import Animal

# tag::clazz[]


@SuperBuilder
@dataclass
class Dog(Animal):
    barkLevel: int = 0
    bread: str | None = None
    big: bool = False
# end::clazz[]
