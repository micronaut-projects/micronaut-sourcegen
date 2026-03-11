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
     * @param values The constructor values
     * @return The new instance
     */
    @Experimental
    default ExpressionDef.NewInstance instantiate(List<? extends ExpressionDef> values) {
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
     * @param name          The method name
     * @param returningType The return type
     * @param values        The values
     * @return the invoke static method expression
     * @since 1.2
     */
    default ExpressionDef.InvokeStaticMethod invokeStatic(String name,
                                                          TypeDef returningType,
                                                          List<? extends ExpressionDef> values) {
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
                    .map(value -> TypeDef.of(value, resolvedVariableFn, erasure))
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
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            List<MethodElement> abstractMethods = classElement.getEnclosedElements(
                ElementQuery.of(MethodElement.class).onlyAbstract());
            if (abstractMethods.size() != 1) {
                throw new IllegalArgumentException("Parent of a lambda should have exactly one " +
                    "abstract method but has " + abstractMethods.size());
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
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            List<MethodDef> methods = objectDef.getMethods()
                .stream()
                .filter(v -> v.getModifiers().contains(Modifier.ABSTRACT))
                .toList();
            if (methods.size() != 1) {
                throw new IllegalArgumentException("Parent of a lambda should have exactly one " +
                    "abstract method but has " + methods.size());
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
        public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
            ClassTypeDef lambdaType = resolveTypeVariables(resolveVariableFn);
            LambdaDef lambda = rawType.getLambda(resolveVariableFn);
            return new LambdaDef(
                lambdaType,
                lambda.getMethod(),
                lambda.getImplementation()
            );
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
