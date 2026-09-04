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
package io.micronaut.sourcegen.example;

//tag::clazs[]
import io.micronaut.sourcegen.annotations.EqualsAndHashCode;
import io.micronaut.sourcegen.annotations.ToString;

@ToString
@EqualsAndHashCode
public class Elephant {
    @EqualsAndHashCode.Exclude
    public String name;
    public int age;
    private boolean hasSibling;

    @ToString.Exclude
    private int[][] values;

    public Elephant(String name, int age, boolean hasSibling, int value) {
        this.name = name;
        this.age = age;
        this.hasSibling = hasSibling;
        values = new int[3][3];
        for(int i=0; i<3; i++) {
            for(int j=0; j<3; j++) {
                values[i][j] = value;
            }
        }
    }

    public Elephant(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isHasSibling() {
        return hasSibling;
    }

    public int[][] getValues() {
        return values;
    }

    @Override
    public String toString() {
        return ElephantObject.toString(this);
    }

    @Override
    public boolean equals(Object o) {
        return ElephantObject.equals(this, o);
    }

    @Override
    public int hashCode() {
        return ElephantObject.hashCode(this);
    }
}
//tag::clazs[]
