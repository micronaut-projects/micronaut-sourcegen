# tag::clazz[]
from dataclasses import dataclass

from micronaut.sourcegen.annotations import Builder


@Builder
@dataclass
class Person:
    id: int | None = None
    name: str | None = None
    data: bytes = b""
# end::clazz[]
