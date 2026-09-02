package io.micronaut.sourcegen.example

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EmployeeStagedBuilderTest {

//tag::test[]
    @Test
    fun buildsEmployee() {
        val employee: Employee = EmployeeStagedBuilder.builder()
            .name("Billy Bounce")
            .age(33)
            .employed(false)
            .nickname("Billy")
            .build()
        assertEquals("Billy Bounce", employee.name)
        assertEquals(33, employee.age)
        assertEquals(false, employee.employed)
        assertEquals("Billy", employee.nickname)
    }
//end::test[]

    @Test
    fun buildsEmployeeWithoutTheOptionalProperties() {
        val nameStage: EmployeeStagedBuilder.NameBuildStage = EmployeeStagedBuilder.builder()
        val ageStage: EmployeeStagedBuilder.AgeBuildStage = nameStage.name("Billy Bounce")
        val employedStage: EmployeeStagedBuilder.EmployedBuildStage = ageStage.age(33)
        val buildFinal: EmployeeStagedBuilder.BuildFinal = employedStage.employed(true)

        val employee: Employee = buildFinal.build()
        assertEquals("Billy Bounce", employee.name)
        assertNull(employee.nickname)
    }
}
