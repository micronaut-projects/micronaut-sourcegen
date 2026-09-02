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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves generated classes first, then class-loaded and model-backed types, annotation-processing
 * types, classpath resources, and finally the JDK resolver.
 *
 * <p>The ClassFile API resolver is intentionally conservative for classes which do not yet exist on
 * a class loader. Keeping generated models in this resolver makes verification deterministic while
 * still allowing ordinary processor-classpath types to be read from their class files.</p>
 */
final class SourcegenClassHierarchyResolver implements ClassHierarchyResolver {

    private final Map<String, byte[]> generated;
    private final ClassHierarchyResolver models;
    private final ClassHierarchyResolver ast;
    private final ClassHierarchyResolver resources;
    private final ClassHierarchyResolver classLoading;
    private final ClassHierarchyResolver fallback;

    SourcegenClassHierarchyResolver(Map<String, byte[]> generated,
                                   Collection<ClassElement> classElements,
                                   Collection<ObjectDef> objectDefs,
                                   ClassLoader classLoader) {
        this.generated = generated;
        Set<ClassDesc> modelInterfaces = new LinkedHashSet<>();
        Map<ClassDesc, ClassDesc> modelSuperclasses = new LinkedHashMap<>();
        Set<ObjectDef> visitedModels = new LinkedHashSet<>();
        for (ObjectDef objectDef : objectDefs) {
            addObjectDef(objectDef, modelInterfaces, modelSuperclasses, visitedModels);
        }
        Set<ClassDesc> astInterfaces = new LinkedHashSet<>();
        Map<ClassDesc, ClassDesc> astSuperclasses = new LinkedHashMap<>();
        for (ClassElement classElement : classElements) {
            ClassDesc classDesc = ClassDesc.of(classElement.getName());
            if (classElement.isInterface()) {
                astInterfaces.add(classDesc);
            } else {
                ClassDesc superclass = classElement.getSuperType()
                    .map(ClassElement::getName)
                    .map(ClassDesc::of)
                    .orElse(ClassDesc.of("java.lang.Object"));
                astSuperclasses.put(classDesc, superclass);
            }
        }
        this.models = ClassHierarchyResolver.of(modelInterfaces, modelSuperclasses);
        this.ast = ClassHierarchyResolver.of(astInterfaces, astSuperclasses);
        this.resources = ClassHierarchyResolver.ofResourceParsing(classLoader);
        this.classLoading = ClassHierarchyResolver.ofClassLoading(classLoader);
        this.fallback = ClassHierarchyResolver.defaultResolver();
    }

    static Set<ClassElement> classElements(ObjectDef objectDef) {
        Set<ClassElement> result = new LinkedHashSet<>();
        collectClassElements(objectDef, result);
        return result;
    }

    private static void addObjectDef(ObjectDef objectDef,
                                     Set<ClassDesc> interfaces,
                                     Map<ClassDesc, ClassDesc> superclasses,
                                     Set<ObjectDef> visited) {
        if (!visited.add(objectDef)) {
            return;
        }
        ClassDesc name = ClassDesc.of(objectDef.getName());
        if (objectDef instanceof InterfaceDef) {
            interfaces.add(name);
        } else if (objectDef instanceof RecordDef) {
            superclasses.put(name, ClassDesc.of("java.lang.Record"));
        } else if (objectDef instanceof EnumDef) {
            superclasses.put(name, ClassDesc.of("java.lang.Enum"));
        } else {
            ClassDesc superclass = objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null
                ? ClassDesc.of(classDef.getSuperclass().getName())
                : ClassDesc.of("java.lang.Object");
            superclasses.put(name, superclass);
        }
        objectDef.getInnerTypes().forEach(inner -> addObjectDef(inner, interfaces, superclasses, visited));
        objectDef.getSuperinterfaces().forEach(type -> addObjectTypes(type, interfaces, superclasses, visited));
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            addObjectTypes(classDef.getSuperclass(), interfaces, superclasses, visited);
            classDef.getFields().forEach(field -> addObjectTypes(field.getType(), interfaces, superclasses, visited));
        }
        objectDef.getProperties().forEach(property -> addObjectTypes(property.getType(), interfaces, superclasses, visited));
        objectDef.getMethods().forEach(method -> {
            addObjectTypes(method.getReturnType(), interfaces, superclasses, visited);
            method.getParameters().forEach(parameter -> addObjectTypes(parameter.getType(), interfaces, superclasses, visited));
            method.getThrowTypes().forEach(type -> addObjectTypes(type, interfaces, superclasses, visited));
        });
    }

    private static void addObjectTypes(TypeDef type,
                                      Set<ClassDesc> interfaces,
                                      Map<ClassDesc, ClassDesc> superclasses,
                                      Set<ObjectDef> visited) {
        switch (type) {
            case ClassTypeDef.ClassDefType classDefType -> addObjectDef(
                classDefType.objectDef(), interfaces, superclasses, visited
            );
            case ClassTypeDef.Parameterized parameterized -> {
                addObjectTypes(parameterized.rawType(), interfaces, superclasses, visited);
                parameterized.typeArguments().forEach(argument ->
                    addObjectTypes(argument, interfaces, superclasses, visited));
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotated ->
                addObjectTypes(annotated.typeDef(), interfaces, superclasses, visited);
            case TypeDef.AnnotatedTypeDef annotated -> addObjectTypes(annotated.typeDef(), interfaces, superclasses, visited);
            case TypeDef.Array array -> addObjectTypes(array.componentType(), interfaces, superclasses, visited);
            case TypeDef.TypeVariable variable -> variable.bounds().forEach(bound ->
                addObjectTypes(bound, interfaces, superclasses, visited));
            case TypeDef.Wildcard wildcard -> {
                wildcard.upperBounds().forEach(bound -> addObjectTypes(bound, interfaces, superclasses, visited));
                wildcard.lowerBounds().forEach(bound -> addObjectTypes(bound, interfaces, superclasses, visited));
            }
            case ClassTypeDef _, TypeDef.Primitive _ -> {
            }
        }
    }

    private static void collectClassElements(ObjectDef objectDef, Set<ClassElement> result) {
        objectDef.getSuperinterfaces().forEach(type -> collectClassElements(type, result));
        if (objectDef instanceof ClassDef classDef && classDef.getSuperclass() != null) {
            collectClassElements(classDef.getSuperclass(), result);
            classDef.getFields().forEach(field -> collectClassElements(field.getType(), result));
            classDef.getFields().forEach(field -> field.getInitializer()
                .ifPresent(expression -> collectClassElements(expression, result)));
            if (classDef.getStaticInitializer() != null) {
                collectClassElements(classDef.getStaticInitializer(), result);
            }
        }
        objectDef.getProperties().forEach(property -> collectClassElements(property.getType(), result));
        if (objectDef instanceof EnumDef enumDef) {
            enumDef.getFields().forEach(field -> collectClassElements(field.getType(), result));
        }
        objectDef.getMethods().forEach(method -> {
            collectClassElements(method.getReturnType(), result);
            method.getParameters().forEach(parameter -> collectClassElements(parameter.getType(), result));
            method.getThrowTypes().forEach(type -> collectClassElements(type, result));
            method.getTypeVariables().forEach(variable -> variable.bounds()
                .forEach(type -> collectClassElements(type, result)));
            method.getStatements().forEach(statement -> collectClassElements(statement, result));
        });
        objectDef.getInnerTypes().forEach(inner -> collectClassElements(inner, result));
    }

    private static void collectClassElements(StatementDef statement, Set<ClassElement> result) {
        statement.nestedExpressionsStream().forEach(expression -> collectClassElements(expression, result));
    }

    private static void collectClassElements(ExpressionDef expression, Set<ClassElement> result) {
        collectClassElements(expression.type(), result);
        expression.nestedExpressionsStream().forEach(child -> collectClassElements(child, result));
    }

    private static void collectClassElements(TypeDef type, Set<ClassElement> result) {
        switch (type) {
            case ClassTypeDef.ClassElementType classElementType -> result.add(classElementType.classElement());
            case ClassTypeDef.Parameterized parameterized -> {
                collectClassElements(parameterized.rawType(), result);
                parameterized.typeArguments().forEach(argument -> collectClassElements(argument, result));
            }
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> collectClassElements(annotated.typeDef(), result);
            case TypeDef.AnnotatedTypeDef annotated -> collectClassElements(annotated.typeDef(), result);
            case TypeDef.Array array -> collectClassElements(array.componentType(), result);
            case TypeDef.TypeVariable variable -> variable.bounds().forEach(bound -> collectClassElements(bound, result));
            case TypeDef.Wildcard wildcard -> {
                wildcard.upperBounds().forEach(bound -> collectClassElements(bound, result));
                wildcard.lowerBounds().forEach(bound -> collectClassElements(bound, result));
            }
            case ClassTypeDef _, TypeDef.Primitive _ -> {
            }
        }
    }

    @Override
    public ClassHierarchyInfo getClassInfo(ClassDesc classDesc) {
        byte[] bytes = generated.get(classDesc.displayName());
        if (bytes != null) {
            ClassModel model = ClassFile.of().parse(bytes);
            return model.flags().has(java.lang.reflect.AccessFlag.INTERFACE)
                ? ClassHierarchyInfo.ofInterface()
                : ClassHierarchyInfo.ofClass(classDesc);
        }
        try {
            return classLoading.getClassInfo(classDesc);
        } catch (RuntimeException ignored) {
            try {
                return models.getClassInfo(classDesc);
            } catch (RuntimeException ignoredAst) {
                try {
                    return ast.getClassInfo(classDesc);
                } catch (RuntimeException ignoredClassLoading) {
                    try {
                        return resources.getClassInfo(classDesc);
                    } catch (RuntimeException ignoredResource) {
                        return fallback.getClassInfo(classDesc);
                    }
                }
            }
        }
    }
}
