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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;

import java.lang.reflect.Executable;
import io.micronaut.sourcegen.generator.OverrideResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which method an invocation names. The bytecode binds the one of the model by its descriptor; Java selects the most
 * specific one that takes the values as the source types them, so a value is cast to the parameter of the model
 * wherever another overload would take it - and to the type the receiver binds a variable of its class with.
 *
 * @since 2.2
 */
@Internal
final class JavaOverloadRules {

    private JavaOverloadRules() {
    }

    /**
     * The parameter as the receiver sees it: a variable of the receiver's class is the type argument it is bound
     * with - the {@code E} of a {@code List<String>} is {@code String}, where the model has the erased {@code Object}.
     */
    static TypeDef receiverBound(TypeDef paramType,
                                 @Nullable TypeDef declaredType,
                                 List<TypeDef.TypeVariable> inferred,
                                 Map<String, TypeDef> receiverArguments) {
        TypeDef named = TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable ? paramType : declaredType;
        if (named == null || !(TypeHierarchy.unwrap(named) instanceof TypeDef.TypeVariable variable)
            || inferred.stream().anyMatch(own -> own.name().equals(variable.name()))) {
            return paramType;
        }
        TypeDef bound = receiverArguments.get(variable.name());
        if (bound == null) {
            return paramType;
        }
        TypeDef unwrapped = TypeHierarchy.unwrap(bound);
        // A wildcard is captured: no value can be cast to it
        return unwrapped instanceof ClassTypeDef || unwrapped instanceof TypeDef.TypeVariable || unwrapped instanceof TypeDef.Array
            ? bound : paramType;
    }

    /**
     * Whether a value is cast to the parameter so that the overload of the model is the one selected.
     */
    static boolean pinsOverload(TypeDef paramType, @Nullable TypeDef sourceType, List<TypeDef.TypeVariable> inferred) {
        TypeDef param = TypeHierarchy.unwrap(paramType);
        if (param instanceof TypeDef.TypeVariable || param instanceof TypeDef.Wildcard
            || param instanceof TypeDef.Array array && TypeHierarchy.unwrap(array.componentType()) instanceof TypeDef.TypeVariable
            || !inferred.isEmpty() && TypeHierarchy.containsVariableOtherThan(paramType, java.util.Set.of())) {
            return false;
        }
        return sourceType == null || !TypeHierarchy.erasedName(paramType).equals(TypeHierarchy.erasedName(sourceType));
    }

    /**
     * Whether the owner declares another method of the name that takes the values as the source types them.
     *
     * @param owner          The type the method is invoked on
     * @param definition     The definition of the owner, where it is generated
     * @param methodName     The method name
     * @param parameterTypes The parameter types of the method of the model
     * @param sourceTypes    The types of the values in the source, {@code null} for the {@code null} literal
     * @return true if another overload is applicable
     */
    static boolean hasApplicableOverload(@Nullable ClassTypeDef owner,
                                         @Nullable ObjectDef definition,
                                         String methodName,
                                         List<TypeDef> parameterTypes,
                                         List<@Nullable TypeDef> sourceTypes) {
        List<String> erasures = parameterTypes.stream().map(TypeHierarchy::erasedName).toList();
        List<List<@Nullable Class<?>>> candidates = new ArrayList<>();
        if (definition != null) {
            for (MethodDef method : definition.getMethods()) {
                if (method.getName().equals(methodName) && method.getParameters().size() == erasures.size()
                    && !method.getParameters().stream().map(ParameterDef::getType).map(TypeHierarchy::erasedName).toList().equals(erasures)) {
                    candidates.add(method.getParameters().stream().<@Nullable Class<?>>map(parameter -> loaded(parameter.getType())).toList());
                }
            }
        } else if (owner != null) {
            Class<?> type = loaded(owner);
            if (type == null) {
                return false;
            }
            List<Executable> executables = new ArrayList<>();
            if (MethodDef.CONSTRUCTOR.equals(methodName)) {
                executables.addAll(Arrays.asList(type.getDeclaredConstructors()));
            } else {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    executables.addAll(Arrays.asList(current.getDeclaredMethods()));
                }
                executables.addAll(Arrays.asList(type.getMethods()));
                executables.removeIf(executable -> !executable.getName().equals(methodName));
            }
            for (Executable executable : executables) {
                if (executable.getParameterCount() == erasures.size() && !executable.isSynthetic()
                    && !Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).toList().equals(erasures)) {
                    candidates.add(Arrays.<Class<?>>asList(executable.getParameterTypes()));
                }
            }
        }
        return candidates.stream().anyMatch(candidate -> {
            for (int i = 0; i < candidate.size(); i++) {
                if (!takes(candidate.get(i), sourceTypes.get(i))) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * The methods of a definition without the bridges the model declares itself: an erased method that is resolved to
     * the signature of another one, which javac writes the bridge of.
     */
    static List<MethodDef> writtenMethods(ObjectDef objectDef) {
        Set<String> declared = new HashSet<>();
        objectDef.getMethods().forEach(method -> declared.add(method.getName() + method.getParameters().stream()
            .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList()));
        return objectDef.getMethods().stream().filter(method -> {
            OverrideResolver.OverriddenMethod overridden = OverrideResolver.resolve(objectDef, method, JavaPoetNames.context());
            if (overridden == null) {
                return true;
            }
            String own = method.getName() + method.getParameters().stream()
                .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList();
            String resolved = method.getName() + overridden.parameterTypes().stream()
                .map(type -> TypeHierarchy.erasedName(type, objectDef)).toList();
            if (!own.equals(resolved)) {
                // Another method is declared with the signature this one is resolved to
                return !declared.contains(resolved);
            }
            // Of two methods of one signature - they differ in the return type - the erased one is the bridge
            return objectDef.getMethods().stream().noneMatch(other -> other != method && other.getName().equals(method.getName())
                && own.equals(other.getName() + other.getParameters().stream()
                .map(parameter -> TypeHierarchy.erasedName(parameter.getType(), objectDef)).toList())
                && OverrideResolver.resolve(objectDef, other, JavaPoetNames.context()) == null);
        }).toList();
    }

    /**
     * The variables a compiled class declares, as a raw receiver sees them: erased to their bounds.
     */
    static Map<String, TypeDef> erasedClassVariables(ClassTypeDef owner) {
        Map<String, TypeDef> erased = new java.util.HashMap<>();
        for (Class<?> type = loaded(owner); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.TypeVariable<?> variable : type.getTypeParameters()) {
                java.lang.reflect.Type bound = variable.getBounds().length == 0 ? Object.class : variable.getBounds()[0];
                while (bound instanceof java.lang.reflect.TypeVariable<?> named) {
                    bound = named.getBounds().length == 0 ? Object.class : named.getBounds()[0];
                }
                erased.putIfAbsent(variable.getName(), TypeDef.of(bound instanceof java.lang.reflect.ParameterizedType parameterized
                    ? (Class<?>) parameterized.getRawType() : bound instanceof Class<?> aClass ? aClass : Object.class));
            }
        }
        return erased;
    }

    private static boolean takes(@Nullable Class<?> parameter, @Nullable TypeDef sourceType) {
        if (parameter == null) {
            // A type that cannot be loaded is taken to fit
            return true;
        }
        if (sourceType == null) {
            return !parameter.isPrimitive();
        }
        Class<?> source = loaded(sourceType);
        if (source == null) {
            return !parameter.isPrimitive();
        }
        if (parameter.isAssignableFrom(source)) {
            return true;
        }
        Class<?> boxedParameter = ReflectionUtils.getWrapperType(parameter);
        Class<?> boxedSource = ReflectionUtils.getWrapperType(source);
        // Boxing, unboxing and the primitive widenings, none of which is told apart here
        return boxedParameter.isAssignableFrom(boxedSource)
            || Number.class.isAssignableFrom(boxedParameter) && (Number.class.isAssignableFrom(boxedSource) || boxedSource == Character.class);
    }

    @Nullable
    private static Class<?> loaded(TypeDef typeDef) {
        TypeDef unwrapped = TypeHierarchy.unwrap(typeDef);
        if (unwrapped instanceof TypeDef.Primitive primitive) {
            return primitive.clazz();
        }
        if (unwrapped instanceof TypeDef.Array) {
            return arrayOf(unwrapped);
        }
        if (unwrapped instanceof ClassTypeDef.Parameterized parameterized) {
            return loaded(parameterized.rawType());
        }
        if (unwrapped instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        if (unwrapped instanceof ClassTypeDef classTypeDef) {
            return ClassUtils.forName(classTypeDef.getName(), JavaOverloadRules.class.getClassLoader()).orElse(null);
        }
        return null;
    }

    @Nullable
    private static Class<?> arrayOf(TypeDef type) {
        TypeDef.Array array = (TypeDef.Array) type;
        Class<?> component = loaded(array.componentType());
        if (component == null) {
            return null;
        }
        return java.lang.reflect.Array.newInstance(component, new int[array.dimensions()]).getClass();
    }
}
