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
package io.micronaut.sourcegen.docs.delegate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelegateWorkerTest {

    @Test
    fun test() {
        // tag::test[]
        val worker = OvertimeWorker(RobotWorker(
            "robot",
            10.0,
            listOf("does everything"),
            "wash flowers",
            emptySet<Any>()
        ))

        assertEquals("robot", worker.name())
        assertEquals(12.0, worker.tasksPerDay()) // increased because of delegate
        assertEquals(listOf("does everything"), worker.competencies())
        assertEquals("wash flowers", worker.currentTask())
        // end::test[]
    }
}
