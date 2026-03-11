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
package io.micronaut.sourcegen.bytecode.statement;

import org.jspecify.annotations.Nullable;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.bytecode.AbstractSwitchWriter;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.StatementDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TableSwitchGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class SwitchStatementWriter extends AbstractSwitchWriter implements StatementWriter {
    private final StatementDef.Switch aSwitch;

    public SwitchStatementWriter(StatementDef.Switch aSwitch) {
        this.aSwitch = aSwitch;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock) {
        boolean isStringSwitch = aSwitch.expression().type() instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(String.class.getName());
        if (isStringSwitch) {
            writeStringSwitch(generatorAdapter, context, finallyBlock, aSwitch);
        } else {
            writeSwitch(generatorAdapter, context, finallyBlock, aSwitch);
        }
    }

    private void writeSwitch(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock, StatementDef.Switch aSwitch) {
        pushSwitchExpression(generatorAdapter, context, aSwitch.expression());
        Map<Integer, StatementDef> map = aSwitch.cases().entrySet().stream().map(e -> Map.entry(toSwitchKey(e.getKey()), e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        tableSwitch(generatorAdapter, context, map, aSwitch.defaultCase(), finallyBlock);
    }

    private void writeStringSwitch(GeneratorAdapter generatorAdapter, MethodContext context, @Nullable Runnable finallyBlock, StatementDef.Switch aSwitch) {
        ExpressionDef expression = aSwitch.expression();

        ExpressionWriter.writeExpression(generatorAdapter, context, expression);

        Type stringType = Type.getType(String.class);
        int switchValueLocal = generatorAdapter.newLocal(stringType);
        generatorAdapter.storeLocal(switchValueLocal, stringType);
        generatorAdapter.loadLocal(switchValueLocal, stringType);
        generatorAdapter.invokeVirtual(
            stringType,
            Method.getMethod(ReflectionUtils.getRequiredMethod(String.class, "hashCode"))
        );

        Map<Integer, Map.Entry<ExpressionDef.Constant, StatementDef>> map = aSwitch.cases().entrySet().stream()
            .map(e -> Map.entry(toSwitchKey(e.getKey()), Map.entry(e.getKey(), e.getValue())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int[] keys = map.keySet().stream().mapToInt(x -> x).sorted().toArray();
        Label defaultEnd = new Label();
        Label finalEnd = new Label();
        generatorAdapter.tableSwitch(keys, new TableSwitchGenerator() {
            @Override
            public void generateCase(int key, Label end) {
                Map.Entry<ExpressionDef.Constant, StatementDef> entry = map.get(key);
                if (entry == null) {
                    generatorAdapter.goTo(defaultEnd);
                    return;
                }
                ExpressionDef.Constant constant = entry.getKey();
                if (!(constant.value() instanceof String stringValue)) {
                    throw new IllegalStateException("Expected a string value got: " + constant);
                }

                generatorAdapter.loadLocal(switchValueLocal, stringType);
                generatorAdapter.push(stringValue);
                generatorAdapter.invokeVirtual(stringType, Method.getMethod(ReflectionUtils.getRequiredMethod(String.class, "equals", Object.class)));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, defaultEnd);
                StatementWriter.of(Objects.requireNonNull(entry.getValue(), "Switch case statement cannot be null"))
                    .writeScoped(generatorAdapter, context, finallyBlock);
                generatorAdapter.goTo(finalEnd);
            }

            @Override
            public void generateDefault() {
                generatorAdapter.goTo(defaultEnd);
            }
        });

        generatorAdapter.visitLabel(defaultEnd);
        if (aSwitch.defaultCase() != null) {
            StatementWriter.of(aSwitch.defaultCase()).writeScoped(generatorAdapter, context, finallyBlock);
        }
        generatorAdapter.visitLabel(finalEnd);
    }

    private void tableSwitch(final GeneratorAdapter generatorAdapter,
                             final MethodContext context,
                             final Map<Integer, StatementDef> cases,
                             @Nullable final StatementDef defaultCase,
                             @Nullable Runnable finallyBlock) {
        final int[] keys = cases.keySet().stream().sorted().mapToInt(i -> i).toArray();
        float density;
        if (keys.length == 0) {
            density = 0;
        } else {
            density = (float) keys.length / (keys[keys.length - 1] - keys[0] + 1);
        }
        tableSwitch(generatorAdapter, context, keys, cases, defaultCase, finallyBlock, density >= 0.5f);
    }

    private void tableSwitch(final GeneratorAdapter generatorAdapter,
                             final MethodContext context,
                             int[] keys,
                             final Map<Integer, StatementDef> cases,
                             @Nullable final StatementDef defaultCase,
                             @Nullable Runnable finallyBlock,
                             final boolean useTable) {
        Label defaultLabel = generatorAdapter.newLabel();
        Label endLabel = generatorAdapter.newLabel();
        if (keys.length > 0) {
            int numKeys = keys.length;
            if (useTable) {
                int min = keys[0];
                int max = keys[numKeys - 1];
                int range = max - min + 1;
                Label[] labels = new Label[range];
                Arrays.fill(labels, defaultLabel);
                List<Map.Entry<Label, StatementDef>> result = new ArrayList<>();
                for (int key : keys) {
                    int i = key - min;
                    StatementDef statementDef = Objects.requireNonNull(
                        cases.get(key),
                        () -> "Switch case statement missing for key: " + key
                    );
                    Label existingLabel = findIndex(result, statementDef);
                    if (existingLabel == null) {
                        Label newLabel = generatorAdapter.newLabel();
                        labels[i] = newLabel;
                        result.add(Map.entry(newLabel, statementDef));
                    } else {
                        // Reuse the label
                        labels[i] = existingLabel;
                    }
                }
                generatorAdapter.visitTableSwitchInsn(min, max, defaultLabel, labels);
                for (Map.Entry<Label, StatementDef> e : result) {
                    generatorAdapter.mark(e.getKey());
                    StatementWriter.of(Objects.requireNonNull(e.getValue(), "Switch case statement cannot be null"))
                        .writeScoped(generatorAdapter, context, finallyBlock);
                    generatorAdapter.goTo(endLabel);
                }
            } else {
                Label[] labels = new Label[keys.length];
                List<Map.Entry<Label, StatementDef>> result = new ArrayList<>();
                for (int i = 0; i < numKeys; ++i) {
                    int key = keys[i];
                    StatementDef statementDef = Objects.requireNonNull(
                        cases.get(key),
                        () -> "Switch case statement missing for key: " + key
                    );
                    Label existingLabel = findIndex(result, statementDef);
                    if (existingLabel == null) {
                        Label newLabel = generatorAdapter.newLabel();
                        labels[i] = newLabel;
                        result.add(Map.entry(newLabel, statementDef));
                    } else {
                        // Reuse the label
                        labels[i] = existingLabel;
                    }
                }
                generatorAdapter.visitLookupSwitchInsn(defaultLabel, keys, labels);
                for (Map.Entry<Label, StatementDef> e : result) {
                    generatorAdapter.mark(e.getKey());
                    StatementWriter.of(e.getValue()).writeScoped(generatorAdapter, context, finallyBlock);
                    generatorAdapter.goTo(endLabel);
                }
            }
        }
        generatorAdapter.mark(defaultLabel);
        if (defaultCase != null) {
            StatementWriter.of(defaultCase).writeScoped(generatorAdapter, context, finallyBlock);
        }
        generatorAdapter.mark(endLabel);
    }

    private static @Nullable Label findIndex(List<Map.Entry<Label, StatementDef>> result, StatementDef statement) {
        for (Map.Entry<Label, StatementDef> e : result) {
            if (e.getValue() == statement) {
                return e.getKey();
            }
        }
        return null;
    }

}
