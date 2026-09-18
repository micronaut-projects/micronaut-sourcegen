/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.docs.stagedbuilder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
