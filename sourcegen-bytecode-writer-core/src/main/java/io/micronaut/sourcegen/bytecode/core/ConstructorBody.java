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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The body of a constructor as both bytecode writers write it: the constructor call first, then the initializers of
 * the instance fields, then the rest of the statements.
 *
 * <ul>
 *     <li>A constructor calling no other constructor calls the superclass's no-argument one, which the writer writes
 *     itself: {@link #implicitSuper()}.</li>
 *     <li>Field initializers run straight after the constructor call, so the call is moved to the front -
 *     {@link #hoistedCall()} - only when there are any; otherwise the statements stay as they are, because the call may
 *     use locals defined before it.</li>
 *     <li>A record has no fields of its own, only the ones backing its components, which its canonical constructor
 *     assigns.</li>
 * </ul>
 *
 * <p>The writers differ on a constructor delegating to {@code this(...)}, and each keeps its behavior -
 * {@link InitializersAfterThis}: the ASM writer runs the field initializers again after the delegated constructor has
 * run them, the JDK writer runs them only once, as javac does.</p>
 *
 * @param implicitSuper     Whether the writer writes the call of the superclass's no-argument constructor first
 * @param hoistedCall       The constructor call moved to the front of the statements, if any
 * @param initializedFields The instance fields whose initializers run after the constructor call, in declaration order
 * @param statements        The statements, without a hoisted call
 * @since 2.3
 */
@Internal
public record ConstructorBody(boolean implicitSuper,
                              @Nullable StatementDef hoistedCall,
                              List<FieldDef> initializedFields,
                              List<StatementDef> statements) {

    /**
     * Normalizes the statements of a constructor.
     *
     * @param objectDef  The definition declaring the constructor
     * @param statements The statements of the constructor
     * @param mode       Whether the field initializers run after a delegation to {@code this(...)}
     * @return The body
     */
    public static ConstructorBody of(@Nullable ObjectDef objectDef, List<StatementDef> statements, InitializersAfterThis mode) {
        if (!(objectDef instanceof ClassDef || objectDef instanceof RecordDef)) {
            return new ConstructorBody(false, null, List.of(), statements);
        }
        Optional<StatementDef> constructorCall = statements.stream().filter(ConstructorBody::isConstructorInvocation).findFirst();
        boolean delegatesToThis = constructorCall
            // Only a call on `this` delegates; the deprecated form of a super call is an instance invocation too, and
            // its constructor still runs the field initializers
            .map(statement -> statement instanceof ExpressionDef.InvokeInstanceMethod call && !(call.instance() instanceof VariableDef.Super))
            .orElse(false);
        List<FieldDef> fields = delegatesToThis && mode == InitializersAfterThis.SKIP ? List.of() : initializedFields(objectDef);
        if (constructorCall.isEmpty()) {
            return new ConstructorBody(true, null, fields, statements);
        }
        if (fields.isEmpty()) {
            return new ConstructorBody(false, null, fields, statements);
        }
        List<StatementDef> rest = new ArrayList<>(statements);
        rest.remove(constructorCall.get());
        return new ConstructorBody(false, constructorCall.get(), fields, rest);
    }

    /**
     * The instance fields of a class that have an initializer, in declaration order; a record has none of its own.
     *
     * @param objectDef The definition
     * @return The fields
     */
    public static List<FieldDef> initializedFields(ObjectDef objectDef) {
        return objectDef instanceof ClassDef classDef
            ? classDef.getFields().stream()
            .filter(field -> !field.getModifiers().contains(Modifier.STATIC) && field.getInitializer().isPresent())
            .toList()
            : List.of();
    }

    /**
     * Whether a statement calls another constructor: {@code super(...)}, or the deprecated form of an instance
     * invocation of a constructor, on {@code this} or on {@code super}.
     *
     * @param statement The statement
     * @return true for a constructor call
     */
    public static boolean isConstructorInvocation(StatementDef statement) {
        return statement instanceof StatementDef.InvokeSuperConstructor
            || statement instanceof ExpressionDef.InvokeInstanceMethod call && call.method().isConstructor();
    }

    /**
     * The whole body as statements: the call of the superclass's no-argument constructor where it is implicit, the
     * hoisted call, the assignments of the field initializers and the statements.
     *
     * @return The statements
     */
    public List<StatementDef> asStatements() {
        if (!implicitSuper && hoistedCall == null && initializedFields.isEmpty()) {
            return statements;
        }
        List<StatementDef> result = new ArrayList<>(statements.size() + initializedFields.size() + 1);
        if (implicitSuper) {
            result.add(new VariableDef.This().superRef().invokeSuperConstructor());
        }
        if (hoistedCall != null) {
            result.add(hoistedCall);
        }
        for (FieldDef field : initializedFields) {
            result.add(new VariableDef.This().field(field).assign(field.getInitializer().orElseThrow()));
        }
        result.addAll(statements);
        return result;
    }

    /**
     * Whether the field initializers run after a delegation to {@code this(...)}.
     */
    public enum InitializersAfterThis {
        /**
         * The ASM writer's: they run again after the delegated constructor has run them.
         */
        RERUN,
        /**
         * The JDK writer's: they run once, in the constructor delegated to, as javac runs them.
         */
        SKIP
    }
}
