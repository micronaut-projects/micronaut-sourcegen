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
package io.micronaut.sourcegen.example;

import io.micronaut.sourcegen.annotations.SuperBuilder;

//tag::clazz[]

@SuperBuilder
public class Dog extends Animal {

    private int barkLevel;
    private String bread;
    private boolean big;

    // Getters / Setters
    //end::clazz[]

    public int getBarkLevel() {
        return barkLevel;
    }

    public void setBarkLevel(int barkLevel) {
        this.barkLevel = barkLevel;
    }

    public String getBread() {
        return bread;
    }

    public void setBread(String bread) {
        this.bread = bread;
    }

    public boolean isBig() {
        return big;
    }

    public void setBig(boolean big) {
        this.big = big;
    }
    //tag::clazz[]
}
//end::clazz[]
