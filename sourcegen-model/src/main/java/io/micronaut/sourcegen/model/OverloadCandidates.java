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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.sourcegen.model.OverloadResolution.Caller;
import io.micronaut.sourcegen.model.OverloadResolution.Tri;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.sourcegen.model.Applicability.assignable;
import static io.micronaut.sourcegen.model.Applicability.referenceConvertible;
import static io.micronaut.sourcegen.model.Inference.expandChains;
import static io.micronaut.sourcegen.model.OverloadResolution.signature;
import static io.micronaut.sourcegen.model.ResolutionTypes.boundAt;
import static io.micronaut.sourcegen.model.TypeOperations.declaredClass;
import static io.micronaut.sourcegen.model.ResolutionTypes.erasedDeep;
import static io.micronaut.sourcegen.model.ResolutionTypes.isSubtype;
import static io.micronaut.sourcegen.model.ResolutionTypes.mentions;
import static io.micronaut.sourcegen.model.ResolutionTypes.sameErasure;

/**
 * The methods a call chooses among: those of a name the owners declare or inherit, by their erased signature, from
 * reflection, a compiler element or the model; the tiers of access a call takes them in; and the type arguments a
 * receiver binds the variables of the class declaring each of them with.
 *
 * @since 2.3
 */
final class OverloadCandidates {

    /**
     * The packages of the platform, which no generated class is in.
     */
    private static final List<String> PLATFORM_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private OverloadCandidates() {
    }

    /**
     * The methods of a name the types declare or inherit, and their constructors, by their erased signature: the
     * declaration nearest a receiver overrides the others of its signature, and one of the first type another type
     * declares too. {@code null} where a type carries no member information.
     */
    static @Nullable Map<String, Candidate> candidates(ResolutionContext context, List<ClassTypeDef> owners, String name) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        boolean constructor = MethodDef.CONSTRUCTOR.equals(name);
        for (ClassTypeDef annotatedOwner : owners) {
            // An annotation on the receiver's type leaves its members as they are
            if (!(TypeHierarchy.unwrap(annotatedOwner) instanceof ClassTypeDef owner)) {
                return null;
            }
            ClassTypeDef raw = declaredClass(owner);
            if (!collect(context, raw, name, owner, result, new HashSet<>(), true)) {
                return null;
            }
            if (!constructor && raw.isInterface()) {
                // An interface has the public methods of Object as members (JLS 9.2)
                Arrays.stream(Object.class.getMethods()).filter(method -> method.getName().equals(name))
                    .forEach(method -> add(context, result, reflected(method, MethodDef.of(method), owner), Map.of()));
            }
        }
        return result;
    }

    private static boolean collect(ResolutionContext context, ClassTypeDef raw, String name, ClassTypeDef receiver, Map<String, Candidate> result,
                                   Set<String> visited, boolean own) {
        if (!visited.add(raw.getName())) {
            return true;
        }
        boolean constructor = MethodDef.CONSTRUCTOR.equals(name);
        switch (raw) {
            case EnclosedClassType memberOf -> {
                visited.remove(raw.getName());
                return collect(context, memberOf.member(), name, receiver, result, visited, own);
            }
            case ClassTypeDef.JavaClass javaClass -> {
                Class<?> type = javaClass.type();
                if (constructor) {
                    for (Constructor<?> declared : type.getDeclaredConstructors()) {
                        if (!declared.isSynthetic()) {
                            add(context, result, reflected(declared, MethodDef.builder(declared).build(), receiver), Map.of());
                        }
                    }
                    return true;
                }
                // The public methods, then the others each class of the hierarchy declares
                Arrays.stream(type.getMethods()).filter(method -> method.getName().equals(name) && !method.isSynthetic() && !method.isBridge())
                    .forEach(method -> add(context, result, reflected(method, MethodDef.of(method), receiver), Map.of()));
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    Arrays.stream(current.getDeclaredMethods()).filter(method -> method.getName().equals(name) && !method.isSynthetic() && !method.isBridge())
                        .forEach(method -> add(context, result, reflected(method, MethodDef.of(method), receiver), Map.of()));
                }
                return true;
            }
            case ClassTypeDef.ClassElementType elementType -> {
                ClassElement element = elementType.classElement();
                List<MethodElement> methods = constructor ? List.copyOf(element.getEnclosedElements(ElementQuery.CONSTRUCTORS))
                    : element.getEnclosedElements(ElementQuery.ALL_METHODS.named(name));
                for (MethodElement method : methods) {
                    add(context, result, fromElement(method, receiver), Map.of());
                }
                return true;
            }
            case ClassTypeDef.ClassDefType classDefType -> {
                ObjectDef definition = classDefType.objectDef();
                List<String> variables = variableNames(definition);
                // A variable named alone erases to its bound, which tells `choose(T)` of a `T extends Number` apart
                // from `choose(Object)`
                Map<String, TypeDef> scope = new HashMap<>();
                classVariables(definition).forEach(variable -> scope.put(variable.name(), variable));
                for (MethodDef method : definition.getMethods()) {
                    if (method.getName().equals(name)) {
                        Map<String, TypeDef> methodScope = new HashMap<>(scope);
                        method.getTypeVariables().forEach(variable -> methodScope.put(variable.name(), variable));
                        expandChains(methodScope);
                        add(context, result, new Candidate(method, method.getParameters().stream().map(ParameterDef::getType).toList(),
                            method.getReturnType(), method.getTypeVariables(), false, definition.getName(), variables,
                            access(method.getModifiers(), definition instanceof InterfaceDef || definition instanceof AnnotationObjectDef),
                            method.getModifiers().contains(Modifier.STATIC), receiver), methodScope);
                    }
                }
                if (constructor) {
                    return true;
                }
                // The methods it inherits are invoked on it too, as a class file resolves them - of Object where it
                // extends no other class
                List<TypeDef> superTypes = new ArrayList<>(TypeHierarchy.superTypesOf(definition));
                if (definition instanceof ClassDef classDef && classDef.getSuperclass() == null) {
                    superTypes.add(TypeDef.OBJECT);
                }
                for (TypeDef superType : superTypes) {
                    if (TypeHierarchy.unwrap(superType) instanceof ClassTypeDef superClass
                        && !collect(context, declaredClass(superClass), name, receiver, result, visited, false)) {
                        return false;
                    }
                }
                return true;
            }
            default -> {
                return !own;
            }
        }
    }

    private static void add(ResolutionContext context, Map<String, Candidate> result, Candidate candidate, Map<String, TypeDef> scope) {
        // The declaration nearest the receiver overrides the others of its signature, and of two a type inherits
        // alongside - `Object value(CharSequence)` and `String value(CharSequence)` of two interfaces - the one of the
        // most specific return type is invoked (JLS 15.12.2.5)
        String signature = signature(candidate.method().getParameters().stream()
            .map(parameter -> TypeHierarchy.substituted(parameter.getType(), scope)).toList());
        Candidate existing = result.putIfAbsent(signature, candidate);
        if (existing != null && !existing.declaring().equals(candidate.declaring()) && narrowerReturn(context, candidate, existing)) {
            result.put(signature, candidate);
        }
    }

    private static boolean narrowerReturn(ResolutionContext context, Candidate candidate, Candidate other) {
        TypeDef returns = erasedDeep(candidate.returnType());
        TypeDef otherReturns = erasedDeep(other.returnType());
        return !returns.isPrimitive() && !otherReturns.isPrimitive() && !sameErasure(returns, otherReturns)
            && referenceConvertible(context, returns, otherReturns) == Tri.YES;
    }

    private static Candidate reflected(Executable executable, MethodDef method, ClassTypeDef receiver) {
        List<TypeDef.TypeVariable> variables = Arrays.stream(executable.getTypeParameters())
            .map(variable -> TypeDef.variable(variable.getName(), Arrays.stream(variable.getBounds())
                .map(TypeHierarchy::typeDefOf).toList()))
            .toList();
        List<TypeDef> parameters = Arrays.stream(executable.getGenericParameterTypes()).map(TypeHierarchy::typeDefOf).toList();
        Class<?> declaring = executable.getDeclaringClass();
        int modifiers = executable.getModifiers();
        Access access = java.lang.reflect.Modifier.isPublic(modifiers) ? Access.PUBLIC
            : java.lang.reflect.Modifier.isPrivate(modifiers) ? Access.PRIVATE
            : java.lang.reflect.Modifier.isProtected(modifiers) ? Access.PROTECTED : Access.PACKAGE;
        TypeDef returnType = executable instanceof java.lang.reflect.Method reflectedMethod
            ? TypeHierarchy.typeDefOf(reflectedMethod.getGenericReturnType()) : TypeDef.VOID;
        return new Candidate(method, parameters, returnType, variables, executable.isVarArgs(), declaring.getName(),
            Arrays.stream(declaring.getTypeParameters()).map(java.lang.reflect.TypeVariable::getName).toList(), access,
            java.lang.reflect.Modifier.isStatic(modifiers), receiver);
    }

    /**
     * A method a compiler element describes, with its generic signature: the wildcards and the bounds of its
     * variables, which its erased model does not keep.
     */
    private static Candidate fromElement(MethodElement method, ClassTypeDef receiver) {
        List<TypeDef> parameters = Arrays.stream(method.getParameters())
            .map(parameter -> TypeDef.of(parameter.getGenericType(), ignore -> null, false)).toList();
        List<TypeDef.TypeVariable> variables = new ArrayList<>();
        for (GenericPlaceholderElement placeholder : method.getDeclaredTypeVariables()) {
            variables.add(TypeDef.variable(placeholder.getVariableName(),
                placeholder.getBounds().stream().map(bound -> TypeDef.of(bound, ignore -> null, false)).toList()));
        }
        ClassElement declaring = method.getDeclaringType();
        // A member of an interface is public unless it is private (JLS 9.4)
        Access access = method.isPublic() || declaring.isInterface() && !method.isPrivate() ? Access.PUBLIC
            : method.isPrivate() ? Access.PRIVATE : method.isProtected() ? Access.PROTECTED : Access.PACKAGE;
        return new Candidate(MethodDef.of(method), parameters, TypeDef.of(method.getGenericReturnType(), ignore -> null, false),
            variables, method.isVarArgs(), declaring.getName(),
            declaring.getDeclaredGenericPlaceholders().stream().map(GenericPlaceholderElement::getVariableName).toList(), access,
            method.isStatic(), receiver);
    }

    /**
     * Who can call a generated member: one of an interface is public unless it is private (JLS 9.4), which the model
     * need not say.
     */
    private static Access access(Set<Modifier> modifiers, boolean ofInterface) {
        if (modifiers.contains(Modifier.PUBLIC) || ofInterface && !modifiers.contains(Modifier.PRIVATE)) {
            return Access.PUBLIC;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            return Access.PRIVATE;
        }
        return modifiers.contains(Modifier.PROTECTED) ? Access.PROTECTED : Access.PACKAGE;
    }

    private static List<String> variableNames(ObjectDef definition) {
        return classVariables(definition).stream().map(TypeDef.TypeVariable::name).toList();
    }

    private static List<TypeDef.TypeVariable> classVariables(ObjectDef definition) {
        return switch (definition) {
            case ClassDef classDef -> classDef.getTypeVariables();
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables();
            case RecordDef recordDef -> recordDef.getTypeVariables();
            default -> List.of();
        };
    }

    /**
     * The candidates a call chooses among, in the tiers it takes them in. Where the call is written those the caller
     * accesses. Where that is not known each but a private one - of the platform a public one first, and a
     * protected one, which a subclass alone reaches, only where no public one applies; a package-private one of the
     * platform no generated class reaches.
     */
    static List<List<Candidate>> tiers(List<Candidate> all, @Nullable Caller caller, boolean constructor) {
        if (caller != null) {
            return List.of(all.stream().filter(candidate -> accessible(candidate, caller, constructor)).toList());
        }
        List<Candidate> first = new ArrayList<>();
        List<Candidate> second = new ArrayList<>();
        for (Candidate candidate : all) {
            boolean platform = platform(candidate.declaring());
            switch (candidate.access()) {
                case PUBLIC -> {
                    first.add(candidate);
                    second.add(candidate);
                }
                case PROTECTED -> {
                    if (!platform) {
                        first.add(candidate);
                    }
                    second.add(candidate);
                }
                case PACKAGE -> {
                    if (!platform) {
                        first.add(candidate);
                        second.add(candidate);
                    }
                }
                default -> {
                    // A private member is reached by the class declaring it alone
                }
            }
        }
        return first.size() == second.size() ? List.of(first) : List.of(first, second);
    }

    /**
     * Whether the class a call is written in accesses a member (JLS 6.6), as its class file does: a private member
     * of the class itself, a package-private one of its package, and a protected one of its package or of a
     * superclass - an instance member through a receiver of the caller's class, a constructor by {@code super(...)}
     * alone, not by {@code new}.
     */
    static boolean accessible(Candidate candidate, Caller caller, boolean constructor) {
        String declaring = candidate.declaring();
        return switch (candidate.access()) {
            case PUBLIC -> true;
            case PRIVATE -> declaring.equals(caller.name());
            case PACKAGE -> packageOf(declaring).equals(caller.packageName());
            case PROTECTED -> packageOf(declaring).equals(caller.packageName())
                || isSubtype(ClassTypeDef.of(caller.definition()), declaring) == Tri.YES
                && (constructor ? caller.self() : candidate.isStatic() || caller.self() || receivedAsCaller(candidate.receiver(), caller));
        };
    }

    private static boolean receivedAsCaller(ClassTypeDef receiver, Caller caller) {
        ClassTypeDef raw = declaredClass(receiver);
        return raw.getName().equals(caller.name()) || isSubtype(raw, caller.name()) == Tri.YES;
    }

    private static boolean platform(String binaryName) {
        return PLATFORM_PACKAGES.stream().anyMatch(binaryName::startsWith);
    }

    private static String packageOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? "" : binaryName.substring(0, i);
    }

    /**
     * Whether a method returns the type the caller requested: one of the same erasure, also as the receiver binds
     * the variables of its class - `T get()` of a `Getter<String>` - a variable of the method the requested type
     * satisfies the bounds of, which the call's target infers - `<T extends Number> T create()` for an Integer - or a
     * type that converts to it in an assignment: the `int` of an `Integer`, the `CharSequence` of a `String`.
     */
    static boolean returns(ResolutionContext context, Candidate candidate, TypeDef requested) {
        TypeDef declared = candidate.returnType();
        if (sameErasure(declared, requested)) {
            return true;
        }
        TypeDef specialized = TypeHierarchy.unwrap(TypeHierarchy.substituted(declared, receiverArguments(context, candidate)));
        if (sameErasure(specialized, requested)) {
            return true;
        }
        if (!(specialized instanceof TypeDef.TypeVariable variable)) {
            return assignable(context, specialized, requested);
        }
        List<TypeDef> bounds = candidate.variables().stream().filter(own -> own.name().equals(variable.name()))
            .findFirst().map(TypeDef.TypeVariable::bounds).orElse(variable.bounds());
        TypeDef target = TypeHierarchy.unwrap(requested) instanceof TypeDef.Primitive primitive ? primitive.wrapperType() : requested;
        for (TypeDef bound : bounds) {
            if (!mentions(bound, candidate.variables()) && referenceConvertible(context, target, erasedDeep(bound)) == Tri.NO) {
                return false;
            }
        }
        return true;
    }

    /**
     * The type arguments the receiver binds the variables of the class declaring a method with - through the
     * supertypes it inherits the method from - which the method's own variables of the same name shadow.
     */
    static Map<String, TypeDef> receiverArguments(ResolutionContext context, Candidate candidate) {
        Map<String, TypeDef> result = new LinkedHashMap<>();
        ClassTypeDef inherited = inherited(candidate);
        if (inherited == null) {
            return result;
        }
        // A member of a parameterized type binds the variables of its enclosing types: `T` of an `Outer<Integer>.Inner`
        TypeHierarchy.enclosingArguments(inherited, null).forEach((name, argument) -> result.put(name,
            TypeHierarchy.unwrap(argument) instanceof TypeDef.Wildcard wildcard ? context.capture(wildcard) : argument));
        if (inherited instanceof ClassTypeDef.Parameterized parameterized
            && parameterized.typeArguments().size() == candidate.declaringVariables().size()) {
            List<List<TypeDef>> declaredBounds = TypeHierarchy.declaredBounds(parameterized);
            for (int i = 0; i < candidate.declaringVariables().size(); i++) {
                TypeDef argument = parameterized.typeArguments().get(i);
                // A wildcard is captured: a `Receiver<? extends CharSequence>` takes no `String` for its `T`
                result.put(candidate.declaringVariables().get(i),
                    TypeHierarchy.unwrap(argument) instanceof TypeDef.Wildcard wildcard ? context.capture(wildcard, boundAt(declaredBounds, i)) : argument);
            }
        }
        candidate.variables().forEach(variable -> result.remove(variable.name()));
        return result;
    }

    /**
     * The receiver as the class declaring a member, with the type arguments it inherits that class with.
     */
    private static @Nullable ClassTypeDef inherited(Candidate candidate) {
        ClassTypeDef owner = candidate.receiver();
        return declaredClass(owner).getName().equals(candidate.declaring()) ? owner
            : TypeHierarchy.asSupertype(owner, candidate.declaring(), null);
    }

    /**
     * Whether the receiver is raw for the class declaring an instance member, whose types are then erased (JLS 4.8).
     */
    static boolean rawReceiver(Candidate candidate) {
        if (candidate.isStatic() || candidate.declaringVariables().isEmpty()) {
            return false;
        }
        ClassTypeDef inherited = inherited(candidate);
        return inherited != null && !(inherited instanceof ClassTypeDef.Parameterized);
    }

    /**
     * Who can call a member.
     */
    enum Access {
        PUBLIC, PROTECTED, PACKAGE, PRIVATE
    }

    /**
     * A method a call can resolve to.
     *
     * @param method             The method, as it is invoked
     * @param parameters         Its generic parameter types
     * @param returnType         Its generic return type
     * @param variables          The variables it declares
     * @param varargs            Whether it takes variable arity
     * @param declaring          The binary name of the class declaring it
     * @param declaringVariables The variables of that class
     * @param access             Who can call it
     * @param isStatic           Whether it is static, which a raw receiver leaves generic
     * @param receiver           The type it is invoked on: the receiver's class, or the bound of a variable it is a
     *                           member of, with its type arguments
     */
    record Candidate(MethodDef method,
                             List<TypeDef> parameters,
                             TypeDef returnType,
                             List<TypeDef.TypeVariable> variables,
                             boolean varargs,
                             String declaring,
                             List<String> declaringVariables,
                             Access access,
                             boolean isStatic,
                             ClassTypeDef receiver) {
    }
}
