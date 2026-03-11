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
package io.micronaut.sourcegen.bytecode.expression;

import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.bytecode.AbstractSwitchWriter;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TableSwitchGenerator;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class SwitchExpressionWriter extends AbstractSwitchWriter implements ExpressionWriter {
    private final ExpressionDef.Switch aSwitch;

    public SwitchExpressionWriter(ExpressionDef.Switch aSwitch) {
        this.aSwitch = aSwitch;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        ExpressionDef expression = aSwitch.expression();
        boolean isStringSwitch = expression.type() instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(String.class.getName());
        if (isStringSwitch) {
            writeStringSwitch(generatorAdapter, context, aSwitch);
        } else {
            writeSwitch(generatorAdapter, context, aSwitch);
        }
    }

    private static void writeSwitch(GeneratorAdapter generatorAdapter, MethodContext context, ExpressionDef.Switch aSwitch) {
        ExpressionDef expression = aSwitch.expression();
        pushSwitchExpression(generatorAdapter, context, expression);
        Map<Integer, ExpressionDef> map = aSwitch.cases().entrySet().stream().map(e -> Map.entry(toSwitchKey(e.getKey()), e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int[] keys = map.keySet().stream().mapToInt(x -> x).sorted().toArray();
        ExpressionDef defaultCase = aSwitch.defaultCase();
        generatorAdapter.tableSwitch(keys, new TableSwitchGenerator() {
            @Override
            public void generateCase(int key, Label end) {
                ExpressionDef exp = Objects.requireNonNull(
                    map.get(key),
                    () -> "Switch expression missing for key: " + key
                );
                ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, exp, aSwitch.type());
                generatorAdapter.goTo(end);
            }

            @Override
            public void generateDefault() {
                if (defaultCase != null) {
                    ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, defaultCase, aSwitch.type());
                }
            }
        });
    }

    private static void writeStringSwitch(GeneratorAdapter generatorAdapter, MethodContext context, ExpressionDef.Switch aSwitch) {
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

        Map<Integer, Map.Entry<ExpressionDef.Constant, ? extends ExpressionDef>> map = aSwitch.cases().entrySet().stream()
            .map(e -> Map.entry(toSwitchKey(e.getKey()), Map.entry(e.getKey(), e.getValue())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int[] keys = map.keySet().stream().mapToInt(x -> x).sorted().toArray();
        Label defaultEnd = new Label();
        Label finalEnd = new Label();
        ExpressionDef defaultCase = aSwitch.defaultCase();
        boolean hasDefault = defaultCase != null;
        generatorAdapter.tableSwitch(keys, new TableSwitchGenerator() {
            @Override
            public void generateCase(int key, Label end) {
                Map.Entry<ExpressionDef.Constant, ? extends ExpressionDef> entry = map.get(key);
                if (entry == null) {
                    if (hasDefault) {
                        generatorAdapter.goTo(defaultEnd);
                        return;
                    }
                    generatorAdapter.goTo(finalEnd);
                    return;
                }
                if (!(entry.getKey().value() instanceof String stringValue)) {
                    throw new IllegalStateException("Expected a switch string value got " + entry.getKey());
                }
                generatorAdapter.loadLocal(switchValueLocal, stringType);
                generatorAdapter.push(stringValue);
                generatorAdapter.invokeVirtual(stringType, Method.getMethod(ReflectionUtils.getRequiredMethod(String.class, "equals", Object.class)));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, defaultEnd);
                ExpressionWriter.writeExpressionCheckCast(
                    generatorAdapter,
                    context,
                    Objects.requireNonNull(entry.getValue(), "Switch expression result cannot be null"),
                    aSwitch.type()
                );
                generatorAdapter.goTo(finalEnd);
            }

            @Override
            public void generateDefault() {
                if (hasDefault) {
                    generatorAdapter.goTo(defaultEnd);
                }
            }
        });
        if (hasDefault) {
            generatorAdapter.visitLabel(defaultEnd);
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, Objects.requireNonNull(defaultCase), aSwitch.type());
        }
        generatorAdapter.visitLabel(finalEnd);
    }
}
