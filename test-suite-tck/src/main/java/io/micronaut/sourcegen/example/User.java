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
import io.micronaut.sourcegen.annotations.Singular;
import io.micronaut.sourcegen.annotations.SuperBuilder;

import java.util.List;
import java.util.Map;

@SuperBuilder
public record User(Long id,
                   String name,
                   @Singular List<String> roles,
                   @Singular("property") Map<String, Object> properties) {
}
//end::clazz[]
