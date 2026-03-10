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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.bytecode.MethodContext;
import io.micronaut.sourcegen.bytecode.TypeUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.commons.GeneratorAdapter;

final class CastExpressionWriter implements ExpressionWriter {

    private final ExpressionDef.Cast castExpressionDef;

    public CastExpressionWriter(ExpressionDef.Cast castExpressionDef) {
        this.castExpressionDef = castExpressionDef;
    }

    @Override
    public void write(GeneratorAdapter generatorAdapter, MethodContext context) {
        ExpressionDef exp = castExpressionDef.expressionDef();
        while (exp instanceof ExpressionDef.Cast cast) {
            if (cast.type().isPrimitive()) {
                TypeDef previousCastType = cast.expressionDef().type();
                if (!previousCastType.equals(TypeDef.OBJECT)) {
                    break;
                }
            }
            // Only keep the last cast
            exp = cast.expressionDef();
        }
        ExpressionWriter.writeExpression(generatorAdapter, context, exp);
        if (exp instanceof ExpressionDef.Constant constant && constant.value() == null) {
            // Avoid casting null to anything
            return;
        }
        cast(generatorAdapter, context, exp.type(), castExpressionDef.type());
    }

    private static void cast(GeneratorAdapter generatorAdapter, MethodContext context, TypeDef from, TypeDef to) {
        from = ObjectDef.getContextualType(context.objectDef(), from);
        to = ObjectDef.getContextualType(context.objectDef(), to);
        if ((from instanceof TypeDef.Primitive fromP && to instanceof TypeDef.Primitive toP) && !from.equals(to)) {
            generatorAdapter.cast(TypeUtils.getType(fromP), TypeUtils.getType(toP));
            return;
        }
        if ((from.isPrimitive() || to.isPrimitive()) && !from.equals(to)) {
            if (from instanceof TypeDef.Primitive primitive && !to.isPrimitive()) {
                box(generatorAdapter, context, from);
                checkCast(generatorAdapter, context, primitive.wrapperType(), to);
            }
            if (!from.isPrimitive() && to.isPrimitive()) {
                unbox(generatorAdapter, context, to);
            }
        } else if (needsCast(from, to)) {
            checkCast(generatorAdapter, context, from, to);
        }
    }

    private static boolean needsCast(TypeDef from, TypeDef to) {
        if (from.makeNullable().equals(to.makeNullable())) {
            return false;
        }
        if (from instanceof ClassTypeDef.Parameterized parameterized) {
            return needsCast(parameterized.rawType(), to);
        }
        if (to instanceof ClassTypeDef.Parameterized parameterized) {
            return needsCast(from, parameterized.rawType());
        }
        if (from instanceof ClassTypeDef.ClassElementType fromElement) {
            return needsCast(fromElement.classElement(), to);
        }
        if (from instanceof ClassTypeDef.JavaClass fromClass) {
            if (to instanceof ClassTypeDef.JavaClass toClass) {
                return !toClass.type().isAssignableFrom(fromClass.type());
            }
        }
        if (from instanceof ClassTypeDef.ClassDefType fromClassDef) {
            ClassTypeDef fromSuperclass = getSuperclass(fromClassDef.objectDef());
            if (fromSuperclass != null) {
                return needsCast(fromSuperclass, to);
            }
        }
        return true;
    }

    private static boolean needsCast(ClassElement from, TypeDef to) {
        if (to instanceof ClassTypeDef.ClassElementType toElement) {
            return !from.isAssignable(toElement.classElement());
        }
        if (to instanceof ClassTypeDef.JavaClass toClass) {
            return !from.isAssignable(toClass.type());
        }
        if (to instanceof ClassTypeDef.ClassName toClassName) {
            return !from.isAssignable(toClassName.name());
        }
        if (to instanceof ClassTypeDef.ClassDefType toClassDefType) {
            if (from.isAssignable(toClassDefType.getName())) {
                return false;
            }
            return !from.isAssignable(toClassDefType.getName());
        }
        return true;
    }

    private static ClassTypeDef getSuperclass(ObjectDef objectDef) {
        if (objectDef instanceof ClassDef classDef) {
            return classDef.getSuperclass();
        }
        if (objectDef instanceof EnumDef) {
            return ClassTypeDef.of(Enum.class);
        }
        if (objectDef instanceof RecordDef) {
            return ClassTypeDef.of(Record.class);
        }
        return null;
    }

    private static void checkCast(GeneratorAdapter generatorAdapter, MethodContext context, TypeDef from, TypeDef to) {
        TypeDef toType = ObjectDef.getContextualType(context.objectDef(), to);
        if (!toType.makeNullable().equals(from.makeNullable())) {
            generatorAdapter.checkCast(TypeUtils.getType(toType, context.objectDef()));
        }
    }

    private static void unbox(GeneratorAdapter generatorAdapter, MethodContext context, TypeDef to) {
        generatorAdapter.unbox(TypeUtils.getType(to, context.objectDef()));
    }

    private static void box(GeneratorAdapter generatorAdapter, MethodContext context, TypeDef from) {
        generatorAdapter.valueOf(TypeUtils.getType(from, context.objectDef()));
    }
}
