/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.sourcegen.example

import io.micronaut.sourcegen.annotations.EqualsAndHashCode
import io.micronaut.sourcegen.annotations.ToString

//tag::clazz[]

@ToString
@EqualsAndHashCode
class Person4(val id: Long, val title: Title, val name: String?, val bytes: ByteArray?) { //end::clazz[]
    enum class Title {
        MRS,
        MR,
        MS
    }

    override fun toString(): String {
        return Person4Object.toString(this)
    }

    override fun equals(o: Any?): Boolean {
        return Person4Object.equals(this, o)
    }

    override fun hashCode(): Int {
        return Person4Object.hashCode(this)
    }

}
