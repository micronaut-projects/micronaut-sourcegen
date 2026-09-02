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
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
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
                int slot = code.allocateLocal(TypeKind.REFERENCE);
                code.storeLocal(TypeKind.REFERENCE, slot);
                code.loadLocal(TypeKind.REFERENCE, slot);
                code.athrow();
            }
            case StatementDef.DefineAndAssign define -> {
                writeExpression(define.expression());
                int slot = code.allocateLocal(kind(define.variable().type()));
                locals.put(define.variable().name(), new Local(define.variable().type(), slot));
                store(define.variable().type(), slot);
            }
            case StatementDef.Assign assign -> {
                writeExpression(assign.expression());
                Local local = local(assign.variable().name());
                store(local.type(), local.slot());
            }
            case StatementDef.PutField putField -> {
                writeExpression(putField.field().instance());
                writeExpression(putField.expression());
                code.putfield(classDesc(putField.field().declaringType()), putField.field().name(),
                    classDesc(putField.field().type()));
            }
            case StatementDef.PutStaticField putStaticField -> {
                writeExpression(putStaticField.expression());
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
        if (expression == null) {
            writeCleanups();
            code.return_();
            return;
        }
        TypeDef returnType = methodDef.getReturnType();
        writeExpression(new ExpressionDef.Cast(returnType, expression));
        TypeKind returnKind = kind(returnType);
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
        List<CatchHandler> handlers = new ArrayList<>();
        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            Label handler = code.newLabel();
            handlers.add(new CatchHandler(aCatch, handler));
            code.exceptionCatch(tryStart, tryEnd, handler, classDesc(aCatch.exception()));
        }
        if (finallyHandler != null) {
            code.exceptionCatchAll(tryStart, tryEnd, finallyHandler);
            for (CatchHandler handler : handlers) {
                handler.protectedEnd = code.newLabel();
                code.exceptionCatchAll(handler.label, handler.protectedEnd, finallyHandler);
            }
        }

        code.labelBinding(tryStart);
        if (finallyStatement != null) {
            cleanups.add(() -> writeStatements(finallyStatement.flatten()));
        }
        writeStatement(aTry.statement());
        if (finallyStatement != null) {
            cleanups.removeLast();
        }
        code.labelBinding(tryEnd);
        if (canCompleteNormally(aTry.statement())) {
            if (finallyStatement != null) {
                writeStatements(finallyStatement.flatten());
            }
            code.goto_(end);
        }

        for (CatchHandler handler : handlers) {
            code.labelBinding(handler.label);
            int slot = code.allocateLocal(TypeKind.REFERENCE);
            code.storeLocal(TypeKind.REFERENCE, slot);
            locals.put(EXCEPTION_NAME, new Local(handler.aCatch.exception(), slot));
            if (finallyStatement != null) {
                cleanups.add(() -> writeStatements(finallyStatement.flatten()));
            }
            writeStatement(handler.aCatch.statement());
            if (finallyStatement != null) {
                cleanups.removeLast();
            }
            locals.remove(EXCEPTION_NAME);
            if (handler.protectedEnd != null) {
                code.labelBinding(handler.protectedEnd);
            }
            if (canCompleteNormally(handler.aCatch.statement())) {
                if (finallyStatement != null) {
                    writeStatements(finallyStatement.flatten());
                }
                code.goto_(end);
            }
        }
        if (finallyHandler != null) {
            StatementDef requiredFinally = Objects.requireNonNull(finallyStatement);
            code.labelBinding(finallyHandler);
            int slot = code.allocateLocal(TypeKind.REFERENCE);
            code.storeLocal(TypeKind.REFERENCE, slot);
            writeStatements(requiredFinally.flatten());
            code.loadLocal(TypeKind.REFERENCE, slot).athrow();
        }
        code.labelBinding(end);
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
        Map<Integer, Label> labels = new LinkedHashMap<>();
        for (ExpressionDef.Constant constant : aSwitch.cases().keySet()) {
            labels.put(switchKey(constant), code.newLabel());
        }
        code.lookupswitch(defaultLabel, labels.entrySet().stream()
            .map(entry -> java.lang.classfile.instruction.SwitchCase.of(entry.getKey(), entry.getValue())).toList());
        for (Map.Entry<Integer, Label> entry : labels.entrySet()) {
            code.labelBinding(entry.getValue());
            StatementDef caseStatement = Objects.requireNonNull(
                aSwitch.cases().get(findConstant(aSwitch, entry.getKey()))
            );
            writeStatement(caseStatement);
            if (canCompleteNormally(caseStatement)) {
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
            .invokevirtual(ConstantDescs.CD_String, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int));
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
        code.lookupswitch(defaultLabel, hashLabels.entrySet().stream()
            .map(entry -> java.lang.classfile.instruction.SwitchCase.of(entry.getKey(), entry.getValue())).toList());
        for (Map.Entry<Integer, Label> entry : hashLabels.entrySet()) {
            ExpressionDef.Constant constant = Objects.requireNonNull(constants.get(entry.getKey()));
            code.labelBinding(entry.getValue())
                .loadLocal(TypeKind.REFERENCE, valueSlot)
                .loadConstant((String) constant.value())
                .invokevirtual(ConstantDescs.CD_String, "equals",
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

    private static int switchKey(ExpressionDef.Constant constant) {
        if (constant.value() instanceof Integer integer) {
            return integer;
        }
        if (constant.value() instanceof String string) {
            return string.hashCode();
        }
        throw new UnsupportedOperationException("Unsupported switch constant: " + constant.value());
    }

    private static ExpressionDef.Constant findConstant(StatementDef.Switch aSwitch, int key) {
        return aSwitch.cases().keySet().stream()
            .filter(constant -> switchKey(constant) == key)
            .findFirst()
            .orElseThrow();
    }

    private static ExpressionDef.Constant findConstant(ExpressionDef.Switch aSwitch, int key) {
        return aSwitch.cases().keySet().stream()
            .filter(constant -> switchKey(constant) == key)
            .findFirst()
            .orElseThrow();
    }

    private static boolean canCompleteNormally(StatementDef statement) {
        List<StatementDef> statements = statement.flatten();
        if (statements.isEmpty()) {
            return true;
        }
        StatementDef last = statements.getLast();
        if (last instanceof StatementDef.IfElse ifElse) {
            return canCompleteNormally(ifElse.statement()) || canCompleteNormally(ifElse.elseStatement());
        }
        if (last instanceof StatementDef.Try aTry) {
            return canCompleteNormally(aTry.statement());
        }
        if (last instanceof StatementDef.Synchronized synchronizedStatement) {
            return canCompleteNormally(synchronizedStatement.statement());
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
                for (int i = 0; i < array.expressions().size(); i++) {
                    code.dup().loadConstant(i);
                    writeExpression(array.expressions().get(i));
                    code.arrayStore(kind(array.type().componentType()));
                }
            }
            case ExpressionDef.ArrayElement array -> {
                writeExpression(array.expression());
                writeExpression(array.indexExpression());
                code.arrayLoad(kind(array.type()));
            }
            case ExpressionDef.GetPropertyValue property -> {
                writeExpression(property.instance());
                code.getfield(classDesc(property.instance().type()), property.propertyElement().getName(),
                    classDesc(property.type()));
            }
            case ExpressionDef.InvokeGetClassMethod getClass -> {
                writeExpression(getClass.instance());
                code.invokevirtual(ConstantDescs.CD_Object, "getClass", MethodTypeDesc.of(ConstantDescs.CD_Class));
            }
            case ExpressionDef.InvokeHashCodeMethod hashCode -> {
                writeExpression(hashCode.instance());
                code.invokevirtual(ConstantDescs.CD_Object, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case ExpressionDef.InstanceOf instanceOf -> {
                writeExpression(instanceOf.expression());
                code.instanceOf(classDesc(instanceOf.instanceType()));
            }
            case ExpressionDef.EqualsReferentially equals -> writeReferenceComparison(equals.instance(), equals.other(), false);
            case ExpressionDef.NotEqualsReferentially notEquals -> writeReferenceComparison(notEquals.instance(), notEquals.other(), true);
            case ExpressionDef.EqualsStructurally equals -> writeObjectsEquals(equals.instance(), equals.other());
            case ExpressionDef.NotEqualsStructurally notEquals -> {
                writeObjectsEquals(notEquals.instance(), notEquals.other());
                code.ixor().loadConstant(1);
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
        writeExpression(initializer);
        code.putfield(classDesc(classDef.asTypeDef()), field.getName(), classDesc(field.getType()));
    }

    private void writeLambda(ExpressionDef.Lambda lambda) {
        List<VariableDef> captured = captureVariables(lambda.implementation());
        List<ParameterDef> parameters = new ArrayList<>();
        for (VariableDef variable : captured) {
            parameters.add(ParameterDef.builder(captureName(variable), variable.type()).build());
        }
        parameters.addAll(lambda.implementation().getParameters());
        MethodDef implementation = MethodDef.builder("lambda$" + methodDef.getName() + "$" + lambdaMethods.size())
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
                case VariableDef.This ignored -> "this";
                case VariableDef.Super ignored -> "super";
                case VariableDef.ExceptionVar ignored -> "exception";
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
            case VariableDef.This ignored -> "this";
            case VariableDef.Super ignored -> "super";
            case VariableDef.ExceptionVar ignored -> "exception";
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
        writeExpression(ifElse.ifExpression());
        code.goto_(end).labelBinding(elseLabel);
        writeExpression(ifElse.elseExpression());
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
        Map<Integer, Label> labels = new LinkedHashMap<>();
        for (ExpressionDef.Constant constant : aSwitch.cases().keySet()) {
            labels.put(switchKey(constant), code.newLabel());
        }
        code.lookupswitch(defaultLabel, labels.entrySet().stream()
            .map(entry -> java.lang.classfile.instruction.SwitchCase.of(entry.getKey(), entry.getValue())).toList());
        for (Map.Entry<Integer, Label> entry : labels.entrySet()) {
            code.labelBinding(entry.getValue());
            ExpressionDef value = Objects.requireNonNull(aSwitch.cases().get(findConstant(aSwitch, entry.getKey())));
            writeExpression(new ExpressionDef.Cast(aSwitch.type(), value));
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
            .invokevirtual(ConstantDescs.CD_String, "hashCode", MethodTypeDesc.of(ConstantDescs.CD_int));
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
        code.lookupswitch(defaultLabel, labels.entrySet().stream()
            .map(entry -> java.lang.classfile.instruction.SwitchCase.of(entry.getKey(), entry.getValue())).toList());
        for (Map.Entry<Integer, Label> entry : labels.entrySet()) {
            ExpressionDef.Constant constant = Objects.requireNonNull(constants.get(entry.getKey()));
            code.labelBinding(entry.getValue())
                .loadLocal(TypeKind.REFERENCE, valueSlot)
                .loadConstant((String) constant.value())
                .invokevirtual(ConstantDescs.CD_String, "equals",
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
        if (condition instanceof ExpressionDef.And and) {
            var right = code.newLabel();
            writeCondition(and.left(), right, falseLabel);
            code.labelBinding(right);
            writeCondition(and.right(), trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.Or or) {
            var right = code.newLabel();
            writeCondition(or.left(), trueLabel, right);
            code.labelBinding(right);
            writeCondition(or.right(), trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.IsNull isNull) {
            writeExpression(isNull.expression());
            jump(java.lang.classfile.Opcode.IFNULL, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.IsNotNull isNotNull) {
            writeExpression(isNotNull.expression());
            jump(java.lang.classfile.Opcode.IFNONNULL, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.IsTrue isTrue) {
            writeExpression(isTrue.expression());
            jump(java.lang.classfile.Opcode.IFNE, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.IsFalse isFalse) {
            writeExpression(isFalse.expression());
            jump(java.lang.classfile.Opcode.IFEQ, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.InstanceOf instanceOf) {
            writeExpression(instanceOf.expression());
            code.instanceOf(classDesc(instanceOf.instanceType()));
            jump(java.lang.classfile.Opcode.IFNE, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.EqualsReferentially equals) {
            writeReferenceBranch(equals.instance(), equals.other(), trueLabel, falseLabel, false);
            return;
        }
        if (condition instanceof ExpressionDef.NotEqualsReferentially notEquals) {
            writeReferenceBranch(notEquals.instance(), notEquals.other(), trueLabel, falseLabel, true);
            return;
        }
        if (condition instanceof ExpressionDef.ComparisonOperation comparison) {
            writeComparisonBranch(comparison, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.EqualsStructurally equals) {
            writeObjectsEquals(equals.instance(), equals.other());
            jump(java.lang.classfile.Opcode.IFNE, trueLabel, falseLabel);
            return;
        }
        if (condition instanceof ExpressionDef.NotEqualsStructurally notEquals) {
            writeObjectsEquals(notEquals.instance(), notEquals.other());
            jump(java.lang.classfile.Opcode.IFEQ, trueLabel, falseLabel);
            return;
        }
        throw unsupported(condition);
    }

    private void writeObjectsEquals(ExpressionDef left, ExpressionDef right) {
        writeBoxed(left);
        writeBoxed(right);
        code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
    }

    private void writeBoxed(ExpressionDef expression) {
        writeExpression(expression);
        TypeKind expressionKind = kind(expression.type());
        if (expressionKind != TypeKind.REFERENCE) {
            box(expressionKind);
        }
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
                    load("super", superVariable.type());
                } else {
                    throw unsupported(variable);
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
        TypeDef source = cast.expressionDef().type();
        TypeDef target = cast.type();
        writeExpression(cast.expressionDef());
        TypeKind sourceKind = kind(source);
        TypeKind targetKind = kind(target);
        if (sourceKind == targetKind) {
            if (targetKind == TypeKind.REFERENCE && !classDesc(target).equals(ConstantDescs.CD_Object)) {
                code.checkcast(classDesc(target));
            }
        } else if (sourceKind != TypeKind.REFERENCE && targetKind != TypeKind.REFERENCE) {
            code.conversion(sourceKind, targetKind);
        } else if (sourceKind != TypeKind.REFERENCE) {
            box(sourceKind);
        } else if (targetKind != TypeKind.REFERENCE) {
            unbox(targetKind);
        } else {
            code.checkcast(classDesc(target));
        }
    }

    private void box(TypeKind kind) {
        ClassDesc wrapper = wrapper(kind);
        code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, kindClass(kind)));
    }

    private void unbox(TypeKind kind) {
        ClassDesc wrapper = wrapper(kind);
        code.checkcast(wrapper).invokevirtual(wrapper, primitiveName(kind), MethodTypeDesc.of(kindClass(kind)));
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
        } else if (value instanceof String string) {
            code.ldc(code.constantPool().stringEntry(string));
        } else if (value instanceof Class<?> type) {
            code.ldc(ClassDesc.ofDescriptor(type.descriptorString().replace('.', '/')));
        } else if (value instanceof TypeDef type) {
            code.ldc(classDesc(type));
        } else if (value instanceof Enum<?> anEnum) {
            ClassDesc type = ClassDesc.of(anEnum.getDeclaringClass().getName());
            code.getstatic(type, anEnum.name(), type);
        } else if (value instanceof Integer integer) {
            code.loadConstant(integer);
        } else if (value instanceof Long longValue) {
            code.loadConstant(longValue);
        } else if (value instanceof Float floatValue) {
            code.loadConstant(floatValue);
        } else if (value instanceof Double doubleValue) {
            code.loadConstant(doubleValue);
        } else if (value instanceof Byte byteValue) {
            code.loadConstant(byteValue.intValue());
        } else if (value instanceof Short shortValue) {
            code.loadConstant(shortValue.intValue());
        } else if (value instanceof Character character) {
            code.loadConstant((int) character.charValue());
        } else if (value instanceof Boolean booleanValue) {
            code.loadConstant(booleanValue ? 1 : 0);
        } else {
            throw unsupported(constant);
        }
    }

    private void writeInvocation(ExpressionDef instance, MethodDef method, List<? extends ExpressionDef> values,
                                 boolean isDefault) {
        writeExpression(instance);
        for (int i = 0; i < values.size(); i++) {
            writeArgument(values.get(i), method.getParameters().get(i).getType());
        }
        ClassDesc methodOwner = methodOwner(instance.type());
        MethodTypeDesc type = methodType(method.getParameters().stream().map(param -> param.getType()).toList(), method.getReturnType());
        if (method.isConstructor()) {
            code.invokespecial(methodOwner, MethodDef.CONSTRUCTOR, type);
        } else if (isDefault) {
            code.invokespecial(methodOwner, method.getName(), type, true);
        } else if (isInterfaceType(instance.type())) {
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
            classDef.isInterface());
    }

    private static boolean isInterfaceType(TypeDef type) {
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
            ? ((ClassDef) objectDef).getSuperclass() == null ? ConstantDescs.CD_Object : classDesc(((ClassDef) objectDef).getSuperclass())
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
        TypeKind componentKind = kind(type.componentType());
        if (componentKind == TypeKind.REFERENCE) {
            code.anewarray(classDesc(type.componentType()));
        } else {
            code.newarray(componentKind);
        }
    }

    private ClassDesc methodOwner(TypeDef type) {
        if (type.equals(TypeDef.THIS)) {
            return owner;
        }
        if (type.equals(TypeDef.SUPER)) {
            return ((ClassDef) objectDef).getSuperclass() == null ? ConstantDescs.CD_Object
                : classDesc(((ClassDef) objectDef).getSuperclass());
        }
        return classDesc(type);
    }

    private TypeKind kind(TypeDef type) {
        return TypeKind.fromDescriptor(TypeUtils.getDescriptor(type, objectDef)).asLoadable();
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
