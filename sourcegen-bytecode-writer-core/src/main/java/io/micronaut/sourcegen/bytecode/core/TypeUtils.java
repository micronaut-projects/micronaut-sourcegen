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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM descriptor operations shared by bytecode writers.
 *
 * <p>This class deliberately has no dependency on a bytecode library. Backends can turn the
 * descriptors into the representation their emitter needs.</p>
 *
 * @since 2.2
 */
@Internal
public final class TypeUtils {

    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\[])+$");
    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";
    private static final Set<String> PRIMITIVE_NAMES = Set.of("void", "byte", "int", "boolean", "long", "char", "short", "double", "float");

    private TypeUtils() {
    }

    /**
     * Returns the descriptor of a method, outside the write of an inner class: no variables of an enclosing class are in scope.
     *
     * @param objectDef The contextual object, if any
     * @param methodDef The method
     * @return The JVM method descriptor
     */
    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return getMethodDescriptor(objectDef, methodDef, EnclosingScope.NONE);
    }

    /**
     * Returns the descriptor of a method.
     *
     * @param objectDef The contextual object, if any
     * @param methodDef The method
     * @param enclosing The enclosing scope of the class being written
     * @return The JVM method descriptor
     */
    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef, EnclosingScope enclosing) {
        StringBuilder descriptor = new StringBuilder("(");
        for (ParameterDef parameter : methodDef.getParameters()) {
            descriptor.append(getDescriptor(parameter.getType(), objectDef, methodDef, enclosing));
        }
        return descriptor.append(')')
            .append(getDescriptor(Objects.requireNonNullElse(methodDef.getReturnType(), TypeDef.VOID), objectDef, methodDef, enclosing))
            .toString();
    }

    /**
     * Returns the erased JVM descriptor of a Sourcegen type, outside the write of an inner class: no variables of an enclosing class are in scope.
     *
     * @param typeDef   The type
     * @param objectDef The contextual object, if any
     * @return The JVM descriptor
     */
    public static String getDescriptor(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        return getDescriptor(typeDef, objectDef, null, EnclosingScope.NONE);
    }

    /**
     * Returns the erased JVM descriptor of a Sourcegen type.
     *
     * @param typeDef   The type
     * @param objectDef The contextual object, if any
     * @param enclosing The enclosing scope of the class being written
     * @return The JVM descriptor
     */
    public static String getDescriptor(TypeDef typeDef, @Nullable ObjectDef objectDef, EnclosingScope enclosing) {
        return getDescriptor(typeDef, objectDef, null, enclosing);
    }

    /**
     * Returns the erased JVM descriptor of a Sourcegen type in the scope of a method: a variable the method
     * declares shadows one of the class of the same name.
     *
     * @param typeDef   The type
     * @param objectDef The contextual object, if any
     * @param methodDef The method whose variables are in scope, if any
     * @param enclosing The enclosing scope of the class being written
     * @return The JVM descriptor
     * @since 2.3
     */
    public static String getDescriptor(TypeDef typeDef,
                                       @Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       EnclosingScope enclosing) {
        return descriptor(typeDef, objectDef, methodDef, enclosing, new HashSet<>());
    }

    private static String descriptor(TypeDef typeDef,
                                     @Nullable ObjectDef objectDef,
                                     @Nullable MethodDef methodDef,
                                     EnclosingScope enclosing,
                                     Set<String> visited) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return descriptor(annotated.typeDef(), objectDef, methodDef, enclosing, visited);
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return descriptor(annotated.typeDef(), objectDef, methodDef, enclosing, visited);
        }
        if (typeDef instanceof TypeDef.Array array) {
            return "[".repeat(array.dimensions()) + descriptor(array.componentType(), objectDef, methodDef, enclosing, visited);
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            return descriptor(parameterized.rawType(), objectDef, methodDef, enclosing, visited);
        }
        if (typeDef instanceof ClassTypeDef classTypeDef) {
            return getDescriptor(getBinaryName(classTypeDef, objectDef));
        }
        if (typeDef instanceof TypeDef.Primitive primitive) {
            return primitiveDescriptor(primitive.name());
        }
        if (typeDef instanceof TypeDef.Wildcard wildcard) {
            List<TypeDef> bounds = !wildcard.lowerBounds().isEmpty()
                ? wildcard.lowerBounds()
                : wildcard.upperBounds();
            return bounds.isEmpty() ? OBJECT_DESCRIPTOR : descriptor(bounds.get(0), objectDef, methodDef, enclosing, visited);
        }
        if (typeDef instanceof TypeDef.TypeVariable variable) {
            // A name alone: the method's variable, else the class's
            boolean methodVariable = methodDef != null && methodDef.getTypeVariables().stream()
                .anyMatch(declared -> declared.name().equals(variable.name()));
            List<TypeDef> bounds = variable.bounds();
            if (bounds.isEmpty()) {
                TypeDef.TypeVariable declaration = methodVariable && methodDef != null ? methodDef.getTypeVariables().stream()
                    .filter(declared -> declared.name().equals(variable.name())).findFirst().orElse(null)
                    : findTypeVariable(objectDef, variable.name(), enclosing);
                bounds = declaration == null ? List.of() : declaration.bounds();
            }
            // The erasure of a variable is that of its leftmost bound (JLS 4.6), `Object` for `T extends Object & Runnable`
            String key = (methodVariable ? "method:" : "class:") + variable.name();
            if (bounds.isEmpty() || !visited.add(key)) {
                return OBJECT_DESCRIPTOR;
            }
            // A bound of a class variable is in the scope of the class, where a variable of the method is not
            String erased = descriptor(bounds.get(0), objectDef, methodVariable ? methodDef : null, enclosing, visited);
            visited.remove(key);
            return erased;
        }
        throw new IllegalStateException("Unsupported type: " + typeDef);
    }

    /**
     * The type a call's receiver is written as, outside the write of an inner class: no variables of an enclosing class are in scope.
     *
     * @param instanceType The type of the receiver
     * @param method       The invoked method
     * @param objectDef    The definition being written, if any
     * @param enclosing    The method being written, if any
     * @return The receiver type, and whether the receiver is cast to it
     * @since 2.3
     */
    public static Receiver receiverOf(TypeDef instanceType,
                                      MethodDef method,
                                      @Nullable ObjectDef objectDef,
                                      @Nullable MethodDef enclosing) {
        return receiverOf(instanceType, method, objectDef, enclosing, EnclosingScope.NONE);
    }

    /**
     * The type a call's receiver is written as.
     *
     * @param instanceType The type of the receiver
     * @param method       The invoked method
     * @param objectDef    The definition being written, if any
     * @param enclosing    The method being written, if any
     * @param scope        The enclosing scope of the class being written
     * @return The receiver type, and whether the receiver is cast to it
     * @since 2.3
     */
    public static Receiver receiverOf(TypeDef instanceType,
                                      MethodDef method,
                                      @Nullable ObjectDef objectDef,
                                      @Nullable MethodDef enclosing,
                                      EnclosingScope scope) {
        if (!(TypeHierarchy.unwrap(instanceType) instanceof TypeDef.TypeVariable variable)) {
            return new Receiver(instanceType, false);
        }
        List<TypeDef> bounds = variableBounds(variable, objectDef, enclosing, scope, new HashSet<>());
        if (bounds.isEmpty()) {
            return new Receiver(TypeDef.OBJECT, false);
        }
        for (int i = 0; i < bounds.size(); i++) {
            if (declares(bounds.get(i), method, scope)) {
                return new Receiver(bounds.get(i), i > 0);
            }
        }
        return new Receiver(bounds.get(0), false);
    }

    /**
     * The bounds of a variable in the scope of a method and a definition, a bound that is another variable being
     * that variable's bounds.
     */
    private static List<TypeDef> variableBounds(TypeDef.TypeVariable variable,
                                                @Nullable ObjectDef objectDef,
                                                @Nullable MethodDef enclosing,
                                                EnclosingScope enclosingScope,
                                                Set<String> visited) {
        if (!visited.add(variable.name())) {
            return List.of();
        }
        TypeDef.TypeVariable methodDeclaration = enclosing == null ? null : enclosing.getTypeVariables().stream()
            .filter(declared -> declared.name().equals(variable.name())).findFirst().orElse(null);
        List<TypeDef> bounds = variable.bounds();
        if (bounds.isEmpty()) {
            TypeDef.TypeVariable declaration = methodDeclaration != null ? methodDeclaration : findTypeVariable(objectDef, variable.name(), enclosingScope);
            bounds = declaration == null ? List.of() : declaration.bounds();
        }
        // A bound of a class variable is in the scope of the class, where a variable of the method is not
        MethodDef scope = methodDeclaration != null ? enclosing : null;
        List<TypeDef> result = new java.util.ArrayList<>();
        for (TypeDef bound : bounds) {
            TypeDef unwrapped = TypeHierarchy.unwrap(bound);
            if (unwrapped instanceof TypeDef.TypeVariable other) {
                result.addAll(variableBounds(other, objectDef, scope, enclosingScope, visited));
            } else {
                result.add(unwrapped);
            }
        }
        return result;
    }

    private static boolean declares(TypeDef bound, MethodDef method, EnclosingScope enclosing) {
        // The full erased signature tells overloads of the bounds apart: `choose(Integer)` of one bound is not the
        // `choose(String)` of another, nor `Integer value()` the `Number value()` of another
        List<String> wanted = method.getParameters().stream()
            .map(parameter -> getDescriptor(parameter.getType(), null, method, enclosing)).toList();
        String wantedReturn = getDescriptor(method.getReturnType(), null, method, enclosing);
        return declares(bound, method.getName(), wanted, wantedReturn, enclosing, new HashSet<>());
    }

    private static boolean declares(TypeDef bound,
                                    String name,
                                    List<String> wanted,
                                    String wantedReturn,
                                    EnclosingScope enclosing,
                                    Set<String> visited) {
        TypeDef raw = TypeOperations.rawClassOf(bound);
        if (!(raw instanceof ClassTypeDef classTypeDef) || !visited.add(classTypeDef.getName())) {
            return false;
        }
        if (raw instanceof ClassTypeDef.ClassDefType classDefType) {
            ObjectDef definition = classDefType.objectDef();
            boolean declared = definition.getMethods().stream().anyMatch(method -> method.getName().equals(name)
                && method.getParameters().stream().map(parameter -> getDescriptor(parameter.getType(), definition, method, enclosing))
                .toList().equals(wanted) && getDescriptor(method.getReturnType(), definition, method, enclosing).equals(wantedReturn));
            // A generated type inherits methods from its supertypes too
            return declared || TypeHierarchy.superTypesOf(definition).stream()
                .anyMatch(superType -> declares(superType, name, wanted, wantedReturn, enclosing, visited));
        }
        if (raw instanceof ClassTypeDef.ClassElementType elementType) {
            // A type being compiled, which no class loader has: the compiler describes its members
            return elementType.classElement().getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS.named(name))
                .stream().anyMatch(declared -> java.util.Arrays.stream(declared.getParameters())
                    .map(parameter -> elementDescriptor(parameter.getType())).toList().equals(wanted)
                    && elementDescriptor(declared.getReturnType()).equals(wantedReturn));
        }
        Class<?> type = TypeLookup.reflective().loadClass(classTypeDef.getName());
        if (type == null) {
            return false;
        }
        return java.util.Arrays.stream(type.getMethods()).anyMatch(declared -> declared.getName().equals(name)
            && java.util.Arrays.stream(declared.getParameterTypes()).map(parameter -> getDescriptor(TypeDef.of(parameter), null, enclosing))
            .toList().equals(wanted) && getDescriptor(TypeDef.of(declared.getReturnType()), null, enclosing).equals(wantedReturn));
    }

    /**
     * Whether a definition declares a method of a name and erased parameters itself - a record member it declares
     * replaces the one a writer would generate.
     *
     * @param objectDef      The definition
     * @param name           The name of the method
     * @param parameterTypes The types of its parameters
     * @param enclosing      The enclosing scope of the class being written
     * @return true where the definition declares it
     * @since 2.3
     */
    public static boolean declaresMethod(ObjectDef objectDef, String name, List<TypeDef> parameterTypes, EnclosingScope enclosing) {
        List<String> descriptors = parameterTypes.stream().map(type -> getDescriptor(type, objectDef, enclosing)).toList();
        return objectDef.getMethods().stream()
            .filter(method -> method.getName().equals(name))
            .anyMatch(method -> method.getParameters().stream()
                .map(parameter -> getDescriptor(parameter.getType(), objectDef, enclosing))
                .toList().equals(descriptors));
    }

    static String elementDescriptor(io.micronaut.inject.ast.ClassElement type) {
        String name = type.getName().replaceAll("(\\[])+$", "");
        String component = PRIMITIVE_NAMES.contains(name) ? primitiveDescriptor(name) : getDescriptor(name);
        return "[".repeat(type.getArrayDimensions()) + component;
    }

    /**
     * An invoked method as the class declaring it sees it: a variable of that class, which a caller's own of the
     * same name would shadow, carries the bounds it is declared with - so that the values passed, the descriptor
     * and the result all erase as the declaring class erases them.
     *
     * @param method    The invoked method
     * @param declaring The definition declaring it, if generated
     * @param current   The definition being written, if any
     * @return The method, with the declaring class's variables bounded inline
     * @since 2.3
     */
    public static MethodDef inDeclaringScope(MethodDef method, @Nullable ObjectDef declaring, @Nullable ObjectDef current) {
        // The class's variables, with their bound chains expanded in the scope of the class: a variable of the
        // method of the same name does not capture them
        java.util.Map<String, TypeDef> classScope = new java.util.HashMap<>();
        if (declaring != null && declaring != current) {
            for (TypeDef.TypeVariable variable : TypeOperations.typeVariablesOf(declaring)) {
                classScope.put(variable.name(), bounded(variable));
            }
            expand(classScope, classScope);
        }
        // The method's own variables shadow the class's, and the caller's of the same name: an unbounded one is an
        // `Object`, not a variable the caller could take for its own
        java.util.Map<String, TypeDef> methodScope = new java.util.HashMap<>();
        for (TypeDef.TypeVariable variable : method.getTypeVariables()) {
            methodScope.put(variable.name(), bounded(variable));
        }
        java.util.Map<String, TypeDef> bounded = new java.util.HashMap<>(classScope);
        bounded.putAll(methodScope);
        expand(methodScope, bounded);
        bounded.putAll(methodScope);
        if (bounded.isEmpty()) {
            return method;
        }
        MethodDef.MethodDefBuilder builder = MethodDef.builder(method.getName())
            .addModifiers(method.getModifiers())
            .returns(TypeHierarchy.substituted(method.getReturnType(), bounded));
        method.getTypeVariables().forEach(builder::addTypeVariable);
        method.getParameters().forEach(parameter -> builder.addParameter(parameter.getName(),
            TypeHierarchy.substituted(parameter.getType(), bounded)));
        builder.addThrows(method.getThrowTypes());
        return builder.build();
    }

    /**
     * The variables in scope where a call is written, with their bounds: the class's, and the method's shadowing them.
     *
     * @param objectDef The definition being written, if any
     * @param methodDef The method being written, if any
     * @return The variables by name
     * @since 2.3
     */
    public static java.util.Map<String, TypeDef> variableScope(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        java.util.Map<String, TypeDef> scope = new java.util.HashMap<>();
        TypeOperations.typeVariablesOf(objectDef).forEach(variable -> scope.put(variable.name(), bounded(variable)));
        if (methodDef != null) {
            methodDef.getTypeVariables().forEach(variable -> scope.put(variable.name(), bounded(variable)));
        }
        return scope;
    }

    /**
     * Whether an argument is passed without a cast, as javac passes it: the array a variable arity tail is packed into,
     * of interfaces, for a parameter that is an array of other interfaces of the same rank, which the verifier takes as
     * Objects - the `Alpha[]` javac packs for the `Zeta[]` of a `T extends Zeta & Alpha`. A cast between such arrays
     * that the model asks for is checked, as javac checks `(Zeta[]) alphas`.
     *
     * @param value     The argument
     * @param parameter The type of the parameter
     * @return true where no cast is written
     * @since 2.3
     */
    public static boolean packedInterfaceArray(ExpressionDef value, TypeDef parameter) {
        return value instanceof ExpressionDef.NewArrayInitialized && interfaceArrays(value.type(), parameter);
    }

    /**
     * Whether a cast between two references checks the value, as a narrowing reference conversion does (JLS 5.1.6):
     * the type of the value is not known to be the target or a subtype of it. A writer leaves a cast of a chain of casts
     * out only where it checks nothing, and writes {@code (Object) (Zeta[]) alphas} with the checkcast javac writes.
     *
     * @param from      The type of the value
     * @param to        The type it is cast to
     * @param objectDef The definition being written, if any
     * @param methodDef The method being written, if any
     * @param enclosing The enclosing scope of the class being written
     * @return true where the cast checks the value
     * @since 2.3
     */
    public static boolean checksReference(TypeDef from,
                                          TypeDef to,
                                          @Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef,
                                          EnclosingScope enclosing) {
        String fromDescriptor = getDescriptor(from, objectDef, methodDef, enclosing);
        String toDescriptor = getDescriptor(to, objectDef, methodDef, enclosing);
        if (!isReference(fromDescriptor) || !isReference(toDescriptor)) {
            return false;
        }
        return !assignable(ObjectDef.getContextualType(objectDef, from), fromDescriptor, toDescriptor);
    }

    private static boolean isReference(String descriptor) {
        return descriptor.charAt(0) == 'L' || descriptor.charAt(0) == '[';
    }

    private static boolean assignable(@Nullable TypeDef from, String fromDescriptor, String toDescriptor) {
        if (fromDescriptor.equals(toDescriptor) || toDescriptor.equals(OBJECT_DESCRIPTOR)) {
            return true;
        }
        if (fromDescriptor.charAt(0) == '[') {
            if (toDescriptor.equals("Ljava/lang/Cloneable;") || toDescriptor.equals("Ljava/io/Serializable;")) {
                return true;
            }
            if (toDescriptor.charAt(0) != '[') {
                return false;
            }
            TypeDef component = from != null && TypeHierarchy.unwrap(from) instanceof TypeDef.Array array
                ? (array.dimensions() > 1 ? TypeDef.array(array.componentType(), array.dimensions() - 1) : array.componentType())
                : null;
            String fromComponent = fromDescriptor.substring(1);
            String toComponent = toDescriptor.substring(1);
            return isReference(fromComponent) && isReference(toComponent) ? assignable(component, fromComponent, toComponent)
                : fromComponent.equals(toComponent);
        }
        if (toDescriptor.charAt(0) == '[') {
            return false;
        }
        String fromName = fromDescriptor.substring(1, fromDescriptor.length() - 1).replace('/', '.');
        TypeDef raw = from == null ? null : TypeOperations.rawClassOf(from);
        ClassTypeDef fromClass;
        if (raw instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(fromName)) {
            fromClass = classTypeDef;
        } else {
            Class<?> loaded = TypeLookup.reflective().loadClass(fromName);
            if (loaded == null) {
                // Not known: the cast is written
                return false;
            }
            fromClass = ClassTypeDef.of(loaded);
        }
        return TypeHierarchy.inherits(fromClass, toDescriptor.substring(1, toDescriptor.length() - 1).replace('/', '.'), null);
    }

    /**
     * Whether both types are arrays of the same rank whose components are interfaces.
     *
     * @param from The type of the value
     * @param to   The type it is converted to
     * @return true for arrays of interfaces
     * @since 2.3
     */
    public static boolean interfaceArrays(TypeDef from, TypeDef to) {
        return TypeHierarchy.unwrap(from) instanceof TypeDef.Array fromArray && TypeHierarchy.unwrap(to) instanceof TypeDef.Array toArray
            && fromArray.dimensions() == toArray.dimensions()
            && TypeHierarchy.unwrap(fromArray.componentType()) instanceof ClassTypeDef fromComponent && fromComponent.isInterface()
            && TypeHierarchy.unwrap(toArray.componentType()) instanceof ClassTypeDef toComponent && toComponent.isInterface();
    }

    /**
     * A method as it reads in the body of another: the variables the enclosing method declares are in scope, unless
     * the method shadows them - the instantiated signature of a lambda, which names the enclosing method's variables.
     *
     * @param method    The method
     * @param enclosing The method it is written in, if any
     * @return The method, declaring the enclosing method's variables too
     * @since 2.3
     */
    public static MethodDef withEnclosingVariables(MethodDef method, @Nullable MethodDef enclosing) {
        if (enclosing == null || enclosing.getTypeVariables().isEmpty()) {
            return method;
        }
        MethodDef.MethodDefBuilder builder = MethodDef.builder(method.getName())
            .addModifiers(method.getModifiers())
            .returns(method.getReturnType());
        method.getTypeVariables().forEach(builder::addTypeVariable);
        enclosing.getTypeVariables().stream()
            .filter(variable -> method.getTypeVariables().stream().noneMatch(own -> own.name().equals(variable.name())))
            .forEach(builder::addTypeVariable);
        method.getParameters().forEach(parameter -> builder.addParameter(parameter.getName(), parameter.getType()));
        return builder.build();
    }

    /**
     * The erasure of a type as a class type, in the scope of a method: what a member that declares no variables -
     * a bridge - writes for the type its delegate declares.
     *
     * @param typeDef   The type
     * @param objectDef The definition, if any
     * @param methodDef The method whose variables are in scope, if any
     * @param enclosing The enclosing scope of the class being written
     * @return The erased type
     * @since 2.3
     */
    public static TypeDef erased(TypeDef typeDef, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, EnclosingScope enclosing) {
        String descriptor = getDescriptor(typeDef, objectDef, methodDef, enclosing);
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return ClassTypeDef.of(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
        return typeDef;
    }

    private static TypeDef.TypeVariable bounded(TypeDef.TypeVariable variable) {
        return variable.bounds().isEmpty() ? TypeDef.variable(variable.name(), List.of(TypeDef.OBJECT)) : variable;
    }

    /**
     * Substitutes the variables of a scope into the bounds of others - `A extends B` carries B's bounds - a few
     * levels deep, which the erasure, taking the first bound of each, needs.
     */
    private static void expand(java.util.Map<String, TypeDef> target, java.util.Map<String, TypeDef> scope) {
        for (int pass = 0; pass < 3; pass++) {
            java.util.Map<String, TypeDef> previous = new java.util.HashMap<>(scope);
            previous.putAll(target);
            for (java.util.Map.Entry<String, TypeDef> entry : new java.util.ArrayList<>(target.entrySet())) {
                TypeDef.TypeVariable variable = (TypeDef.TypeVariable) entry.getValue();
                target.put(entry.getKey(), TypeDef.variable(variable.name(), variable.bounds().stream()
                    .map(bound -> TypeHierarchy.substituted(bound, previous)).toList()));
            }
        }
    }

    /**
     * The definition whose variables a member of a type is declared with: the generated definition the type names,
     * or the one being written, where no other is known.
     *
     * @param owner     The type declaring the member
     * @param objectDef The definition being written, if any
     * @return The definition to erase the member's types in
     * @since 2.3
     */
    @Nullable
    public static ObjectDef declaringScope(@Nullable TypeDef owner, @Nullable ObjectDef objectDef) {
        TypeDef raw = owner == null ? null : TypeOperations.rawClassOf(owner);
        return raw instanceof ClassTypeDef.ClassDefType classDefType ? classDefType.objectDef() : objectDef;
    }

    /**
     * The definition declaring a method invoked on a type: the generated type, or the generated supertype it inherits
     * the method from, whose variables the method is declared with.
     *
     * @param owner     The type the method is invoked on
     * @param objectDef The definition being written, if any
     * @param method    The invoked method
     * @param enclosing The enclosing scope of the class being written
     * @return The definition to erase the method in
     * @since 2.3
     */
    @Nullable
    public static ObjectDef declaringScope(@Nullable TypeDef owner, @Nullable ObjectDef objectDef, MethodDef method, EnclosingScope enclosing) {
        ObjectDef scope = declaringScope(owner, objectDef);
        if (scope == null) {
            return null;
        }
        // The declaration itself, else one of the same erased signature, else of the name and arity: an overload of a
        // subclass does not hide the superclass declaring the method
        ObjectDef declaring = declaringDefinition(scope, candidate -> candidate.getMethods().stream()
            .anyMatch(declared -> declared == method), new HashSet<>());
        if (declaring == null) {
            List<String> wanted = method.getParameters().stream().map(parameter -> getDescriptor(parameter.getType(), null, method, enclosing)).toList();
            declaring = declaringDefinition(scope, candidate -> candidate.getMethods().stream()
                .anyMatch(declared -> declared.getName().equals(method.getName()) && declared.getParameters().stream()
                    .map(parameter -> getDescriptor(parameter.getType(), candidate, declared, enclosing)).toList().equals(wanted)), new HashSet<>());
        }
        if (declaring == null) {
            declaring = declaringDefinition(scope, candidate -> candidate.getMethods().stream()
                .anyMatch(declared -> declared.getName().equals(method.getName())
                    && declared.getParameters().size() == method.getParameters().size()), new HashSet<>());
        }
        return declaring == null ? scope : declaring;
    }

    /**
     * The definition declaring a field read on a type: the generated type, or the generated supertype it inherits
     * the field from.
     *
     * @param owner     The type the field is read on
     * @param objectDef The definition being written, if any
     * @param fieldName The field name
     * @return The definition to erase the field in
     * @since 2.3
     */
    @Nullable
    public static ObjectDef fieldScope(@Nullable TypeDef owner, @Nullable ObjectDef objectDef, String fieldName) {
        ObjectDef scope = declaringScope(owner, objectDef);
        ObjectDef declaring = scope == null ? null : declaringDefinition(scope, candidate -> candidate instanceof ClassDef classDef
            && classDef.getFields().stream().anyMatch(field -> field.getName().equals(fieldName)), new HashSet<>());
        return declaring == null ? scope : declaring;
    }

    @Nullable
    private static ObjectDef declaringDefinition(ObjectDef definition, java.util.function.Predicate<ObjectDef> declares, Set<String> visited) {
        if (!visited.add(definition.getName())) {
            return null;
        }
        if (declares.test(definition)) {
            return definition;
        }
        for (TypeDef superType : TypeHierarchy.superTypesOf(definition)) {
            TypeDef raw = TypeOperations.rawClassOf(superType);
            if (raw instanceof ClassTypeDef.ClassDefType classDefType) {
                ObjectDef found = declaringDefinition(classDefType.objectDef(), declares, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Returns the binary name a reference to a type resolves to in the scope of the type being written.
     *
     * <p>The model is immutable, so {@link io.micronaut.sourcegen.model.ObjectDefBuilder#addInnerType} can
     * only qualify the copy of a member type it stores; the definition the caller holds on to keeps the
     * simple name it was built with, and a reference taken from it reads as {@code Inner} rather than
     * {@code com.example.Outer$Inner}. Java source has scoping that makes such a reference resolve anyway -
     * javac reads {@code Inner} inside {@code Outer} as its member type - while a class file has none: every
     * name in it is a binary name. This applies the same scoping the source generator relies on, so that
     * both generators accept the same definition.</p>
     *
     * <p>An unqualified name is resolved against the type being written and the member types it declares,
     * which is the scope a definition knows about. A name that is already qualified, and one that matches
     * nothing in scope, is left as it is.</p>
     *
     * @param classTypeDef The referenced type
     * @param objectDef    The contextual object, if any
     * @return The binary name to write
     */
    public static String getBinaryName(ClassTypeDef classTypeDef, @Nullable ObjectDef objectDef) {
        return TypeHierarchy.binaryName(TypeHierarchy.memberClass(classTypeDef), objectDef);
    }

    /**
     * How deep a type is nested in the enclosing types it names: 0 for {@code Outer<String>}, 1 for its member
     * {@code Outer<String>.Inner}, 2 for {@code Outer<String>.Inner<Integer>.Deep}. A type annotation of the member,
     * or of its type arguments, is reached through as many nested type steps.
     *
     * @param type The type
     * @return The depth
     * @since 2.3
     */
    public static int memberDepth(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return memberDepth(parameterized.rawType());
        }
        ClassTypeDef enclosing = unwrapped instanceof ClassTypeDef classType ? TypeHierarchy.enclosingOf(classType) : null;
        if (enclosing != null) {
            return 1 + memberDepth(enclosing);
        }
        return 0;
    }

    /**
     * Returns a descriptor for a binary class name or a source-style array name.
     *
     * @param className The class name
     * @return The descriptor
     */
    public static String getDescriptor(String className) {
        if (className.startsWith("[")) {
            // The binary name of an array class, as returned by Class#getName, is already a
            // descriptor: [Lcom.Example; or [I. Wrapping it again would produce L[Lcom/Example;;
            return className.replace('.', '/');
        }
        Matcher matcher = ARRAY_PATTERN.matcher(className);
        StringBuilder result = new StringBuilder();
        if (matcher.find()) {
            result.append("[".repeat(matcher.group(0).length() / 2));
            className = matcher.replaceFirst("");
        }
        result.append('L').append(getInternalName(className)).append(';');
        return result.toString();
    }

    /**
     * Returns the internal JVM name of a class.
     *
     * @param className The binary name
     * @return The internal name
     */
    public static String getInternalName(String className) {
        return ARRAY_PATTERN.matcher(className.replace('.', '/')).replaceFirst("");
    }

    private static String primitiveDescriptor(String name) {
        return switch (name) {
            case "void" -> "V";
            case "byte" -> "B";
            case "int" -> "I";
            case "boolean" -> "Z";
            case "long" -> "J";
            case "char" -> "C";
            case "short" -> "S";
            case "double" -> "D";
            case "float" -> "F";
            default -> throw new IllegalStateException("Expected a primitive type, got: " + name);
        };
    }

    private static TypeDef.@Nullable TypeVariable findTypeVariable(@Nullable ObjectDef objectDef,
                                                                  String name,
                                                                  EnclosingScope enclosing) {
        return TypeOperations.typeVariablesOf(objectDef).stream()
            .filter(variable -> variable.name().equals(name))
            .findFirst()
            .orElseGet(() -> enclosing.variable(objectDef, name));
    }

    /**
     * The type a call's receiver is written as. A receiver of a type variable is its erasure, unless the method
     * is declared by a later bound only - {@code compareTo} of a {@code T extends CharSequence & Comparable<T>} -
     * which the receiver is then cast to, as javac does.
     *
     * @param type The type the receiver is written as
     * @param cast Whether the receiver is cast to it
     * @since 2.3
     */
    public record Receiver(TypeDef type, boolean cast) {
    }

}
