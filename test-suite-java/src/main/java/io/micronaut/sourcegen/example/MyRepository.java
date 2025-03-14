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

public interface MyRepository {

    static String staticMethod(String string, Integer integer, int i) {
        return string + (integer + i);
    }

    default String defaultMethod(String string, Integer integer, int i) {
        return string + (integer + i);
    }

    String interfaceMethod(String string, Integer integer, int i);

    double interfaceMethodReturnsDouble();

    long interfaceMethodReturnsLong();

    int interfaceMethodReturnsInt();

}
