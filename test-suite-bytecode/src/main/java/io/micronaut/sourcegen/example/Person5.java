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

import io.micronaut.sourcegen.annotations.Builder;

import java.util.Arrays;
import java.util.List;

@Builder(strict = true)
public class Person5 {
    public enum Title {
        MRS,
        MR,
        MS
    }

    private long id;
    private Title title;
    private String name;
    private byte[] bytes;
    private List<String> strings;

    public Person5(long id, Title title, String name, byte[] bytes, List<String> strings) {
        this.id = id;
        this.title = title;
        this.name = name;
        this.bytes = (bytes != null) ? Arrays.copyOf(bytes, bytes.length) : null;
        this.strings = strings;
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

    public List<String> getStrings() {
        return strings;
    }
}
