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
package io.micronaut.sourcegen.bytecode.tck;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads classes a backend wrote, by binary name, so that they can refer to each other and to the
 * classes of the tests that wrote them. Shared by the TCK and the tests of every backend.
 *
 * @since 2.3
 */
public final class GeneratedClassLoader extends ClassLoader {

    private final Map<String, byte[]> classes;

    /**
     * A loader whose parent is the loader of the TCK, and so of the tests.
     *
     * @param classes The class file bytes by binary name
     */
    public GeneratedClassLoader(Map<String, byte[]> classes) {
        this(GeneratedClassLoader.class.getClassLoader(), classes);
    }

    /**
     * A loader with the given parent.
     *
     * @param parent  The parent loader
     * @param classes The class file bytes by binary name
     */
    public GeneratedClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
        super(parent);
        this.classes = new LinkedHashMap<>(classes);
    }

    /**
     * Defines one class at once in a loader of its own, without asking the parent loader for a
     * class of the same name first.
     *
     * @param name  The binary name of the class
     * @param bytes The class file bytes
     * @return The defined class
     */
    public static Class<?> defineDirectly(String name, byte[] bytes) {
        return new GeneratedClassLoader(Map.of(name, bytes)).defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            return super.findClass(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
