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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * What the generation of one file resolves against: the outermost definition being written, whose inner types name it
 * and each other, and the visitor context the file is written with, which knows the types only known by name. A
 * generator creates one for each file it writes and passes it to the rules that depend on either.
 *
 * @since 2.3
 */
@Internal
public final class GenerationScope {

    private static final GenerationScope NONE = new GenerationScope(null, null);

    @Nullable
    private final ObjectDef written;
    @Nullable
    private final VisitorContext visitorContext;
    private final TypeLookup typeLookup;
    private final TypeHierarchy.Lookup hierarchyLookup;
    private final TypeHierarchy.Lookup definitionsLookup;

    private GenerationScope(@Nullable ObjectDef written, @Nullable VisitorContext visitorContext) {
        this.written = written;
        this.visitorContext = visitorContext;
        this.typeLookup = TypeLookup.of(visitorContext);
        this.definitionsLookup = TypeHierarchy.Lookup.of(this::writtenDefinition, null);
        this.hierarchyLookup = visitorContext == null ? definitionsLookup
            : TypeHierarchy.Lookup.of(this::writtenDefinition, typeLookup::classElement);
    }

    /**
     * @return The scope of no file: no definition is being written, and no context knows the types named
     */
    public static GenerationScope none() {
        return NONE;
    }

    /**
     * @param written        The outermost definition of the file being written, or {@code null}
     * @param visitorContext The context the file is written with, or {@code null}
     * @return The scope
     */
    public static GenerationScope of(@Nullable ObjectDef written, @Nullable VisitorContext visitorContext) {
        return written == null && visitorContext == null ? NONE : new GenerationScope(written, visitorContext);
    }

    /**
     * @return The same scope without the visitor context: the definitions of the file alone
     */
    public GenerationScope withoutVisitorContext() {
        return visitorContext == null ? this : of(written, null);
    }

    /**
     * @return The outermost definition being written, or {@code null}
     */
    @Nullable
    public ObjectDef written() {
        return written;
    }

    /**
     * @return The context the file is written with, or {@code null}
     */
    @Nullable
    public VisitorContext visitorContext() {
        return visitorContext;
    }

    /**
     * @return The lookup of the elements of the context and of the loaded classes
     */
    public TypeLookup typeLookup() {
        return typeLookup;
    }

    /**
     * @return What the hierarchy of a definition is looked up in: the definitions of the file being written - a member
     * type named by its simple name, or naming its enclosing type by name - and the elements of the context
     */
    public TypeHierarchy.Lookup hierarchyLookup() {
        return hierarchyLookup;
    }

    /**
     * @return The lookup of the definitions of the file being written alone
     */
    public TypeHierarchy.Lookup definitionsLookup() {
        return definitionsLookup;
    }

    /**
     * The definition a type names, where it is one of the file being written.
     *
     * @param owner The type, or {@code null}
     * @return The definition, or {@code null} where the type is no generated definition
     */
    @Nullable
    public ObjectDef definitionOf(@Nullable ClassTypeDef owner) {
        return definitionOf(owner, null);
    }

    /**
     * The definition a method is invoked on, where it is generated: the one the owner names, or the one being written.
     *
     * @param owner   The type declaring the invoked method, or {@code null}
     * @param current The definition being written, or {@code null}
     * @return The definition, or {@code null} where the owner is no generated definition
     */
    @Nullable
    public ObjectDef definitionOf(@Nullable ClassTypeDef owner, @Nullable ObjectDef current) {
        ClassTypeDef raw = owner == null ? null : TypeOperations.rawClass(owner);
        if (raw instanceof ClassTypeDef.ClassDefType classDefType) {
            return classDefType.objectDef();
        }
        if (raw != null && current != null && raw.getName().equals(current.asTypeDef().getName())) {
            return current;
        }
        // A type of the file being written, named from another of them: an inner type and the types enclosing it
        return raw == null || written == null ? null : named(written, raw.getName());
    }

    /**
     * The variables of the types enclosing a definition that are in its scope: those of the enclosing class of an
     * inner class - a member class that is not static - and of its enclosing class in turn, the nearest first.
     * The definitions of the file being written tell which types enclose it.
     *
     * @param definition The definition
     * @return The variables, empty for a top level type or a static member
     */
    public List<TypeDef.TypeVariable> enclosingVariables(@Nullable ObjectDef definition) {
        if (definition == null || written == null || written == definition) {
            return List.of();
        }
        List<TypeDef.TypeVariable> found = enclosingVariables(written, definition, List.of());
        return found == null ? List.of() : found;
    }

    /**
     * @return The lookup of the context's elements by name, or {@code null} without a context
     */
    @Nullable
    public Function<String, @Nullable ClassElement> elementLookup() {
        return visitorContext == null ? null : typeLookup::classElement;
    }

    @Nullable
    private static List<TypeDef.TypeVariable> enclosingVariables(ObjectDef enclosing,
                                                                 ObjectDef definition,
                                                                 List<TypeDef.TypeVariable> inScope) {
        for (ObjectDef member : enclosing.getInnerTypes()) {
            List<TypeDef.TypeVariable> scope = List.of();
            if (isInnerClass(member, enclosing)) {
                List<TypeDef.TypeVariable> variables = new ArrayList<>(TypeOperations.typeVariablesOf(enclosing));
                variables.addAll(inScope);
                scope = variables;
            }
            if (member == definition || member.getName().equals(definition.getName())) {
                return scope;
            }
            List<TypeDef.TypeVariable> found = enclosingVariables(member, definition, scope);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Whether a member type has an enclosing instance, whose variables are in its scope: a class that is not static,
     * of a type that is no interface - a member of an interface, an enum, a record and an interface are static.
     */
    private static boolean isInnerClass(ObjectDef member, ObjectDef enclosing) {
        return member instanceof ClassDef classDef && !classDef.getModifiers().contains(Modifier.STATIC)
            && !(enclosing instanceof InterfaceDef) && !(enclosing instanceof AnnotationObjectDef);
    }

    /**
     * A definition of the file being written: by its binary name, or a member type by its simple name, which the
     * definition a caller holds on to keeps.
     */
    @Nullable
    private ObjectDef writtenDefinition(String name) {
        ObjectDef outermost = written;
        if (outermost == null) {
            return null;
        }
        ObjectDef found = named(outermost, name);
        if (found != null || name.indexOf('.') != -1 || name.indexOf('$') != -1) {
            return found;
        }
        ObjectDef member = null;
        Deque<ObjectDef> queue = new ArrayDeque<>(outermost.getInnerTypes());
        while (!queue.isEmpty()) {
            ObjectDef candidate = queue.removeFirst();
            if (candidate.getSimpleName().equals(name)) {
                if (member != null) {
                    // Two member types of the name: which one is meant depends on where it is named
                    return null;
                }
                member = candidate;
            }
            queue.addAll(candidate.getInnerTypes());
        }
        return member;
    }

    @Nullable
    private static ObjectDef named(ObjectDef definition, String name) {
        if (definition.asTypeDef().getName().equals(name)) {
            return definition;
        }
        for (ObjectDef inner : definition.getInnerTypes()) {
            ObjectDef found = named(inner, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
