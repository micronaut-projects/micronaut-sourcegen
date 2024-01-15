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

import io.micronaut.core.annotation.Introspected;
import io.micronaut.sourcegen.custom.example.CopyAnnotations;
import io.micronaut.sourcegen.example.Heron.Boo;
import io.micronaut.sourcegen.example.Heron.Color;
import io.micronaut.sourcegen.example.Heron.MultiValue;
import io.micronaut.sourcegen.example.Heron.Nested;
import io.micronaut.sourcegen.example.Heron.Simple;

import java.lang.annotation.Repeatable;

@CopyAnnotations(newTypeName = "BlueHeron")
@Simple(
    name = "A",
    age = 12,
    color = Color.WHITE,
    type = String.class
)
@Nested(
    simple = @Simple(
        name = "B",
        age = 12,
        color = Color.BLUE
    )
)
@MultiValue(
    simples = {
        @Simple(
            name = "C"
        ),
        @Simple(
            name = "D"
        )
    },
    values = {
        1,
        2,
        3
    },
    strings = {
        "a",
        "b"
    }
)
@Boo(name = "boom")
@Boo(name = "bam")
@Introspected
public class Heron {

    public enum Color {
        BLUE,
        WHITE,
        BLACK
    }

    public @interface Simple {
        String name();
        int age() default 0;
        Color color() default Color.BLACK;
        Class<?> type() default Void.class;
    }

    public @interface Nested {
        Simple simple();
    }

    public @interface MultiValue {
        Simple[] simples();
        int[] values();
        String[] strings();
    }

    @Repeatable(BooRepeated.class)
    public @interface Boo {
        String name();
    }

    public @interface BooRepeated {
        Boo[] value();
    }
}
