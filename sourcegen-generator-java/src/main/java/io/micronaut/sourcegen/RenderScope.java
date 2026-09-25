/*
 * Copyright 2017-2023 original authors
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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.SourceVersion;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The scope of a method or lambda body being rendered: the names its variables are written as, and where its
 * statements return to and which exceptions they may throw.
 *
 * <p>Every name the source declares is chosen here: a parameter, a local, the parameter of a catch and of a lambda,
 * the copies of captured locals and the helper variables the generator introduces. A name that is a keyword - a valid
 * name in Kotlin - is written under another, and so is one an enclosing scope already has in scope, which Java forbids
 * a lambda parameter or a local of a nested block to shadow.
 *
 * <p>A lambda body is rendered in a scope of its own, nested in the scope of the enclosing method, so that a
 * reference to an enclosing method's parameter resolves instead of failing.
 */
@Internal
final class RenderScope {

    @Nullable
    private final RenderScope parent;
    @Nullable
    private final MethodDef owner;
    // The names parameters of the owner are written as, where they are not their own
    private final Map<String, String> parameterRenames = new LinkedHashMap<>();
    private final Map<String, String> localRenames = new LinkedHashMap<>();
    private final Set<String> taken = new LinkedHashSet<>();
    // Of a body: the names it declares anywhere, which an allocated name does not take
    private final Set<String> reserved = new LinkedHashSet<>();
    // Of a catch: the name the exception it catches is written as
    @Nullable
    private String caughtException;
    // The copies of the captured locals a lambda of this scope is written with, by the lambda
    private final Map<ExpressionDef.Lambda, Map<String, String>> captureCopies = new IdentityHashMap<>();
    // Of a body: the locals it assigns
    @Nullable
    private Set<String> assignedLocals;
    // The checked exceptions a statement of this scope may throw: those the method declares, or a try catches
    private List<TypeDef> handled = List.of();
    // The label a return of a static initializer breaks out of, and the locals fields are written as
    @Nullable
    private String returnLabel;
    private final Map<String, String> fieldRenames = new LinkedHashMap<>();
    @Nullable
    private TypeDef yieldType;
    private boolean yieldsConverted;

    private RenderScope(@Nullable RenderScope parent, @Nullable MethodDef owner) {
        this.parent = parent;
        this.owner = owner;
        if (owner != null) {
            for (ParameterDef parameter : owner.getParameters()) {
                taken.add(parameter.getName());
            }
        }
    }

    /**
     * @param owner The method the scope belongs to
     * @return A root scope
     */
    static RenderScope root(@Nullable MethodDef owner) {
        return new RenderScope(null, owner);
    }

    /**
     * @param owner The method the nested scope belongs to
     * @return A scope nested in this one
     */
    RenderScope nested(@Nullable MethodDef owner) {
        return new RenderScope(this, owner);
    }

    /**
     * @param type      The type of the switch expression
     * @param converted Whether each value yielded is converted to the type
     * @return A scope nested in this one for a block of a switch expression, whose returns yield its result
     */
    RenderScope yielding(TypeDef type, boolean converted) {
        RenderScope scope = new RenderScope(this, null);
        scope.yieldType = type;
        scope.yieldsConverted = converted;
        return scope;
    }

    /**
     * @return Whether the values the innermost switch expression block yields are converted to its type
     */
    boolean yieldsConverted() {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.yieldType != null) {
                return s.yieldsConverted;
            }
            if (s.owner != null) {
                return false;
            }
        }
        return false;
    }

    /**
     * @return The type a return yields in a block of a switch expression, or {@code null} where it returns from the
     * method or lambda: a lambda in the block returns from itself
     */
    @Nullable
    TypeDef yieldType() {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.yieldType != null) {
                return s.yieldType;
            }
            if (s.owner != null) {
                return null;
            }
        }
        return null;
    }

    /**
     * @param label The label of the block a return of this body breaks out of: a static initializer does not return
     * @return This scope
     */
    RenderScope returning(String label) {
        returnLabel = label;
        return this;
    }

    /**
     * @return The label a return breaks out of, or {@code null} where it returns
     */
    @Nullable
    String returnLabel() {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.returnLabel != null) {
                return s.returnLabel;
            }
            if (s.owner != null) {
                return null;
            }
        }
        return null;
    }

    /**
     * Records that a field of `this` is written as a local.
     *
     * @param name  The name of the field
     * @param local The name of the local
     */
    void renameField(String name, String local) {
        fieldRenames.put(name, local);
    }

    /**
     * @param name The name of a field of `this`
     * @return The local it is written as, or {@code null}
     */
    @Nullable
    String resolveField(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            String local = s.fieldRenames.get(name);
            if (local != null) {
                return local;
            }
        }
        return null;
    }

    /**
     * @param exceptions The checked exceptions the body of this scope may throw: those its method declares
     * @return This scope
     */
    RenderScope handling(List<TypeDef> exceptions) {
        handled = exceptions;
        return this;
    }

    /**
     * @param exceptions The checked exceptions a try catches
     * @return A scope nested in this one for the body of the try
     */
    RenderScope nestedHandling(List<TypeDef> exceptions) {
        RenderScope scope = new RenderScope(this, null);
        scope.handled = exceptions;
        return scope;
    }

    /**
     * @param handles Whether exceptions a scope handles - those its method declares, or a try catches - include the
     *                one asked for
     * @return Whether a statement of this scope may throw it: its method declares it, or a try catches it
     */
    boolean handles(Predicate<List<TypeDef>> handles) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (handles.test(s.handled)) {
                return true;
            }
            if (s.owner != null) {
                // The body of a method or a lambda
                return false;
            }
        }
        return false;
    }

    /**
     * Makes this scope the one of a body - of a method, a lambda or an initializer.
     *
     * @param statements The statements of the body
     * @return This scope
     */
    RenderScope body(List<StatementDef> statements) {
        assignedLocals = JavaLambdaRules.assignedLocals(statements);
        reserved.addAll(JavaSourceRules.declaredLocals(statements));
        return this;
    }

    /**
     * @param name The name of a local
     * @return Whether the innermost body assigns the local after declaring it, which leaves it not effectively final
     */
    boolean isAssignedInBody(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.assignedLocals != null) {
                return s.assignedLocals.contains(name);
            }
        }
        return false;
    }

    /**
     * Records the copies of captured locals a lambda is written with.
     *
     * @param lambda The lambda
     * @param copies The names of the copies by the names of the locals
     */
    void recordCopies(ExpressionDef.Lambda lambda, Map<String, String> copies) {
        captureCopies.put(lambda, copies);
    }

    /**
     * @param lambda The lambda
     * @return The names of the copies of captured locals the lambda is written with, by the names of the locals
     */
    Map<String, String> copiesOf(ExpressionDef.Lambda lambda) {
        for (RenderScope s = this; s != null; s = s.parent) {
            Map<String, String> copies = s.captureCopies.get(lambda);
            if (copies != null) {
                return copies;
            }
        }
        return Map.of();
    }

    /**
     * @param name The name of a local in the model
     * @return The name it is written as
     */
    String resolveLocal(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            String emittedName = s.localRenames.get(name);
            if (emittedName != null) {
                return emittedName;
            }
        }
        return name;
    }

    /**
     * Resolves the name a method parameter is emitted under, looking in the innermost scope that
     * declares it and walking outwards so that a lambda body can capture a parameter of the
     * enclosing method.
     *
     * @param name The parameter name
     * @return The name to emit, or {@code null} if no scope declares the parameter
     */
    @Nullable
    String resolveParameter(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.owner != null && s.owner.findParameter(name) != null) {
                return s.parameterRenames.getOrDefault(name, name);
            }
        }
        return null;
    }

    /**
     * @return The name the exception of the innermost catch is written as, or {@code null} outside of a catch
     */
    @Nullable
    String caughtException() {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.caughtException != null) {
                return s.caughtException;
            }
        }
        return null;
    }

    /**
     * Declares a parameter of the method this scope belongs to: under its own name, or another where it is a
     * keyword.
     *
     * @param name The name of the parameter in the model
     * @return The name it is written as
     */
    String declareParameter(String name) {
        return declareParameterAs(name, escaped(name));
    }

    /**
     * Declares a parameter of the lambda this scope belongs to: under another name where the enclosing scope has
     * its name in scope, which Java forbids a lambda parameter to shadow, or where it is a keyword.
     *
     * @param name The name of the parameter in the model
     * @return The name it is written as
     */
    String declareLambdaParameter(String name) {
        return declareParameterAs(name, parent != null && parent.isTaken(name) ? allocate(name) : escaped(name));
    }

    private String declareParameterAs(String name, String emittedName) {
        parameterRenames.put(name, emittedName);
        taken.add(emittedName);
        return emittedName;
    }

    /**
     * Declares a local of the model in this scope, under another name where its own is no Java name - a keyword - or
     * is one an enclosing scope has in scope, which Java forbids a local of a lambda body or a nested block to shadow.
     *
     * @param name The name of the local in the model
     * @return The name it is written as
     */
    String declareLocal(String name) {
        String emittedName = !isKeyword(name) && parent != null && parent.isTaken(name) ? allocate(name) : escaped(name);
        if (!emittedName.equals(name)) {
            localRenames.put(name, emittedName);
        }
        taken.add(emittedName);
        return emittedName;
    }

    /**
     * Declares a copy of a local, which the local is written as from here on.
     *
     * @param name The name of the local in the model
     * @return The name of the copy
     */
    String declareCopy(String name) {
        String copy = allocate(name);
        renameLocal(name, copy);
        return copy;
    }

    /**
     * Records that a local is written under another name from here on.
     *
     * @param name        The name in the model
     * @param emittedName The name to emit
     */
    void renameLocal(String name, String emittedName) {
        localRenames.put(name, emittedName);
        taken.add(emittedName);
    }

    /**
     * Declares a variable the model does not have, which the source introduces: under the name asked for, or one
     * derived from it that nothing in scope has.
     *
     * @param name The preferred name
     * @return The name declared
     */
    String declareFresh(String name) {
        String declared = allocate(name);
        taken.add(declared);
        return declared;
    }

    /**
     * Declares the parameter of a catch in a scope nested in this one for the body of the catch: `e0`, `e1` and so
     * on, a name that neither a variable in scope, nor a local of the body, nor a field of the definition has - an
     * unqualified assignment of a field of that name would write the parameter instead.
     *
     * @param objectDef The definition being written
     * @param handler   The body of the catch
     * @param index     The index of the next catch parameter, which is advanced
     * @return The scope of the body of the catch, whose {@link #caughtException()} is the name
     */
    RenderScope catching(@Nullable ObjectDef objectDef, @Nullable StatementDef handler, int[] index) {
        RenderScope scope = new RenderScope(this, null);
        Set<String> handlerLocals = handler == null ? Set.of() : JavaSourceRules.declaredLocals(List.of(handler));
        String name = "e" + index[0]++;
        while (JavaSourceRules.declaresField(objectDef, name) || isTaken(name) || handlerLocals.contains(name)) {
            name = "e" + index[0]++;
        }
        scope.caughtException = name;
        scope.taken.add(name);
        return scope;
    }

    /**
     * @param name The name
     * @return True if the name is already used by this scope or any enclosing one
     */
    boolean isTaken(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.taken.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Allocates a name that is not used by this scope or any enclosing one.
     *
     * @param name The preferred name
     * @return The preferred name, or a name derived from it
     */
    String allocate(String name) {
        if (!isTaken(name) && !isReserved(name)) {
            return name;
        }
        int i = 1;
        String candidate = name + i;
        while (isTaken(candidate) || isReserved(candidate)) {
            candidate = name + ++i;
        }
        return candidate;
    }

    private boolean isReserved(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.reserved.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param name A name of the model
     * @return The name, or another derived from it where it is a keyword, which Java has no variable of
     */
    private String escaped(String name) {
        return isKeyword(name) ? allocate(name + "_") : name;
    }

    private static boolean isKeyword(String name) {
        return SourceVersion.isKeyword(name);
    }

    /**
     * The names the variables of a definition are declared by - its fields and properties, the parameters of its
     * methods, and the locals and lambda parameters of their bodies - which obscure a type of the same name (JLS
     * 6.4.2): {@code Math.max} of a local named {@code Math} calls a method of the local.
     *
     * @param objectDef The definition
     * @return The names, sorted
     */
    static Set<String> obscuringNames(ObjectDef objectDef) {
        Set<String> names = new TreeSet<>();
        objectDef.getProperties().forEach(property -> names.add(property.getName()));
        List<FieldDef> fields = objectDef instanceof ClassDef classDef ? classDef.getFields()
            : objectDef instanceof EnumDef enumDef ? enumDef.getFields() : List.of();
        fields.forEach(field -> names.add(field.getName()));
        for (MethodDef method : objectDef.getMethods()) {
            method.getParameters().forEach(parameter -> names.add(parameter.getName()));
            names.addAll(JavaLambdaRules.declaredNames(method.getStatements()));
        }
        if (objectDef instanceof ClassDef classDef && classDef.getStaticInitializer() != null) {
            names.addAll(JavaLambdaRules.declaredNames(List.of(classDef.getStaticInitializer())));
        }
        return names;
    }
}
