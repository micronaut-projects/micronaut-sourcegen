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
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.StringConcatenation;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayList;
import java.util.List;

final class StringConcatenationExpressionWriter extends AbstractStatementAwareExpressionWriter {

    private static final String STRING_CONCAT_FACTORY_TYPE = "java/lang/invoke/StringConcatFactory";
    private static final String MAKE_CONCAT_METHOD = "makeConcatWithConstants";
    private static final String MAKE_CONCAT_DYNAMIC_METHOD = MAKE_CONCAT_METHOD;
    private static final String MAKE_CONCAT_METHOD_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)" +
            "Ljava/lang/invoke/CallSite;";
    private final List<ExpressionDef> concatParts;

    public StringConcatenationExpressionWriter(StringConcatenation concat) {
        List<ExpressionDef> concatStrings = new ArrayList<>();
        flattenConcat(concat, concatStrings);
        this.concatParts = concatStrings;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        // Write the parameters
        for (ExpressionDef value : concatParts) {
            ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, value, TypeDef.OBJECT);
        }

        // Call to StringConcatFactory.makeConcatWithConstants
        Handle bootstrapMethodHandle = new Handle(
            Opcodes.H_INVOKESTATIC,
            STRING_CONCAT_FACTORY_TYPE,
            MAKE_CONCAT_METHOD,
            MAKE_CONCAT_METHOD_DESCRIPTOR,
            false
        );
        generatorAdapter.visitInvokeDynamicInsn(
            MAKE_CONCAT_DYNAMIC_METHOD,
            createDynamicMethodDescriptor(concatParts, context),
            bootstrapMethodHandle,
            "\u0001".repeat(concatParts.size())
        );
        popValueIfNeeded(generatorAdapter, TypeDef.STRING);
    }

    /**
     * Flatten concat.
     * @param concat The concat expression
     * @param result The result to store flattened expressions to
     */
    private static void flattenConcat(StringConcatenation concat, List<ExpressionDef> result) {
        if (concat.left() instanceof StringConcatenation left) {
            flattenConcat(left, result);
        } else {
            result.add(concat.left());
        }
        if (concat.right() instanceof StringConcatenation right) {
            flattenConcat(right, result);
        } else {
            result.add(concat.right());
        }
    }

    String createDynamicMethodDescriptor(List<ExpressionDef> concatParts, MethodContext context) {
        StringBuilder dynamicDescriptor = new StringBuilder("(");
        for (ExpressionDef part : concatParts) {
            dynamicDescriptor.append(TypeUtils.getType(part.type(), context.objectDef()));
        }
        dynamicDescriptor.append(")");
        dynamicDescriptor.append(TypeUtils.getType(TypeDef.STRING).getDescriptor());
        return dynamicDescriptor.toString();
    }
}
