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


//tag::clazz[]
import io.micronaut.sourcegen.annotations.Utils;

import java.util.Arrays;

@Utils
public class Person4 {
    private long id;
    private String name;
    private byte[] bytes;

    public Person4(long id, String name, byte[] bytes) {
        this.id = id;
        this.name = name;
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public byte[] getBytes() {
        return bytes;
    }
}
//end::clazz[]
