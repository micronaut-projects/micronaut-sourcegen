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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A constructor of a record other than the canonical one that assigns the fields of the components itself: a plain
 * constructor in bytecode, which Java requires to call another. The components are assigned to locals, which the
 * canonical constructor is called with, from a prologue before that call (JLS 8.8.7).
 *
 * @since 2.3
 */
@Internal
final class JavaRecordConstructors {

    private JavaRecordConstructors() {
    }

    static CodeBlock render(JavaStatementRenderer renderer,
                            RecordDef recordDef,
                            MethodDef constructor,
                            RenderScope scope,
                            List<StatementDef> statements) {
        JavaExpressionRenderer expressions = renderer.expressions();
        Set<String> components = new HashSet<>();
        recordDef.getProperties().forEach(property -> components.add(property.getName()));
        // The call of the constructor of Record is the one of the canonical constructor
        List<StatementDef> body = statements.stream().filter(statement -> !JavaExceptionRules.isConstructorInvocation(statement)).toList();
        int last = -1;
        Map<String, Integer> topLevel = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        Set<String> nested = new HashSet<>();
        for (int i = 0; i < body.size(); i++) {
            StatementDef statement = body.get(i);
            String component = componentOf(statement, components);
            if (component != null) {
                topLevel.merge(component, 1, Integer::sum);
            }
            List<String> all = new ArrayList<>();
            JavaLambdaRules.forEachStatement(List.of(statement), child -> {
                String name = componentOf(child, components);
                if (name != null) {
                    all.add(name);
                }
            });
            if (component != null) {
                // The statement itself, visited first
                all.remove(component);
            }
            Set<String> inside = new HashSet<>(all);
            nested.addAll(inside);
            if (component != null || !inside.isEmpty()) {
                last = i;
                assigned.addAll(inside);
                if (component != null) {
                    assigned.add(component);
                }
            }
        }
        Map<String, String> locals = new LinkedHashMap<>();
        CodeBlock.Builder builder = CodeBlock.builder();
        for (PropertyDef property : recordDef.getProperties()) {
            if (!assigned.contains(property.getName())) {
                continue;
            }
            String local = scope.declareFresh(property.getName());
            locals.put(property.getName(), local);
            if (nested.contains(property.getName()) || topLevel.getOrDefault(property.getName(), 0) != 1) {
                builder.addStatement("$T $L = $L", expressions.types().asType(property.getType(), recordDef, constructor), local,
                    defaultValue(expressions, recordDef, constructor, scope, property.getType()));
            }
        }
        locals.forEach(scope::renameField);
        for (StatementDef statement : body.subList(0, last + 1)) {
            String component = componentOf(statement, components);
            if (component != null && !nested.contains(component) && topLevel.getOrDefault(component, 0) == 1) {
                StatementDef.PutField put = (StatementDef.PutField) statement;
                builder.addStatement("$T $L = $L", expressions.types().asType(put.field().type(), recordDef, constructor), locals.get(component),
                    expressions.renderStored(recordDef, constructor, scope, put.field().type(), put.expression()));
            } else {
                builder.add(renderer.renderStatementCodeBlock(recordDef, constructor, scope, statement, false));
            }
        }
        List<CodeBlock> arguments = new ArrayList<>();
        for (PropertyDef property : recordDef.getProperties()) {
            String local = locals.get(property.getName());
            arguments.add(local != null ? CodeBlock.of("$L", local)
                : defaultValue(expressions, recordDef, constructor, scope, property.getType()));
        }
        builder.addStatement("this($L)", CodeBlock.join(arguments, ", "));
        return builder.add(renderer.renderBlock(recordDef, constructor, scope, body.subList(last + 1, body.size()), true)).build();
    }

    /**
     * @return The component a statement assigns the field of, where it is a put of a field of `this`
     */
    @Nullable
    private static String componentOf(StatementDef statement, Set<String> components) {
        return statement instanceof StatementDef.PutField put && put.field().instance() instanceof VariableDef.This
            && components.contains(put.field().name()) ? put.field().name() : null;
    }

    /**
     * The value a field has before a constructor assigns it.
     */
    private static CodeBlock defaultValue(JavaExpressionRenderer expressions, RecordDef recordDef, MethodDef constructor,
                                          RenderScope scope, TypeDef type) {
        if (type instanceof TypeDef.Primitive primitive) {
            Object zero = switch (primitive.name()) {
                case "boolean" -> false;
                case "char" -> (char) 0;
                default -> 0;
            };
            return expressions.renderExpression(recordDef, constructor, scope, new ExpressionDef.Constant(primitive, zero));
        }
        return CodeBlock.of("($T) null", expressions.types().asType(type, recordDef, constructor));
    }
}
