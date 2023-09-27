package io.micronaut.sourcegen.visitors
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PersonBuilderTest : StringSpec({
    "should create a person" {
        val person = Person::class.builder()
            .id(123)
            .name("Cédric")
            .build()
        person.id shouldBe 123
        person.name shouldBe "Cédric"
    }

})
