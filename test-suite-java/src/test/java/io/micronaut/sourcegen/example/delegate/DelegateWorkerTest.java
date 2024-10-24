/*
 * Copyright 2003-2021 the original author or authors.
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
package io.micronaut.sourcegen.example.delegate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelegateWorkerTest {

    @Test
    void test() {
        // tag::test[]
        OvertimeWorker<String> worker = new OvertimeWorker<>(new RobotWorker<String>(
            "robot",
            10,
            List.of("does everything"),
            "wash flowers",
            Set.of()
        ));

        assertEquals("robot", worker.name());
        assertEquals(12, worker.tasksPerDay()); // increased because of delegate
        assertEquals(List.of("does everything"), worker.competencies());
        assertEquals("wash flowers", worker.currentTask());
        // end::test[]
    }

}
