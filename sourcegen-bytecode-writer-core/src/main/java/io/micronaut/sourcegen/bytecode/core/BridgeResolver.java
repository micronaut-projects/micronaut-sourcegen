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
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeLookup;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves generic bridges from Sourcegen model, reflection, and annotation-processing types.
 *
 * <p>Bridge resolution is part of lowering rather than an ASM concern: the same erased inherited
 * method must be represented by every bytecode backend. The resolver intentionally ignores a
 * supertype represented only by a name because that form does not contain enough method metadata.</p>
 *
 * @since 2.2
 */
@Internal
public final class BridgeResolver {

    /**
     * The methods of Object a class can override: the walk of the hierarchy does not reach them.
     */
    private static final List<java.lang.reflect.Method> OBJECT_METHODS = java.util.Arrays.stream(Object.class.getDeclaredMethods())
        .filter(method -> {
            int modifiers = method.getModifiers();
            return !java.lang.reflect.Modifier.isStatic(modifiers) && !java.lang.reflect.Modifier.isFinal(modifiers)
                && !java.lang.reflect.Modifier.isPrivate(modifiers);
        })
        .toList();

    private BridgeResolver() {
    }

    /**
     * The bridges a declared method requires, as the finished methods a writer writes with {@link Bridge#flags()}: one
     * per inherited method that the method overrides with a different erasure, named as the method, with its access,
     * annotations and parameter names, erased as the inherited method, declaring the exceptions the inherited method
     * does or else the erasure of the method's, and delegating to the method, casting every parameter to its type. A
     * bridge is always concrete - a default method of an interface - delegating to the method even when it is abstract,
     * as the Java compiler emits: dispatch reaches the implementation through it.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @return The bridges
     * @since 2.3
     */
    public static List<Bridge> bridgesOf(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return bridgesOf(objectDef, methodDef, EnclosingScope.NONE);
    }

    /**
     * The bridges a declared method requires, as {@link #bridgesOf(ObjectDef, MethodDef)} finds them, in the enclosing
     * scope of the class being written.
     *
     * @param objectDef      The declaring definition
     * @param methodDef      The declared method
     * @param enclosingScope The enclosing scope of the class being written
     * @return The bridges
     * @since 2.3
     */
    public static List<Bridge> bridgesOf(@Nullable ObjectDef objectDef, MethodDef methodDef, EnclosingScope enclosingScope) {
        return resolve(objectDef, methodDef, enclosingScope).stream()
            .map(bridge -> bridge(objectDef, methodDef, bridge, enclosingScope)).toList();
    }

    /**
     * The bridges a class declares for the methods it inherits, as the finished methods a writer writes with
     * {@link Bridge#flags()}: an implementation inherited from a superclass satisfies an added interface through a
     * bridge of the class, delegating to the inherited method as {@link #bridgesOf} bridges delegate to a declared one.
     *
     * @param objectDef      The definition
     * @param enclosingScope The enclosing scope of the class being written
     * @return The bridges
     * @since 2.3
     */
    public static List<Bridge> inheritedBridgesOf(ObjectDef objectDef, EnclosingScope enclosingScope) {
        return resolveInherited(objectDef, enclosingScope).stream()
            .map(inherited -> bridge(objectDef, inherited.target(), inherited.bridge(), enclosingScope)).toList();
    }

    /**
     * The {@link #resolveVisibility visibility bridges} of a class, as the finished methods a writer writes with
     * {@link Bridge#flags()}: a public method a public class inherits from a superclass that is not public is declared
     * again, as javac does, with the inherited method's modifiers and signature, invoking the superclass's method - a
     * virtual call would dispatch back to the bridge.
     *
     * @param objectDef      The definition
     * @param enclosingScope The enclosing scope of the class being written
     * @return The bridges
     * @since 2.3
     */
    public static List<Bridge> visibilityBridgesOf(ObjectDef objectDef, EnclosingScope enclosingScope) {
        return resolveVisibility(objectDef, enclosingScope).stream().map(visibility -> {
            MethodDef inherited = visibility.method();
            MethodDef bridge = MethodDef.builder(inherited.getName())
                .addModifiers(inherited.getModifiers())
                .addParameters(inherited.getParameters())
                .returns(inherited.getReturnType())
                .addThrows(inherited.getThrowTypes())
                .build((aThis, parameters) -> {
                    ExpressionDef.InvokeInstanceMethod invocation = aThis.superRef().invoke(inherited, parameters);
                    return inherited.getReturnType().equals(TypeDef.VOID) ? invocation : invocation.returning();
                });
            return new Bridge(bridge, inherited);
        }).toList();
    }

    private static Bridge bridge(@Nullable ObjectDef objectDef, MethodDef target, BridgeMethod shape, EnclosingScope enclosingScope) {
        List<ParameterDef> parameters = target.getParameters();
        MethodDef.MethodDefBuilder builder = MethodDef.builder(target.getName())
            .addModifiers(target.getModifiers().stream()
                .filter(modifier -> modifier == Modifier.PUBLIC || modifier == Modifier.PROTECTED || modifier == Modifier.PRIVATE)
                .toList())
            .returns(shape.returnType())
            .addAnnotations(target.getAnnotations())
            // A bridge declares no variables: a thrown one is its erasure
            .addThrows(shape.throwTypes() != null ? shape.throwTypes() : target.getThrowTypes().stream()
                .map(thrown -> TypeUtils.erased(thrown, objectDef, target, enclosingScope)).toList());
        for (int i = 0; i < parameters.size(); i++) {
            ParameterDef parameter = parameters.get(i);
            builder.addParameter(ParameterDef.builder(parameter.getName(), shape.parameterTypes().get(i))
                .addAnnotations(parameter.getAnnotations())
                .build());
        }
        builder.addStatement((aThis, bridgeParameters) -> {
            // The invocation casts every parameter to the type of the delegate
            ExpressionDef.InvokeInstanceMethod invocation = aThis.invoke(target, bridgeParameters);
            return shape.returnType().equals(TypeDef.VOID) ? invocation : invocation.returning();
        });
        return new Bridge(builder.build(), target);
    }

    /**
     * Resolves the bridges required by a declared method.
     *
     * @param objectDef The declaring definition
     * @param methodDef The declared method
     * @return Erased inherited method shapes requiring a bridge
     */
    public static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return resolve(objectDef, methodDef, EnclosingScope.NONE);
    }

    /**
     * Resolves the bridges required by a declared method in the enclosing scope of the class being written.
     *
     * @param objectDef      The declaring definition
     * @param methodDef      The declared method
     * @param enclosingScope The enclosing scope of the class being written
     * @return Erased inherited method shapes requiring a bridge
     * @since 2.3
     */
    public static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef, MethodDef methodDef, EnclosingScope enclosingScope) {
        return resolve(objectDef, methodDef, true, enclosingScope);
    }

    /**
     * Resolves the bridges of a method.
     *
     * @param objectDef The declaring definition
     * @param methodDef The method
     * @param written   Whether the method is written with its own descriptor - a declared method, or an accessor the
     *                  writer synthesizes - which no bridge can have then
     * @param enclosingScope The enclosing scope of the class being written
     * @return Erased inherited method shapes requiring a bridge
     */
    private static List<BridgeMethod> resolve(@Nullable ObjectDef objectDef,
                                              MethodDef methodDef,
                                              boolean written,
                                              EnclosingScope enclosingScope) {
        if (objectDef == null || methodDef.isConstructor()
            || methodDef.getModifiers().contains(Modifier.STATIC)
            || methodDef.getModifiers().contains(Modifier.PRIVATE)) {
            return List.of();
        }
        // An inherited method is bridged to Object's by the class declaring it
        boolean objectMethods = written && !(objectDef instanceof InterfaceDef) && !(objectDef instanceof AnnotationObjectDef)
            && OBJECT_METHODS.stream().anyMatch(method -> method.getName().equals(methodDef.getName()));
        if (TypeHierarchy.superTypesOf(objectDef).isEmpty() && !objectMethods) {
            return List.of();
        }
        // A variable of the method, named alone, erases to the bound the method declares it with
        List<String> parameters = methodDef.getParameters().stream()
            .map(parameter -> TypeUtils.getDescriptor(parameter.getType(), objectDef, methodDef, enclosingScope)).toList();
        Declared declared = new Declared(objectDef, TypeHierarchy.declaring(objectDef), methodDef, parameters,
            TypeUtils.getDescriptor(methodDef.getReturnType(), objectDef, methodDef, enclosingScope));
        List<BridgeMethod> result = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        objectDef.getMethods().stream()
            .filter(method -> method.getName().equals(methodDef.getName()))
            .map(method -> TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope))
            .forEach(taken::add);
        if (written) {
            // A record accessor or a property accessor is written by the writer, not declared by the definition, and
            // has the descriptor of an inherited method as well as a declared one does
            taken.add(TypeUtils.getMethodDescriptor(objectDef, methodDef, enclosingScope));
        }
        // The descriptors the supertypes declare the method with: a class declaring one of Object's methods again
        // stands for Object's
        Set<String> inheritedDescriptors = new HashSet<>();
        TypeHierarchy.visitInheritedMethods(objectDef, null, (type, inherited) -> {
            if (objectMethods && inherited.name().equals(methodDef.getName())) {
                inheritedDescriptors.add(inherited.bridgeParameters().stream()
                    .map(parameter -> TypeUtils.getDescriptor(type.erase(parameter), null, EnclosingScope.NONE))
                    .collect(Collectors.joining("", "(", ")")) + TypeUtils.getDescriptor(type.erase(inherited.returnType()), null, EnclosingScope.NONE));
            }
            BridgeMethod bridge = bridgeFor(declared, type, inherited);
            if (bridge != null && taken.add(descriptorOf(bridge))) {
                result.add(bridge);
            }
            return true;
        });
        if (objectMethods) {
            // Object is no supertype the hierarchy walks, but a covariant `clone()` overrides its method all the same,
            // and javac bridges it - with the exceptions Object declares it with
            for (java.lang.reflect.Method method : OBJECT_METHODS) {
                BridgeMethod bridge = objectBridgeFor(declared, method);
                if (bridge != null && !inheritedDescriptors.contains(descriptorOf(bridge)) && taken.add(descriptorOf(bridge))) {
                    result.add(bridge);
                }
            }
        }
        return result;
    }

    @Nullable
    private static BridgeMethod objectBridgeFor(Declared declared, java.lang.reflect.Method method) {
        if (!method.getName().equals(declared.methodDef().getName())
            || method.getParameterCount() != declared.parameterDescriptors().size()) {
            return null;
        }
        List<TypeDef> parameterTypes = java.util.Arrays.stream(method.getParameterTypes()).<TypeDef>map(TypeDef::of).toList();
        List<String> parameters = parameterTypes.stream().map(type -> TypeUtils.getDescriptor(type, null, EnclosingScope.NONE)).toList();
        TypeDef returnType = TypeDef.of(method.getReturnType());
        String returnDescriptor = TypeUtils.getDescriptor(returnType, null, EnclosingScope.NONE);
        if (!parameters.equals(declared.parameterDescriptors()) || returnDescriptor.equals(declared.returnDescriptor())
            || isPrimitive(returnDescriptor) || isPrimitive(declared.returnDescriptor())) {
            return null;
        }
        return new BridgeMethod(parameterTypes, returnType,
            java.util.Arrays.stream(method.getExceptionTypes()).<TypeDef>map(TypeDef::of).toList());
    }

    /**
     * The bridges a class needs for methods it inherits rather than declares: an implementation inherited from a
     * superclass satisfies an interface the class adds - {@code String get()} of a superclass for a
     * {@code Supplier<String>} - through a bridge the class declares, as javac writes it. A bridge that the
     * superclasses already declare is not repeated.
     *
     * @param objectDef The class
     * @return The inherited methods, each with a bridge delegating to it
     * @since 2.3
     */
    public static List<InheritedBridge> resolveInherited(ObjectDef objectDef) {
        return resolveInherited(objectDef, EnclosingScope.NONE);
    }

    /**
     * The bridges a class needs for methods it inherits, as {@link #resolveInherited(ObjectDef)} finds them, in the
     * enclosing scope of the class being written.
     *
     * @param objectDef      The class
     * @param enclosingScope The enclosing scope of the class being written
     * @return The inherited methods, each with a bridge delegating to it
     * @since 2.3
     */
    public static List<InheritedBridge> resolveInherited(ObjectDef objectDef, EnclosingScope enclosingScope) {
        if (!(objectDef instanceof ClassDef classDef) || classDef.getSuperclass() == null
            || classDef.getSuperinterfaces().isEmpty()) {
            return List.of();
        }
        Set<String> declared = new HashSet<>();
        objectDef.getMethods().forEach(method -> declared.add(method.getName() + TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope)));
        Set<String> seen = new HashSet<>();
        Set<String> existing = new HashSet<>();
        List<MethodDef> inherited = new ArrayList<>();
        // The class declaring each inherited method, and its variables, which the class's supertype binds
        java.util.Map<MethodDef, java.util.Map.Entry<String, List<String>>> declarations = new java.util.IdentityHashMap<>();
        // The generic shape of a compiled method, whose erased model has no variables left to bind
        java.util.Map<MethodDef, MethodDef> generic = new java.util.IdentityHashMap<>();
        TypeDef superclass = classDef.getSuperclass();
        while (superclass != null) {
            TypeDef raw = TypeOperations.rawClassOf(superclass);
            if (raw instanceof ClassTypeDef.ClassDefType classDefType) {
                ObjectDef parent = classDefType.objectDef();
                for (MethodDef method : parent.getMethods()) {
                    String key = method.getName() + TypeUtils.getMethodDescriptor(parent, method, enclosingScope);
                    // A private method is not inherited, and does not stand for a bridge this class needs
                    if (!method.getModifiers().contains(Modifier.PRIVATE)) {
                        existing.add(key);
                    }
                    if (inheritable(method.getModifiers(), method.isConstructor()) && !declared.contains(key) && seen.add(key)) {
                        // As the superclass declares it: its variables erase to their bounds there, not in this class
                        MethodDef target = TypeUtils.inDeclaringScope(method, parent, objectDef);
                        inherited.add(target);
                        declarations.put(target, java.util.Map.entry(parent.getName(), variableNames(parent)));
                    }
                }
                superclass = parent instanceof ClassDef parentClass ? parentClass.getSuperclass() : null;
                continue;
            }
            if (raw instanceof ClassTypeDef.ClassElementType elementType) {
                // A class being compiled, which no class loader has: the compiler describes its methods
                for (io.micronaut.inject.ast.MethodElement method : elementType.classElement()
                    .getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS.onlyInstance())) {
                    String key = method.getName() + java.util.Arrays.stream(method.getParameters())
                        .map(parameter -> TypeUtils.elementDescriptor(parameter.getType()))
                        .collect(Collectors.joining("", "(", ")")) + TypeUtils.elementDescriptor(method.getReturnType());
                    if (!method.isPrivate()) {
                        existing.add(key);
                    }
                    if ((method.isPublic() || method.isProtected()) && !method.isStatic()
                        && !declared.contains(key) && seen.add(key)) {
                        // With its checked exceptions, which the bridge declares too
                        MethodDef target = MethodDef.builder(method).addThrows(java.util.Arrays.stream(method.getThrownTypes())
                            .<TypeDef>map(thrown -> TypeDef.erasure(thrown)).toList()).build();
                        inherited.add(target);
                        // Its generic shape, which the erased model loses, and the class declaring it
                        MethodDef.MethodDefBuilder shape = MethodDef.builder(method.getName())
                            .returns(TypeDef.of(method.getGenericReturnType(), ignore -> null, false));
                        io.micronaut.inject.ast.ParameterElement[] parameters = method.getParameters();
                        for (int i = 0; i < parameters.length; i++) {
                            shape.addParameter("p" + i, TypeDef.of(parameters[i].getGenericType(), ignore -> null, false));
                        }
                        method.getDeclaredTypeVariables().forEach(variable -> shape.addTypeVariable(TypeDef.variable(variable.getVariableName(),
                            variable.getBounds().stream().map(bound -> TypeDef.of(bound, ignore -> null, false)).toList())));
                        generic.put(target, shape.build());
                        io.micronaut.inject.ast.ClassElement declaringType = method.getDeclaringType();
                        declarations.put(target, java.util.Map.entry(declaringType.getName(), declaringType.getDeclaredGenericPlaceholders()
                            .stream().map(io.micronaut.inject.ast.GenericPlaceholderElement::getVariableName).toList()));
                    }
                }
                superclass = null;
                continue;
            }
            Class<?> type = raw instanceof ClassTypeDef classTypeDef ? TypeLookup.reflective().loadClass(classTypeDef.getName()) : null;
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                    String key = method.getName() + methodDescriptor(method);
                    int modifiers = method.getModifiers();
                    if (!java.lang.reflect.Modifier.isPrivate(modifiers)) {
                        existing.add(key);
                    }
                    if (!method.isSynthetic() && !java.lang.reflect.Modifier.isStatic(modifiers)
                        && (java.lang.reflect.Modifier.isPublic(modifiers) || java.lang.reflect.Modifier.isProtected(modifiers))
                        && !declared.contains(key) && seen.add(key)) {
                        // With its checked exceptions, which the bridge declares too
                        MethodDef target = MethodDef.builder(method).addThrows(java.util.Arrays.stream(method.getExceptionTypes())
                            .<TypeDef>map(TypeDef::of).toList()).build();
                        inherited.add(target);
                        MethodDef.MethodDefBuilder shape = MethodDef.builder(method.getName())
                            .returns(TypeHierarchy.typeDefOf(method.getGenericReturnType()));
                        java.lang.reflect.Type[] parameterTypes = method.getGenericParameterTypes();
                        for (int i = 0; i < parameterTypes.length; i++) {
                            shape.addParameter("p" + i, TypeHierarchy.typeDefOf(parameterTypes[i]));
                        }
                        java.util.Arrays.stream(method.getTypeParameters()).forEach(variable -> shape.addTypeVariable(
                            TypeDef.variable(variable.getName(), java.util.Arrays.stream(variable.getBounds()).map(TypeHierarchy::typeDefOf).toList())));
                        generic.put(target, shape.build());
                        declarations.put(target, java.util.Map.entry(current.getName(), java.util.Arrays.stream(current.getTypeParameters())
                            .map(java.lang.reflect.TypeVariable::getName).toList()));
                    }
                }
            }
            superclass = null;
        }
        List<InheritedBridge> result = new ArrayList<>();
        Set<String> bridged = new HashSet<>(declared);
        for (MethodDef method : inherited) {
            String own = method.getName() + TypeUtils.getMethodDescriptor(objectDef, method, enclosingScope);
            // Matched as this class inherits it: `apply(T)` of a `NumericIdentity<Integer>` is its `apply(Integer)`,
            // which `Function<Integer, Integer>` needs a bridge to
            MethodDef specialized = specialized(generic.getOrDefault(method, method), declarations.get(method), classDef.getSuperclass());
            for (BridgeMethod bridge : resolve(objectDef, specialized, false, enclosingScope)) {
                String key = method.getName() + descriptorOf(bridge);
                // A bridge with the descriptor of the method it delegates to would call itself
                if (!key.equals(own) && !existing.contains(key) && bridged.add(key)) {
                    result.add(new InheritedBridge(method, bridge));
                }
            }
        }
        return result;
    }

    /**
     * The visibility bridges of a public class, as javac writes them: each public, concrete, non-final method it
     * inherits from a superclass that is not public - {@code public String value()} of a package-private base - and
     * overrides nowhere is declared again, public, synthetic and a bridge, delegating to the superclass's. Otherwise
     * reflection could not invoke the method through the public class. A public superclass declares those of its own
     * superclasses already.
     *
     * @param objectDef      The class
     * @param enclosingScope The enclosing scope of the class being written
     * @return The inherited methods to bridge
     * @since 2.3
     */
    public static List<VisibilityBridge> resolveVisibility(ObjectDef objectDef, EnclosingScope enclosingScope) {
        if (!(objectDef instanceof ClassDef classDef) || !classDef.getModifiers().contains(Modifier.PUBLIC)
            || classDef.getSuperclass() == null) {
            return List.of();
        }
        // The methods overridden already, by name and erased parameters
        Set<String> overridden = new HashSet<>();
        for (MethodDef method : writtenMethods(objectDef)) {
            overridden.add(method.getName() + method.getParameters().stream()
                .map(parameter -> TypeUtils.getDescriptor(parameter.getType(), objectDef, method, enclosingScope))
                .collect(Collectors.joining("", "(", ")")));
        }
        List<VisibilityBridge> result = new ArrayList<>();
        TypeDef superclass = classDef.getSuperclass();
        while (superclass != null) {
            TypeDef raw = TypeOperations.rawClassOf(superclass);
            superclass = switch (raw) {
                case ClassTypeDef.ClassDefType classDefType -> visibilityOfModel(classDefType.objectDef(), overridden, result, enclosingScope);
                case ClassTypeDef.ClassElementType elementType -> {
                    visibilityOfElement(elementType.classElement(), overridden, result);
                    yield null;
                }
                case ClassTypeDef classTypeDef -> {
                    Class<?> type = classTypeDef instanceof ClassTypeDef.JavaClass javaClass ? javaClass.type() : TypeLookup.reflective().loadClass(classTypeDef.getName());
                    if (type != null) {
                        visibilityOfClass(type, overridden, result);
                    }
                    yield null;
                }
                default -> null;
            };
        }
        return result;
    }

    /**
     * The visibility bridges for the methods of a generated superclass.
     *
     * @return Its superclass, to continue with, or {@code null} where it is public or has none
     */
    @Nullable
    private static TypeDef visibilityOfModel(ObjectDef parent,
                                             Set<String> overridden,
                                             List<VisibilityBridge> result,
                                             EnclosingScope enclosingScope) {
        if (parent.getModifiers().contains(Modifier.PUBLIC)) {
            return null;
        }
        for (MethodDef method : writtenMethods(parent)) {
            if (method.isConstructor() || method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            List<TypeDef> parameters = method.getParameters().stream()
                .map(parameter -> typeOf(TypeUtils.getDescriptor(parameter.getType(), parent, method, enclosingScope))).toList();
            String key = method.getName() + parameters.stream().map(type -> TypeUtils.getDescriptor(type, null, EnclosingScope.NONE))
                .collect(Collectors.joining("", "(", ")"));
            if (overridden.add(key) && !method.isSynthetic() && visible(method.getModifiers())) {
                result.add(new VisibilityBridge(erasedMethod(method.getName(), parameters,
                    typeOf(TypeUtils.getDescriptor(method.getReturnType(), parent, method, enclosingScope)),
                    method.getThrowTypes().stream().map(thrown -> typeOf(TypeUtils.getDescriptor(thrown, parent, method, enclosingScope))).toList())));
            }
        }
        return parent instanceof ClassDef parentClass ? parentClass.getSuperclass() : null;
    }

    private static void visibilityOfClass(Class<?> type, Set<String> overridden, List<VisibilityBridge> result) {
        for (Class<?> current = type; current != null && current != Object.class
            && !java.lang.reflect.Modifier.isPublic(current.getModifiers()); current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isPrivate(modifiers)) {
                    continue;
                }
                List<TypeDef> parameters = java.util.Arrays.stream(method.getParameterTypes()).<TypeDef>map(TypeDef::of).toList();
                String key = method.getName() + parameters.stream().map(parameter -> TypeUtils.getDescriptor(parameter, null, EnclosingScope.NONE))
                    .collect(Collectors.joining("", "(", ")"));
                // A bridge of the superclass stands for the method it bridges
                if (overridden.add(key) && !method.isSynthetic() && java.lang.reflect.Modifier.isPublic(modifiers)
                    && !java.lang.reflect.Modifier.isAbstract(modifiers) && !java.lang.reflect.Modifier.isFinal(modifiers)) {
                    result.add(new VisibilityBridge(erasedMethod(method.getName(), parameters, TypeDef.of(method.getReturnType()),
                        java.util.Arrays.stream(method.getExceptionTypes()).<TypeDef>map(TypeDef::of).toList())));
                }
            }
        }
    }

    private static void visibilityOfElement(io.micronaut.inject.ast.ClassElement type, Set<String> overridden, List<VisibilityBridge> result) {
        for (io.micronaut.inject.ast.ClassElement current = type; current != null && !current.getName().equals(Object.class.getName())
            && !current.isPublic(); current = current.getSuperType().orElse(null)) {
            for (io.micronaut.inject.ast.MethodElement method : current.getEnclosedElements(
                io.micronaut.inject.ast.ElementQuery.ALL_METHODS.onlyDeclared().onlyInstance())) {
                if (method.isPrivate()) {
                    continue;
                }
                List<TypeDef> parameters = java.util.Arrays.stream(method.getParameters())
                    .map(parameter -> typeOf(TypeUtils.elementDescriptor(parameter.getType()))).toList();
                String key = method.getName() + parameters.stream().map(parameter -> TypeUtils.getDescriptor(parameter, null, EnclosingScope.NONE))
                    .collect(Collectors.joining("", "(", ")"));
                if (overridden.add(key) && !method.isSynthetic() && method.isPublic() && !method.isAbstract() && !method.isFinal()) {
                    result.add(new VisibilityBridge(erasedMethod(method.getName(), parameters,
                        typeOf(TypeUtils.elementDescriptor(method.getReturnType())),
                        java.util.Arrays.stream(method.getThrownTypes()).<TypeDef>map(TypeDef::erasure).toList())));
                }
            }
        }
    }

    private static boolean visible(Set<Modifier> modifiers) {
        return modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.ABSTRACT)
            && !modifiers.contains(Modifier.FINAL) && !modifiers.contains(Modifier.NATIVE);
    }

    private static MethodDef erasedMethod(String name, List<TypeDef> parameters, TypeDef returnType, List<TypeDef> throwTypes) {
        MethodDef.MethodDefBuilder builder = MethodDef.builder(name).addModifiers(Modifier.PUBLIC).returns(returnType)
            .addThrows(throwTypes);
        for (int i = 0; i < parameters.size(); i++) {
            builder.addParameter("arg" + i, parameters.get(i));
        }
        return builder.build();
    }

    /**
     * The methods the class file of a definition declares, the accessors a writer adds for its properties included.
     */
    private static List<MethodDef> writtenMethods(ObjectDef objectDef) {
        List<MethodDef> result = new ArrayList<>(objectDef.getMethods());
        for (io.micronaut.sourcegen.model.PropertyDef property : objectDef.getProperties()) {
            if (objectDef instanceof io.micronaut.sourcegen.model.RecordDef) {
                result.add(MethodDef.builder(property.getName()).addModifiers(Modifier.PUBLIC).returns(property.getType()).build());
            } else {
                String capitalized = io.micronaut.core.naming.NameUtils.capitalize(property.getName());
                result.add(MethodDef.builder("get" + capitalized).addModifiers(property.getModifiers())
                    .returns(property.getType()).build());
                result.add(MethodDef.builder("set" + capitalized).addModifiers(property.getModifiers())
                    .addParameter(property.getName(), property.getType()).returns(TypeDef.VOID).build());
            }
        }
        return result;
    }

    private static TypeDef typeOf(String descriptor) {
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        TypeDef component = switch (descriptor.charAt(dimensions)) {
            case 'V' -> TypeDef.VOID;
            case 'Z' -> TypeDef.Primitive.BOOLEAN;
            case 'B' -> TypeDef.Primitive.BYTE;
            case 'C' -> TypeDef.Primitive.CHAR;
            case 'S' -> TypeDef.Primitive.SHORT;
            case 'I' -> TypeDef.Primitive.INT;
            case 'J' -> TypeDef.Primitive.LONG;
            case 'F' -> TypeDef.Primitive.FLOAT;
            case 'D' -> TypeDef.Primitive.DOUBLE;
            default -> ClassTypeDef.of(descriptor.substring(dimensions + 1, descriptor.length() - 1).replace('/', '.'));
        };
        return dimensions == 0 ? component : TypeDef.array(component, dimensions);
    }

    private static MethodDef specialized(MethodDef method,
                                         java.util.Map.@Nullable Entry<String, List<String>> declaration,
                                         @Nullable TypeDef superclass) {
        if (declaration == null || declaration.getValue().isEmpty() || superclass == null
            || !(TypeHierarchy.unwrap(superclass) instanceof ClassTypeDef superType)) {
            return method;
        }
        ClassTypeDef inherited = TypeHierarchy.asSupertype(superType, declaration.getKey(), null);
        if (!(inherited instanceof ClassTypeDef.Parameterized parameterized)
            || parameterized.typeArguments().size() != declaration.getValue().size()) {
            return method;
        }
        java.util.Map<String, TypeDef> substitution = new java.util.HashMap<>();
        for (int i = 0; i < declaration.getValue().size(); i++) {
            substitution.put(declaration.getValue().get(i), parameterized.typeArguments().get(i));
        }
        // The method's own variables are not the class's
        method.getTypeVariables().forEach(variable -> substitution.remove(variable.name()));
        MethodDef.MethodDefBuilder builder = MethodDef.builder(method.getName())
            .addModifiers(method.getModifiers())
            .returns(TypeHierarchy.substituted(method.getReturnType(), substitution));
        // `<U extends T> U apply(U)` of a `Parent<Integer>` is `<U extends Integer>`
        method.getTypeVariables().forEach(variable -> builder.addTypeVariable(TypeDef.variable(variable.name(),
            variable.bounds().stream().map(bound -> TypeHierarchy.substituted(bound, substitution)).toList())));
        method.getParameters().forEach(parameter -> builder.addParameter(parameter.getName(),
            TypeHierarchy.substituted(parameter.getType(), substitution)));
        return builder.build();
    }

    private static List<String> variableNames(ObjectDef definition) {
        return switch (definition) {
            case ClassDef classDef -> classDef.getTypeVariables().stream().map(TypeDef.TypeVariable::name).toList();
            case io.micronaut.sourcegen.model.InterfaceDef interfaceDef -> interfaceDef.getTypeVariables().stream().map(TypeDef.TypeVariable::name).toList();
            default -> List.of();
        };
    }

    private static boolean inheritable(Set<Modifier> modifiers, boolean constructor) {
        return !constructor && !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE)
            && (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED));
    }

    private static String methodDescriptor(java.lang.reflect.Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            descriptor.append(TypeUtils.getDescriptor(TypeDef.of(parameter), null, EnclosingScope.NONE));
        }
        return descriptor.append(')').append(TypeUtils.getDescriptor(TypeDef.of(method.getReturnType()), null, EnclosingScope.NONE)).toString();
    }

    @Nullable
    private static BridgeMethod bridgeFor(Declared declared,
                                          TypeHierarchy.InheritedType type,
                                          TypeHierarchy.InheritedMethod inherited) {
        if (!inherited.name().equals(declared.methodDef().getName())
            || inherited.overrideParameters().size() != declared.parameterDescriptors().size()
            || inherited.finalMethod()
            || (inherited.packagePrivate() && !type.getPackageName().equals(TypeHierarchy.packageOf(declared.objectDef())))) {
            return null;
        }
        // A variable the method declares of its own is not the type's of the same name: it is erased to its bound,
        // with the type arguments substituted
        List<String> substituted = inherited.overrideParameters().stream()
            .map(parameter -> type.substitute(parameter, inherited.typeVariables()))
            .map(parameter -> TypeUtils.getDescriptor(type.erase(parameter, declared.declaringType()), null, EnclosingScope.NONE))
            .toList();
        if (!substituted.equals(declared.parameterDescriptors())) {
            return null;
        }
        TypeDef returnType = type.erase(inherited.returnType());
        String returnDescriptor = TypeUtils.getDescriptor(returnType, null, EnclosingScope.NONE);
        if (!returnDescriptor.equals(declared.returnDescriptor())
            && (isPrimitive(returnDescriptor) || isPrimitive(declared.returnDescriptor()))) {
            return null;
        }
        return new BridgeMethod(inherited.bridgeParameters().stream().map(type::erase).toList(), returnType);
    }

    private static String descriptorOf(BridgeMethod bridge) {
        return bridge.parameterTypes().stream().map(type -> TypeUtils.getDescriptor(type, null, EnclosingScope.NONE))
            .collect(Collectors.joining("", "(", ")")) + TypeUtils.getDescriptor(bridge.returnType(), null, EnclosingScope.NONE);
    }

    private static boolean isPrimitive(String descriptor) {
        return descriptor.charAt(0) != 'L' && descriptor.charAt(0) != '[';
    }

    /**
     * The erased method shape used by a bridge.
     *
     * @param parameterTypes Erased parameter types
     * @param returnType Erased return type
     * @param throwTypes The exceptions the bridge declares, or {@code null} for those of the method it delegates to
     */
    public record BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType, @Nullable List<TypeDef> throwTypes) {

        /**
         * A bridge declaring the exceptions of the method it delegates to.
         *
         * @param parameterTypes Erased parameter types
         * @param returnType Erased return type
         */
        public BridgeMethod(List<TypeDef> parameterTypes, TypeDef returnType) {
            this(parameterTypes, returnType, null);
        }
    }

    /**
     * A visibility bridge: a public method a public class inherits from a superclass that is not public, which javac
     * declares again in the class, delegating to the superclass's, so that reflection can invoke it through the class.
     *
     * @param method The inherited method, erased: the bridge has its name, descriptor and exceptions, and is public
     * @since 2.3
     */
    public record VisibilityBridge(MethodDef method) {
    }

    private record Declared(ObjectDef objectDef,
                            TypeHierarchy.InheritedType declaringType,
                            MethodDef methodDef,
                            List<String> parameterDescriptors,
                            String returnDescriptor) {
    }

    /**
     * A bridge a class declares for a method it inherits.
     *
     * @param target The inherited method the bridge delegates to
     * @param bridge The erased shape of the bridge
     * @since 2.3
     */
    public record InheritedBridge(MethodDef target, BridgeMethod bridge) {
    }

    /**
     * A finished bridge method.
     *
     * @param method The bridge, as a writer writes it with {@link #flags()}
     * @param target The method the bridge delegates to
     * @since 2.3
     */
    public record Bridge(MethodDef method, MethodDef target) {

        /**
         * @return The flags a bridge carries beyond those of its modifiers: {@code ACC_BRIDGE} and {@code ACC_SYNTHETIC}
         */
        public int flags() {
            return ModifierUtils.ACC_BRIDGE | ModifierUtils.ACC_SYNTHETIC;
        }
    }

}
