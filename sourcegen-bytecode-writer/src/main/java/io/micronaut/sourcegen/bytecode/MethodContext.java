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
import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The statement context.
 *
 * @param objectDef The current object definition
 * @param methodDef The current method definition.
 * @param locals    The locals
 * @param lambdaMethods  Lambda methods created by this method
 * @param isLambda  Whether this method is a lambda
 * @param yieldTargets  The switch yield cases being written, innermost last
 * @param openGaps  The gaps opened by the returns and yields being written, innermost last
 * @param enclosingScope The enclosing scope of the class being written
 * @since 1.5
 */
@Internal
public record MethodContext(@Nullable ObjectDef objectDef,
                            MethodDef methodDef,
                            Map<String, LocalData> locals,
                            List<MethodDef> lambdaMethods,
                            boolean isLambda,
                            Deque<YieldTarget> yieldTargets,
                            List<Gap> openGaps,
                            EnclosingScope enclosingScope) {

    public MethodContext(@Nullable ObjectDef objectDef,
                         MethodDef methodDef,
                         Map<String, LocalData> locals,
                         List<MethodDef> lambdaMethods,
                         boolean isLambda,
                         EnclosingScope enclosingScope) {
        this(objectDef, methodDef, locals, lambdaMethods, isLambda, new ArrayDeque<>(), new ArrayList<>(), enclosingScope);
    }

    public MethodContext(@Nullable ObjectDef objectDef,
                         MethodDef methodDef, boolean isLambda, EnclosingScope enclosingScope) {
        this(objectDef, methodDef, new LinkedHashMap<>(), new ArrayList<>(), isLambda, enclosingScope);
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
     * Opens a gap at the current position, where a return or a yield writes the cleanup of a statement it
     * leaves. The return or the yield closes the gap past the instruction that leaves.
     *
     * @param generatorAdapter The adapter
     * @return The gap
     */
    public Gap openGap(GeneratorAdapter generatorAdapter) {
        Gap gap = new Gap();
        generatorAdapter.visitLabel(gap.start);
        openGaps.add(gap);
        return gap;
    }

    /**
     * Closes the gaps opened by a return or a yield, once it has written the instruction that leaves.
     *
     * @param generatorAdapter The adapter
     * @param from             The number of gaps that were open before the return or the yield
     */
    public void closeGaps(GeneratorAdapter generatorAdapter, int from) {
        List<Gap> opened = openGaps.subList(from, openGaps.size());
        if (opened.isEmpty()) {
            return;
        }
        Label end = new Label();
        generatorAdapter.visitLabel(end);
        for (Gap gap : opened) {
            gap.end = end;
        }
        opened.clear();
    }

    /**
     * The code a return or a yield writes on its way out of a try or a synchronized statement: from the
     * copy of the finally block, or from past the release of the monitor, to past the instruction that
     * leaves. As javac does, the statement leaves the gap out of its exception ranges, so that it does
     * not handle an exception thrown by its own finally block, or release its monitor twice.
     */
    public static final class Gap {

        private final Label start = new Label();
        private @Nullable Label end;

        private Gap() {
        }

        /**
         * @return The label the gap starts at
         */
        public Label start() {
            return start;
        }

        /**
         * @return The label the gap ends at, once the return or the yield closed it
         */
        public Label end() {
            return Objects.requireNonNull(end, "The gap is not closed");
        }
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
