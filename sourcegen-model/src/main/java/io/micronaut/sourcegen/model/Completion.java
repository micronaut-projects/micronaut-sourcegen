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

import io.micronaut.core.annotation.Internal;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Whether a statement can complete normally, following JLS 14.22, which every backend asks: where control reaches the
 * end of a method, of a branch, of a try block or of a switch case decides whether a return, a jump or a finally block
 * is written after it, and whether a source compiler accepts what follows.
 *
 * <p>The rules every backend shares:</p>
 * <ul>
 *     <li>A return or a throw never completes; a statement the rules below do not name always does.</li>
 *     <li>An if/else completes when either branch does; an if without an else always does.</li>
 *     <li>A switch completes without a default, or when the default or one of its cases does.</li>
 * </ul>
 *
 * <p>Where the backends answer differently, each one is a setting of this class:</p>
 * <ul>
 *     <li>How a block completes: Java source takes a block that stops at any statement as stopping,
 *     as javac does; the bytecode writers and Kotlin take the last statement.</li>
 *     <li>The constant conditions: Java source takes a {@code while} loop over a constant expression of the value
 *     true as never completing - the model has no break - because javac's reachability depends on it, which needs
 *     Java's constant folding. The bytecode writers and Kotlin take every loop as completing.</li>
 *     <li>The catches of a try: Java source counts the catches javac takes as reachable alone; the others count every
 *     catch.</li>
 *     <li>A try with a finally block: Java source and the bytecode writers analyze it - a finally that cannot complete
 *     ends the try, and one that can completes as the try and its catches do. Kotlin takes it as completing.</li>
 *     <li>A synchronized block: Java source and the bytecode writers complete it as its body does. Kotlin writes it as a
 *     call of {@code synchronized}, which it takes as completing.</li>
 * </ul>
 *
 * <p>Being wrong in the "cannot complete" direction is not just a compile or verifier error: an if/else then-branch
 * judged as non-completing gets no jump over the else-branch and falls through into it, and a method judged as
 * returning gets no implicit return.</p>
 *
 * @since 2.3
 */
@Internal
public final class Completion {

    /**
     * As the bytecode writers analyze a statement: the last statement of a block, and no constant condition.
     */
    public static final Completion BYTECODE = new Completion(Blocks.LAST, condition -> false,
        StatementDef.Try::catches, true, true);

    /**
     * As the Kotlin generator analyzes a statement: the last statement of a block, no constant condition, and a try
     * with a finally block and a synchronized block taken as completing.
     */
    public static final Completion KOTLIN = new Completion(Blocks.LAST, condition -> false,
        StatementDef.Try::catches, false, false);

    private final Blocks blocks;
    private final Predicate<ExpressionDef> constantTrue;
    private final Function<StatementDef.Try, List<StatementDef.Try.Catch>> reachableCatches;
    private final boolean analyzesFinally;
    private final boolean analyzesSynchronized;

    private Completion(Blocks blocks,
                       Predicate<ExpressionDef> constantTrue,
                       Function<StatementDef.Try, List<StatementDef.Try.Catch>> reachableCatches,
                       boolean analyzesFinally,
                       boolean analyzesSynchronized) {
        this.blocks = blocks;
        this.constantTrue = constantTrue;
        this.reachableCatches = reachableCatches;
        this.analyzesFinally = analyzesFinally;
        this.analyzesSynchronized = analyzesSynchronized;
    }

    /**
     * The analysis of Java source, as javac decides reachability: a block stops at any statement that cannot complete,
     * a loop over a constant expression of the value true never completes, and a try completes as the catches javac
     * takes as reachable do.
     *
     * @param constantTrue     Whether a loop condition is a constant expression of the value true, as Java folds it
     * @param reachableCatches The catches of a try that javac takes as reachable
     * @return The analysis
     */
    public static Completion javaSource(Predicate<ExpressionDef> constantTrue,
                                        Function<StatementDef.Try, List<StatementDef.Try.Catch>> reachableCatches) {
        return new Completion(Blocks.ANY, constantTrue, reachableCatches, true, true);
    }

    /**
     * @param statement The statement
     * @return {@code true} if control can reach the end of the statement
     */
    public boolean canCompleteNormally(StatementDef statement) {
        return switch (statement) {
            case StatementDef.Return ignored -> false;
            case StatementDef.Throw ignored -> false;
            case StatementDef.Multi multi -> blockCompletes(multi);
            case StatementDef.IfElse ifElse ->
                canCompleteNormally(ifElse.statement()) || canCompleteNormally(ifElse.elseStatement());
            case StatementDef.Switch aSwitch -> aSwitch.defaultCase() == null
                || canCompleteNormally(aSwitch.defaultCase())
                || aSwitch.cases().values().stream().anyMatch(this::canCompleteNormally);
            case StatementDef.Try aTry -> tryCompletes(aTry);
            case StatementDef.Synchronized aSynchronized ->
                !analyzesSynchronized || canCompleteNormally(aSynchronized.statement());
            case StatementDef.While aWhile -> !constantTrue.test(aWhile.expression());
            default -> true;
        };
    }

    /**
     * @param statement The statement
     * @return {@code true} if control cannot reach the end of the statement
     */
    public boolean cannotCompleteNormally(StatementDef statement) {
        return !canCompleteNormally(statement);
    }

    private boolean blockCompletes(StatementDef.Multi multi) {
        // The statements of the block with the blocks nested in it flattened: an empty nested block is no statement
        List<StatementDef> statements = multi.flatten();
        return switch (blocks) {
            case LAST -> statements.isEmpty() || canCompleteNormally(statements.getLast());
            case ANY -> statements.stream().allMatch(this::canCompleteNormally);
        };
    }

    private boolean tryCompletes(StatementDef.Try aTry) {
        StatementDef finallyStatement = aTry.finallyStatement();
        if (finallyStatement != null) {
            if (!analyzesFinally) {
                return true;
            }
            if (!canCompleteNormally(finallyStatement)) {
                // A finally that cannot complete ends the try, whatever the try does
                return false;
            }
        }
        return canCompleteNormally(aTry.statement())
            || reachableCatches.apply(aTry).stream().anyMatch(aCatch -> canCompleteNormally(aCatch.statement()));
    }

    /**
     * How a block completes.
     */
    private enum Blocks {
        /**
         * As its last statement does. An empty block completes.
         */
        LAST,
        /**
         * Unless any of its statements cannot complete, as javac takes a block whose statement after one that cannot
         * complete is unreachable. An empty block completes.
         */
        ANY
    }
}
