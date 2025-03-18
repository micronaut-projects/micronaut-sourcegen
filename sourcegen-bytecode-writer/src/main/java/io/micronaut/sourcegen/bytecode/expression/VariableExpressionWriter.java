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

import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.bytecode.statement.TryCatchStatementWriter;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.objectweb.asm.commons.GeneratorAdapter;

final class VariableExpressionWriter implements ExpressionWriter {
    private final VariableDef variableDef;

    public VariableExpressionWriter(VariableDef variableDef) {
        this.variableDef = variableDef;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        System.out.println("Variable: " + variableDef);
        System.out.println("isLambda: " + context.isLambda());

        if (context.isLambda()) {
            writeLambdaVariable(generatorAdapter, context);
            return;
        }
        if (variableDef instanceof VariableDef.ExceptionVar) {
            MethodContext.LocalData localData = context.locals().get(TryCatchStatementWriter.EXCEPTION_NAME);
            generatorAdapter.loadLocal(localData.index(), localData.type());
            return;
        }
        if (variableDef instanceof VariableDef.Local localVariableDef) {
            MethodContext.LocalData localData = context.locals().get(localVariableDef.name());
            generatorAdapter.loadLocal(localData.index(), localData.type());
            return;
        }
        if (variableDef instanceof VariableDef.MethodParameter parameterVariableDef) {
            writeParameterVariable(generatorAdapter, context, parameterVariableDef.name());
            return;
        }
        if (variableDef instanceof VariableDef.StaticField field) {
            writeStaticField(generatorAdapter, context, field);
            return;
        }
        if (variableDef instanceof VariableDef.Field field) {
            writeField(generatorAdapter, context, field);
            return;
        }
        if (variableDef instanceof VariableDef.This) {
            if (context.objectDef() == null) {
                throw new IllegalStateException("Accessing 'this' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        if (variableDef instanceof VariableDef.Super) {
            if (context.objectDef() == null) {
                throw new IllegalStateException("Accessing 'super' is not available");
            }
            generatorAdapter.loadThis();
            return;
        }
        throw new UnsupportedOperationException("Unrecognized variable: " + variableDef);
    }

    private void writeField(GeneratorAdapter generatorAdapter, MethodContext context, VariableDef.Field field) {
        ExpressionWriter.writeExpression(generatorAdapter, context, field.instance());
        TypeDef fieldType = field.type();
        TypeDef owner = field.instance().type();
        generatorAdapter.getField(TypeUtils.getType(owner, context.objectDef()), field.name(), TypeUtils.getType(fieldType, context.objectDef()));
    }

    private void writeParameterVariable(GeneratorAdapter generatorAdapter, MethodContext context, String name) {
        if (context.methodDef() == null) {
            throw new IllegalStateException("Accessing method parameters is not available");
        }
        ParameterDef parameterDef = context.methodDef().getParameters().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
        int parameterIndex = context.methodDef().getParameters().indexOf(parameterDef);
        generatorAdapter.loadArg(parameterIndex);
    }

    private void writeStaticField(GeneratorAdapter generatorAdapter, MethodContext context, VariableDef.StaticField field) {
        TypeDef owner = field.ownerType();
        TypeDef fieldType = field.type();

        generatorAdapter.getStatic(TypeUtils.getType(owner, context.objectDef()), field.name(), TypeUtils.getType(fieldType, context.objectDef()));
    }

    public void writeLambdaVariable(GeneratorAdapter generatorAdapter, MethodContext context) {
        if (variableDef instanceof VariableDef.StaticField field) {
            writeStaticField(generatorAdapter, context, field);
            return;
        } else if (variableDef instanceof VariableDef.Field field) {
            writeField(generatorAdapter, context, field);
            return;
        }
        String name = null;
        if (variableDef instanceof VariableDef.ExceptionVar) {
            name = LambdaExpressionWriter.EXCEPTION_VAR_NAME;
        } else if (variableDef instanceof VariableDef.This) {
            name = LambdaExpressionWriter.THIS_VAR_NAME;
        } else if (variableDef instanceof VariableDef.Super) {
            name = LambdaExpressionWriter.SUPER_VAR_NAME;
        } else if (variableDef instanceof VariableDef.Local local) {
            name = local.name();
        } else if (variableDef instanceof VariableDef.MethodParameter methodParameter) {
            name = methodParameter.name();
        }

        if (name != null) {
            writeParameterVariable(generatorAdapter, context, name);
            return;
        }
        throw new UnsupportedOperationException("Unrecognized lambda variable: " + variableDef);
    }

}
