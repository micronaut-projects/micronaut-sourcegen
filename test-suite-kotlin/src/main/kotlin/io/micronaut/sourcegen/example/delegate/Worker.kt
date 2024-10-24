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

import io.micronaut.sourcegen.annotations.Delegate;

/**
 * A worker interface that delegate will be generated for.
 */
@Delegate
interface Worker<T>: SimpleWorker {

    override fun name(): String

    fun canComplete(tasks: List<T>): Boolean

    fun competencies(): List<String>

    fun currentTask(): T

}
