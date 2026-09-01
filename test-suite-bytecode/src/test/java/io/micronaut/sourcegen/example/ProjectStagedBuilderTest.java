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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectStagedBuilderTest {

    @Test
    public void buildsProjectWithTheDefaultsOfTheFinalStage() {
        var project = ProjectStagedBuilder.builder()
            .title("Micronaut SourceGen")
            .build();
        assertEquals("Micronaut SourceGen", project.title());
        assertEquals(10, project.priority());
        assertEquals(List.of(), project.tasks());
    }

    @Test
    public void buildsProjectAssigningTheFinalStageInAnyOrder() {
        var project = ProjectStagedBuilder.builder()
            .title("Micronaut SourceGen")
            .task("Write the code")
            .priority(1)
            .task("Write the tests")
            .build();
        assertEquals(1, project.priority());
        assertEquals(List.of("Write the code", "Write the tests"), project.tasks());
    }
}
