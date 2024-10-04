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

//tag::clazs[]

@ToString
@EqualsAndHashCode
class Elephant { //tag::clazs[]
    @EqualsAndHashCode.Exclude
    var name: String
    var age: Int? = 0
    var isHasSibling: Boolean = false

    @ToString.Exclude
    var values: Array<IntArray>? = null

    constructor(name: String, age: Int?, hasSibling: Boolean, value: Int) {
        this.name = name
        this.age = age
        this.isHasSibling = hasSibling
        this.values = Array(3) { IntArray(3) }
        for (i in 0..2) {
            for (j in 0..2) {
                values!![i][j] = value
            }
        }
    }

    constructor(name: String) {
        this.name = name
    }

    override fun toString(): String {
        return ElephantObject.toString(this)
    }

    override fun equals(o: Any?): Boolean {
        return ElephantObject.equals(this, o)
    }

    override fun hashCode(): Int {
        return ElephantObject.hashCode(this)
    }


}
