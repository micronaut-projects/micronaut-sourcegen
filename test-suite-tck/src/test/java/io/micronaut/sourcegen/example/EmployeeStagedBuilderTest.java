/*
 * Copyright 2003-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example;

import io.micronaut.core.beans.BeanIntrospection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmployeeStagedBuilderTest {

//tag::test[]
    @Test
    public void buildsEmployee() {
        var employee = EmployeeStagedBuilder.builder()
            .name("Billy Bounce")
            .age(33)
            .employed(false)
            .nickname("Billy")
            .build();
        assertEquals("Billy Bounce", employee.name());
        assertEquals(33, employee.age());
        assertEquals(false, employee.employed());
        assertEquals("Billy", employee.nickname());
    }
//end::test[]

    @Test
    public void buildsEmployeeWithoutTheOptionalProperties() {
        var employee = EmployeeStagedBuilder.builder()
            .name("Billy Bounce")
            .age(33)
            .employed(true)
            .build();
        assertEquals("Billy Bounce", employee.name());
        assertNull(employee.nickname());
    }

    @Test
    public void requiredPropertiesAreAssignedInStages() {
        // Every stage only exposes the property it assigns, so the build method
        // is not reachable until all of the required ones are assigned
        EmployeeStagedBuilder.NameBuildStage nameStage = EmployeeStagedBuilder.builder();
        EmployeeStagedBuilder.AgeBuildStage ageStage = nameStage.name("Billy Bounce");
        EmployeeStagedBuilder.EmployedBuildStage employedStage = ageStage.age(33);
        EmployeeStagedBuilder.BuildFinal buildFinal = employedStage.employed(true);

        assertEquals(List.of("name"), methodNames(EmployeeStagedBuilder.NameBuildStage.class));
        assertEquals(List.of("age"), methodNames(EmployeeStagedBuilder.AgeBuildStage.class));
        assertEquals(List.of("employed"), methodNames(EmployeeStagedBuilder.EmployedBuildStage.class));
        // The nullable property can be assigned in any order, on the final stage
        assertEquals(List.of("build", "nickname"), methodNames(EmployeeStagedBuilder.BuildFinal.class));

        assertEquals("Billy Bounce", buildFinal.build().name());
    }

    @Test
    public void theBuilderMethodReturnsTheFirstStage() throws NoSuchMethodException {
        assertEquals(
            EmployeeStagedBuilder.NameBuildStage.class,
            EmployeeStagedBuilder.class.getMethod("builder").getReturnType()
        );
        assertThrows(
            NoSuchMethodException.class,
            () -> EmployeeStagedBuilder.NameBuildStage.class.getMethod("nickname", String.class)
        );
    }

    @Test
    public void employeeIntrospection() {
        var introspection = BeanIntrospection.getIntrospection(EmployeeStagedBuilder.Builder.class);
        assertNotNull(introspection);
        assertEquals(0, introspection.getBeanProperties().size());
        assertEquals(4, introspection.getConstructorArguments().length);
    }

    private static List<String> methodNames(Class<?> stage) {
        return java.util.Arrays.stream(stage.getDeclaredMethods()).map(java.lang.reflect.Method::getName).sorted().toList();
    }
}
