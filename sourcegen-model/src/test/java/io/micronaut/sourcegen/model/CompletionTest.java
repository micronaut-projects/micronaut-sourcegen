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
package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings in which the completion analyses of the backends differ.
 */
class CompletionTest {

    private static final StatementDef RETURNING = ExpressionDef.constant(1).returning();
    private static final StatementDef THROWING = ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow();
    private static final StatementDef COMPLETING = new VariableDef.Local("value", TypeDef.Primitive.INT)
        .defineAndAssign(ExpressionDef.constant(1));
    private static final Completion JAVA = Completion.javaSource(
        condition -> condition instanceof ExpressionDef.Constant constant && Boolean.TRUE.equals(constant.value()),
        StatementDef.Try::catches);

    @Test
    void blocks() {
        // A statement after one that returns: javac stops at the return, the others read the last statement
        StatementDef deadEnd = new StatementDef.Multi(List.of(RETURNING, COMPLETING));
        assertFalse(JAVA.canCompleteNormally(deadEnd));
        assertTrue(Completion.BYTECODE.canCompleteNormally(deadEnd));
        assertTrue(Completion.KOTLIN.canCompleteNormally(deadEnd));
        // A nested block is flattened: an empty one at the end is no statement
        StatementDef trailingEmpty = new StatementDef.Multi(List.of(RETURNING, new StatementDef.Multi(List.of())));
        for (Completion completion : List.of(JAVA, Completion.BYTECODE, Completion.KOTLIN)) {
            assertTrue(completion.canCompleteNormally(new StatementDef.Multi(List.of())));
            assertFalse(completion.canCompleteNormally(new StatementDef.Multi(List.of(COMPLETING, THROWING))));
            assertFalse(completion.canCompleteNormally(trailingEmpty));
        }
    }

    @Test
    void constantLoops() {
        StatementDef endless = new StatementDef.While(ExpressionDef.trueValue(), COMPLETING);
        assertFalse(JAVA.canCompleteNormally(endless));
        assertTrue(Completion.BYTECODE.canCompleteNormally(endless));
        assertTrue(Completion.KOTLIN.canCompleteNormally(endless));
    }

    @Test
    void finallyBlocks() {
        StatementDef returnsThenFinally = new StatementDef.Try(RETURNING, List.of(), COMPLETING);
        StatementDef finallyThrows = new StatementDef.Try(COMPLETING, List.of(), THROWING);
        assertFalse(JAVA.canCompleteNormally(returnsThenFinally));
        assertFalse(Completion.BYTECODE.canCompleteNormally(returnsThenFinally));
        assertTrue(Completion.KOTLIN.canCompleteNormally(returnsThenFinally));
        assertFalse(JAVA.canCompleteNormally(finallyThrows));
        assertFalse(Completion.BYTECODE.canCompleteNormally(finallyThrows));
        assertTrue(Completion.KOTLIN.canCompleteNormally(finallyThrows));
        // Without a finally, every analysis completes a try as its body and catches do
        StatementDef.Try caught = new StatementDef.Try(RETURNING, List.of(), null).doCatch(Exception.class, exception -> COMPLETING);
        for (Completion completion : List.of(JAVA, Completion.BYTECODE, Completion.KOTLIN)) {
            assertTrue(completion.canCompleteNormally(caught));
            assertFalse(completion.canCompleteNormally(new StatementDef.Try(RETURNING, List.of(), null)));
        }
    }

    @Test
    void reachableCatches() {
        // A catch javac takes as unreachable does not complete the try
        Completion withoutCatches = Completion.javaSource(condition -> false, aTry -> List.of());
        StatementDef.Try caught = new StatementDef.Try(RETURNING, List.of(), null).doCatch(Exception.class, exception -> COMPLETING);
        assertFalse(withoutCatches.canCompleteNormally(caught));
        assertTrue(JAVA.canCompleteNormally(caught));
    }

    @Test
    void synchronizedBlocks() {
        StatementDef locked = new StatementDef.Synchronized(ExpressionDef.nullValue(), RETURNING);
        assertFalse(JAVA.canCompleteNormally(locked));
        assertFalse(Completion.BYTECODE.canCompleteNormally(locked));
        assertTrue(Completion.KOTLIN.canCompleteNormally(locked));
    }
}
