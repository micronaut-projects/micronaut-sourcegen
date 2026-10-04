/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.tck;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Function;

/**
 * Compiled fixtures shared by the signature TCK and the TCK topics that reuse its annotations and bridges.
 *
 * @since 2.3
 */
@SuppressWarnings({"MissingOverride", "UnusedTypeParameter"})
public final class SignatureFixtures {

    private SignatureFixtures() {
    }


    /**
     * Runtime marker for generic bound type annotations.
     *
     * @since 2.3
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Marker {
    }

    /** Bridge for a substituted inherited implementation.
     * @since 2.3
     */
    public static class InheritedSpecializedBridge extends ApplicabilityFixtures.NumericIdentity<Integer>
        implements Function<Integer, Integer> {
    }
}
