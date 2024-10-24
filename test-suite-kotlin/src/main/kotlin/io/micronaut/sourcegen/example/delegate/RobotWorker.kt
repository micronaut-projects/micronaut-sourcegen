/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.example.delegate;

/**
 * An implementation of worker interface
 */
class RobotWorker<T> (
    var name: String,
    var tasksPerDay: Double,
    var competencies: List<String>,
    var currentTask: T
) : Worker<T> {

    override fun name(): String {
        return name
    }

    override fun competencies(): List<String> {
        return competencies
    }

    override fun tasksPerDay(): Double {
        return tasksPerDay
    }

    override fun canComplete(tasks: List<T>): Boolean {
        return tasks.size <= tasksPerDay()
    }

    override fun currentTask(): T {
        return currentTask
    }

}
