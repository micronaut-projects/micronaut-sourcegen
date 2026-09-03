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

import io.micronaut.sourcegen.bytecode.core.TypeUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

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
    private final List<Runnable> cleanups = new ArrayList<>();
    private final List<MethodDef> lambdaMethods = new ArrayList<>();
    private final MethodDef methodDef;

    private JdkMethodWriter(CodeBuilder code, ObjectDef objectDef, MethodDef methodDef, ClassDesc owner) {
        this.code = code;
        this.objectDef = objectDef;
        this.owner = owner;
        this.methodDef = methodDef;
        for (int i = 0; i < methodDef.getParameters().size(); i++) {
            var parameter = methodDef.getParameters().get(i);
            locals.put(parameter.getName(), new Local(parameter.getType(), code.parameterSlot(i)));
        }
    }

    static void write(CodeBuilder code, ObjectDef objectDef, MethodDef methodDef, ClassDesc owner) {
        new JdkMethodWriter(code, objectDef, methodDef, owner).writeStatements(methodDef.getStatements());
    }

    static JdkMethodWriter create(CodeBuilder code, ObjectDef objectDef, MethodDef methodDef, ClassDesc owner) {
        return new JdkMethodWriter(code, objectDef, methodDef, owner);
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
                    classDesc(putField.field().type()));
            }
            case StatementDef.PutStaticField putStaticField -> {
                writeExpression(new ExpressionDef.Cast(putStaticField.field().type(), putStaticField.expression()));
                code.putstatic(classDesc(putStaticField.field().ownerType()), putStaticField.field().name(),
                    classDesc(putStaticField.field().type()));
            }
            case ExpressionDef.InvokeInstanceMethod invoke -> {
                writeInvocation(invoke.instance(), invoke.method(), invoke.values(), invoke.isDefault());
                popIfNeeded(invoke.method().getReturnType());
            }
            case ExpressionDef.InvokeStaticMethod invoke -> {
                writeStaticInvocation(invoke.classDef(), invoke.method(), invoke.values());
                popIfNeeded(invoke.method().getReturnType());
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
                boolean thenCompletes = canCompleteNormally(ifElse.statement());
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

    private void writeCleanups() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
    }

    private void writeTry(StatementDef.Try aTry) {
        StatementDef finallyStatement = aTry.finallyStatement();
        Label tryStart = code.newLabel();
        Label tryEnd = code.newLabel();
        Label end = code.newLabel();
        Label finallyHandler = finallyStatement == null ? null : code.newLabel();
        List<CatchHandler> handlers = registerCatchHandlers(aTry, tryStart, tryEnd);
        if (finallyHandler != null) {
            registerFinallyHandlers(tryStart, tryEnd, finallyHandler, handlers);
        }

        code.labelBinding(tryStart);
        writeTryBody(aTry.statement(), finallyStatement);
        code.labelBinding(tryEnd);
        if (canCompleteNormally(aTry.statement())) {
            writeFinally(finallyStatement);
            code.goto_(end);
        }

        writeCatchHandlers(handlers, finallyStatement, end);
        if (finallyHandler != null) {
            writeFinallyHandler(finallyHandler, finallyStatement);
        }
        code.labelBinding(end);
    }

    private List<CatchHandler> registerCatchHandlers(StatementDef.Try aTry, Label tryStart, Label tryEnd) {
        List<CatchHandler> handlers = new ArrayList<>();
        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            Label handler = code.newLabel();
            handlers.add(new CatchHandler(aCatch, handler));
            code.exceptionCatch(tryStart, tryEnd, handler, classDesc(aCatch.exception()));
        }
        return handlers;
    }

    private void registerFinallyHandlers(Label tryStart, Label tryEnd, Label finallyHandler,
                                         List<CatchHandler> handlers) {
        code.exceptionCatchAll(tryStart, tryEnd, finallyHandler);
        for (CatchHandler handler : handlers) {
            handler.protectedEnd = code.newLabel();
            code.exceptionCatchAll(handler.label, handler.protectedEnd, finallyHandler);
        }
    }

    private void writeTryBody(StatementDef statement, @Nullable StatementDef finallyStatement) {
        addCleanup(finallyStatement);
        writeStatement(statement);
        removeCleanup(finallyStatement);
    }

    private void writeCatchHandlers(List<CatchHandler> handlers, @Nullable StatementDef finallyStatement, Label end) {
        for (CatchHandler handler : handlers) {
            code.labelBinding(handler.label);
            int slot = code.allocateLocal(TypeKind.REFERENCE);
            code.storeLocal(TypeKind.REFERENCE, slot);
            locals.put(EXCEPTION_NAME, new Local(handler.aCatch.exception(), slot));
            addCleanup(finallyStatement);
            writeStatement(handler.aCatch.statement());
            removeCleanup(finallyStatement);
            locals.remove(EXCEPTION_NAME);
            if (handler.protectedEnd != null) {
                code.labelBinding(handler.protectedEnd);
            }
            if (canCompleteNormally(handler.aCatch.statement())) {
                writeFinally(finallyStatement);
                code.goto_(end);
            }
        }
    }

    private void writeFinallyHandler(Label finallyHandler, @Nullable StatementDef finallyStatement) {
        StatementDef requiredFinally = Objects.requireNonNull(finallyStatement);
        code.labelBinding(finallyHandler);
        int slot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, slot);
        writeStatements(requiredFinally.flatten());
        code.loadLocal(TypeKind.REFERENCE, slot).athrow();
    }

    private void addCleanup(@Nullable StatementDef finallyStatement) {
        if (finallyStatement != null) {
            cleanups.add(() -> writeStatements(finallyStatement.flatten()));
        }
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

    private void writeSynchronized(StatementDef.Synchronized synchronizedStatement) {
        writeExpression(synchronizedStatement.monitor());
        int monitorSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, monitorSlot);
        code.loadLocal(TypeKind.REFERENCE, monitorSlot).monitorenter();
        Label start = code.newLabel();
        Label end = code.newLabel();
        Label handler = code.newLabel();
        Label complete = code.newLabel();
        code.exceptionCatchAll(start, end, handler);
        code.labelBinding(start);
        Runnable exit = () -> code.loadLocal(TypeKind.REFERENCE, monitorSlot).monitorexit();
        cleanups.add(exit);
        writeStatement(synchronizedStatement.statement());
        cleanups.removeLast();
        code.labelBinding(end);
        if (canCompleteNormally(synchronizedStatement.statement())) {
            exit.run();
            code.goto_(complete);
        }
        code.labelBinding(handler);
        int exceptionSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, exceptionSlot);
        exit.run();
        code.loadLocal(TypeKind.REFERENCE, exceptionSlot).athrow();
        code.labelBinding(complete);
    }

    private void writeSwitch(StatementDef.Switch aSwitch) {
        if (aSwitch.expression().type().equals(TypeDef.STRING)) {
            writeStringSwitch(aSwitch);
            return;
        }
        writeExpression(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, StatementDef>> bodies = new ArrayList<>();
        Map<Integer, Label> labels = switchLabels(aSwitch.cases(), bodies);
        writeSwitchInstruction(defaultLabel, labels);
        for (Map.Entry<Label, StatementDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeStatement(body.getValue());
            if (canCompleteNormally(body.getValue())) {
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
        writeExpression(aSwitch.expression());
        int valueSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, valueSlot);
        code.loadLocal(TypeKind.REFERENCE, valueSlot)
            .invokevirtual(ConstantDescs.CD_String, HASH_CODE, MethodTypeDesc.of(ConstantDescs.CD_int));
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        Map<Integer, Label> hashLabels = new LinkedHashMap<>();
        Map<Integer, ExpressionDef.Constant> constants = new HashMap<>();
        for (ExpressionDef.Constant constant : aSwitch.cases().keySet()) {
            int key = switchKey(constant);
            if (constants.put(key, constant) != null) {
                throw new UnsupportedOperationException("String switch hash collision");
            }
            hashLabels.put(key, code.newLabel());
        }
        writeSwitchInstruction(defaultLabel, hashLabels);
        for (Map.Entry<Integer, Label> entry : hashLabels.entrySet()) {
            ExpressionDef.Constant constant = Objects.requireNonNull(constants.get(entry.getKey()));
            code.labelBinding(entry.getValue())
                .loadLocal(TypeKind.REFERENCE, valueSlot)
                .loadConstant((String) constant.value())
                .invokevirtual(ConstantDescs.CD_String, EQUALS,
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object))
                .branch(IFEQ, defaultLabel);
            StatementDef statement = aSwitch.cases().get(constant);
            writeStatement(Objects.requireNonNull(statement));
            if (canCompleteNormally(statement)) {
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
            T body = Objects.requireNonNull(entry.getValue(), "Switch case cannot be null");
            Label label = null;
            for (Map.Entry<Label, T> existing : bodies) {
                if (existing.getValue() == body) {
                    label = existing.getKey();
                    break;
                }
            }
            if (label == null) {
                label = code.newLabel();
                bodies.add(Map.entry(label, body));
            }
            labels.put(switchKey(entry.getKey()), label);
        }
        return labels;
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

    private static int switchKey(ExpressionDef.Constant constant) {
        if (constant.value() instanceof Integer integer) {
            return integer;
        }
        if (constant.value() instanceof String string) {
            return string.hashCode();
        }
        throw new UnsupportedOperationException("Unsupported switch constant: " + constant.value());
    }

    /**
     * Whether control can reach the end of a statement, following JLS 14.22. Being wrong in the
     * "cannot complete" direction is not just a verifier error: an if/else then-branch judged as
     * non-completing gets no jump over the else-branch and falls through into it.
     *
     * @param statement The statement
     * @return {@code true} if the statement can complete normally
     */
    static boolean canCompleteNormally(StatementDef statement) {
        List<StatementDef> statements = statement.flatten();
        if (statements.isEmpty()) {
            return true;
        }
        StatementDef last = statements.getLast();
        if (last instanceof StatementDef.IfElse ifElse) {
            return canCompleteNormally(ifElse.statement()) || canCompleteNormally(ifElse.elseStatement());
        }
        if (last instanceof StatementDef.Try aTry) {
            boolean bodyOrCatchCompletes = canCompleteNormally(aTry.statement())
                || aTry.catches().stream().anyMatch(aCatch -> canCompleteNormally(aCatch.statement()));
            return bodyOrCatchCompletes
                && (aTry.finallyStatement() == null || canCompleteNormally(aTry.finallyStatement()));
        }
        if (last instanceof StatementDef.Synchronized synchronizedStatement) {
            return canCompleteNormally(synchronizedStatement.statement());
        }
        if (last instanceof StatementDef.Switch aSwitch) {
            return aSwitch.defaultCase() == null
                || canCompleteNormally(aSwitch.defaultCase())
                || aSwitch.cases().values().stream().anyMatch(JdkMethodWriter::canCompleteNormally);
        }
        return !(last instanceof StatementDef.Return || last instanceof StatementDef.Throw);
    }

    void writeExpression(ExpressionDef expression) {
        switch (expression) {
            case ExpressionDef.Constant constant -> writeConstant(constant);
            case VariableDef variable -> writeVariable(variable);
            case ExpressionDef.Cast cast -> writeCast(cast);
            case ExpressionDef.NewInstance newInstance -> {
                ClassDesc type = classDesc(newInstance.type());
                code.new_(type).dup();
                for (int i = 0; i < newInstance.values().size(); i++) {
                    writeArgument(newInstance.values().get(i), newInstance.parameterTypes().get(i));
                }
                code.invokespecial(type, MethodDef.CONSTRUCTOR, methodType(newInstance.parameterTypes(), TypeDef.VOID));
            }
            case ExpressionDef.InvokeInstanceMethod invoke -> writeInvocation(invoke.instance(), invoke.method(), invoke.values(), invoke.isDefault());
            case ExpressionDef.InvokeStaticMethod invoke -> writeStaticInvocation(invoke.classDef(), invoke.method(), invoke.values());
            case ExpressionDef.MathBinaryOperation math -> {
                writeExpression(math.left());
                writeExpression(math.right());
                if (kind(math.type()) == TypeKind.LONG && isShift(math.opType())) {
                    code.l2i();
                }
                writeMath(math);
            }
            case ExpressionDef.MathUnaryOperation math -> {
                writeExpression(math.expression());
                if (math.opType() == ExpressionDef.MathUnaryOperation.OpType.NEGATE) {
                    switch (kind(math.type())) {
                        case INT -> code.ineg();
                        case LONG -> code.lneg();
                        case FLOAT -> code.fneg();
                        case DOUBLE -> code.dneg();
                        default -> throw unsupported(expression);
                    }
                }
            }
            case ExpressionDef.StringConcatenation concat -> writeConcat(concat);
            case ExpressionDef.Lambda lambda -> writeLambda(lambda);
            case MethodReferenceExpression methodReference -> writeMethodReference(methodReference);
            case ExpressionDef.IfElse ifElse -> writeIfElseExpression(ifElse);
            case ExpressionDef.Switch aSwitch -> writeExpressionSwitch(aSwitch);
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
            case ExpressionDef.InstanceOf instanceOf -> {
                writeExpression(instanceOf.expression());
                code.instanceOf(classDesc(instanceOf.instanceType()));
            }
            case ExpressionDef.EqualsReferentially equals -> writeReferenceComparison(equals.instance(), equals.other(), false);
            case ExpressionDef.NotEqualsReferentially notEquals -> writeReferenceComparison(notEquals.instance(), notEquals.other(), true);
            case ExpressionDef.EqualsStructurally equals -> writeStructuralEquals(equals.instance(), equals.other());
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                writeStructuralEquals(notEquals.instance(), notEquals.other());
                code.loadConstant(1).ixor();
            }
            case ExpressionDef.ComparisonOperation comparison -> writeBooleanExpression(comparison);
            case ExpressionDef.IsNull isNull -> {
                writeBooleanExpression(isNull);
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                writeBooleanExpression(isNotNull);
            }
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
            parameters.add(ParameterDef.builder(captureName(variable), variable.type()).build());
        }
        parameters.addAll(lambda.implementation().getParameters());
        MethodDef implementation = MethodDef.builder("lambda$" + lambdaOwnerName(methodDef) + "$" + lambdaMethods.size())
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameters(parameters)
            .returns(lambda.implementation().getReturnType())
            .addStatements(lambda.implementation().getStatements())
            .build();
        lambdaMethods.add(implementation);
        for (VariableDef variable : captured) {
            writeExpression(variable);
        }
        ClassDesc factory = ClassDesc.of("java.lang.invoke.LambdaMetafactory");
        MethodTypeDesc bootstrapType = MethodTypeDesc.of(ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
            ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
        DirectMethodHandleDesc bootstrap = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            factory, "metafactory", bootstrapType);
        MethodHandleDesc implementationHandle = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            owner, implementation.getName(), methodType(implementation));
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(bootstrap, lambda.target().getName(),
            methodType(captured.stream().map(VariableDef::type).toList(), lambda.type()),
            methodType(lambda.target()), implementationHandle, methodType(lambda.implementation()));
        code.invokedynamic(callSite);
    }

    /**
     * The enclosing method segment of a lambda implementation name. Constructors and static
     * initializers are named {@code <init>} and {@code <clinit>}, which are not valid in a member
     * name, so use the same {@code new} and {@code static} placeholders that javac does.
     */
    private static String lambdaOwnerName(MethodDef methodDef) {
        return switch (methodDef.getName()) {
            case MethodDef.CONSTRUCTOR -> "new";
            case "<clinit>" -> "static";
            default -> methodDef.getName();
        };
    }

    private void writeMethodReference(MethodReferenceExpression methodReference) {
        ExpressionDef instance = methodReference.instance();
        if (instance != null) {
            writeExpression(instance);
        }
        ClassDesc referencedOwner = classDesc(ObjectDef.getContextualType(objectDef, methodReference.owner()));
        DirectMethodHandleDesc.Kind handleKind;
        MethodHandleDesc handle;
        if (methodReference.isStatic()) {
            handleKind = methodReference.owner().isInterface()
                ? DirectMethodHandleDesc.Kind.INTERFACE_STATIC : DirectMethodHandleDesc.Kind.STATIC;
            handle = MethodHandleDesc.ofMethod(handleKind, referencedOwner, methodReference.method().getName(),
                methodType(methodReference.method()));
        } else if (methodReference.isConstructor()) {
            handle = MethodHandleDesc.ofConstructor(referencedOwner,
                methodReference.method().getParameters().stream().map(parameter -> classDesc(parameter.getType())).toArray(ClassDesc[]::new));
        } else {
            handleKind = methodReference.owner().isInterface()
                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL : DirectMethodHandleDesc.Kind.VIRTUAL;
            handle = MethodHandleDesc.ofMethod(handleKind, referencedOwner, methodReference.method().getName(),
                methodType(methodReference.method()));
        }
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
            lambdaMetafactory(), methodReference.instantiated().getName(),
            methodType(instance == null ? List.of() : List.of(instance.type()), methodReference.type()),
            methodType(methodReference.target()), handle, methodType(methodReference.instantiated())
        );
        code.invokedynamic(callSite);
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
            String name = switch (variable) {
                case VariableDef.Local local -> local.name();
                case VariableDef.MethodParameter parameter -> parameter.name();
                case VariableDef.This _ -> "this";
                case VariableDef.Super _ -> SUPER;
                case VariableDef.ExceptionVar _ -> "exception";
                default -> null;
            };
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

    private static String captureName(VariableDef variable) {
        return switch (variable) {
            case VariableDef.Local local -> local.name();
            case VariableDef.MethodParameter parameter -> parameter.name();
            case VariableDef.This _ -> "this";
                case VariableDef.Super _ -> SUPER;
            case VariableDef.ExceptionVar _ -> "exception";
            default -> variable.type().toString();
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
        writeExpression(aSwitch.expression());
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        List<Map.Entry<Label, ExpressionDef>> bodies = new ArrayList<>();
        Map<Integer, Label> labels = switchLabels(aSwitch.cases(), bodies);
        writeSwitchInstruction(defaultLabel, labels);
        for (Map.Entry<Label, ExpressionDef> body : bodies) {
            code.labelBinding(body.getKey());
            writeExpression(new ExpressionDef.Cast(aSwitch.type(), body.getValue()));
            code.goto_(end);
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeExpression(new ExpressionDef.Cast(aSwitch.type(), aSwitch.defaultCase()));
        }
        code.labelBinding(end);
    }

    private void writeStringExpressionSwitch(ExpressionDef.Switch aSwitch) {
        writeExpression(aSwitch.expression());
        int valueSlot = code.allocateLocal(TypeKind.REFERENCE);
        code.storeLocal(TypeKind.REFERENCE, valueSlot);
        code.loadLocal(TypeKind.REFERENCE, valueSlot)
            .invokevirtual(ConstantDescs.CD_String, HASH_CODE, MethodTypeDesc.of(ConstantDescs.CD_int));
        Label defaultLabel = code.newLabel();
        Label end = code.newLabel();
        Map<Integer, Label> labels = new LinkedHashMap<>();
        Map<Integer, ExpressionDef.Constant> constants = new HashMap<>();
        for (ExpressionDef.Constant constant : aSwitch.cases().keySet()) {
            int key = switchKey(constant);
            if (constants.put(key, constant) != null) {
                throw new UnsupportedOperationException("String switch hash collision");
            }
            labels.put(key, code.newLabel());
        }
        writeSwitchInstruction(defaultLabel, labels);
        for (Map.Entry<Integer, Label> entry : labels.entrySet()) {
            ExpressionDef.Constant constant = Objects.requireNonNull(constants.get(entry.getKey()));
            code.labelBinding(entry.getValue())
                .loadLocal(TypeKind.REFERENCE, valueSlot)
                .loadConstant((String) constant.value())
                .invokevirtual(ConstantDescs.CD_String, EQUALS,
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object))
                .branch(IFEQ, defaultLabel);
            writeExpression(new ExpressionDef.Cast(
                aSwitch.type(), Objects.requireNonNull(aSwitch.cases().get(constant))
            ));
            code.goto_(end);
        }
        code.labelBinding(defaultLabel);
        if (aSwitch.defaultCase() != null) {
            writeExpression(new ExpressionDef.Cast(aSwitch.type(), aSwitch.defaultCase()));
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
                writeStructuralEquals(equals.instance(), equals.other());
                jump(IFNE, trueLabel, falseLabel);
            }
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                writeStructuralEquals(notEquals.instance(), notEquals.other());
                jump(IFEQ, trueLabel, falseLabel);
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

    private void writeComparisonBranch(ExpressionDef.ComparisonOperation comparison,
                                       @Nullable Label trueLabel,
                                       @Nullable Label falseLabel) {
        TypeKind leftKind = kind(comparison.left().type());
        writeExpression(comparison.left());
        writeExpression(comparison.right());
        boolean notEqual = comparison.opType() == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO;
        if (leftKind == TypeKind.REFERENCE) {
            jump(notEqual ? java.lang.classfile.Opcode.IF_ACMPNE : java.lang.classfile.Opcode.IF_ACMPEQ,
                trueLabel, falseLabel);
            return;
        }
        if (leftKind == TypeKind.LONG) {
            code.lcmp();
        } else if (leftKind == TypeKind.FLOAT) {
            compareFloat(comparison);
        } else if (leftKind == TypeKind.DOUBLE) {
            compareDouble(comparison);
        }
        int operation = switch (comparison.opType()) {
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

    private static boolean isShift(ExpressionDef.MathBinaryOperation.OpType opType) {
        return opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT
            || opType == ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
    }

    private void compareFloat(ExpressionDef.ComparisonOperation comparison) {
        if (comparison.opType() == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN
            || comparison.opType() == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL) {
            code.fcmpl();
        } else {
            code.fcmpg();
        }
    }

    private void compareDouble(ExpressionDef.ComparisonOperation comparison) {
        if (comparison.opType() == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN
            || comparison.opType() == ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL) {
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
                code.getfield(classDesc(field.declaringType()), field.name(), classDesc(field.type()));
            }
            case VariableDef.StaticField field -> code.getstatic(classDesc(field.ownerType()), field.name(), classDesc(field.type()));
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
        ExpressionDef expression = withoutRedundantCasts(cast.expressionDef());
        writeExpression(expression);
        if (expression instanceof ExpressionDef.Constant constant && constant.value() == null) {
            // null is assignable to every reference type, so it needs no cast
            return;
        }
        writeConversion(expression.type(), cast.type());
    }

    /**
     * Mirrors the ASM writer: only the last cast of a chain is emitted. Keeping an inner primitive
     * cast would unbox and rebox a reference, turning a legitimate null into a
     * NullPointerException; a primitive cast of something that is not Object is a real conversion
     * and stays.
     */
    private static ExpressionDef withoutRedundantCasts(ExpressionDef expression) {
        ExpressionDef result = expression;
        while (result instanceof ExpressionDef.Cast nested
            && !(nested.type().isPrimitive() && !nested.expressionDef().type().equals(TypeDef.OBJECT))) {
            result = nested.expressionDef();
        }
        return result;
    }

    private void writeConversion(TypeDef source, TypeDef target) {
        // The exact kinds matter here: a boolean boxes to Boolean, not Integer, and an int
        // narrows to byte with i2b even though both load as int
        TypeKind sourceKind = exactKind(source);
        TypeKind targetKind = exactKind(target);
        boolean sourceReference = sourceKind == TypeKind.REFERENCE;
        boolean targetReference = targetKind == TypeKind.REFERENCE;
        if (sourceReference && targetReference) {
            writeCheckCast(target, classDesc(source));
        } else if (!sourceReference && !targetReference) {
            if (sourceKind != targetKind) {
                code.conversion(sourceKind, targetKind);
            }
        } else if (sourceReference) {
            unbox(targetKind);
        } else {
            box(sourceKind);
            // The target may be narrower than the box, and may even be unrelated to it when the
            // model casts through a shared dispatch signature; a checkcast keeps that verifiable
            writeCheckCast(target, wrapper(sourceKind));
        }
    }

    /**
     * Emits a checkcast unless it would be a no-op: a cast to Object, or to the type the value
     * already has. These casts are inserted on every argument and every return, so leaving the
     * redundant ones out keeps generated methods well inside the 64KB limit.
     */
    private void writeCheckCast(TypeDef target, ClassDesc redundant) {
        ClassDesc targetDesc = classDesc(target);
        if (!targetDesc.equals(ConstantDescs.CD_Object) && !targetDesc.equals(redundant)) {
            code.checkcast(targetDesc);
        }
    }

    private void box(TypeKind kind) {
        ClassDesc wrapper = wrapper(kind);
        code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, kindClass(kind)));
    }

    private void unbox(TypeKind kind) {
        // Any Number unboxes to any numeric primitive, so a reference holding a Long can be read
        // as an int the way the ASM backend allows; only boolean and char need their exact box
        ClassDesc boxed = kind == TypeKind.BOOLEAN || kind == TypeKind.CHAR
            ? wrapper(kind)
            : ClassDesc.of("java.lang.Number");
        code.checkcast(boxed).invokevirtual(boxed, primitiveName(kind), MethodTypeDesc.of(kindClass(kind)));
    }

    private static String primitiveName(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "booleanValue";
            case BYTE -> "byteValue";
            case CHAR -> "charValue";
            case SHORT -> "shortValue";
            case INT -> "intValue";
            case LONG -> "longValue";
            case FLOAT -> "floatValue";
            case DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException("Not primitive: " + kind);
        };
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

    private void writeInvocation(ExpressionDef instance, MethodDef method, List<? extends ExpressionDef> values,
                                 boolean isDefault) {
        writeExpression(instance);
        for (int i = 0; i < values.size(); i++) {
            writeArgument(values.get(i), method.getParameters().get(i).getType());
        }
        MethodTypeDesc type = methodType(method.getParameters().stream().map(param -> param.getType()).toList(), method.getReturnType());
        if (instance instanceof VariableDef.Super aSuper) {
            // A super call, including the deprecated form of an explicit super constructor call,
            // is dispatched non-virtually against the supertype
            ClassTypeDef superType = superTypeOf(aSuper);
            code.invokespecial(classDesc(superType), method.getName(), type, superType.isInterface() && isDefault);
            return;
        }
        ClassDesc methodOwner = methodOwner(instance.type());
        if (method.isConstructor()) {
            code.invokespecial(methodOwner, MethodDef.CONSTRUCTOR, type);
        } else if (isInterfaceType(instance.type())) {
            // isDefault only qualifies a super call; an ordinary call on an interface receiver is
            // still dispatched with invokeinterface, even when the target is a default method
            code.invokeinterface(methodOwner, method.getName(), type);
        } else {
            code.invokevirtual(methodOwner, method.getName(), type);
        }
    }

    private void writeStaticInvocation(ClassTypeDef classDef, MethodDef method, List<? extends ExpressionDef> values) {
        for (int i = 0; i < values.size(); i++) {
            writeArgument(values.get(i), method.getParameters().get(i).getType());
        }
        ClassDesc methodOwner = classDesc(classDef);
        code.invokestatic(methodOwner, method.getName(),
            methodType(method.getParameters().stream().map(parameter -> parameter.getType()).toList(), method.getReturnType()),
            isInterfaceType(classDef));
    }

    private boolean isInterfaceType(TypeDef type) {
        // THIS and SUPER only know whether they are an interface once resolved against the definition
        type = ObjectDef.getContextualType(objectDef, type);
        if (type instanceof ClassTypeDef.Parameterized parameterized) {
            return isInterfaceType(parameterized.rawType());
        }
        if (type instanceof TypeDef.TypeVariable variable) {
            return !variable.bounds().isEmpty() && isInterfaceType(variable.bounds().getFirst());
        }
        if (type instanceof TypeDef.AnnotatedTypeDef annotated) {
            return isInterfaceType(annotated.typeDef());
        }
        if (type instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return isInterfaceType(annotated.typeDef());
        }
        return type instanceof ClassTypeDef classTypeDef && classTypeDef.isInterface();
    }

    private void writeSuperConstructor(StatementDef.InvokeSuperConstructor invocation) {
        code.aload(code.receiverSlot());
        for (int i = 0; i < invocation.values().size(); i++) {
            writeArgument(invocation.values().get(i), invocation.method().getParameters().get(i).getType());
        }
        ClassDesc superClass = invocation.superInstance().type().equals(TypeDef.SUPER)
            ? classDesc(superType())
            : classDesc(invocation.superInstance().type());
        code.invokespecial(superClass, MethodDef.CONSTRUCTOR,
            methodType(invocation.method().getParameters().stream().map(parameter -> parameter.getType()).toList(), TypeDef.VOID));
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

    private ClassDesc methodOwner(TypeDef type) {
        if (type.equals(TypeDef.THIS)) {
            return owner;
        }
        if (type.equals(TypeDef.SUPER)) {
            return classDesc(superType());
        }
        return classDesc(type);
    }

    /**
     * The supertype a {@code super} reference resolves against: the one it names, or the
     * definition's own supertype when it is the placeholder {@link TypeDef#SUPER}.
     */
    private ClassTypeDef superTypeOf(VariableDef.Super aSuper) {
        return aSuper.type().equals(TypeDef.SUPER) ? superType() : aSuper.type();
    }

    private ClassTypeDef superType() {
        if (objectDef instanceof RecordDef) {
            return ClassTypeDef.of(Record.class);
        }
        if (objectDef instanceof ClassDef classDef) {
            ClassTypeDef superclass = classDef.getSuperclass();
            if (superclass != null) {
                return superclass;
            }
        }
        return TypeDef.OBJECT;
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
        return TypeKind.fromDescriptor(TypeUtils.getDescriptor(type, objectDef));
    }

    private MethodTypeDesc methodType(List<TypeDef> parameters, TypeDef returnType) {
        return MethodTypeDesc.of(classDesc(returnType), parameters.stream().map(this::classDesc).toList());
    }

    private MethodTypeDesc methodType(MethodDef method) {
        return methodType(method.getParameters().stream().map(ParameterDef::getType).toList(), method.getReturnType());
    }

    private ClassDesc classDesc(TypeDef type) {
        return ClassDesc.ofDescriptor(TypeUtils.getDescriptor(type, objectDef));
    }

    private static UnsupportedOperationException unsupported(Object value) {
        return new UnsupportedOperationException("Unsupported direct JDK lowering: " + value.getClass().getName());
    }

    private record Local(TypeDef type, int slot) {
    }

    private static final class CatchHandler {
        private final StatementDef.Try.Catch aCatch;
        private final Label label;
        private @Nullable Label protectedEnd;

        private CatchHandler(StatementDef.Try.Catch aCatch, Label label) {
            this.aCatch = aCatch;
            this.label = label;
        }
    }
}
