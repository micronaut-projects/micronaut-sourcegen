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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a call again where it is written, which the model cannot while the call is built: it knows neither the
 * class the call is written in nor, for a variable named alone, the bounds that class and the method declare.
 *
 * <ul>
 *     <li>A method the caller does not access - a package-private one of another package, a protected one of a class
 *     the caller does not extend, or reached through a receiver that is not of the caller's class - is replaced by the
 *     one javac chooses among those it accesses.</li>
 *     <li>A method the owner does not declare is resolved again where the model could not resolve it: on `this` or
 *     `super`, of the caller's own class, or with an argument or a receiver that names a variable alone.</li>
 *     <li>A call no declared method is named by, which is ambiguous among those it accesses, is rejected, as javac
 *     rejects it.</li>
 * </ul>
 *
 * <p>A method the owner declares and the caller accesses is kept as it is, whether the model resolved it or it was
 * given explicitly.</p>
 *
 * @since 2.3
 */
@Internal
public final class InvocationResolver {

    /**
     * Whether a type declares a method, and whether the caller accesses it, by the caller: the class files a
     * definition's calls are written into look up the same ones again and again.
     */
    private static final Map<ObjectDef, Map<String, OverloadResolution.Declaration>> DECLARATIONS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private InvocationResolver() {
    }

    /**
     * Resolves a call in the scope of the class and the method it is written in, where an argument or the receiver
     * names a variable alone.
     *
     * @param owner  The type the method is invoked on
     * @param method The method as the model holds it
     * @param values The arguments
     * @param scope  The variables of the class and the method, with their bounds, by name
     * @return The call, or {@code null} where it is kept as it is
     */
    @Nullable
    public static Call resolve(TypeDef owner, MethodDef method, List<? extends ExpressionDef> values, Map<String, TypeDef> scope) {
        if (!Invocations.needsScope(values) && !Invocations.receiverNeedsScope(owner)) {
            return null;
        }
        return resolve(owner, method, values, scope, null, false);
    }

    /**
     * Resolves a call in the scope of the class and the method it is written in, among the members the class
     * accesses.
     *
     * @param owner  The type the method is invoked on, `this` and `super` resolved in the definition
     * @param method The method as the model holds it
     * @param values The arguments
     * @param scope  The variables of the class and the method, with their bounds, by name
     * @param caller The definition the call is written in, or {@code null}
     * @param self   Whether the receiver is `this` or `super` - a constructor invoked by `this(...)` or `super(...)`
     *               rather than instantiated by `new`
     * @return The call, or {@code null} where it is kept as it is
     * @throws IllegalStateException where the call is ambiguous
     */
    @Nullable
    public static Call resolve(TypeDef owner,
                               MethodDef method,
                               List<? extends ExpressionDef> values,
                               Map<String, TypeDef> scope,
                               @Nullable ObjectDef caller,
                               boolean self) {
        List<ClassTypeDef> owners = Invocations.receiverClasses(owner, scope);
        if (owners.isEmpty()) {
            return null;
        }
        List<TypeDef> declared = method.getParameters().stream()
            .map(parameter -> TypeHierarchy.substituted(parameter.getType(), scope)).toList();
        OverloadResolution.Caller site = caller == null ? null : new OverloadResolution.Caller(caller, self);
        OverloadResolution.Declaration declaration = declaration(owners, method.getName(), declared, site);
        if (declaration == OverloadResolution.Declaration.ACCESSIBLE || declaration == OverloadResolution.Declaration.UNKNOWN) {
            return null;
        }
        // `this` as an argument is of the caller's class
        List<TypeDef> argumentTypes = values.stream().map(value -> TypeHierarchy.substituted(value.type(), scope))
            .map(type -> caller != null && type == TypeDef.THIS ? caller.asTypeDef() : type).toList();
        TypeDef returnType = method.isConstructor() ? null : method.getReturnType();
        // The model could not resolve a call on `this` or `super`, of a variable named alone, or of a private member of
        // the caller's own class, and one it could not tell among the members of another class too - an inaccessible
        // one among them. A method it does resolve, where the owner declares none of the descriptor, was named
        // explicitly and is kept as it is
        boolean replaced = declaration == OverloadResolution.Declaration.INACCESSIBLE || self
            || Invocations.needsScope(values) || Invocations.receiverNeedsScope(owner)
            || caller != null && owners.stream().anyMatch(ownerClass -> TypeHierarchy.erasedName(ownerClass).equals(caller.getName()))
            || OverloadResolution.resolve(owners, method.getName(), returnType, values, argumentTypes, null).outcome()
                != OverloadResolution.Outcome.RESOLVED;
        if (!replaced) {
            return null;
        }
        OverloadResolution.Resolution resolution = OverloadResolution.resolve(owners, method.getName(), returnType, values,
            argumentTypes, site);
        if (resolution.outcome() == OverloadResolution.Outcome.AMBIGUOUS) {
            // javac rejects it: "reference to name is ambiguous"
            throw new IllegalStateException("The call " + TypeHierarchy.erasedName(owners.getFirst()) + "#" + method.getName()
                + argumentTypes.stream().map(TypeHierarchy::erasedName).toList() + " is ambiguous: none of "
                + String.join(" and ", resolution.ambiguous()) + " is more specific than the others");
        }
        Invocations.Resolved resolved = resolution.resolved();
        if (resolved == null) {
            return null;
        }
        MethodDef.MethodDefBuilder builder = MethodDef.builder(method.getName()).addParameters(resolved.parameterTypes())
            .returns(method.getReturnType());
        if (resolved.isStatic()) {
            builder.addModifiers(Modifier.STATIC);
        }
        return new Call(builder.build(), resolved.values());
    }

    private static OverloadResolution.Declaration declaration(List<ClassTypeDef> owners,
                                                              String name,
                                                              List<TypeDef> parameterTypes,
                                                              OverloadResolution.@Nullable Caller caller) {
        if (caller == null) {
            return OverloadResolution.declaration(owners, name, parameterTypes, null);
        }
        String key = owners.stream().map(TypeHierarchy::erasedName).toList() + "#" + name
            + OverloadResolution.signature(parameterTypes) + (caller.self() ? "/self" : "");
        return DECLARATIONS.computeIfAbsent(caller.definition(), ignore -> new ConcurrentHashMap<>())
            .computeIfAbsent(key, ignore -> OverloadResolution.declaration(owners, name, parameterTypes, caller));
    }

    /**
     * A call resolved where it is written.
     *
     * @param method The invoked method
     * @param values The arguments, a variable arity tail packed into its array
     */
    public record Call(MethodDef method, List<? extends ExpressionDef> values) {
    }
}
