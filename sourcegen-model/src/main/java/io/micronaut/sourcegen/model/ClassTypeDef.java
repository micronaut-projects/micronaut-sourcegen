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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The class type definition.
 * Not-null by default.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed interface ClassTypeDef extends TypeDef {

    ClassTypeDef OBJECT = of(Object.class);

    private static int hashCode(ClassTypeDef classTypeDef) {
        return classTypeDef.getName().hashCode();
    }

    private static boolean equals(ClassTypeDef classTypeDef, Object o) {
        if (classTypeDef == o) {
            return true;
        }
        if (classTypeDef == null || o == null) {
            return false;
        }
        if (classTypeDef instanceof Parameterized parameterized1) {
            if (o instanceof Parameterized parameterized2) {
                return parameterized1.getName().equals(parameterized2.getName())
                    && parameterized1.typeArguments.equals(parameterized2.typeArguments)
                    && parameterized1.isNullable() == parameterized2.isNullable();
            }
            return false; // Avoid comparing not-parameterized and parameterized
        }
        if (o instanceof Parameterized) {
            return false; // Avoid comparing not-parameterized and parameterized
        }
        return o instanceof ClassTypeDef other && classTypeDef.getName().equals(other.getName()) && classTypeDef.isNullable() == other.isNullable();
    }

    /**
     * @return The type name
     */
    String getName();

    @Override
    default ClassTypeDef resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
        return this;
    }

    /**
     * @return The canonical type name
     * @since 1.5
     */
    default String getCanonicalName() {
        if (isInner()) {
            return getName().replace("$", ".");
        }
        return getName();
    }

    /**
     * @return The simple name
     */
    default String getSimpleName() {
        String name = getCanonicalName();
        int i = name.lastIndexOf('.');
        if (i == -1) {
            return name;
        }
        return name.substring(i + 1);
    }

    /**
     * @return The package name
     */
    default String getPackageName() {
        String name = getCanonicalName();
        int i = name.lastIndexOf('.');
        if (i == -1) {
            return "";
        }
        return name.substring(0, i);
    }

    @Override
    ClassTypeDef makeNullable();

    /**
     * @return True if the class is an enum
     * @since 1.2
     */
    default boolean isEnum() {
        return false;
    }

    /**
     * @return True if interface
     * @since 1.5
     */
    default boolean isInterface() {
        return false;
    }

    /**
     * @return True if inner
     * @since 1.5
     */
    default boolean isInner() {
        return false;
    }

    /**
     * Find the methods this type declares that a call with the given name and number of arguments could
     * target, variable arity included.
     *
     * <p>Used by the convenience overloads that would otherwise build the invoked signature from the
     * static types of the arguments, which names a method that does not exist whenever an argument is
     * statically narrower than the declared parameter.
     *
     * <p>Bridge methods are included, because their descriptors are real: a covariant override such as
     * {@code ReentrantReadWriteLock.readLock()} leaves a bridge returning the supertype, and a call
     * declaring that return type does resolve.
     *
     * <p>Only implementations that carry member information return anything - {@link #of(Class)},
     * {@link #of(ClassElement)} and a definition being generated. {@link #of(String)} has nothing to
     * resolve against and returns an empty list, which leaves the signature inferred as before.
     *
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param argumentCount The number of arguments at the call site
     * @return The candidate methods, or an empty list when they cannot be resolved
     * @since 2.2
     */
    default List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
        return List.of();
    }

    /**
     * The names of the type variables this type declares, in declaration order.
     *
     * <p>Used to line up the arguments of a {@link Parameterized} type with the variables the raw type
     * declares. Only implementations that carry member information return anything.
     *
     * @return The type variable names, or an empty list when they cannot be resolved
     * @since 2.2
     */
    default List<String> getTypeVariableNames() {
        return List.of();
    }

    /**
     * @param parameterCount The number of declared parameters
     * @param varArgs        True if the declaration has variable arity
     * @param argumentCount  The number of arguments at the call site
     * @return True if a call with that many arguments can target the declaration
     * @since 2.2
     */
    private static boolean matchesArity(int parameterCount, boolean varArgs, int argumentCount) {
        return varArgs ? argumentCount >= parameterCount - 1 : argumentCount == parameterCount;
    }

    /**
     * An interface may redeclare a public method of {@link Object} as abstract - {@code Comparator} does
     * with {@code equals} - without that making it a second abstract method for the purpose of being a
     * functional interface (JLS 9.8).
     *
     * @param name           The method name
     * @param parameterTypes The parameter type names
     * @return True if the method is a public method of {@link Object}
     * @since 2.2
     */
    private static boolean isObjectMethod(String name, List<String> parameterTypes) {
        return switch (name) {
            case "equals" -> parameterTypes.equals(List.of(Object.class.getName()));
            case "hashCode", "toString" -> parameterTypes.isEmpty();
            default -> false;
        };
    }

    /**
     * Find the method that can be represented as a lambda.
     *
     * @param resolvedTypeVariables The resolved type variables
     * @return The lambda method
     * @since 1.7
     */
    default LambdaDef getLambda(Map<String, TypeDef> resolvedTypeVariables) {
        return getLambda(resolvedTypeVariables::get);
    }

    /**
     * Find the method that can be represented as a lambda.
     *
     * @param resolveVariableFn The resolved variable function
     * @return The lambda method
     * @since 2.0
     */
    default LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
        throw new UnsupportedOperationException("ClassTypeDef: " + getName() + " doesn't support lambdas");
    }

    /**
     * Find the method that can be represented as a lambda.
     *
     * @return The lambda method
     * @since 1.7
     */
    default LambdaDef getLambda() {
        return getLambda(Map.of());
    }

    /**
     * Implement this interface with a reference to a static method, e.g. {@code MyClass::staticMethod}.
     *
     * @param lambda The interface the reference implements
     * @param method The referenced method
     * @return The method reference
     * @since 2.2
     */
    /**
     * Implement this interface with a reference to a method of the given receiver,
     * e.g. {@code myInstance::instanceMethod}.
     *
     * <p>Here {@code this} is the functional interface being implemented, as it is for
     * {@link #getLambda()}. The receiver is evaluated where the reference is created, not where the
     * interface method is called.
     *
     * <p>Parameterize the interface - {@code TypeDef.parameterized(Function.class, String.class,
     * String.class)} - so that its type variables resolve; a raw interface leaves them erased to
     * {@link Object} and the reference fails to link.
     *
     * @param instance The receiver the reference is bound to
     * @param method   The referenced method
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression methodReference(ExpressionDef instance, MethodDef method) {
        LambdaDef lambda = getLambda();
        return new InstanceMethodReferenceExpression(
            lambda.getType(), lambda.getMethod(), lambda.getImplementation(),
            receiverType(instance), instance, method);
    }

    /**
     * Implement this interface with a reference to a method of the given receiver,
     * e.g. {@code myInstance::instanceMethod}.
     *
     * @param instance The receiver the reference is bound to
     * @param method   The referenced method
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression methodReference(ExpressionDef instance, Method method) {
        return methodReference(instance, MethodDef.of(method));
    }

    /**
     * Implement this interface with a reference to a method of the given receiver,
     * e.g. {@code myInstance::instanceMethod}.
     *
     * <p>The method is resolved with {@link #findDeclaredMethods(String, int)} against the type of
     * {@code instance}, which requires it to carry member information. Use
     * {@link #methodReference(ExpressionDef, MethodDef)} when it does not.
     *
     * @param instance The receiver the reference is bound to
     * @param name     The name of the referenced method
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression methodReference(ExpressionDef instance, String name) {
        return methodReference(instance,
            MethodReferences.resolve(receiverType(instance), name, getLambda().getImplementation().getParameters().size()));
    }

    /**
     * @param instance The receiver of a method reference
     * @return The type of the receiver, which must be a class type
     */
    private static ClassTypeDef receiverType(ExpressionDef instance) {
        if (instance.type() instanceof ClassTypeDef owner) {
            return owner;
        }
        throw new IllegalArgumentException(
            "The receiver of a method reference must be of a class type, but was: " + instance.type());
    }

    /**
     * Implement this interface with a reference to a static method, e.g. {@code MyClass::staticMethod}.
     *
     * <p>Parameterize this interface - {@code TypeDef.parameterized(Function.class, String.class,
     * String.class)} - so that its type variables resolve; a raw interface leaves them erased to
     * {@link Object} and the reference fails to link.
     *
     * @param owner  The type declaring the method
     * @param method The referenced method
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression staticMethodReference(ClassTypeDef owner, MethodDef method) {
        LambdaDef lambda = getLambda();
        return new StaticMethodReferenceExpression(
            lambda.getType(), lambda.getMethod(), lambda.getImplementation(), owner, method);
    }

    /**
     * Implement this interface with a reference to a static method, e.g. {@code MyClass::staticMethod}.
     *
     * @param owner The type declaring the method
     * @param name  The name of the referenced method
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression staticMethodReference(ClassTypeDef owner, String name) {
        return staticMethodReference(owner,
            MethodReferences.resolve(owner, name, getLambda().getImplementation().getParameters().size()));
    }

    /**
     * Implement this interface with a reference to a constructor, e.g. {@code MyClass::new}.
     *
     * @param owner       The type to construct
     * @param constructor The referenced constructor
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression constructorReference(ClassTypeDef owner, MethodDef constructor) {
        LambdaDef lambda = getLambda();
        return new ConstructorMethodReferenceExpression(
            lambda.getType(), lambda.getMethod(), lambda.getImplementation(), owner, constructor);
    }

    /**
     * Implement this interface with a reference to a constructor, e.g. {@code MyClass::new}.
     *
     * <p>The constructor is resolved with {@link #findDeclaredMethods(String, int)}, which requires
     * {@code owner} to carry member information. Use {@link #constructorReference(ClassTypeDef, MethodDef)}
     * when it does not, or when more than one constructor takes the arguments of the interface.
     *
     * @param owner The type to construct
     * @return The method reference
     * @since 2.2
     */
    @Experimental
    default MethodReferenceExpression constructorReference(ClassTypeDef owner) {
        return constructorReference(owner, MethodReferences.resolve(owner, MethodDef.CONSTRUCTOR,
            getLambda().getImplementation().getParameters().size()));
    }

    /**
     * The new instance expression.
     *
     * @param values The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(ExpressionDef... values) {
        return instantiate(List.of(values));
    }

    /**
     * The new instance expression.
     *
     * <p>The constructor is resolved with {@link #findDeclaredMethods(String, int)} when this type carries
     * member information. When it does not, the signature is inferred from the static types of the
     * values, which names a constructor that does not exist if any of them is narrower than the declared
     * parameter - use {@link #instantiate(List, List)} or {@link #instantiate(Constructor, List)} then.
     *
     * @param values The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(List<? extends ExpressionDef> values) {
        Invocations.Resolved resolved = Invocations.resolve(this, MethodDef.CONSTRUCTOR, null, values);
        if (resolved != null) {
            return instantiate(resolved.parameterTypes(), resolved.values());
        }
        return instantiate(values.stream().map(ExpressionDef::type).toList(), values);
    }

    /**
     * The new instance expression.
     *
     * @param parameterTypes The constructor parameter types
     * @param values         The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(List<TypeDef> parameterTypes, ExpressionDef... values) {
        return instantiate(parameterTypes, List.of(values));
    }

    /**
     * The new instance expression.
     *
     * @param parameterTypes The constructor parameter types
     * @param values         The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(List<TypeDef> parameterTypes, List<? extends ExpressionDef> values) {
        return new ExpressionDef.NewInstance(this, parameterTypes, values);
    }

    /**
     * The new instance expression.
     *
     * @param constructor The constructor
     * @param values      The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(Constructor<?> constructor, ExpressionDef... values) {
        return instantiate(constructor, List.of(values));
    }

    /**
     * The new instance expression.
     *
     * @param constructor The constructor
     * @param values      The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(Constructor<?> constructor, List<? extends ExpressionDef> values) {
        return instantiate(Arrays.stream(constructor.getParameterTypes()).map(TypeDef::of).toList(), values);
    }

    /**
     * The new instance expression.
     *
     * @param methodElement The method element
     * @param values        The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(MethodElement methodElement, List<? extends ExpressionDef> values) {
        return instantiate(Arrays.stream(methodElement.getSuspendParameters()).map(ParameterElement::getType).map(TypeDef::erasure).toList(), values);
    }

    /**
     * Get static field.
     *
     * @param name The field name
     * @param type The field type
     * @return the get static field expression
     * @since 1.5
     */
    default VariableDef.StaticField getStaticField(String name,
                                                   TypeDef type) {
        return new VariableDef.StaticField(this, name, type);
    }

    /**
     * Get static field.
     *
     * @param field The field
     * @return the get static field expression
     * @since 1.5
     */
    default VariableDef.StaticField getStaticField(FieldDef field) {
        return getStaticField(field.getName(), field.getType());
    }

    /**
     * Get static field.
     *
     * @param field The field
     * @return the get static field expression
     * @since 1.5
     */
    default VariableDef.StaticField getStaticField(Field field) {
        return getStaticField(field.getName(), TypeDef.of(field.getType()));
    }

    /**
     * Invoke static method.
     *
     * <p>The method is resolved with {@link #findDeclaredMethods(String, int)} when this type carries member
     * information. When it does not, the signature is inferred from the static types of the values, which
     * names a method that does not exist if any of them is narrower than the declared parameter - use
     * {@link #invokeStatic(String, List, TypeDef, List)}, {@link #invokeStatic(Method, List)} or
     * {@link #invokeStatic(MethodElement, List)} then.
     *
     * @param name          The method name
     * @param returningType The return type
     * @param values        The values
     * @return the invoke static method expression
     * @since 1.2
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(String name,
                                                          TypeDef returningType,
                                                          List<? extends ExpressionDef> values) {
        Invocations.Resolved resolved = Invocations.resolve(this, name, returningType, values);
        if (resolved != null) {
            return invokeStatic(name, resolved.parameterTypes(), returningType, resolved.values());
        }
        return invokeStatic(name, values.stream().map(ExpressionDef::type).toList(), returningType, values);
    }

    /**
     * Invoke static method.
     *
     * @param name           The method name
     * @param parameterTypes The parameter types
     * @param returningType  The return type
     * @param values         The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(String name,
                                                          List<TypeDef> parameterTypes,
                                                          TypeDef returningType,
                                                          List<? extends ExpressionDef> values) {
        return new ExpressionDef.InvokeStaticMethod(this,
            MethodDef.builder(name)
                .addParameters(parameterTypes)
                .returns(returningType)
                .build(),
            values);
    }

    /**
     * Invoke static method.
     *
     * @param name          The method name
     * @param returningType The return type
     * @param values        The parameters
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(String name,
                                                          TypeDef returningType,
                                                          ExpressionDef... values) {
        return invokeStatic(name, returningType, List.of(values));
    }

    /**
     * Invoke static method.
     *
     * @param name          The method name
     * @param parameterTypes The parameter types
     * @param returningType The return type
     * @param values    The parameters
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(String name,
                                                          List<TypeDef> parameterTypes,
                                                          TypeDef returningType,
                                                          ExpressionDef... values) {
        return invokeStatic(name, parameterTypes, returningType, List.of(values));
    }

    /**
     * Invoke static method.
     *
     * @param method The method
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(MethodDef method, ExpressionDef... values) {
        return invokeStatic(method, List.of(values));
    }

    /**
     * Invoke static method.
     *
     * @param method The method
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(Method method, ExpressionDef... values) {
        return invokeStatic(method, List.of(values));
    }

    /**
     * Invoke static method.
     *
     * @param method The method
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(Method method, List<? extends ExpressionDef> values) {
        return invokeStatic(
            method.getName(),
            Arrays.stream(method.getParameters()).map(p -> TypeDef.of(p.getType())).toList(),
            TypeDef.of(method.getReturnType()),
            values);
    }

    /**
     * Invoke static method.
     *
     * @param methodElement The method element
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(MethodElement methodElement, ExpressionDef... values) {
        return invokeStatic(methodElement, List.of(values));
    }

    /**
     * Invoke static method.
     *
     * @param methodElement The method element
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(MethodElement methodElement, List<? extends ExpressionDef> values) {
        return invokeStatic(
            methodElement.getName(),
            Arrays.stream(methodElement.getSuspendParameters()).map(p -> TypeDef.erasure(p.getType())).toList(),
            methodElement.isSuspend() ? TypeDef.OBJECT : TypeDef.of(methodElement.getReturnType()),
            values
        );
    }

    /**
     * Invoke static method.
     *
     * @param method The method
     * @param values The values
     * @return the invoke static method expression
     * @since 1.5
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(MethodDef method, List<? extends ExpressionDef> values) {
        return new ExpressionDef.InvokeStaticMethod(this, method, values);
    }

    /**
     * Create a new type definition.
     *
     * @param type The class
     * @return type definition
     */
    static ClassTypeDef of(Class<?> type) {
        if (type.isPrimitive()) {
            throw new IllegalStateException("Primitive classes cannot be of type: " + ClassTypeDef.class.getName());
        }
        return new JavaClass(type, false);
    }

    /**
     * Create a new type definition.
     *
     * @param className The class name
     * @return type definition
     */
    static ClassTypeDef of(String className) {
        return of(className, false);
    }

    /**
     * Create a new type definition that is an erasure.
     * This means that no type arguments will be copied.
     *
     * @param classElement The class element
     * @return type definition
     */
    static ClassTypeDef erasure(ClassElement classElement) {
        return of(classElement, ignore -> null, true);
    }

    /**
     * Create a new type definition.
     *
     * @param className The class name
     * @param isInner   Is inner type
     * @return type definition
     * @since 1.5
     */
    static ClassTypeDef of(String className, boolean isInner) {
        return new ClassName(className, isInner, false);
    }

    /**
     * Create a new type definition.
     *
     * @param classElement The class element
     * @return type definition
     */
    static ClassTypeDef of(ClassElement classElement) {
        return of(classElement, ignore -> null, false);
    }

    /**
     * Create a new type definition.
     *
     * @param classElement The class element
     * @param resolvedTypeVariables The resolved type variables
     * @param erasure      The erasure
     * @return type definition
     * @deprecated Replaced with {@link #of(TypedElement, Function, boolean)}
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @SuppressWarnings("java:S1133")
    static ClassTypeDef of(ClassElement classElement,
                           Map<String, TypeDef> resolvedTypeVariables,
                           boolean erasure) {
        return of(classElement, resolvedTypeVariables::get, erasure);
    }

    /**
     * Create a new type definition.
     *
     * @param classElement       The class element
     * @param resolvedVariableFn The resolved variable function
     * @param erasure            The erasure
     * @return type definition
     * @since 2.0
     */
    static ClassTypeDef of(ClassElement classElement,
                           Function<String, TypeDef> resolvedVariableFn,
                           boolean erasure) {
        if (classElement.isPrimitive()) {
            throw new IllegalStateException("Primitive classes cannot be of type: " + ClassTypeDef.class.getName());
        }
        if (!classElement.getTypeArguments().isEmpty()) {
            return new Parameterized(
                new ClassElementType(classElement, classElement.isNullable()),
                classElement.getTypeArguments().values()
                    .stream()
                    // Erasure applies to the type itself; the arguments keep the generic signature, so
                    // that `Set<?>` does not become `Set<Object>`
                    .map(value -> TypeDef.of(value, resolvedVariableFn, false))
                    .toList()
            );
        }
        return new ClassElementType(classElement, classElement.isNullable());
    }

    /**
     * Create a new type definition.
     *
     * @param objectDef The object definition
     * @return type definition
     */
    static ClassTypeDef of(ObjectDef objectDef) {
        return new ClassDefType(objectDef, false);
    }

    /**
     * Define a ClassTypeDef with annotations.
     *
     * @param annotations the annotation definitions to be added
     * @return The AnnotatedClassTypeDef
     * @since 1.4
     */
    @Override
    default AnnotatedClassTypeDef annotated(AnnotationDef... annotations) {
        return annotated(List.of(annotations));
    }

    /**
     * Define a ClassTypeDef with annotations.
     *
     * @param annotations The list of the AnnotationDef
     * @return The AnnotatedClassTypeDef
     * @since 1.4
     */
    @Override
    default AnnotatedClassTypeDef annotated(List<AnnotationDef> annotations) {
        return new AnnotatedClassTypeDef(this, annotations);
    }

    /**
     * The class type.
     *
     * @param type     The type
     * @param nullable Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record JavaClass(Class<?> type, boolean nullable) implements ClassTypeDef {

        @Override
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            List<Method> abstractMethods = Arrays.stream(type.getMethods())
                .filter(JavaClass::isAbstractMethod)
                .filter(m -> !isObjectMethod(m))
                .toList();
            if (abstractMethods.size() != 1) {
                throw new IllegalArgumentException("Parent of a lambda should have exactly one " +
                    "abstract method but has " + abstractMethods.size());
            }
            Method method = abstractMethods.get(0);
            return new LambdaDef(
                ClassTypeDef.of(type).resolveTypeVariables(resolveVariableFn),
                MethodDef.of(method),
                asImplementation(method, resolveVariableFn)
            );
        }

        private static boolean isAbstractMethod(Method method) {
            int modifiers = method.getModifiers();
            return java.lang.reflect.Modifier.isAbstract(modifiers)
                && !java.lang.reflect.Modifier.isStatic(modifiers)
                && !method.isDefault()
                && !method.isBridge()
                && !method.isSynthetic();
        }

        /**
         * An interface can redeclare a public method of {@link Object} as abstract - {@code Comparator}
         * does with {@code equals} - without that making it a second abstract method for the purpose of
         * being a functional interface.
         *
         * @param method The method
         * @return True if the method is a public method of {@link Object}
         */
        private static boolean isObjectMethod(Method method) {
            try {
                Object.class.getMethod(method.getName(), method.getParameterTypes());
                return true;
            } catch (NoSuchMethodException _) {
                return false;
            }
        }

        private static MethodDef asImplementation(Method method, Function<String, @Nullable TypeDef> resolveVariableFn) {
            MethodDef.MethodDefBuilder builder = MethodDef.builder(method.getName())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(asTypeDef(method.getGenericReturnType(), method.getReturnType(), resolveVariableFn));
            java.lang.reflect.Parameter[] parameters = method.getParameters();
            java.lang.reflect.Type[] genericParameterTypes = method.getGenericParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                builder.addParameter(
                    parameters[i].getName(),
                    asTypeDef(genericParameterTypes[i], parameters[i].getType(), resolveVariableFn)
                );
            }
            return builder.build();
        }

        /**
         * Resolves a reflective type, substituting a type variable when the caller provided a value for
         * it and falling back to the erasure otherwise, so that an unresolved variable never leaks into
         * the model.
         *
         * @param genericType       The generic type
         * @param erasure           The erasure of the generic type
         * @param resolveVariableFn The resolve variable function
         * @return The type definition
         */
        private static TypeDef asTypeDef(java.lang.reflect.Type genericType,
                                         Class<?> erasure,
                                         Function<String, @Nullable TypeDef> resolveVariableFn) {
            if (genericType instanceof java.lang.reflect.TypeVariable<?> typeVariable) {
                TypeDef resolved = resolveVariableFn.apply(typeVariable.getName());
                if (resolved != null) {
                    return resolved;
                }
            }
            return TypeDef.of(erasure);
        }

        @Override
        public List<String> getTypeVariableNames() {
            return Arrays.stream(type.getTypeParameters()).map(java.lang.reflect.TypeVariable::getName).toList();
        }

        @Override
        public List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
            if (MethodDef.CONSTRUCTOR.equals(name)) {
                return Arrays.stream(type.getDeclaredConstructors())
                    .filter(c -> !c.isSynthetic())
                    .filter(c -> matchesArity(c.getParameterCount(), c.isVarArgs(), argumentCount))
                    .<MethodDef>map(c -> MethodDef.builder(c).build())
                    .toList();
            }
            // getMethods() covers the inherited public methods, getDeclaredMethods() the non-public ones
            return Stream.concat(Arrays.stream(type.getMethods()), Arrays.stream(type.getDeclaredMethods()))
                .distinct()
                // A bridge method is synthetic but its descriptor is real, so a call declaring it resolves
                .filter(m -> m.getName().equals(name) && (!m.isSynthetic() || m.isBridge()))
                .filter(m -> matchesArity(m.getParameterCount(), m.isVarArgs(), argumentCount))
                .map(MethodDef::of)
                .toList();
        }

        @Override
        public String getName() {
            return type.getName();
        }

        @Override
        public String getSimpleName() {
            return type.getSimpleName();
        }

        @Override
        public String getCanonicalName() {
            return type.getCanonicalName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new JavaClass(type, true);
        }

        @Override
        public boolean isEnum() {
            return type.isEnum();
        }

        @Override
        public boolean isInterface() {
            return type.isInterface();
        }

        @Override
        public boolean isInner() {
            return type.isMemberClass();
        }

        @Override
        public int hashCode() {
            return ClassTypeDef.hashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return ClassTypeDef.equals(this, obj);
        }
    }

    /**
     * The class name type.
     *
     * @param name The class name
     * @param isInner   Is inner
     * @param nullable  Is nullable
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record ClassName(String name, boolean isInner, boolean nullable) implements ClassTypeDef {

        public ClassName(String name) {
            this(name, false);
        }

        public ClassName(String name, boolean isInner) {
            this(name, isInner, false);
        }

        @Override
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            throw new UnsupportedOperationException("ClassTypeDef: " + name + " doesn't support lambdas" +
                " because it is defined by name only and carries no member information." +
                " Use ClassTypeDef.of(Class) or ClassTypeDef.of(ClassElement) instead.");
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isInner() {
            return isInner;
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new ClassName(name,  isInner, true);
        }

        @Override
        public int hashCode() {
            return ClassTypeDef.hashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return ClassTypeDef.equals(this, obj);
        }
    }

    /**
     * The class element type.
     *
     * @param classElement The class element
     * @param nullable     Is nullable
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record ClassElementType(ClassElement classElement, boolean nullable) implements ClassTypeDef {

        @Override
        public List<String> getTypeVariableNames() {
            return classElement.getDeclaredGenericPlaceholders().stream()
                .map(GenericPlaceholderElement::getVariableName)
                .toList();
        }

        @Override
        public List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
            List<MethodElement> methods;
            if (MethodDef.CONSTRUCTOR.equals(name)) {
                methods = List.copyOf(classElement.getEnclosedElements(ElementQuery.CONSTRUCTORS));
            } else {
                methods = classElement.getEnclosedElements(ElementQuery.ALL_METHODS.named(name));
            }
            return methods.stream()
                .filter(m -> matchesArity(m.getParameters().length, m.isVarArgs(), argumentCount))
                .map(MethodDef::of)
                .toList();
        }

        @Override
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            List<MethodElement> abstractMethods = classElement.getEnclosedElements(
                    ElementQuery.of(MethodElement.class).onlyAbstract())
                .stream()
                .filter(m -> !isObjectMethod(m.getName(), Arrays.stream(m.getParameters()).map(ParameterElement::getType).map(ClassElement::getName).toList()))
                .toList();
            if (abstractMethods.size() != 1) {
                throw new IllegalArgumentException("Parent of a lambda should have exactly one " +
                    "abstract method but has " + abstractMethods.size() + ": "
                    + abstractMethods.stream().map(MethodElement::getName).toList());
            }
            MethodElement methodElement = abstractMethods.get(0);
            MethodDef build = MethodDef.builder(methodElement, resolveVariableFn).addTypeVariables(methodElement, resolveVariableFn).build();
            return new LambdaDef(
                ClassTypeDef.of(classElement).resolveTypeVariables(resolveVariableFn),
                MethodDef.of(methodElement),
                build
            );
        }

        @Override
        public String getName() {
            return classElement.getName();
        }

        @Override
        public String getSimpleName() {
            return classElement.getSimpleName();
        }

        @Override
        public String getCanonicalName() {
            return classElement.getCanonicalName();
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new ClassElementType(classElement, true);
        }

        @Override
        public boolean isEnum() {
            return classElement.isEnum();
        }

        @Override
        public boolean isInterface() {
            return classElement.isInterface();
        }

        @Override
        public boolean isInner() {
            return classElement.isInner();
        }

        @Override
        public int hashCode() {
            return ClassTypeDef.hashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return ClassTypeDef.equals(this, obj);
        }
    }

    /**
     * The class def element type.
     *
     * @param objectDef The object def
     * @param nullable Is nullable
     * @author Denis Stepanov
     * @since 1.2
     */
    @Experimental
    record ClassDefType(ObjectDef objectDef, boolean nullable) implements ClassTypeDef {

        @Override
        public List<String> getTypeVariableNames() {
            List<TypeDef.TypeVariable> variables = switch (objectDef) {
                case ClassDef classDef -> classDef.getTypeVariables();
                case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables();
                case RecordDef recordDef -> recordDef.getTypeVariables();
                default -> List.of();
            };
            return variables.stream().map(TypeDef.TypeVariable::name).toList();
        }

        @Override
        public List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
            return objectDef.getMethods()
                .stream()
                .filter(m -> m.getName().equals(name))
                .filter(m -> matchesArity(m.getParameters().size(), false, argumentCount))
                .toList();
        }

        @Override
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            List<MethodDef> methods = objectDef.getMethods()
                .stream()
                .filter(v -> v.getModifiers().contains(Modifier.ABSTRACT))
                .filter(v -> !isObjectMethod(v.getName(), v.getParameters().stream()
                    .map(ParameterDef::getType)
                    .map(t -> t instanceof ClassTypeDef classTypeDef ? classTypeDef.getName() : t.toString())
                    .toList()))
                .toList();
            if (methods.size() != 1) {
                throw new IllegalArgumentException("Parent of a lambda should have exactly one " +
                    "abstract method but has " + methods.size() + ": "
                    + methods.stream().map(MethodDef::getName).toList());
            }
            MethodDef methodDef = methods.get(0);
            return new LambdaDef(
                ClassTypeDef.of(objectDef).resolveTypeVariables(resolveVariableFn),
                methodDef,
                methodDef.resolveTypeVariables(resolveVariableFn)
            );
        }

        @Override
        public String getName() {
            return objectDef.className.name;
        }

        @Override
        public boolean isInner() {
            return objectDef.className.isInner;
        }

        @Override
        public boolean isInterface() {
            return objectDef instanceof InterfaceDef;
        }

        @Override
        public boolean isNullable() {
            return nullable;
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new ClassDefType(objectDef, true);
        }

        @Override
        public int hashCode() {
            return ClassTypeDef.hashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return ClassTypeDef.equals(this, obj);
        }

    }

    /**
     * The parameterized type definition.
     *
     * @param rawType       The raw type definition
     * @param typeArguments The type arguments
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    record Parameterized(ClassTypeDef rawType,
                         List<TypeDef> typeArguments) implements ClassTypeDef {

        @Override
        public Parameterized resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
            return new Parameterized(rawType, typeArguments.stream().map(t -> t.resolveTypeVariables(resolveVariableFn)).toList());
        }

        @Override
        public List<String> getTypeVariableNames() {
            return rawType.getTypeVariableNames();
        }

        @Override
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            Parameterized lambdaType = resolveTypeVariables(resolveVariableFn);
            // The arguments of this type resolve the variables the raw type declares - without this the
            // implementation stays erased, and a method reference to it fails to link
            List<String> names = rawType.getTypeVariableNames();
            List<TypeDef> arguments = lambdaType.typeArguments();
            LambdaDef lambda = rawType.getLambda(name -> {
                int i = names.indexOf(name);
                if (i >= 0 && i < arguments.size()) {
                    return arguments.get(i);
                }
                return resolveVariableFn.apply(name);
            });
            return new LambdaDef(
                lambdaType,
                lambda.getMethod(),
                lambda.getImplementation()
            );
        }

        @Override
        public List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
            return rawType.findDeclaredMethods(name, argumentCount);
        }

        @Override
        public String getName() {
            return rawType.getName();
        }

        @Override
        public String getSimpleName() {
            return rawType.getSimpleName();
        }

        @Override
        public String getCanonicalName() {
            return rawType.getCanonicalName();
        }

        @Override
        public boolean isNullable() {
            return rawType.isNullable();
        }

        @Override
        public ClassTypeDef makeNullable() {
            return new Parameterized(rawType.makeNullable(), typeArguments);
        }

        @Override
        public boolean isInner() {
            return rawType.isInner();
        }

        @Override
        public boolean isInterface() {
            return rawType.isInterface();
        }

        @Override
        public int hashCode() {
            return ClassTypeDef.hashCode(this);
        }

        @Override
        public boolean equals(Object obj) {
            return ClassTypeDef.equals(this, obj);
        }
    }

    /**
     * A combined type for representing a ClassTypeDef with annotations.
     *
     * @param typeDef       The raw type definition
     * @param annotations   List of annotations to associate
     * @author Elif Kurtay
     * @since 1.4
     */
    @Experimental
    record AnnotatedClassTypeDef(ClassTypeDef typeDef,
                                 List<AnnotationDef> annotations) implements TypeDef.Annotated {

    }

}
