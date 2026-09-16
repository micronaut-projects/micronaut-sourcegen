# tag::clazz[]
from dataclasses import dataclass

from micronaut.sourcegen.annotations import StagedBuilder


@StagedBuilder
@dataclass
class Employee:
    name: str
    age: int
    employed: bool
    nickname: str | None = None
# end::clazz[]
