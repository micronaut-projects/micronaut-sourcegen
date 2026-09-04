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

import io.micronaut.core.annotation.Vetoed;
import io.micronaut.sourcegen.annotations.SuperBuilder;

//tag::clazz[]

@SuperBuilder
public class Cat extends Animal {

    private int meowLevel;
    private String bread;

    // Getters / Setters
    //end::clazz[]

    public int getMeowLevel() {
        return meowLevel;
    }

    public void setMeowLevel(int meowLevel) {
        this.meowLevel = meowLevel;
    }

    public String getBread() {
        return bread;
    }

    public void setBread(String bread) {
        this.bread = bread;
    }
    //tag::clazz[]

    @Vetoed // vetoed is currently required.
    public static CatSuperBuilder builder() {
        return new CatSuperBuilder();
    }
}
//end::clazz[]
