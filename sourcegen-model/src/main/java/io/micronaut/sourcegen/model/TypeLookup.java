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
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds a type by its binary name while a file is written: the compiler's element where a context of the round is
 * given - the only thing that knows a type compiled or generated in the same round - and else the class, loaded by
 * reflection without being initialized.
 *
 * <p>A class that cannot be loaded is remembered per class loader, whose classpath does not change, so that the
 * types a generated file names are not looked for again on every question asked about them. The elements a context
 * finds are remembered for as long as the lookup is used.</p>
 *
 * @since 2.3
 */
@Internal
public final class TypeLookup {

    /**
     * The names each class loader has no class of. The names alone are held, which keep no loader alive.
     */
    private static final Map<ClassLoader, Set<String>> MISSING = Collections.synchronizedMap(new WeakHashMap<>());

    private static final TypeLookup REFLECTIVE = new TypeLookup(null, TypeLookup.class.getClassLoader());

    private final @Nullable VisitorContext context;
    private final ClassLoader classLoader;
    private final Map<String, Optional<ClassElement>> elements = new HashMap<>();

    private TypeLookup(@Nullable VisitorContext context, @Nullable ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader;
    }

    /**
     * @return The lookup of classes alone, through the class loader of the generators
     */
    public static TypeLookup reflective() {
        return REFLECTIVE;
    }

    /**
     * @param context The context of the round, or {@code null}
     * @return The lookup of the context's elements and of classes through the class loader of the generators
     */
    public static TypeLookup of(@Nullable VisitorContext context) {
        return context == null ? REFLECTIVE : new TypeLookup(context, TypeLookup.class.getClassLoader());
    }

    /**
     * @param context     The context of the round, or {@code null}
     * @param classLoader The class loader classes are loaded through
     * @return The lookup
     */
    public static TypeLookup of(@Nullable VisitorContext context, ClassLoader classLoader) {
        return new TypeLookup(context, classLoader);
    }

    /**
     * A type by its binary name: the compiler's element where the context knows it, else the loaded class.
     *
     * @param name The binary name
     * @return The type, or {@code null} where neither knows it
     */
    public @Nullable ClassTypeDef find(String name) {
        ClassElement element = classElement(name);
        if (element != null) {
            return ClassTypeDef.of(element);
        }
        Class<?> type = loadClass(name);
        return type == null || type.isPrimitive() ? null : ClassTypeDef.of(type);
    }

    /**
     * The compiler's element of a type.
     *
     * @param name The binary name
     * @return The element, or {@code null} without a context, or where it does not know the type
     */
    public @Nullable ClassElement classElement(String name) {
        VisitorContext visitorContext = context;
        if (visitorContext == null) {
            return null;
        }
        return elements.computeIfAbsent(name, visitorContext::getClassElement).orElse(null);
    }

    /**
     * Loads a class by its binary name, or the name of a primitive, without initializing it.
     *
     * @param name The name
     * @return The class, or {@code null} where the class loader has none of the name
     */
    public @Nullable Class<?> loadClass(String name) {
        Class<?> common = ClassUtils.COMMON_CLASS_MAP.get(name);
        if (common != null) {
            return common;
        }
        Set<String> missing = MISSING.computeIfAbsent(classLoader, loader -> ConcurrentHashMap.newKeySet());
        if (missing.contains(name)) {
            return null;
        }
        try {
            return Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            // A type generated in this round, or one of a library that is not on the path of the generators
            missing.add(name);
            return null;
        }
    }

    /**
     * Loads the class of a type: the class of a parameterized type, and the array class of an array.
     *
     * @param type The type
     * @return The class, or {@code null} for a type variable, a wildcard, or a class that cannot be loaded
     */
    public @Nullable Class<?> loadClass(TypeDef type) {
        TypeDef unwrapped = TypeOperations.unwrap(type);
        if (unwrapped instanceof TypeDef.Primitive primitive) {
            return primitive.clazz();
        }
        if (unwrapped instanceof TypeDef.Array array) {
            Class<?> component = loadClass(array.componentType());
            return component == null ? null : java.lang.reflect.Array.newInstance(component, new int[array.dimensions()]).getClass();
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return loadClass(parameterized.rawType());
        }
        if (unwrapped instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            return loadClass(classTypeDef.getName());
        }
        return null;
    }
}
