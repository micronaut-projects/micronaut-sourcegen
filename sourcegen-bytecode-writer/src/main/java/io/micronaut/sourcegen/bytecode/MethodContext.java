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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The statement context.
 *
 * @param objectDef The current object definition
 * @param methodDef The current method definition.
 * @param locals    The locals
 * @param lambdaMethods  Lambda methods created by this method
 * @param isLambda  Whether this method is a lambda
 * @param yieldTargets  The switch yield cases being written, innermost last
 * @since 1.5
 */
@Internal
public record MethodContext(@Nullable ObjectDef objectDef,
                            MethodDef methodDef,
                            Map<String, LocalData> locals,
                            List<MethodDef> lambdaMethods,
                            boolean isLambda,
                            Deque<YieldTarget> yieldTargets) {

    public MethodContext(@Nullable ObjectDef objectDef,
                         MethodDef methodDef,
                         Map<String, LocalData> locals,
                         List<MethodDef> lambdaMethods,
                         boolean isLambda) {
        this(objectDef, methodDef, locals, lambdaMethods, isLambda, new ArrayDeque<>());
    }

    public MethodContext(@Nullable ObjectDef objectDef,
                         MethodDef methodDef, boolean isLambda) {
        this(objectDef, methodDef, new LinkedHashMap<>(), new ArrayList<>(), isLambda);
    }

    /**
     * The switch yield case a return statement contributes its value to, instead of returning
     * from the method.
     *
     * @return The innermost yield case being written, or null when a return is a method return
     */
    @Nullable
    public YieldTarget currentYieldTarget() {
        return yieldTargets.peek();
    }

    /**
     * A switch yield case being written.
     *
     * @param type  The type the case yields
     * @param slot  The local the yielded value is held in
     * @param end   The label the value is loaded at
     */
    public record YieldTarget(TypeDef type, int slot, Label end) {
    }

    /**
     * The local data.
     *
     * @param name  The name
     * @param type  The type
     * @param start The start label
     * @param index The index
     */
    public record LocalData(String name, Type type, Label start, int index) {
    }

}
