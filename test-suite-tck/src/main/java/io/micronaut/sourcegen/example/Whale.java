/*
 * Copyright 2017-2025 original authors
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

/**
 * A bean whose properties are read through accessors that the JavaBeans rules do not decapitalize the
 * same way as the fields behind them: {@code getABC()} gives the property name {@code ABC} for a field
 * called {@code aBC}, because a name whose first two characters are upper case is left alone. The
 * builder has to keep such a property in a field of one name, so that the setter that assigns it and
 * the build method that reads it cannot disagree.
 */
@Builder
public class Whale {

    private final String aBC;
    private final int x;
    private final String URL;

    public Whale(String aBC, int x, String URL) {
        this.aBC = aBC;
        this.x = x;
        this.URL = URL;
    }

    public String getABC() {
        return aBC;
    }

    public int getX() {
        return x;
    }

    public String getURL() {
        return URL;
    }
}
