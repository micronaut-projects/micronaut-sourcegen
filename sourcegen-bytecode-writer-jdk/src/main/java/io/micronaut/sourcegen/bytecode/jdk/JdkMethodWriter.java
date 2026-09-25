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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import io.micronaut.sourcegen.model.Completion;
import io.micronaut.sourcegen.bytecode.core.Conversions;
import io.micronaut.sourcegen.bytecode.core.InvocationPlan;
import io.micronaut.sourcegen.bytecode.core.ReferenceComparisons;
import io.micronaut.sourcegen.bytecode.core.SwitchKeys;
import io.micronaut.sourcegen.bytecode.core.TypeUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;

import javax.lang.model.element.Modifier;

import static java.lang.classfile.Opcode.IFNE;
import static java.lang.classfile.Opcode.IFEQ;

/**
 * Lowers Sourcegen method trees directly to a JDK {@link CodeBuilder}.
 *
 * <p>This writer deliberately keeps all labels and local-slot allocation in one context. The JDK
 * API then computes max stack, max locals, and stack maps when the class is built.</p>
 *
 * @since 2.2
 */
final class JdkMethodWriter {

    private static final String EXCEPTION_NAME = "$exception";
    private static final String HASH_CODE = "hashCode";
    private static final String EQUALS = "equals";
    private static final String SUPER = "super";

    private final CodeBuilder code;
    private final ObjectDef objectDef;
    private final ClassDesc owner;
    private final Map<String, Local> locals = new LinkedHashMap<>();
    private final List<Cleanup> cleanups = new ArrayList<>();
    private final List<Gap> openGaps = new ArrayList<>();
    private final Deque<YieldTarget> yieldTargets = new ArrayDeque<>();
    private final List<MethodDef> lambdaMethods = new ArrayList<>();
    private final MethodDef methodDef;
    private final EnclosingScope enclosingScope;
    private final InstructionCounter instructions;

    private JdkMethodWriter(CodeBuilder code, ObjectDef objectDef, MethodDef methodDef, ClassDesc owner,
                            EnclosingScope enclosingScope, InstructionCounter instructions) {
        this.code = code;
        this.enclosingScope = enclosingScope;
        this.objectDef = objectDef;
        this.owner = owner;
        this.methodDef = methodDef;
        this.instructions = instructions;
        for (int i = 0; i < methodDef.getParameters().size(); i++) {
            var parameter = methodDef.getParameters().get(i);
            locals.put(parameter.getName(), new Local(parameter.getType(), code.parameterSlot(i)));
        }
    }

    /**
     * Writes the code of a method with a writer. The code builder handed to the handler counts the
     * instructions written, which is how the writer leaves out an exception range protecting none.
     *
     * @param methodBuilder  The method builder
     * @param objectDef      The object the method belongs to
     * @param methodDef      The method
     * @param owner          The class the method belongs to
     * @param enclosingScope The enclosing scope of the class being written
     * @param handler        Writes the code with the code builder and the writer
     */
    static void withCode(MethodBuilder methodBuilder, ObjectDef objectDef, MethodDef methodDef, ClassDesc owner,
                         EnclosingScope enclosingScope, BiConsumer<CodeBuilder, JdkMethodWriter> handler) {
        methodBuilder.withCode(code -> {
            InstructionCounter instructions = new InstructionCounter();
            code.transforming(instructions, counted ->
                handler.accept(counted, new JdkMethodWriter(counted, objectDef, methodDef, owner, enclosingScope, instructions)));
        });
    }

    List<MethodDef> lambdaMethods() {
        return List.copyOf(lambdaMethods);
    }

    void writeStatements(List<StatementDef> statements) {
        for (StatementDef statement : statements) {
            writeStatement(statement);
        }
    }

    void writeLocalVariables() {
        Label start = code.startLabel();
        Label end = code.endLabel();
        for (Map.Entry<String, Local> entry : locals.entrySet()) {
            Local local = entry.getValue();
            code.localVariable(local.slot(), entry.getKey(), classDesc(local.type()), start, end);
        }
    }

    private void writeStatement(StatementDef statement) {
        switch (statement) {
            case StatementDef.Multi multi -> writeStatements(multi.flatten());
            case StatementDef.Return returnStatement -> {
                writeReturn(returnStatement.expression());
            }
            case StatementDef.Throw throwing -> {
                writeExpression(throwing.expression());
                code.athrow();
            }
            case StatementDef.DefineAndAssign define -> {
                writeExpression(new ExpressionDef.Cast(define.variable().type(), define.expression()));
                int slot = code.allocateLocal(kind(define.variable().type()));
                locals.put(define.variable().name(), new Local(define.variable().type(), slot));
                store(define.variable().type(), slot);
            }
            case StatementDef.Assign assign -> {
                Local local = local(assign.variable().name());
                writeExpression(new ExpressionDef.Cast(local.type(), assign.expression()));
                store(local.type(), local.slot());
            }
            case StatementDef.PutField putField -> {
                writeExpression(putField.field().instance());
                writeExpression(new ExpressionDef.Cast(putField.field().type(), putField.expression()));
                code.putfield(classDesc(putField.field().declaringType()), putField.field().name(),
                    memberDesc(putField.field().declaringType(), putField.field().name(), putField.field().type()));
            }
            case StatementDef.PutStaticField putStaticField -> {
                writeExpression(new ExpressionDef.Cast(putStaticField.field().type(), putStaticField.expression()));
                code.putstatic(classDesc(putStaticField.field().ownerType()), putStaticField.field().name(),
                    memberDesc(putStaticField.field().ownerType(), putStaticField.field().name(), putStaticField.field().type()));
            }
            case ExpressionDef.InvokeInstanceMethod invoke -> {
                writeInvocation(invoke.instance(), invoke.method(), invoke.values());
                // The call leaves what it is typed as, converted from the declared return type
                popIfNeeded(invoke.type());
            }
            case ExpressionDef.InvokeStaticMethod invoke -> {
                writeStaticInvocation(invoke.classDef(), invoke.method(), invoke.values());
                popIfNeeded(invoke.type());
            }
            case StatementDef.InvokeSuperConstructor invoke -> writeSuperConstructor(invoke);
            case StatementDef.If anIf -> {
                var end = code.newLabel();
                writeCondition(anIf.condition(), null, end);
                writeStatement(anIf.statement());
                code.labelBinding(end);
            }
            case StatementDef.IfElse ifElse -> {
                var elseLabel = code.newLabel();
                var end = code.newLabel();
                writeCondition(ifElse.condition(), null, elseLabel);
                writeStatement(ifElse.statement());
                boolean thenCompletes = Completion.BYTECODE.canCompleteNormally(ifElse.statement());
                if (thenCompletes) {
                    code.goto_(end);
                }
                code.labelBinding(elseLabel);
                writeStatement(ifElse.elseStatement());
                if (thenCompletes) {
                    code.labelBinding(end);
                }
            }
            case StatementDef.While loop -> {
                var test = code.newLabel();
                var end = code.newLabel();
                code.labelBinding(test);
                writeCondition(loop.expression(), null, end);
                writeStatement(loop.statement());
                code.goto_(test).labelBinding(end);
            }
            case StatementDef.Switch aSwitch -> writeSwitch(aSwitch);
            case StatementDef.Try aTry -> writeTry(aTry);
            case StatementDef.Synchronized synchronizedStatement -> writeSynchronized(synchronizedStatement);
            case ExpressionDef expression -> {
                writeExpression(expression);
                popIfNeeded(expression.type());
            }
            default -> throw unsupported(statement);
        }
    }

    private void writeReturn(@Nullable ExpressionDef expression) {
        // The gaps the cleanups are written in end past the instruction that leaves
        int gapsBefore = openGaps.size();
        YieldTarget yieldTarget = yieldTargets.peek();
        if (yieldTarget != null) {
            writeYield(expression, yieldTarget);
        } else {
            writeMethodReturn(expression);
        }
        closeGaps(gapsBefore);
    }

    private void writeMethodReturn(@Nullable ExpressionDef expression) {
        TypeDef returnType = methodDef.getReturnType();
        if (expression == null || returnType.equals(TypeDef.VOID)) {
            // A void method may still return the result of an expression, which is evaluated for
            // its effect and discarded; there is no value to hold across the cleanups
            if (expression != null) {
                writeExpression(expression);
                popIfNeeded(expression.type());
            }
            writeCleanups();
            code.return_();
            return;
        }
        writeExpression(new ExpressionDef.Cast(returnType, expression));
        TypeKind returnKind = kind(returnType);
        if (cleanups.isEmpty()) {
            code.return_(returnKind);
            return;
        }
        // The finally blocks run between evaluating the value and returning it, so it has to be
        // held in a local across them
        int slot = code.allocateLocal(returnKind);
        code.storeLocal(returnKind, slot);
        writeCleanups();
        code.loadLocal(returnKind, slot).return_(returnKind);
    }

    /**
     * Writes a return that is the value of the switch yield case being written: the value is held
     * in the local of the case and control jumps to its end, where the case loads it.
     */
    private void writeYield(@Nullable ExpressionDef expression, YieldTarget yieldTarget) {
        if (expression == null) {
            throw new IllegalStateException("Switch yield return has no value");
        }
        writeExpression(new ExpressionDef.Cast(yieldTarget.type(), expression));
        store(yieldTarget.type(), yieldTarget.slot());
        writeCleanups(yieldTarget.cleanups());
        code.goto_(yieldTarget.end());
    }

    /**
     * Writes a switch yield case, leaving the value it yields on the stack. The case is a statement
     * block, and the returns it contains are its yields; the ASM backend lowers them the same way.
     */
    private void writeYieldCase(ExpressionDef.SwitchYieldCase yieldCase) {
        TypeKind kind = kind(yieldCase.type());
        int slot = code.allocateLocal(kind);
        Label end = code.newLabel();
        yieldTargets.push(new YieldTarget(yieldCase.type(), slot, end, cleanups.size()));
        try {
            writeStatement(yieldCase.statement());
        } finally {
            yieldTargets.pop();
        }
        code.labelBinding(end);
        code.loadLocal(kind, slot);
    }

    private void writeCleanups() {
        writeCleanups(0);
    }

    private void writeCleanups(int from) {
        for (int i = cleanups.size() - 1; i >= from; i--) {
            writeCleanup(i);
        }
    }

    /**
     * Writes a pending cleanup. The cleanup belongs outside of the statement that opened it, so only
     * the cleanups opened before it are pending while it is written - a return in a finally block
     * runs those, not the finally block again.
     */
    private void writeCleanup(int index) {
        List<Cleanup> opened = cleanups.subList(index, cleanups.size());
        List<Cleanup> suspended = new ArrayList<>(opened);
        opened.clear();
        try {
            Cleanup cleanup = suspended.getFirst();
            if (cleanup.inGap()) {
                openGap(cleanup);
                cleanup.code().run();
            } else {
                cleanup.code().run();
                openGap(cleanup);
            }
        } finally {
            cleanups.addAll(suspended);
        }
    }

    private void openGap(Cleanup cleanup) {
        Gap gap = new Gap(position());
        cleanup.gaps().add(gap);
        openGaps.add(gap);
    }

    /**
     * Closes the gaps opened by a return or a yield, once it has written the instruction that leaves.
     *
     * @param from The number of gaps that were open before the return or the yield
     */
    private void closeGaps(int from) {
        List<Gap> opened = openGaps.subList(from, openGaps.size());
        if (opened.isEmpty()) {
            return;
        }
        Position end = position();
        for (Gap gap : opened) {
            gap.end = end;
        }
        opened.clear();
    }

    private Position position() {
        return new Position(code.newBoundLabel(), instructions.count);
    }

    private Position position(Label label) {
        code.labelBinding(label);
        return new Position(label, instructions.count);
    }

    /**
     * Adds the entries of a handler for a range of code, leaving out the gaps in it. The JVM rejects an
     * entry protecting no instruction, which a try leaves where its body ends with a return, past the
     * finally block the return writes, so such an entry is left out as javac does.
     *
     * @param range   The range
     * @param handler The handler
     * @param type    The exception type handled, or null for any
     */
    private void exceptionCatch(Range range, Label handler, @Nullable ClassDesc type) {
        Position from = range.start();
        for (Gap gap : range.gaps()) {
            exceptionCatch(from, gap.start, handler, type);
            from = Objects.requireNonNull(gap.end, "The gap is not closed");
        }
        exceptionCatch(from, range.end(), handler, type);
    }

    private void exceptionCatch(Position start, Position end, Label handler, @Nullable ClassDesc type) {
        if (end.instructions() == start.instructions()) {
            return;
        }
        if (type == null) {
            code.exceptionCatchAll(start.label(), end.label(), handler);
        } else {
            code.exceptionCatch(start.label(), end.label(), handler, type);
        }
    }

    private void writeTry(StatementDef.Try aTry) {
        StatementDef finallyStatement = aTry.finallyStatement();
        Label end = code.newLabel();
        Label finallyHandler = finallyStatement == null ? null : code.newLabel();
        List<CatchHandler> handlers = new ArrayList<>();
        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            handlers.add(new CatchHandler(aCatch, code.newLabel()));
        }

        Range body = writeTryBody(aTry.statement(), finallyStatement);
        if (Completion.BYTECODE.canCompleteNormally(aTry.statement())) {
            writeFinallyAndExit(finallyStatement, end);
        }

        List<Range> catchBodies = writeCatchHandlers(handlers, finallyStatement, end);
        if (finallyHandler != null) {
            writeFinallyHandler(finallyHandler, finallyStatement);
        }
        code.labelBinding(end);

        // The JVM takes the first entry of the exception table that matches, so the entries of the try
        // follow those of the statements nested in it, which were added as they were written
        for (CatchHandler handler : handlers) {
            exceptionCatch(body, handler.label(), classDesc(handler.aCatch().exception()));
        }
        if (finallyHandler != null) {
            exceptionCatch(body, finallyHandler, null);
            for (Range catchBody : catchBodies) {
                exceptionCatch(catchBody, finallyHandler, null);
            }
        }
    }

    private Range writeTryBody(StatementDef statement, @Nullable StatementDef finallyStatement) {
        Position start = position();
        List<Gap> gaps = addCleanup(finallyStatement);
        writeStatement(statement);
        removeCleanup(finallyStatement);
        return new Range(start, position(), gaps);
    }

    /**
     * Writes the catch blocks of a try.
     *
     * @return The ranges of the catch blocks, from their handlers to the end of their bodies
     */
    private List<Range> writeCatchHandlers(List<CatchHandler> handlers, @Nullable StatementDef finallyStatement,
                                           Label end) {
        List<Range> bodies = new ArrayList<>(handlers.size());
        for (CatchHandler handler : handlers) {
            Position start = position(handler.label());
            int slot = code.allocateLocal(TypeKind.REFERENCE);
            code.storeLocal(TypeKind.REFERENCE, slot);
            locals.put(EXCEPTION_NAME, new Local(handler.aCatch().exception(), slot));
            List<Gap> gaps = addCleanup(finallyStatement);
            writeStatement(handler.aCatch().statement());
            removeCleanup(finallyStatement);
            locals.remove(EXCEPTION_NAME);
            bodies.add(new Range(start, position(), gaps));
            if (Completion.BYTECODE.canCompleteNormally(handler.aCatch().statement())) {
                writeFinallyAndExit(finallyStatement, end);
            }
        }
        return bodies;
    }

    private void writeFinallyHandler(Label finallyHandler, @Nullable StatementDef finallyStatement) {
        StatementDef requiredFinally = Objects.requireNonNull(finallyStatement);
        code.labelBinding(finallyHandler);
        int slot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, slot);
        writeStatements(requiredFinally.flatten());
        code.loadLocal(TypeKind.REFERENCE, slot).athrow();
    }

    /**
     * Opens the finally block of a try as the cleanup of the body being written.
     *
     * @return The gaps the returns and the yields in the body write the finally block in
     */
    private List<Gap> addCleanup(@Nullable StatementDef finallyStatement) {
        if (finallyStatement == null) {
            return List.of();
        }
        Cleanup cleanup = new Cleanup(() -> writeStatements(finallyStatement.flatten()), true);
        cleanups.add(cleanup);
        return cleanup.gaps();
    }

    private void removeCleanup(@Nullable StatementDef finallyStatement) {
        if (finallyStatement != null) {
            cleanups.removeLast();
        }
    }

    private void writeFinally(@Nullable StatementDef finallyStatement) {
        if (finallyStatement != null) {
            writeStatements(finallyStatement.flatten());
        }
    }

    /**
     * Writes the finally block of a try block or catch block that completed, then the jump past the
     * try. A finally block that cannot complete takes no jump: the try cannot complete either, so its
     * end may be the end of the code.
     */
    private void writeFinallyAndExit(@Nullable StatementDef finallyStatement, Label end) {
        writeFinally(finallyStatement);
        if (finallyStatement == null || Completion.BYTECODE.canCompleteNormally(finallyStatement)) {
            code.goto_(end);
        }
    }

    private void writeSynchronized(StatementDef.Synchronized synchronizedStatement) {
        writeExpression(synchronizedStatement.monitor());
        int monitorSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, monitorSlot);
        code.loadLocal(TypeKind.REFERENCE, monitorSlot).monitorenter();
        Label handler = code.newLabel();
        Label complete = code.newLabel();
        Position start = position();
        Runnable exit = () -> code.loadLocal(TypeKind.REFERENCE, monitorSlot).monitorexit();
        // The release stays protected, as javac does, and the finally blocks around the block do not
        Cleanup release = new Cleanup(exit, false);
        cleanups.add(release);
        writeStatement(synchronizedStatement.statement());
        cleanups.removeLast();
        Range body = new Range(start, position(), release.gaps());
        if (Completion.BYTECODE.canCompleteNormally(synchronizedStatement.statement())) {
            exit.run();
            code.goto_(complete);
        }
        code.labelBinding(handler);
        int exceptionSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, exceptionSlot);
        exit.run();
        code.loadLocal(TypeKind.REFERENCE, exceptionSlot).athrow();
        code.labelBinding(complete);

        // The JVM takes the first entry of the exception table that matches, so the entry releasing the
        // monitor follows those of the statements nested in the block
        exceptionCatch(body, handler, null);
    }

    private void writeSwitch(StatementDef.Switch aSwitch) {
        if (aSwitch.expression().type().equals(TypeDef.STRING)) {
            writeStringSwitch(aSwitch);
            return;
        }
        writeSwitchSelector(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, StatementDef>> bodies = new ArrayList<>();
        Map<Integer, Label> labels = switchLabels(aSwitch.cases(), bodies);
        writeSwitchInstruction(defaultLabel, labels);
        for (Map.Entry<Label, StatementDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeStatement(body.getValue());
            if (Completion.BYTECODE.canCompleteNormally(body.getValue())) {
                code.goto_(end);
            }
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeStatement(aSwitch.defaultCase());
        }
        code.labelBinding(end);
    }

    private void writeStringSwitch(StatementDef.Switch aSwitch) {
        int valueSlot = writeSwitchHash(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, StatementDef>> bodies = new ArrayList<>();
        Map<ExpressionDef.Constant, Label> caseLabels = new LinkedHashMap<>();
        aSwitch.cases().forEach((constant, statement) ->
            caseLabels.put(constant, bodyLabel(statement, bodies)));

        writeStringDispatch(aSwitch.cases().keySet(), caseLabels, valueSlot, defaultLabel);
        for (Map.Entry<Label, StatementDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeStatement(body.getValue());
            if (Completion.BYTECODE.canCompleteNormally(body.getValue())) {
                code.goto_(end);
            }
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeStatement(aSwitch.defaultCase());
        }
        code.labelBinding(end);
    }

    /**
     * Pushes the hash code of the switch value and returns the local holding the value itself,
     * which the equality tests then read.
     */
    private int writeSwitchHash(ExpressionDef expression) {
        writeExpression(expression);
        int valueSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, valueSlot);
        code.loadLocal(TypeKind.REFERENCE, valueSlot)
            .invokevirtual(ConstantDescs.CD_String, HASH_CODE, MethodTypeDesc.of(ConstantDescs.CD_int));
        return valueSlot;
    }

    /**
     * Switches on the hash of the value, then confirms the match with {@code String.equals}. Two
     * case values can share a hash code, so a hash may lead to several equality tests before the
     * default is taken.
     */
    private void writeStringDispatch(Collection<ExpressionDef.Constant> constants,
                                     Map<ExpressionDef.Constant, Label> caseLabels,
                                     int valueSlot,
                                     Label defaultLabel) {
        Map<Integer, List<ExpressionDef.Constant>> byHash = new LinkedHashMap<>();
        for (ExpressionDef.Constant constant : constants) {
            byHash.computeIfAbsent(switchKey(constant), key -> new ArrayList<>()).add(constant);
        }
        Map<Integer, Label> hashLabels = new LinkedHashMap<>();
        byHash.keySet().forEach(hash -> hashLabels.put(hash, code.newLabel()));
        writeSwitchInstruction(defaultLabel, hashLabels);
        for (Map.Entry<Integer, List<ExpressionDef.Constant>> entry : byHash.entrySet()) {
            code.labelBinding(hashLabels.get(entry.getKey()));
            for (ExpressionDef.Constant constant : entry.getValue()) {
                code.loadLocal(TypeKind.REFERENCE, valueSlot)
                    .loadConstant((String) constant.value())
                    .invokevirtual(ConstantDescs.CD_String, EQUALS,
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object))
                    .branch(IFNE, caseLabels.get(constant));
            }
            code.goto_(defaultLabel);
        }
    }

    /**
     * Emits the switch instruction, choosing {@code tableswitch} for a dense set of keys and
     * {@code lookupswitch} otherwise, the same trade-off the ASM backend makes. A dense switch is
     * both smaller and faster; string switches key on hash codes and are always sparse.
     */
    /**
     * Assigns one label per distinct case body, so that keys sharing a body branch to the same
     * code. A model that maps many keys to one statement, as a wither dispatch does, would
     * otherwise have that statement emitted once per key and quickly exceed the method size limit.
     *
     * @param cases The switch cases
     * @param bodies Receives each distinct body, in emission order, with the label bound to it
     * @return The label to branch to for each switch key
     */
    private <T> Map<Integer, Label> switchLabels(Map<ExpressionDef.Constant, ? extends T> cases,
                                                 List<Map.Entry<Label, T>> bodies) {
        Map<Integer, Label> labels = new LinkedHashMap<>();
        for (Map.Entry<ExpressionDef.Constant, ? extends T> entry : cases.entrySet()) {
            labels.put(switchKey(entry.getKey()), bodyLabel(entry.getValue(), bodies));
        }
        return labels;
    }

    /**
     * The label bound to a case body, reusing the one already assigned when several keys share the
     * very same body.
     */
    private <T> Label bodyLabel(T body, List<Map.Entry<Label, T>> bodies) {
        Objects.requireNonNull(body, "Switch case cannot be null");
        for (Map.Entry<Label, T> existing : bodies) {
            if (existing.getValue() == body) {
                return existing.getKey();
            }
        }
        Label label = code.newLabel();
        bodies.add(Map.entry(label, body));
        return label;
    }

    private void writeSwitchInstruction(Label defaultLabel, Map<Integer, Label> labels) {
        List<java.lang.classfile.instruction.SwitchCase> cases = labels.entrySet().stream()
            .map(entry -> java.lang.classfile.instruction.SwitchCase.of(entry.getKey(), entry.getValue()))
            .toList();
        int min = labels.keySet().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = labels.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        long range = (long) max - min + 1;
        if (!labels.isEmpty() && cases.size() * 2L >= range) {
            code.tableswitch(min, max, defaultLabel, cases);
        } else {
            code.lookupswitch(defaultLabel, cases);
        }
    }

    /**
     * Writes the selector of a switch on an int: a char, a short or a byte is widened, and a wrapper unboxed, which
     * throws for null, as javac switches on them.
     */
    private void writeSwitchSelector(ExpressionDef selector) {
        writeExpression(new ExpressionDef.Cast(TypeDef.Primitive.INT, selector));
    }

    private static int switchKey(ExpressionDef.Constant constant) {
        if (constant.value() instanceof String string) {
            return string.hashCode();
        }
        Integer key = SwitchKeys.key(constant);
        if (key != null) {
            return key;
        }
        throw new UnsupportedOperationException("Unsupported switch constant: " + constant.value());
    }

    void writeExpression(ExpressionDef expression) {
        switch (expression) {
            case ExpressionDef.Constant constant -> writeConstant(constant);
            case VariableDef variable -> writeVariable(variable);
            case ExpressionDef.Cast cast -> writeCast(cast);
            case ExpressionDef.NewInstance newInstance -> {
                InvocationPlan plan = InvocationPlan.ofNewInstance(newInstance, objectDef, methodDef, enclosingScope);
                code.new_(ClassDesc.ofDescriptor(plan.ownerDescriptor())).dup();
                writeInvocation(plan);
            }
            case ExpressionDef.InvokeInstanceMethod invoke -> writeInvocation(invoke.instance(), invoke.method(), invoke.values());
            case ExpressionDef.InvokeStaticMethod invoke -> writeStaticInvocation(invoke.classDef(), invoke.method(), invoke.values());
            case ExpressionDef.MathBinaryOperation math -> {
                writeExpression(math.left());
                if (kind(math.type()) == TypeKind.LONG && isShift(math.opType())) {
                    // The JVM shifts a long by an int distance, which the model converts to the type of the operation
                    ExpressionDef distance = math.right();
                    if (distance instanceof ExpressionDef.Cast cast && cast.expressionDef().type().isPrimitive()
                        && exactKind(cast.expressionDef().type()) != TypeKind.BOOLEAN && kind(cast.expressionDef().type()) == TypeKind.INT) {
                        // An int widened to long only to be narrowed again
                        distance = cast.expressionDef();
                    }
                    writeExpression(distance);
                    if (kind(distance.type()) == TypeKind.LONG) {
                        code.l2i();
                    }
                } else {
                    writeExpression(math.right());
                }
                writeMath(math);
                narrow(math.type());
            }
            case ExpressionDef.MathUnaryOperation math -> {
                // A wrapper is unboxed, negated as the primitive it holds and boxed again: the model types the
                // negation as its operand
                TypeDef operand = TypeDef.Primitive.unboxIfPossible(math.type());
                writeExpression(new ExpressionDef.Cast(operand, math.expression()));
                if (math.opType() == ExpressionDef.MathUnaryOperation.OpType.NEGATE) {
                    switch (kind(operand)) {
                        case INT -> code.ineg();
                        case LONG -> code.lneg();
                        case FLOAT -> code.fneg();
                        case DOUBLE -> code.dneg();
                        default -> throw unsupported(expression);
                    }
                    narrow(operand);
                }
                writeConversion(operand, math.type());
            }
            case ExpressionDef.StringConcatenation concat -> writeConcat(concat);
            case ExpressionDef.Lambda lambda -> writeLambda(lambda);
            case MethodReferenceExpression methodReference -> writeMethodReference(methodReference);
            case ExpressionDef.IfElse ifElse -> writeIfElseExpression(ifElse);
            case ExpressionDef.Switch aSwitch -> writeExpressionSwitch(aSwitch);
            case ExpressionDef.SwitchYieldCase yieldCase -> writeYieldCase(yieldCase);
            case ExpressionDef.NewArrayOfSize array -> {
                code.loadConstant(array.size());
                writeNewArray(array.type());
            }
            case ExpressionDef.NewArrayInitialized array -> {
                code.loadConstant(array.expressions().size());
                writeNewArray(array.type());
                TypeDef element = elementType(array.type());
                for (int i = 0; i < array.expressions().size(); i++) {
                    code.dup().loadConstant(i);
                    writeExpression(new ExpressionDef.Cast(element, array.expressions().get(i)));
                    code.arrayStore(exactKind(element));
                }
            }
            case ExpressionDef.ArrayElement array -> {
                writeExpression(array.expression());
                writeExpression(array.indexExpression());
                code.arrayLoad(exactKind(array.type()));
            }
            case ExpressionDef.GetPropertyValue property ->
                // The same idiom the ASM writer uses: the read method when the property has one,
                // the field otherwise. A direct getfield would break on a private field.
                writeExpression(new ExpressionDef.Cast(property.type(), JavaIdioms.getPropertyValue(property)));
            case ExpressionDef.InvokeGetClassMethod getClass -> {
                writeExpression(getClass.instance());
                code.invokevirtual(ConstantDescs.CD_Object, "getClass", MethodTypeDesc.of(ConstantDescs.CD_Class));
            }
            case ExpressionDef.InvokeHashCodeMethod hashCode ->
                // Null-safe, array-aware and primitive-aware, exactly as the ASM writer lowers it
                writeExpression(JavaIdioms.hashCode(hashCode));
            default -> writeBooleanValued(expression);
        }
    }

    /**
     * Writes an expression whose value is a boolean, leaving 0 or 1 on the stack.
     */
    private void writeBooleanValued(ExpressionDef expression) {
        switch (expression) {
            case ExpressionDef.InstanceOf instanceOf -> {
                writeExpression(instanceOf.expression());
                code.instanceOf(classDesc(instanceOf.instanceType()));
            }
            case ExpressionDef.EqualsReferentially equals -> writeReferenceComparison(equals.instance(), equals.other(), false);
            case ExpressionDef.NotEqualsReferentially notEquals -> writeReferenceComparison(notEquals.instance(), notEquals.other(), true);
            case ExpressionDef.EqualsStructurally equals -> {
                ExpressionDef.ComparisonOperation primitive = primitiveEquals(equals.instance(), equals.other(), false);
                if (primitive != null) {
                    writeBooleanExpression(primitive);
                } else {
                    writeStructuralEquals(equals.instance(), equals.other());
                }
            }
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                ExpressionDef.ComparisonOperation primitive = primitiveEquals(notEquals.instance(), notEquals.other(), true);
                if (primitive != null) {
                    writeBooleanExpression(primitive);
                } else {
                    writeStructuralEquals(notEquals.instance(), notEquals.other());
                    code.loadConstant(1).ixor();
                }
            }
            case ExpressionDef.ComparisonOperation comparison -> writeBooleanExpression(comparison);
            case ExpressionDef.IsNull isNull -> writeBooleanExpression(isNull);
            case ExpressionDef.IsNotNull isNotNull -> writeBooleanExpression(isNotNull);
            case ExpressionDef.IsTrue isTrue -> writeExpression(isTrue.expression());
            case ExpressionDef.IsFalse isFalse -> {
                writeExpression(isFalse.expression());
                code.iconst_1().ixor();
            }
            case ExpressionDef.And and -> writeBooleanExpression(and);
            case ExpressionDef.Or or -> writeBooleanExpression(or);
            default -> throw unsupported(expression);
        }
    }

    void writeInstanceInitializer(ClassDef classDef, io.micronaut.sourcegen.model.FieldDef field,
                                  ExpressionDef initializer) {
        code.aload(code.receiverSlot());
        writeExpression(new ExpressionDef.Cast(field.getType(), initializer));
        code.putfield(classDesc(classDef.asTypeDef()), field.getName(), classDesc(field.getType()));
    }

    private void writeLambda(ExpressionDef.Lambda lambda) {
        List<VariableDef> captured = captureVariables(lambda.implementation());
        List<ParameterDef> parameters = new ArrayList<>();
        for (VariableDef variable : captured) {
            parameters.add(ParameterDef.builder(Objects.requireNonNull(captureName(variable)), capturedType(variable)).build());
        }
        parameters.addAll(lambda.implementation().getParameters());
        MethodDef.MethodDefBuilder builder = MethodDef.builder("lambda$" + lambdaOwnerName(methodDef) + "$" + lambdaMethods.size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(parameters)
            .returns(lambda.implementation().getReturnType())
            .addStatements(lambda.implementation().getStatements());
        // The body is in the scope of the enclosing method: a captured value of its variable erases to the bound
        List<TypeDef.TypeVariable> own = lambda.implementation().getTypeVariables();
        own.forEach(builder::addTypeVariable);
        methodDef.getTypeVariables().stream()
            .filter(variable -> own.stream().noneMatch(declared -> declared.name().equals(variable.name())))
            .forEach(builder::addTypeVariable);
        MethodDef implementation = builder.build();
        lambdaMethods.add(implementation);
        for (VariableDef variable : captured) {
            writeExpression(variable);
        }
        MethodHandleDesc implementationHandle = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            owner, implementation.getName(), methodType(implementation));
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(lambdaMetafactory(), lambda.target().getName(),
            methodType(captured.stream().map(this::capturedType).toList(), lambda.type()),
            methodType(lambda.target()), implementationHandle, methodType(lambda.implementation()));
        code.invokedynamic(callSite);
    }

    /**
     * The enclosing method segment of a lambda implementation name. Constructors and static
     * initializers are named {@code <init>} and {@code <clinit>}, which are not valid in a member
     * name, so use the same {@code new} and {@code static} placeholders that javac does.
     */
    /**
     * The type a lambda captures a variable as. `super` is the receiver: the special call the body makes on it is only
     * verified for a value of the class that makes it, not of its superclass.
     */
    private TypeDef capturedType(VariableDef variable) {
        return variable instanceof VariableDef.Super ? objectDef.asTypeDef() : variable.type();
    }

    private static String lambdaOwnerName(MethodDef methodDef) {
        return switch (methodDef.getName()) {
            case MethodDef.CONSTRUCTOR -> "new";
            case "<clinit>" -> "static";
            default -> methodDef.getName();
        };
    }

    private void writeMethodReference(MethodReferenceExpression methodReference) {
        ExpressionDef instance = methodReference.instance();
        if (instance instanceof VariableDef.Super superInstance) {
            // `super::name` is the method of the superclass, which a handle on it would dispatch past: javac writes a
            // lambda that makes the special call, and so does this
            writeLambda(methodReference.type().getLambda().implement((aThis, parameters) -> {
                ExpressionDef.InvokeInstanceMethod call = superInstance.invoke(methodReference.method(), parameters);
                return TypeDef.VOID.equals(methodReference.method().getReturnType()) ? call : call.returning();
            }));
            return;
        }
        ExpressionDef adapter = io.micronaut.sourcegen.bytecode.core.ReferenceAdapters.varargsAdapter(methodReference);
        if (adapter instanceof ExpressionDef.Lambda lambda) {
            // The values of a variable arity call are packed into its array, which no handle does
            writeLambda(lambda);
            return;
        }
        if (instance != null) {
            writeExpression(instance);
            if (!(instance instanceof VariableDef.This)) {
                // A bound reference checks its receiver where it is created, as `target::apply` does in source
                code.dup().invokestatic(ClassDesc.of("java.util.Objects"), "requireNonNull",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object)).pop();
            }
        }
        ClassDesc referencedOwner = classDesc(ObjectDef.getContextualType(objectDef, methodReference.owner()));
        DirectMethodHandleDesc.Kind handleKind;
        MethodHandleDesc handle;
        if (methodReference.isStatic()) {
            handleKind = methodReference.owner().isInterface()
                ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC : DirectMethodHandleDesc.Kind.STATIC;
            handle = MethodHandleDesc.ofMethod(handleKind, referencedOwner, methodReference.method().getName(),
                referencedType(methodReference));
        } else if (methodReference.isConstructor()) {
            // Erased in the scope of the constructor, as its class declares it
            handle = MethodHandleDesc.ofConstructor(referencedOwner,
                referencedType(methodReference).parameterList().toArray(ClassDesc[]::new));
        } else {
            handleKind = methodReference.owner().isInterface()
                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL : DirectMethodHandleDesc.Kind.VIRTUAL;
            handle = MethodHandleDesc.ofMethod(handleKind, referencedOwner, methodReference.method().getName(),
                referencedType(methodReference));
        }
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
            lambdaMetafactory(), methodReference.instantiated().getName(),
            methodType(instance == null ? List.of() : List.of(instance.type()), methodReference.type()),
            methodType(methodReference.target()), handle, methodType(methodReference.instantiated())
        );
        code.invokedynamic(callSite);
    }

    /**
     * The descriptor of a referenced method, erased in the scope of the class and the method declaring it.
     */
    private MethodTypeDesc referencedType(MethodReferenceExpression methodReference) {
        TypeDef owner = ObjectDef.getContextualType(objectDef, methodReference.owner());
        ObjectDef scope = TypeUtils.declaringScope(owner, objectDef, methodReference.method(), enclosingScope);
        return MethodTypeDesc.ofDescriptor(TypeUtils.getMethodDescriptor(scope,
            TypeUtils.inDeclaringScope(methodReference.method(), scope, objectDef), enclosingScope));
    }

    private static DirectMethodHandleDesc lambdaMetafactory() {
        ClassDesc factory = ClassDesc.of("java.lang.invoke.LambdaMetafactory");
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
            ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
        return MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            factory, "metafactory", bootstrapType);
    }

    private List<VariableDef> captureVariables(MethodDef implementation) {
        Set<String> variables = new LinkedHashSet<>(implementation.getParameters().stream()
            .map(ParameterDef::getName).toList());
        List<VariableDef> captured = new ArrayList<>();
        for (StatementDef statement : implementation.getStatements()) {
            captureVariables(statement, variables, captured);
        }
        return captured;
    }

    private void captureVariables(StatementDef statement, Set<String> variables, List<VariableDef> captured) {
        statement.nestedExpressionsStream().forEach(expression -> captureVariables(expression, variables, captured));
    }

    private void captureVariables(ExpressionDef expression, Set<String> variables, List<VariableDef> captured) {
        if (expression instanceof VariableDef variable) {
            String name = captureName(variable);
            if (name != null && variables.add(name)) {
                captured.add(variable);
            }
            if (variable instanceof VariableDef.Field field) {
                captureVariables(field.instance(), variables, captured);
            }
        } else {
            expression.nestedExpressionsStream().forEach(child -> captureVariables(child, variables, captured));
        }
    }

    /**
     * The name a lambda captures a variable by: its own, or {@code this}, {@code super} or {@code exception}; a field
     * is not captured, its instance is.
     */
    @Nullable
    private static String captureName(VariableDef variable) {
        return switch (variable) {
            case VariableDef.Local local -> local.name();
            case VariableDef.MethodParameter parameter -> parameter.name();
            case VariableDef.This _ -> "this";
            case VariableDef.Super _ -> SUPER;
            case VariableDef.ExceptionVar _ -> "exception";
            default -> null;
        };
    }

    private void writeBooleanExpression(ExpressionDef.ConditionExpressionDef condition) {
        var trueLabel = code.newLabel();
        var falseLabel = code.newLabel();
        var end = code.newLabel();
        writeCondition(condition, trueLabel, falseLabel);
        code.labelBinding(trueLabel).loadConstant(1).goto_(end)
            .labelBinding(falseLabel).loadConstant(0).labelBinding(end);
    }

    private void writeReferenceComparison(ExpressionDef left, ExpressionDef right, boolean negate) {
        var trueLabel = code.newLabel();
        var falseLabel = code.newLabel();
        var end = code.newLabel();
        writeReferenceBranch(left, right, trueLabel, falseLabel, negate);
        code.labelBinding(trueLabel).loadConstant(1).goto_(end)
            .labelBinding(falseLabel).loadConstant(0).labelBinding(end);
    }

    private void writeIfElseExpression(ExpressionDef.IfElse ifElse) {
        var elseLabel = code.newLabel();
        var end = code.newLabel();
        writeCondition(ifElse.condition(), null, elseLabel);
        // Both branches must leave the result type on the stack, boxed or unboxed as needed, so
        // that the two paths agree where they join
        writeExpression(new ExpressionDef.Cast(ifElse.type(), ifElse.ifExpression()));
        code.goto_(end).labelBinding(elseLabel);
        writeExpression(new ExpressionDef.Cast(ifElse.type(), ifElse.elseExpression()));
        code.labelBinding(end);
    }

    private void writeExpressionSwitch(ExpressionDef.Switch aSwitch) {
        if (aSwitch.expression().type().equals(TypeDef.STRING)) {
            writeStringExpressionSwitch(aSwitch);
            return;
        }
        writeSwitchSelector(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, ExpressionDef>> bodies = new ArrayList<>();
        Map<Integer, Label> labels = switchLabels(aSwitch.cases(), bodies);
        writeSwitchInstruction(defaultLabel, labels);
        for (Map.Entry<Label, ExpressionDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeCaseValue(aSwitch.type(), body.getValue(), end);
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeCaseValue(aSwitch.type(), aSwitch.defaultCase(), null);
        }
        code.labelBinding(end);
    }

    /**
     * Writes the value a case contributes to a switch expression. A yield case is a statement
     * block, so it is written as one and only jumps to the end of the switch when control can
     * reach there; the ASM backend lowers a yielding return the same way.
     */
    private void writeCaseValue(TypeDef type, ExpressionDef value, @Nullable Label end) {
        if (value instanceof ExpressionDef.SwitchYieldCase yieldCase) {
            writeYieldCase(yieldCase);
            if (end != null) {
                code.goto_(end);
            }
            return;
        }
        writeExpression(new ExpressionDef.Cast(type, value));
        if (end != null) {
            code.goto_(end);
        }
    }

    private void writeStringExpressionSwitch(ExpressionDef.Switch aSwitch) {
        int valueSlot = writeSwitchHash(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, ExpressionDef>> bodies = new ArrayList<>();
        Map<ExpressionDef.Constant, Label> caseLabels = new LinkedHashMap<>();
        aSwitch.cases().forEach((constant, value) -> caseLabels.put(constant, bodyLabel(value, bodies)));

        writeStringDispatch(aSwitch.cases().keySet(), caseLabels, valueSlot, defaultLabel);
        for (Map.Entry<Label, ExpressionDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeCaseValue(aSwitch.type(), body.getValue(), end);
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeCaseValue(aSwitch.type(), aSwitch.defaultCase(), null);
        }
        code.labelBinding(end);
    }

    private void writeCondition(ExpressionDef condition, @Nullable Label trueLabel,
                                @Nullable Label falseLabel) {
        switch (condition) {
            case ExpressionDef.And and -> writeAnd(and, trueLabel, falseLabel);
            case ExpressionDef.Or or -> writeOr(or, trueLabel, falseLabel);
            case ExpressionDef.IsNull isNull ->
                writeUnaryBranch(isNull.expression(), Opcode.IFNULL, trueLabel, falseLabel);
            case ExpressionDef.IsNotNull isNotNull ->
                writeUnaryBranch(isNotNull.expression(), Opcode.IFNONNULL, trueLabel, falseLabel);
            case ExpressionDef.IsTrue isTrue ->
                writeUnaryBranch(isTrue.expression(), IFNE, trueLabel, falseLabel);
            case ExpressionDef.IsFalse isFalse ->
                writeUnaryBranch(isFalse.expression(), IFEQ, trueLabel, falseLabel);
            case ExpressionDef.InstanceOf instanceOf -> {
                writeExpression(instanceOf.expression());
                code.instanceOf(classDesc(instanceOf.instanceType()));
                jump(IFNE, trueLabel, falseLabel);
            }
            case ExpressionDef.EqualsReferentially equals ->
                writeReferenceBranch(equals.instance(), equals.other(), trueLabel, falseLabel, false);
            case ExpressionDef.NotEqualsReferentially notEquals ->
                writeReferenceBranch(notEquals.instance(), notEquals.other(), trueLabel, falseLabel, true);
            case ExpressionDef.ComparisonOperation comparison ->
                writeComparisonBranch(comparison, trueLabel, falseLabel);
            case ExpressionDef.EqualsStructurally equals -> {
                ExpressionDef.ComparisonOperation primitive = primitiveEquals(equals.instance(), equals.other(), false);
                if (primitive != null) {
                    writeComparisonBranch(primitive, trueLabel, falseLabel);
                } else {
                    writeStructuralEquals(equals.instance(), equals.other());
                    jump(IFNE, trueLabel, falseLabel);
                }
            }
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                ExpressionDef.ComparisonOperation primitive = primitiveEquals(notEquals.instance(), notEquals.other(), true);
                if (primitive != null) {
                    writeComparisonBranch(primitive, trueLabel, falseLabel);
                } else {
                    writeStructuralEquals(notEquals.instance(), notEquals.other());
                    jump(IFEQ, trueLabel, falseLabel);
                }
            }
            default -> throw unsupported(condition);
        }
    }

    private void writeUnaryBranch(ExpressionDef operand, Opcode opcode,
                                  @Nullable Label trueLabel, @Nullable Label falseLabel) {
        writeExpression(operand);
        jump(opcode, trueLabel, falseLabel);
    }

    private void writeAnd(ExpressionDef.And and, @Nullable Label trueLabel, @Nullable Label falseLabel) {
        Label right = code.newLabel();
        writeCondition(and.left(), right, falseLabel);
        code.labelBinding(right);
        writeCondition(and.right(), trueLabel, falseLabel);
    }

    private void writeOr(ExpressionDef.Or or, @Nullable Label trueLabel, @Nullable Label falseLabel) {
        // A true left operand must skip the right one. Without a true label (statement context,
        // where the true path is the fall-through) that needs a label of its own.
        Label right = code.newLabel();
        Label leftTrue = trueLabel != null ? trueLabel : code.newLabel();
        writeCondition(or.left(), leftTrue, right);
        code.labelBinding(right);
        writeCondition(or.right(), trueLabel, falseLabel);
        if (trueLabel == null) {
            code.labelBinding(leftTrue);
        }
    }

    /**
     * Structural equality as the ASM writer lowers it: {@code Arrays.equals} or
     * {@code Arrays.deepEquals} for arrays, {@code Objects.equals} otherwise; the static-call
     * lowering boxes primitive operands.
     */
    private void writeStructuralEquals(ExpressionDef left, ExpressionDef right) {
        writeExpression(JavaIdioms.equalsStructurally(left, right));
    }

    /**
     * Structural equality of a primitive operand is the numeric comparison {@code ==}, as the ASM writer lowers it:
     * boxing both floats into {@code Objects.equals} would take a NaN for equal to itself.
     *
     * @return The comparison, the other operand converted to the primitive's type, or {@code null} for two references
     */
    private static ExpressionDef.@Nullable ComparisonOperation primitiveEquals(ExpressionDef left, ExpressionDef right, boolean negate) {
        ExpressionDef.ComparisonOperation.OpType opType = negate
            ? ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO : ExpressionDef.ComparisonOperation.OpType.EQUAL_TO;
        if (left.type().isPrimitive()) {
            return new ExpressionDef.ComparisonOperation(opType, left, right.cast(left.type()));
        }
        if (right.type().isPrimitive()) {
            return new ExpressionDef.ComparisonOperation(opType, left.cast(right.type()), right);
        }
        return null;
    }

    private void writeComparisonBranch(ExpressionDef.ComparisonOperation comparison,
                                       @Nullable Label trueLabel,
                                       @Nullable Label falseLabel) {
        TypeKind leftKind = kind(comparison.left().type());
        writeExpression(comparison.left());
        writeExpression(comparison.right());
        compareAndJump(leftKind, comparison.opType(), trueLabel, falseLabel);
    }

    /**
     * Compares the two values of a kind on the stack and jumps as the comparison answers.
     */
    private void compareAndJump(TypeKind leftKind,
                                ExpressionDef.ComparisonOperation.OpType opType,
                                @Nullable Label trueLabel,
                                @Nullable Label falseLabel) {
        boolean notEqual = opType == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO;
        if (leftKind == TypeKind.REFERENCE) {
            jump(notEqual ? java.lang.classfile.Opcode.IF_ACMPNE : java.lang.classfile.Opcode.IF_ACMPEQ,
                trueLabel, falseLabel);
            return;
        }
        if (leftKind == TypeKind.LONG) {
            code.lcmp();
        } else if (leftKind == TypeKind.FLOAT) {
            compareFloat(opType);
        } else if (leftKind == TypeKind.DOUBLE) {
            compareDouble(opType);
        }
        int operation = switch (opType) {
            case EQUAL_TO -> 0;
            case NOT_EQUAL_TO -> 1;
            case LESS_THAN -> 2;
            case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4;
            case GREATER_THAN_OR_EQUAL -> 5;
        };
        java.lang.classfile.Opcode opcode = switch (operation) {
            case 0 -> java.lang.classfile.Opcode.IFEQ;
            case 1 -> java.lang.classfile.Opcode.IFNE;
            case 2 -> java.lang.classfile.Opcode.IFLT;
            case 3 -> java.lang.classfile.Opcode.IFLE;
            case 4 -> java.lang.classfile.Opcode.IFGT;
            default -> java.lang.classfile.Opcode.IFGE;
        };
        if (leftKind == TypeKind.INT) {
            opcode = switch (operation) {
                case 0 -> java.lang.classfile.Opcode.IF_ICMPEQ;
                case 1 -> java.lang.classfile.Opcode.IF_ICMPNE;
                case 2 -> java.lang.classfile.Opcode.IF_ICMPLT;
                case 3 -> java.lang.classfile.Opcode.IF_ICMPLE;
                case 4 -> java.lang.classfile.Opcode.IF_ICMPGT;
                default -> java.lang.classfile.Opcode.IF_ICMPGE;
            };
        }
        jump(opcode, trueLabel, falseLabel);
    }

    private void writeReferenceBranch(ExpressionDef left, ExpressionDef right,
                                      @Nullable Label trueLabel,
                                      @Nullable Label falseLabel,
                                      boolean negate) {
        ReferenceComparisons.PrimitiveComparison primitive = ReferenceComparisons.primitiveComparison(left, right);
        if (primitive != null) {
            // Two primitives are compared as values, promoted to a common type as javac promotes them
            writeExpression(primitive.left());
            writeConversion(primitive.left().type(), primitive.type());
            writeExpression(primitive.right());
            writeConversion(primitive.right().type(), primitive.type());
            compareAndJump(kind(primitive.type()), negate ? ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO
                : ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, trueLabel, falseLabel);
            return;
        }
        if (left.type().isPrimitive() && right.type().isPrimitive()) {
            // A boolean and a number, which javac does not compare: as the ASM writer compares them
            writeComparisonBranch(new ExpressionDef.ComparisonOperation(negate ? ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO
                : ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, left, right), trueLabel, falseLabel);
            return;
        }
        writeExpression(left);
        writeExpression(right);
        jump(negate ? java.lang.classfile.Opcode.IF_ACMPNE : java.lang.classfile.Opcode.IF_ACMPEQ,
            trueLabel, falseLabel);
    }

    private void jump(java.lang.classfile.Opcode opcode, @Nullable Label trueLabel, @Nullable Label falseLabel) {
        if (trueLabel != null) {
            code.branch(opcode, trueLabel);
            if (falseLabel != null) {
                code.goto_(falseLabel);
            }
        } else if (falseLabel != null) {
            code.branch(inverse(opcode), falseLabel);
        }
    }

    private static java.lang.classfile.Opcode inverse(java.lang.classfile.Opcode opcode) {
        return switch (opcode) {
            case IFEQ -> java.lang.classfile.Opcode.IFNE;
            case IFNE -> java.lang.classfile.Opcode.IFEQ;
            case IFLT -> java.lang.classfile.Opcode.IFGE;
            case IFGE -> java.lang.classfile.Opcode.IFLT;
            case IFGT -> java.lang.classfile.Opcode.IFLE;
            case IFLE -> java.lang.classfile.Opcode.IFGT;
            case IF_ICMPEQ -> java.lang.classfile.Opcode.IF_ICMPNE;
            case IF_ICMPNE -> java.lang.classfile.Opcode.IF_ICMPEQ;
            case IF_ICMPLT -> java.lang.classfile.Opcode.IF_ICMPGE;
            case IF_ICMPGE -> java.lang.classfile.Opcode.IF_ICMPLT;
            case IF_ICMPGT -> java.lang.classfile.Opcode.IF_ICMPLE;
            case IF_ICMPLE -> java.lang.classfile.Opcode.IF_ICMPGT;
            case IF_ACMPEQ -> java.lang.classfile.Opcode.IF_ACMPNE;
            case IF_ACMPNE -> java.lang.classfile.Opcode.IF_ACMPEQ;
            case IFNULL -> java.lang.classfile.Opcode.IFNONNULL;
            case IFNONNULL -> java.lang.classfile.Opcode.IFNULL;
            default -> throw new IllegalArgumentException("Not a conditional opcode: " + opcode);
        };
    }

    private void writeMath(ExpressionDef.MathBinaryOperation math) {
        TypeKind kind = kind(math.type());
        switch (math.opType()) {
            case ADDITION -> arithmetic(kind, 0);
            case SUBTRACTION -> arithmetic(kind, 1);
            case MULTIPLICATION -> arithmetic(kind, 2);
            case DIVISION -> arithmetic(kind, 3);
            case MODULUS -> arithmetic(kind, 4);
            case BITWISE_AND -> arithmetic(kind, 5);
            case BITWISE_OR -> arithmetic(kind, 6);
            case BITWISE_XOR -> arithmetic(kind, 7);
            case BITWISE_LEFT_SHIFT -> arithmetic(kind, 8);
            case BITWISE_RIGHT_SHIFT -> arithmetic(kind, 9);
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> arithmetic(kind, 10);
            default -> throw unsupported(math);
        }
    }

    /**
     * Narrows the int the JVM computes a byte, short or char operation in to the type of the operation, as a cast of
     * the operation does in Java: {@code (byte) (a + b)}.
     */
    private void narrow(TypeDef type) {
        TypeKind exact = exactKind(type);
        if (exact == TypeKind.BYTE || exact == TypeKind.SHORT || exact == TypeKind.CHAR) {
            code.conversion(TypeKind.INT, exact);
        }
    }

    private static boolean isShift(ExpressionDef.MathBinaryOperation.OpType opType) {
        return opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
    }

    private void compareFloat(ExpressionDef.ComparisonOperation.OpType opType) {
        if (opType == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN
            || opType == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL) {
            code.fcmpl();
        } else {
            code.fcmpg();
        }
    }

    private void compareDouble(ExpressionDef.ComparisonOperation.OpType opType) {
        if (opType == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN
            || opType == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL) {
            code.dcmpl();
        } else {
            code.dcmpg();
        }
    }

    private void arithmetic(TypeKind kind, int operation) {
        switch (kind) {
            case INT -> intArithmetic(operation);
            case LONG -> longArithmetic(operation);
            case FLOAT -> arithmeticFloat(operation);
            case DOUBLE -> arithmeticDouble(operation);
            default -> throw new UnsupportedOperationException("Unsupported arithmetic kind: " + kind);
        }
    }

    private void intArithmetic(int operation) {
        switch (operation) {
            case 0 -> code.iadd();
            case 1 -> code.isub();
            case 2 -> code.imul();
            case 3 -> code.idiv();
            case 4 -> code.irem();
            case 5 -> code.iand();
            case 6 -> code.ior();
            case 7 -> code.ixor();
            case 8 -> code.ishl();
            case 9 -> code.ishr();
            default -> code.iushr();
        }
    }

    private void longArithmetic(int operation) {
        switch (operation) {
            case 0 -> code.ladd();
            case 1 -> code.lsub();
            case 2 -> code.lmul();
            case 3 -> code.ldiv();
            case 4 -> code.lrem();
            case 5 -> code.land();
            case 6 -> code.lor();
            case 7 -> code.lxor();
            case 8 -> code.lshl();
            case 9 -> code.lshr();
            default -> code.lushr();
        }
    }

    private void arithmeticFloat(int operation) {
        switch (operation) {
            case 0 -> code.fadd();
            case 1 -> code.fsub();
            case 2 -> code.fmul();
            case 3 -> code.fdiv();
            default -> code.frem();
        }
    }

    private void arithmeticDouble(int operation) {
        switch (operation) {
            case 0 -> code.dadd();
            case 1 -> code.dsub();
            case 2 -> code.dmul();
            case 3 -> code.ddiv();
            default -> code.drem();
        }
    }

    private void writeConcat(ExpressionDef.StringConcatenation concat) {
        List<ExpressionDef> parts = new ArrayList<>();
        flattenConcat(concat, parts);
        List<ExpressionDef> dynamic = parts.stream().filter(part -> !(part instanceof ExpressionDef.Constant constant
            && (constant.type().isPrimitive() || constant.type().equals(TypeDef.STRING)))).toList();
        StringBuilder template = new StringBuilder();
        for (ExpressionDef part : parts) {
            if (dynamic.contains(part)) {
                template.append('\u0001');
                writeExpression(part);
            } else {
                template.append(((ExpressionDef.Constant) part).value());
            }
        }
        if (dynamic.isEmpty()) {
            code.ldc(code.constantPool().stringEntry(template.toString()));
            return;
        }
        ClassDesc factory = ClassDesc.of("java.lang.invoke.StringConcatFactory");
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
            ConstantDescs.CD_String, ConstantDescs.CD_Object.arrayType());
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            factory, "makeConcatWithConstants", bootstrapType);
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(bootstrap, "makeConcatWithConstants",
            MethodTypeDesc.of(ConstantDescs.CD_String, dynamic.stream().map(part -> classDesc(part.type())).toList()), template.toString());
        code.invokedynamic(callSite);
    }

    private static void flattenConcat(ExpressionDef expression, List<ExpressionDef> parts) {
        if (expression instanceof ExpressionDef.StringConcatenation concat) {
            flattenConcat(concat.left(), parts);
            flattenConcat(concat.right(), parts);
        } else {
            parts.add(expression);
        }
    }

    // The model permits a Super variable only in the static helper context; the non-static branch
    // is retained as a defensive failure for malformed model trees.
    @SuppressWarnings("java:S2583")
    private void writeVariable(VariableDef variable) {
        switch (variable) {
            case VariableDef.This thisVariable -> {
                if (methodDef.getModifiers().contains(Modifier.STATIC)) {
                    load("this", thisVariable.type());
                } else {
                    code.aload(code.receiverSlot());
                }
            }
            case VariableDef.MethodParameter parameter -> load(parameter.name(), parameter.type());
            case VariableDef.Local local -> load(local.name(), local.type());
            case VariableDef.Field field -> {
                writeExpression(field.instance());
                code.getfield(classDesc(field.declaringType()), field.name(), memberDesc(field.declaringType(), field.name(), field.type()));
            }
            case VariableDef.StaticField field -> code.getstatic(classDesc(field.ownerType()), field.name(),
                memberDesc(field.ownerType(), field.name(), field.type()));
            case VariableDef.ExceptionVar exception -> load(EXCEPTION_NAME, exception.type());
            case VariableDef.Super superVariable -> {
                if (methodDef.getModifiers().contains(Modifier.STATIC)) {
                    // A lambda body captures the enclosing receiver as a parameter
                    load(SUPER, superVariable.type());
                } else {
                    // `super` is the current receiver; only the dispatch differs
                    code.aload(code.receiverSlot());
                }
            }
        }
    }

    private void load(String name, TypeDef type) {
        Local local = local(name);
        code.loadLocal(kind(type), local.slot());
    }

    private Local local(String name) {
        Local local = locals.get(name);
        if (local == null) {
            throw new IllegalStateException("Unknown local variable: " + name);
        }
        return local;
    }

    private void writeCast(ExpressionDef.Cast cast) {
        // Only the last cast of a chain is written, unless an inner one converts or checks what it does not
        ExpressionDef expression = Conversions.castOperand(cast, objectDef, methodDef, enclosingScope);
        writeExpression(expression);
        if (expression instanceof ExpressionDef.Constant constant && constant.value() == null) {
            // null is assignable to every reference type, so it needs no cast; to a primitive it is unboxed, which
            // throws, as javac unboxes it
            if (cast.type() instanceof TypeDef.Primitive primitive && !primitive.equals(TypeDef.VOID)) {
                writeConversion(primitive.wrapperType(), primitive);
            }
            return;
        }
        writeConversion(expression.type(), cast.type());
    }

    /**
     * Emits the conversion {@link Conversions#plan} plans, leaving out a checkcast to {@code Object} or to the type the
     * value already has. These casts are inserted on every argument and every return, so leaving the redundant ones
     * out keeps generated methods well inside the 64KB limit.
     */
    private void writeConversion(TypeDef source, TypeDef target) {
        for (Conversions.Step step : Conversions.plan(source, target, objectDef, methodDef, Conversions.Checkcasts.ERASURE, enclosingScope)) {
            switch (step) {
                case Conversions.CheckCast checkCast -> code.checkcast(ClassDesc.ofDescriptor(checkCast.descriptor()));
                case Conversions.Unboxing unboxing -> {
                    ClassDesc owner = ClassDesc.ofInternalName(unboxing.owner());
                    code.checkcast(owner).invokevirtual(owner, unboxing.method(), MethodTypeDesc.ofDescriptor(unboxing.methodDescriptor()));
                }
                case Conversions.PrimitiveConversion conversion -> code.conversion(primitiveKind(conversion.from()), primitiveKind(conversion.to()));
                case Conversions.Box box -> code.invokestatic(ClassDesc.ofInternalName(box.owner()), "valueOf",
                    MethodTypeDesc.ofDescriptor(box.methodDescriptor()));
            }
        }
    }

    private static TypeKind primitiveKind(TypeDef.Primitive primitive) {
        return TypeKind.fromDescriptor(TypeUtils.getDescriptor(primitive, null, EnclosingScope.NONE));
    }

    private void box(TypeKind kind) {
        ClassDesc wrapper = wrapper(kind);
        code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, kindClass(kind)));
    }

    private static ClassDesc wrapper(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case BYTE -> ConstantDescs.CD_Byte;
            case CHAR -> ConstantDescs.CD_Character;
            case SHORT -> ConstantDescs.CD_Short;
            case INT -> ConstantDescs.CD_Integer;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            default -> throw new IllegalArgumentException("Not primitive: " + kind);
        };
    }

    private static ClassDesc kindClass(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case CHAR -> ConstantDescs.CD_char;
            case SHORT -> ConstantDescs.CD_short;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case VOID -> ConstantDescs.CD_void;
            default -> throw new IllegalArgumentException("Not a primitive: " + kind);
        };
    }

    private void writeConstant(ExpressionDef.Constant constant) {
        Object value = constant.value();
        if (value == null) {
            code.aconst_null();
        } else {
            writeNonNullConstant(constant, value);
        }
    }

    private void writeNonNullConstant(ExpressionDef.Constant constant, Object value) {
        if (value.getClass().isArray()) {
            writeConstantArray(value);
            return;
        }
        switch (value) {
            case String string -> code.ldc(code.constantPool().stringEntry(string));
            case Class<?> type -> code.ldc(ClassDesc.ofDescriptor(type.descriptorString().replace('.', '/')));
            case TypeDef type -> code.ldc(classDesc(type));
            case Enum<?> anEnum -> {
                ClassDesc type = ClassDesc.of(anEnum.getDeclaringClass().getName());
                code.getstatic(type, anEnum.name(), type);
            }
            case Character character -> writeNumericConstant(constant, (int) character.charValue(), TypeKind.CHAR);
            case Boolean booleanValue -> writeNumericConstant(constant, booleanValue ? 1 : 0, TypeKind.BOOLEAN);
            case Number number -> writeNumericConstant(constant, number, valueKind(number));
            default -> throw unsupported(constant);
        }
    }

    /**
     * An array constant is lowered as the initialized array it describes; its elements are
     * constants in turn.
     */
    private void writeConstantArray(Object value) {
        TypeDef componentType = TypeDef.of(value.getClass().getComponentType());
        int length = java.lang.reflect.Array.getLength(value);
        List<ExpressionDef> elements = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            elements.add(ExpressionDef.constant(java.lang.reflect.Array.get(value, i)));
        }
        writeExpression(componentType.array().instantiate(elements));
    }

    /**
     * Pushes a numeric, character or boolean constant. The declared type wins over the value's own
     * class, so a constant carrying an {@code Integer} but declared {@code long} is pushed as a
     * long rather than an int.
     */
    private void writeNumericConstant(ExpressionDef.Constant constant, Number value, TypeKind valueKind) {
        TypeKind declared = exactKind(constant.type());
        TypeKind pushed = declared == TypeKind.REFERENCE ? valueKind : declared;
        switch (pushed) {
            case LONG -> code.loadConstant(value.longValue());
            case FLOAT -> code.loadConstant(value.floatValue());
            case DOUBLE -> code.loadConstant(value.doubleValue());
            case BYTE -> code.loadConstant((int) value.byteValue());
            case SHORT -> code.loadConstant((int) value.shortValue());
            default -> code.loadConstant(value.intValue());
        }
        boxConstant(constant, pushed);
    }

    private static TypeKind valueKind(Number value) {
        return switch (value) {
            case Long _ -> TypeKind.LONG;
            case Float _ -> TypeKind.FLOAT;
            case Double _ -> TypeKind.DOUBLE;
            case Byte _ -> TypeKind.BYTE;
            case Short _ -> TypeKind.SHORT;
            default -> TypeKind.INT;
        };
    }

    /**
     * A constant declared with a reference type, such as {@code constant(Integer.valueOf(1))} or a
     * builder default typed by its property, has to leave the wrapper on the stack, not the raw
     * primitive. A wrapper of another kind converts first, as in {@code Long} for an {@code int} value.
     */
    private void boxConstant(ExpressionDef.Constant constant, TypeKind valueKind) {
        ClassDesc declared = classDesc(constant.type());
        if (declared.isPrimitive()) {
            return;
        }
        TypeKind targetKind = wrapperKind(declared);
        if (targetKind == null) {
            box(valueKind);
            return;
        }
        if (targetKind != valueKind) {
            code.conversion(valueKind, targetKind);
        }
        box(targetKind);
    }

    @Nullable
    private static TypeKind wrapperKind(ClassDesc type) {
        if (type.equals(ConstantDescs.CD_Integer)) {
            return TypeKind.INT;
        }
        if (type.equals(ConstantDescs.CD_Long)) {
            return TypeKind.LONG;
        }
        if (type.equals(ConstantDescs.CD_Float)) {
            return TypeKind.FLOAT;
        }
        if (type.equals(ConstantDescs.CD_Double)) {
            return TypeKind.DOUBLE;
        }
        if (type.equals(ConstantDescs.CD_Byte)) {
            return TypeKind.BYTE;
        }
        if (type.equals(ConstantDescs.CD_Short)) {
            return TypeKind.SHORT;
        }
        if (type.equals(ConstantDescs.CD_Character)) {
            return TypeKind.CHAR;
        }
        if (type.equals(ConstantDescs.CD_Boolean)) {
            return TypeKind.BOOLEAN;
        }
        return null;
    }

    private void writeInvocation(ExpressionDef instance, MethodDef method, List<? extends ExpressionDef> values) {
        InvocationPlan plan = InvocationPlan.ofInstance(instance, method, values, objectDef, methodDef, enclosingScope);
        writeExpression(instance);
        if (plan.receiver() == InvocationPlan.Receiver.DISCARD) {
            code.pop();
        } else if (plan.receiver() == InvocationPlan.Receiver.CAST) {
            code.checkcast(ClassDesc.ofDescriptor(plan.receiverCast()));
        }
        writeInvocation(plan);
    }

    private void writeStaticInvocation(ClassTypeDef classDef, MethodDef method, List<? extends ExpressionDef> values) {
        writeInvocation(InvocationPlan.ofStatic(classDef, method, values, objectDef, methodDef, enclosingScope));
    }

    private void writeSuperConstructor(StatementDef.InvokeSuperConstructor invocation) {
        code.aload(code.receiverSlot());
        writeInvocation(InvocationPlan.ofSuperConstructor(invocation, objectDef, methodDef, enclosingScope));
    }

    /**
     * Writes the arguments of an invocation, converted to the parameters it plans, and emits it and the conversion of
     * its result. The receiver, if any, is written first as the plan says.
     */
    private void writeInvocation(InvocationPlan plan) {
        List<TypeDef> parameterTypes = plan.parameterTypes();
        for (int i = 0; i < plan.values().size(); i++) {
            writeArgument(plan.values().get(i), parameterTypes.get(i));
        }
        ClassDesc methodOwner = ClassDesc.ofDescriptor(plan.ownerDescriptor());
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(plan.descriptor());
        switch (plan.kind()) {
            case STATIC -> code.invokestatic(methodOwner, plan.name(), type, plan.ownerInterface());
            case SPECIAL -> code.invokespecial(methodOwner, plan.name(), type, plan.ownerInterface());
            case INTERFACE -> code.invokeinterface(methodOwner, plan.name(), type);
            default -> code.invokevirtual(methodOwner, plan.name(), type);
        }
        InvocationPlan.ResultConversion result = plan.result();
        if (result != null) {
            writeConversion(result.from(), result.to());
        }
    }

    private void popIfNeeded(TypeDef type) {
        if (!type.equals(TypeDef.VOID)) {
            if (kind(type) == TypeKind.LONG || kind(type) == TypeKind.DOUBLE) {
                code.pop2();
            } else {
                code.pop();
            }
        }
    }

    private void writeArgument(ExpressionDef expression, TypeDef expectedType) {
        if (TypeUtils.packedInterfaceArray(expression, expectedType)) {
            // A variable arity tail packed into an array of interfaces is passed as it is, as javac passes it
            writeExpression(expression);
            return;
        }
        writeExpression(new ExpressionDef.Cast(expectedType, expression));
    }

    private void store(TypeDef type, int slot) {
        code.storeLocal(kind(type), slot);
    }

    private void writeNewArray(TypeDef.Array type) {
        // newarray needs the exact element kind; a boolean[] is not an int[]
        TypeDef element = elementType(type);
        TypeKind componentKind = exactKind(element);
        if (componentKind == TypeKind.REFERENCE) {
            code.anewarray(classDesc(element));
        } else {
            code.newarray(componentKind);
        }
    }

    /**
     * The type of one element of the array. {@link TypeDef.Array} counts every dimension at once,
     * so the elements of a two-dimensional array are themselves arrays.
     */
    private static TypeDef elementType(TypeDef.Array type) {
        return type.dimensions() > 1
            ? new TypeDef.Array(type.componentType(), type.dimensions() - 1, false)
            : type.componentType();
    }

    /**
     * The kind a value of the type occupies on the stack and in locals: boolean, byte, char and
     * short all load and store as int.
     */
    private TypeKind kind(TypeDef type) {
        return exactKind(type).asLoadable();
    }

    /**
     * The declared kind of the type, needed wherever the JVM distinguishes the small integral
     * types: boxing, array creation and element access, and narrowing conversions.
     */
    private TypeKind exactKind(TypeDef type) {
        return TypeKind.fromDescriptor(TypeUtils.getDescriptor(type, objectDef, enclosingScope));
    }

    private MethodTypeDesc methodType(List<TypeDef> parameters, TypeDef returnType) {
        return MethodTypeDesc.of(classDesc(returnType), parameters.stream().map(this::classDesc).toList());
    }

    private MethodTypeDesc methodType(MethodDef method) {
        return methodType(method.getParameters().stream().map(ParameterDef::getType).toList(), method.getReturnType());
    }

    /**
     * A type of a member, erased in the scope of the class declaring it, whose variables the caller's can shadow.
     */
    private ClassDesc memberDesc(TypeDef owner, String name, TypeDef type) {
        return ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, TypeUtils.fieldScope(owner, objectDef, name), enclosingScope));
    }

    private ClassDesc classDesc(TypeDef type) {
        // A value of the body is typed in the scope of the method, whose variables shadow the class's
        return ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, objectDef, methodDef, enclosingScope));
    }

    private static UnsupportedOperationException unsupported(Object value) {
        return new UnsupportedOperationException("Unsupported direct JDK lowering: " + value.getClass().getName());
    }

    /**
     * A switch yield case being written.
     *
     * @param type     The type the case yields
     * @param slot     The local the yielded value is held in
     * @param end      The label the value is loaded at
     * @param cleanups The number of finally blocks pending when the case was entered - only the
     *                 ones opened inside the case run when it yields
     */
    private record YieldTarget(TypeDef type, int slot, Label end, int cleanups) {
    }

    private record Local(TypeDef type, int slot) {
    }

    /**
     * A catch block of a try being written.
     *
     * @param aCatch The catch block
     * @param label  The label of its handler
     */
    private record CatchHandler(StatementDef.Try.Catch aCatch, Label label) {
    }

    /**
     * The code a return or a yield writes on its way out of a try or synchronized statement being
     * written.
     *
     * @param code  Writes the finally block, or the release of the monitor
     * @param inGap Whether the code is written in the gap: a finally block is, the release of the monitor
     *              stays protected as javac does, and the gap follows it
     * @param gaps  The gaps the returns and the yields leaving the statement opened
     */
    private record Cleanup(Runnable code, boolean inGap, List<Gap> gaps) {

        private Cleanup(Runnable code, boolean inGap) {
            this(code, inGap, new ArrayList<>());
        }
    }

    /**
     * The code a return or a yield writes on its way out of a try or synchronized statement: from the
     * copy of the finally block, or from past the release of the monitor, to past the instruction that
     * leaves. As javac does, the statement leaves the gap out of its exception ranges, so that it does
     * not handle an exception thrown by its own finally block, or release its monitor twice.
     */
    private static final class Gap {

        private final Position start;
        private @Nullable Position end;

        private Gap(Position start) {
            this.start = start;
        }
    }

    /**
     * A position in the code.
     *
     * @param label        The label bound there
     * @param instructions The number of instructions written before it
     */
    private record Position(Label label, int instructions) {
    }

    /**
     * A range of code a statement protects.
     *
     * @param start The start of the range
     * @param end   The end of the range
     * @param gaps  The gaps the statement leaves out of the range, in the order of the code
     */
    private record Range(Position start, Position end, List<Gap> gaps) {
    }

    /**
     * Counts the instructions written, which tells whether a range of code protects any.
     */
    private static final class InstructionCounter implements CodeTransform {

        private int count;

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof Instruction) {
                count++;
            }
            builder.with(element);
        }
    }
}
