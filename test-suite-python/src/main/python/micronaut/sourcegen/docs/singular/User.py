# tag::clazz[]
from dataclasses import dataclass, field
from typing import Annotated

from micronaut.sourcegen.annotations import Singular, SuperBuilder


@SuperBuilder
@dataclass
class User:
    id: int | None = None
    name: str | None = None
    roles: Annotated[list[str], Singular] = field(default_factory=list)
    properties: Annotated[dict[str, object], Singular("property")] = field(default_factory=dict)
# end::clazz[]
