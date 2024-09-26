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
import io.micronaut.sourcegen.annotations.EqualsAndHashCode;
import io.micronaut.sourcegen.annotations.ToString;

import java.util.Arrays;

@ToString
@EqualsAndHashCode
public class Person4 {
    public enum Title {
        MRS,
        MR,
        MS
    }

    private long id;
    private Title title;
    private String name;
    private byte[] bytes;

    public Person4(long id, Title title, String name, byte[] bytes) {
        this.id = id;
        this.title = title;
        this.name = name;
        this.bytes = (bytes != null) ? Arrays.copyOf(bytes, bytes.length) : null;
    }

    public long getId() {
        return id;
    }

    public Title getTitle() {
        return title;
    }

    public String getName() {
        return name;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return Person4Object.toString(this);
    }

    @Override
    public boolean equals(Object o) {
        return Person4Object.equals(this, o);
    }

    @Override
    public int hashCode() {
        return Person4Object.hashCode(this);
    }
}
//end::clazz[]
